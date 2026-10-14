package com.versioncontrol.scion.failures;

import com.tickloom.ProcessId;
import com.versioncontrol.scion.objects.Blob;
import com.versioncontrol.scion.scn.Scn;
import com.versioncontrol.scion.sim.ScionSimulation;
import com.versioncontrol.scion.spanner.SpannerApi;
import com.versioncontrol.scion.topology.Topology;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Crashes and partitions, on the simulated network and simulated disks. */
class FailureTest {
    private static void restart(ScionSimulation sim, ProcessId id) {
        sim.cluster().crashNode(id);
        sim.cluster().restartNode(id);
        sim.tickUntil(() -> sim.cluster().getProcess(id).isInitialised());
    }

    /**
     * Scenario: alice lands a file on main, then Spanner, cloud storage and both servers crash and restart.
     * Then bob, in another region, finds main at alice's changeset and reads its file.
     * Everything durable was in Spanner and cloud storage; the servers kept nothing.
     */
    @Test
    void refsAndChangesetsSurviveASpannerCrash() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").region("east", "bob").build(1)) {
            Scn alice = sim.scn("alice");
            alice.write("f", "durable\n");
            sim.await(alice.commit("c"));
            String landed = sim.await(alice.land("main"));

            restart(sim, Topology.SPANNER);
            restart(sim, Topology.CLOUD_STORAGE);
            for (int n = 1; n <= 2; n++) {
                restart(sim, Topology.server(n));   // and no server remembers anything
            }

            Scn bob = sim.scn("bob");
            assertEquals(landed, sim.await(bob.resolveRef("main")));
            assertEquals(Map.of("f", "durable\n"), sim.await(bob.readAllFiles(landed)));
        }
    }

    /**
     * Scenario: alice lands one change, then server 1 crashes.
     * 1. alice commits and lands five more changes. The cache stops sending to server 1 after it times out.
     * 2. Server 1 restarts.
     * Then main has the last change, and the restarted server starts with nothing in memory.
     */
    @Test
    void serversKeepNoLongLivedStateSoAnotherServesWhenOneCrashes() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            alice.write("f", "1\n");
            sim.await(alice.commit("one"));
            sim.await(alice.land("main"));
            sim.cluster().crashNode(Topology.server(1));

            for (int i = 2; i <= 6; i++) {
                alice.write("f", i + "\n");
                sim.await(alice.commit("change " + i));
                sim.await(alice.land("main"));
            }

            assertEquals(Map.of("f", "6\n"), sim.await(alice.readAllFiles(sim.await(alice.resolveRef("main")))));
            sim.cluster().restartNode(Topology.server(1));
            sim.tickUntil(() -> sim.server(1).isInitialised());
            assertEquals(0, sim.server(1).cachedObjects(), "a restarted server starts empty");
        }
    }

    /**
     * Scenario: the west cache is cut off from every server.
     * 1. alice commits and reads the changeset, so the daemon and cache hold it.
     * 2. The partition starts. Resolving main fails, but the changeset already cached is still served.
     * 3. The partition heals.
     * Then alice resolves main again and lands.
     */
    @Test
    void aPartitionBetweenCacheAndServersFailsCallsUntilItHeals() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            alice.write("f", "1\n");
            String first = sim.await(alice.commit("one"));
            sim.await(alice.getChangeset(first));   // now held by the daemon and the cache
            for (int n = 1; n <= 2; n++) {
                sim.cluster().partitionNodes(Topology.cache("west"), Topology.server(n));
            }

            sim.awaitFailure(alice.resolveRef("main"));
            // Immutable data already in the daemon and cache is still served.
            assertEquals(List.of(), sim.await(alice.getChangeset(first)).parents());

            for (int n = 1; n <= 2; n++) {
                sim.cluster().healPartition(Topology.cache("west"), Topology.server(n));
            }
            sim.tick(1);
            assertNull(sim.await(alice.resolveRef("main")));
            assertEquals(first, sim.await(alice.land("main")));
        }
    }

    /**
     * Scenario: every server is cut off from Spanner while alice commits.
     * 1. The commit fails. Its blob is already in cloud storage, but no changeset row was written.
     * 2. The partition heals and alice commits again. Cloud storage then crashes and restarts.
     * Then alice still reads the file: a changeset is recorded only after its content is durable.
     */
    @Test
    void contentIsDurableBeforeTheChangesetIsRecordedSoNoChangesetPointsAtMissingContent() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            for (int n = 1; n <= 2; n++) {
                sim.cluster().partitionNodes(Topology.server(n), Topology.SPANNER);
            }
            alice.write("f", "precious\n");

            sim.awaitFailure(alice.commit("lost"));

            assertTrue(sim.cloudStorage().contains(Blob.of("precious\n").id().hex()),
                    "the blob was stored first");
            assertTrue(sim.spanner().rows(SpannerApi.CHANGESETS).isEmpty(), "no changeset was recorded");

            for (int n = 1; n <= 2; n++) {
                sim.cluster().healPartition(Topology.server(n), Topology.SPANNER);
            }
            String id = sim.await(alice.commit("kept"));
            restart(sim, Topology.CLOUD_STORAGE);
            assertEquals(Map.of("f", "precious\n"), sim.await(alice.readAllFiles(id)));
        }
    }

    /**
     * Scenario: Spanner restarts while the servers stay up.
     * 1. alice lands a change. The server now holds a Spanner session.
     * 2. Spanner restarts, which deletes every session; requests to a deleted one return NOT_FOUND.
     * 3. alice lands another change.
     * Then the server dropped its old session, created a new one and carried on: the landing succeeds.
     */
    @Test
    void aServerReplacesItsSpannerSessionsAfterSpannerRestarts() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(1).region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            alice.write("f", "1\n");
            sim.await(alice.commit("one"));
            sim.await(alice.land("main"));
            long sessions = sim.server(1).sessionsCreated();

            restart(sim, Topology.SPANNER);

            alice.write("f", "2\n");
            sim.await(alice.commit("two"));
            String landed = sim.await(alice.land("main"));
            assertEquals(landed, sim.await(alice.resolveRef("main")));
            assertTrue(sim.server(1).sessionsCreated() > sessions, "the server created a new session");
        }
    }
}
