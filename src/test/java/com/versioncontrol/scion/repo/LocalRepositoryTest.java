package com.versioncontrol.scion.repo;

import com.versioncontrol.scion.diff.Change;
import com.versioncontrol.scion.objects.ObjectId;
import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.util.StubClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The local repository on its own, on a simulated disk. */
class LocalRepositoryTest {
    private final SimulatedFileSystem disk = new SimulatedFileSystem();
    private final StubClock clock = new StubClock(1000);
    private Repository repo;

    @BeforeEach
    void open() {
        repo = new Repository(disk, "ada", clock);
        await(repo.open());
    }

    private <T> T await(TickCompletableFuture<T> future) {
        for (int i = 0; i < 1000 && future.isPending(); i++) {
            clock.advance(1);
            disk.tick();
            repo.tick();
        }
        assertFalse(future.isPending(), "did not complete");
        if (future.isFailed()) {
            throw new AssertionError(future.getException());
        }
        return future.getResult();
    }

    private Throwable awaitFailure(TickCompletableFuture<?> future) {
        for (int i = 0; i < 1000 && future.isPending(); i++) {
            disk.tick();
            repo.tick();
        }
        assertTrue(future.isFailed(), "expected failure");
        return future.getException();
    }

    private ObjectId commitFile(String path, String content, String message) {
        repo.writeFile(path, content);
        repo.add(path);
        return await(repo.commit(message));
    }

    @Test
    void commitRecordsTheIndexOnTheCurrentBranch() {
        ObjectId first = commitFile("README.md", "hello\n", "first");
        ObjectId second = commitFile("README.md", "hello world\n", "second");

        assertEquals(second, repo.headCommit());
        assertEquals(List.of(first), repo.readCommit(second).parents());
        assertEquals(List.of("second", "first"), repo.log().stream().map(c -> c.commit().subject()).toList());
        assertEquals("hello world\n", repo.filesAt(second).get("README.md"));
    }

    @Test
    void nothingToCommit() {
        assertInstanceOf(ScionException.class, awaitFailure(repo.commit("empty")));
        commitFile("a", "1", "first");
        assertInstanceOf(ScionException.class, awaitFailure(repo.commit("again")));
    }

    @Test
    void statusSeparatesStagedUnstagedAndUntracked() {
        commitFile("tracked", "v1", "first");
        repo.writeFile("tracked", "v2");
        repo.writeFile("new", "n");
        Status status = repo.status();
        assertEquals(List.of(), status.staged());
        assertEquals(List.of(Change.Kind.MODIFIED), status.unstaged().stream().map(Change::kind).toList());
        assertEquals(List.of("new"), status.untracked());

        repo.add("tracked", "new");
        status = repo.status();
        assertEquals(List.of("A new", "M tracked"), status.staged().stream().map(Change::toString).toList());
        assertTrue(status.unstaged().isEmpty());
    }

    @Test
    void removeStagesADeletion() {
        commitFile("a", "1", "first");
        repo.remove("a");
        ObjectId second = await(repo.commit("remove a"));
        assertTrue(repo.filesAt(second).isEmpty());
        assertEquals(Optional.empty(), repo.readFile("a"));
    }

    @Test
    void diffShowsWorkingTreeChanges() {
        commitFile("f", "one\ntwo\n", "first");
        repo.writeFile("f", "one\n2\n");
        assertEquals("""
                --- a/f
                +++ b/f
                @@ -1,2 +1,2 @@
                 one
                -two
                +2
                """, repo.diff());
    }

    @Test
    void checkoutRefusesToOverwriteAnUntrackedFile() {
        commitFile("f", "1", "first");
        await(repo.createBranch("other"));
        await(repo.checkout("other"));
        commitFile("notes", "committed on other", "notes");
        await(repo.checkout("main"));

        repo.writeFile("notes", "my untracked notes");
        assertInstanceOf(ScionException.class, awaitFailure(repo.checkout("other")));
        assertEquals("main", repo.currentBranch());
        assertEquals(Optional.of("my untracked notes"), repo.readFile("notes"));
    }

    @Test
    void fastForwardRefusesToOverwriteAnUntrackedFile() {
        commitFile("f", "1", "first");
        await(repo.createBranch("other"));
        await(repo.checkout("other"));
        commitFile("notes", "committed on other", "notes");
        await(repo.checkout("main"));

        repo.writeFile("notes", "my untracked notes");
        assertInstanceOf(ScionException.class, awaitFailure(repo.merge("other")));
        assertEquals(Optional.of("my untracked notes"), repo.readFile("notes"));
    }

