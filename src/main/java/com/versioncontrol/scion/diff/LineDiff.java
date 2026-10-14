package com.versioncontrol.scion.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Myers' O(ND) shortest edit script between two lists of lines. */
public final class LineDiff {
    private LineDiff() {
    }

    public static List<Edit> diff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int max = n + m;
        int offset = max;
        int[] v = new int[2 * max + 2];
        List<int[]> trace = new ArrayList<>();
        outer:
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1];
                } else {
                    x = v[offset + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    break outer;
                }
            }
        }
        return backtrack(trace, a, b, offset);
    }

    private static List<Edit> backtrack(List<int[]> trace, List<String> a, List<String> b, int offset) {
        List<Edit> edits = new ArrayList<>();
        int x = a.size();
        int y = b.size();
        for (int d = trace.size() - 1; d >= 0; d--) {
            int[] v = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = v[offset + prevK];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--;
                y--;
                edits.add(new Edit(Edit.Kind.EQUAL, x, y, a.get(x)));
            }
            if (d > 0) {
                if (x == prevX) {
                    y--;
                    edits.add(new Edit(Edit.Kind.INSERT, -1, y, b.get(y)));
                } else {
                    x--;
                    edits.add(new Edit(Edit.Kind.DELETE, x, -1, a.get(x)));
                }
            }
        }
        Collections.reverse(edits);
        return edits;
    }
}
