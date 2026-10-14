package com.versioncontrol.scion.objects;

/** An immutable, content-addressed object: a file's bytes, a directory listing, or a commit. */
public sealed interface ScionObject permits Blob, Tree, Commit {
    ObjectType type();

    /** The bytes after the header. Two objects with the same content have the same id. */
    byte[] content();

    default ObjectId id() {
        return ObjectCodec.idOf(this);
    }
}
