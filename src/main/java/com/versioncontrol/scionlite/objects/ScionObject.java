package com.versioncontrol.scionlite.objects;

/** An immutable, content-addressed object: a file's bytes or a directory listing. */
public sealed interface ScionObject permits Blob, Tree {
    ObjectType type();

    /** The bytes after the header. Two objects with the same content have the same id. */
    byte[] content();

    default ObjectId id() {
        return ObjectCodec.idOf(this);
    }
}
