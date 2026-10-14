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

    @Test
    void aSecondWorkstationInTheRegionIsServedFromTheCache() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "ada", "bob").build(1)) {
            String id = sim.await(sim.scn("ada").createChangeset(List.of(), repo(20), "repo"));
            Map<String, String> adaSaw = sim.await(sim.scn("ada").readAllFiles(id));
            long before = serverRequests(sim);

            Map<String, String> bobSaw = sim.await(sim.scn("bob").readAllFiles(id));

            assertEquals(adaSaw, bobSaw);
            assertEquals(before, serverRequests(sim), "bob's fetch never reached a server");
            assertTrue(sim.cache("west").hits() > 20);
            assertEquals(0, sim.daemon("bob").hits());
        }
    }

    @Test
    void aDaemonAnswersRepeatedReadsWithoutGoingUpstream() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String id = sim.await(ada.createChangeset(List.of(), repo(10), "repo"));
            sim.await(ada.readAllFiles(id));
            long forwarded = sim.daemon("ada").forwardedUpstream();

            sim.await(ada.readAllFiles(id));

            assertEquals(forwarded, sim.daemon("ada").forwardedUpstream());
        }
    }

    @Test
    void theDaemonCacheIsBounded() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").daemonCapacity(5).build(1)) {
            Scn ada = sim.scn("ada");
            Map<String, String> contents = repo(30);
            String id = sim.await(ada.createChangeset(List.of(), contents, "repo"));

            assertEquals(contents, sim.await(ada.readAllFiles(id)));
            assertEquals(contents, sim.await(ada.readAllFiles(id)));

            assertTrue(sim.daemon("ada").cachedEntries() <= 5);
        }
    }

    @Test
    void cacheAndServerAnswerTheSameCallsAsTheDaemon() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "ada").build(1)) {
            String id = sim.await(sim.scn("ada").createChangeset(List.of(), repo(8), "repo"));
            Scn onCache = sim.cluster().newClientConnectedTo(com.tickloom.ProcessId.of("scn-on-cache"),
                    Topology.cache("west"), Scn::new);
            Scn onServer = sim.cluster().newClientConnectedTo(com.tickloom.ProcessId.of("scn-on-server"),
                    Topology.server(2), Scn::new);

            Map<String, String> viaDaemon = sim.await(sim.scn("ada").readAllFiles(id));

            assertEquals(viaDaemon, sim.await(onCache.readAllFiles(id)));
            assertEquals(viaDaemon, sim.await(onServer.readAllFiles(id)));
            assertEquals(sim.await(sim.scn("ada").getChangeset(id)), sim.await(onServer.getChangeset(id)));
        }
    }
}
