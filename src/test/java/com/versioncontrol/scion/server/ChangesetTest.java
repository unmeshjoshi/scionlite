package com.versioncontrol.scion.server;

import com.versioncontrol.scion.changeset.Changeset;
import com.versioncontrol.scion.changeset.FileChange;
import com.versioncontrol.scion.scn.Scn;
import com.versioncontrol.scion.sim.ScionSimulation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The post: "every commit, rebase, or merge happens on the server, so there's nothing to push", and a changeset
 * "stores a list of the files that changed relative to its first parent" instead of pointing to a tree.
 */
class ChangesetTest {
    @Test
    void aCommitIsCreatedOnTheServerAndRecordsOnlyTheFilesChangedSinceItsFirstParent() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().servers(2).region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");

            String first = sim.await(ada.createChangeset(List.of(),
                    Map.of("README.md", "hello\n", "src/Main.java", "class Main {}\n"), "first"));
            String second = sim.await(ada.createChangeset(List.of(first),
                    Map.of("src/Main.java", "class Main { int x; }\n"), "second"));

            Changeset changeset = sim.await(ada.getChangeset(second));
            assertEquals(List.of(first), changeset.parents());
            assertEquals(List.of("src/Main.java"), changeset.changes().stream().map(FileChange::path).toList());

            // The full file listing is derived data: the root tree, computed from the first parent's.
            assertEquals(Map.of("README.md", "hello\n", "src/Main.java", "class Main { int x; }\n"),
                    sim.await(ada.readAllFiles(second)));
        }
    }
}
