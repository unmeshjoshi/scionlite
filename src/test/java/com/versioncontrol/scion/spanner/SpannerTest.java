package com.versioncontrol.scion.spanner;

import com.tickloom.ProcessId;
import com.versioncontrol.scion.sim.ScionSimulation;
import com.versioncontrol.scion.topology.Topology;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.versioncontrol.scion.spanner.SpannerApi.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Spanner node answers Google Cloud Spanner's {@code Read}, {@code BeginTransaction}, {@code Commit} and
 * {@code Rollback}, with the error codes Google's reference gives. A test client talks to it directly.
 */
class SpannerTest {
    private static final List<String> COLUMNS = Schema.REFS_COLUMNS;

    private static SpannerClient client(ScionSimulation sim) throws Exception {
        return sim.cluster().newClientConnectedTo(ProcessId.of("spanner-client"), Topology.SPANNER,
                SpannerClient::new);
    }

    private static ScionSimulation simulation() throws Exception {
        return ScionSimulation.builder().region("west", "alice").build(1);
    }

    private static void restartSpanner(ScionSimulation sim) {
        sim.cluster().crashNode(Topology.SPANNER);
        sim.cluster().restartNode(Topology.SPANNER);
        sim.tickUntil(() -> sim.cluster().getProcess(Topology.SPANNER).isInitialised());
    }

    private static Mutation insert(String name, String changeset) {
        return Mutation.insert(REFS, COLUMNS, List.of(name, changeset));
    }

    private static List<List<String>> allRefs(SpannerClient client, ScionSimulation sim) {
        String session = sim.await(client.createSession()).name();
        return sim.await(client.read(ReadRequest.strong(session, REFS, COLUMNS, new KeySet(List.of(), List.of(), true))))
                .rows();
    }

    /**
     * Scenario: single-use commits write rows, and strong reads read them.
     * 1. One commit inserts main and release/1; another inserts release/2.
     * 2. A read by key, a read of the range of keys starting "release/", and a read of every key.
     * 3. insertOrUpdate overwrites main; delete removes release/1.
     * Then each read returns the rows in key order with the columns asked for, and a missing key returns nothing.
     */
    @Test
    void singleUseCommitsWriteRowsAndStrongReadsReadThem() throws Exception {
        try (ScionSimulation sim = simulation()) {
            SpannerClient client = client(sim);
            String session = sim.await(client.createSession()).name();
            sim.await(client.commit(CommitRequest.singleUse(session, List.of(insert("main", "c1"), insert("release/1", "c2")))));
            sim.await(client.commit(CommitRequest.singleUse(session, List.of(insert("release/2", "c3")))));

            ResultSet main = sim.await(client.readRef(session, null, "main"));
            assertEquals(List.of(List.of("main", "c1")), main.rows());
            assertEquals(List.of(new Field("name", new Type("STRING")), new Field("changeset", new Type("STRING"))),
                    main.metadata().rowType().fields());
            assertEquals(List.of(List.of("c2"), List.of("c3")), sim.await(client.read(ReadRequest.strong(session, REFS,
                    List.of("changeset"), KeySet.range(KeyRange.prefix("release/"))))).rows());
            assertEquals(List.of(), sim.await(client.readRef(session, null, "missing")).rows());

            sim.await(client.commit(CommitRequest.singleUse(session, List.of(
                    Mutation.insertOrUpdate(REFS, COLUMNS, List.of("main", "c4")),
                    Mutation.delete(REFS, KeySet.key("release/1"))))));

            assertEquals(List.of(List.of("main", "c4"), List.of("release/2", "c3")), allRefs(client, sim));
        }
    }

