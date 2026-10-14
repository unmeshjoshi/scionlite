package com.versioncontrol.scionlite.spanner;

import com.versioncontrol.scionlite.rpc.RpcException;

import java.util.List;

/**
 * The calls the Spanner node answers, modelled on Google Cloud Spanner's RPC API, the {@code google.spanner.v1.Spanner}
 * service (https://cloud.google.com/spanner/docs/reference/rpc/google.spanner.v1). Only scion-servers call them.
 *
 * <p>Scion Lite keeps five of its methods: {@code CreateSession}, {@code BeginTransaction}, {@code Read},
 * {@code Commit} and {@code Rollback}. Message and field names follow the reference, written in Java's camel case as
 * the JSON mapping of the protos does ({@code key_set} is {@code keySet}). As in the reference, every value travels as
 * JSON: a {@code STRING} as a string, and a {@code BYTES} value as "a base64-encoded string". Fields Scion Lite
 * doesn't use are left out.
 *
 * <p>Every call names a session. From the reference: "Sessions can only execute one transaction at a time", and "If a
 * session is deleted, requests to it return NOT_FOUND". This node keeps sessions in memory, so a restart deletes them
 * all.
 *
 * <p>Errors come back with a gRPC status code as the first word of the message, for example
 * {@code "ABORTED: ..."}; {@link #code} reads it.
 */
public final class SpannerApi {
    public static final String CREATE_SESSION = "google.spanner.v1.Spanner/CreateSession";
    public static final String BEGIN_TRANSACTION = "google.spanner.v1.Spanner/BeginTransaction";
    public static final String READ = "google.spanner.v1.Spanner/Read";
    public static final String COMMIT = "google.spanner.v1.Spanner/Commit";
    public static final String ROLLBACK = "google.spanner.v1.Spanner/Rollback";

    // ---- The tables Scion Lite uses. Their schema is in Schema. ----

    public static final String REFS = "refs";
    public static final String CHANGESETS = "changesets";
    public static final String ROOT_TREE = "root_tree";

    /** Scion Lite has one database. [Lite assumption] Its name. */
    public static final String DATABASE = "projects/scionlite/instances/scionlite/databases/scion";

    // ---- Sessions ----

    /** {@code session} is a template in the reference; Scion Lite sets none of its fields, so it is null. */
    public record CreateSessionRequest(String database, Session session) {
        public static CreateSessionRequest of(String database) {
            return new CreateSessionRequest(database, null);
        }
    }

    /** {@code name}: {@code <database>/sessions/<id>}. */
    public record Session(String name) {
    }

    // ---- Transactions ----

    /**
     * Only {@code OPTIMISTIC} is supported: in the reference, "reads and writes do not acquire locks during transaction
     * statement execution", and the guarantees are "provided at commit time".
     */
    public static final String OPTIMISTIC = "OPTIMISTIC";

    public record ReadWrite(String readLockMode) {
        public static ReadWrite optimistic() {
            return new ReadWrite(OPTIMISTIC);
        }
    }

    /** {@code strong}: read everything committed so far. Scion Lite has no stale reads. */
    public record ReadOnly(boolean strong) {
    }

    /** Exactly one of {@code readWrite} and {@code readOnly} is set. */
    public record TransactionOptions(ReadWrite readWrite, ReadOnly readOnly) {
        public static TransactionOptions optimisticReadWrite() {
            return new TransactionOptions(ReadWrite.optimistic(), null);
        }
    }

    /** In the reference {@code id} is bytes; here it is an opaque string. */
    public record Transaction(String id) {
    }

    public record BeginTransactionRequest(String session, TransactionOptions options) {
    }

    /**
     * Which transaction a Read runs in: at most one field is set. "If no fields are set, the default is a single use
     * transaction with strong concurrency." {@code begin} starts a transaction and returns its id in
     * {@code ResultSetMetadata.transaction}.
     */
    public record TransactionSelector(TransactionOptions singleUse, String id, TransactionOptions begin) {
        public static TransactionSelector begin(TransactionOptions options) {
            return new TransactionSelector(null, null, options);
        }

        public static TransactionSelector id(String id) {
            return new TransactionSelector(null, id, null);
        }
    }

    // ---- Keys ----

    /**
     * Keys are lists, one value for each primary-key column. Exactly one start and one end is set; a closed end
     * includes rows that match it.
     */
    public record KeyRange(List<String> startClosed, List<String> startOpen, List<String> endClosed,
                           List<String> endOpen) {
        /** Every key that starts with {@code prefix}: closed at the prefix, open at the next string after it. */
        public static KeyRange prefix(String prefix) {
            if (prefix.isEmpty()) {
                return new KeyRange(List.of(), null, List.of(), null);
            }
            char last = prefix.charAt(prefix.length() - 1);
            String after = prefix.substring(0, prefix.length() - 1) + (char) (last + 1);
            return new KeyRange(List.of(prefix), null, null, List.of(after));
        }
    }

