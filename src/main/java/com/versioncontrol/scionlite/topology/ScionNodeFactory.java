package com.versioncontrol.scionlite.topology;

import com.tickloom.Process;
import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.versioncontrol.scionlite.cache.ScionCache;
import com.versioncontrol.scionlite.daemon.ScionDaemon;
import com.versioncontrol.scionlite.server.ScionLiteServer;
import com.versioncontrol.scionlite.spanner.Spanner;
import com.versioncontrol.scionlite.storage.CloudStorage;

import java.util.List;

/** Builds the process for each id in the topology: its role follows from the id. */
public class ScionNodeFactory implements ProcessFactory {
    private final Topology topology;
    private final ScionLiteServer.DerivedData derivedData;
    private final int daemonCapacity;

    public ScionNodeFactory(Topology topology) {
        this(topology, ScionLiteServer.DerivedData.EAGER, ScionDaemon.DEFAULT_CAPACITY);
    }

    public ScionNodeFactory(Topology topology, ScionLiteServer.DerivedData derivedData, int daemonCapacity) {
        this.topology = topology;
        this.derivedData = derivedData;
        this.daemonCapacity = daemonCapacity;
    }

    @Override
    public Process create(List<ProcessId> peerIds, ProcessParams params) {
        ProcessId id = params.id();
        if (id.equals(topology.spanner())) {
            return new Spanner(params);
        }
        if (id.equals(topology.cloudStorage())) {
            return new CloudStorage(params);
        }
        if (topology.servers().contains(id)) {
            return new ScionLiteServer(withTimeout(params, Timeouts.SERVER), topology, derivedData);
        }
        if (topology.cacheByRegion().containsValue(id)) {
            return new ScionCache(withTimeout(params, Timeouts.CACHE), topology);
        }
        if (topology.regionByDaemon().containsKey(id)) {
            return new ScionDaemon(withTimeout(params, Timeouts.DAEMON), topology.cacheFor(id), daemonCapacity);
        }
        throw new IllegalArgumentException("no role for " + id);
    }

    private static ProcessParams withTimeout(ProcessParams p, int ticks) {
        return new ProcessParams(p.id(), p.messageBus(), p.messageCodec(), ticks, p.clock(), p.idGenerator(),
                p.fileSystem());
    }
}
