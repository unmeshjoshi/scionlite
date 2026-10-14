package com.versioncontrol.scion.sim;

import com.versioncontrol.scion.client.ScionClient;
import com.versioncontrol.scion.repo.Repository;
import com.versioncontrol.scion.server.ScionServer;
import com.versioncontrol.scion.server.ScionServerFactory;
import com.tickloom.ProcessId;
import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.testkit.Cluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A Scion deployment in simulation: replicas on tickloom's {@link Cluster} (simulated network, a simulated disk
 * each) and workstations whose repositories live on their own simulated disks. {@link #tick()} advances all of it.
 */
public class ScionSimulation implements AutoCloseable {
    public static final ProcessId ALPHA = ProcessId.of("alpha");   // sorts first: the primary
    public static final ProcessId BETA = ProcessId.of("beta");
    public static final ProcessId GAMMA = ProcessId.of("gamma");
    public static final int REQUEST_TIMEOUT_TICKS = 200;
    private static final int MAX_TICKS = 5000;

    private final Cluster cluster;
    private final List<Workstation> workstations = new ArrayList<>();

    private ScionSimulation(Cluster cluster) {
        this.cluster = cluster;
    }

    public static ScionSimulation threeReplicas(long seed) throws IOException {
        return create(List.of(ALPHA, BETA, GAMMA), seed, false);
    }

    public static ScionSimulation create(List<ProcessId> replicas, long seed, boolean lossy) throws IOException {
        Cluster cluster = new Cluster()
                .withProcessIds(replicas)
                .withSeed(seed)
                .withRequestTimeoutTicks(REQUEST_TIMEOUT_TICKS)
                .useSimulatedNetwork(lossy)
                .build(new ScionServerFactory())
                .start();
        return new ScionSimulation(cluster);
    }

    public Cluster cluster() {
        return cluster;
    }

    public ScionServer server(ProcessId id) {
        return cluster.getNode(id);
    }

    public List<ScionServer> servers() {
        return cluster.getServerProcessIds().stream().map(this::server).toList();
    }

    /** A workstation with an empty repository. */
    public Workstation workstation(String name) throws IOException {
        Workstation workstation = newWorkstation(name);
        workstation.repo = new Repository(workstation.disk, name, workstation.clock);
        await(workstation.repo.open());
        return workstation;
    }

    /** A workstation that clones the repository from the servers. */
    public Workstation cloneTo(String name) throws IOException {
        Workstation workstation = newWorkstation(name);
        workstation.repo = new Repository(workstation.disk, name, workstation.clock);
        await(workstation.repo.cloneFrom(workstation.client));
        return workstation;
    }

    private Workstation newWorkstation(String name) throws IOException {
        ProcessId id = ProcessId.of(name);
        ScionClient client = cluster.newClient(id, ScionClient::new);
        Workstation workstation = new Workstation(id, client, new SimulatedFileSystem(), cluster.getClockForProcess(id));
        workstations.add(workstation);
        return workstation;
    }

    /** Power cut on the workstation, then the repository is opened again from what reached its disk. */
    public void crashAndReopen(Workstation workstation) {
        workstation.disk.crash();
        workstation.disk.restart();
        workstation.repo = new Repository(workstation.disk, workstation.id.name(), workstation.clock);
        await(workstation.repo.open());
    }

    public void tick() {
        cluster.tick();
        workstations.forEach(Workstation::tick);
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
