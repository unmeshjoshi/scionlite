package com.versioncontrol.scion.snapshot;

import com.versioncontrol.scion.objects.EntryKind;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotsTest {
    private final ObjectStore store = new ObjectStore();

    @Test
    void nestedDirectoriesBecomeTrees() {
        ObjectId root = Snapshots.writeFiles(files("README", "hi", "src/main/A.java", "class A {}"), store);
        var entries = store.tree(root).entries();
        assertEquals(2, entries.size());
        assertEquals(EntryKind.FILE, store.tree(root).entry("README").orElseThrow().kind());
        assertEquals(EntryKind.DIR, store.tree(root).entry("src").orElseThrow().kind());
    }

    @Test
    void roundTripsFiles() {
        Map<String, byte[]> files = files("a", "1", "d/b", "2", "d/e/c", "3");
        SortedMap<String, byte[]> read = Snapshots.readFiles(Snapshots.writeFiles(files, store), store);
        assertEquals(files.keySet(), read.keySet());
        files.forEach((path, bytes) -> assertArrayEquals(bytes, read.get(path)));
    }

    @Test
    void sameFilesGiveSameRootWhateverTheOrder() {
        ObjectId first = Snapshots.writeFiles(files("x/1", "a", "y/2", "b"), store);
        ObjectId second = Snapshots.writeFiles(files("y/2", "b", "x/1", "a"), store);
        assertEquals(first, second);
    }

    @Test
    void rejectsInvalidPathsAndFileDirectoryClashes() {
        assertThrows(IllegalArgumentException.class, () -> Snapshots.writeFiles(files("/abs", "x"), store));
        assertThrows(IllegalArgumentException.class, () -> Snapshots.writeFiles(files("a//b", "x"), store));
        assertThrows(IllegalArgumentException.class, () -> Snapshots.writeFiles(files("a/../b", "x"), store));
        assertThrows(IllegalArgumentException.class, () -> Snapshots.writeFiles(files("a", "x", "a/b", "y"), store));
    }

    static Map<String, byte[]> files(String... pathsAndContents) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            files.put(pathsAndContents[i], pathsAndContents[i + 1].getBytes());
        }
        return files;
    }
}
