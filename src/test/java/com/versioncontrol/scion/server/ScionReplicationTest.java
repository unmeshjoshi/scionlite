package com.versioncontrol.scion.server;

import com.versioncontrol.scion.objects.Blob;
import com.versioncontrol.scion.objects.Commit;
import com.versioncontrol.scion.objects.ObjectCodec;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.Tree;
import com.versioncontrol.scion.protocol.PushRequest;
import com.versioncontrol.scion.protocol.PushResponse;
import com.versioncontrol.scion.protocol.PushStatus;
import com.versioncontrol.scion.protocol.RefUpdate;
import com.versioncontrol.scion.repo.MergeOutcome;
import com.versioncontrol.scion.repo.Repository;
import com.versioncontrol.scion.sim.ScionSimulation;
import com.versioncontrol.scion.sim.Workstation;
import com.tickloom.ProcessId;
import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.versioncontrol.scion.sim.ScionSimulation.ALPHA;
import static com.versioncontrol.scion.sim.ScionSimulation.BETA;
import static com.versioncontrol.scion.sim.ScionSimulation.GAMMA;
import static org.junit.jupiter.api.Assertions.*;

/** Scion end to end on three replicas, with the simulated network and simulated disks. */
class ScionReplicationTest {
    private ScionSimulation sim;

