package com.versioncontrol.scion.spanner;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scion.rpc.RpcNode;
import com.versioncontrol.scion.storage.DurableTables;

import java.util.Arrays;

import static com.versioncontrol.scion.spanner.SpannerApi.*;

/**
 * Stands in for Google Cloud Spanner, which the post says holds Scion's refs (moving one "is a compare-and-swap on
 * a single Spanner row") and the derived data that servers write back.
 *
 * <p>For teaching this is <b>one node</b>: rows are kept in memory and made durable in a journal on its simulated
 * disk, and every reply waits until what it reports is durable. The post says nothing about how Spanner is built
 * and never mentions Raft, Paxos or consensus. Google's Spanner paper (not the post) describes replicating each
 * range of rows over several zones with Paxos. Swapping this node for a replicated implementation would not change
 * any caller.
 */
public class Spanner extends RpcNode {
    public static final String JOURNAL_FILE = "spanner-log";

    private final DurableTables tables;
    private long requestsServed;

    public Spanner(ProcessParams params) {
        super(params);
        this.tables = new DurableTables(fileSystem, JOURNAL_FILE);
    }

    @Override
    protected TickCompletableFuture<?> onStart() {
        return tables.open();
    }

    @Override
    protected void onTick() {
        tables.tick();
    }

    @Override
    protected void onStop() {
        tables.close();
    }

    @Override
    protected TickCompletableFuture<byte[]> serve(String method, byte[] body, ProcessId caller) {
        requestsServed++;
        return switch (method) {
            case READ -> {
                ReadRequest request = decode(body, ReadRequest.class);
                yield reply(tables.afterDurable(new ReadReply(tables.get(request.table(), request.key()))));
            }
            case SCAN -> {
                ScanRequest request = decode(body, ScanRequest.class);
                yield reply(tables.afterDurable(new ScanReply(tables.scan(request.table(), request.prefix()))));
            }
            case INSERT -> {
                InsertRequest request = decode(body, InsertRequest.class);
                byte[] existing = tables.get(request.table(), request.key());
                if (existing == null) {
                    tables.put(request.table(), request.key(), request.value());
                }
                yield reply(tables.afterDurable(new InsertReply(existing == null, existing)));
            }
            case WRITE -> {
                WriteRequest request = decode(body, WriteRequest.class);
                tables.put(request.table(), request.key(), request.value());
                yield reply(tables.afterDurable(new WriteReply(true)));
            }
            case COMPARE_AND_SWAP -> {
                CompareAndSwapRequest request = decode(body, CompareAndSwapRequest.class);
                byte[] current = tables.get(request.table(), request.key());
                boolean matches = Arrays.equals(current, request.expected());
                if (matches) {
                    tables.put(request.table(), request.key(), request.value());
                }
                yield reply(tables.afterDurable(
                        new CompareAndSwapReply(matches, matches ? request.value() : current)));
            }
            default -> failed("spanner: unknown method " + method);
        };
    }

    /** The row as the node holds it now, for tests. */
    public byte[] row(String table, String key) {
        return tables.get(table, key);
    }

    /** Every row of a table as the node holds it now, for tests. */
    public java.util.SortedMap<String, byte[]> rows(String table) {
        return tables.scan(table, "");
    }

    /**
     * Deletes a row directly, for tests that need derived data to be missing. Journalled like any other change.
     */
    public void deleteRowForTest(String table, String key) {
        tables.put(table, key, null);
    }

    /** Calls answered since this process started. */
    public long requestsServed() {
        return requestsServed;
    }
}
