package com.versioncontrol.scion.server;

import com.versioncontrol.scion.api.ScionApi.FileDiff;
import com.versioncontrol.scion.api.ScionApi.MergeReply;
import com.versioncontrol.scion.api.ScionApi.MoveRefReply;
import com.versioncontrol.scion.api.ScionApi.RebaseReply;
import com.versioncontrol.scion.changeset.Changeset;
import com.versioncontrol.scion.diff.Change;
import com.versioncontrol.scion.scn.Scn;
import com.versioncontrol.scion.sim.ScionSimulation;
import com.versioncontrol.scion.spanner.SpannerApi;
import com.versioncontrol.scion.topology.Topology;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The calls a scion-server answers, through the full tier chain. */
class ServerOperationsTest {
    private static final String BASE = "one\ntwo\nthree\nfour\nfive\n";

    /**
     * Scenario: alice sends files a and b again, with only b's content changed, and then a changeset on a parent
     * that does not exist.
     * Then the server records only b, and rejects the changeset with an unknown parent.
     */
    @Test
    void filesThatDoNotChangeAreNotRecordedAndUnknownParentsAreRejected() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String first = sim.await(alice.createChangeset(List.of(), Map.of("a", "1", "b", "2"), "first"));

            String second = sim.await(alice.createChangeset(List.of(first), Map.of("a", "1", "b", "3"), "second"));

