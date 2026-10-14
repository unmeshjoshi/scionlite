package com.versioncontrol.scion.storage;

/** The calls the cloud-storage node answers. Only scion-servers call them. */
public final class CloudStorageApi {
    public static final String PUT = "storage.put";
    public static final String GET = "storage.get";

    /** Objects are immutable: putting a key that exists keeps the first value. */
    public record PutRequest(String key, byte[] value) {
    }

    public record PutReply(boolean stored) {
    }

    public record GetRequest(String key) {
    }

    /** {@code value} is null when there is no such object. */
    public record GetReply(byte[] value) {
    }

    private CloudStorageApi() {
    }
}
