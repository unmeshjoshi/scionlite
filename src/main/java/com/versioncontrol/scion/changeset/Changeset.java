package com.versioncontrol.scion.changeset;

import com.versioncontrol.scion.objects.ObjectId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The unit of history: in the post, a changeset is "an entry in a write-ahead log", and "a changeset stores a list
 * of the files that changed relative to its first parent", "along with content-addressed pointers to the
 * compressed file contents in blob storage". There is no pointer to a tree: the full listing of files (the root tree) is
 * derived data.
 *
 * <p>The post: "All source control data is content-addressed". The id is the SHA-256 of {@link #encode()}; the hash
 * and the encoding are <b>[Lite assumptions]</b>. Content addressing makes a retried create idempotent without any
 * coordination between servers. The fields besides parents and changes (author, time, message) are also a
 * <b>[Lite assumption]</b>: the post says only that "A changeset stores most of what you'd expect from a commit in
 * git". Where the
 * log lives is also not said; Scion Lite keeps it in the Spanner {@code changesets} table <b>[Lite assumption]</b>.
 */
public record Changeset(List<String> parents, String author, long time, String message, List<FileChange> changes) {
    public Changeset {
        parents = List.copyOf(parents);
        parents.forEach(ObjectId::of);
        if (new HashSet<>(parents).size() != parents.size()) {
            throw new IllegalArgumentException("duplicate parent");
        }
        author = author == null ? "" : author;
        message = message == null ? "" : message;
        List<FileChange> sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparing(FileChange::path));
        Set<String> seen = new HashSet<>();
        for (FileChange change : sorted) {
            if (!seen.add(change.path())) {
                throw new IllegalArgumentException("path changed twice: " + change.path());
            }
        }
        changes = List.copyOf(sorted);
    }

    /** The parent the changes are relative to, or null for a changeset with no parents. */
    public String firstParent() {
        return parents.isEmpty() ? null : parents.get(0);
    }

    public String id() {
        return ObjectId.hash(encode()).hex();
    }

    /**
     * The canonical bytes, which are also what the {@code changesets} table stores:
     * <pre>
     * parent &lt;id&gt;            (one line per parent, in order)
     * author &lt;text&gt;
     * time &lt;number&gt;
     * change &lt;blob id|-&gt; &lt;path&gt;   (one line per change, by path; "-" is a delete)
     * message &lt;length&gt;
     * &lt;message&gt;
     * </pre>
     */
    public byte[] encode() {
        StringBuilder out = new StringBuilder();
        parents.forEach(p -> out.append("parent ").append(p).append('\n'));
        out.append("author ").append(author.replace('\n', ' ')).append('\n');
        out.append("time ").append(time).append('\n');
        changes.forEach(c -> out.append("change ").append(c.blob() == null ? "-" : c.blob())
                .append(' ').append(c.path()).append('\n'));
        out.append("message ").append(message.length()).append('\n').append(message);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static Changeset decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> parents = new ArrayList<>();
        List<FileChange> changes = new ArrayList<>();
        String author = null;
        long time = 0;
        int position = 0;
        while (position < text.length()) {
            int end = text.indexOf('\n', position);
            if (end < 0) {
                throw new IllegalArgumentException("changeset line not terminated");
            }
            String line = text.substring(position, end);
            position = end + 1;
            int space = line.indexOf(' ');
            String key = space < 0 ? line : line.substring(0, space);
            String value = space < 0 ? "" : line.substring(space + 1);
            switch (key) {
                case "parent" -> parents.add(value);
                case "author" -> author = value;
                case "time" -> time = Long.parseLong(value);
                case "change" -> {
                    int split = value.indexOf(' ');
                    String blob = value.substring(0, split);
                    changes.add(new FileChange(value.substring(split + 1), blob.equals("-") ? null : blob));
                }
                case "message" -> {
                    int length = Integer.parseInt(value);
                    return new Changeset(parents, author, time, text.substring(position, position + length), changes);
                }
                default -> throw new IllegalArgumentException("bad changeset line: " + line);
            }
        }
        throw new IllegalArgumentException("changeset has no message");
    }
}
