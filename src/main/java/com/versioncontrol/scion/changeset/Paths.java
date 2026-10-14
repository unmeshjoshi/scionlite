package com.versioncontrol.scion.changeset;

import com.versioncontrol.scion.objects.TreeEntry;

import java.util.List;

/** Repository paths are relative, use '/' between segments and name files, not directories. */
public final class Paths {
    private Paths() {
    }

    public static void validate(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        segments(path).forEach(TreeEntry::validateName);
    }

    public static List<String> segments(String path) {
        return List.of(path.split("/", -1));
    }

    /** "a/b/c.txt" is under "a" and "a/b"; every path is under "". */
    public static boolean isUnder(String path, String directory) {
        return directory.isEmpty() || path.equals(directory) || path.startsWith(directory + "/");
    }

    /** Joins a directory and a name; the root directory is "". */
    public static String join(String directory, String name) {
        return directory.isEmpty() ? name : directory + "/" + name;
    }
}
