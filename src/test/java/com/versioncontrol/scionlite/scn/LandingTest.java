package com.versioncontrol.scionlite.scn;

import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scionlite.sim.ScionSimulation;
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

    /**
     * Scenario: alice commits a file and lands it on main, which does not exist yet.
     * Then main points at alice's changeset, and so does alice's head.
     */
    @Test
    void theFirstLandingCreatesTheBranch() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");

            String landed = seedMain(sim, alice);

            assertEquals(landed, sim.await(alice.resolveRef("main")));
            assertEquals(landed, alice.head());
        }
    }

    /**
     * Scenario: three workstations in two regions land on main at the same time.
     * 1. alice seeds main; bob and carol check it out.
     * 2. alice adds alice.txt, bob adds bob.txt, carol edits shared. Each commits.
     * 3. All three land at once. There is no lock: each rebases onto the tip on the server, then moves main by
     *    compare-and-swap; whoever loses the race retries from the new tip.
     * Then all three land, main's history is the seed plus three changesets in a straight line,
     * and main holds all three edits.
     */
    @Test
    void concurrentLandingsOfDifferentFilesBothLand() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(3)
                .region("west", "alice", "bob").region("east", "carol").build(1)) {
            Scn alice = sim.scn("alice"), bob = sim.scn("bob"), carol = sim.scn("carol");
            String base = seedMain(sim, alice);
            sim.await(bob.checkout(base));
            sim.await(carol.checkout(base));
            alice.write("alice.txt", "from alice\n");
            bob.write("bob.txt", "from bob\n");
            carol.write("shared", BASE.replace("three", "THREE"));
            sim.await(alice.commit("alice"));
            sim.await(bob.commit("bob"));
            sim.await(carol.commit("carol"));

            List<TickCompletableFuture<String>> lands = List.of(alice.land("main"), bob.land("main"), carol.land("main"));
            List<String> landed = lands.stream().map(sim::await).toList();

            String main = sim.await(alice.resolveRef("main"));
            List<String> history = sim.await(alice.log(main, 0));
            assertTrue(history.containsAll(landed));
            assertEquals(5 - 1, history.size(), "base, then three landed changesets in a line: " + history);
            assertEquals(Map.of("shared", BASE.replace("three", "THREE"), "alice.txt", "from alice\n",
                    "bob.txt", "from bob\n"), sim.await(alice.readAllFiles(main)));
            assertEquals(main, landed.stream().filter(main::equals).findFirst().orElseThrow());
        }
    }

    /**
     * Scenario: alice and bob edit different lines of the same file.
     * 1. alice edits line one and bob edits line five, both from the same base. Each commits.
     * 2. alice lands first. bob's landing then rebases onto alice's change; the server merges the file line by line.
     * Then bob's working copy has both edits, and main is bob's landed changeset.
     */
    @Test
    void editsToDifferentLinesOfOneFileMergeOnTheServer() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice", "bob").build(1)) {
            Scn alice = sim.scn("alice"), bob = sim.scn("bob");
            String base = seedMain(sim, alice);
            sim.await(bob.checkout(base));
            alice.write("shared", BASE.replace("one", "ONE"));
            bob.write("shared", BASE.replace("five", "FIVE"));
            sim.await(alice.commit("alice"));
            sim.await(bob.commit("bob"));

            sim.await(alice.land("main"));
            String landed = sim.await(bob.land("main"));

            assertEquals("ONE\ntwo\nthree\nfour\nFIVE\n", sim.await(bob.read("shared")));
            assertEquals(landed, sim.await(alice.resolveRef("main")));
        }
    }

    /**
     * Scenario: alice and bob change the same line in different ways.
     * 1. Both commit; alice lands first.
     * 2. bob's landing rebases onto alice's change and the server reports a conflict in shared.
     * Then bob's landing fails with the conflicting path, main stays at alice's change, and bob's head is still bob's commit.
     */
    @Test
    void conflictingEditsStopLandingAndLeaveTheBranchAlone() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice", "bob").build(1)) {
            Scn alice = sim.scn("alice"), bob = sim.scn("bob");
            String base = seedMain(sim, alice);
            sim.await(bob.checkout(base));
            alice.write("shared", BASE.replace("two", "alice's two"));
            bob.write("shared", BASE.replace("two", "bob's two"));
            sim.await(alice.commit("alice"));
            String bobsChange = sim.await(bob.commit("bob"));
            String aliceLanded = sim.await(alice.land("main"));

            Throwable failure = sim.awaitFailure(bob.land("main"));

            Scn.LandConflictException conflict = assertInstanceOf(Scn.LandConflictException.class, failure);
            assertEquals(List.of("shared"), conflict.paths());
            assertEquals(aliceLanded, sim.await(alice.resolveRef("main")));
            assertEquals(bobsChange, bob.head());
        }
    }
}
