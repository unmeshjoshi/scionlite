package com.versioncontrol.scion.server;

import com.versioncontrol.scion.history.CommitGraph;
import com.versioncontrol.scion.objects.Commit;
import com.versioncontrol.scion.objects.CorruptObjectException;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;
import com.versioncontrol.scion.protocol.AppendEntries;
import com.versioncontrol.scion.protocol.AppendResponse;
import com.versioncontrol.scion.protocol.FetchRequest;
import com.versioncontrol.scion.protocol.FetchResponse;
import com.versioncontrol.scion.protocol.ListRefsRequest;
import com.versioncontrol.scion.protocol.ListRefsResponse;
import com.versioncontrol.scion.protocol.LogEntry;
import com.versioncontrol.scion.protocol.Primary;
import com.versioncontrol.scion.protocol.PushRequest;
import com.versioncontrol.scion.protocol.PushResponse;
import com.versioncontrol.scion.protocol.PushStatus;
import com.versioncontrol.scion.protocol.RefUpdate;
import com.versioncontrol.scion.protocol.ScionMessageTypes;
import com.versioncontrol.scion.repo.RefNames;
import com.versioncontrol.scion.storage.Journal;
import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.Replica;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One replica of a Scion repository.
 *
 * <p>Replication is a log with a fixed primary (the replica whose id sorts first; see {@link Primary}):
 * <ul>
 *   <li>The primary checks a push against the branches as they stand after every accepted push (compare-and-set on
 *       the expected old value, fast-forward unless forced, every reachable object present), gives it the next log
 *       index and appends it to its own journal.</li>
 *   <li>Only once the entry is durable on the primary is it sent to the followers. So a follower's log is always a
 *       prefix of the primary's durable log, and no entry a follower holds can be lost by a primary crash.</li>
 *   <li>Followers accept entries in index order and acknowledge the highest index durable on their disk.</li>
 *   <li>An entry is committed once a majority (the primary included) has it durable. The push is acknowledged then,
 *       and the branch moves for readers.</li>
 *   <li>Heartbeats resend whatever a follower has not acknowledged and carry the commit index, so a follower that
 *       was partitioned or restarted catches up without any extra protocol.</li>
 *   <li>A restarted primary does not know which of its entries were committed. It answers clients only once it has
 *       committed its whole recovered log again, so it never shows an older branch than it showed before.</li>
 * </ul>
 *
 * <p>There is no failover: while the primary is down, pushes and primary reads are unavailable. Followers keep
 * serving fetches from what they know is committed, which may be behind the primary.
 */
public class ScionServer extends Replica {
    public static final String JOURNAL_FILE = "scion-server-log";
    static final int GROUP_WAIT_TICKS = 1;
    static final int HEARTBEAT_TICKS = 5;
    static final int MAX_ENTRIES_PER_APPEND = 32;

    private final ProcessId primary;
    private final Journal journal;
    private final ObjectStore objects = new ObjectStore();
    private final CommitGraph graph = new CommitGraph(objects);

    private final List<LogEntry> log = new ArrayList<>();          // log.get(i) is index i + 1
    private final SortedMap<String, ObjectId> acceptedRefs = new TreeMap<>();   // after every entry in the log
    private final SortedMap<String, ObjectId> committedRefs = new TreeMap<>();  // after entries up to appliedIndex
    private long durableIndex;
    private long commitIndex;
    private long appliedIndex;
    private long recoveredIndex;

    // Primary only
    private final Map<ProcessId, Long> matchIndex = new HashMap<>();
    private final Map<Long, Message> pushesAwaitingCommit = new HashMap<>();
    private final List<Message> deferredUntilCaughtUp = new ArrayList<>();
    private long ticks;

    public ScionServer(List<ProcessId> peerIds, ProcessParams processParams) {
        super(peerIds, processParams);
        this.primary = Primary.of(getAllNodes());
        this.journal = new Journal(fileSystem, JOURNAL_FILE, GROUP_WAIT_TICKS);
    }

