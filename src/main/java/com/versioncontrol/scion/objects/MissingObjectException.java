package com.versioncontrol.scion.objects;

public class MissingObjectException extends RuntimeException {
    private final ObjectId id;

    public MissingObjectException(ObjectId id) {
        super("missing object " + id);
        this.id = id;
    }

    public ObjectId id() {
        return id;
    }
}
