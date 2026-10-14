package com.versioncontrol.scionlite.merge;

import com.versioncontrol.scionlite.objects.Blob;
import com.versioncontrol.scionlite.objects.ObjectId;
import com.versioncontrol.scionlite.objects.ObjectStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MergeTest {
    private static TextMerge.Result merge(String base, String ours, String theirs) {
        return TextMerge.merge(base.getBytes(), ours.getBytes(), theirs.getBytes(), "ours", "theirs");
    }

    @Test
    void changesToDifferentLinesMergeCleanly() {
        TextMerge.Result result = merge("a\nb\nc\nd\n", "A\nb\nc\nd\n", "a\nb\nc\nD\n");
        assertFalse(result.conflicted());
        assertEquals("A\nb\nc\nD\n", new String(result.merged()));
    }

    @Test
    void sameChangeOnBothSidesIsTakenOnce() {
        TextMerge.Result result = merge("a\nb\n", "a\nX\n", "a\nX\n");
        assertFalse(result.conflicted());
        assertEquals("a\nX\n", new String(result.merged()));
    }

    @Test
    void insertionsAtDifferentPlacesMerge() {
        TextMerge.Result result = merge("a\nb\nc\n", "top\na\nb\nc\n", "a\nb\nc\nbottom\n");
        assertFalse(result.conflicted());
        assertEquals("top\na\nb\nc\nbottom\n", new String(result.merged()));
    }

    @Test
    void conflictingChangesGetMarkers() {
        TextMerge.Result result = merge("a\nb\nc\n", "a\nours\nc\n", "a\ntheirs\nc\n");
        assertTrue(result.conflicted());
        assertEquals("a\n<<<<<<< ours\nours\n=======\ntheirs\n>>>>>>> theirs\nc\n", new String(result.merged()));
    }

    @Test
    void changesToAdjacentLinesConflictAsInDiff3() {
        assertTrue(merge("x\ny\n", "X\ny\n", "x\nY\n").conflicted());
    }

    @Test
    void treeMergeTakesOneSidedChangesAndFlagsConflicts() {
        ObjectStore store = new ObjectStore();
        ObjectId base = store.put(Blob.of("x\nm\ny\n"));
        ObjectId ours = store.put(Blob.of("X\nm\ny\n"));
        ObjectId theirs = store.put(Blob.of("x\nm\nY\n"));
        ObjectId other = store.put(Blob.of("other\n"));

        TreeMerge.Result result = TreeMerge.merge(
                Map.of("both", base, "deleted-by-us", base, "modify-delete", base, "add-only-theirs", other),
                Map.of("both", ours, "modify-delete", ours, "add-only-theirs", other),
                Map.of("both", theirs, "deleted-by-us", base, "new-theirs", other, "add-only-theirs", other),
                store, "ours", "theirs");

        assertEquals(List.of("modify-delete"), result.conflicts());
        assertEquals("X\nm\nY\n", new String(store.blob(result.files().get("both")).data()));
        assertFalse(result.files().containsKey("deleted-by-us"));
        assertEquals(other, result.files().get("new-theirs"));
        assertEquals(ours, result.files().get("modify-delete"));
    }
}
