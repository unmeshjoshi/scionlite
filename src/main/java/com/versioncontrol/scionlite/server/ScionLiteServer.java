package com.versioncontrol.scionlite.server;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scionlite.api.ScionApi;
import com.versioncontrol.scionlite.api.ScionApi.*;
import com.versioncontrol.scionlite.async.Futures;
import com.versioncontrol.scionlite.changeset.Changeset;
import com.versioncontrol.scionlite.changeset.Compression;
import com.versioncontrol.scionlite.changeset.FileChange;
import com.versioncontrol.scionlite.diff.Change;
import com.versioncontrol.scionlite.merge.TextMerge;
import com.versioncontrol.scionlite.objects.Blob;
import com.versioncontrol.scionlite.objects.CorruptObjectException;
import com.versioncontrol.scionlite.objects.MissingObjectException;
import com.versioncontrol.scionlite.objects.ObjectCodec;
import com.versioncontrol.scionlite.objects.ObjectId;
import com.versioncontrol.scionlite.objects.ObjectStore;
import com.versioncontrol.scionlite.objects.ScionObject;
import com.versioncontrol.scionlite.rpc.RpcException;
import com.versioncontrol.scionlite.rpc.RpcNode;
import com.versioncontrol.scionlite.spanner.Schema;
import com.versioncontrol.scionlite.spanner.SpannerApi;
import com.versioncontrol.scionlite.spanner.SpannerApi.*;
import com.versioncontrol.scionlite.storage.CloudStorageApi;
import com.versioncontrol.scionlite.topology.Topology;
import com.versioncontrol.scionlite.tree.TreeEdit;
import com.versioncontrol.scionlite.tree.TreeWalker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

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
public class ScionLiteServer extends RpcNode {
    /** Read-write transactions on a ref that Spanner aborts are retried this many times. [Lite assumption] */
    public static final int MAX_TRANSACTION_ATTEMPTS = 10;

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
    /** Spanner sessions not in use. A session runs one transaction at a time, so each call takes one. */
    private final Deque<String> freeSessions = new ArrayDeque<>();
    private long sessionsCreated;
    private final TreeWalker walker;
    private long requestsServed;
    private long rootTreesComputed;

