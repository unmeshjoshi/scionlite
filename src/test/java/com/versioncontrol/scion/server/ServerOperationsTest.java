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

    @Test
    void filesThatDoNotChangeAreNotRecordedAndUnknownParentsAreRejected() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String first = sim.await(ada.createChangeset(List.of(), Map.of("a", "1", "b", "2"), "first"));

            String second = sim.await(ada.createChangeset(List.of(first), Map.of("a", "1", "b", "3"), "second"));

            assertEquals(List.of("b"), sim.await(ada.getChangeset(second)).changes().stream()
                    .map(c -> c.path()).toList());
            Throwable error = sim.awaitFailure(ada.createChangeset(List.of("f".repeat(64)), Map.of("a", "1"), "x"));
            assertTrue(error.getMessage().contains("no such changeset"), error.getMessage());
        }
    }

    @Test
    void deferredRootTreeIsComputedOnDemandAndWrittenBackToSpanner() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").deferDerivedData().build(1)) {
            Scn ada = sim.scn("ada");
            String first = sim.await(ada.createChangeset(List.of(), Map.of("a/x", "1"), "first"));
            String second = sim.await(ada.createChangeset(List.of(first), Map.of("a/y", "2"), "second"));
            assertNull(sim.spanner().row(SpannerApi.ROOT_TREE, second));

            assertEquals(Map.of("a/x", "1", "a/y", "2"), sim.await(ada.readAllFiles(second)));

            assertNotNull(sim.spanner().row(SpannerApi.ROOT_TREE, second));
        }
    }

    @Test
    void aRootTreeMissingFromALongHistoryIsRecomputedByWalkingBackIteratively() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(1).region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String tip = null;
            List<String> chain = new java.util.ArrayList<>();
            for (int i = 0; i < 150; i++) {
                tip = sim.await(ada.createChangeset(tip == null ? List.of() : List.of(tip),
                        Map.of("dir" + (i % 5) + "/file" + i, "v" + i), "change " + i));
                chain.add(tip);
            }
            // Lose every root tree but the first, and the server's memory of them.
            chain.subList(1, chain.size()).forEach(id -> sim.spanner().deleteRowForTest(SpannerApi.ROOT_TREE, id));
            sim.cluster().crashNode(Topology.server(1));
            sim.cluster().restartNode(Topology.server(1));
            sim.tickUntil(() -> sim.server(1).isInitialised());
            // The cache and daemon remember root trees they served; use a changeset they never saw.

            Map<String, String> files = sim.await(ada.readAllFiles(tip));

            assertEquals(150, files.size());
            assertEquals(149, sim.server(1).rootTreesComputed());
            assertNotNull(sim.spanner().row(SpannerApi.ROOT_TREE, chain.get(75)));
        }
    }

    @Test
    void logDiffAndMergeBase() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String base = sim.await(ada.createChangeset(List.of(), Map.of("a", "1", "d/b", "2"), "base"));
            String left = sim.await(ada.createChangeset(List.of(base), Map.of("a", "left"), "left"));
            String right = sim.await(ada.createChangeset(List.of(base), Map.of("d/c", "new"), "right"));

            assertEquals(List.of(left, base), sim.await(ada.log(left, 10)));
            assertEquals(List.of(left), sim.await(ada.log(left, 1)));
            assertEquals(base, sim.await(ada.mergeBase(left, right)));
            List<FileDiff> diff = sim.await(ada.diff(left, right));
            assertEquals(List.of("a", "d/c"), diff.stream().map(FileDiff::path).toList());
            assertEquals(List.of(Change.Kind.MODIFIED, Change.Kind.ADDED), diff.stream().map(FileDiff::kind).toList());
        }
    }

    @Test
    void mergeOnTheServerCombinesBothSidesAndRecordsTwoParents() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String base = sim.await(ada.createChangeset(List.of(), Map.of("f", BASE, "g", "g"), "base"));
            String ours = sim.await(ada.createChangeset(List.of(base), Map.of("f", BASE.replace("one", "ONE")), "ours"));
            String theirs = sim.await(ada.createChangeset(List.of(base),
                    Map.of("f", BASE.replace("five", "FIVE"), "h", "h"), "theirs"));

            MergeReply merged = sim.await(ada.merge(ours, theirs, "merge"));

            assertEquals(List.of(), merged.conflicts());
            Changeset merge = sim.await(ada.getChangeset(merged.changeset()));
            assertEquals(List.of(ours, theirs), merge.parents());
            assertEquals(Map.of("f", "ONE\ntwo\nthree\nfour\nFIVE\n", "g", "g", "h", "h"),
                    sim.await(ada.readAllFiles(merged.changeset())));
        }
    }

    @Test
    void conflictingEditsAreReportedNotRecorded() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String base = sim.await(ada.createChangeset(List.of(), Map.of("f", BASE), "base"));
            String ours = sim.await(ada.createChangeset(List.of(base), Map.of("f", BASE.replace("two", "2a")), "o"));
            String theirs = sim.await(ada.createChangeset(List.of(base), Map.of("f", BASE.replace("two", "2b")), "t"));

            MergeReply merged = sim.await(ada.merge(ours, theirs, "merge"));
            RebaseReply rebased = sim.await(ada.rebase(theirs, ours));

            assertNull(merged.changeset());
            assertEquals(List.of("f"), merged.conflicts());
            assertNull(rebased.changeset());
            assertEquals(List.of("f"), rebased.conflicts());
        }
    }

    @Test
    void rebaseReplaysAChainOfChangesetsOntoANewParent() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String base = sim.await(ada.createChangeset(List.of(), Map.of("f", BASE), "base"));
            String upstream = sim.await(ada.createChangeset(List.of(base), Map.of("f", BASE.replace("one", "1")), "up"));
            String mine1 = sim.await(ada.createChangeset(List.of(base), Map.of("f", BASE.replace("five", "5")), "m1"));
            String mine2 = sim.await(ada.createChangeset(List.of(mine1), Map.of("g", "new"), "m2"));

            RebaseReply rebased = sim.await(ada.rebase(mine2, upstream));

            String tip = rebased.changeset();
            assertEquals(Map.of("f", "1\ntwo\nthree\nfour\n5\n", "g", "new"), sim.await(ada.readAllFiles(tip)));
            List<String> log = sim.await(ada.log(tip, 10));
            assertEquals(4, log.size());
            assertEquals(List.of(upstream, base), log.subList(2, 4));
            Changeset tipChangeset = sim.await(ada.getChangeset(tip));
            assertEquals("m2", tipChangeset.message());
            assertEquals(List.of("g"), tipChangeset.changes().stream().map(c -> c.path()).toList());
            // Rebasing something already in the target changes nothing.
            assertEquals(upstream, sim.await(ada.rebase(base, upstream)).changeset());
        }
    }

    @Test
    void refsMoveByCompareAndSwapOnOneRow() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String a = sim.await(ada.createChangeset(List.of(), Map.of("f", "a"), "a"));
            String b = sim.await(ada.createChangeset(List.of(a), Map.of("f", "b"), "b"));

            assertTrue(sim.await(ada.moveRef("main", null, a)).swapped());
            MoveRefReply stale = sim.await(ada.moveRef("main", null, b));
            assertFalse(stale.swapped());
            assertEquals(a, stale.current());
            assertTrue(sim.await(ada.moveRef("main", a, b)).swapped());
            assertEquals(b, sim.await(ada.resolveRef("main")));
            assertEquals(Map.of("main", b), sim.await(ada.listRefs()));
            assertNull(sim.await(ada.resolveRef("release/1")));
            sim.awaitFailure(ada.moveRef("main", b, "e".repeat(64)));
            Map<String, String> unchanged = new HashMap<>(Map.of("main", b));
            assertEquals(unchanged, sim.await(ada.listRefs()));
        }
    }
}
