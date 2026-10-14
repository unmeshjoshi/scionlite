package com.versioncontrol.scion.objects;

/** Bytes that are not a well-formed object, or whose hash does not match the id they were stored under. */
public class CorruptObjectException extends RuntimeException {
    public CorruptObjectException(String message) {
        super(message);
    }
}