    @Test
    void branchesAndCheckout() {
        ObjectId base = commitFile("f", "base", "base");
        await(repo.createBranch("feature"));
        await(repo.checkout("feature"));
        commitFile("g", "feature work", "feature");
        assertEquals("feature", repo.currentBranch());

        await(repo.checkout("main"));
        assertEquals(base, repo.headCommit());
        assertEquals(Optional.empty(), repo.readFile("g"));
        assertEquals(List.of("feature", "main"), List.copyOf(repo.branches()));
    }

    @Test
    void checkoutRefusesToLoseChanges() {
        commitFile("f", "1", "first");
        await(repo.createBranch("other"));
        repo.writeFile("f", "dirty");
        assertInstanceOf(ScionException.class, awaitFailure(repo.checkout("other")));
    }

    @Test
    void fastForwardMerge() {
        commitFile("f", "1", "first");
        await(repo.createBranch("feature"));
        await(repo.checkout("feature"));
        ObjectId tip = commitFile("f", "2", "second");
        await(repo.checkout("main"));

        MergeOutcome outcome = await(repo.merge("feature"));
        assertEquals(MergeOutcome.Kind.FAST_FORWARD, outcome.kind());
        assertEquals(tip, repo.headCommit());
        assertEquals(Optional.of("2"), repo.readFile("f"));
        assertEquals(MergeOutcome.Kind.UP_TO_DATE, await(repo.merge("feature")).kind());
    }

    @Test
    void threeWayMergeCreatesAMergeCommit() {
        commitFile("f", "a\nb\nc\n", "base");
        await(repo.createBranch("feature"));
        await(repo.checkout("feature"));
        ObjectId theirs = commitFile("f", "a\nb\nC\n", "feature edits the end");
        await(repo.checkout("main"));
        ObjectId ours = commitFile("f", "A\nb\nc\n", "main edits the start");

        MergeOutcome outcome = await(repo.merge("feature"));
        assertEquals(MergeOutcome.Kind.MERGED, outcome.kind());
        assertEquals(List.of(ours, theirs), repo.readCommit(outcome.commit()).parents());
        assertEquals(Optional.of("A\nb\nC\n"), repo.readFile("f"));
    }

    @Test
    void conflictedMergeIsFinishedByResolvingAndCommitting() {
        commitFile("f", "line\n", "base");
        await(repo.createBranch("feature"));
        await(repo.checkout("feature"));
        ObjectId theirs = commitFile("f", "theirs\n", "feature");
        await(repo.checkout("main"));
        ObjectId ours = commitFile("f", "ours\n", "main");

        MergeOutcome outcome = await(repo.merge("feature"));
        assertEquals(MergeOutcome.Kind.CONFLICTS, outcome.kind());
        assertEquals(List.of("f"), outcome.conflicts());
        assertTrue(repo.readFile("f").orElseThrow().contains("<<<<<<< main"));
        assertInstanceOf(ScionException.class, awaitFailure(repo.commit("too early")));

        repo.writeFile("f", "resolved\n");
        repo.add("f");
        ObjectId merge = await(repo.commit("merge feature"));
        assertEquals(List.of(ours, theirs), repo.readCommit(merge).parents());
        assertFalse(repo.isMerging());
    }

    @Test
    void abortMergeRestoresTheWorkingTree() {
        commitFile("f", "line\n", "base");
        await(repo.createBranch("feature"));
        await(repo.checkout("feature"));
        commitFile("f", "theirs\n", "feature");
        await(repo.checkout("main"));
        commitFile("f", "ours\n", "main");
        await(repo.merge("feature"));

        repo.abortMerge();
        assertEquals(Optional.of("ours\n"), repo.readFile("f"));
        assertTrue(repo.status().isClean());
    }

    @Test
    void historyAndBranchesSurviveACrash() {
        ObjectId first = commitFile("f", "1", "first");
        await(repo.createBranch("feature"));
        await(repo.checkout("feature"));
        ObjectId second = commitFile("f", "2", "second");
        repo.writeFile("unsaved", "lost on crash");

        disk.crash();
        disk.restart();
        repo = new Repository(disk, "ada", clock);
        await(repo.open());

        assertEquals("feature", repo.currentBranch());
        assertEquals(second, repo.headCommit());
        assertEquals(List.of(second, first), repo.log().stream().map(CommitInfo::id).toList());
        assertEquals(Optional.of("2"), repo.readFile("f"));
        assertEquals(Optional.empty(), repo.readFile("unsaved"));
    }

    @Test
    void aCommitNotYetDurableIsLostInACrash() {
        ObjectId first = commitFile("f", "1", "first");
        repo.writeFile("f", "2");
        repo.add("f");
        TickCompletableFuture<ObjectId> pending = repo.commit("second");
        assertTrue(pending.isPending());

        disk.crash();
        disk.restart();
        repo = new Repository(disk, "ada", clock);
        await(repo.open());
        assertEquals(first, repo.headCommit());
    }
}
