package com.versioncontrol.scion.tree;

import com.versioncontrol.scion.changeset.FileChange;
import com.versioncontrol.scion.objects.Blob;
import com.versioncontrol.scion.objects.EntryKind;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;
import com.versioncontrol.scion.objects.Tree;
import com.versioncontrol.scion.objects.TreeEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class TreeEditTest {
    private final ObjectStore store = new ObjectStore();

    private FileChange write(String path, String text) {
        Blob blob = Blob.of(text);
        store.put(blob);
        return FileChange.write(path, blob.id());
    }

    private Map<String, ObjectId> flatten(ObjectId tree, String prefix) {
        Map<String, ObjectId> files = new TreeMap<>();
        for (TreeEntry entry : store.tree(tree).entries()) {
            String path = prefix + entry.name();
            if (entry.kind() == EntryKind.FILE) {
                files.put(path, entry.id());
            } else {
                files.putAll(flatten(entry.id(), path + "/"));
            }
        }
        return files;
    }

    @Test
    void buildsNestedDirectoriesFromNothing() {
        TreeEdit.Result result = TreeEdit.apply(store, null, List.of(write("README.md", "hi"),
                write("src/main/A.java", "a"), write("src/main/B.java", "b")));

        assertEquals(Map.of("README.md", Blob.of("hi").id(), "src/main/A.java", Blob.of("a").id(),
                "src/main/B.java", Blob.of("b").id()), flatten(result.root(), ""));
        assertEquals(3, result.written().size());   // root, src, src/main
    }

    @Test
    void rewritesOnlyTheDirectoriesOnTheChangedPath() {
        ObjectId root = TreeEdit.apply(store, null, List.of(write("a/b/x", "1"), write("c/d/y", "2"))).root();
        ObjectId untouched = store.tree(root).entry("c").orElseThrow().id();

        TreeEdit.Result result = TreeEdit.apply(store, root, List.of(write("a/b/x", "changed")));

        assertEquals(3, result.written().size());   // root, a, a/b; not c or c/d
        assertEquals(untouched, store.tree(result.root()).entry("c").orElseThrow().id());
        assertEquals(Blob.of("changed").id(), flatten(result.root(), "").get("a/b/x"));
    }

    @Test
    void anEmptiedDirectoryDisappearsButTheRootStays() {
        ObjectId root = TreeEdit.apply(store, null, List.of(write("a/b/x", "1"))).root();

        ObjectId after = TreeEdit.apply(store, root, List.of(FileChange.delete("a/b/x"))).root();

        assertEquals(Tree.EMPTY.id(), after);
    }

    @Test
    void theSameFilesGiveTheSameRootWhateverTheHistory() {
        ObjectId direct = TreeEdit.apply(store, null, List.of(write("a/x", "1"), write("b", "2"))).root();
        ObjectId stepOne = TreeEdit.apply(store, null, List.of(write("a/x", "0"), write("c", "3"))).root();
        ObjectId stepTwo = TreeEdit.apply(store, stepOne,
                List.of(write("a/x", "1"), write("b", "2"), FileChange.delete("c"))).root();

        assertEquals(direct, stepTwo);
    }

    @Test
    void rejectsAPathThatIsBothAFileAndADirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> TreeEdit.apply(store, null, List.of(write("a", "1"), write("a/b", "2"))));
    }

    @Test
    void directoriesOnListsEveryAncestorDirectory() {
        assertEquals(java.util.Set.of("", "a", "a/b", "c"), TreeEdit.directoriesOn(List.of("a/b/x", "c/y", "z")));
    }
}
