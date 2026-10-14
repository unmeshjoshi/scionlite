package com.versioncontrol.scion.objects;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A snapshot of the whole project (its root tree) and the commits it came from. No parents for the first commit,
 * one for an ordinary commit, two for a merge. The timestamp comes from the process clock, so ids are deterministic
 * in simulation.
 */
public record Commit(ObjectId tree, List<ObjectId> parents, String author, long timestamp, String message)
        implements ScionObject {

    public Commit {
        Objects.requireNonNull(tree, "tree");
        parents = List.copyOf(parents);
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(message, "message");
        if (author.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("author must be a single line");
        }
    }

    public boolean isMerge() {
        return parents.size() > 1;
    }

    /** The first line of the message. */
    public String subject() {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    @Override
    public ObjectType type() {
        return ObjectType.COMMIT;
    }

    // tree <id>\n(parent <id>\n)*author <a>\ntime <t>\n\n<message>
    @Override
    public byte[] content() {
        StringBuilder out = new StringBuilder();
        out.append("tree ").append(tree.hex()).append('\n');
        for (ObjectId parent : parents) {
            out.append("parent ").append(parent.hex()).append('\n');
        }
        out.append("author ").append(author).append('\n');
        out.append("time ").append(timestamp).append('\n');
        out.append('\n').append(message);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Commit parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        int headerEnd = text.indexOf("\n\n");
        if (headerEnd < 0) {
            throw new CorruptObjectException("commit has no message separator");
        }
        ObjectId tree = null;
        List<ObjectId> parents = new ArrayList<>();
        String author = null;
        Long time = null;
        for (String line : text.substring(0, headerEnd).split("\n")) {
            int space = line.indexOf(' ');
            if (space < 0) {
                throw new CorruptObjectException("bad commit header: " + line);
            }
            String key = line.substring(0, space);
            String value = line.substring(space + 1);
            switch (key) {
                case "tree" -> tree = ObjectId.of(value);
                case "parent" -> parents.add(ObjectId.of(value));
                case "author" -> author = value;
                case "time" -> time = Long.parseLong(value);
                default -> throw new CorruptObjectException("unknown commit header: " + key);
            }
        }
        if (tree == null || author == null || time == null) {
            throw new CorruptObjectException("commit is missing tree, author or time");
        }
        return new Commit(tree, parents, author, time, text.substring(headerEnd + 2));
    }
}
