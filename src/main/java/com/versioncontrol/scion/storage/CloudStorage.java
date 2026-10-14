package com.versioncontrol.scion.storage;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scion.rpc.RpcNode;

import static com.versioncontrol.scion.storage.CloudStorageApi.*;

/**
 * Stands in for the blob storage that, per the post, holds "the compressed file contents" a changeset points to
 * (the post also says servers "read from Spanner or cloud storage as needed"). One node with a journal on its
 * simulated disk; a reply waits until the object is durable. Real cloud storage replicates internally; callers would
 * not change. Trees are kept here too <b>[Lite assumption]</b>.
 */
public class CloudStorage extends RpcNode {
    public static final String JOURNAL_FILE = "cloud-storage-log";
    private static final String OBJECTS = "objects";

    private final DurableTables tables;
    private long requestsServed;

    public CloudStorage(ProcessParams params) {
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
            case PUT -> {
                PutRequest request = decode(body, PutRequest.class);
                if (tables.get(OBJECTS, request.key()) == null) {
                    tables.put(OBJECTS, request.key(), request.value());
                }
                yield reply(tables.afterDurable(new PutReply(true)));
            }
            case GET -> {
                GetRequest request = decode(body, GetRequest.class);
                yield reply(tables.afterDurable(new GetReply(tables.get(OBJECTS, request.key()))));
            }
            default -> failed("cloud-storage: unknown method " + method);
        };
    }

    /** Whether the object is stored, for tests. */
    public boolean contains(String key) {
        return tables.get(OBJECTS, key) != null;
    }

    public long requestsServed() {
        return requestsServed;
    }
}