    /**
     * Scenario: a commit's mutations break the reference's rules.
     * 1. Inserting a row that exists fails with ALREADY_EXISTS. The same commit also inserted another row.
     * 2. Updating a row that doesn't exist fails with NOT_FOUND.
     * Then neither commit changed anything: "All mutations are applied atomically".
     */
    @Test
    void insertOfAnExistingRowAndUpdateOfAMissingOneFailTheWholeCommit() throws Exception {
        try (ScionSimulation sim = simulation()) {
            SpannerClient client = client(sim);
            String session = sim.await(client.createSession()).name();
            sim.await(client.commit(CommitRequest.singleUse(session, List.of(insert("main", "c1")))));

            Throwable exists = sim.awaitFailure(client.commit(CommitRequest.singleUse(session, List.of(
                    insert("other", "c2"), insert("main", "c3")))));
            Throwable missing = sim.awaitFailure(client.commit(CommitRequest.singleUse(session, List.of(
                    insert("other", "c2"), Mutation.update(REFS, COLUMNS, List.of("nope", "c4"))))));

            assertEquals(Code.ALREADY_EXISTS, SpannerApi.code(exists));
            assertEquals(Code.NOT_FOUND, SpannerApi.code(missing));
            assertEquals(List.of(List.of("main", "c1")), allRefs(client, sim));
        }
    }

    /**
     * Scenario: two read-write transactions read main, and both try to move it.
     * 1. Transaction one begins with its Read; transaction two, in a second session ("Sessions can only execute one
     *    transaction at a time"), begins with BeginTransaction and then reads.
     * 2. One commits first.
     * 3. Two commits next. The row it read has changed, so Commit returns ABORTED and nothing is written.
     * 4. Two retries "from the beginning": it reads the new value and commits.
     * This is how a scion-server's compare-and-swap of a ref works on Spanner.
     */
    @Test
    void aTransactionWhoseReadRowChangedIsAbortedAndRetried() throws Exception {
        try (ScionSimulation sim = simulation()) {
            SpannerClient client = client(sim);
            String session = sim.await(client.createSession()).name();
            sim.await(client.commit(CommitRequest.singleUse(session, List.of(insert("main", "c1")))));

            ResultSet oneRead = sim.await(client.readRef(session, 
                    TransactionSelector.begin(TransactionOptions.optimisticReadWrite()), "main"));
            String one = oneRead.metadata().transaction().id();
            String other = sim.await(client.createSession()).name();
            String two = sim.await(client.begin(other)).id();
            assertEquals(oneRead.rows(), sim.await(client.readRef(other, TransactionSelector.id(two), "main")).rows());

            sim.await(client.commit(CommitRequest.of(session, one, List.of(Mutation.update(REFS, COLUMNS,
                    List.of("main", "by one"))))));
            Throwable aborted = sim.awaitFailure(client.commit(CommitRequest.of(other, two, List.of(
                    Mutation.update(REFS, COLUMNS, List.of("main", "by two"))))));

            assertEquals(Code.ABORTED, SpannerApi.code(aborted));
            assertEquals(List.of(List.of("main", "by one")), allRefs(client, sim));

            ResultSet retry = sim.await(client.readRef(session, 
                    TransactionSelector.begin(TransactionOptions.optimisticReadWrite()), "main"));
            assertEquals("by one", retry.rows().get(0).get(1));
            sim.await(client.commit(CommitRequest.of(session, retry.metadata().transaction().id(), List.of(
                    Mutation.update(REFS, COLUMNS, List.of("main", "by two"))))));
            assertEquals(List.of(List.of("main", "by two")), allRefs(client, sim));
        }
    }

    /**
     * Scenario: what happens to a transaction after it ends.
     * 1. A transaction commits, and the same Commit is sent again (as after a lost reply): it gets the same answer.
     * 2. A rolled-back transaction can't commit: ABORTED. Rolling back again, or an unknown id, is still OK.
     * 3. A transaction left idle for longer than Spanner keeps one is dropped: ABORTED.
     */
    @Test
    void finishedRolledBackAndIdleTransactions() throws Exception {
        try (ScionSimulation sim = simulation()) {
            SpannerClient client = client(sim);
            String session = sim.await(client.createSession()).name();
            String committed = sim.await(client.begin(session)).id();
            CommitRequest commit = CommitRequest.of(session, committed, List.of(insert("main", "c1")));
            CommitResponse first = sim.await(client.commit(commit));
            assertEquals(first, sim.await(client.commit(commit)));
            assertEquals(List.of(List.of("main", "c1")), allRefs(client, sim));

            String rolledBack = sim.await(client.begin(session)).id();
            sim.await(client.rollback(session, rolledBack));
            sim.await(client.rollback(session, rolledBack));
            sim.await(client.rollback(session, "no-such-transaction"));
            assertEquals(Code.ABORTED, SpannerApi.code(sim.awaitFailure(
                    client.commit(CommitRequest.of(session, rolledBack, List.of(insert("x", "c2")))))));

            String idle = sim.await(client.begin(session)).id();
            sim.tick(Spanner.TRANSACTION_IDLE_TICKS + 10);
            assertEquals(0, sim.spanner().openTransactions());
            assertEquals(Code.ABORTED, SpannerApi.code(sim.awaitFailure(
                    client.commit(CommitRequest.of(session, idle, List.of(insert("y", "c3")))))));
            assertEquals(List.of(List.of("main", "c1")), allRefs(client, sim));
        }
    }

