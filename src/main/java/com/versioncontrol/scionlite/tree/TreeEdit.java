package com.versioncontrol.scionlite.tree;

import com.versioncontrol.scionlite.changeset.FileChange;
import com.versioncontrol.scionlite.changeset.Paths;
import com.versioncontrol.scionlite.objects.EntryKind;
import com.versioncontrol.scionlite.objects.ObjectId;
import com.versioncontrol.scionlite.objects.ObjectStore;
import com.versioncontrol.scionlite.objects.Tree;
import com.versioncontrol.scionlite.objects.TreeEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Derives a changeset's root tree from its first parent's: applies the changed files and rewrites only the
 * directories on their paths. Every other directory keeps its id, so the cost grows with the size of the change,
 * not the size of the repository. This is derived data in the post's sense: "Each type of derived data is defined
 * recursively, so its value for a changeset is computed from its values for that changeset's parents."
 */
public final class TreeEdit {
    /** {@code written}: the trees this edit created, including the root. */
    public record Result(ObjectId root, List<Tree> written) {
    }

    private TreeEdit() {
    }

    /** The directories whose trees {@link #apply} reads: every directory on a changed path. */
    public static Set<String> directoriesOn(Collection<String> paths) {
        Set<String> directories = new TreeSet<>();
        directories.add("");
        for (String path : paths) {
            int slash = -1;
            while ((slash = path.indexOf('/', slash + 1)) >= 0) {
                directories.add(path.substring(0, slash));
            }
        }
        return directories;
    }

    /**
     * {@code root} is null for no parent (the empty tree). The store must hold every tree on a changed path;
     * new trees are added to it.
     */
    public static Result apply(ObjectStore store, ObjectId root, List<FileChange> changes) {
        Node top = new Node();
        for (FileChange change : changes) {
            Node node = top;
            List<String> segments = Paths.segments(change.path());
            for (int i = 0; i < segments.size() - 1; i++) {
                node = node.directory(segments.get(i), change.path());
            }
            node.file(segments.get(segments.size() - 1), change);
        }
        List<Tree> written = new ArrayList<>();
        ObjectId newRoot = edit(store, root, top, written, true);
        return new Result(newRoot, written);
    }

    private static ObjectId edit(ObjectStore store, ObjectId treeId, Node node, List<Tree> written, boolean isRoot) {
        Map<String, TreeEntry> entries = new TreeMap<>();
        if (treeId != null) {
            store.tree(treeId).entries().forEach(e -> entries.put(e.name(), e));
        }
        node.files.forEach((name, change) -> {
            if (change.blobId() == null) {
                entries.remove(name);
            } else {
                entries.put(name, new TreeEntry(name, EntryKind.FILE, change.blobId()));
            }
        });
        node.directories.forEach((name, child) -> {
            TreeEntry existing = entries.get(name);
            ObjectId childTree = existing != null && existing.kind() == EntryKind.DIR ? existing.id() : null;
            ObjectId edited = edit(store, childTree, child, written, false);
            if (edited == null) {
                entries.remove(name);
            } else {
                entries.put(name, new TreeEntry(name, EntryKind.DIR, edited));
            }
        });
        if (entries.isEmpty() && !isRoot) {
            return null;   // a directory with nothing left in it disappears
        }
        Tree tree = new Tree(new ArrayList<>(entries.values()));
        written.add(tree);
        return store.put(tree);
    }

    private static final class Node {
        final Map<String, Node> directories = new LinkedHashMap<>();
        final Map<String, FileChange> files = new LinkedHashMap<>();

        Node directory(String name, String path) {
            if (files.containsKey(name)) {
                throw new IllegalArgumentException("path is both a file and a directory: " + path);
            }
            return directories.computeIfAbsent(name, n -> new Node());
        }

        void file(String name, FileChange change) {
            if (directories.containsKey(name)) {
                throw new IllegalArgumentException("path is both a file and a directory: " + change.path());
            }
            files.put(name, change);
        }
    }
}
