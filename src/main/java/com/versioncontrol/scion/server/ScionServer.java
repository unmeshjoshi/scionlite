package com.versioncontrol.scion.server;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scion.api.ScionApi;
import com.versioncontrol.scion.api.ScionApi.*;
import com.versioncontrol.scion.async.Futures;
import com.versioncontrol.scion.changeset.Changeset;
import com.versioncontrol.scion.changeset.Compression;
import com.versioncontrol.scion.changeset.FileChange;
import com.versioncontrol.scion.diff.Change;
import com.versioncontrol.scion.merge.TextMerge;
import com.versioncontrol.scion.objects.Blob;
import com.versioncontrol.scion.objects.CorruptObjectException;
import com.versioncontrol.scion.objects.MissingObjectException;
import com.versioncontrol.scion.objects.ObjectCodec;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;
import com.versioncontrol.scion.objects.ScionObject;
import com.versioncontrol.scion.rpc.RpcException;
import com.versioncontrol.scion.rpc.RpcNode;
import com.versioncontrol.scion.spanner.SpannerApi;
import com.versioncontrol.scion.storage.CloudStorageApi;
import com.versioncontrol.scion.topology.Topology;
import com.versioncontrol.scion.tree.TreeEdit;
import com.versioncontrol.scion.tree.TreeWalker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The authoritative tier. The post: caches ask "the authoritative scion-servers, which read from Spanner or cloud
 * storage as needed", and the servers are "essentially large caches that can also compute derived data on the fly
 * and write the results back to Spanner".
 *
 * <ul>
 *   <li>The post: "Each tier of this system maintains a cache hit rate of over 99% and does not require long-lived
 *       state". A server keeps no long-lived state: everything in its memory is a cache of immutable data (objects,
 *       changesets, root trees) that it can read again. A restarted server starts empty; any server can answer any
 *       call.</li>
 *   <li>Changesets are created here: "every commit, rebase, or merge happens on the server, so there's nothing to
 *       push". As in the post there is "no locking or cross-server coordination".</li>
 *   <li>Write order for a new changeset <b>[Lite assumption]</b>: its blobs and trees are durable in cloud storage,
 *       then its row is inserted in Spanner, then its root tree row is written, then the caller gets the id. So a
 *       changeset that exists never points at content that does not.</li>
 *   <li>Refs move separately: "moving one is a compare-and-swap on a single Spanner row that happens separately
 *       from creating changesets".</li>
 * </ul>
 */
public class ScionServer extends RpcNode {
    /**
     * The post: "for each type, we can choose whether to compute it eagerly or defer the work". Eager: a changeset's
     * root tree is computed and written when the changeset is created. Deferred: it is computed the first time someone
     * asks for it.
     */
    public enum DerivedData {EAGER, DEFERRED}

    private final Topology topology;
    private final DerivedData derivedData;
    private final ObjectStore objects = new ObjectStore();
    private final Map<String, Changeset> changesets = new HashMap<>();
    private final Map<String, ObjectId> rootTrees = new HashMap<>();
    private final TreeWalker walker;
    private long requestsServed;
    private long rootTreesComputed;

    public ScionServer(ProcessParams params, Topology topology, DerivedData derivedData) {
        super(params);
        this.topology = topology;
        this.derivedData = derivedData;
        this.walker = new TreeWalker(objects, this::loadFromCloudStorage);
    }

