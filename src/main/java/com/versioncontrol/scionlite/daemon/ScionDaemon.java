package com.versioncontrol.scionlite.daemon;

import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scionlite.api.ScionApi;
import com.versioncontrol.scionlite.rpc.RpcNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One per node. The post: "In the fleet, the client talks over a socket to a daemon, scion-daemon, that stores
 * objects for the whole node." <b>[Lite assumption]</b>: Scion Lite gives every node, which it calls a
 * workstation, a daemon; the post describes daemons only in the fleet. Here the socket is the simulated network. The daemon answers the same calls as a
 * server and talks only to its region's cache.
 *
 * <ul>
 *   <li>The post says "The client maintains a bounded local object cache". <b>[Lite assumption]</b> In Scion Lite
 *       the node's object cache lives in the daemon, bounded to {@code capacity} immutable replies with the least
 *       recently used dropped first; scn keeps no object store of its own.</li>
 *   <li>Not modelled: "Each daemon starts from a periodically refreshed snapshot".</li>
 * </ul>
 */
public class ScionDaemon extends RpcNode {
    public static final int DEFAULT_CAPACITY = 1000;

    private final ProcessId cache;
    private final int capacity;
    private final Map<String, byte[]> cached;
    private long hits;
    private long misses;
    private long forwardedUpstream;

    public ScionDaemon(ProcessParams params, ProcessId cache, int capacity) {
        super(params);
        this.cache = cache;
        this.capacity = capacity;
        this.cached = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > ScionDaemon.this.capacity;
            }
        };
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
        return rpc.callRaw(cache, method, body).thenApply(reply -> {
            if (immutable) {
                cached.put(key, reply);
            }
            return reply;
        });
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    /** Calls this daemon passed on to its cache. */
    public long forwardedUpstream() {
        return forwardedUpstream;
    }

    /** Immutable replies held now; never more than {@link #capacity()}. */
    public int cachedEntries() {
        return cached.size();
    }

    public int capacity() {
        return capacity;
    }
}
