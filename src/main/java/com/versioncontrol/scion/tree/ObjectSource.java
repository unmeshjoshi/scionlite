package com.versioncontrol.scion.tree;

import com.tickloom.future.TickCompletableFuture;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;

/**
 * Where a {@link TreeWalker} gets objects it does not have: cloud storage for a server, the daemon for scn. A
 * load completes once the object is in the walker's store.
 */
public interface ObjectSource {
    TickCompletableFuture<Void> load(ObjectId id, ObjectStore into);
}