    @Override
    protected TickCompletableFuture<byte[]> serve(String method, byte[] body, ProcessId caller) {
        requestsServed++;
        return switch (method) {
            case ScionApi.GET_OBJECT -> reply(getObject(decode(body, GetObjectRequest.class)));
            case ScionApi.GET_CHANGESET -> reply(changeset(decode(body, GetChangesetRequest.class).id())
                    .thenApply(cs -> new ChangesetReply(cs.encode())));
            case ScionApi.GET_ROOT_TREE -> reply(rootTree(decode(body, GetRootTreeRequest.class).changeset())
                    .thenApply(tree -> new RootTreeReply(tree.hex())));
            case ScionApi.CREATE_CHANGESET -> reply(createChangeset(decode(body, CreateChangesetRequest.class)));
            case ScionApi.LIST_REFS -> reply(listRefs(decode(body, ListRefsRequest.class)));
            case ScionApi.RESOLVE_REF -> reply(resolveRef(decode(body, ResolveRefRequest.class).name())
                    .thenApply(ResolveRefReply::new));
            case ScionApi.MOVE_REF -> reply(moveRef(decode(body, MoveRefRequest.class)));
            case ScionApi.LOG -> reply(log(decode(body, LogRequest.class)));
            case ScionApi.DIFF -> reply(diff(decode(body, DiffRequest.class)));
            case ScionApi.MERGE_BASE -> {
                MergeBaseRequest request = decode(body, MergeBaseRequest.class);
                yield reply(mergeBase(request.first(), request.second()).thenApply(MergeBaseReply::new));
            }
            case ScionApi.REBASE -> reply(rebase(decode(body, RebaseRequest.class)));
            case ScionApi.MERGE -> reply(merge(decode(body, MergeRequest.class)));
            default -> failed("scion-server: unknown method " + method);
        };
    }

    // ---- Immutable reads ----

    private TickCompletableFuture<ObjectReply> getObject(GetObjectRequest request) {
        ObjectId id = ObjectId.of(request.id());
        return walker.ensure(List.of(id)).thenApply(ignored -> new ObjectReply(objects.encoded(id).orElseThrow()));
    }

    private TickCompletableFuture<Changeset> changeset(String id) {
        Changeset cached = changesets.get(id);
        if (cached != null) {
            return TickCompletableFuture.completed(cached);
        }
        return spannerRead(SpannerApi.CHANGESETS, id).thenApply(bytes -> {
            if (bytes == null) {
                throw new RpcException("no such changeset " + id);
            }
            Changeset changeset = Changeset.decode(bytes);
            if (!changeset.id().equals(id)) {
                throw new RpcException("changeset row " + id + " holds " + changeset.id());
            }
            changesets.put(id, changeset);
            return changeset;
        });
    }

    /**
     * The root tree is derived data: "its value for a changeset is computed from its values for that changeset's
     * parents". A changeset's root tree is its first parent's with the changeset's files applied. Walks back along first
     * parents to the nearest changeset whose root tree is known, then computes forward, writing each result back to
     * Spanner. The walk is a loop, so a long chain does not grow the stack.
     */
    private TickCompletableFuture<ObjectId> rootTree(String changesetId) {
        ObjectId cached = rootTrees.get(changesetId);
        if (cached != null) {
            return TickCompletableFuture.completed(cached);
        }
        record WalkBack(String next, List<Changeset> toCompute, ObjectId known, boolean done) {
        }
        return Futures.loop(new WalkBack(changesetId, new ArrayList<>(), null, false), WalkBack::done, walk -> {
            ObjectId known = rootTrees.get(walk.next());
            if (known != null) {
                return TickCompletableFuture.completed(new WalkBack(null, walk.toCompute(), known, true));
            }
            return spannerRead(SpannerApi.ROOT_TREE, walk.next()).thenCompose(row -> {
                if (row != null) {
                    ObjectId tree = ObjectId.of(new String(row, StandardCharsets.UTF_8));
                    rootTrees.put(walk.next(), tree);
                    return TickCompletableFuture.completed(new WalkBack(null, walk.toCompute(), tree, true));
                }
                return changeset(walk.next()).thenApply(changeset -> {
                    walk.toCompute().add(changeset);
                    String parent = changeset.firstParent();
                    return new WalkBack(parent, walk.toCompute(), null, parent == null);
                });
            });
        }).thenCompose(walk -> {
            List<Changeset> forward = new ArrayList<>(walk.toCompute());
            java.util.Collections.reverse(forward);
            record Forward(int index, ObjectId root) {
            }
            return Futures.loop(new Forward(0, walk.known()), f -> f.index() == forward.size(), f -> {
                Changeset changeset = forward.get(f.index());
                return computeRootTree(f.root(), changeset)
                        .thenCompose(root -> writeRootTree(changeset.id(), root))
                        .thenApply(root -> new Forward(f.index() + 1, root));
            }).thenApply(Forward::root);
        });
    }

