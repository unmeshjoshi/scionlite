package com.versioncontrol.scion.scn;

import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scion.sim.ScionSimulation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Landing: rebase on the server, then a compare-and-swap of the branch. The post: moving a ref "is a compare-and-swap
 * on a single Spanner row", there is no "global repository lock on the merge queue", and "concurrent changes can
 * conflict".
 */
class LandingTest {
    private static final String BASE = "one\ntwo\nthree\nfour\nfive\n";

    private static String seedMain(ScionSimulation sim, Scn scn) throws Exception {
        scn.write("shared", BASE);
        sim.await(scn.commit("base"));
        return sim.await(scn.land("main"));
    }

    @Test
    void theFirstLandingCreatesTheBranch() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");

            String landed = seedMain(sim, ada);

            assertEquals(landed, sim.await(ada.resolveRef("main")));
            assertEquals(landed, ada.head());
        }
    }

    @Test
    void concurrentLandingsOfDifferentFilesBothLand() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(3)
                .region("west", "ada", "bob").region("east", "cy").build(1)) {
            Scn ada = sim.scn("ada"), bob = sim.scn("bob"), cy = sim.scn("cy");
            String base = seedMain(sim, ada);
            sim.await(bob.checkout(base));
            sim.await(cy.checkout(base));
            ada.write("ada.txt", "from ada\n");
            bob.write("bob.txt", "from bob\n");
            cy.write("shared", BASE.replace("three", "THREE"));
            sim.await(ada.commit("ada"));
            sim.await(bob.commit("bob"));
            sim.await(cy.commit("cy"));

            List<TickCompletableFuture<String>> lands = List.of(ada.land("main"), bob.land("main"), cy.land("main"));
            List<String> landed = lands.stream().map(sim::await).toList();

            String main = sim.await(ada.resolveRef("main"));
            List<String> history = sim.await(ada.log(main, 0));
            assertTrue(history.containsAll(landed));
            assertEquals(5 - 1, history.size(), "base, then three landed changesets in a line: " + history);
            assertEquals(Map.of("shared", BASE.replace("three", "THREE"), "ada.txt", "from ada\n",
                    "bob.txt", "from bob\n"), sim.await(ada.readAllFiles(main)));
            assertEquals(main, landed.stream().filter(main::equals).findFirst().orElseThrow());
        }
    }

    @Test
    void editsToDifferentLinesOfOneFileMergeOnTheServer() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada", "bob").build(1)) {
            Scn ada = sim.scn("ada"), bob = sim.scn("bob");
            String base = seedMain(sim, ada);
            sim.await(bob.checkout(base));
            ada.write("shared", BASE.replace("one", "ONE"));
            bob.write("shared", BASE.replace("five", "FIVE"));
            sim.await(ada.commit("ada"));
            sim.await(bob.commit("bob"));

            sim.await(ada.land("main"));
            String landed = sim.await(bob.land("main"));

            assertEquals("ONE\ntwo\nthree\nfour\nFIVE\n", sim.await(bob.read("shared")));
            assertEquals(landed, sim.await(ada.resolveRef("main")));
        }
    }

    @Test
    void conflictingEditsStopLandingAndLeaveTheBranchAlone() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada", "bob").build(1)) {
            Scn ada = sim.scn("ada"), bob = sim.scn("bob");
            String base = seedMain(sim, ada);
            sim.await(bob.checkout(base));
            ada.write("shared", BASE.replace("two", "ada's two"));
            bob.write("shared", BASE.replace("two", "bob's two"));
            sim.await(ada.commit("ada"));
            String bobsChange = sim.await(bob.commit("bob"));
            String adaLanded = sim.await(ada.land("main"));

            Throwable failure = sim.awaitFailure(bob.land("main"));

            Scn.LandConflictException conflict = assertInstanceOf(Scn.LandConflictException.class, failure);
            assertEquals(List.of("shared"), conflict.paths());
            assertEquals(adaLanded, sim.await(ada.resolveRef("main")));
            assertEquals(bobsChange, bob.head());
        }
    }
}
