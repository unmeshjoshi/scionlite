package com.versioncontrol.scion.snapshot;

import com.versioncontrol.scion.objects.Blob;
import com.versioncontrol.scion.objects.EntryKind;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;
import com.versioncontrol.scion.objects.Tree;
import com.versioncontrol.scion.objects.TreeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Turns a flat map of paths ("src/Main.java") into nested trees and back. Paths use '/' and have no empty,
 * "." or ".." segments. A path cannot be both a file and a directory.
 */
public final class Snapshots {
    private Snapshots() {
    }

    /** Writes the blobs and trees for the files into the store and returns the root tree's id. */
    public static ObjectId writeFiles(Map<String, byte[]> files, ObjectStore store) {
        SortedMap<String, ObjectId> blobs = new TreeMap<>();
        files.forEach((path, bytes) -> blobs.put(path, store.put(new Blob(bytes))));
        return writeTree(blobs, store);
    }

    /** Writes the trees for files already stored as blobs and returns the root tree's id. */
    public static ObjectId writeTree(Map<String, ObjectId> blobsByPath, ObjectStore store) {
        Dir root = new Dir();
        blobsByPath.forEach((path, blob) -> root.add(validate(path), 0, blob));
        return root.write(store);
    }

    /** Every file under the tree, by full path, in path order. */
    public static SortedMap<String, ObjectId> readTree(ObjectId treeId, ObjectStore store) {
        SortedMap<String, ObjectId> files = new TreeMap<>();
        collect(treeId, "", store, files);
        return files;
    }

    /** The contents of every file under the tree. */
    public static SortedMap<String, byte[]> readFiles(ObjectId treeId, ObjectStore store) {
        SortedMap<String, byte[]> files = new TreeMap<>();
        readTree(treeId, store).forEach((path, blob) -> files.put(path, store.blob(blob).data()));
        return files;
    }

    public static String[] validate(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.endsWith("/")) {
            throw new IllegalArgumentException("invalid path: '" + path + "'");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            TreeEntry.validateName(segment);
        }
        return segments;
    }

    private static void collect(ObjectId treeId, String prefix, ObjectStore store, Map<String, ObjectId> out) {
        for (TreeEntry entry : store.tree(treeId).entries()) {
            String path = prefix + entry.name();
            if (entry.kind() == EntryKind.FILE) {
                out.put(path, entry.id());
            } else {
                collect(entry.id(), path + "/", store, out);
            }
        }
    }

    private static final class Dir {
        final SortedMap<String, ObjectId> files = new TreeMap<>();
        final SortedMap<String, Dir> dirs = new TreeMap<>();

        void add(String[] segments, int index, ObjectId blob) {
            String name = segments[index];
            if (index == segments.length - 1) {
                if (dirs.containsKey(name)) {
                    throw new IllegalArgumentException("'" + name + "' is both a file and a directory");
                }
                files.put(name, blob);
                return;
            }
            if (files.containsKey(name)) {
                throw new IllegalArgumentException("'" + name + "' is both a file and a directory");
            }
            dirs.computeIfAbsent(name, n -> new Dir()).add(segments, index + 1, blob);
        }

        ObjectId write(ObjectStore store) {
            List<TreeEntry> entries = new ArrayList<>();
            files.forEach((name, id) -> entries.add(new TreeEntry(name, EntryKind.FILE, id)));
            dirs.forEach((name, dir) -> entries.add(new TreeEntry(name, EntryKind.DIR, dir.write(store))));
            return store.put(new Tree(entries));
        }
    }
}