    /** {@code parentRoot} null: no parent, start from the empty tree. Stores the new trees in cloud storage. */
    private TickCompletableFuture<ObjectId> computeRootTree(ObjectId parentRoot, Changeset changeset) {
        rootTreesComputed++;
        Set<String> directories = TreeEdit.directoriesOn(changeset.changes().stream().map(FileChange::path).toList());
        TickCompletableFuture<Void> loaded = parentRoot == null
                ? TickCompletableFuture.completed(null)
                : walker.loadDirectories(parentRoot, directories);
        return loaded.thenCompose(ignored -> {
            TreeEdit.Result edit = TreeEdit.apply(objects, parentRoot, changeset.changes());
            return storeObjects(edit.written()).thenApply(stored -> edit.root());
        });
    }

    private TickCompletableFuture<ObjectId> writeRootTree(String changesetId, ObjectId root) {
        return call(topology.spanner(), SpannerApi.WRITE,
                new SpannerApi.WriteRequest(SpannerApi.ROOT_TREE, changesetId, root.hex().getBytes(StandardCharsets.UTF_8)),
                SpannerApi.WriteReply.class)
                .thenApply(written -> {
                    rootTrees.put(changesetId, root);
                    return root;
                });
    }

    // ---- Creating changesets ----

    private TickCompletableFuture<CreateChangesetReply> createChangeset(CreateChangesetRequest request) {
        List<String> parents = request.parents() == null ? List.of() : request.parents();
        List<FileContent> files = request.files() == null ? List.of() : request.files();
        List<TickCompletableFuture<Changeset>> parentReads = new ArrayList<>();
        parents.forEach(p -> parentReads.add(changeset(p)));
        String firstParent = parents.isEmpty() ? null : parents.get(0);
        return Futures.allOf(parentReads)
                .thenCompose(ignored -> firstParent == null
                        ? TickCompletableFuture.<ObjectId>completed(null) : rootTree(firstParent))
                .thenCompose(parentRoot -> lookup(parentRoot, files.stream().map(FileContent::path).toList())
                        .thenCompose(current -> {
                            List<FileChange> changes = new ArrayList<>();
                            List<ScionObject> blobs = new ArrayList<>();
                            for (FileContent file : files) {
                                ObjectId newId = null;
                                if (file.content() != null) {
                                    Blob blob = new Blob(file.content());
                                    newId = blob.id();
                                    blobs.add(blob);
                                }
                                if (Objects.equals(newId, current.get(file.path()))) {
                                    continue;   // unchanged relative to the first parent
                                }
                                changes.add(new FileChange(file.path(), ObjectId.toHex(newId)));
                            }
                            Changeset changeset = new Changeset(parents, request.author(), request.time(),
                                    request.message(), changes);
                            return persist(changeset, blobs, parentRoot);
                        }))
                .thenApply(CreateChangesetReply::new);
    }

    private TickCompletableFuture<Map<String, ObjectId>> lookup(ObjectId root, Collection<String> paths) {
        return root == null ? TickCompletableFuture.completed(Map.of()) : walker.lookup(root, paths);
    }

