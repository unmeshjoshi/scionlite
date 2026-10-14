package com.versioncontrol.scionlite.changeset;

import com.versioncontrol.scionlite.objects.Blob;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangesetTest {
    private static final String P1 = "a".repeat(64);
    private static final String P2 = "b".repeat(64);

    @Test
    void encodesAndDecodesToTheSameChangesetWithTheSameId() {
        Changeset changeset = new Changeset(List.of(P1, P2), "alice", 42, "line one\nline two\n",
                List.of(FileChange.write("src/Main.java", Blob.of("x").id()), FileChange.delete("README.md")));

        Changeset decoded = Changeset.decode(changeset.encode());

        assertEquals(changeset, decoded);
        assertEquals(changeset.id(), decoded.id());
        assertEquals(P1, decoded.firstParent());
    }

    @Test
    void theIdIsTheContentSoChangeOrderDoesNotMatterButAnyFieldDoes() {
        FileChange a = FileChange.write("a", Blob.of("1").id());
        FileChange b = FileChange.delete("b");
        Changeset one = new Changeset(List.of(P1), "alice", 1, "m", List.of(a, b));
        Changeset two = new Changeset(List.of(P1), "alice", 1, "m", List.of(b, a));

        assertEquals(one.id(), two.id());
        assertNotEquals(one.id(), new Changeset(List.of(P1), "alice", 2, "m", List.of(a, b)).id());
        assertNotEquals(one.id(), new Changeset(List.of(P2), "alice", 1, "m", List.of(a, b)).id());
    }

    @Test
    void rejectsAPathChangedTwiceAndInvalidPaths() {
        assertThrows(IllegalArgumentException.class, () -> new Changeset(List.of(), "alice", 1, "m",
                List.of(FileChange.delete("a"), FileChange.write("a", Blob.of("1").id()))));
        assertThrows(IllegalArgumentException.class, () -> FileChange.delete("a//b"));
        assertThrows(IllegalArgumentException.class, () -> FileChange.delete("../etc/passwd"));
    }

    @Test
    void compressionRoundTripsAndShrinksRepetitiveContent() {
        byte[] content = "the same line\n".repeat(1000).getBytes(StandardCharsets.UTF_8);

        byte[] compressed = Compression.compress(content);

        assertTrue(compressed.length < content.length / 10);
        assertArrayEquals(content, Compression.decompress(compressed));
        assertArrayEquals(new byte[0], Compression.decompress(Compression.compress(new byte[0])));
    }
}
