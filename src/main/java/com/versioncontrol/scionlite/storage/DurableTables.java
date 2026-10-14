package com.versioncontrol.scionlite.storage;

import com.tickloom.fs.FileSystem;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.MessageCodec;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Named tables of key to bytes, kept in memory and made durable through a {@link Journal}. Used by the Spanner and
 * cloud-storage nodes.
 *
 * <ul>
 *   <li>A change is applied in memory at once, so later operations see it, and is journalled.</li>
 *   <li>{@link #putAll} journals several changes as one record, so after a crash either all of them are there
 *       or none is.</li>
 *   <li>{@link #afterDurable} runs an action once every change made so far is durable. Both nodes answer every
 *       request that way, so no caller ever sees a value that a crash could take back.</li>
 * </ul>
 */
public class DurableTables {
    public record Mutation(String table, String key, byte[] value) {
    }

    /** One journal record. */
    private record Batch(List<Mutation> mutations) {
    }

    private final Journal journal;
    private final MessageCodec codec = new JsonMessageCodec();
    private final Map<String, SortedMap<String, byte[]>> tables = new HashMap<>();
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private long appended;
    private long durable;

    private record Waiter(long upTo, Runnable action) {
    }

    public DurableTables(FileSystem fileSystem, String fileName) {
        this.journal = new Journal(fileSystem, fileName, 1);
    }

    public TickCompletableFuture<Void> open() {
        return journal.open().thenApply(records -> {
            records.forEach(bytes -> codec.decode(bytes, Batch.class).mutations().forEach(this::apply));
            return null;
        });
    }

    public byte[] get(String table, String key) {
        return table(table).get(key);
    }

    public SortedMap<String, byte[]> scan(String table, String prefix) {
        SortedMap<String, byte[]> rows = new TreeMap<>();
        table(table).tailMap(prefix).forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                rows.put(key, value);
            }
        });
        return rows;
    }

    /** A null value deletes the row. */
    public void put(String table, String key, byte[] value) {
        putAll(List.of(new Mutation(table, key, value)));
    }

    /** Applies the changes together and journals them as one record. */
    public void putAll(List<Mutation> mutations) {
        mutations.forEach(this::apply);
        long sequence = ++appended;
        journal.append(codec.encode(new Batch(List.copyOf(mutations)))).whenComplete((ignored, error) -> {
            if (error == null) {
                durable = Math.max(durable, sequence);
                runDurableWaiters();
            }
        });
    }

    /** Runs the action once every change made before this call is durable. */
    public void afterDurable(Runnable action) {
        if (durable >= appended) {
            action.run();
        } else {
            waiters.add(new Waiter(appended, action));
        }
    }

    /** Wraps {@link #afterDurable} as a future of the given value. */
    public <T> TickCompletableFuture<T> afterDurable(T value) {
        TickCompletableFuture<T> future = new TickCompletableFuture<>();
        afterDurable(() -> future.complete(value));
        return future;
    }

    public void tick() {
        journal.tick();
    }

    public void close() {
        journal.close();
    }

    private void runDurableWaiters() {
        while (!waiters.isEmpty() && waiters.peekFirst().upTo() <= durable) {
            waiters.pollFirst().action().run();
        }
    }

    private void apply(Mutation mutation) {
        if (mutation.value() == null) {
            table(mutation.table()).remove(mutation.key());
        } else {
            table(mutation.table()).put(mutation.key(), mutation.value());
        }
    }

    private SortedMap<String, byte[]> table(String name) {
        return tables.computeIfAbsent(name, n -> new TreeMap<>());
    }
}