    /**
     * Records a changeset built on this server. Blobs first, then (eagerly) its trees, then the Spanner row, then
     * the root tree row. Inserting an equal row again is fine: the id is the content.
     */
    private TickCompletableFuture<String> persist(Changeset changeset, List<ScionObject> newBlobs, ObjectId parentRoot) {
        String id = changeset.id();
        byte[] encoded = changeset.encode();
        return storeObjects(newBlobs)
                .thenCompose(ignored -> derivedData == DerivedData.EAGER
                        ? computeRootTree(parentRoot, changeset)
                        : TickCompletableFuture.<ObjectId>completed(null))
                .thenCompose(root -> call(topology.spanner(), SpannerApi.INSERT,
                        new SpannerApi.InsertRequest(SpannerApi.CHANGESETS, id, encoded), SpannerApi.InsertReply.class)
                        .thenCompose(insert -> {
                            if (!insert.inserted() && !Arrays.equals(insert.existing(), encoded)) {
                                throw new RpcException("changeset " + id + " exists with other content");
                            }
                            changesets.put(id, changeset);
                            return root == null ? TickCompletableFuture.completed(null) : writeRootTree(id, root);
                        }))
                .thenApply(ignored -> id);
    }

    // ---- Refs ----

    private TickCompletableFuture<RefsReply> listRefs(ListRefsRequest request) {
        String prefix = request.prefix() == null ? "" : request.prefix();
        return call(topology.spanner(), SpannerApi.SCAN, new SpannerApi.ScanRequest(SpannerApi.REFS, prefix),
                SpannerApi.ScanReply.class)
                .thenApply(scan -> {
                    Map<String, String> refs = new TreeMap<>();
                    scan.rows().forEach((name, value) -> refs.put(name, new String(value, StandardCharsets.UTF_8)));
                    return new RefsReply(refs);
                });
    }

    private TickCompletableFuture<String> resolveRef(String name) {
        return spannerRead(SpannerApi.REFS, name)
                .thenApply(value -> value == null ? null : new String(value, StandardCharsets.UTF_8));
    }

    /** A compare-and-swap on the ref's row. The target must be a changeset that exists. */
    private TickCompletableFuture<MoveRefReply> moveRef(MoveRefRequest request) {
        return changeset(request.target()).thenCompose(target -> call(topology.spanner(), SpannerApi.COMPARE_AND_SWAP,
                new SpannerApi.CompareAndSwapRequest(SpannerApi.REFS, request.name(), utf8(request.expected()),
                        utf8(request.target())),
                SpannerApi.CompareAndSwapReply.class))
                .thenApply(cas -> new MoveRefReply(cas.swapped(),
                        cas.current() == null ? null : new String(cas.current(), StandardCharsets.UTF_8)));
    }

    // ---- History ----

    private TickCompletableFuture<LogReply> log(LogRequest request) {
        record Walk(Deque<String> queue, Set<String> seen, List<String> out) {
        }
        Deque<String> queue = new ArrayDeque<>(List.of(request.from()));
        int limit = request.limit() <= 0 ? Integer.MAX_VALUE : request.limit();
        return Futures.loop(new Walk(queue, new HashSet<>(queue), new ArrayList<>()),
                w -> w.queue().isEmpty() || w.out().size() >= limit,
                w -> {
                    String id = w.queue().removeFirst();
                    return changeset(id).thenApply(changeset -> {
                        w.out().add(id);
                        changeset.parents().forEach(p -> {
                            if (w.seen().add(p)) {
                                w.queue().addLast(p);
                            }
                        });
                        return w;
                    });
                })
                .thenApply(w -> new LogReply(List.copyOf(w.out())));
    }

    /** Every ancestor of {@code from}, including itself. */
    private TickCompletableFuture<Set<String>> ancestors(String from) {
        record Walk(Deque<String> queue, Set<String> seen) {
        }
        return Futures.loop(new Walk(new ArrayDeque<>(List.of(from)), new HashSet<>(List.of(from))),
                w -> w.queue().isEmpty(),
                w -> changeset(w.queue().removeFirst()).thenApply(changeset -> {
                    changeset.parents().forEach(p -> {
                        if (w.seen().add(p)) {
                            w.queue().addLast(p);
                        }
                    });
                    return w;
                }))
                .thenApply(Walk::seen);
    }

