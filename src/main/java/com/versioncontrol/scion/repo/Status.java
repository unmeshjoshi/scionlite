package com.versioncontrol.scion.repo;

import com.versioncontrol.scion.diff.Change;

import java.util.List;

/**
 * Staged: HEAD to index. Unstaged: index to working tree, for tracked files. Untracked: in the working tree only.
 * Conflicts: paths of a merge in progress that have not been added since.
 */
public record Status(String branch, List<Change> staged, List<Change> unstaged, List<String> untracked,
                     List<String> conflicts, boolean merging) {
    public boolean isClean() {
        return staged.isEmpty() && unstaged.isEmpty() && conflicts.isEmpty() && !merging;
    }
}
