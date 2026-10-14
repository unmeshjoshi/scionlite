package com.versioncontrol.scion.api;

import com.versioncontrol.scion.diff.Change;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The calls that scion-daemon, scion-cache and scion-server all answer. The post: "The scion-daemon, scion-cache,
 * and scion-server all respond to identical RPCs for this protocol, so the client doesn't need to know the cache
 * topology." Its servers provide "source control APIs for any operation a client needs, such as getting changesets,
 * computing diffs, finding merge bases, and fetching blobs"; here also creating changesets and moving refs.
 *
 * <p>Ids travel as hex strings. Objects and changesets travel as their canonical bytes, so every receiver can
 * check them against the id it asked for.
 */
public final class ScionApi {
    // Reads of immutable, content-addressed data. Caches and daemons may answer these from memory.
    public static final String GET_OBJECT = "getObject";
    public static final String GET_CHANGESET = "getChangeset";
    public static final String GET_ROOT_TREE = "getRootTree";

    /** The calls whose answer never changes, so any tier may cache it. */
    public static final Set<String> IMMUTABLE = Set.of(GET_OBJECT, GET_CHANGESET, GET_ROOT_TREE);

    // Everything else goes to a server.
    public static final String CREATE_CHANGESET = "createChangeset";
    public static final String LIST_REFS = "listRefs";
    public static final String RESOLVE_REF = "resolveRef";
    public static final String MOVE_REF = "moveRef";
    public static final String LOG = "log";
    public static final String DIFF = "diff";
    public static final String MERGE_BASE = "mergeBase";
    public static final String REBASE = "rebase";
    public static final String MERGE = "merge";

    /** A blob or a tree. */
    public record GetObjectRequest(String id) {
    }

    /** The object's canonical bytes ({@code ObjectCodec}). */
    public record ObjectReply(byte[] encoded) {
    }

    public record GetChangesetRequest(String id) {
    }

    /** The changeset's canonical bytes ({@code Changeset.encode}). */
    public record ChangesetReply(byte[] encoded) {
    }

    /** Derived data: the tree listing every file in the changeset's snapshot. */
    public record GetRootTreeRequest(String changeset) {
    }

    public record RootTreeReply(String tree) {
    }

    /** A file's new content, or null {@code content} to delete it. */
    public record FileContent(String path, byte[] content) {
    }

    /**
     * A new changeset, built and recorded on the server. {@code files} are relative to the first parent; files whose
     * content is unchanged are dropped. Author and time come from the client <b>[Lite assumption]</b>, so a retried
     * request names the same changeset.
     */
    public record CreateChangesetRequest(List<String> parents, String author, long time, String message,
                                         List<FileContent> files) {
    }

    public record CreateChangesetReply(String id) {
    }

    public record ListRefsRequest(String prefix) {
    }

    /** Ref name to changeset id. */
    public record RefsReply(Map<String, String> refs) {
    }

    public record ResolveRefRequest(String name) {
    }

    /** {@code changeset} is null when there is no such ref. */
    public record ResolveRefReply(String changeset) {
    }

    /**
     * The post: moving a ref "is a compare-and-swap on a single Spanner row". {@code expected} null: the ref must not
     * exist yet.
     */
    public record MoveRefRequest(String name, String expected, String target) {
    }

    /** {@code current} is where the ref points after the call. */
    public record MoveRefReply(boolean swapped, String current) {
    }

    /** Ancestors of {@code from}, nearest first, at most {@code limit}. */
    public record LogRequest(String from, int limit) {
    }

    public record LogReply(List<String> changesets) {
    }

    public record DiffRequest(String from, String to) {
    }

    /** Ids are blob ids; {@code before} is null for an added file and {@code after} for a deleted one. */
    public record FileDiff(String path, Change.Kind kind, String before, String after) {
    }

    public record DiffReply(List<FileDiff> files) {
    }

    public record MergeBaseRequest(String first, String second) {
    }

    /** {@code base} is null when the two have no common ancestor. */
    public record MergeBaseReply(String base) {
    }

    /**
     * Replays {@code changeset}, and its first-parent ancestors that {@code onto} lacks, on top of {@code onto}.
     * With {@code since} set, replays only the changesets after {@code since} on that chain.
     */
    public record RebaseRequest(String changeset, String onto, String since) {
    }

    /** {@code changeset} is the rebased changeset, or null when {@code conflicts} lists conflicting paths. */
    public record RebaseReply(String changeset, List<String> conflicts) {
    }

    /** A changeset whose parents are {@code ours} then {@code theirs}. */
    public record MergeRequest(String ours, String theirs, String author, long time, String message) {
    }

    /** {@code changeset} is the merge, or null when {@code conflicts} lists conflicting paths. */
    public record MergeReply(String changeset, List<String> conflicts) {
    }

    private ScionApi() {
    }
}
