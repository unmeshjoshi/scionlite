package com.versioncontrol.scion.diff;

import com.versioncontrol.scion.objects.ObjectId;

/** A file that differs between two snapshots. oldId is null for an added file, newId for a deleted one. */
public record Change(Kind kind, String path, ObjectId oldId, ObjectId newId) {
    public enum Kind {ADDED, MODIFIED, DELETED}

    @Override
    public String toString() {
        return switch (kind) {
            case ADDED -> "A " + path;
            case MODIFIED -> "M " + path;
            case DELETED -> "D " + path;
        };
    }
}
