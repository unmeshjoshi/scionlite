package com.versioncontrol.scionlite.scn;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.PeerType;
import com.versioncontrol.scionlite.api.ScionApi;
import com.versioncontrol.scionlite.api.ScionApi.*;
import com.versioncontrol.scionlite.async.Futures;
import com.versioncontrol.scionlite.changeset.Changeset;
import com.versioncontrol.scionlite.changeset.Paths;
import com.versioncontrol.scionlite.objects.CorruptObjectException;
import com.versioncontrol.scionlite.objects.ObjectId;
import com.versioncontrol.scionlite.objects.ObjectStore;
import com.versioncontrol.scionlite.objects.ScionObject;
import com.versioncontrol.scionlite.rpc.Rpc;
import com.versioncontrol.scionlite.rpc.RpcMessageTypes;
import com.versioncontrol.scionlite.tree.TreeWalker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The Scion client. It talks only to its node's scion-daemon and does not know the tiers behind it.
 *
 * <ul>
 *   <li><b>Fetch.</b> The post: "Scion's negotiation doesn't tell the server what the client already has; instead,
 *       it operates exclusively on trees and blobs the client wants", and "Starting from a root tree, the client
 *       checks its local store for missing trees and requests them recursively, then repeats the process for
 *       blobs." scn asks for each object by id; its node's store is the daemon, which answers repeats from its
 *       cache <b>[Lite assumption]</b>.</li>
 *   <li><b>Checkouts.</b> Full, sparse ("only contain the directories the client asks for") and virtual
 *       ("materialize files on demand"). "Sparse and even virtualized checkouts don't need special support, because
 *       the client simply doesn't ask for trees it doesn't want."</li>
 *   <li><b>Working copy.</b> The post's client "lays out the working copy files on disk, monitors for filesystem
 *       changes". <b>[Lite assumption]</b> Here the working copy is kept in scn's memory, not on a disk, and edits
 *       are made through {@link #write} and {@link #delete}, so scn knows what changed without a watcher. As in the
 *       post, "The working copy is optional": {@link #createChangeset} needs none.</li>
 *   <li><b>Commit</b> creates the changeset on the server. There is nothing to push.</li>
 *   <li><b>Land</b> rebases onto the branch tip (on the server), then moves the branch by compare-and-swap, and
 *       retries if someone else moved it first. <b>[Lite assumption]</b>: the post mentions an Antique merge queue
 *       but does not describe how landing works; this loop stands in for it. The post says the team has not been
 *       able "to effectively replicate the industry standard of a global repository lock on the merge queue", and
 *       that "concurrent changes can conflict"; here too there is no lock.</li>
 * </ul>
 */
public class Scn extends ClusterClient {
    public static final int MAX_LAND_ATTEMPTS = 20;

    public enum CheckoutMode {FULL, SPARSE, VIRTUAL}

    /** Landing stopped because the change conflicts with the branch. */
    public static class LandConflictException extends RuntimeException {
        private final List<String> paths;

        public LandConflictException(List<String> paths) {
            super("conflicts in " + paths);
            this.paths = List.copyOf(paths);
        }

        public List<String> paths() {
            return paths;
        }
    }

    private final ProcessId daemon;
    private final Rpc rpc;
    private long objectRequests;

    // The working copy.
    private String head;
    private ObjectId headRoot;
    private CheckoutMode mode = CheckoutMode.FULL;
    private List<String> sparseDirectories = List.of();
    private final SortedMap<String, String> files = new TreeMap<>();
    private final SortedMap<String, String> edits = new TreeMap<>();

    public Scn(List<ProcessId> daemon, ProcessParams params) {
        super(daemon, params);
        this.daemon = daemon.get(0);
        this.rpc = new Rpc(id, PeerType.CLIENT, messageBus, messageCodec, waitingList,
                () -> idGen.generateCorrelationId("scn"));
    }

    @Override
    protected Map<MessageType, Handler> initialiseHandlers() {
        return Map.of(RpcMessageTypes.RPC_REPLY, message -> rpc.onReply(message));
    }

    private <T> TickCompletableFuture<T> call(String method, Object request, Class<T> type) {
        return rpc.call(daemon, method, request, type);
    }

    // ---- The calls every tier answers ----

    /** Ref name to changeset id. */
    public TickCompletableFuture<Map<String, String>> listRefs() {
        return call(ScionApi.LIST_REFS, new ListRefsRequest(""), RefsReply.class).thenApply(RefsReply::refs);
    }

    /** The changeset the ref points at, or null. */
    public TickCompletableFuture<String> resolveRef(String name) {
        return call(ScionApi.RESOLVE_REF, new ResolveRefRequest(name), ResolveRefReply.class)
                .thenApply(ResolveRefReply::changeset);
    }

    public TickCompletableFuture<MoveRefReply> moveRef(String name, String expected, String target) {
        return call(ScionApi.MOVE_REF, new MoveRefRequest(name, expected, target), MoveRefReply.class);
    }

    /** Creates a changeset on the server from whole-file contents. Returns its id. */
    public TickCompletableFuture<String> createChangeset(List<String> parents, Map<String, String> changedFiles,
                                                         String message) {
        SortedMap<String, String> sorted = new TreeMap<>(changedFiles);
        return create(parents, sorted, message);
    }

    private TickCompletableFuture<String> create(List<String> parents, SortedMap<String, String> changedFiles, String message) {
        List<FileContent> contents = new ArrayList<>();
        changedFiles.forEach((path, text) -> contents.add(
                new FileContent(path, text == null ? null : text.getBytes(StandardCharsets.UTF_8))));
        return call(ScionApi.CREATE_CHANGESET,
                new CreateChangesetRequest(parents, author(), clock.now(), message, contents),
                CreateChangesetReply.class)
                .thenApply(CreateChangesetReply::id);
    }

    /** The changeset, checked against its id. */
    public TickCompletableFuture<Changeset> getChangeset(String id) {
        return call(ScionApi.GET_CHANGESET, new GetChangesetRequest(id), ChangesetReply.class).thenApply(reply -> {
            Changeset changeset = Changeset.decode(reply.encoded());
            if (!changeset.id().equals(id)) {
                throw new CorruptObjectException("asked for changeset " + id + ", got " + changeset.id());
            }
            return changeset;
        });
    }

    public TickCompletableFuture<ObjectId> getRootTree(String changeset) {
        return call(ScionApi.GET_ROOT_TREE, new GetRootTreeRequest(changeset), RootTreeReply.class)
                .thenApply(reply -> ObjectId.of(reply.tree()));
    }

    /** One object, checked against its id. */
    public TickCompletableFuture<ScionObject> getObject(ObjectId id) {
        ObjectStore store = new ObjectStore();
        return load(id, store).thenApply(ignored -> store.get(id));
    }

    public TickCompletableFuture<List<String>> log(String from, int limit) {
        return call(ScionApi.LOG, new LogRequest(from, limit), LogReply.class).thenApply(LogReply::changesets);
    }

    public TickCompletableFuture<List<FileDiff>> diff(String from, String to) {
        return call(ScionApi.DIFF, new DiffRequest(from, to), DiffReply.class).thenApply(DiffReply::files);
    }

    public TickCompletableFuture<String> mergeBase(String first, String second) {
        return call(ScionApi.MERGE_BASE, new MergeBaseRequest(first, second), MergeBaseReply.class)
                .thenApply(MergeBaseReply::base);
    }

    public TickCompletableFuture<RebaseReply> rebase(String changeset, String onto) {
        return call(ScionApi.REBASE, new RebaseRequest(changeset, onto, null), RebaseReply.class);
    }

    public TickCompletableFuture<MergeReply> merge(String ours, String theirs, String message) {
        return call(ScionApi.MERGE, new MergeRequest(ours, theirs, author(), clock.now(), message), MergeReply.class);
    }

    // ---- Fetch ----

    private TickCompletableFuture<Void> load(ObjectId id, ObjectStore into) {
        objectRequests++;
        return call(ScionApi.GET_OBJECT, new GetObjectRequest(id.hex()), ObjectReply.class).thenApply(reply -> {
            if (!ObjectId.hash(reply.encoded()).equals(id)) {
                throw new CorruptObjectException("asked for " + id + ", got other content");
            }
            into.putEncoded(reply.encoded());
            return null;
        });
    }

    private TreeWalker walker() {
        return new TreeWalker(new ObjectStore(), this::load);
    }

    /**
     * The fetch protocol: from the root tree, the trees of the directories {@code descend} accepts, then the blobs
     * of the files {@code include} accepts. Returns path to content.
     */
    private TickCompletableFuture<SortedMap<String, String>> fetch(ObjectId root, Predicate<String> descend,
                                                                  Predicate<String> include) {
        TreeWalker walker = walker();
        return walker.files(root, descend).thenCompose(listing -> {
            SortedMap<String, ObjectId> wanted = new TreeMap<>();
            listing.forEach((path, blob) -> {
                if (include.test(path)) {
                    wanted.put(path, blob);
                }
            });
            return walker.ensure(wanted.values()).thenApply(ignored -> {
                SortedMap<String, String> contents = new TreeMap<>();
                wanted.forEach((path, blob) -> contents.put(path, text(walker.store(), blob)));
                return contents;
            });
        });
    }

    /** Every file of a changeset, by the fetch protocol. */
    public TickCompletableFuture<Map<String, String>> readAllFiles(String changeset) {
        return getRootTree(changeset).thenCompose(root -> fetch(root, d -> true, p -> true))
                .thenApply(Collections::unmodifiableMap);
    }

    private static String text(ObjectStore store, ObjectId blob) {
        return new String(store.blob(blob).data(), StandardCharsets.UTF_8);
    }

    // ---- The working copy ----

    /** Fetches every file of the changeset. */
    public TickCompletableFuture<Void> checkout(String changeset) {
        return checkout(changeset, CheckoutMode.FULL, List.of());
    }

    /** Fetches only the trees and files under the given directories. */
    public TickCompletableFuture<Void> checkoutSparse(String changeset, List<String> directories) {
        return checkout(changeset, CheckoutMode.SPARSE, directories);
    }

    /** Fetches nothing now; each file is fetched when it is first read. */
    public TickCompletableFuture<Void> checkoutVirtual(String changeset) {
        return checkout(changeset, CheckoutMode.VIRTUAL, List.of());
    }

    private TickCompletableFuture<Void> checkout(String changeset, CheckoutMode newMode, List<String> directories) {
        if (!edits.isEmpty()) {
            return Futures.failed(new IllegalStateException("uncommitted edits: " + edits.keySet()));
        }
        return getRootTree(changeset).thenCompose(root -> {
            TickCompletableFuture<SortedMap<String, String>> fetched = switch (newMode) {
                case FULL -> fetch(root, d -> true, p -> true);
                case SPARSE -> fetch(root,
                        d -> directories.stream().anyMatch(s -> Paths.isUnder(s, d) || Paths.isUnder(d, s)),
                        p -> directories.stream().anyMatch(s -> Paths.isUnder(p, s)));
                case VIRTUAL -> TickCompletableFuture.completed(new TreeMap<>());
            };
            return fetched.thenApply(contents -> {
                head = changeset;
                headRoot = root;
                mode = newMode;
                sparseDirectories = List.copyOf(directories);
                files.clear();
                files.putAll(contents);
                return null;
            });
        });
    }

    /** The changeset the working copy is based on; null before the first checkout or commit. */
    public String head() {
        return head;
    }

    public CheckoutMode mode() {
        return mode;
    }

    /** The files present locally, with uncommitted edits applied. A virtual checkout lists only files read so far. */
    public SortedMap<String, String> files() {
        SortedMap<String, String> view = new TreeMap<>(files);
        edits.forEach((path, text) -> {
            if (text == null) {
                view.remove(path);
            } else {
                view.put(path, text);
            }
        });
        return view;
    }

    /** Uncommitted edits: path to new content, or null for a delete. */
    public SortedMap<String, String> status() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(edits));
    }

    public void write(String path, String content) {
        checkWritable(path);
        edits.put(path, content);
    }

    public void delete(String path) {
        checkWritable(path);
        edits.put(path, null);
    }

    private void checkWritable(String path) {
        Paths.validate(path);
        if (mode == CheckoutMode.SPARSE && sparseDirectories.stream().noneMatch(s -> Paths.isUnder(path, s))) {
            throw new IllegalArgumentException(path + " is outside the sparse checkout " + sparseDirectories);
        }
    }

    /** A file's content. In a virtual checkout the first read fetches it. */
    public TickCompletableFuture<String> read(String path) {
        if (edits.containsKey(path)) {
            String text = edits.get(path);
            return text == null ? Futures.failed(new IllegalArgumentException("deleted: " + path))
                    : TickCompletableFuture.completed(text);
        }
        String known = files.get(path);
        if (known != null) {
            return TickCompletableFuture.completed(known);
        }
        if (mode != CheckoutMode.VIRTUAL || head == null) {
            return Futures.failed(new IllegalArgumentException("no such file in the working copy: " + path));
        }
        String readAt = head;
        TickCompletableFuture<ObjectId> root = headRoot != null
                ? TickCompletableFuture.completed(headRoot) : getRootTree(head);
        TreeWalker walker = walker();
        return root.thenCompose(r -> walker.lookup(r, List.of(path))).thenCompose(found -> {
            ObjectId blob = found.get(path);
            if (blob == null) {
                throw new IllegalArgumentException("no such file: " + path);
            }
            return walker.ensure(List.of(blob)).thenApply(ignored -> {
                String text = text(walker.store(), blob);
                if (readAt.equals(head)) {
                    files.put(path, text);
                }
                return text;
            });
        });
    }

    /**
     * Creates a changeset from the uncommitted edits, on the server, with the head as its parent. The working copy
     * moves to it; nothing needs pushing.
     */
    public TickCompletableFuture<String> commit(String message) {
        if (edits.isEmpty()) {
            return Futures.failed(new IllegalStateException("nothing to commit"));
        }
        SortedMap<String, String> committing = new TreeMap<>(edits);
        List<String> parents = head == null ? List.of() : List.of(head);
        return create(parents, committing, message).thenApply(id -> {
            committing.forEach((path, text) -> {
                if (text == null) {
                    files.remove(path);
                } else {
                    files.put(path, text);
                }
                if (java.util.Objects.equals(edits.get(path), text)) {
                    edits.remove(path);
                }
            });
            head = id;
            headRoot = null;
            return id;
        });
    }

    private record LandAttempt(int attempts, String landed, List<String> conflicts) {
        boolean finished() {
            return landed != null || conflicts != null;
        }
    }

    /** A compare-and-swap of a branch to {@code target}, the rebased {@code change}, whose outcome is unknown. */
    private record Unconfirmed(String change, String target) {
    }

    /** Kept across calls to {@link #land} until one succeeds. */
    private final List<Unconfirmed> unconfirmed = new ArrayList<>();

    /**
     * Lands the head on {@code branch}: rebase onto the branch tip (on the server), then compare-and-swap the
     * branch from that tip to the rebased changeset. If another landing moved the branch first, start again from
     * the new tip. A conflict stops landing with {@link LandConflictException}. Every step is safe to repeat, so a
     * step that times out is retried too. On success the working copy moves to the landed changeset.
     */
    public TickCompletableFuture<String> land(String branch) {
        if (!edits.isEmpty()) {
            return Futures.failed(new IllegalStateException("commit before landing: " + edits.keySet()));
        }
        if (head == null) {
            return Futures.failed(new IllegalStateException("nothing to land"));
        }
        return landChangeset(branch, head).thenCompose(landed -> {
            head = landed;   // landed, even if refreshing the working copy below fails
            headRoot = null;
            return checkout(landed, mode, sparseDirectories).thenApply(ignored -> landed);
        });
    }

    /**
     * Lands {@code change} on {@code branch} without a working copy, entirely through server calls: the post says
     * "many write workloads can also skip the checkout and function entirely via API". Same steps as {@link #land}.
     */
    public TickCompletableFuture<String> landChangeset(String branch, String change) {
        return Futures.loop(new LandAttempt(0, null, null), LandAttempt::finished, attempt -> {
            if (attempt.attempts() >= MAX_LAND_ATTEMPTS) {
                throw new IllegalStateException("could not land on " + branch + " after " + attempt.attempts()
                        + " attempts");
            }
            TickCompletableFuture<LandAttempt> next = new TickCompletableFuture<>();
            landOnce(branch, change, attempt.attempts() + 1).whenComplete((result, error) -> {
                next.complete(error == null ? result : new LandAttempt(attempt.attempts() + 1, null, null));
            });
            return next;
        }).thenApply(result -> {
            if (result.conflicts() != null) {
                throw new LandConflictException(result.conflicts());
            }
            unconfirmed.clear();
            return result.landed();
        });
    }

    /**
     * One attempt. A compare-and-swap whose reply was lost, in this call or an earlier one, may have moved the
     * branch after all. So an attempt first looks for the newest such target that is in the branch: if it was this
     * change, the change has landed; otherwise only the changesets made after it are rebased. Rebasing them all
     * again onto a branch holding newer versions of them would report false conflicts.
     */
    private TickCompletableFuture<LandAttempt> landOnce(String branch, String change, int attempts) {
        return resolveRef(branch).thenCompose(tip -> newestLanded(tip).thenCompose(landed -> {
            if (landed != null && landed.change().equals(change)) {
                return TickCompletableFuture.completed(new LandAttempt(attempts, landed.target(), null));
            }
            String since = landed == null ? null : landed.change();
            TickCompletableFuture<RebaseReply> rebased = tip == null
                    ? TickCompletableFuture.completed(new RebaseReply(change, List.of()))
                    : call(ScionApi.REBASE, new RebaseRequest(change, tip, since), RebaseReply.class);
            return rebased.thenCompose(reply -> {
                if (!reply.conflicts().isEmpty()) {
                    return TickCompletableFuture.completed(new LandAttempt(attempts, null, reply.conflicts()));
                }
                String target = reply.changeset();
                unconfirmed.add(new Unconfirmed(change, target));
                return moveRef(branch, tip, target).thenApply(moved ->
                        moved.swapped() || target.equals(moved.current())
                                ? new LandAttempt(attempts, target, null)
                                : new LandAttempt(attempts, null, null));
            });
        }));
    }

    /** The newest unconfirmed landing whose target is {@code tip} or an ancestor of it, or null. */
    private TickCompletableFuture<Unconfirmed> newestLanded(String tip) {
        if (tip == null || unconfirmed.isEmpty()) {
            return TickCompletableFuture.completed(null);
        }
        List<Unconfirmed> newestFirst = new ArrayList<>(unconfirmed);
        Collections.reverse(newestFirst);
        record Search(int index, Unconfirmed found) {
        }
        return Futures.loop(new Search(0, null), s -> s.found() != null || s.index() == newestFirst.size(),
                s -> {
                    Unconfirmed candidate = newestFirst.get(s.index());
                    return mergeBase(candidate.target(), tip).thenApply(base -> new Search(s.index() + 1,
                            candidate.target().equals(base) ? candidate : null));
                })
                .thenApply(Search::found);
    }

    private String author() {
        return id.name();
    }

    /** Objects this client asked its daemon for. */
    public long objectRequests() {
        return objectRequests;
    }
}