    @Override
    protected TickCompletableFuture<?> onStart() {
        return journal.open().thenApply(records -> {
            for (byte[] record : records) {
                accept(deserializePayload(record, LogEntry.class));
            }
            durableIndex = log.size();
            recoveredIndex = log.size();
            if (isPrimary()) {
                advanceCommitIndex();
            }
            return null;
        });
    }

    @Override
    protected void onTick() {
        journal.tick();
        if (isPrimary() && isRunning() && ++ticks % HEARTBEAT_TICKS == 0) {
            peerIds.forEach(this::replicateTo);
        }
    }

    @Override
    protected void onStop() {
        journal.close();
    }

    @Override
    protected Map<MessageType, Handler> initialiseHandlers() {
        return Map.of(
                ScionMessageTypes.PUSH_REQUEST, this::handlePush,
                ScionMessageTypes.FETCH_REQUEST, this::handleFetch,
                ScionMessageTypes.LIST_REFS_REQUEST, this::handleListRefs,
                ScionMessageTypes.APPEND_ENTRIES, this::handleAppendEntries,
                ScionMessageTypes.APPEND_RESPONSE, this::handleAppendResponse);
    }

    // ---------- Client requests ----------

    private void handlePush(Message message) {
        if (!isPrimary()) {
            reply(message, new PushResponse(PushStatus.NOT_PRIMARY, "the primary is " + primary, Map.of()),
                    ScionMessageTypes.PUSH_RESPONSE);
            return;
        }
        if (deferUntilCaughtUp(message)) {
            return;
        }
        PushRequest request = deserializePayload(message.payload(), PushRequest.class);
        ObjectStore staged = new ObjectStore(objects);
        PushResponse rejection = validate(request, staged);
        if (rejection != null) {
            reply(message, rejection, ScionMessageTypes.PUSH_RESPONSE);
            return;
        }
        List<byte[]> newObjects = new ArrayList<>();
        for (ObjectId id : staged.ids()) {
            newObjects.add(staged.encoded(id).orElseThrow());
        }
        LogEntry entry = new LogEntry(log.size() + 1, newObjects, request.updates());
        accept(entry);
        pushesAwaitingCommit.put(entry.index(), message);
        journal.append(serializePayload(entry)).whenComplete((ignored, error) -> {
            if (error != null) {
                System.err.println(id + ": could not make log entry " + entry.index() + " durable: " + error);
                return;
            }
            durableIndex = Math.max(durableIndex, entry.index());
            peerIds.forEach(this::replicateTo);
            advanceCommitIndex();
        });
    }

    // Null if the push can be accepted. Pushed objects are added to staged, which reads through to the store.
    private PushResponse validate(PushRequest request, ObjectStore staged) {
        Map<String, String> current = currentValues(request.updates());
        if (request.updates() == null || request.updates().isEmpty()) {
            return new PushResponse(PushStatus.INVALID, "no branch updates", current);
        }
        try {
            for (byte[] encoded : request.objects() == null ? List.<byte[]>of() : request.objects()) {
                staged.putEncoded(encoded);
            }
        } catch (CorruptObjectException e) {
            return new PushResponse(PushStatus.INVALID, "corrupt object: " + e.getMessage(), current);
        }
        CommitGraph stagedGraph = new CommitGraph(staged);
        Set<String> branches = new HashSet<>();
        for (RefUpdate update : request.updates()) {
            if (!RefNames.isValidBranch(update.branch()) || !branches.add(update.branch())) {
                return new PushResponse(PushStatus.INVALID, "bad or repeated branch: " + update.branch(), current);
            }
            ObjectId expected;
            ObjectId newId;
            try {
                expected = ObjectId.ofNullable(update.expectedOld());
                newId = ObjectId.of(update.newId());
            } catch (RuntimeException e) {
                return new PushResponse(PushStatus.INVALID, "bad object id in update of " + update.branch(), current);
            }
            ObjectId existing = acceptedRefs.get(update.branch());
            if (!Objects.equals(existing, expected)) {
                return new PushResponse(PushStatus.STALE, update.branch() + " is at " + existing, current);
            }
            Set<ObjectId> missing = stagedGraph.missingObjects(List.of(newId));
            if (!missing.isEmpty()) {
                return new PushResponse(PushStatus.MISSING_OBJECTS, missing.size() + " objects missing, e.g. "
                        + missing.iterator().next(), current);
            }
            if (!(staged.get(newId) instanceof Commit)) {
                return new PushResponse(PushStatus.INVALID, newId + " is not a commit", current);
            }
            if (existing != null && !request.force() && !stagedGraph.isAncestor(existing, newId)) {
                return new PushResponse(PushStatus.NON_FAST_FORWARD,
                        update.branch() + ": " + newId.shortId() + " does not descend from " + existing.shortId(), current);
            }
        }
        return null;
    }

