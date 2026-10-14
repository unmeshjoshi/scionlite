package com.versioncontrol.scion.showcase;

import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scion.api.ScionApi.FileDiff;
import com.versioncontrol.scion.scn.Scn;
import com.versioncontrol.scion.server.ScionServer;
import com.versioncontrol.scion.sim.ScionSimulation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A day of work as the post describes it: "nearly all of our clients were agents and jobs that are online by
 * definition". Agents change code, a bot writes without a checkout, changes land concurrently, one conflicts and is
 * fixed, and a CI job tests main after the merge.
 *
 * <pre>
 * Setup: two regions, two servers, four client machines, on tickloom's simulated network.
 *
 *     west: alice (two agent jobs), bob ──► scion-cache west ─┐
 *                                                              ├──► scion-server 1, 2 ──► Spanner, cloud storage
 *     east: carol (a bot), dave (CI) ──────► scion-cache east ─┘
 *
 * Scenario:
 *  0. main holds a small monorepo: src/, docs/, infra/ and research/.
 *  1. An agent on alice makes a sparse checkout of src/ only, edits line 1 of src/app.txt, and commits.
 *  2. An agent on bob makes a virtual checkout and reads only src/app.txt. Everything it fetches comes from the west
 *     cache. It edits line 5 and commits.
 *  3. A bot on carol updates docs/guide.txt with no checkout at all, through server calls only.
 *  4. A second agent job on alice checks out src/. Its objects come from alice's daemon, which the first job filled.
 *     It edits line 1 too, differently, and commits.
 *  5. The changes from steps 1-3 land at the same time. There is no lock: each rebases on the server and moves main
 *     by compare-and-swap; the server merges the two edits to src/app.txt.
 *  6. The second job lands its change. It conflicts with step 1's line 1. The agent checks out the new main, makes an
 *     edit that keeps both intents, and lands it.
 *  7. A CI job on dave tests main after the merge, with no working copy: it asks the server for the merge base and
 *     the diff, then reads only the changed files through a virtual checkout.
 *
 * Not modelled: in the post, landing goes through Antique code review and its merge queue, and "Claude can typically
 * fix these conflicts". Here scn lands directly, and the test plays the agent that fixes the conflict.
 * </pre>
 */
