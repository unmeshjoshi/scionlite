package com.versioncontrol.scion.spanner;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.MessageCodec;
import com.versioncontrol.scion.rpc.RpcException;
import com.versioncontrol.scion.rpc.RpcNode;
import com.versioncontrol.scion.storage.DurableTables;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.versioncontrol.scion.spanner.SpannerApi.*;

/**
 * Stands in for Google Cloud Spanner, which the post says holds Scion's refs (moving one "is a compare-and-swap on
 * a single Spanner row") and the derived data that servers write back. It answers {@code BeginTransaction},
 * {@code Read}, {@code Commit} and {@code Rollback} from Google's {@code google.spanner.v1} API ({@link SpannerApi}).
 *
 * <p>For teaching this is <b>one node</b>: rows are kept in memory and made durable in a journal on its simulated
 * disk, and every reply waits until what it reports is durable. A commit is one journal record, so a crash keeps all
 * of its mutations or none. The post says nothing about how Spanner is built and never mentions Raft, Paxos or
 * consensus. Google's Spanner paper (not the post) describes replicating each range of rows over several zones with
 * Paxos. Swapping this node for a replicated implementation would not change any caller.
 *
 * <p>Sessions are kept in memory, so a restart deletes them and later calls naming them get {@code NOT_FOUND}, as
 * for a deleted session in the reference. A session runs one transaction at a time: [Lite assumption] starting
 * another one, or a single-use read or commit, in the same session drops the open one, which is then
 * {@code ABORTED}.
 *
 * <p>Read-write transactions use the reference's {@code OPTIMISTIC} read lock mode: reads take no locks, and Commit
 * returns {@code ABORTED} if a row the transaction read has changed since. [Lite assumption] A transaction idle for
 * {@link #TRANSACTION_IDLE_TICKS} is dropped, and a later call with its id is {@code ABORTED}; the outcome of a
 * commit is kept for as long, so committing the same transaction again returns the same outcome.
 */
public class Spanner extends RpcNode {
    public static final String JOURNAL_FILE = "spanner-log";
    public static final int TRANSACTION_IDLE_TICKS = 1000;

    /** Holds the last commit timestamp, so timestamps keep increasing across a restart. */
    private static final String META = "__meta";
    private static final String LAST_COMMIT = "last_commit_micros";

    private final DurableTables tables;
    private final MessageCodec rowCodec = new JsonMessageCodec();
    /** Bumped on every write to a row (also deletes), and to the table for range reads. Not durable: no transaction
     * survives a restart. */
    private final Map<String, Long> rowVersions = new HashMap<>();
    private final Map<String, Long> tableVersions = new HashMap<>();
    /** Session name to the id of its open read-write transaction, or null. */
    private final Map<String, String> sessions = new HashMap<>();
    private final Map<String, OpenTransaction> open = new HashMap<>();
    private final Map<String, Finished> finished = new HashMap<>();
    private long version;
    private long lastCommitMicros;
    private long now;
    private long requestsServed;
    private long abortedCommits;

    private static final class OpenTransaction {
        final String session;
        final Map<String, Long> rowsRead = new HashMap<>();
        final Map<String, Long> tablesRead = new HashMap<>();
        long lastUsed;

        OpenTransaction(String session) {
            this.session = session;
        }
    }

    /** {@code response} null: the transaction failed with {@code error}. */
    private record Finished(CommitResponse response, String error, long at) {
    }

    public Spanner(ProcessParams params) {
        super(params);
        this.tables = new DurableTables(fileSystem, JOURNAL_FILE);
    }

    @Override
    protected TickCompletableFuture<?> onStart() {
        return tables.open().thenApply(ignored -> {
            byte[] last = tables.get(META, LAST_COMMIT);
            lastCommitMicros = last == null ? 0 : Long.parseLong(new String(last, StandardCharsets.UTF_8));
            return null;
        });
    }

    @Override
    protected void onTick() {
        now++;
        tables.tick();
        open.values().removeIf(t -> now - t.lastUsed > TRANSACTION_IDLE_TICKS);
        finished.values().removeIf(f -> now - f.at() > TRANSACTION_IDLE_TICKS);
    }

    @Override
    protected void onStop() {
        tables.close();
    }

