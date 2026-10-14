package com.versioncontrol.scionlite.topology;

import com.tickloom.ProcessId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who talks to whom. Tiers are strict, as in the post: a daemon talks only to its region's cache, a cache only to
 * the servers, and only servers talk to Spanner and cloud storage.
 */
public record Topology(ProcessId spanner, ProcessId cloudStorage, List<ProcessId> servers,
                       Map<String, ProcessId> cacheByRegion, Map<ProcessId, String> regionByDaemon) {

    public static final ProcessId SPANNER = ProcessId.of("spanner");
    public static final ProcessId CLOUD_STORAGE = ProcessId.of("cloud-storage");

    public static ProcessId server(int n) {
        return ProcessId.of("server-" + n);
    }

    public static ProcessId cache(String region) {
        return ProcessId.of("cache-" + region);
    }

    public static ProcessId daemon(String workstation) {
        return ProcessId.of("daemon-" + workstation);
    }

    public static ProcessId scn(String workstation) {
        return ProcessId.of("scn-" + workstation);
    }

    /** {@code workstationsByRegion}: region name to the workstations (nodes) in it. */
    public static Topology of(int servers, Map<String, List<String>> workstationsByRegion) {
        List<ProcessId> serverIds = new ArrayList<>();
        for (int i = 1; i <= servers; i++) {
            serverIds.add(server(i));
        }
        Map<String, ProcessId> caches = new LinkedHashMap<>();
        Map<ProcessId, String> daemons = new LinkedHashMap<>();
        workstationsByRegion.forEach((region, workstations) -> {
            caches.put(region, cache(region));
            workstations.forEach(w -> daemons.put(daemon(w), region));
        });
        return new Topology(SPANNER, CLOUD_STORAGE, List.copyOf(serverIds), caches, daemons);
    }

    public ProcessId cacheFor(ProcessId daemon) {
        return cacheByRegion.get(regionByDaemon.get(daemon));
    }

    /** Every server-side process, for building the cluster. */
    public List<ProcessId> allNodes() {
        List<ProcessId> all = new ArrayList<>();
        all.add(spanner);
        all.add(cloudStorage);
        all.addAll(servers);
        all.addAll(cacheByRegion.values());
        all.addAll(regionByDaemon.keySet());
        return all;
    }
}