    /** The common ancestor nearest to {@code second}, searching breadth first; null if there is none. */
    private TickCompletableFuture<String> mergeBase(String first, String second) {
        return ancestors(first).thenCompose(ofFirst -> {
            record Walk(Deque<String> queue, Set<String> seen, String found) {
            }
            return Futures.loop(new Walk(new ArrayDeque<>(List.of(second)), new HashSet<>(List.of(second)), null),
                    w -> w.found() != null || w.queue().isEmpty(),
                    w -> {
                        String id = w.queue().removeFirst();
                        if (ofFirst.contains(id)) {
                            return TickCompletableFuture.completed(new Walk(w.queue(), w.seen(), id));
                        }
                        return changeset(id).thenApply(changeset -> {
                            changeset.parents().forEach(p -> {
                                if (w.seen().add(p)) {
                                    w.queue().addLast(p);
                                }
                            });
                            return w;
                        });
                    })
                    .thenApply(Walk::found);
        });
    }

    private TickCompletableFuture<DiffReply> diff(DiffRequest request) {
        return rootTree(request.from()).thenCompose(from -> rootTree(request.to())
                .thenCompose(to -> walker.diff(from, to)))
                .thenApply(changes -> new DiffReply(changes.stream()
                        .map(c -> new FileDiff(c.path(), c.kind(), ObjectId.toHex(c.oldId()), ObjectId.toHex(c.newId())))
                        .toList()));
    }

    // ---- Rebase and merge, on the server, without locks ----

    /**
     * Replays the changeset, and the first-parent ancestors of it that {@code onto} does not have, on top of
     * {@code onto}. Because a changeset records only the files it changed relative to its first parent, replaying
     * it looks up just those paths in the old parent and in the new one: a file upstream did not touch takes the
     * changeset's version; a file both changed is merged line by line, and a conflict stops the rebase.
     */
    private TickCompletableFuture<RebaseReply> rebase(RebaseRequest request) {
        return ancestors(request.onto()).thenCompose(ofOnto -> {
            if (ofOnto.contains(request.changeset()) || request.changeset().equals(request.since())) {
                return TickCompletableFuture.completed(new RebaseReply(request.onto(), List.of()));
            }
            record WalkBack(String next, List<Changeset> chain, boolean done) {
            }
            return Futures.loop(new WalkBack(request.changeset(), new ArrayList<>(), false), WalkBack::done,
                    w -> changeset(w.next()).thenApply(changeset -> {
                        if (changeset.parents().size() > 1) {
                            throw new RpcException("rebase of a merge changeset is not supported: " + w.next());
                        }
                        w.chain().add(changeset);
                        String parent = changeset.firstParent();
                        return new WalkBack(parent, w.chain(),
                                parent == null || ofOnto.contains(parent) || parent.equals(request.since()));
                    }))
                    .thenCompose(walk -> {
                        List<Changeset> chain = new ArrayList<>(walk.chain());
                        java.util.Collections.reverse(chain);
                        if (Objects.equals(chain.get(0).firstParent(), request.onto())) {
                            return TickCompletableFuture.completed(new RebaseReply(request.changeset(), List.of()));
                        }
                        record Replay(int index, String tip, List<String> conflicts) {
                        }
                        return Futures.loop(new Replay(0, request.onto(), List.of()),
                                r -> r.index() == chain.size() || !r.conflicts().isEmpty(),
                                r -> replay(chain.get(r.index()), r.tip()).thenApply(result -> result.conflicts().isEmpty()
                                        ? new Replay(r.index() + 1, result.changeset(), List.of())
                                        : new Replay(r.index(), null, result.conflicts())))
                                .thenApply(r -> r.conflicts().isEmpty()
                                        ? new RebaseReply(r.tip(), List.of())
                                        : new RebaseReply(null, r.conflicts()));
                    });
        });
    }

