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

    @Test
    void refsAndChangesetsSurviveASpannerCrash() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").region("east", "bob").build(1)) {
            Scn ada = sim.scn("ada");
            ada.write("f", "durable\n");
            sim.await(ada.commit("c"));
            String landed = sim.await(ada.land("main"));

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

    @Test
    void serversKeepNoLongLivedStateSoAnotherServesWhenOneCrashes() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            ada.write("f", "1\n");
            sim.await(ada.commit("one"));
            sim.await(ada.land("main"));
            sim.cluster().crashNode(Topology.server(1));

            for (int i = 2; i <= 6; i++) {
                ada.write("f", i + "\n");
                sim.await(ada.commit("change " + i));
                sim.await(ada.land("main"));
            }

            assertEquals(Map.of("f", "6\n"), sim.await(ada.readAllFiles(sim.await(ada.resolveRef("main")))));
            sim.cluster().restartNode(Topology.server(1));
            sim.tickUntil(() -> sim.server(1).isInitialised());
            assertEquals(0, sim.server(1).cachedObjects(), "a restarted server starts empty");
        }
    }

    @Test
    void aPartitionBetweenCacheAndServersFailsCallsUntilItHeals() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            ada.write("f", "1\n");
            String first = sim.await(ada.commit("one"));
            sim.await(ada.getChangeset(first));   // now held by the daemon and the cache
            for (int n = 1; n <= 2; n++) {
                sim.cluster().partitionNodes(Topology.cache("west"), Topology.server(n));
            }

            sim.awaitFailure(ada.resolveRef("main"));
            // Immutable data already in the daemon and cache is still served.
            assertEquals(List.of(), sim.await(ada.getChangeset(first)).parents());

            for (int n = 1; n <= 2; n++) {
                sim.cluster().healPartition(Topology.cache("west"), Topology.server(n));
            }
            sim.tick(1);
            assertNull(sim.await(ada.resolveRef("main")));
            assertEquals(first, sim.await(ada.land("main")));
        }
    }

    @Test
    void contentIsDurableBeforeTheChangesetIsRecordedSoNoChangesetPointsAtMissingContent() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            for (int n = 1; n <= 2; n++) {
                sim.cluster().partitionNodes(Topology.server(n), Topology.SPANNER);
            }
            ada.write("f", "precious\n");

            sim.awaitFailure(ada.commit("lost"));

            assertTrue(sim.cloudStorage().contains(Blob.of("precious\n").id().hex()),
                    "the blob was stored first");
            assertTrue(sim.spanner().rows(SpannerApi.CHANGESETS).isEmpty(), "no changeset was recorded");

            for (int n = 1; n <= 2; n++) {
                sim.cluster().healPartition(Topology.server(n), Topology.SPANNER);
            }
            String id = sim.await(ada.commit("kept"));
            restart(sim, Topology.CLOUD_STORAGE);
            assertEquals(Map.of("f", "precious\n"), sim.await(ada.readAllFiles(id)));
        }
    }
}
