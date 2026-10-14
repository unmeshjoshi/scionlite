package com.versioncontrol.scion.repo;

/** A command that cannot be carried out as asked: nothing to commit, unknown branch, dirty working tree... */
public class ScionException extends RuntimeException {
    public ScionException(String message) {
        super(message);
    }
}