    private TickCompletableFuture<RebaseReply> replay(Changeset changeset, String newParent) {
        List<String> paths = changeset.changes().stream().map(FileChange::path).toList();
        Map<String, ObjectId> theirs = new HashMap<>();
        changeset.changes().forEach(c -> theirs.put(c.path(), c.blobId()));
        TickCompletableFuture<ObjectId> oldRoot = changeset.firstParent() == null
                ? TickCompletableFuture.completed(null) : rootTree(changeset.firstParent());
        return oldRoot.thenCompose(baseRoot -> lookup(baseRoot, paths))
                .thenCompose(base -> rootTree(newParent).thenCompose(newRoot -> lookup(newRoot, paths)
                        .thenCompose(ours -> resolve(paths, base, ours, theirs, "onto " + shortId(newParent),
                                shortId(changeset.id())))
                        .thenCompose(resolution -> {
                            if (!resolution.conflicts().isEmpty()) {
                                return TickCompletableFuture.completed(new RebaseReply(null, resolution.conflicts()));
                            }
                            if (resolution.changes().isEmpty() && !changeset.changes().isEmpty()) {
                                // The new parent already has every change: replaying it would add an empty changeset.
                                return TickCompletableFuture.completed(new RebaseReply(newParent, List.of()));
                            }
                            Changeset rebased = new Changeset(List.of(newParent), changeset.author(), changeset.time(),
                                    changeset.message(), resolution.changes());
                            return persist(rebased, resolution.newBlobs(), newRoot)
                                    .thenApply(id -> new RebaseReply(id, List.of()));
                        })));
    }

    /** A changeset with parents {@code ours} then {@code theirs}; its changes are relative to {@code ours}. */
    private TickCompletableFuture<MergeReply> merge(MergeRequest request) {
        return mergeBase(request.ours(), request.theirs()).thenCompose(base -> {
            if (request.theirs().equals(base)) {
                return TickCompletableFuture.completed(new MergeReply(request.ours(), List.of()));
            }
            if (request.ours().equals(base)) {
                return TickCompletableFuture.completed(new MergeReply(request.theirs(), List.of()));
            }
            TickCompletableFuture<ObjectId> baseRoot = base == null
                    ? TickCompletableFuture.completed(null) : rootTree(base);
            return baseRoot.thenCompose(b -> rootTree(request.theirs()).thenCompose(t -> walker.diff(b, t)))
                    .thenCompose(theirChanges -> rootTree(request.ours()).thenCompose(oursRoot -> {
                        List<String> paths = theirChanges.stream().map(Change::path).toList();
                        Map<String, ObjectId> baseValues = new HashMap<>();
                        Map<String, ObjectId> theirValues = new HashMap<>();
                        theirChanges.forEach(c -> {
                            baseValues.put(c.path(), c.oldId());
                            theirValues.put(c.path(), c.newId());
                        });
                        return lookup(oursRoot, paths)
                                .thenCompose(ours -> resolve(paths, baseValues, ours, theirValues,
                                        shortId(request.ours()), shortId(request.theirs())))
                                .thenCompose(resolution -> {
                                    if (!resolution.conflicts().isEmpty()) {
                                        return TickCompletableFuture.completed(
                                                new MergeReply(null, resolution.conflicts()));
                                    }
                                    Changeset merge = new Changeset(List.of(request.ours(), request.theirs()),
                                            request.author(), request.time(), request.message(), resolution.changes());
                                    return persist(merge, resolution.newBlobs(), oursRoot)
                                            .thenApply(id -> new MergeReply(id, List.of()));
                                });
                    }));
        });
    }

    private record Resolution(List<FileChange> changes, List<ScionObject> newBlobs, List<String> conflicts) {
    }

