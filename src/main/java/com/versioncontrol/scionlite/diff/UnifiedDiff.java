package com.versioncontrol.scionlite.diff;

import java.util.ArrayList;
import java.util.List;

/** Formats a line diff as a unified diff with three lines of context. */
public final class UnifiedDiff {
    private static final int CONTEXT = 3;

    private UnifiedDiff() {
    }

    public static String format(String oldName, String newName, List<String> oldLines, List<String> newLines) {
        List<Edit> edits = LineDiff.diff(oldLines, newLines);
        if (edits.stream().allMatch(e -> e.kind() == Edit.Kind.EQUAL)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(oldName).append('\n');
        out.append("+++ ").append(newName).append('\n');
        for (List<Edit> hunk : hunks(edits)) {
            appendHunk(out, hunk);
        }
        return out.toString();
    }

    private static List<List<Edit>> hunks(List<Edit> edits) {
        List<List<Edit>> hunks = new ArrayList<>();
        int i = 0;
        while (i < edits.size()) {
            if (edits.get(i).kind() == Edit.Kind.EQUAL) {
                i++;
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            // Extend while the next change is within 2*CONTEXT equal lines.
            while (end < edits.size()) {
                if (edits.get(end).kind() != Edit.Kind.EQUAL) {
                    end++;
                    continue;
                }
                int run = end;
                while (run < edits.size() && edits.get(run).kind() == Edit.Kind.EQUAL) {
                    run++;
                }
                if (run < edits.size() && run - end <= 2 * CONTEXT) {
                    end = run;
                } else {
                    end = Math.min(run, end + CONTEXT);
                    break;
                }
            }
            hunks.add(edits.subList(start, end));
            i = end;
        }
        return hunks;
    }

    private static void appendHunk(StringBuilder out, List<Edit> hunk) {
        int oldStart = -1;
        int newStart = -1;
        int oldCount = 0;
        int newCount = 0;
        for (Edit edit : hunk) {
            if (edit.kind() != Edit.Kind.INSERT) {
                if (oldStart < 0) oldStart = edit.oldIndex();
                oldCount++;
            }
            if (edit.kind() != Edit.Kind.DELETE) {
                if (newStart < 0) newStart = edit.newIndex();
                newCount++;
            }
        }
        out.append("@@ -").append(range(oldStart, oldCount)).append(" +").append(range(newStart, newCount)).append(" @@\n");
        for (Edit edit : hunk) {
            char prefix = switch (edit.kind()) {
                case EQUAL -> ' ';
                case DELETE -> '-';
                case INSERT -> '+';
            };
            out.append(prefix).append(edit.line()).append('\n');
        }
    }

    private static String range(int start, int count) {
        return (count == 0 ? Math.max(start, 0) : start + 1) + "," + count;
    }
}
