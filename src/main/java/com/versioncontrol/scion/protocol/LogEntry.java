package com.versioncontrol.scion.protocol;

import java.util.List;

/**
 * One accepted push in the replicated log. Index 1 is the first entry. The objects are those the push brought that
 * the primary did not already have.
 */
public record LogEntry(long index, List<byte[]> objects, List<RefUpdate> updates) {
}
