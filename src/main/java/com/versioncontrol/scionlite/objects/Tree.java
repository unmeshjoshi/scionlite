package com.versioncontrol.scionlite.objects;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A directory listing. Entries are kept sorted by name, so the same directory always has the same id whatever
 * order its entries were added in. Names are unique.
 */
public record Tree(List<TreeEntry> entries) implements ScionObject {
    public static final Tree EMPTY = new Tree(List.of());

    public Tree {
        List<TreeEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(TreeEntry::name));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).name().equals(sorted.get(i - 1).name())) {
                throw new IllegalArgumentException("duplicate tree entry: " + sorted.get(i).name());
            }
        }
        entries = List.copyOf(sorted);
    }

    public Optional<TreeEntry> entry(String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst();
    }

    @Override
    public ObjectType type() {
        return ObjectType.TREE;
    }

    // One line per entry: "<file|dir> <id> <name>\n"
    @Override
    public byte[] content() {
        StringBuilder out = new StringBuilder();
        for (TreeEntry entry : entries) {
            out.append(entry.kind().name().toLowerCase()).append(' ')
                    .append(entry.id().hex()).append(' ')
                    .append(entry.name()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Tree parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<TreeEntry> entries = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            int end = text.indexOf('\n', position);
            if (end < 0) {
                throw new CorruptObjectException("tree entry not terminated");
            }
            String line = text.substring(position, end);
            String[] parts = line.split(" ", 3);
            if (parts.length != 3) {
                throw new CorruptObjectException("bad tree entry: " + line);
            }
            EntryKind kind = switch (parts[0]) {
                case "file" -> EntryKind.FILE;
                case "dir" -> EntryKind.DIR;
                default -> throw new CorruptObjectException("bad entry kind: " + parts[0]);
            };
            entries.add(new TreeEntry(parts[2], kind, ObjectId.of(parts[1])));
            position = end + 1;
        }
        return new Tree(entries);
    }
}
