package com.versioncontrol.scionlite.storage;

import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JournalTest {
    private final SimulatedFileSystem fs = new SimulatedFileSystem();

    private <T> T await(Journal journal, TickCompletableFuture<T> future) {
        for (int i = 0; i < 1000 && future.isPending(); i++) {
            fs.tick();
            journal.tick();
        }
        assertFalse(future.isPending(), "future did not complete");
        return future.getResult();
    }

    private static List<String> strings(List<byte[]> records) {
        return records.stream().map(String::new).toList();
    }

    @Test
    void newJournalIsEmpty() {
        Journal journal = new Journal(fs, "j", 1);
        assertEquals(List.of(), await(journal, journal.open()));
    }

    @Test
    void durableRecordsSurviveACrash() {
        Journal journal = new Journal(fs, "j", 1);
        await(journal, journal.open());
        await(journal, journal.append("one".getBytes()));
        await(journal, journal.append("two".getBytes()));

        fs.crash();
        fs.restart();

        Journal reopened = new Journal(fs, "j", 1);
        assertEquals(List.of("one", "two"), strings(await(reopened, reopened.open())));
    }

    @Test
    void recordsNotYetDurableAreLostInACrash() {
        Journal journal = new Journal(fs, "j", 100);   // the group wait keeps the second record unsynced
        await(journal, journal.open());
        await(journal, journal.append("synced".getBytes()));   // completes after the group wait
        TickCompletableFuture<Void> second = journal.append("unsynced".getBytes());
        fs.tick();
        journal.tick();
        assertTrue(second.isPending());

        fs.crash();
        fs.restart();

        Journal reopened = new Journal(fs, "j", 1);
        assertEquals(List.of("synced"), strings(await(reopened, reopened.open())));
    }

    @Test
    void appendBeforeOpenFails() {
        assertTrue(new Journal(fs, "j", 1).append("x".getBytes()).isFailed());
    }
}