    /**
     * Three-way merge of the given paths. Changes are relative to {@code ours}. A path changed differently on both
     * sides is merged line by line; deleted on one side and changed on the other, it conflicts.
     */
    private TickCompletableFuture<Resolution> resolve(List<String> paths, Map<String, ObjectId> base,
                                                      Map<String, ObjectId> ours, Map<String, ObjectId> theirs,
                                                      String oursLabel, String theirsLabel) {
        Set<ObjectId> needed = new LinkedHashSet<>();
        for (String path : paths) {
            ObjectId b = base.get(path), o = ours.get(path), t = theirs.get(path);
            if (!Objects.equals(o, t) && !Objects.equals(o, b) && !Objects.equals(t, b) && o != null && t != null) {
                needed.add(o);
                needed.add(t);
                if (b != null) {
                    needed.add(b);
                }
            }
        }
        return walker.ensure(needed).thenApply(ignored -> {
            List<FileChange> changes = new ArrayList<>();
            List<ScionObject> newBlobs = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            for (String path : new java.util.TreeSet<>(paths)) {
                ObjectId b = base.get(path), o = ours.get(path), t = theirs.get(path);
                if (Objects.equals(o, t) || Objects.equals(t, b)) {
                    continue;   // ours already has it
                }
                if (Objects.equals(o, b)) {
                    changes.add(new FileChange(path, ObjectId.toHex(t)));
                    continue;
                }
                if (o == null || t == null) {
                    conflicts.add(path);   // deleted on one side, changed on the other
                    continue;
                }
                byte[] baseBytes = b == null ? new byte[0] : objects.blob(b).data();
                TextMerge.Result text = TextMerge.merge(baseBytes, objects.blob(o).data(), objects.blob(t).data(),
                        oursLabel, theirsLabel);
                if (text.conflicted()) {
                    conflicts.add(path);
                    continue;
                }
                Blob merged = new Blob(text.merged());
                newBlobs.add(merged);
                changes.add(FileChange.write(path, merged.id()));
            }
            return new Resolution(changes, newBlobs, conflicts);
        });
    }

    // ---- Spanner and cloud storage ----

    private TickCompletableFuture<byte[]> spannerRead(String table, String key) {
        return call(topology.spanner(), SpannerApi.READ, new SpannerApi.ReadRequest(table, key),
                SpannerApi.ReadReply.class).thenApply(SpannerApi.ReadReply::value);
    }

    /** Objects are stored compressed, keyed by id. Completes once all are durable. */
    private TickCompletableFuture<Void> storeObjects(Collection<? extends ScionObject> toStore) {
        Map<ObjectId, byte[]> unique = new TreeMap<>();
        toStore.forEach(object -> {
            byte[] encoded = ObjectCodec.encode(object);
            unique.put(ObjectId.hash(encoded), encoded);
        });
        List<TickCompletableFuture<CloudStorageApi.PutReply>> puts = new ArrayList<>();
        unique.forEach((id, encoded) -> puts.add(call(topology.cloudStorage(), CloudStorageApi.PUT,
                new CloudStorageApi.PutRequest(id.hex(), Compression.compress(encoded)), CloudStorageApi.PutReply.class)));
        return Futures.allOf(puts).thenApply(ignored -> null);
    }

    private TickCompletableFuture<Void> loadFromCloudStorage(ObjectId id, ObjectStore into) {
        return call(topology.cloudStorage(), CloudStorageApi.GET, new CloudStorageApi.GetRequest(id.hex()),
                CloudStorageApi.GetReply.class)
                .thenApply(reply -> {
                    if (reply.value() == null) {
                        throw new MissingObjectException(id);
                    }
                    byte[] encoded = Compression.decompress(reply.value());
                    if (!ObjectId.hash(encoded).equals(id)) {
                        throw new CorruptObjectException("cloud storage returned other content for " + id);
                    }
                    into.putEncoded(encoded);
                    return null;
                });
    }

    private static byte[] utf8(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    private static String shortId(String id) {
        return id == null ? "none" : id.substring(0, Math.min(10, id.length()));
    }

    // ---- For tests ----

    public long requestsServed() {
        return requestsServed;
    }

    /** Root trees this server computed (rather than read from Spanner or its memory). */
    public long rootTreesComputed() {
        return rootTreesComputed;
    }

    /** Immutable data held in memory. A restarted server starts with none. */
    public int cachedObjects() {
        return objects.size() + changesets.size() + rootTrees.size();
    }
}
