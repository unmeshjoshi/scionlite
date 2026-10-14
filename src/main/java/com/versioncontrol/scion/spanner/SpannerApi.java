package com.versioncontrol.scion.spanner;

import java.util.Map;

/**
 * The calls the Spanner node answers. Only scion-servers call them.
 */
public final class SpannerApi {
    public static final String READ = "spanner.read";
    public static final String SCAN = "spanner.scan";
    public static final String INSERT = "spanner.insert";
    public static final String WRITE = "spanner.write";
    public static final String COMPARE_AND_SWAP = "spanner.compareAndSwap";

    /** Tables Scion Lite uses. */
    public static final String REFS = "refs";
    public static final String CHANGESETS = "changesets";
    public static final String ROOT_TREE = "root_tree";

    public record ReadRequest(String table, String key) {
    }

    /** {@code value} is null when there is no such row. */
    public record ReadReply(byte[] value) {
    }

    public record ScanRequest(String table, String prefix) {
    }

    public record ScanReply(Map<String, byte[]> rows) {
    }

    /** Inserts the row unless the key exists. */
    public record InsertRequest(String table, String key, byte[] value) {
    }

    /** {@code existing} is the value already there, or null if this insert created the row. */
    public record InsertReply(boolean inserted, byte[] existing) {
    }

    public record WriteRequest(String table, String key, byte[] value) {
    }

    public record WriteReply(boolean written) {
    }

    /**
     * Sets the row to {@code value} only if it currently holds {@code expected} (null: the row must not exist).
     */
    public record CompareAndSwapRequest(String table, String key, byte[] expected, byte[] value) {
    }

    /** {@code current} is the row's value after the call. */
    public record CompareAndSwapReply(boolean swapped, byte[] current) {
    }

    private SpannerApi() {
    }
}
