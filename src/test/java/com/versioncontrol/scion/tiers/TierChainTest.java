package com.versioncontrol.scion.tiers;

import com.versioncontrol.scion.sim.ScionSimulation;
import com.versioncontrol.scion.scn.Scn;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The first slice: one request travels the whole tier chain of the post,
 * scn → scion-daemon → scion-cache → scion-server → Spanner, and the reply comes back the same way.
 */
class TierChainTest {
    /**
     * Scenario: alice, on a fresh repository, lists the refs.
     * 1. scn sends listRefs to alice's daemon. Refs can change, so the daemon cannot answer from its cache and forwards it.
     * 2. The west cache forwards it to a server for the same reason.
     * 3. The server opens a Spanner session (CreateSession), Reads the refs table in it, and the empty answer comes
     *    back down the chain.
     * Then each tier forwarded exactly one request, and Spanner served two: CreateSession and Read.
     */
    @Test
    void listRefsTravelsFromScnThroughEveryTierToSpannerAndBack() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder()
                .servers(2)
                .region("west", "alice")
                .build(1)) {
            Scn alice = sim.scn("alice");

            assertEquals(Map.of(), sim.await(alice.listRefs()));

            assertEquals(1, sim.daemon("alice").forwardedUpstream());
            assertEquals(1, sim.cache("west").forwardedUpstream());
            assertEquals(2, sim.spanner().requestsServed());   // CreateSession, then Read
        }
    }
}
