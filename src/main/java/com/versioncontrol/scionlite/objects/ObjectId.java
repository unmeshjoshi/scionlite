package com.versioncontrol.scionlite.objects;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** The SHA-256 of an object's canonical bytes, as 64 lowercase hex characters. Equal content, equal id. */
public record ObjectId(String hex) implements Comparable<ObjectId> {
    private static final int HEX_LENGTH = 64;

    public ObjectId {
        Objects.requireNonNull(hex, "hex");
        if (hex.length() != HEX_LENGTH || !hex.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("not an object id: " + hex);
        }
    }

    public static ObjectId of(String hex) {
        return new ObjectId(hex);
    }

    /** Null in, null out. Message payloads carry ids as nullable strings. */
    public static ObjectId ofNullable(String hex) {
        return hex == null ? null : new ObjectId(hex);
    }

    public static String toHex(ObjectId id) {
        return id == null ? null : id.hex;
    }

    public static ObjectId hash(byte[] bytes) {
        try {
            return new ObjectId(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String shortId() {
        return hex.substring(0, 10);
    }

    @Override
    public int compareTo(ObjectId other) {
        return hex.compareTo(other.hex);
    }

    @Override
    public String toString() {
        return hex;
    }
}