    @Override
    protected TickCompletableFuture<byte[]> serve(String method, byte[] body, ProcessId caller) {
        requestsServed++;
        return switch (method) {
            case CREATE_SESSION -> reply(createSession(decode(body, CreateSessionRequest.class)));
            case BEGIN_TRANSACTION -> {
                BeginTransactionRequest request = decode(body, BeginTransactionRequest.class);
                yield reply(TickCompletableFuture.completed(begin(session(request.session()), request.options())));
            }
            case READ -> reply(read(decode(body, ReadRequest.class)));
            case COMMIT -> reply(commit(decode(body, CommitRequest.class)));
            case ROLLBACK -> reply(rollback(decode(body, RollbackRequest.class)));
            default -> failed(Code.UNIMPLEMENTED + ": spanner: unknown method " + method);
        };
    }

    // ---- Sessions ----

    private TickCompletableFuture<Session> createSession(CreateSessionRequest request) {
        if (!DATABASE.equals(request.database())) {
            throw error(Code.NOT_FOUND, "no database " + request.database());
        }
        String name = DATABASE + "/sessions/" + clock.now() + "-" + idGen.generateCorrelationId();
        sessions.put(name, null);
        return TickCompletableFuture.completed(new Session(name));
    }

    /** The session, which must exist. */
    private String session(String name) {
        if (name == null || !sessions.containsKey(name)) {
            throw error(Code.NOT_FOUND, "session not found: " + name);
        }
        return name;
    }

    /** "Sessions can only execute one transaction at a time": drops the session's open transaction, if any. */
    private void endOpenTransaction(String session) {
        String id = sessions.put(session, null);
        if (id != null && open.remove(id) != null) {
            finished.put(id, new Finished(null, Code.ABORTED + ": another transaction started in its session", now));
        }
    }

    // ---- Transactions ----

    private Transaction begin(String session, TransactionOptions options) {
        if (options == null || options.readWrite() == null) {
            throw error(Code.UNIMPLEMENTED, "only read-write transactions can be started");
        }
        if (!OPTIMISTIC.equals(options.readWrite().readLockMode())) {
            throw error(Code.UNIMPLEMENTED, "only the OPTIMISTIC read lock mode is supported");
        }
        endOpenTransaction(session);
        // The clock and a random part keep ids unique across restarts.
        String id = "txn-" + clock.now() + "-" + idGen.generateCorrelationId();
        OpenTransaction transaction = new OpenTransaction(session);
        transaction.lastUsed = now;
        open.put(id, transaction);
        sessions.put(session, id);
        return new Transaction(id);
    }

    private OpenTransaction openTransaction(String session, String id) {
        OpenTransaction transaction = open.get(id);
        if (transaction == null) {
            throw error(Code.ABORTED, "transaction " + id + " is not open (finished, expired, or Spanner restarted)");
        }
        if (!transaction.session.equals(session)) {
            throw error(Code.INVALID_ARGUMENT, "transaction " + id + " belongs to another session");
        }
        transaction.lastUsed = now;
        return transaction;
    }

    private TickCompletableFuture<Empty> rollback(RollbackRequest request) {
        String session = session(request.session());
        // "Rollback returns OK if it successfully aborts the transaction, the transaction was already aborted, or
        // the transaction isn't found. Rollback never returns ABORTED."
        OpenTransaction transaction = open.get(request.transactionId());
        if (transaction != null && transaction.session.equals(session)) {
            open.remove(request.transactionId());
            sessions.put(session, null);
            finished.put(request.transactionId(), new Finished(null, Code.ABORTED + ": rolled back", now));
        }
        return TickCompletableFuture.completed(new Empty());
    }

    // ---- Read ----

    private TickCompletableFuture<ResultSet> read(ReadRequest request) {
        String session = session(request.session());
        Schema.Table table = table(request.table());
        for (String column : request.columns()) {
            if (table.indexOf(column) < 0) {
                throw error(Code.INVALID_ARGUMENT, "no column " + column + " in " + table.name());
            }
        }
        TransactionSelector selector = request.transaction();
        Transaction begun = null;
        OpenTransaction transaction = null;
        if (selector != null && selector.begin() != null) {
            begun = begin(session, selector.begin());
            transaction = open.get(begun.id());
        } else if (selector != null && selector.id() != null) {
            transaction = openTransaction(session, selector.id());
        } else {
            endOpenTransaction(session);   // a single-use read is a transaction too
        }

        SortedMap<String, List<String>> matched = new TreeMap<>();
        KeySet keySet = request.keySet();
        for (List<String> key : keySet.keys()) {
            String k = key.get(0);
            List<String> row = row(table, k);
            if (row != null) {
                matched.put(k, row);
            }
            if (transaction != null) {
                transaction.rowsRead.put(rowId(table.name(), k), rowVersions.getOrDefault(rowId(table.name(), k), 0L));
            }
        }
        if (keySet.all() || !keySet.ranges().isEmpty()) {
            scan(table).forEach((k, row) -> {
                if (keySet.all() || keySet.ranges().stream().anyMatch(r -> inRange(k, r))) {
                    matched.put(k, row);
                }
            });
            if (transaction != null) {
                transaction.tablesRead.put(table.name(), tableVersions.getOrDefault(table.name(), 0L));
            }
        }

        List<List<String>> rows = new ArrayList<>();
        for (List<String> row : matched.values()) {
            if (request.limit() > 0 && rows.size() == request.limit()) {
                break;
            }
            rows.add(request.columns().stream().map(c -> row.get(table.indexOf(c))).toList());
        }
        ResultSetMetadata metadata = new ResultSetMetadata(table.rowType(request.columns()), begun);
        return tables.afterDurable(new ResultSet(metadata, rows));
    }

