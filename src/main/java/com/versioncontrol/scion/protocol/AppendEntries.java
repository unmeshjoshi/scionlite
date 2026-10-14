package com.versioncontrol.scion.protocol;

import java.util.List;

/**
 * Primary to follower: entries starting right after {@code prevIndex}, and how far the log is committed. Sent
 * when an entry becomes durable on the primary and on every heartbeat, so it also carries retransmissions.
 */
public record AppendEntries(long prevIndex, List<LogEntry> entries, long commitIndex) {
}