    public ScionLiteServer(ProcessParams params, Topology topology, DerivedData derivedData) {
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
        return readRow(SpannerApi.CHANGESETS, Schema.CHANGESETS_COLUMNS, id).thenApply(row -> {
            if (row == null) {
                throw new RpcException("no such changeset " + id);
            }
            Changeset changeset = Changeset.decode(Base64.getDecoder().decode(row.get(1)));
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
            return readRow(SpannerApi.ROOT_TREE, Schema.ROOT_TREE_COLUMNS, walk.next()).thenCompose(row -> {
                if (row != null) {
                    ObjectId tree = ObjectId.of(row.get(1));
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
        Mutation write = Mutation.insertOrUpdate(SpannerApi.ROOT_TREE, Schema.ROOT_TREE_COLUMNS,
                List.of(changesetId, root.hex()));
        return commitSingleUse(List.of(write))
                .thenApply(committed -> {
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
     * the root tree row. Each Spanner write is a single-use Commit. An insert that fails with ALREADY_EXISTS is fine
     * if the row holds the same changeset: the id is the content, so a retried create inserts the same row.
     */
    private TickCompletableFuture<String> persist(Changeset changeset, List<ScionObject> newBlobs, ObjectId parentRoot) {
        String id = changeset.id();
        byte[] encoded = changeset.encode();
        return storeObjects(newBlobs)
                .thenCompose(ignored -> derivedData == DerivedData.EAGER
                        ? computeRootTree(parentRoot, changeset)
                        : TickCompletableFuture.<ObjectId>completed(null))
                .thenCompose(root -> insertChangeset(id, encoded)
                        .thenCompose(inserted -> {
                            changesets.put(id, changeset);
                            return root == null ? TickCompletableFuture.completed(null) : writeRootTree(id, root);
                        }))
                .thenApply(ignored -> id);
    }

    // ---- Refs ----

    private TickCompletableFuture<RefsReply> listRefs(ListRefsRequest request) {
        String prefix = request.prefix() == null ? "" : request.prefix();
        return withSession(session -> call(topology.spanner(), SpannerApi.READ, ReadRequest.strong(session,
                        SpannerApi.REFS, Schema.REFS_COLUMNS, KeySet.range(KeyRange.prefix(prefix))), ResultSet.class))
                .thenApply(result -> {
                    Map<String, String> refs = new TreeMap<>();
                    result.rows().forEach(row -> refs.put(row.get(0), row.get(1)));
                    return new RefsReply(refs);
                });
    }

    private TickCompletableFuture<String> resolveRef(String name) {
        return readRow(SpannerApi.REFS, Schema.REFS_COLUMNS, name).thenApply(row -> row == null ? null : row.get(1));
    }

    /**
     * The post: moving a ref "is a compare-and-swap on a single Spanner row". Spanner has no compare-and-swap call,
     * so it is a read-write transaction: Read the ref's row (beginning the transaction), compare it with
     * {@code expected} here, then Commit the new value, or Roll back if it differs. Commit returns ABORTED if
     * another transaction changed the row in between; then the whole transaction is retried, as the reference says
     * to. The target must be a changeset that exists, and descend from {@code expected}: refs only move forward
     * [Lite assumption: the post doesn't say; real servers enforce this, or permissions, so that no caller can rewind
     * main]. Moving a ref to the changeset it points at is allowed. The check runs before the transaction, against
     * {@code expected}; if the ref holds something else, the compare fails anyway.
     */
    private TickCompletableFuture<MoveRefReply> moveRef(MoveRefRequest request) {
        record Attempt(int number, MoveRefReply reply) {
        }
        return changeset(request.target()).thenCompose(target -> requireFastForward(request)).thenCompose(ok -> Futures.loop(new Attempt(0, null),
                attempt -> attempt.reply() != null, attempt -> {
                    if (attempt.number() == MAX_TRANSACTION_ATTEMPTS) {
                        throw new RpcException("moveRef " + request.name() + ": aborted "
                                + MAX_TRANSACTION_ATTEMPTS + " times");
                    }
                    TickCompletableFuture<Attempt> next = new TickCompletableFuture<>();
                    compareAndSwapRef(request).whenComplete((reply, error) -> {
                        if (error == null) {
                            next.complete(new Attempt(attempt.number() + 1, reply));
                        } else if (SpannerApi.code(error) == Code.ABORTED
                                || SpannerApi.code(error) == Code.ALREADY_EXISTS) {
                            next.complete(new Attempt(attempt.number() + 1, null));
                        } else {
                            next.fail(error);
                        }
                    });
                    return next;
                }).thenApply(Attempt::reply));
    }

    private TickCompletableFuture<Void> requireFastForward(MoveRefRequest request) {
        if (request.expected() == null) {
            return TickCompletableFuture.completed(null);
        }
        return isAncestor(request.expected(), request.target()).thenApply(descends -> {
            if (!descends) {
                throw new RpcException("moveRef " + request.name() + ": " + request.target()
                        + " does not descend from " + request.expected() + "; refs only move forward");
            }
            return null;
        });
    }

    /**
     * One read-write transaction, in a session of its own. ALREADY_EXISTS: another transaction created the ref first.
     * If the row differs, the answer is known before Rollback, so a lost Rollback reply doesn't fail the call; an
     * unfinished transaction is dropped by Spanner when its session starts another or when it sits idle.
     */
    private TickCompletableFuture<MoveRefReply> compareAndSwapRef(MoveRefRequest request) {
        return withSession(session -> {
            ReadRequest read = new ReadRequest(session, TransactionSelector.begin(TransactionOptions.optimisticReadWrite()),
                    SpannerApi.REFS, Schema.REFS_COLUMNS, KeySet.key(request.name()), 0);
            return call(topology.spanner(), SpannerApi.READ, read, ResultSet.class).thenCompose(result -> {
                String transaction = result.metadata().transaction().id();
                String current = result.rows().isEmpty() ? null : result.rows().get(0).get(1);
                if (!Objects.equals(current, request.expected())) {
                    MoveRefReply notSwapped = new MoveRefReply(false, current);
                    TickCompletableFuture<MoveRefReply> answered = new TickCompletableFuture<>();
                    call(topology.spanner(), SpannerApi.ROLLBACK, new RollbackRequest(session, transaction), Empty.class)
                            .whenComplete((ignored, error) -> answered.complete(notSwapped));
                    return answered;
                }
                List<String> row = List.of(request.name(), request.target());
                Mutation write = current == null
                        ? Mutation.insert(SpannerApi.REFS, Schema.REFS_COLUMNS, row)
                        : Mutation.update(SpannerApi.REFS, Schema.REFS_COLUMNS, row);
                return call(topology.spanner(), SpannerApi.COMMIT, CommitRequest.of(session, transaction, List.of(write)),
                        CommitResponse.class).thenApply(committed -> new MoveRefReply(true, request.target()));
            });
        });
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
        return ancestry(from).thenApply(Map::keySet);
    }

    /** Every ancestor of {@code from}, including itself, with its parents, in breadth-first order. */
    private TickCompletableFuture<Map<String, List<String>>> ancestry(String from) {
        record Walk(Deque<String> queue, Set<String> seen, Map<String, List<String>> parents) {
        }
        return Futures.loop(new Walk(new ArrayDeque<>(List.of(from)), new HashSet<>(List.of(from)), new LinkedHashMap<>()),
                w -> w.queue().isEmpty(),
                w -> {
                    String id = w.queue().removeFirst();
                    return changeset(id).thenApply(changeset -> {
                        w.parents().put(id, changeset.parents());
                        changeset.parents().forEach(p -> {
                            if (w.seen().add(p)) {
                                w.queue().addLast(p);
                            }
                        });
                        return w;
                    });
                })
                .thenApply(Walk::parents);
    }

    /** Whether {@code ancestor} is {@code from} or one of its ancestors. The walk stops when it finds it. */
    private TickCompletableFuture<Boolean> isAncestor(String ancestor, String from) {
        record Walk(Deque<String> queue, Set<String> seen, boolean found) {
        }
        return Futures.loop(new Walk(new ArrayDeque<>(List.of(from)), new HashSet<>(List.of(from)), false),
                w -> w.found() || w.queue().isEmpty(),
                w -> {
                    String id = w.queue().removeFirst();
                    if (id.equals(ancestor)) {
                        return TickCompletableFuture.completed(new Walk(w.queue(), w.seen(), true));
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
    }

    /**
     * A best common ancestor, as git's merge-base finds: a common ancestor that no other common ancestor descends
     * from. The first common ancestor a walk meets is not always one: after a merge it can be an older changeset, and
     * merging from it would undo changes made since. Every ancestor of a common ancestor is common too, so the best
     * ones are the common ancestors that are not a parent of a common ancestor. After a criss-cross merge there can be
     * several; this returns the one nearest to {@code second}, breadth first [Lite assumption: git's recursive
     * strategy merges them into one]. Null if there is no common ancestor.
     */
    private TickCompletableFuture<String> mergeBase(String first, String second) {
        return ancestors(first).thenCompose(ofFirst -> ancestry(second).thenApply(ofSecond -> {
            Set<String> older = new HashSet<>();
            ofSecond.forEach((id, parents) -> {
                if (ofFirst.contains(id)) {
                    older.addAll(parents);
                }
            });
            return ofSecond.keySet().stream()
                    .filter(id -> ofFirst.contains(id) && !older.contains(id))
                    .findFirst().orElse(null);
        }));
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

    /**
     * Runs {@code work} in a Spanner session from the pool, creating one if none is free, and returns the session to
     * the pool afterwards. If Spanner has deleted the session (it restarted), the session is dropped and the work is
     * tried once more in a new one.
     */
    private <T> TickCompletableFuture<T> withSession(Function<String, TickCompletableFuture<T>> work) {
        TickCompletableFuture<T> result = new TickCompletableFuture<>();
        inSession(work).whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else if (SpannerApi.sessionNotFound(error)) {
                inSession(work).whenComplete((retried, retryError) -> {
                    if (retryError == null) {
                        result.complete(retried);
                    } else {
                        result.fail(retryError);
                    }
                });
            } else {
                result.fail(error);
            }
        });
        return result;
    }

    /** One try: a deleted session is not returned to the pool. */
    private <T> TickCompletableFuture<T> inSession(Function<String, TickCompletableFuture<T>> work) {
        TickCompletableFuture<String> session = freeSessions.isEmpty()
                ? call(topology.spanner(), SpannerApi.CREATE_SESSION, CreateSessionRequest.of(SpannerApi.DATABASE),
                        Session.class).thenApply(created -> {
                            sessionsCreated++;
                            return created.name();
                        })
                : TickCompletableFuture.completed(freeSessions.pop());
        return session.thenCompose(name -> {
            TickCompletableFuture<T> done = new TickCompletableFuture<>();
            work.apply(name).whenComplete((value, error) -> {
                if (error == null || !SpannerApi.sessionNotFound(error)) {
                    freeSessions.push(name);
                }
                if (error == null) {
                    done.complete(value);
                } else {
                    done.fail(error);
                }
            });
            return done;
        });
    }

    /** A strong single-use Read of one row: its values in {@code columns} order, or null. */
    private TickCompletableFuture<List<String>> readRow(String table, List<String> columns, String key) {
        return withSession(session -> call(topology.spanner(), SpannerApi.READ,
                ReadRequest.strong(session, table, columns, KeySet.key(key)), ResultSet.class))
                .thenApply(result -> result.rows().isEmpty() ? null : result.rows().get(0));
    }

    /** Not idempotent in Spanner; every caller writes rows whose content depends only on their key. */
    private TickCompletableFuture<CommitResponse> commitSingleUse(List<Mutation> mutations) {
        return withSession(session -> call(topology.spanner(), SpannerApi.COMMIT,
                CommitRequest.singleUse(session, mutations), CommitResponse.class));
    }

    /** Inserts the changeset row. If it is already there, it must hold the same bytes. */
    private TickCompletableFuture<Void> insertChangeset(String id, byte[] encoded) {
        String body = Base64.getEncoder().encodeToString(encoded);
        TickCompletableFuture<Void> done = new TickCompletableFuture<>();
        commitSingleUse(List.of(Mutation.insert(SpannerApi.CHANGESETS, Schema.CHANGESETS_COLUMNS,
                List.of(id, body)))).whenComplete((committed, error) -> {
            if (error == null) {
                done.complete(null);
            } else if (SpannerApi.code(error) != Code.ALREADY_EXISTS) {
                done.fail(error);
            } else {
                readRow(SpannerApi.CHANGESETS, Schema.CHANGESETS_COLUMNS, id).whenComplete((row, readError) -> {
                    if (readError != null) {
                        done.fail(readError);
                    } else if (row == null || !row.get(1).equals(body)) {
                        done.fail(new RpcException("changeset " + id + " exists with other content"));
                    } else {
                        done.complete(null);
                    }
                });
            }
        });
        return done;
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

    /** Spanner sessions this server has created since it started. */
    public long sessionsCreated() {
        return sessionsCreated;
    }
}