    @BeforeEach
    void start() throws Exception {
        sim = ScionSimulation.threeReplicas(42);
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private ObjectId commit(Workstation ws, String path, String content, String message) {
        ws.repo().writeFile(path, content);
        ws.repo().add(path);
        return sim.await(ws.repo().commit(message));
    }

    private PushResponse push(Workstation ws) {
        return sim.await(ws.repo().push(ws.client()));
    }

    private void awaitAllReplicasAt(String branch, ObjectId commit) {
        sim.tickUntil(() -> sim.servers().stream().allMatch(s -> commit.equals(s.committedRefs().get(branch))));
    }

    @Test
    void theFirstReplicaByNameIsThePrimary() {
        assertTrue(sim.server(ALPHA).isPrimary());
        assertFalse(sim.server(BETA).isPrimary());
    }

    @Test
    void pushThenCloneOnAnotherWorkstation() throws Exception {
        Workstation ada = sim.workstation("ada");
        ObjectId first = commit(ada, "README.md", "# Scion\n", "Initial commit");
        ObjectId second = commit(ada, "src/Main.java", "class Main {}\n", "Add Main");

        PushResponse response = push(ada);
        assertEquals(PushStatus.OK, response.status());
        assertEquals(Map.of("main", second), ada.repo().remoteBranches());

        Workstation bob = sim.cloneTo("bob");
        assertEquals("main", bob.repo().currentBranch());
        assertEquals(second, bob.repo().headCommit());
        assertEquals(List.of(second, first), bob.repo().log().stream().map(c -> c.id()).toList());
        assertEquals(Optional.of("class Main {}\n"), bob.repo().readFile("src/Main.java"));

        awaitAllReplicasAt("main", second);
    }

    @Test
    void secondPushSendsOnlyNewObjectsAndFetchBringsThemAcross() throws Exception {
        Workstation ada = sim.workstation("ada");
        commit(ada, "f", "1", "one");
        push(ada);
        Workstation bob = sim.cloneTo("bob");

        ObjectId two = commit(ada, "f", "2", "two");
        assertTrue(push(ada).succeeded());

        Map<String, ObjectId> remote = sim.await(bob.repo().fetch(bob.client()));
        assertEquals(two, remote.get("main"));
        assertEquals(MergeOutcome.Kind.FAST_FORWARD, sim.await(bob.repo().merge("origin/main")).kind());
        assertEquals(Optional.of("2"), bob.repo().readFile("f"));
    }

    @Test
    void concurrentPushesToTheSameBranchOneWinsTheOtherPullsMergesAndPushes() throws Exception {
        Workstation ada = sim.workstation("ada");
        commit(ada, "shared.txt", "top\nmiddle\nbottom\n", "base");
        push(ada);
        Workstation bob = sim.cloneTo("bob");

        ObjectId adas = commit(ada, "shared.txt", "TOP\nmiddle\nbottom\n", "ada edits the top");
        ObjectId bobs = commit(bob, "shared.txt", "top\nmiddle\nBOTTOM\n", "bob edits the bottom");

        TickCompletableFuture<PushResponse> adaPush = ada.repo().push(ada.client());
        TickCompletableFuture<PushResponse> bobPush = bob.repo().push(bob.client());
        PushResponse adaResult = sim.await(adaPush);
        PushResponse bobResult = sim.await(bobPush);

        assertEquals(1, List.of(adaResult, bobResult).stream().filter(PushResponse::succeeded).count(),
                "exactly one push wins: " + adaResult + " / " + bobResult);
        Workstation loser = adaResult.succeeded() ? bob : ada;
        PushResponse rejected = adaResult.succeeded() ? bobResult : adaResult;
        assertEquals(PushStatus.STALE, rejected.status());

        MergeOutcome merged = sim.await(loser.repo().pull(loser.client()));
        assertEquals(MergeOutcome.Kind.MERGED, merged.kind());
        assertEquals(Optional.of("TOP\nmiddle\nBOTTOM\n"), loser.repo().readFile("shared.txt"));
        assertTrue(push(loser).succeeded());

        Workstation carol = sim.cloneTo("carol");
        assertEquals(merged.commit(), carol.repo().headCommit());
        assertEquals(List.of(adas, bobs).stream().sorted().toList(),
                carol.repo().readCommit(merged.commit()).parents().stream().sorted().toList());
    }

    @Test
    void nonFastForwardIsRejectedUnlessForced() throws Exception {
        Workstation ada = sim.workstation("ada");
        ObjectId one = commit(ada, "f", "1", "one");
        ObjectId two = commit(ada, "f", "2", "two");
        push(ada);

        // A sibling of "two": it does not descend from what the server has.
        Commit sibling = new Commit(ada.repo().readCommit(one).tree(), List.of(one), "eve", 1, "sibling");
        PushRequest request = new PushRequest(List.of(ObjectCodec.encode(sibling)),
                List.of(new RefUpdate("main", two.hex(), sibling.id().hex())), false);
        assertEquals(PushStatus.NON_FAST_FORWARD, sim.await(ada.client().push(request)).status());

        PushRequest forced = new PushRequest(request.objects(), request.updates(), true);
        assertEquals(PushStatus.OK, sim.await(ada.client().push(forced)).status());
        awaitAllReplicasAt("main", sibling.id());
    }

    @Test
    void pushMissingObjectsIsRejected() throws Exception {
        Workstation ada = sim.workstation("ada");
        Blob blob = Blob.of("content");
        Tree tree = new Tree(List.of(new com.versioncontrol.scion.objects.TreeEntry("f",
                com.versioncontrol.scion.objects.EntryKind.FILE, blob.id())));
        Commit commit = new Commit(tree.id(), List.of(), "ada", 1, "incomplete");
        PushRequest withoutBlob = new PushRequest(List.of(ObjectCodec.encode(commit), ObjectCodec.encode(tree)),
                List.of(new RefUpdate("main", null, commit.id().hex())), false);

        PushResponse response = sim.await(ada.client().push(withoutBlob));
        assertEquals(PushStatus.MISSING_OBJECTS, response.status());
        assertTrue(sim.server(ALPHA).committedRefs().isEmpty());
    }

    @Test
    void corruptObjectsAndBadBranchNamesAreInvalid() throws Exception {
        Workstation ada = sim.workstation("ada");
        Commit commit = new Commit(Tree.EMPTY.id(), List.of(), "ada", 1, "empty");
        List<byte[]> objects = List.of(ObjectCodec.encode(commit), ObjectCodec.encode(Tree.EMPTY));

        PushRequest corrupt = new PushRequest(List.of("garbage".getBytes()),
                List.of(new RefUpdate("main", null, commit.id().hex())), false);
        assertEquals(PushStatus.INVALID, sim.await(ada.client().push(corrupt)).status());

        PushRequest badName = new PushRequest(objects, List.of(new RefUpdate("../main", null, commit.id().hex())), false);
        assertEquals(PushStatus.INVALID, sim.await(ada.client().push(badName)).status());

        PushRequest good = new PushRequest(objects, List.of(new RefUpdate("main", null, commit.id().hex())), false);
        assertEquals(PushStatus.OK, sim.await(ada.client().push(good)).status());
    }

    @Test
    void followersRefusePushes() throws Exception {
        Workstation ada = sim.workstation("ada");
        commit(ada, "f", "1", "one");
        sim.await(ada.repo().push(ada.client()));
        // Talk to a follower directly.
        var response = sim.await(ada.client().<PushResponse>sendRequest(
                new PushRequest(List.of(), List.of(new RefUpdate("x", null, ada.repo().headCommit().hex())), false),
                BETA, com.versioncontrol.scion.protocol.ScionMessageTypes.PUSH_REQUEST));
        assertEquals(PushStatus.NOT_PRIMARY, response.status());
    }

    @Test
    void pushIsAcknowledgedWithOneFollowerPartitionedAndTheFollowerCatchesUpLater() throws Exception {
        Workstation ada = sim.workstation("ada");
        sim.cluster().partitionNodes(ALPHA, GAMMA);

        ObjectId one = commit(ada, "f", "1", "one");
        assertTrue(push(ada).succeeded());
        ObjectId two = commit(ada, "f", "2", "two");
        assertTrue(push(ada).succeeded());

        assertEquals(two, sim.server(ALPHA).committedRefs().get("main"));
        assertEquals(0, sim.server(GAMMA).logLength());
        assertFalse(sim.server(GAMMA).hasObject(one));

        sim.cluster().healPartition(ALPHA, GAMMA);
        awaitAllReplicasAt("main", two);
        assertEquals(2, sim.server(GAMMA).logLength());
        assertTrue(sim.server(GAMMA).hasObject(one));
    }

    @Test
    void pushFailsWithoutAMajorityAndTheBranchDoesNotMove() throws Exception {
        Workstation ada = sim.workstation("ada");
        ObjectId one = commit(ada, "f", "1", "one");
        assertTrue(push(ada).succeeded());

        sim.cluster().partitionNodes(ALPHA, BETA);
        sim.cluster().partitionNodes(ALPHA, GAMMA);
        commit(ada, "f", "2", "two");
        sim.awaitFailure(ada.repo().push(ada.client()));   // request times out
        assertEquals(one, sim.server(ALPHA).committedRefs().get("main"));
        assertEquals(one, ada.repo().remoteBranches().get("main"));

        // Once the partition heals the accepted entry commits; the retried push sees it already landed.
        sim.cluster().healAllPartitions();
        PushResponse retry = push(ada);
        assertTrue(retry.succeeded(), retry.toString());
        awaitAllReplicasAt("main", ada.repo().headCommit());
    }

    @Test
    void aFollowerFetchCanBeBehindButTheOneFromThePrimaryIsNot() throws Exception {
        Workstation ada = sim.workstation("ada");
        commit(ada, "f", "1", "one");
        push(ada);
        awaitAllReplicasAt("main", ada.repo().headCommit());

        sim.cluster().partitionNodes(ALPHA, GAMMA);
        ObjectId two = commit(ada, "f", "2", "two");
        push(ada);

        var fromGamma = sim.await(ada.client().fetchFrom(GAMMA, new com.versioncontrol.scion.protocol.FetchRequest(List.of())));
        var fromPrimary = sim.await(ada.client().fetch(new com.versioncontrol.scion.protocol.FetchRequest(List.of())));
        assertNotEquals(two.hex(), fromGamma.refs().get("main"));
        assertEquals(two.hex(), fromPrimary.refs().get("main"));
    }

    @Test
    void aFollowerRecoversItsLogFromDiskAfterACrashAndCatchesUp() throws Exception {
        Workstation ada = sim.workstation("ada");
        commit(ada, "f", "1", "one");
        push(ada);
        awaitAllReplicasAt("main", ada.repo().headCommit());

        sim.cluster().crashNode(GAMMA);
        ObjectId two = commit(ada, "f", "2", "two");
        assertTrue(push(ada).succeeded());   // alpha + beta are a majority

        sim.cluster().restartNode(GAMMA);
        sim.tickUntil(() -> sim.server(GAMMA).isRunning());
        assertTrue(sim.server(GAMMA).logLength() >= 1, "entry 1 was durable before the crash");
        awaitAllReplicasAt("main", two);
    }

    @Test
    void thePrimaryKeepsAcknowledgedPushesAcrossACrashAndAnswersOnlyOnceCaughtUp() throws Exception {
        Workstation ada = sim.workstation("ada");
        commit(ada, "f", "1", "one");
        push(ada);
        ObjectId two = commit(ada, "f", "2", "two");
        assertTrue(push(ada).succeeded());

        sim.cluster().crashNode(ALPHA);
        // While the primary is down, pushes cannot be acknowledged.
        commit(ada, "f", "3", "three");
        sim.awaitFailure(ada.repo().push(ada.client()));

        sim.cluster().restartNode(ALPHA);
        sim.tickUntil(() -> sim.server(ALPHA).isRunning());
        assertEquals(2, sim.server(ALPHA).logLength());

        // A read issued right after the restart is answered with the re-committed state, never an older one.
        var refs = sim.await(ada.client().listRefs());
        assertEquals(two.hex(), refs.refs().get("main"));

        PushResponse third = push(ada);
        assertTrue(third.succeeded(), third.toString());
        awaitAllReplicasAt("main", ada.repo().headCommit());
    }

    @Test
    void aRestartedPrimaryHoldsReadsUntilAMajorityConfirmsItsLog() throws Exception {
        Workstation ada = sim.workstation("ada");
        commit(ada, "f", "1", "one");
        assertTrue(push(ada).succeeded());

        sim.cluster().crashNode(ALPHA);
        sim.cluster().restartNode(ALPHA);
        sim.cluster().partitionNodes(ALPHA, BETA);
        sim.cluster().partitionNodes(ALPHA, GAMMA);
        sim.tickUntil(() -> sim.server(ALPHA).isRunning());

        TickCompletableFuture<?> read = ada.client().listRefs();
        sim.tick(50);
        assertTrue(read.isPending(), "no answer while the primary cannot confirm what is committed");

        sim.cluster().healAllPartitions();
        sim.await(read);
    }

    @Test
    void aWorkstationCrashKeepsCommittedHistoryAndCanStillPush() throws Exception {
        Workstation ada = sim.workstation("ada");
        commit(ada, "f", "1", "one");
        push(ada);
        ObjectId two = commit(ada, "f", "2", "two");

        sim.crashAndReopen(ada);
        assertEquals(two, ada.repo().headCommit());
        assertTrue(push(ada).succeeded());
        awaitAllReplicasAt("main", two);
    }

    @Test
    void featureBranchesArePushedAndCheckedOutElsewhere() throws Exception {
        Workstation ada = sim.workstation("ada");
        commit(ada, "f", "1", "one");
        push(ada);
        sim.await(ada.repo().createBranch("feature/login"));
        sim.await(ada.repo().checkout("feature/login"));
        ObjectId feature = commit(ada, "login.txt", "login", "login page");
        assertTrue(push(ada).succeeded());

        Workstation bob = sim.cloneTo("bob");
        assertEquals(List.of("feature/login", "main"), List.copyOf(bob.repo().remoteBranches().keySet()));
        sim.await(bob.repo().checkout("feature/login"));
        assertEquals(feature, bob.repo().headCommit());
        assertEquals(Optional.of("login"), bob.repo().readFile("login.txt"));
    }
}
