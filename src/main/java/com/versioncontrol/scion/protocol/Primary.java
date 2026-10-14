package com.versioncontrol.scion.protocol;

import com.tickloom.ProcessId;

import java.util.Collection;
import java.util.Comparator;

/** The primary is fixed: the replica whose id sorts first. Servers and clients work it out the same way. */
public final class Primary {
    private Primary() {
    }

    public static ProcessId of(Collection<ProcessId> replicas) {
        return replicas.stream().min(Comparator.comparing(ProcessId::name))
                .orElseThrow(() -> new IllegalArgumentException("no replicas"));
    }
}
