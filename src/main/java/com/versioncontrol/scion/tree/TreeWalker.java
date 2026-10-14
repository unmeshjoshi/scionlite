package com.versioncontrol.scion.tree;

import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scion.async.Futures;
import com.versioncontrol.scion.changeset.Paths;
import com.versioncontrol.scion.diff.Change;
import com.versioncontrol.scion.objects.EntryKind;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;
import com.versioncontrol.scion.objects.Tree;
import com.versioncontrol.scion.objects.TreeEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Walks trees one level at a time, loading only the trees it needs and each level's trees in parallel. Used by
 * servers (objects from cloud storage) and by scn (objects from its daemon).
 */
public class TreeWalker {
    private final ObjectStore store;
    private final ObjectSource source;

    public TreeWalker(ObjectStore store, ObjectSource source) {
        this.store = store;
        this.source = source;
    }

    public ObjectStore store() {
        return store;
    }

    /** Loads the objects not already in the store. */
    public TickCompletableFuture<Void> ensure(Collection<ObjectId> ids) {
        List<TickCompletableFuture<Void>> loads = new ArrayList<>();
        new TreeSet<>(ids).forEach(id -> {
            if (!store.contains(id)) {
                loads.add(source.load(id, store));
            }
        });
        return Futures.allOf(loads).thenApply(ignored -> null);
    }

    /**
     * The files under {@code root}, descending only into directories {@code descend} accepts. Only trees are loaded.
     */
    public TickCompletableFuture<SortedMap<String, ObjectId>> files(ObjectId root, Predicate<String> descend) {
        SortedMap<String, ObjectId> files = new TreeMap<>();
        return walk(root, descend, (path, entry) -> files.put(path, entry.id())).thenApply(ignored -> files);
    }

    /** Loads the trees of the given directories (those that exist) and every directory above them. */
    public TickCompletableFuture<Void> loadDirectories(ObjectId root, Set<String> directories) {
        Set<String> needed = new TreeSet<>();
        directories.forEach(d -> needed.addAll(TreeEdit.directoriesOn(List.of(d + "/x"))));
        return walk(root, needed::contains, (path, entry) -> {
        });
    }

    /** The blob at each path, for those of the paths that are files under {@code root}. */
    public TickCompletableFuture<Map<String, ObjectId>> lookup(ObjectId root, Collection<String> paths) {
        Set<String> wanted = new TreeSet<>(paths);
        Set<String> directories = TreeEdit.directoriesOn(wanted);
        Map<String, ObjectId> found = new HashMap<>();
        return walk(root, directories::contains, (path, entry) -> {
            if (wanted.contains(path)) {
                found.put(path, entry.id());
            }
        }).thenApply(ignored -> found);
    }

    private interface FileVisitor {
        void visit(String path, TreeEntry entry);
    }

    private record Level(String directory, ObjectId tree) {
    }

    private TickCompletableFuture<Void> walk(ObjectId root, Predicate<String> descend, FileVisitor visitor) {
        List<Level> start = descend.test("") ? List.of(new Level("", root)) : List.of();
        return Futures.loop(start, List::isEmpty, level ->
                ensure(level.stream().map(Level::tree).toList()).thenApply(ignored -> {
                    List<Level> next = new ArrayList<>();
                    for (Level l : level) {
                        for (TreeEntry entry : store.tree(l.tree()).entries()) {
                            String path = Paths.join(l.directory(), entry.name());
                            if (entry.kind() == EntryKind.FILE) {
                                visitor.visit(path, entry);
                            } else if (descend.test(path)) {
                                next.add(new Level(path, entry.id()));
                            }
                        }
                    }
                    return next;
                })).thenApply(ignored -> null);
    }

    private record Pair(String directory, ObjectId before, ObjectId after) {
    }

    /**
     * The files that differ between two trees, in path order. A subtree whose id is the same on both sides is
     * skipped without being loaded.
     */
    public TickCompletableFuture<List<Change>> diff(ObjectId before, ObjectId after) {
        List<Change> changes = new ArrayList<>();
        List<Pair> start = java.util.Objects.equals(before, after) ? List.of() : List.of(new Pair("", before, after));
        return Futures.loop(start, List::isEmpty, level -> {
            List<ObjectId> ids = new ArrayList<>();
            level.forEach(p -> {
                if (p.before() != null) {
                    ids.add(p.before());
                }
                if (p.after() != null) {
                    ids.add(p.after());
                }
            });
            return ensure(ids).thenApply(ignored -> {
                List<Pair> next = new ArrayList<>();
                for (Pair pair : level) {
                    Map<String, TreeEntry> b = entries(pair.before());
                    Map<String, TreeEntry> a = entries(pair.after());
                    Set<String> names = new TreeSet<>(b.keySet());
                    names.addAll(a.keySet());
                    for (String name : names) {
                        String path = Paths.join(pair.directory(), name);
                        TreeEntry was = b.get(name);
                        TreeEntry now = a.get(name);
                        ObjectId wasFile = was != null && was.kind() == EntryKind.FILE ? was.id() : null;
                        ObjectId nowFile = now != null && now.kind() == EntryKind.FILE ? now.id() : null;
                        if (wasFile != null || nowFile != null) {
                            if (wasFile == null) {
                                changes.add(new Change(Change.Kind.ADDED, path, null, nowFile));
                            } else if (nowFile == null) {
                                changes.add(new Change(Change.Kind.DELETED, path, wasFile, null));
                            } else if (!wasFile.equals(nowFile)) {
                                changes.add(new Change(Change.Kind.MODIFIED, path, wasFile, nowFile));
                            }
                        }
                        ObjectId wasDir = was != null && was.kind() == EntryKind.DIR ? was.id() : null;
                        ObjectId nowDir = now != null && now.kind() == EntryKind.DIR ? now.id() : null;
                        if ((wasDir != null || nowDir != null) && !java.util.Objects.equals(wasDir, nowDir)) {
                            next.add(new Pair(path, wasDir, nowDir));
                        }
                    }
                }
                return next;
            });
        }).thenApply(ignored -> {
            changes.sort(Comparator.comparing(Change::path));
            return changes;
        });
    }

    private Map<String, TreeEntry> entries(ObjectId tree) {
        Map<String, TreeEntry> entries = new HashMap<>();
        if (tree != null) {
            Tree t = store.tree(tree);
            t.entries().forEach(e -> entries.put(e.name(), e));
        }
        return entries;
    }
}
