package com.versioncontrol.scion.diff;

import com.versioncontrol.scion.objects.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/** The files that differ between two flat snapshots (path to blob id), in path order. */
public final class TreeDiff {
    private TreeDiff() {
    }

    public static List<Change> diff(Map<String, ObjectId> before, Map<String, ObjectId> after) {
        SortedSet<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<Change> changes = new ArrayList<>();
        for (String path : paths) {
            ObjectId oldId = before.get(path);
            ObjectId newId = after.get(path);
            if (Objects.equals(oldId, newId)) {
                continue;
            }
            Change.Kind kind = oldId == null ? Change.Kind.ADDED : newId == null ? Change.Kind.DELETED : Change.Kind.MODIFIED;
            changes.add(new Change(kind, path, oldId, newId));
        }
        return changes;
    }
}
