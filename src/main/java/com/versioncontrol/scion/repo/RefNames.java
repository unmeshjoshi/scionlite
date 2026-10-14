package com.versioncontrol.scion.repo;

import com.versioncontrol.scion.objects.TreeEntry;

/**
 * Names of references in a local repository. Local branches live under {@code heads/}, the last known state of the
 * server's branches under {@code remotes/origin/}. On the server a branch is just its name.
 */
public final class RefNames {
    public static final String HEADS = "heads/";
    public static final String REMOTES = "remotes/origin/";
    public static final String REMOTE_NAME = "origin";

    private RefNames() {
    }

    public static String head(String branch) {
        return HEADS + branch;
    }

    public static String remote(String branch) {
        return REMOTES + branch;
    }

    /** Like a path: '/'-separated segments, none empty, "." or ".."; and no whitespace. */
    public static boolean isValidBranch(String branch) {
        if (branch == null || branch.isEmpty() || branch.startsWith("/") || branch.endsWith("/")
                || branch.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            for (String segment : branch.split("/", -1)) {
                TreeEntry.validateName(segment);
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String requireValidBranch(String branch) {
        if (!isValidBranch(branch)) {
            throw new IllegalArgumentException("invalid branch name: '" + branch + "'");
        }
        return branch;
    }
}
