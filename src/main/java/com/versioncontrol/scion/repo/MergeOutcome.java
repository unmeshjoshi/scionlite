package com.versioncontrol.scion.repo;

import com.versioncontrol.scion.objects.ObjectId;

import java.util.List;

/** {@code commit} is the branch's commit after the merge (the new merge commit for MERGED), null for CONFLICTS. */
public record MergeOutcome(Kind kind, ObjectId commit, List<String> conflicts) {
    public enum Kind {UP_TO_DATE, FAST_FORWARD, MERGED, CONFLICTS}
}
