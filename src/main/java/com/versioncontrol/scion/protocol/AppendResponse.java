package com.versioncontrol.scion.protocol;

/** Follower to primary: every entry up to this index is durable on the follower. */
public record AppendResponse(long durableIndex) {
}