    /** Keys have one column, so a key list is empty (matches every key) or holds one value. */
    private static boolean inRange(String key, KeyRange range) {
        boolean afterStart = range.startClosed() != null
                ? range.startClosed().isEmpty() || key.compareTo(range.startClosed().get(0)) >= 0
                : !range.startOpen().isEmpty() && key.compareTo(range.startOpen().get(0)) > 0;
        boolean beforeEnd = range.endClosed() != null
                ? range.endClosed().isEmpty() || key.compareTo(range.endClosed().get(0)) <= 0
                : !range.endOpen().isEmpty() && key.compareTo(range.endOpen().get(0)) < 0;
        return afterStart && beforeEnd;
    }

    // ---- Commit ----

    private TickCompletableFuture<CommitResponse> commit(CommitRequest request) {
        String session = session(request.session());
        String id = request.transactionId();
        if (id == null) {
            if (request.singleUseTransaction() == null || request.singleUseTransaction().readWrite() == null) {
                throw error(Code.INVALID_ARGUMENT, "a commit needs a transaction id or a read-write single-use "
                        + "transaction");
            }
            endOpenTransaction(session);
            return tables.afterDurable(apply(request.mutations()));
        }
        Finished done = finished.get(id);
        if (done != null) {
            // Committing a started transaction again returns its outcome.
            if (done.response() == null) {
                throw new RpcException(done.error());
            }
            return tables.afterDurable(done.response());
        }
        OpenTransaction transaction = openTransaction(session, id);
        open.remove(id);
        sessions.put(session, null);
        try {
            boolean changed = transaction.rowsRead.entrySet().stream()
                    .anyMatch(e -> !rowVersions.getOrDefault(e.getKey(), 0L).equals(e.getValue()))
                    || transaction.tablesRead.entrySet().stream()
                    .anyMatch(e -> !tableVersions.getOrDefault(e.getKey(), 0L).equals(e.getValue()));
            if (changed) {
                abortedCommits++;
                throw error(Code.ABORTED, "a row this transaction read has changed");
            }
            CommitResponse response = apply(request.mutations());
            // Recorded now, so a repeated Commit that arrives before this one is durable gets the same answer.
            finished.put(id, new Finished(response, null, now));
            return tables.afterDurable(response);
        } catch (RuntimeException e) {
            finished.put(id, new Finished(null, e.getMessage(), now));
            throw e;
        }
    }

