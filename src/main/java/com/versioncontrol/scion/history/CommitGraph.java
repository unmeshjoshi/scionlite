package com.versioncontrol.scion.history;

import com.versioncontrol.scion.objects.Commit;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;
import com.versioncontrol.scion.objects.ScionObject;
import com.versioncontrol.scion.objects.Tree;
import com.versioncontrol.scion.objects.TreeEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/** Questions about the commit graph held in one object store. */
public class CommitGraph {
    private final ObjectStore store;

    public CommitGraph(ObjectStore store) {
        this.store = store;
    }

    /** Commits reachable from the tip, newest first (by timestamp, then id), each once. */
    public List<ObjectId> log(ObjectId tip) {
        List<ObjectId> out = new ArrayList<>();
        if (tip == null) {
            return out;
        }
        Comparator<ObjectId> newestFirst = Comparator
                .comparingLong((ObjectId id) -> store.commit(id).timestamp()).reversed()
                .thenComparing(Comparator.reverseOrder());
        PriorityQueue<ObjectId> queue = new PriorityQueue<>(newestFirst);
        Set<ObjectId> seen = new HashSet<>();
        queue.add(tip);
        seen.add(tip);
        while (!queue.isEmpty()) {
            ObjectId id = queue.poll();
            out.add(id);
            for (ObjectId parent : store.commit(id).parents()) {
                if (seen.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return out;
    }

    /** All commits reachable from the tip, including the tip. */
    public Set<ObjectId> ancestors(ObjectId tip) {
        Set<ObjectId> seen = new HashSet<>();
        if (tip == null) {
            return seen;
        }
        Deque<ObjectId> stack = new ArrayDeque<>();
        stack.push(tip);
        while (!stack.isEmpty()) {
            ObjectId id = stack.pop();
            if (seen.add(id)) {
                store.commit(id).parents().forEach(stack::push);
            }
        }
        return seen;
    }

    /** True if {@code ancestor} is {@code descendant} or reachable from it. */
    public boolean isAncestor(ObjectId ancestor, ObjectId descendant) {
        return ancestors(descendant).contains(ancestor);
    }

    /**
     * The best common ancestor: a common ancestor that is not an ancestor of another common ancestor. With
     * criss-cross history there can be several; the newest (then the greatest id) is chosen so the answer is
     * deterministic.
     */
    public Optional<ObjectId> mergeBase(ObjectId a, ObjectId b) {
        Set<ObjectId> common = ancestors(a);
        common.retainAll(ancestors(b));
        Set<ObjectId> best = new HashSet<>(common);
        for (ObjectId candidate : common) {
            for (ObjectId parent : store.commit(candidate).parents()) {
                best.removeAll(ancestors(parent));
            }
        }
        return best.stream().max(Comparator
                .comparingLong((ObjectId id) -> store.commit(id).timestamp())
                .thenComparing(Comparator.naturalOrder()));
    }

    /**
     * Every object (commits, trees, blobs) reachable from the tips but not from the haves. Haves this store does not
     * know are ignored. Used to send only what the other side lacks.
     */
    public Set<ObjectId> objectsToSend(Collection<ObjectId> tips, Collection<ObjectId> haves) {
        Set<ObjectId> excluded = new HashSet<>();
        for (ObjectId have : haves) {
            if (have != null && store.contains(have)) {
                walk(have, Set.of(), excluded);
            }
        }
        Set<ObjectId> out = new LinkedHashSet<>();
        for (ObjectId tip : tips) {
            if (tip != null) {
                walk(tip, excluded, out);
            }
        }
        return out;
    }

    /** Ids reachable from the tips, as far as the store can follow, that the store does not have. */
    public Set<ObjectId> missingObjects(Collection<ObjectId> tips) {
        Set<ObjectId> missing = new LinkedHashSet<>();
        Set<ObjectId> seen = new HashSet<>();
        Deque<ObjectId> stack = new ArrayDeque<>(tips);
        while (!stack.isEmpty()) {
            ObjectId id = stack.pop();
            if (!seen.add(id)) {
                continue;
            }
            if (!store.contains(id)) {
                missing.add(id);
                continue;
            }
            children(store.get(id)).forEach(stack::push);
        }
        return missing;
    }

    // Adds to out everything reachable from start that is not in stop and not already in out.
    private void walk(ObjectId start, Set<ObjectId> stop, Set<ObjectId> out) {
        Deque<ObjectId> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            ObjectId id = stack.pop();
            if (stop.contains(id) || !out.add(id)) {
                continue;
            }
            children(store.get(id)).forEach(stack::push);
        }
    }

    private static List<ObjectId> children(ScionObject object) {
        List<ObjectId> children = new ArrayList<>();
        if (object instanceof Commit commit) {
            children.add(commit.tree());
            children.addAll(commit.parents());
        } else if (object instanceof Tree tree) {
            for (TreeEntry entry : tree.entries()) {
                children.add(entry.id());
            }
        }
        return children;
    }
}
