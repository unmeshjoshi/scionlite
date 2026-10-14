package com.versioncontrol.scionlite.showcase;

import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scionlite.changeset.FileChange;
import com.versioncontrol.scionlite.scn.Scn;
import com.versioncontrol.scionlite.server.ScionLiteServer;
import com.versioncontrol.scionlite.sim.ScionSimulation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One end-to-end story of how Scion works, from the post "Redesigning source control at Anthropic".
 *
 * <pre>
 * Setup: two regions, two servers, and three workstations, all on tickloom's simulated network.
 *
 *     west: alice, bob ──► scion-cache west ─┐
 *                                            ├──► scion-server 1, 2 ──► Spanner, cloud storage
 *     east: carol ───────► scion-cache east ─┘
 *
 * Each workstation runs scn, which talks only to that workstation's scion-daemon.
 *
 * Scenario:
 *  1. alice seeds the repository and lands it on main. The first landing creates the ref.
 *  2. bob, in the same region, checks out main. Every tree and blob comes from the west cache;
 *     no server is asked.
 *  3. carol, in the other region, makes a virtual checkout. Nothing is fetched until carol reads a file.
 *  4. bob edits the first line of src/app.txt and carol edits its last line. Each commit is created on the server
 *     and records only the file that changed. There is nothing to push: alice can read bob's commit at once.
 *  5. bob and carol land on main at the same time. There is no lock: each rebases on the server and moves main
 *     with a compare-and-swap, and whoever loses the race retries. The server merges the two edits.
 *  6. alice, who has not pulled anything, reads main and sees both edits, in one straight line of history.
 * </pre>
 */
class ShowcaseTest {
    /** The content of src/app.txt when main is seeded; bob and carol edit its first and last lines. */
    private static final String ORIGINAL_APP_FILE_CONTENT = "line 1\nline 2\nline 3\nline 4\nline 5\n";

    private static long serverRequests(ScionSimulation sim) {
        return sim.servers().stream().mapToLong(ScionLiteServer::requestsServed).sum();
    }

    @Test
    void howScionWorks() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2)
                .region("west", "alice", "bob").region("east", "carol").build(1)) {
            Scn alice = sim.scn("alice"), bob = sim.scn("bob"), carol = sim.scn("carol");

            // 1. alice seeds the repository and lands it on main.
            //    "moving one is a compare-and-swap on a single Spanner row"
            alice.write("README.md", "Scion Lite\n");
            alice.write("src/app.txt", ORIGINAL_APP_FILE_CONTENT);
            alice.write("docs/guide.txt", "how to use it\n");
            String seed = sim.await(alice.commit("seed"));
            assertEquals(seed, sim.await(alice.land("main")));
            assertEquals(seed, sim.await(bob.resolveRef("main")));

            // 2. bob checks out main and is served by the regional cache, which alice's landing already filled.
            //    "When a cache is missing an object, it asks the authoritative scion-servers"; here it is not missing.
            long serverRequestsBefore = serverRequests(sim);
            sim.await(bob.checkout(seed));
            assertEquals(Map.of("README.md", "Scion Lite\n", "src/app.txt", ORIGINAL_APP_FILE_CONTENT, "docs/guide.txt", "how to use it\n"),
                    bob.files());
            assertEquals(serverRequestsBefore, serverRequests(sim), "bob's checkout never reached a server");
            assertTrue(sim.cache("west").hits() > 0);

            // 3. carol makes a virtual checkout: "the client simply doesn't ask for trees it doesn't want".
            sim.await(carol.checkoutVirtual(seed));
            assertEquals(0, carol.objectRequests(), "a virtual checkout fetches nothing up front");
            assertEquals(ORIGINAL_APP_FILE_CONTENT, sim.await(carol.read("src/app.txt")));
            assertEquals(3, carol.objectRequests(), "the root tree, the src tree and one blob");

            // 4. Both commit. "every commit, rebase, or merge happens on the server, so there's nothing to push"
            //    "a changeset stores a list of the files that changed relative to its first parent"
            bob.write("src/app.txt", ORIGINAL_APP_FILE_CONTENT.replace("line 1", "line 1 by bob"));
            carol.write("src/app.txt", ORIGINAL_APP_FILE_CONTENT.replace("line 5", "line 5 by carol"));
            String bobsCommit = sim.await(bob.commit("bob's edit"));
            String carolsCommit = sim.await(carol.commit("carol's edit"));
            assertEquals(List.of("src/app.txt"),
                    sim.await(alice.getChangeset(bobsCommit)).changes().stream().map(FileChange::path).toList());
            assertEquals(ORIGINAL_APP_FILE_CONTENT.replace("line 1", "line 1 by bob"),
                    sim.await(alice.readAllFiles(bobsCommit)).get("src/app.txt"), "alice sees bob's commit at once");

            // 5. bob and carol land at the same time. No "global repository lock on the merge queue".
            List<TickCompletableFuture<String>> lands = List.of(bob.land("main"), carol.land("main"));
            List<String> landed = lands.stream().map(sim::await).toList();

            // 6. alice reads main: both edits, merged on the server, in one line of history.
            String main = sim.await(alice.resolveRef("main"));
            assertTrue(landed.contains(main), "main is the last of the two landings");
            assertEquals("line 1 by bob\nline 2\nline 3\nline 4\nline 5 by carol\n",
                    sim.await(alice.readAllFiles(main)).get("src/app.txt"));
            List<String> history = sim.await(alice.log(main, 0));
            assertEquals(3, history.size(), "seed, then two landed changesets: " + history);
            assertTrue(history.containsAll(landed));
            assertTrue(history.contains(seed));
            assertFalse(history.contains(bobsCommit) && history.contains(carolsCommit),
                    "at least one commit was rebased onto the other before landing");
        }
    }
}