    public record KeySet(List<List<String>> keys, List<KeyRange> ranges, boolean all) {
        public static KeySet key(String key) {
            return new KeySet(List.of(List.of(key)), List.of(), false);
        }

        public static KeySet range(KeyRange range) {
            return new KeySet(List.of(), List.of(range), false);
        }
    }

    // ---- Read ----

    /** {@code transaction} null: a single-use, strong read. {@code limit} 0: no limit. */
    public record ReadRequest(String session, TransactionSelector transaction, String table, List<String> columns,
                              KeySet keySet, long limit) {
        public static ReadRequest strong(String session, String table, List<String> columns, KeySet keySet) {
            return new ReadRequest(session, null, table, columns, keySet, 0);
        }
    }

    public record Type(String code) {
    }

    public record Field(String name, Type type) {
    }

    public record StructType(List<Field> fields) {
    }

    /** {@code transaction} is set when the read began one. */
    public record ResultSetMetadata(StructType rowType, Transaction transaction) {
    }

    /** Rows in primary-key order; each row has one value for each requested column, in that order. */
    public record ResultSet(ResultSetMetadata metadata, List<List<String>> rows) {
    }

    // ---- Commit and Rollback ----

    /** {@code values} holds one list per row, in {@code columns} order. */
    public record Write(String table, List<String> columns, List<List<String>> values) {
    }

    public record Delete(String table, KeySet keySet) {
    }

    /**
     * Exactly one field is set. From the reference: {@code insert} fails "with error ALREADY_EXISTS" if a row exists;
     * {@code update} fails "with error NOT_FOUND" if one doesn't; {@code insertOrUpdate} overwrites the columns given;
     * {@code replace} deletes the row first; {@code delete} "Succeeds whether or not the named rows were present".
     */
    public record Mutation(Write insert, Write update, Write insertOrUpdate, Write replace, Delete delete) {
        public static Mutation insert(String table, List<String> columns, List<String> values) {
            return new Mutation(new Write(table, columns, List.of(values)), null, null, null, null);
        }

        public static Mutation update(String table, List<String> columns, List<String> values) {
            return new Mutation(null, new Write(table, columns, List.of(values)), null, null, null);
        }

        public static Mutation insertOrUpdate(String table, List<String> columns, List<String> values) {
            return new Mutation(null, null, new Write(table, columns, List.of(values)), null, null);
        }

        public static Mutation delete(String table, KeySet keySet) {
            return new Mutation(null, null, null, null, new Delete(table, keySet));
        }
    }

    /**
     * Exactly one of {@code transactionId} and {@code singleUseTransaction} is set. "All mutations are applied
     * atomically, in the order they appear in this list." Committing a started transaction again returns the same
     * outcome; a single-use commit "is non-idempotent".
     */
    public record CommitRequest(String session, String transactionId, TransactionOptions singleUseTransaction,
                                List<Mutation> mutations) {
        public static CommitRequest singleUse(String session, List<Mutation> mutations) {
            return new CommitRequest(session, null, TransactionOptions.optimisticReadWrite(), mutations);
        }

        public static CommitRequest of(String session, String transactionId, List<Mutation> mutations) {
            return new CommitRequest(session, transactionId, null, mutations);
        }
    }

    /** An RFC 3339 timestamp, as in the reference's JSON encoding. */
    public record CommitResponse(String commitTimestamp) {
    }

    public record RollbackRequest(String session, String transactionId) {
    }

    /** The reference returns {@code google.protobuf.Empty}. */
    public record Empty() {
    }

    // ---- Errors ----

    /** The gRPC status codes this node returns. */
    public enum Code {
        ABORTED, ALREADY_EXISTS, NOT_FOUND, INVALID_ARGUMENT, FAILED_PRECONDITION, UNIMPLEMENTED, UNKNOWN
    }

    static RpcException error(Code code, String message) {
        return new RpcException(code + ": " + message);
    }

    /** NOT_FOUND for the session itself, not a row: create a new session and retry. */
    public static boolean sessionNotFound(Throwable failure) {
        return code(failure) == Code.NOT_FOUND && String.valueOf(rootMessage(failure)).contains("session not found");
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof RpcException)) {
            cause = cause.getCause();
        }
        return cause == null ? null : cause.getMessage();
    }

    /** The status code of a failed call, or {@link Code#UNKNOWN} (for example a timeout). */
    public static Code code(Throwable failure) {
        String message = rootMessage(failure);
        if (message == null) {
            return Code.UNKNOWN;
        }
        String first = message.split(":", 2)[0];
        for (Code code : Code.values()) {
            if (code.name().equals(first)) {
                return code;
            }
        }
        return Code.UNKNOWN;
    }

    private SpannerApi() {
    }
}
