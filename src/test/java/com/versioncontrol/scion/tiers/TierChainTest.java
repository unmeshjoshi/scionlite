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
    @Test
    void listRefsTravelsFromScnThroughEveryTierToSpannerAndBack() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder()
                .servers(2)
                .region("west", "ada")
                .build(1)) {
            Scn ada = sim.scn("ada");

            assertEquals(Map.of(), sim.await(ada.listRefs()));

            assertEquals(1, sim.daemon("ada").forwardedUpstream());
            assertEquals(1, sim.cache("west").forwardedUpstream());
            assertEquals(1, sim.spanner().requestsServed());
        }
    }
}