    private Map<String, String> currentValues(List<RefUpdate> updates) {
        Map<String, String> values = new LinkedHashMap<>();
        if (updates != null) {
            for (RefUpdate update : updates) {
                if (update.branch() != null) {
                    values.put(update.branch(), ObjectId.toHex(acceptedRefs.get(update.branch())));
                }
            }
        }
        return values;
    }

    private void handleFetch(Message message) {
        if (deferUntilCaughtUp(message)) {
            return;
        }
        FetchRequest request = deserializePayload(message.payload(), FetchRequest.class);
        List<ObjectId> haves = new ArrayList<>();
        for (String have : request.haves() == null ? List.<String>of() : request.haves()) {
            try {
                haves.add(ObjectId.of(have));
            } catch (IllegalArgumentException ignored) {
                // not an id: nothing to exclude
            }
        }
        List<byte[]> toSend = new ArrayList<>();
        for (ObjectId id : graph.objectsToSend(committedRefs.values(), haves)) {
            toSend.add(objects.encoded(id).orElseThrow());
        }
        reply(message, new FetchResponse(hexRefs(committedRefs), toSend), ScionMessageTypes.FETCH_RESPONSE);
    }

    private void handleListRefs(Message message) {
        if (deferUntilCaughtUp(message)) {
            return;
        }
        ListRefsRequest request = deserializePayload(message.payload(), ListRefsRequest.class);
        String prefix = request.prefix() == null ? "" : request.prefix();
        Map<String, String> refs = new TreeMap<>(hexRefs(committedRefs));
        refs.keySet().removeIf(name -> !name.startsWith(prefix));
        reply(message, new ListRefsResponse(refs), ScionMessageTypes.LIST_REFS_RESPONSE);
    }

    // A restarted primary holds requests until it has re-committed what it recovered.
    private boolean deferUntilCaughtUp(Message message) {
        if (isPrimary() && !isCaughtUp()) {
            deferredUntilCaughtUp.add(message);
            return true;
        }
        return false;
    }

    // ---------- Replication ----------

    private void replicateTo(ProcessId follower) {
        long from = matchIndex.getOrDefault(follower, 0L) + 1;
        long to = Math.min(durableIndex, from + MAX_ENTRIES_PER_APPEND - 1);
        List<LogEntry> entries = from <= to ? List.copyOf(log.subList((int) from - 1, (int) to)) : List.of();
        send(createMessage(follower, idGen.generateCorrelationId("append"),
                new AppendEntries(from - 1, entries, commitIndex), ScionMessageTypes.APPEND_ENTRIES));
    }

    private void handleAppendEntries(Message message) {
        if (isPrimary()) {
            return;
        }
        AppendEntries append = deserializePayload(message.payload(), AppendEntries.class);
        if (append.prevIndex() > log.size()) {
            // A gap: tell the primary how far this replica really is.
            reply(message, new AppendResponse(durableIndex), ScionMessageTypes.APPEND_RESPONSE);
            return;
        }
        List<LogEntry> fresh = append.entries().stream()
                .filter(entry -> entry.index() > log.size())   // the rest are held already, identical
                .toList();
        learnCommitIndex(append.commitIndex());
        if (fresh.isEmpty()) {
            reply(message, new AppendResponse(durableIndex), ScionMessageTypes.APPEND_RESPONSE);
            return;
        }
        for (int i = 0; i < fresh.size(); i++) {
            LogEntry entry = fresh.get(i);
            accept(entry);
            boolean last = i == fresh.size() - 1;
            // Journal records become durable in order, so when one is durable so is everything before it.
            journal.append(serializePayload(entry)).whenComplete((ignored, error) -> {
                if (error != null) {
                    return;
                }
                durableIndex = Math.max(durableIndex, entry.index());
                if (last) {
                    learnCommitIndex(append.commitIndex());
                    reply(message, new AppendResponse(durableIndex), ScionMessageTypes.APPEND_RESPONSE);
                }
            });
        }
    }

