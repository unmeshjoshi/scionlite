package com.versioncontrol.scion.scn;

import com.versioncontrol.scion.sim.ScionSimulation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The post: "Starting from a root tree, the client checks its local store for missing trees and requests them
 * recursively, then repeats the process for blobs", and "Sparse and even virtualized checkouts don't need special
 * support, because the client simply doesn't ask for trees it doesn't want."
 */
class CheckoutTest {
    private static final Map<String, String> REPO = Map.of(
            "README.md", "readme\n",
            "a/one", "1\n", "a/two", "2\n", "a/deep/three", "3\n",
            "b/four", "4\n", "b/five", "5\n",
            "c/six", "6\n");

    @Test
    void fullCheckoutFetchesEveryTreeThenEveryBlob() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String id = sim.await(ada.createChangeset(List.of(), REPO, "repo"));

            sim.await(ada.checkout(id));

            assertEquals(REPO, ada.files());
            assertEquals(id, ada.head());
            assertEquals(5 + 7, ada.objectRequests());   // trees: root, a, a/deep, b, c; then 7 blobs
        }
    }

    @Test
    void sparseCheckoutFetchesOnlyTheChosenDirectories() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String id = sim.await(ada.createChangeset(List.of(), REPO, "repo"));

            sim.await(ada.checkoutSparse(id, List.of("a")));

            assertEquals(Map.of("a/one", "1\n", "a/two", "2\n", "a/deep/three", "3\n"), ada.files());
            assertEquals(3 + 3, ada.objectRequests());   // trees: root, a, a/deep; blobs under a
            assertThrows(IllegalArgumentException.class, () -> ada.write("b/four", "x"));
        }
    }

    @Test
    void virtualCheckoutFetchesAFileWhenItIsFirstRead() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String id = sim.await(ada.createChangeset(List.of(), REPO, "repo"));

            sim.await(ada.checkoutVirtual(id));
            assertEquals(0, ada.objectRequests());

            assertEquals("4\n", sim.await(ada.read("b/four")));
            assertEquals(3, ada.objectRequests());   // root, b, the blob
            assertEquals(Map.of("b/four", "4\n"), ada.files());
            assertEquals("4\n", sim.await(ada.read("b/four")));
            assertEquals(3, ada.objectRequests());
            sim.awaitFailure(ada.read("b/missing"));
        }
    }

    @Test
    void commitSendsTheTrackedEditsToTheServerAndMovesTheHead() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").build(1)) {
            Scn ada = sim.scn("ada");
            String id = sim.await(ada.createChangeset(List.of(), REPO, "repo"));
            sim.await(ada.checkout(id));

            ada.write("a/one", "one\n");
            ada.write("d/new", "new\n");
            ada.delete("c/six");
            assertEquals(3, ada.status().size());
            String committed = sim.await(ada.commit("edit"));

            assertEquals(committed, ada.head());
            assertTrue(ada.status().isEmpty());
            assertEquals(List.of(id), sim.await(ada.getChangeset(committed)).parents());
            assertEquals(ada.files(), sim.await(ada.readAllFiles(committed)));
            assertFalse(ada.files().containsKey("c/six"));
            assertFalse(ada.files().containsKey("c/six"));
            sim.awaitFailure(ada.commit("nothing"));
        }
    }

    @Test
    void aCommitIsVisibleToAnotherWorkstationAtOnceWithNothingToPush() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "ada").region("east", "bob").build(1)) {
            Scn ada = sim.scn("ada");
            ada.write("hello", "world\n");
            String id = sim.await(ada.commit("first"));

            assertEquals(Map.of("hello", "world\n"), sim.await(sim.scn("bob").readAllFiles(id)));
        }
    }
}
