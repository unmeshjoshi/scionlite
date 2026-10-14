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

    /**
     * Scenario: alice makes a full checkout of a 7-file repository with 5 directories (counting the root).
     * 1. scn fetches the root tree, then the missing trees one level at a time, then the blobs.
     * Then the working copy holds every file, and scn asked for exactly 5 trees and 7 blobs.
     */
    @Test
    void fullCheckoutFetchesEveryTreeThenEveryBlob() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String id = sim.await(alice.createChangeset(List.of(), REPO, "repo"));

            sim.await(alice.checkout(id));

            assertEquals(REPO, alice.files());
            assertEquals(id, alice.head());
            assertEquals(5 + 7, alice.objectRequests());   // trees: root, a, a/deep, b, c; then 7 blobs
        }
    }

    /**
     * Scenario: alice makes a sparse checkout of directory a only.
     * Then scn fetched the trees on the way to a and the files under it, nothing from b or c,
     * and writing outside a is refused.
     */
    @Test
    void sparseCheckoutFetchesOnlyTheChosenDirectories() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String id = sim.await(alice.createChangeset(List.of(), REPO, "repo"));

            sim.await(alice.checkoutSparse(id, List.of("a")));

            assertEquals(Map.of("a/one", "1\n", "a/two", "2\n", "a/deep/three", "3\n"), alice.files());
            assertEquals(3 + 3, alice.objectRequests());   // trees: root, a, a/deep; blobs under a
            assertThrows(IllegalArgumentException.class, () -> alice.write("b/four", "x"));
        }
    }

    /**
     * Scenario: alice makes a virtual checkout and then reads one file.
     * 1. The checkout fetches nothing.
     * 2. Reading b/four fetches the root tree, the b tree and that one blob.
     * 3. Reading it again fetches nothing more.
     * 4. Reading a file that does not exist fails.
     */
    @Test
    void virtualCheckoutFetchesAFileWhenItIsFirstRead() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String id = sim.await(alice.createChangeset(List.of(), REPO, "repo"));

            sim.await(alice.checkoutVirtual(id));
            assertEquals(0, alice.objectRequests());

            assertEquals("4\n", sim.await(alice.read("b/four")));
            assertEquals(3, alice.objectRequests());   // root, b, the blob
            assertEquals(Map.of("b/four", "4\n"), alice.files());
            assertEquals("4\n", sim.await(alice.read("b/four")));
            assertEquals(3, alice.objectRequests());
            sim.awaitFailure(alice.read("b/missing"));
        }
    }

    /**
     * Scenario: alice checks out a repository, then edits one file, adds one and deletes one.
     * 1. status shows the three edits.
     * 2. alice commits. The server creates the changeset with the old head as its parent.
     * Then the head moves to the new changeset, status is empty, the server's files match the working copy,
     * and committing again with no edits fails.
     */
    @Test
    void commitSendsTheTrackedEditsToTheServerAndMovesTheHead() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").build(1)) {
            Scn alice = sim.scn("alice");
            String id = sim.await(alice.createChangeset(List.of(), REPO, "repo"));
            sim.await(alice.checkout(id));

            alice.write("a/one", "one\n");
            alice.write("d/new", "new\n");
            alice.delete("c/six");
            assertEquals(3, alice.status().size());
            String committed = sim.await(alice.commit("edit"));

            assertEquals(committed, alice.head());
            assertTrue(alice.status().isEmpty());
            assertEquals(List.of(id), sim.await(alice.getChangeset(committed)).parents());
            assertEquals(alice.files(), sim.await(alice.readAllFiles(committed)));
            assertFalse(alice.files().containsKey("c/six"));
            assertFalse(alice.files().containsKey("c/six"));
            sim.awaitFailure(alice.commit("nothing"));
        }
    }

    /**
     * Scenario: alice in west commits a file; bob is in east.
     * Then bob can read alice's commit straight away. The commit was created on the server, so nothing was pushed.
     */
    @Test
    void aCommitIsVisibleToAnotherWorkstationAtOnceWithNothingToPush() throws Exception {
        try (ScionSimulation sim = ScionSimulation.builder().region("west", "alice").region("east", "bob").build(1)) {
            Scn alice = sim.scn("alice");
            alice.write("hello", "world\n");
            String id = sim.await(alice.commit("first"));

            assertEquals(Map.of("hello", "world\n"), sim.await(sim.scn("bob").readAllFiles(id)));
        }
    }
}
