package com.versioncontrol.scion.diff;

import com.versioncontrol.scion.objects.Blob;
import com.versioncontrol.scion.objects.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiffTest {
    @Test
    void diffIsAMinimalEditScriptThatRebuildsTheNewLines() {
        List<String> a = List.of("a", "b", "c", "a", "b", "b", "a");
        List<String> b = List.of("c", "b", "a", "b", "a", "c");
        List<Edit> edits = LineDiff.diff(a, b);
        assertEquals(5, edits.stream().filter(e -> e.kind() != Edit.Kind.EQUAL).count());
        List<String> rebuilt = new ArrayList<>();
        edits.stream().filter(e -> e.kind() != Edit.Kind.DELETE).forEach(e -> rebuilt.add(e.line()));
        assertEquals(b, rebuilt);
    }

    @Test
    void emptyInputs() {
        assertEquals(List.of(), LineDiff.diff(List.of(), List.of()));
        assertEquals(1, LineDiff.diff(List.of(), List.of("x")).size());
    }

    @Test
    void unifiedDiff() {
        String diff = UnifiedDiff.format("a/f", "b/f", List.of("one", "two", "three"), List.of("one", "2", "three"));
        assertEquals("""
                --- a/f
                +++ b/f
                @@ -1,3 +1,3 @@
                 one
                -two
                +2
                 three
                """, diff);
        assertEquals("", UnifiedDiff.format("a", "b", List.of("same"), List.of("same")));
    }

    @Test
    void linesRoundTripAnyBytes() {
        byte[] bytes = {'a', '\n', (byte) 0xff, '\n'};
        assertArrayEquals(bytes, Lines.join(Lines.split(bytes)));
    }

    @Test
    void treeDiffReportsAddedModifiedAndDeleted() {
        ObjectId one = Blob.of("1").id();
        ObjectId two = Blob.of("2").id();
        List<Change> changes = TreeDiff.diff(Map.of("keep", one, "mod", one, "gone", one),
                Map.of("keep", one, "mod", two, "new", two));
        assertEquals(List.of("D gone", "M mod", "A new"), changes.stream().map(Change::toString).toList());
    }
}
