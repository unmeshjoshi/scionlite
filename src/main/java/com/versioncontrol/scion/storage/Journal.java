package com.versioncontrol.scion.storage;

import com.tickloom.fs.FileIO;
import com.tickloom.fs.FileSystem;
import com.tickloom.fs.OpenMode;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.wal.BatchedLog;
import com.tickloom.wal.Wal;

import java.util.List;

/**
 * An append-only file of records on a tickloom {@link FileSystem}, built on tickloom's {@link Wal} and
 * {@link BatchedLog}.
 *
 * <ul>
 *   <li>{@link #open()} opens (or creates) the file, makes its name durable, and returns every valid record in
 *       order. A torn or unsynced tail left by a crash is cut off.</li>
 *   <li>{@link #append(byte[])} completes only when the record is durable. Records become durable in the order
 *       they were appended. Appends close together share one sync.</li>
 * </ul>
 *
 * The owner must call {@link #tick()} and tick the file system.
 */
public class Journal {
    /** Large enough for a push of a sizeable project in one record. */
    public static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;

    private final FileSystem fileSystem;
    private final String fileName;
    private final int groupWaitTicks;
    private FileIO file;
    private BatchedLog log;

    public Journal(FileSystem fileSystem, String fileName, int groupWaitTicks) {
        this.fileSystem = fileSystem;
        this.fileName = fileName;
        this.groupWaitTicks = groupWaitTicks;
    }

    public TickCompletableFuture<List<byte[]>> open() {
        if (file != null) {
            throw new IllegalStateException("journal already open");
        }
        return fileSystem.open(fileName, OpenMode.OPEN_OR_CREATE)
                .thenCompose(opened -> fileSystem.syncDirectory().thenApply(ignored -> opened))
                .thenCompose(opened -> {
                    file = opened;
                    Wal wal = new Wal(opened, MAX_RECORD_BYTES);
                    return wal.recover().thenApply(entries -> {
                        log = new BatchedLog(wal, groupWaitTicks);
                        return entries.stream().map(Wal.Entry::payload).toList();
                    });
                });
    }

    public boolean isOpen() {
        return log != null;
    }

    /** Completes when the record is durable. */
    public TickCompletableFuture<Void> append(byte[] record) {
        if (log == null) {
            TickCompletableFuture<Void> failed = new TickCompletableFuture<>();
            failed.fail(new IllegalStateException("journal is not open"));
            return failed;
        }
        return log.append(record).thenApply(offset -> null);
    }

    public void tick() {
        if (log != null) {
            log.tick();
        }
    }

    /** Makes waiting records durable, then closes the file. */
    public TickCompletableFuture<Void> close() {
        if (log == null) {
            return TickCompletableFuture.completed(null);
        }
        BatchedLog closing = log;
        log = null;
        return closing.close();
    }
}
