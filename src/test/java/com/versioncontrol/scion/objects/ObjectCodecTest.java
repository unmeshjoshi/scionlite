package com.versioncontrol.scion.objects;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObjectCodecTest {
    private final ObjectId someId = Blob.of("x").id();

    @Test
    void sameContentHasSameIdAndDifferentContentDoesNot() {
        assertEquals(Blob.of("hello").id(), Blob.of("hello").id());
        assertNotEquals(Blob.of("hello").id(), Blob.of("hello!").id());
    }

    @Test
    void blobIdIsTheHashOfTheHeaderAndContent() {
        byte[] expected = "blob 5\0hello".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(expected, ObjectCodec.encode(Blob.of("hello")));
        assertEquals(ObjectId.hash(expected), Blob.of("hello").id());
    }

    @Test
    void roundTripsEveryObjectType() {
        Tree tree = new Tree(List.of(new TreeEntry("a.txt", EntryKind.FILE, someId)));
        for (ScionObject object : List.of(Blob.of("data"), tree)) {
            assertEquals(object, ObjectCodec.decode(ObjectCodec.encode(object)));
        }
    }

    @Test
    void treeIdDoesNotDependOnEntryOrder() {
        TreeEntry a = new TreeEntry("a", EntryKind.FILE, someId);
        TreeEntry b = new TreeEntry("b", EntryKind.DIR, Tree.EMPTY.id());
        assertEquals(new Tree(List.of(a, b)).id(), new Tree(List.of(b, a)).id());
    }

    @Test
    void rejectsDuplicateNamesAndBadSegments() {
        TreeEntry a = new TreeEntry("a", EntryKind.FILE, someId);
        assertThrows(IllegalArgumentException.class, () -> new Tree(List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () -> new TreeEntry("a/b", EntryKind.FILE, someId));
        assertThrows(IllegalArgumentException.class, () -> new TreeEntry("..", EntryKind.FILE, someId));
    }

    @Test
    void rejectsCorruptBytes() {
        assertThrows(CorruptObjectException.class, () -> ObjectCodec.decode("blob 9\0short".getBytes()));
        assertThrows(CorruptObjectException.class, () -> ObjectCodec.decode("bogus 1\0x".getBytes()));
        assertThrows(CorruptObjectException.class, () -> ObjectCodec.decode("no header".getBytes()));
    }

    @Test
    void storeReturnsTypedObjectsAndReportsMissingOnes() {
        ObjectStore store = new ObjectStore();
        ObjectId id = store.put(Blob.of("content"));
        assertEquals("content", new String(store.blob(id).data()));
        assertThrows(CorruptObjectException.class, () -> store.tree(id));
        assertThrows(MissingObjectException.class, () -> store.get(Blob.of("other").id()));
    }

    @Test
    void storeAcceptsEncodedBytesFromElsewhereOnlyIfTheyDecode() {
        ObjectStore store = new ObjectStore();
        assertEquals(Blob.of("x").id(), store.putEncoded(ObjectCodec.encode(Blob.of("x"))));
        assertThrows(CorruptObjectException.class, () -> store.putEncoded("garbage".getBytes()));
    }
}
