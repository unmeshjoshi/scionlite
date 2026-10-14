package com.versioncontrol.scion.repo;

import com.versioncontrol.scion.objects.Commit;
import com.versioncontrol.scion.objects.ObjectId;

public record CommitInfo(ObjectId id, Commit commit) {
    @Override
    public String toString() {
        return id.shortId() + " " + commit.author() + " " + commit.subject();
    }
}
