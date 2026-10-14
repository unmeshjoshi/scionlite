package com.versioncontrol.scion.objects;

import java.util.Objects;

/** One name in a directory: a file (a blob) or a subdirectory (a tree). */
public record TreeEntry(String name, EntryKind kind, ObjectId id) {
    public TreeEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        validateName(name);
    }

    public static void validateName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid path segment: '" + name + "'");
        }
    }
}
