package com.versioncontrol.scion.history;

import com.versioncontrol.scion.objects.Blob;
import com.versioncontrol.scion.objects.Commit;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;
import com.versioncontrol.scion.snapshot.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommitGraphTest {
    private final ObjectStore store = new ObjectStore();
    private final CommitGraph graph = new CommitGraph(store);
    private long time = 1;

    private ObjectId commit(String content, ObjectId... parents) {
        ObjectId tree = Snapshots.writeFiles(Map.of("file", content.getBytes()), store);
        return store.put(new Commit(tree, List.of(parents), "test", time++, content));
    }

    @Test
    void logListsNewestFirst() {
        ObjectId a = commit("a");
        ObjectId b = commit("b", a);
        ObjectId c = commit("c", b);
        assertEquals(List.of(c, b, a), graph.log(c));
    }

    @Test
    void ancestry() {
        ObjectId a = commit("a");
        ObjectId b = commit("b", a);
        ObjectId c = commit("c", a);
        assertTrue(graph.isAncestor(a, b));
        assertTrue(graph.isAncestor(b, b));
        assertFalse(graph.isAncestor(b, c));
    }

    @Test
    void mergeBaseOfDivergedBranches() {
        ObjectId a = commit("a");
        ObjectId b = commit("b", a);
        ObjectId c = commit("c", b);
        ObjectId d = commit("d", b);
        ObjectId e = commit("e", d);
        assertEquals(Optional.of(b), graph.mergeBase(c, e));
        assertEquals(Optional.of(b), graph.mergeBase(b, e));
    }

    @Test
    void mergeBaseAfterAnEarlierMergeIsTheMergedCommit() {
        ObjectId a = commit("a");
        ObjectId b = commit("b", a);
        ObjectId c = commit("c", a);
        ObjectId m = commit("m", b, c);   // c merged into b's line
        ObjectId d = commit("d", c);      // c's line continues
        ObjectId n = commit("n", m);
        assertEquals(Optional.of(c), graph.mergeBase(n, d));
    }

    @Test
    void unrelatedHistoriesHaveNoMergeBase() {
        assertEquals(Optional.empty(), graph.mergeBase(commit("x"), commit("y")));
    }

    @Test
    void objectsToSendExcludesWhatTheOtherSideHas() {
        ObjectId a = commit("a");
        ObjectId b = commit("b", a);
        Set<ObjectId> all = graph.objectsToSend(List.of(b), List.of());
        Set<ObjectId> onlyNew = graph.objectsToSend(List.of(b), List.of(a));
        assertTrue(all.contains(a));
        assertTrue(onlyNew.contains(b));
        assertFalse(onlyNew.contains(a));
        assertFalse(onlyNew.contains(store.commit(a).tree()));
        assertTrue(onlyNew.contains(store.commit(b).tree()));
    }

    @Test
    void missingObjectsFindsWhatIsNotInTheStore() {
        ObjectStore partial = new ObjectStore();
        ObjectId tree = Snapshots.writeFiles(Map.of("f", "x".getBytes()), partial);
        ObjectId parent = Blob.of("pretend parent").id();
        ObjectId c = partial.put(new Commit(tree, List.of(parent), "t", 1, "m"));
        assertEquals(Set.of(parent), new CommitGraph(partial).missingObjects(List.of(c)));
    }
}
