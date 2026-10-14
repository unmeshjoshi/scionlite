package com.versioncontrol.scion.protocol;

public enum PushStatus {
    /** Durable on a majority of replicas. */
    OK,
    /** A branch is not at the expected old value: someone else pushed first. Fetch and merge, then push again. */
    STALE,
    /** The new commit does not descend from the branch's current commit. */
    NON_FAST_FORWARD,
    /** The push does not include every object reachable from the new commits that the server lacks. */
    MISSING_OBJECTS,
    /** Malformed objects or updates. */
    INVALID,
    /** Sent to a replica that is not the primary. */
    NOT_PRIMARY
}
