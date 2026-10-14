package com.versioncontrol.scionlite.objects;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The bytes of one file. */
public final class Blob implements ScionObject {
    private final byte[] data;

    public Blob(byte[] data) {
        this.data = data.clone();
    }

    public static Blob of(String text) {
        return new Blob(text.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] data() {
        return data.clone();
    }

    @Override
    public ObjectType type() {
        return ObjectType.BLOB;
    }

    @Override
    public byte[] content() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Blob other && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "Blob[" + data.length + " bytes]";
    }
}
