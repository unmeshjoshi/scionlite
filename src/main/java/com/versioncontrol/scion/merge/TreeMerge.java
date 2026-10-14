package com.versioncontrol.scion.merge;

import com.versioncontrol.scion.objects.Blob;
import com.versioncontrol.scion.objects.ObjectId;
import com.versioncontrol.scion.objects.ObjectStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Three-way merge of flat snapshots (path to blob id). A path changed on one side takes that side; changed the same
 * way on both, it takes it once; changed differently on both, the contents are merged line by line. A file deleted
 * on one side and changed on the other is a conflict, and the changed version is kept.
 */
public final class TreeMerge {
    public record Result(SortedMap<String, ObjectId> files, List<String> conflicts) {
        public boolean isClean() {
            return conflicts.isEmpty();
        }
    }

    private TreeMerge() {
    }

    public static Result merge(Map<String, ObjectId> base, Map<String, ObjectId> ours, Map<String, ObjectId> theirs,
                               ObjectStore store, String oursLabel, String theirsLabel) {
        SortedSet<String> paths = new TreeSet<>(base.keySet());
        paths.addAll(ours.keySet());
        paths.addAll(theirs.keySet());
        SortedMap<String, ObjectId> merged = new TreeMap<>();
        List<String> conflicts = new ArrayList<>();
        for (String path : paths) {
            ObjectId b = base.get(path);
            ObjectId o = ours.get(path);
            ObjectId t = theirs.get(path);
            ObjectId result;
            if (Objects.equals(o, t)) {
                result = o;
            } else if (Objects.equals(o, b)) {
                result = t;
            } else if (Objects.equals(t, b)) {
                result = o;
            } else if (o == null || t == null) {
                conflicts.add(path);   // deleted on one side, changed on the other
                result = o != null ? o : t;
            } else {
                byte[] baseBytes = b == null ? new byte[0] : store.blob(b).data();
                TextMerge.Result text = TextMerge.merge(baseBytes, store.blob(o).data(), store.blob(t).data(),
                        oursLabel, theirsLabel);
                if (text.conflicted()) {
                    conflicts.add(path);
                }
                result = store.put(new Blob(text.merged()));
            }
            if (result != null) {
                merged.put(path, result);
            }
        }
        return new Result(merged, conflicts);
    }
}
