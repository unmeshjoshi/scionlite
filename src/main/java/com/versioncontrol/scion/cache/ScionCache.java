package com.versioncontrol.scion.cache;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scion.api.ScionApi;
import com.versioncontrol.scion.rpc.RpcException;
import com.versioncontrol.scion.rpc.RpcNode;
import com.versioncontrol.scion.topology.Topology;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The regional cache tier. It answers the same calls as a server. Reads of immutable data ({@link ScionApi#IMMUTABLE})
 * are kept in memory. The post: "When a cache is missing an object, it asks the authoritative scion-servers". It
 * talks only to servers.
 *
 * <p>The post calls these "the tree and blob cache servers". Two <b>[Lite assumptions]</b>: they also cache
 * changesets and root trees (the post: "All source control data is content-addressed, so ... any server can cache
 * it"), and every other call (creating changesets, refs, rebase, merge) is passed through to a server, because the
 * post says the daemon "talks only to" the regional cache but not how writes travel.
 *
 * <ul>
 *   <li><b>[Lite assumption]</b> Calls are spread over the servers by a hash of the request (for reads, the id).
 *       The post does not say how a cache picks a server.</li>
 *   <li><b>[Lite assumption]</b> A server that does not answer in time is skipped for a while and the call goes to
 *       the next server. Servers keep no long-lived state, so any of them can answer.</li>
 *   <li>Not modelled: the post's caches "stream objects directly from memory, in the disk format that the daemon
 *       will consume".</li>
 * </ul>
 */
public class ScionCache extends RpcNode {
    public static final int SUSPECT_TICKS = 1000;

    private final List<ProcessId> servers;
    private final Map<String, byte[]> cached = new HashMap<>();
    private final Map<ProcessId, Long> suspectUntil = new HashMap<>();
    private long now;
    private long hits;
    private long misses;
    private long forwardedUpstream;

    public ScionCache(ProcessParams params, Topology topology) {
        super(params);
        this.servers = topology.servers();
    }

    @Override
    protected void onTick() {
        now++;
    }

    @Override
    protected TickCompletableFuture<byte[]> serve(String method, byte[] body, ProcessId caller) {
        String key = method + " " + new String(body, StandardCharsets.UTF_8);
        boolean immutable = ScionApi.IMMUTABLE.contains(method);
        if (immutable) {
            byte[] hit = cached.get(key);
            if (hit != null) {
                hits++;
                return TickCompletableFuture.completed(hit);
            }
            misses++;
        }
        forwardedUpstream++;
        return forward(method, body, Math.floorMod(key.hashCode(), servers.size()), 0).thenApply(reply -> {
            if (immutable) {
                cached.put(key, reply);
            }
            return reply;
        });
    }

    /** Tries the servers in turn from {@code start}, skipping suspected ones unless there is no other choice. */
    private TickCompletableFuture<byte[]> forward(String method, byte[] body, int start, int attempt) {
        ProcessId server = pick(start, attempt);
        TickCompletableFuture<byte[]> result = new TickCompletableFuture<>();
        rpc.callRaw(server, method, body).whenComplete((reply, error) -> {
            if (error == null) {
                suspectUntil.remove(server);
                result.complete(reply);
            } else if (error instanceof RpcException || attempt + 1 >= servers.size()) {
                result.fail(error);   // the server answered with an error, or no server is left to try
            } else {
                suspectUntil.put(server, now + SUSPECT_TICKS);
                forward(method, body, start, attempt + 1).whenComplete((r, e) -> {
                    if (e == null) {
                        result.complete(r);
                    } else {
                        result.fail(e);
                    }
                });
            }
        });
        return result;
    }

    private ProcessId pick(int start, int attempt) {
        for (int i = 0; i < servers.size(); i++) {
            ProcessId candidate = servers.get((start + attempt + i) % servers.size());
            if (suspectUntil.getOrDefault(candidate, 0L) <= now) {
                return candidate;
            }
        }
        return servers.get((start + attempt) % servers.size());
    }

    /** Immutable reads answered from memory. */
    public long hits() {
        return hits;
    }

    /** Immutable reads that had to go to a server. */
    public long misses() {
        return misses;
    }

    /** Calls this cache passed on to a server. */
    public long forwardedUpstream() {
        return forwardedUpstream;
    }
}