    /**
     * Scenario: Spanner crashes at every moment while committing two rows, from before the commit arrives until
     * after it is durable. After each restart the test reads both rows.
     * Then every run holds both rows or neither, and both outcomes happen.
     */
    @Test
    void aCrashKeepsAllOfACommitOrNone() throws Exception {
        boolean sawNone = false, sawAll = false;
        for (int crashAfter = 0; crashAfter <= 30 && !(sawNone && sawAll); crashAfter++) {
            try (ScionSimulation sim = simulation()) {
                SpannerClient client = client(sim);
                String session = sim.await(client.createSession()).name();
                client.commit(CommitRequest.singleUse(session, List.of(insert("a", "c1"), insert("b", "c2"))));
                sim.tick(crashAfter);

                restartSpanner(sim);

                int rows = allRefs(client, sim).size();
                assertTrue(rows == 0 || rows == 2, "crash after " + crashAfter + " ticks kept " + rows + " rows");
                sawNone |= rows == 0;
                sawAll |= rows == 2;
            }
        }
        assertTrue(sawNone && sawAll);
    }

    /**
     * Scenario: sessions.
     * 1. A transaction is open in a session, and a single-use read runs in the same session: "Sessions can only
     *    execute one transaction at a time", so the open transaction can no longer commit (ABORTED).
     * 2. Spanner restarts. It keeps sessions in memory, so the session is gone: "If a session is deleted, requests to
     *    it return NOT_FOUND". A new session works.
     */
    @Test
    void aSessionRunsOneTransactionAndARestartDeletesSessions() throws Exception {
        try (ScionSimulation sim = simulation()) {
            SpannerClient client = client(sim);
            String session = sim.await(client.createSession()).name();
            String open = sim.await(client.begin(session)).id();
            sim.await(client.readRef(session, null, "main"));

            assertEquals(Code.ABORTED, SpannerApi.code(sim.awaitFailure(
                    client.commit(CommitRequest.of(session, open, List.of(insert("main", "c1")))))));

            restartSpanner(sim);

            Throwable gone = sim.awaitFailure(client.readRef(session, null, "main"));
            assertEquals(Code.NOT_FOUND, SpannerApi.code(gone));
            assertTrue(SpannerApi.sessionNotFound(gone));
            String fresh = sim.await(client.createSession()).name();
            assertEquals(List.of(), sim.await(client.readRef(fresh, null, "main")).rows());
        }
    }

    /**
     * Scenario: a commit, a Spanner restart, another commit.
     * Then the second commit timestamp is later than the first.
     */
    @Test
    void commitTimestampsKeepIncreasingAcrossARestart() throws Exception {
        try (ScionSimulation sim = simulation()) {
            SpannerClient client = client(sim);
            String session = sim.await(client.createSession()).name();
            Instant first = Instant.parse(sim.await(client.commit(CommitRequest.singleUse(session, List.of(insert("a", "1")))))
                    .commitTimestamp());

            restartSpanner(sim);
            String fresh = sim.await(client.createSession()).name();

            Instant second = Instant.parse(sim.await(client.commit(CommitRequest.singleUse(fresh, List.of(
                    insert("b", "2"))))).commitTimestamp());
            assertTrue(second.isAfter(first), first + " then " + second);
        }
    }
}
