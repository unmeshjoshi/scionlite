package com.versioncontrol.scion.tiers;

import com.versioncontrol.scion.scn.Scn;
import com.versioncontrol.scion.server.ScionServer;
import com.versioncontrol.scion.sim.ScionSimulation;
import com.versioncontrol.scion.topology.Topology;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The post: "The scion-daemon, scion-cache, and scion-server all respond to identical RPCs for this
 * protocol", and each tier "maintains a cache hit rate of over 99%" (not measured here). */
class TiersTest {
    private static Map<String, String> repo(int files) {
        Map<String, String> contents = new HashMap<>();
        for (int i = 0; i < files; i++) {
            contents.put("dir" + (i % 4) + "/file" + i, "content " + i + "\n");
        }
        return contents;
    }

    private static long serverRequests(ScionSimulation sim) {
        return sim.servers().stream().mapToLong(ScionServer::requestsServed).sum();
    }

    /**
     * Scenario: alice and bob work in the same region.
     * 1. alice creates a changeset of 20 files and reads them all. Every object passes through the west cache.
     * 2. bob reads the same changeset.
     * Then bob gets the same files, no server is asked, and the west cache answers; bob's own daemon had nothing yet.
     */
    @Test
    void aSecondWorkstationInTheRegionIsServedFromTheCache() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "alice", "bob").build(1)) {
            String id = sim.await(sim.scn("alice").createChangeset(List.of(), repo(20), "repo"));
            Map<String, String> aliceSaw = sim.await(sim.scn("alice").readAllFiles(id));
            long before = serverRequests(sim);

            Map<String, String> bobSaw = sim.await(sim.scn("bob").readAllFiles(id));

            assertEquals(aliceSaw, bobSaw);
            assertEquals(before, serverRequests(sim), "bob's fetch never reached a server");
            assertTrue(sim.cache("west").hits() > 20);
            assertEquals(0, sim.daemon("bob").hits());
        }
    }

    /**
     * Scenario: alice reads the same changeset twice.
     * 1. The first read fills alice's daemon.
     * 2. The second read is answered by the daemon alone.
     * Then the daemon forwarded nothing more upstream.
     */
    @Test
    void aDaemonAnswersRepeatedReadsWithoutGoingUpstream() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String id = sim.await(alice.createChangeset(List.of(), repo(10), "repo"));
            sim.await(alice.readAllFiles(id));
            long forwarded = sim.daemon("alice").forwardedUpstream();

            sim.await(alice.readAllFiles(id));

            assertEquals(forwarded, sim.daemon("alice").forwardedUpstream());
        }
    }

    /**
     * Scenario: alice's daemon may keep only 5 replies, and alice reads a 30-file changeset twice.
     * Then both reads return every file, and the daemon still holds at most 5 entries: older ones were evicted.
     */
    @Test
    void theDaemonCacheIsBounded() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").daemonCapacity(5).build(1)) {
            Scn alice = sim.scn("alice");
            Map<String, String> contents = repo(30);
            String id = sim.await(alice.createChangeset(List.of(), contents, "repo"));

            assertEquals(contents, sim.await(alice.readAllFiles(id)));
            assertEquals(contents, sim.await(alice.readAllFiles(id)));

            assertTrue(sim.daemon("alice").cachedEntries() <= 5);
        }
    }

    /**
     * Scenario: besides alice (through a daemon), one test client is connected straight to the west cache and one
     * straight to a server. This breaks the strict tiering on purpose, only to show the RPCs are identical.
     * Then all three get the same files and the same changeset.
     */
    @Test
    void cacheAndServerAnswerTheSameCallsAsTheDaemon() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "alice").build(1)) {
            String id = sim.await(sim.scn("alice").createChangeset(List.of(), repo(8), "repo"));
            Scn onCache = sim.cluster().newClientConnectedTo(com.tickloom.ProcessId.of("scn-on-cache"),
                    Topology.cache("west"), Scn::new);
            Scn onServer = sim.cluster().newClientConnectedTo(com.tickloom.ProcessId.of("scn-on-server"),
                    Topology.server(2), Scn::new);

            Map<String, String> viaDaemon = sim.await(sim.scn("alice").readAllFiles(id));

            assertEquals(viaDaemon, sim.await(onCache.readAllFiles(id)));
            assertEquals(viaDaemon, sim.await(onServer.readAllFiles(id)));
            assertEquals(sim.await(sim.scn("alice").getChangeset(id)), sim.await(onServer.getChangeset(id)));
        }
    }
}
