package com.versioncontrol.scionlite.diff;

/** One step of a line diff. For EQUAL both indexes are set; DELETE has only oldIndex, INSERT only newIndex (-1). */
public record Edit(Kind kind, int oldIndex, int newIndex, String line) {
    public enum Kind {EQUAL, DELETE, INSERT}
}
