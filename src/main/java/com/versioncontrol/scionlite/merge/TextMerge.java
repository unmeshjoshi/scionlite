package com.versioncontrol.scionlite.merge;

import com.versioncontrol.scionlite.diff.Edit;
import com.versioncontrol.scionlite.diff.LineDiff;
import com.versioncontrol.scionlite.diff.Lines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Three-way line merge (diff3). Lines the base shares with both sides are stable; between stable lines, a chunk
 * changed on one side only takes that side, a chunk changed the same way on both takes it once, and a chunk changed
 * differently on both sides is a conflict, written out with markers.
 */
public final class TextMerge {
    public static final String OURS_MARKER = "<<<<<<< ";
    public static final String SEPARATOR = "=======";
    public static final String THEIRS_MARKER = ">>>>>>> ";

    public record Result(byte[] merged, boolean conflicted) {
    }

    private TextMerge() {
    }

    public static Result merge(byte[] base, byte[] ours, byte[] theirs, String oursLabel, String theirsLabel) {
        List<String> b = Lines.split(base);
        List<String> o = Lines.split(ours);
        List<String> t = Lines.split(theirs);
        int[] toOurs = matches(b, o);
        int[] toTheirs = matches(b, t);

        List<String> out = new ArrayList<>();
        boolean conflicted = false;
        int i = 0, j = 0, k = 0;
        while (true) {
            if (i < b.size() && toOurs[i] == j && toTheirs[i] == k) {
                out.add(b.get(i));
                i++;
                j++;
                k++;
                continue;
            }
            int next = i;
            while (next < b.size() && (toOurs[next] < 0 || toTheirs[next] < 0)) {
                next++;
            }
            int oEnd = next < b.size() ? toOurs[next] : o.size();
            int tEnd = next < b.size() ? toTheirs[next] : t.size();
            conflicted |= resolve(b.subList(i, next), o.subList(j, oEnd), t.subList(k, tEnd), out, oursLabel, theirsLabel);
            if (next >= b.size()) {
                break;
            }
            i = next;
            j = oEnd;
            k = tEnd;
        }
        return new Result(Lines.join(out), conflicted);
    }

    // Returns true for a conflict.
    private static boolean resolve(List<String> base, List<String> ours, List<String> theirs, List<String> out,
                                   String oursLabel, String theirsLabel) {
        if (ours.equals(base)) {
            out.addAll(theirs);
        } else if (theirs.equals(base) || ours.equals(theirs)) {
            out.addAll(ours);
        } else {
            out.add(OURS_MARKER + oursLabel);
            out.addAll(ours);
            out.add(SEPARATOR);
            out.addAll(theirs);
            out.add(THEIRS_MARKER + theirsLabel);
            return true;
        }
        return false;
    }

    // For each base line, the index of the line it is matched to in the other version, or -1.
    private static int[] matches(List<String> base, List<String> other) {
        int[] matched = new int[base.size()];
        Arrays.fill(matched, -1);
        for (Edit edit : LineDiff.diff(base, other)) {
            if (edit.kind() == Edit.Kind.EQUAL) {
                matched[edit.oldIndex()] = edit.newIndex();
            }
        }
        return matched;
    }
}