    private void learnCommitIndex(long primaryCommitIndex) {
        long known = Math.min(primaryCommitIndex, durableIndex);
        if (known > commitIndex) {
            commitIndex = known;
            applyCommitted();
        }
    }

    private void handleAppendResponse(Message message) {
        if (!isPrimary()) {
            return;
        }
        AppendResponse response = deserializePayload(message.payload(), AppendResponse.class);
        matchIndex.merge(message.source(), Math.min(response.durableIndex(), log.size()), Math::max);
        advanceCommitIndex();
    }

    // The highest index durable on a majority, counting the primary's own disk.
    private void advanceCommitIndex() {
        List<Long> durable = new ArrayList<>();
        durable.add(durableIndex);
        peerIds.forEach(peer -> durable.add(matchIndex.getOrDefault(peer, 0L)));
        durable.sort(java.util.Comparator.reverseOrder());
        long majorityDurable = durable.get(durable.size() / 2);
        if (majorityDurable <= commitIndex) {
            return;
        }
        commitIndex = majorityDurable;
        applyCommitted();
        acknowledgeCommittedPushes();
        if (isCaughtUp() && !deferredUntilCaughtUp.isEmpty()) {
            List<Message> deferred = new ArrayList<>(deferredUntilCaughtUp);
            deferredUntilCaughtUp.clear();
            deferred.forEach(m -> getHandler(m.messageType()).handle(m));
        }
    }

    private void acknowledgeCommittedPushes() {
        for (long index = 1; index <= commitIndex; index++) {
            Message push = pushesAwaitingCommit.remove(index);
            if (push != null) {
                LogEntry entry = log.get((int) index - 1);
                Map<String, String> refs = new LinkedHashMap<>();
                entry.updates().forEach(u -> refs.put(u.branch(), u.newId()));
                reply(push, new PushResponse(PushStatus.OK, "committed at log index " + index, refs),
                        ScionMessageTypes.PUSH_RESPONSE);
            }
        }
    }

    // ---------- State ----------

    private void accept(LogEntry entry) {
        if (entry.index() != log.size() + 1) {
            throw new IllegalStateException(id + ": expected log index " + (log.size() + 1) + ", got " + entry.index());
        }
        entry.objects().forEach(objects::putEncoded);
        log.add(entry);
        entry.updates().forEach(u -> acceptedRefs.put(u.branch(), ObjectId.of(u.newId())));
    }

    private void applyCommitted() {
        while (appliedIndex < commitIndex) {
            LogEntry entry = log.get((int) appliedIndex);
            entry.updates().forEach(u -> committedRefs.put(u.branch(), ObjectId.of(u.newId())));
            appliedIndex++;
        }
    }

    private boolean isCaughtUp() {
        return commitIndex >= recoveredIndex;
    }

    private void reply(Message request, Object payload, MessageType type) {
        send(createResponseMessage(request, payload, type));
    }

    private static Map<String, String> hexRefs(Map<String, ObjectId> refs) {
        Map<String, String> hex = new TreeMap<>();
        refs.forEach((name, id) -> hex.put(name, id.hex()));
        return hex;
    }

    // ---------- For tests and operators ----------

    public boolean isPrimary() {
        return id.equals(primary);
    }

    public ProcessId primary() {
        return primary;
    }

    public long logLength() {
        return log.size();
    }

    public long durableIndex() {
        return durableIndex;
    }

    public long commitIndex() {
        return commitIndex;
    }

    /** Branches as of the committed log known to this replica. */
    public SortedMap<String, ObjectId> committedRefs() {
        return new TreeMap<>(committedRefs);
    }

    public boolean hasObject(ObjectId id) {
        return objects.contains(id);
    }
}