            assertEquals(List.of("b"), sim.await(alice.getChangeset(second)).changes().stream()
                    .map(c -> c.path()).toList());
            Throwable error = sim.awaitFailure(alice.createChangeset(List.of("f".repeat(64)), Map.of("a", "1"), "x"));
            assertTrue(error.getMessage().contains("no such changeset"), error.getMessage());
        }
    }

    /**
     * Scenario: the servers defer derived data. alice creates two changesets.
     * 1. No root tree has been written for the second one yet.
     * 2. alice reads its files, so a server computes the root tree.
     * Then the files are right and the root tree is now in Spanner's root_tree table.
     */
    @Test
    void deferredRootTreeIsComputedOnDemandAndWrittenBackToSpanner() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").deferDerivedData().build(1)) {
            Scn alice = sim.scn("alice");
            String first = sim.await(alice.createChangeset(List.of(), Map.of("a/x", "1"), "first"));
            String second = sim.await(alice.createChangeset(List.of(first), Map.of("a/y", "2"), "second"));
            assertNull(sim.spanner().row(SpannerApi.ROOT_TREE, second));

            assertEquals(Map.of("a/x", "1", "a/y", "2"), sim.await(alice.readAllFiles(second)));

            assertNotNull(sim.spanner().row(SpannerApi.ROOT_TREE, second));
        }
    }

    /**
     * Scenario: a history of 150 changesets loses every root tree but the first.
     * 1. alice creates 150 changesets, each adding one file.
     * 2. The test deletes 149 root_tree rows and restarts the only server, so it remembers nothing.
     * 3. alice reads the tip's files.
     * Then the server walks back to the first root tree, computes the 149 missing ones forward without
     * recursion, and writes them back to Spanner.
     */
    @Test
    void aRootTreeMissingFromALongHistoryIsRecomputedByWalkingBackIteratively() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(1).region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String tip = null;
            List<String> chain = new java.util.ArrayList<>();
            for (int i = 0; i < 150; i++) {
                tip = sim.await(alice.createChangeset(tip == null ? List.of() : List.of(tip),
                        Map.of("dir" + (i % 5) + "/file" + i, "v" + i), "change " + i));
                chain.add(tip);
            }
            // Lose every root tree but the first, and the server's memory of them.
            chain.subList(1, chain.size()).forEach(id -> sim.spanner().deleteRowForTest(SpannerApi.ROOT_TREE, id));
            sim.cluster().crashNode(Topology.server(1));
            sim.cluster().restartNode(Topology.server(1));
            sim.tickUntil(() -> sim.server(1).isInitialised());
            // The cache and daemon remember root trees they served; use a changeset they never saw.

            Map<String, String> files = sim.await(alice.readAllFiles(tip));

            assertEquals(150, files.size());
            assertEquals(149, sim.server(1).rootTreesComputed());
            assertNotNull(sim.spanner().row(SpannerApi.ROOT_TREE, chain.get(75)));
        }
    }

    /**
     * Scenario: two changesets, left and right, branch off one base.
     * Then log walks back from left, the merge base of left and right is base,
     * and the diff from left to right lists a as modified and d/c as added.
     */
    @Test
    void logDiffAndMergeBase() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String base = sim.await(alice.createChangeset(List.of(), Map.of("a", "1", "d/b", "2"), "base"));
            String left = sim.await(alice.createChangeset(List.of(base), Map.of("a", "left"), "left"));
            String right = sim.await(alice.createChangeset(List.of(base), Map.of("d/c", "new"), "right"));

            assertEquals(List.of(left, base), sim.await(alice.log(left, 10)));
            assertEquals(List.of(left), sim.await(alice.log(left, 1)));
            assertEquals(base, sim.await(alice.mergeBase(left, right)));
            List<FileDiff> diff = sim.await(alice.diff(left, right));
            assertEquals(List.of("a", "d/c"), diff.stream().map(FileDiff::path).toList());
            assertEquals(List.of(Change.Kind.MODIFIED, Change.Kind.ADDED), diff.stream().map(FileDiff::kind).toList());
        }
    }

    /**
     * Scenario: the first common ancestor a walk meets is not the merge base.
     * 1. root holds f. x, on top of root, adds g. y, on top of x, edits line 2 of f.
     * 2. On main, q and then q2 follow y. On a side branch, w adds k on top of x. m merges w into q2.
     * 3. revert, on top of y, puts f back as it was.
     * The common ancestors of revert and m are y, x and root. Walking back from m reaches x (through w) before y
     * (through q2 and q), but y descends from x, so y is the merge base.
     * Then the merge base is y from either side, and merging m into revert keeps the revert. Merging from x would
     * bring y's edit back.
     */
    @Test
    void theMergeBaseIsTheBestCommonAncestorNotTheNearest() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String original = "1\n2\n3\n";
            String root = sim.await(alice.createChangeset(List.of(), Map.of("f", original), "root"));
            String x = sim.await(alice.createChangeset(List.of(root), Map.of("g", "x"), "x"));
            String y = sim.await(alice.createChangeset(List.of(x), Map.of("f", "1\nY\n3\n"), "y"));
            String q = sim.await(alice.createChangeset(List.of(y), Map.of("h", "q"), "q"));
            String q2 = sim.await(alice.createChangeset(List.of(q), Map.of("h", "q2"), "q2"));
            String w = sim.await(alice.createChangeset(List.of(x), Map.of("k", "w"), "w"));
            MergeReply m = sim.await(alice.merge(q2, w, "m"));
            assertEquals(List.of(), m.conflicts());
            assertEquals(List.of(q2, w), sim.await(alice.getChangeset(m.changeset())).parents());
            String revert = sim.await(alice.createChangeset(List.of(y), Map.of("f", original), "revert"));

            assertEquals(y, sim.await(alice.mergeBase(revert, m.changeset())));
            assertEquals(y, sim.await(alice.mergeBase(m.changeset(), revert)));
            MergeReply merged = sim.await(alice.merge(revert, m.changeset(), "merge m into revert"));
            assertEquals(List.of(), merged.conflicts());
            assertEquals(original, sim.await(alice.readAllFiles(merged.changeset())).get("f"), "the revert is kept");
        }
    }

    /**
     * Scenario: a ref only moves forward [Lite assumption].
     * 1. main moves from a to its child b.
     * 2. Moving main back to a fails, and so does moving it to c, a sibling of b.
     * 3. Moving main to the changeset it already points at is allowed.
     * Then main is still b.
     */
    @Test
    void refsOnlyMoveForward() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String a = sim.await(alice.createChangeset(List.of(), Map.of("f", "a"), "a"));
            String b = sim.await(alice.createChangeset(List.of(a), Map.of("f", "b"), "b"));
            String c = sim.await(alice.createChangeset(List.of(a), Map.of("f", "c"), "c"));
            assertTrue(sim.await(alice.moveRef("main", null, a)).swapped());
            assertTrue(sim.await(alice.moveRef("main", a, b)).swapped());

            assertTrue(sim.awaitFailure(alice.moveRef("main", b, a)).getMessage().contains("refs only move forward"));
            assertTrue(sim.awaitFailure(alice.moveRef("main", b, c)).getMessage().contains("refs only move forward"));
            assertTrue(sim.await(alice.moveRef("main", b, b)).swapped());

            assertEquals(b, sim.await(alice.resolveRef("main")));
        }
    }

    /**
     * Scenario: ours edits line one of f, theirs edits line five and adds h. The server merges them.
     * Then there are no conflicts, the merge records both parents, and it holds both edits plus h.
     */
    @Test
    void mergeOnTheServerCombinesBothSidesAndRecordsTwoParents() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String base = sim.await(alice.createChangeset(List.of(), Map.of("f", BASE, "g", "g"), "base"));
            String ours = sim.await(alice.createChangeset(List.of(base), Map.of("f", BASE.replace("one", "ONE")), "ours"));
            String theirs = sim.await(alice.createChangeset(List.of(base),
                    Map.of("f", BASE.replace("five", "FIVE"), "h", "h"), "theirs"));

            MergeReply merged = sim.await(alice.merge(ours, theirs, "merge"));

            assertEquals(List.of(), merged.conflicts());
            Changeset merge = sim.await(alice.getChangeset(merged.changeset()));
            assertEquals(List.of(ours, theirs), merge.parents());
            assertEquals(Map.of("f", "ONE\ntwo\nthree\nfour\nFIVE\n", "g", "g", "h", "h"),
                    sim.await(alice.readAllFiles(merged.changeset())));
        }
    }

    /**
     * Scenario: ours and theirs change the same line of f differently.
     * Then both merge and rebase report f as a conflict, and neither creates a changeset.
     */
    @Test
    void conflictingEditsAreReportedNotRecorded() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String base = sim.await(alice.createChangeset(List.of(), Map.of("f", BASE), "base"));
            String ours = sim.await(alice.createChangeset(List.of(base), Map.of("f", BASE.replace("two", "2a")), "o"));
            String theirs = sim.await(alice.createChangeset(List.of(base), Map.of("f", BASE.replace("two", "2b")), "t"));

            MergeReply merged = sim.await(alice.merge(ours, theirs, "merge"));
            RebaseReply rebased = sim.await(alice.rebase(theirs, ours));

            assertNull(merged.changeset());
            assertEquals(List.of("f"), merged.conflicts());
            assertNull(rebased.changeset());
            assertEquals(List.of("f"), rebased.conflicts());
        }
    }

    /**
     * Scenario: two of my changesets (m1, m2) are rebased onto upstream, which branched from the same base.
     * Then the result has upstream's edit and both of mine, the history is m2', m1', upstream, base,
     * and the replayed m2 keeps its message and records only g. Rebasing something already in upstream
     * returns upstream.
     */
    @Test
    void rebaseReplaysAChainOfChangesetsOntoANewParent() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String base = sim.await(alice.createChangeset(List.of(), Map.of("f", BASE), "base"));
            String upstream = sim.await(alice.createChangeset(List.of(base), Map.of("f", BASE.replace("one", "1")), "up"));
            String mine1 = sim.await(alice.createChangeset(List.of(base), Map.of("f", BASE.replace("five", "5")), "m1"));
            String mine2 = sim.await(alice.createChangeset(List.of(mine1), Map.of("g", "new"), "m2"));

            RebaseReply rebased = sim.await(alice.rebase(mine2, upstream));

            String tip = rebased.changeset();
            assertEquals(Map.of("f", "1\ntwo\nthree\nfour\n5\n", "g", "new"), sim.await(alice.readAllFiles(tip)));
            List<String> log = sim.await(alice.log(tip, 10));
            assertEquals(4, log.size());
            assertEquals(List.of(upstream, base), log.subList(2, 4));
            Changeset tipChangeset = sim.await(alice.getChangeset(tip));
            assertEquals("m2", tipChangeset.message());
            assertEquals(List.of("g"), tipChangeset.changes().stream().map(c -> c.path()).toList());
            // Rebasing something already in the target changes nothing.
            assertEquals(upstream, sim.await(alice.rebase(base, upstream)).changeset());
        }
    }

    /**
     * Scenario: alice moves main by compare-and-swap.
     * 1. Creating main from nothing to a succeeds.
     * 2. Creating it again, to b, fails and reports that main is at a.
     * 3. Moving it from a to b succeeds.
     * Then main is b, an unknown ref resolves to null, and moving main to a changeset that does not exist fails.
     */
    @Test
    void refsMoveByCompareAndSwapOnOneRow() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String a = sim.await(alice.createChangeset(List.of(), Map.of("f", "a"), "a"));
            String b = sim.await(alice.createChangeset(List.of(a), Map.of("f", "b"), "b"));

            assertTrue(sim.await(alice.moveRef("main", null, a)).swapped());
            MoveRefReply stale = sim.await(alice.moveRef("main", null, b));
            assertFalse(stale.swapped());
            assertEquals(a, stale.current());
            assertTrue(sim.await(alice.moveRef("main", a, b)).swapped());
            assertEquals(b, sim.await(alice.resolveRef("main")));
            assertEquals(Map.of("main", b), sim.await(alice.listRefs()));
            assertNull(sim.await(alice.resolveRef("release/1")));
            sim.awaitFailure(alice.moveRef("main", b, "e".repeat(64)));
            Map<String, String> unchanged = new HashMap<>(Map.of("main", b));
            assertEquals(unchanged, sim.await(alice.listRefs()));
        }
    }

    /**
     * Scenario: two servers move main from the same value at the same time, so their Spanner transactions overlap.
     * Two test clients talk straight to server 1 and server 2 (bypassing the tiers on purpose), and Spanner's replies
     * to server 1 are slowed down.
     * 1. main points at a. Server 1 already knows changeset b, server 2 knows c.
     * 2. Server 1 is asked to move main from a to b, and server 2 from a to c, at the same time.
     * 3. Both Read main (a) in read-write transactions. Server 2 commits first. Server 1's Commit is ABORTED,
     *    because the row it read has changed.
     * 4. Server 1 retries the transaction from the beginning, reads c, which isn't a, and rolls back.
     * Then server 2's move swapped, server 1's reports that main is at c, and main is c.
     */
    @Test
    void overlappingRefMovesFromTheSameValueLetExactlyOneWin() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "alice").build(1)) {
            Scn onServer1 = sim.cluster().newClientConnectedTo(com.tickloom.ProcessId.of("scn-on-server-1"),
                    Topology.server(1), Scn::new);
            Scn onServer2 = sim.cluster().newClientConnectedTo(com.tickloom.ProcessId.of("scn-on-server-2"),
                    Topology.server(2), Scn::new);
            String a = sim.await(onServer1.createChangeset(List.of(), Map.of("f", "a"), "a"));
            String b = sim.await(onServer1.createChangeset(List.of(a), Map.of("f", "b"), "b"));
            String c = sim.await(onServer2.createChangeset(List.of(a), Map.of("f", "c"), "c"));
            assertTrue(sim.await(onServer1.moveRef("main", null, a)).swapped());
            sim.cluster().setNetworkDelay(Topology.SPANNER, Topology.server(1), 20);

            var toB = onServer1.moveRef("main", a, b);
            var toC = onServer2.moveRef("main", a, c);
            MoveRefReply viaServer1 = sim.await(toB), viaServer2 = sim.await(toC);

            assertTrue(viaServer2.swapped());
            assertFalse(viaServer1.swapped());
            assertEquals(c, viaServer1.current());
            assertEquals(1, sim.spanner().abortedCommits(), "server 1's first Commit was aborted");
            assertEquals(c, sim.await(onServer1.resolveRef("main")));
        }
    }
}
