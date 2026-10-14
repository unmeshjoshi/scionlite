package com.versioncontrol.scion.objects;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Content-addressed objects in memory, kept as their canonical bytes. Durability is the owner's job: the local
 * repository and the server both write objects to their journal and rebuild this store from it on startup.
 */
public class ObjectStore {
    private final Map<ObjectId, byte[]> objects = new HashMap<>();
    private final ObjectStore parent;

    public ObjectStore() {
        this(null);
    }

    /**
     * A store whose reads fall back to the parent and whose writes stay here. Used to check objects received from
     * elsewhere together with the ones already held, without adding them until they are accepted.
     */
    public ObjectStore(ObjectStore parent) {
        this.parent = parent;
    }

    public ObjectId put(ScionObject object) {
        byte[] encoded = ObjectCodec.encode(object);
        ObjectId id = ObjectId.hash(encoded);
        if (!contains(id)) {
            objects.put(id, encoded);
        }
        return id;
    }

    /** Stores canonical bytes received from elsewhere, after checking they decode. Returns their id. */
    public ObjectId putEncoded(byte[] encoded) {
        ObjectCodec.decode(encoded);
        ObjectId id = ObjectId.hash(encoded);
        if (!contains(id)) {
            objects.put(id, encoded.clone());
        }
        return id;
    }

    public boolean contains(ObjectId id) {
        return objects.containsKey(id) || (parent != null && parent.contains(id));
    }

    public Optional<byte[]> encoded(ObjectId id) {
        byte[] bytes = raw(id);
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }

    public ScionObject get(ObjectId id) {
        byte[] bytes = raw(id);
        if (bytes == null) {
            throw new MissingObjectException(id);
        }
        return ObjectCodec.decode(bytes);
    }

    public Commit commit(ObjectId id) {
        return as(id, Commit.class);
    }

    public Tree tree(ObjectId id) {
        return as(id, Tree.class);
    }

    public Blob blob(ObjectId id) {
        return as(id, Blob.class);
    }

    /** Objects held here, not counting the parent's. */
    public int size() {
        return objects.size();
    }

    /** Ids held here, not counting the parent's, in id order. */
    public Set<ObjectId> ids() {
        return new TreeSet<>(objects.keySet());
    }

    public void putAllEncoded(Collection<byte[]> encoded) {
        encoded.forEach(this::putEncoded);
    }

    private byte[] raw(ObjectId id) {
        byte[] bytes = objects.get(id);
        if (bytes == null && parent != null) {
            return parent.raw(id);
        }
        return bytes;
    }

    private <T extends ScionObject> T as(ObjectId id, Class<T> type) {
        ScionObject object = get(id);
        if (!type.isInstance(object)) {
            throw new CorruptObjectException(id + " is a " + object.type().label() + ", not a "
                    + type.getSimpleName().toLowerCase());
        }
        return type.cast(object);
    }
}
