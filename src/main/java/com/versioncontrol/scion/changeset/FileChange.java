package com.versioncontrol.scion.changeset;

import com.versioncontrol.scion.objects.ObjectId;

import java.util.Objects;

/**
 * One file a changeset changed relative to its first parent: the path and the content-addressed id of the file's
 * new blob, or a null {@code blob} when the file was deleted.
 */
public record FileChange(String path, String blob) {
    public FileChange {
        Paths.validate(path);
        if (blob != null) {
            ObjectId.of(blob);
        }
    }

    public static FileChange write(String path, ObjectId blob) {
        return new FileChange(path, Objects.requireNonNull(blob, "blob").hex());
    }

    public static FileChange delete(String path) {
        return new FileChange(path, null);
    }

    /** The new blob's id, or null if the file was deleted. */
    public ObjectId blobId() {
        return ObjectId.ofNullable(blob);
    }
}
