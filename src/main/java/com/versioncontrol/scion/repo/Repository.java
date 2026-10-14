package com.versioncontrol.scion.repo;

import com.versioncontrol.scion.client.ScionClient;
import com.versioncontrol.scion.diff.Change;
import com.versioncontrol.scion.diff.Lines;
import com.versioncontrol.scion.diff.TreeDiff;
import com.versioncontrol.scion.diff.UnifiedDiff;
import com.versioncontrol.scion.history.CommitGraph;
import com.versioncontrol.scion.merge.TreeMerge;
import com.versioncontrol.scion.objects.Blob;
import com.versioncontrol.scion.objects.Commit;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;
import com.versioncontrol.scion.objects.ScionObject;
import com.versioncontrol.scion.objects.Tree;
import com.versioncontrol.scion.objects.TreeEntry;
import com.versioncontrol.scion.protocol.FetchRequest;
import com.versioncontrol.scion.protocol.FetchResponse;
import com.versioncontrol.scion.protocol.PushRequest;
import com.versioncontrol.scion.protocol.PushResponse;
import com.versioncontrol.scion.protocol.PushStatus;
import com.versioncontrol.scion.protocol.RefUpdate;
import com.versioncontrol.scion.snapshot.Snapshots;
import com.versioncontrol.scion.storage.Journal;
import com.tickloom.Tickable;
import com.tickloom.fs.FileSystem;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.MessageCodec;
import com.tickloom.util.Clock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A user's local repository: history, branches, an index and a working tree.
 *
 * <ul>
 *   <li>History and refs are durable. Every change to them is one record in a {@link Journal} on the tickloom
 *       {@link FileSystem}, and the future a command returns completes only once that record is durable.</li>
 *   <li>The working tree and the index are held in memory, like an editor's buffers. After a crash the repository
 *       reopens with them reset to the current branch's last commit.</li>
 *   <li>In-memory state changes as soon as a command runs, so commands can be issued back to back; their records
 *       become durable in the same order.</li>
 * </ul>
 *
 * Everything runs on the tick loop. The owner calls {@link #tick()} from the moment it creates the repository, and
 * ticks the file system.
 */
public class Repository implements Tickable {
    public static final String JOURNAL_FILE = "scion-local-log";
    private static final int GROUP_WAIT_TICKS = 1;

    private final Journal journal;
    private final String author;
    private final Clock clock;
    private final MessageCodec codec = new JsonMessageCodec();

    private final ObjectStore objects = new ObjectStore();
    private final CommitGraph graph = new CommitGraph(objects);
    private final Set<ObjectId> journaled = new HashSet<>();
    private final SortedMap<String, ObjectId> refs = new TreeMap<>();
    private String branch = "main";

    private SortedMap<String, byte[]> workingTree = new TreeMap<>();
    private SortedMap<String, ObjectId> index = new TreeMap<>();
    private ObjectId mergeHead;
    private final SortedSet<String> unresolved = new TreeSet<>();

    /** A repository stored on the file system. Call {@link #open()} or {@link #cloneFrom} before anything else. */
    public Repository(FileSystem fileSystem, String author, Clock clock) {
        this.journal = new Journal(fileSystem, JOURNAL_FILE, GROUP_WAIT_TICKS);
        this.author = author;
        this.clock = clock;
    }

    /** Loads the repository from its journal, or starts an empty one if there is none. */
    public TickCompletableFuture<Repository> open() {
        return journal.open().thenApply(records -> {
            records.forEach(this::replay);
            resetToHead();
            return this;
        });
    }

    /** Opens an empty repository and fills it from the server: every branch, with main checked out if it exists. */
    public TickCompletableFuture<Repository> cloneFrom(ScionClient remote) {
        return open().thenCompose(opened -> {
            if (!refs.isEmpty()) {
                throw new ScionException("cannot clone into a repository that already has history");
            }
            return fetch(remote).thenCompose(fetched -> {
                ObjectId main = refs.get(RefNames.remote("main"));
                if (main == null) {
                    return TickCompletableFuture.completed(this);
                }
                refs.put(RefNames.head("main"), main);
                resetToHead();
                return persist(List.of(), Map.of(RefNames.head("main"), main), List.of(), null)
                        .thenApply(ignored -> this);
            });
        });
    }

    @Override
    public void tick() {
        journal.tick();
    }

    // ---------- Working tree ----------

    public void writeFile(String path, String content) {
        writeFile(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public void writeFile(String path, byte[] content) {
        Snapshots.validate(path);
        workingTree.put(path, content.clone());
    }

    public Optional<String> readFile(String path) {
        byte[] bytes = workingTree.get(path);
        return bytes == null ? Optional.empty() : Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

    public void deleteFile(String path) {
        workingTree.remove(path);
    }

    public SortedSet<String> files() {
        return new TreeSet<>(workingTree.keySet());
    }

    // ---------- Index ----------

    /** Stages the working tree's version of each path; a path missing from the working tree is staged as deleted. */
    public void add(String... paths) {
        for (String path : paths) {
            byte[] content = workingTree.get(path);
            if (content == null) {
                if (!index.containsKey(path)) {
                    throw new ScionException("pathspec '" + path + "' did not match any file");
                }
                index.remove(path);
            } else {
                index.put(path, objects.put(new Blob(content)));
            }
            unresolved.remove(path);
        }
    }

    /** Stages every change in the working tree, including new and deleted files. */
    public void addAll() {
        Set<String> paths = new TreeSet<>(index.keySet());
        paths.addAll(workingTree.keySet());
        add(paths.toArray(String[]::new));
    }

    /** Deletes the file and stages the deletion. */
    public void remove(String path) {
        if (!index.containsKey(path)) {
            throw new ScionException("'" + path + "' is not tracked");
        }
        workingTree.remove(path);
        index.remove(path);
        unresolved.remove(path);
    }

    public Status status() {
        SortedMap<String, ObjectId> headFiles = headFiles();
        List<Change> staged = TreeDiff.diff(headFiles, index);
        SortedMap<String, ObjectId> workingIds = new TreeMap<>();
        workingTree.forEach((path, bytes) -> workingIds.put(path, new Blob(bytes).id()));
        List<Change> unstaged = TreeDiff.diff(index, workingIds).stream()
                .filter(change -> change.kind() != Change.Kind.ADDED)
                .toList();
        List<String> untracked = workingTree.keySet().stream().filter(p -> !index.containsKey(p)).toList();
        return new Status(branch, staged, unstaged, untracked, List.copyOf(unresolved), mergeHead != null);
    }

    // ---------- Commits ----------

    /** Records the index as a new commit on the current branch. Completes with its id once it is durable. */
    public TickCompletableFuture<ObjectId> commit(String message) {
        if (!unresolved.isEmpty()) {
            return failed(new ScionException("unresolved conflicts: " + unresolved));
        }
        ObjectId parent = headCommit();
        ObjectId tree = Snapshots.writeTree(index, objects);
        boolean unchanged = parent == null ? index.isEmpty() : tree.equals(objects.commit(parent).tree());
        if (mergeHead == null && unchanged) {
            return failed(new ScionException("nothing to commit"));
        }
        List<ObjectId> parents = new ArrayList<>();
        if (parent != null) {
            parents.add(parent);
        }
        if (mergeHead != null) {
            parents.add(mergeHead);
        }
        ObjectId commit = objects.put(new Commit(tree, parents, author, clock.now(), message));
        mergeHead = null;
        return moveBranch(branch, commit).thenApply(ignored -> commit);
    }

    public ObjectId headCommit() {
        return refs.get(RefNames.head(branch));
    }

    public String currentBranch() {
        return branch;
    }

    public List<CommitInfo> log() {
        return log(headCommit());
    }

    public List<CommitInfo> log(ObjectId tip) {
        return graph.log(tip).stream().map(id -> new CommitInfo(id, objects.commit(id))).toList();
    }

    public Commit readCommit(ObjectId id) {
        return objects.commit(id);
    }

    /** The files of a commit, by path. */
    public SortedMap<String, String> filesAt(ObjectId commit) {
        SortedMap<String, String> files = new TreeMap<>();
        Snapshots.readFiles(objects.commit(commit).tree(), objects)
                .forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    // ---------- Diff ----------

    /** Unified diff from the current commit to the working tree, tracked and untracked files alike. */
    public String diff() {
        SortedMap<String, ObjectId> workingIds = new TreeMap<>();
        workingTree.forEach((path, bytes) -> workingIds.put(path, objects.put(new Blob(bytes))));
        return formatDiff(headFiles(), workingIds);
    }

    /** Unified diff between two commits. */
    public String diff(ObjectId from, ObjectId to) {
        return formatDiff(filesOf(from), filesOf(to));
    }

    private String formatDiff(Map<String, ObjectId> before, Map<String, ObjectId> after) {
        StringBuilder out = new StringBuilder();
        for (Change change : TreeDiff.diff(before, after)) {
            List<String> oldLines = displayLines(change.oldId());
            List<String> newLines = displayLines(change.newId());
            String oldName = change.oldId() == null ? "/dev/null" : "a/" + change.path();
            String newName = change.newId() == null ? "/dev/null" : "b/" + change.path();
            out.append(UnifiedDiff.format(oldName, newName, oldLines, newLines));
        }
        return out.toString();
    }

    // Lines.split keeps a trailing "" for a final newline so merges are lossless; a diff shows lines, not that.
    private List<String> displayLines(ObjectId blob) {
        if (blob == null) {
            return List.of();
        }
        List<String> lines = Lines.split(objects.blob(blob).data());
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            return lines.subList(0, lines.size() - 1);
        }
        return lines;
    }

    // ---------- Branches ----------

    /** Local branch names. */
    public SortedSet<String> branches() {
        return namesUnder(RefNames.HEADS);
    }

    /** The server's branches as of the last fetch or push. */
    public SortedMap<String, ObjectId> remoteBranches() {
        SortedMap<String, ObjectId> remote = new TreeMap<>();
        refs.forEach((name, id) -> {
            if (name.startsWith(RefNames.REMOTES)) {
                remote.put(name.substring(RefNames.REMOTES.length()), id);
            }
        });
        return remote;
    }

    /** Creates a branch at the current commit. */
    public TickCompletableFuture<Void> createBranch(String name) {
        RefNames.requireValidBranch(name);
        if (refs.containsKey(RefNames.head(name))) {
            return failed(new ScionException("branch '" + name + "' already exists"));
        }
        ObjectId head = headCommit();
        if (head == null) {
            return failed(new ScionException("cannot branch before the first commit"));
        }
        return moveBranch(name, head);
    }

    public TickCompletableFuture<Void> deleteBranch(String name) {
        if (name.equals(branch)) {
            return failed(new ScionException("cannot delete the current branch"));
        }
        if (refs.remove(RefNames.head(name)) == null) {
            return failed(new ScionException("no branch '" + name + "'"));
        }
        return persist(List.of(), Map.of(), List.of(RefNames.head(name)), null);
    }

    /**
     * Switches to a branch, replacing the tracked files in the working tree. A branch that exists only on the server
     * is created locally from it. Refused while there are uncommitted changes.
     */
    public TickCompletableFuture<Void> checkout(String name) {
        RefNames.requireValidBranch(name);
        Status status = status();
        if (!status.isClean()) {
            return failed(new ScionException("commit or discard your changes before switching branches"));
        }
        ObjectId target = refs.containsKey(RefNames.head(name)) ? refs.get(RefNames.head(name)) : refs.get(RefNames.remote(name));
        if (target == null) {
            return failed(new ScionException("no branch '" + name + "'"));
        }
        String clobbered = untrackedFileOverwrittenBy(filesOf(target));
        if (clobbered != null) {
            return failed(new ScionException("untracked file '" + clobbered + "' would be overwritten by checkout"));
        }
        Map<String, ObjectId> setRefs = new LinkedHashMap<>();
        if (!refs.containsKey(RefNames.head(name))) {
            refs.put(RefNames.head(name), target);
            setRefs.put(RefNames.head(name), target);
        }
        branch = name;
        resetToHead();
        return persist(List.of(), setRefs, List.of(), name);
    }

    // ---------- Merge ----------

    /**
     * Merges a branch ("feature"), a remote branch ("origin/main") or a commit id into the current branch.
     * Fast-forwards when it can. A clean three-way merge is committed at once; a conflicted one leaves markers in
     * the working tree and is finished by adding the resolved files and committing.
     */
    public TickCompletableFuture<MergeOutcome> merge(String what) {
        ObjectId theirs = resolve(what);
        if (theirs == null) {
            return failed(new ScionException("cannot resolve '" + what + "'"));
        }
        if (!status().isClean()) {
            return failed(new ScionException("commit or discard your changes before merging"));
        }
        ObjectId ours = headCommit();
        if (ours != null && graph.isAncestor(theirs, ours)) {
            return TickCompletableFuture.completed(new MergeOutcome(MergeOutcome.Kind.UP_TO_DATE, ours, List.of()));
        }
        if (ours == null || graph.isAncestor(ours, theirs)) {
            String clobbered = untrackedFileOverwrittenBy(filesOf(theirs));
            if (clobbered != null) {
                return failed(new ScionException("untracked file '" + clobbered + "' would be overwritten by merge"));
            }
            return moveBranch(branch, theirs)
                    .thenApply(ignored -> new MergeOutcome(MergeOutcome.Kind.FAST_FORWARD, theirs, List.of()));
        }
        Map<String, ObjectId> baseFiles = graph.mergeBase(ours, theirs).<Map<String, ObjectId>>map(this::filesOf).orElse(Map.of());
        TreeMerge.Result result = TreeMerge.merge(baseFiles, filesOf(ours), filesOf(theirs), objects, branch, what);
        String clobbered = untrackedFileOverwrittenBy(result.files());
        if (clobbered != null) {
            return failed(new ScionException("untracked file '" + clobbered + "' would be overwritten by merge"));
        }
        if (result.isClean()) {
            ObjectId tree = Snapshots.writeTree(result.files(), objects);
            ObjectId commit = objects.put(new Commit(tree, List.of(ours, theirs), author, clock.now(),
                    "Merge " + what + " into " + branch));
            return moveBranch(branch, commit)
                    .thenApply(ignored -> new MergeOutcome(MergeOutcome.Kind.MERGED, commit, List.of()));
        }
        // Clean paths are staged; conflicted ones are in the working tree with markers until added again.
        replaceTrackedFiles(result.files());
        unresolved.addAll(result.conflicts());
        mergeHead = theirs;
        return TickCompletableFuture.completed(new MergeOutcome(MergeOutcome.Kind.CONFLICTS, null, result.conflicts()));
    }

    /** Gives up a conflicted merge and puts the working tree back to the current commit. */
    public void abortMerge() {
        if (mergeHead == null) {
            throw new ScionException("no merge in progress");
        }
        resetToHead();
    }

    public boolean isMerging() {
        return mergeHead != null;
    }

    // ---------- Remote ----------

    /**
     * Downloads what the server has that this repository lacks and updates the remote branches
     * ({@code origin/<name>}). Local branches are not touched. Completes once the result is durable.
     */
    public TickCompletableFuture<SortedMap<String, ObjectId>> fetch(ScionClient remote) {
        List<String> haves = refs.values().stream().distinct().map(ObjectId::hex).toList();
        return remote.fetch(new FetchRequest(haves)).thenCompose(response -> storeFetched(response));
    }

    private TickCompletableFuture<SortedMap<String, ObjectId>> storeFetched(FetchResponse response) {
        ObjectStore staged = new ObjectStore(objects);
        for (byte[] encoded : response.objects()) {
            staged.putEncoded(encoded);
        }
        SortedMap<String, ObjectId> remoteRefs = new TreeMap<>();
        response.refs().forEach((name, hex) -> remoteRefs.put(name, ObjectId.of(hex)));
        Set<ObjectId> missing = new CommitGraph(staged).missingObjects(remoteRefs.values());
        if (!missing.isEmpty()) {
            throw new ScionException("server sent an incomplete history; missing " + missing.iterator().next());
        }
        List<ObjectId> received = new ArrayList<>(staged.ids());
        for (ObjectId id : received) {
            objects.putEncoded(staged.encoded(id).orElseThrow());
        }
        Map<String, ObjectId> setRefs = new LinkedHashMap<>();
        remoteRefs.forEach((name, id) -> setRefs.put(RefNames.remote(name), id));
        List<String> deleted = new ArrayList<>();
        for (String name : remoteBranches().keySet()) {
            if (!remoteRefs.containsKey(name)) {
                deleted.add(RefNames.remote(name));
            }
        }
        refs.putAll(setRefs);
        deleted.forEach(refs::remove);
        return persist(received, setRefs, deleted, null).thenApply(ignored -> remoteRefs);
    }

    /** Fetch, then merge the server's version of the current branch into it. */
    public TickCompletableFuture<MergeOutcome> pull(ScionClient remote) {
        return fetch(remote).thenCompose(remoteRefs -> {
            if (!remoteRefs.containsKey(branch)) {
                return TickCompletableFuture.completed(
                        new MergeOutcome(MergeOutcome.Kind.UP_TO_DATE, headCommit(), List.of()));
            }
            return merge(RefNames.REMOTE_NAME + "/" + branch);
        });
    }

    /** Pushes the current branch. */
    public TickCompletableFuture<PushResponse> push(ScionClient remote) {
        return push(remote, branch, false);
    }

    /**
     * Pushes a local branch, expecting the server's branch to be where this repository last saw it. The server
     * rejects the push if someone else moved it since (STALE) or, unless forced, if it would not be a fast-forward.
     * On success the remote branch is updated here too.
     */
    public TickCompletableFuture<PushResponse> push(ScionClient remote, String name, boolean force) {
        ObjectId tip = refs.get(RefNames.head(name));
        if (tip == null) {
            return failed(new ScionException("no branch '" + name + "'"));
        }
        ObjectId expected = refs.get(RefNames.remote(name));
        List<byte[]> toSend = new ArrayList<>();
        for (ObjectId id : graph.objectsToSend(List.of(tip), remoteBranches().values())) {
            toSend.add(objects.encoded(id).orElseThrow());
        }
        PushRequest request = new PushRequest(toSend,
                List.of(new RefUpdate(name, ObjectId.toHex(expected), tip.hex())), force);
        return remote.push(request).thenCompose(response -> {
            // A retried push that already went through comes back STALE with the branch at our commit.
            boolean landed = response.succeeded()
                    || (response.status() == PushStatus.STALE && tip.hex().equals(response.refs().get(name)));
            if (!landed) {
                return TickCompletableFuture.completed(response);
            }
            refs.put(RefNames.remote(name), tip);
            PushResponse ok = new PushResponse(PushStatus.OK, response.detail(), response.refs());
            return persist(List.of(), Map.of(RefNames.remote(name), tip), List.of(), null).thenApply(ignored -> ok);
        });
    }

    // ---------- Internals ----------

    /** "main", "heads/main", "origin/main", "remotes/origin/main" or a full commit id. */
    public ObjectId resolve(String what) {
        if (what == null) {
            return null;
        }
        String originPrefix = RefNames.REMOTE_NAME + "/";
        for (String candidate : List.of(RefNames.head(what), what,
                what.startsWith(originPrefix) ? RefNames.remote(what.substring(originPrefix.length())) : "")) {
            ObjectId id = refs.get(candidate);
            if (id != null) {
                return id;
            }
        }
        try {
            ObjectId id = ObjectId.of(what);
            return objects.contains(id) && objects.get(id) instanceof Commit ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Moves a local branch, and the working tree with it if it is the current branch.
    private TickCompletableFuture<Void> moveBranch(String name, ObjectId commit) {
        refs.put(RefNames.head(name), commit);
        if (name.equals(branch)) {
            resetToHead();
        }
        return persist(unjournaledReachableFrom(commit), Map.of(RefNames.head(name), commit), List.of(), null);
    }

    private TickCompletableFuture<Void> persist(Collection<ObjectId> newObjects, Map<String, ObjectId> setRefs,
                                                List<String> deletedRefs, String head) {
        List<byte[]> encoded = new ArrayList<>();
        for (ObjectId id : newObjects) {
            if (journaled.add(id)) {
                encoded.add(objects.encoded(id).orElseThrow());
            }
        }
        Map<String, String> hexRefs = new LinkedHashMap<>();
        setRefs.forEach((name, id) -> hexRefs.put(name, id.hex()));
        LocalRecord record = new LocalRecord(encoded, hexRefs, List.copyOf(deletedRefs), head);
        return journal.append(codec.encode(record));
    }

    private void replay(byte[] bytes) {
        LocalRecord record = codec.decode(bytes, LocalRecord.class);
        for (byte[] encoded : record.objects()) {
            journaled.add(objects.putEncoded(encoded));
        }
        record.setRefs().forEach((name, hex) -> refs.put(name, ObjectId.of(hex)));
        record.deletedRefs().forEach(refs::remove);
        if (record.head() != null) {
            branch = record.head();
        }
    }

    // Objects reachable from the commit that are not yet in the journal. Stops at journaled objects: whatever they
    // reach was journaled with or before them.
    private List<ObjectId> unjournaledReachableFrom(ObjectId start) {
        List<ObjectId> out = new ArrayList<>();
        Set<ObjectId> seen = new HashSet<>();
        Deque<ObjectId> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            ObjectId id = stack.pop();
            if (journaled.contains(id) || !seen.add(id)) {
                continue;
            }
            out.add(id);
            ScionObject object = objects.get(id);
            if (object instanceof Commit commit) {
                stack.push(commit.tree());
                commit.parents().forEach(stack::push);
            } else if (object instanceof Tree tree) {
                tree.entries().stream().map(TreeEntry::id).forEach(stack::push);
            }
        }
        return out;
    }

    // Index and working tree back to the current commit; a merge in progress is dropped. Untracked files stay.
    private void resetToHead() {
        mergeHead = null;
        unresolved.clear();
        replaceTrackedFiles(headFiles());
    }

    // An untracked file that the target tree would replace with different content, or null.
    private String untrackedFileOverwrittenBy(Map<String, ObjectId> target) {
        for (Map.Entry<String, ObjectId> file : target.entrySet()) {
            byte[] current = workingTree.get(file.getKey());
            if (current != null && !index.containsKey(file.getKey()) && !new Blob(current).id().equals(file.getValue())) {
                return file.getKey();
            }
        }
        return null;
    }

    private void replaceTrackedFiles(SortedMap<String, ObjectId> files) {
        index.keySet().forEach(workingTree::remove);
        index = new TreeMap<>(files);
        files.forEach((path, blob) -> workingTree.put(path, objects.blob(blob).data()));
    }

    private SortedMap<String, ObjectId> headFiles() {
        ObjectId head = headCommit();
        return head == null ? new TreeMap<>() : filesOf(head);
    }

    private SortedMap<String, ObjectId> filesOf(ObjectId commit) {
        return Snapshots.readTree(objects.commit(commit).tree(), objects);
    }

    private SortedSet<String> namesUnder(String prefix) {
        SortedSet<String> names = new TreeSet<>();
        refs.keySet().stream().filter(n -> n.startsWith(prefix)).forEach(n -> names.add(n.substring(prefix.length())));
        return names;
    }

    private static <T> TickCompletableFuture<T> failed(Throwable error) {
        TickCompletableFuture<T> future = new TickCompletableFuture<>();
        future.fail(error);
        return future;
    }
}
