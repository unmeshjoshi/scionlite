package com.versioncontrol.scionlite.server;

import com.versioncontrol.scionlite.changeset.Changeset;
import com.versioncontrol.scionlite.changeset.FileChange;
import com.versioncontrol.scionlite.scn.Scn;
import com.versioncontrol.scionlite.sim.ScionSimulation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The post: "every commit, rebase, or merge happens on the server, so there's nothing to push", and a changeset
 * "stores a list of the files that changed relative to its first parent" instead of pointing to a tree.
 */
class ChangesetTest {
    /**
     * Scenario: alice creates a changeset with two files, then a second that changes one of them.
     * Then the second changeset records its parent and only src/Main.java,
     * and its full file listing, derived from the parent's root tree, still has both files.
     */
    @Test
    void aCommitIsCreatedOnTheServerAndRecordsOnlyTheFilesChangedSinceItsFirstParent() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");

            String first = sim.await(alice.createChangeset(List.of(),
                    Map.of("README.md", "hello\n", "src/Main.java", "class Main {}\n"), "first"));
            String second = sim.await(alice.createChangeset(List.of(first),
                    Map.of("src/Main.java", "class Main { int x; }\n"), "second"));

            Changeset changeset = sim.await(alice.getChangeset(second));
            assertEquals(List.of(first), changeset.parents());
            assertEquals(List.of("src/Main.java"), changeset.changes().stream().map(FileChange::path).toList());

            // The full file listing is derived data: the root tree, computed from the first parent's.
            assertEquals(Map.of("README.md", "hello\n", "src/Main.java", "class Main { int x; }\n"),
                    sim.await(alice.readAllFiles(second)));
        }
    }
}