class AgentScaleScenarioTest {
    /** The content of src/app.txt when main is seeded; the agents edit its first and last lines. */
    private static final String ORIGINAL_APP_FILE_CONTENT = "line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final Map<String, String> MONOREPO = Map.of(
            "README.md", "the monorepo\n",
            "src/app.txt", ORIGINAL_APP_FILE_CONTENT,
            "src/util.txt", "helpers\n",
            "docs/guide.txt", "how to use it\n",
            "infra/deploy.txt", "deploy steps\n",
            "research/notes.txt", "experiments\n");

    private static long serverRequests(ScionSimulation sim) {
        return sim.servers().stream().mapToLong(ScionServer::requestsServed).sum();
    }

    @Test
    void agentsAndJobsChangeTheMonorepoConcurrentlyAndCiTestsMainAfterTheMerge() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2)
                .region("west", "alice", "bob").region("east", "carol", "dave").build(1)) {
            Scn agent = sim.scn("alice"), secondAgent = sim.job("alice", "agent-2");
            Scn bobAgent = sim.scn("bob"), bot = sim.scn("carol"), ci = sim.scn("dave");

            // 0. main holds the monorepo. It is created and landed through server calls alone.
            String seed = sim.await(bot.createChangeset(List.of(), MONOREPO, "seed the monorepo"));
            String main = sim.await(bot.landChangeset("main", seed));

            // 1. Sparse checkout of src/: "only contain the directories the client asks for".
            sim.await(agent.checkoutSparse(main, List.of("src")));
            assertEquals(Map.of("src/app.txt", ORIGINAL_APP_FILE_CONTENT, "src/util.txt", "helpers\n"), agent.files());
            assertEquals(4, agent.objectRequests(), "the root and src trees, and src's two files");
            agent.write("src/app.txt", ORIGINAL_APP_FILE_CONTENT.replace("line 1", "line 1 by alice"));
            sim.await(agent.commit("alice: change line 1"));

            // 2. Virtual checkout: files are materialized "on demand". The west cache already holds what alice fetched.
            long serverRequestsBefore = serverRequests(sim);
            sim.await(bobAgent.checkoutVirtual(main));
            assertEquals(ORIGINAL_APP_FILE_CONTENT, sim.await(bobAgent.read("src/app.txt")));
            assertEquals(3, bobAgent.objectRequests(), "the root tree, the src tree and one file");
            assertEquals(serverRequestsBefore, serverRequests(sim), "the west cache answered bob's fetches");
            bobAgent.write("src/app.txt", ORIGINAL_APP_FILE_CONTENT.replace("line 5", "line 5 by bob"));
            sim.await(bobAgent.commit("bob: change line 5"));

            // 3. "many write workloads can also skip the checkout and function entirely via API"
            String botsChange = sim.await(bot.createChangeset(List.of(main),
                    Map.of("docs/guide.txt", "how to use it, updated by the bot\n"), "bot: refresh the guide"));
            assertEquals(0, bot.objectRequests(), "the bot fetched no files");

            // 4. "jobs on the same node reuse most of the same objects": alice's daemon serves the second job.
            long forwardedBefore = sim.daemon("alice").forwardedUpstream();
            sim.await(secondAgent.checkoutSparse(main, List.of("src")));
            assertEquals(forwardedBefore, sim.daemon("alice").forwardedUpstream(), "nothing went past alice's daemon");
            secondAgent.write("src/app.txt", ORIGINAL_APP_FILE_CONTENT.replace("line 1", "line 1 by agent-2"));
            sim.await(secondAgent.commit("agent-2: change line 1"));

            // 5. Three landings at once, with no "global repository lock on the merge queue".
            List<TickCompletableFuture<String>> lands = List.of(
                    agent.land("main"), bobAgent.land("main"), bot.landChangeset("main", botsChange));
            List<String> landed = lands.stream().map(sim::await).toList();
            String afterThree = sim.await(ci.resolveRef("main"));
            assertTrue(landed.contains(afterThree), "main is the last of the three landings");
            Map<String, String> afterThreeFiles = sim.await(bot.readAllFiles(afterThree));
            assertEquals("line 1 by alice\nline 2\nline 3\nline 4\nline 5 by bob\n", afterThreeFiles.get("src/app.txt"),
                    "the server merged alice's and bob's edits");
            assertEquals("how to use it, updated by the bot\n", afterThreeFiles.get("docs/guide.txt"));

            // 6. "concurrent changes can conflict". The agent rebuilds its change on the new main and lands it.
            Throwable failure = sim.awaitFailure(secondAgent.land("main"));
            Scn.LandConflictException conflict = assertInstanceOf(Scn.LandConflictException.class, failure);
            assertEquals(List.of("src/app.txt"), conflict.paths());
            assertEquals(afterThree, sim.await(ci.resolveRef("main")), "a conflict leaves main alone");
            sim.await(secondAgent.checkoutSparse(afterThree, List.of("src")));
            String merged = sim.await(secondAgent.read("src/app.txt"));
            secondAgent.write("src/app.txt", merged.replace("line 1 by alice", "line 1 by alice and agent-2"));
            sim.await(secondAgent.commit("agent-2: change line 1, on top of alice's change"));
            String finalMain = sim.await(secondAgent.land("main"));

            // 7. Post-merge CI on dave: "a CI job that needs the diff between a commit and its merge base ... can ask
            //    the server for it". It then reads only the changed files.
            assertEquals(finalMain, sim.await(ci.resolveRef("main")));
            String base = sim.await(ci.mergeBase(seed, finalMain));
            assertEquals(seed, base);
            List<FileDiff> changed = sim.await(ci.diff(base, finalMain));
            assertEquals(List.of("docs/guide.txt", "src/app.txt"), changed.stream().map(FileDiff::path).toList());
            sim.await(ci.checkoutVirtual(finalMain));
            assertEquals("line 1 by alice and agent-2\nline 2\nline 3\nline 4\nline 5 by bob\n",
                    sim.await(ci.read("src/app.txt")));
            assertEquals("how to use it, updated by the bot\n", sim.await(ci.read("docs/guide.txt")));
            assertEquals(Map.of("src/app.txt", "line 1 by alice and agent-2\nline 2\nline 3\nline 4\nline 5 by bob\n",
                    "docs/guide.txt", "how to use it, updated by the bot\n"), ci.files(), "CI read only what changed");

            // main's history is one line: the seed, three landings, then the fixed change.
            assertEquals(5, sim.await(ci.log(finalMain, 0)).size());
        }
    }
}