    /**
     * Checks every mutation against the rows as they would be after the ones before it, then applies them all as one
     * journal record. The caller replies once that record is durable.
     */
    private CommitResponse apply(List<Mutation> mutations) {
        // table name -> key -> full row, or null for a deleted row
        Map<String, Map<String, List<String>>> staged = new LinkedHashMap<>();
        for (Mutation mutation : mutations) {
            if (mutation.delete() != null) {
                Delete delete = mutation.delete();
                Schema.Table table = table(delete.table());
                Map<String, List<String>> rows = staged.computeIfAbsent(table.name(), t -> new LinkedHashMap<>());
                TreeSet<String> keys = new TreeSet<>();
                delete.keySet().keys().forEach(k -> keys.add(k.get(0)));
                if (delete.keySet().all() || !delete.keySet().ranges().isEmpty()) {
                    scan(table).keySet().stream()
                            .filter(k -> delete.keySet().all() || delete.keySet().ranges().stream()
                                    .anyMatch(r -> inRange(k, r)))
                            .forEach(keys::add);
                }
                keys.forEach(k -> rows.put(k, null));
                continue;
            }
            Write write = mutation.insert() != null ? mutation.insert()
                    : mutation.update() != null ? mutation.update()
                    : mutation.insertOrUpdate() != null ? mutation.insertOrUpdate() : mutation.replace();
            if (write == null) {
                throw error(Code.INVALID_ARGUMENT, "a mutation must set one operation");
            }
            Schema.Table table = table(write.table());
            int keyColumn = write.columns().indexOf(table.key());
            if (keyColumn < 0) {
                throw error(Code.INVALID_ARGUMENT, "the columns must include the key " + table.key());
            }
            for (String column : write.columns()) {
                if (table.indexOf(column) < 0) {
                    throw error(Code.INVALID_ARGUMENT, "no column " + column + " in " + table.name());
                }
            }
            Map<String, List<String>> rows = staged.computeIfAbsent(table.name(), t -> new LinkedHashMap<>());
            for (List<String> values : write.values()) {
                if (values.size() != write.columns().size()) {
                    throw error(Code.INVALID_ARGUMENT, "each row needs one value per column");
                }
                String key = values.get(keyColumn);
                List<String> existing = rows.containsKey(key) ? rows.get(key) : row(table, key);
                if (mutation.insert() != null && existing != null) {
                    throw error(Code.ALREADY_EXISTS, "row " + key + " in " + table.name());
                }
                if (mutation.update() != null && existing == null) {
                    throw error(Code.NOT_FOUND, "row " + key + " in " + table.name());
                }
                // insertOrUpdate and update keep the columns not written; insert and replace start empty.
                boolean keep = existing != null && (mutation.insertOrUpdate() != null || mutation.update() != null);
                List<String> row = new ArrayList<>(keep ? existing
                        : java.util.Collections.nCopies(table.columns().size(), (String) null));
                for (int i = 0; i < write.columns().size(); i++) {
                    row.set(table.indexOf(write.columns().get(i)), values.get(i));
                }
                if (row.contains(null)) {
                    throw error(Code.FAILED_PRECONDITION, "every column of " + table.name() + " is NOT NULL");
                }
                rows.put(key, row);
            }
        }

        long micros = Math.max(lastCommitMicros + 1, clock.now() * 1000);
        lastCommitMicros = micros;
        version++;
        List<DurableTables.Mutation> changes = new ArrayList<>();
        staged.forEach((tableName, rows) -> {
            tableVersions.put(tableName, version);
            rows.forEach((key, row) -> {
                rowVersions.put(rowId(tableName, key), version);
                changes.add(new DurableTables.Mutation(tableName, key, row == null ? null : rowCodec.encode(row)));
            });
        });
        changes.add(new DurableTables.Mutation(META, LAST_COMMIT,
                Long.toString(micros).getBytes(StandardCharsets.UTF_8)));
        tables.putAll(changes);
        return new CommitResponse(Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1000).toString());
    }

    // ---- Rows ----

    private static Schema.Table table(String name) {
        return Schema.table(name).orElseThrow(() -> error(Code.NOT_FOUND, "no table " + name));
    }

    private static String rowId(String table, String key) {
        return table + "/" + key;
    }

    @SuppressWarnings("unchecked")
    private List<String> row(Schema.Table table, String key) {
        byte[] stored = tables.get(table.name(), key);
        return stored == null ? null : rowCodec.decode(stored, List.class);
    }

    @SuppressWarnings("unchecked")
    private SortedMap<String, List<String>> scan(Schema.Table table) {
        SortedMap<String, List<String>> rows = new TreeMap<>();
        tables.scan(table.name(), "").forEach((k, v) -> rows.put(k, rowCodec.decode(v, List.class)));
        return rows;
    }

    // ---- For tests ----

    /** The row's values in schema column order, as the node holds it now, or null. */
    public List<String> row(String table, String key) {
        return row(table(table), key);
    }

    /** Every row of a table, keyed by primary key, as the node holds it now. */
    public SortedMap<String, List<String>> rows(String table) {
        return scan(table(table));
    }

    /**
     * Deletes a row directly, for tests that need derived data to be missing. Journalled like any other change.
     */
    public void deleteRowForTest(String table, String key) {
        tables.put(table, key, null);
        version++;
        rowVersions.put(rowId(table, key), version);
        tableVersions.put(table, version);
    }

    /** Calls answered since this process started. */
    public long requestsServed() {
        return requestsServed;
    }

    /** Commits refused with ABORTED because a row the transaction read had changed. */
    public long abortedCommits() {
        return abortedCommits;
    }

    /** Read-write transactions started and not yet finished or expired. */
    public int openTransactions() {
        return open.size();
    }
}
