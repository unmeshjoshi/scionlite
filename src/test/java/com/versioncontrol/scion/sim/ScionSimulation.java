package com.versioncontrol.scion.sim;

import com.tickloom.ProcessId;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;
import com.versioncontrol.scion.cache.ScionCache;
import com.versioncontrol.scion.daemon.ScionDaemon;
import com.versioncontrol.scion.scn.Scn;
import com.versioncontrol.scion.server.ScionServer;
import com.versioncontrol.scion.spanner.Spanner;
import com.versioncontrol.scion.storage.CloudStorage;
import com.versioncontrol.scion.topology.ScionNodeFactory;
import com.versioncontrol.scion.topology.Topology;
import com.versioncontrol.scion.topology.Timeouts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every Scion tier as a tickloom process on one simulated network, each with its own simulated disk.
 * Workstations are declared when the simulation is built, because each one runs a scion-daemon.
 */
public class ScionSimulation implements AutoCloseable {
    private static final int MAX_TICKS = 300_000;

    private final Cluster cluster;
    private final Topology topology;
    private final Map<String, Scn> scns = new HashMap<>();

    private ScionSimulation(Cluster cluster, Topology topology) {
        this.cluster = cluster;
        this.topology = topology;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int servers = 2;
        private final Map<String, List<String>> regions = new LinkedHashMap<>();
        private ScionServer.DerivedData derivedData = ScionServer.DerivedData.EAGER;
        private int daemonCapacity = ScionDaemon.DEFAULT_CAPACITY;

        public Builder servers(int servers) {
            this.servers = servers;
            return this;
        }

        /** Root trees are computed when first asked for, not when a changeset is created. */
        public Builder deferDerivedData() {
            this.derivedData = ScionServer.DerivedData.DEFERRED;
            return this;
        }

        public Builder daemonCapacity(int entries) {
            this.daemonCapacity = entries;
            return this;
        }

        public Builder region(String region, String... workstations) {
            regions.put(region, List.of(workstations));
            return this;
        }

        public ScionSimulation build(long seed) throws IOException {
            Topology topology = Topology.of(servers, regions);
            Cluster cluster = new Cluster()
                    .withProcessIds(topology.allNodes())
                    .withSeed(seed)
                    .withRequestTimeoutTicks(Timeouts.SCN)
                    .useSimulatedNetwork()
                    .build(new ScionNodeFactory(topology, derivedData, daemonCapacity))
                    .start();
            return new ScionSimulation(cluster, topology);
        }
    }

    public Cluster cluster() {
        return cluster;
    }

    public Topology topology() {
        return topology;
    }

    /** The scn client on a workstation, created on first use and connected only to that workstation's daemon. */
    public Scn scn(String workstation) throws IOException {
        Scn scn = scns.get(workstation);
        if (scn == null) {
            ProcessId daemon = Topology.daemon(workstation);
            if (!topology.regionByDaemon().containsKey(daemon)) {
                throw new IllegalArgumentException("no workstation " + workstation);
            }
            scn = cluster.newClientConnectedTo(Topology.scn(workstation), daemon, Scn::new);
            scns.put(workstation, scn);
        }
        return scn;
    }

    public Spanner spanner() {
        return cluster.getNode(topology.spanner());
    }

    public CloudStorage cloudStorage() {
        return cluster.getNode(topology.cloudStorage());
    }

    public ScionServer server(int n) {
        return cluster.getNode(Topology.server(n));
    }

    public List<ScionServer> servers() {
        List<ScionServer> servers = new ArrayList<>();
        topology.servers().forEach(id -> servers.add(cluster.getNode(id)));
        return servers;
    }

    public ScionCache cache(String region) {
        return cluster.getNode(Topology.cache(region));
    }

    public ScionDaemon daemon(String workstation) {
        return cluster.getNode(Topology.daemon(workstation));
    }

    public void tick() {
        cluster.tick();
    }

    public void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
        }
    }

    public void tickUntil(BooleanSupplier condition) {
        for (int i = 0; !condition.getAsBoolean(); i++) {
            if (i > MAX_TICKS) {
                fail("condition not met after " + MAX_TICKS + " ticks");
            }
            tick();
        }
    }

    /** Ticks until the future is done and returns its result; fails the test if it failed. */
    public <T> T await(TickCompletableFuture<T> future) {
        tickUntil(() -> !future.isPending());
        if (future.isFailed()) {
            throw new AssertionError("expected success but failed: " + future.getException(), future.getException());
        }
        return future.getResult();
    }

    /** Ticks until the future is done and returns its failure; fails the test if it succeeded. */
    public Throwable awaitFailure(TickCompletableFuture<?> future) {
        tickUntil(() -> !future.isPending());
        if (!future.isFailed()) {
            fail("expected failure but got " + future.getResult());
        }
        return future.getException();
    }

    @Override
    public void close() {
        cluster.close();
    }
}
