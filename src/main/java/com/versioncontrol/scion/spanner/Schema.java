package com.versioncontrol.scion.spanner;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.versioncontrol.scion.spanner.SpannerApi.*;

/**
 * The tables Scion Lite keeps in Spanner. Each has a one-column primary key, the first column. In Spanner DDL:
 *
 * <pre>
 * CREATE TABLE refs       (name STRING(MAX) NOT NULL, changeset STRING(64) NOT NULL) PRIMARY KEY (name);
 * CREATE TABLE changesets (id STRING(64) NOT NULL, body BYTES(MAX) NOT NULL)          PRIMARY KEY (id);
 * CREATE TABLE root_tree  (changeset STRING(64) NOT NULL, root STRING(64) NOT NULL)   PRIMARY KEY (changeset);
 * </pre>
 *
 * The post says refs and derived data are in Spanner; the tables, their columns and keeping changesets in Spanner
 * are [Lite assumptions].
 */
public final class Schema {
    public enum ColumnType {STRING, BYTES}

    public record Column(String name, ColumnType type) {
    }

    /** Every column is NOT NULL. The first column is the primary key. */
    public record Table(String name, List<Column> columns) {
        public String key() {
            return columns.get(0).name();
        }

        public List<String> columnNames() {
            return columns.stream().map(Column::name).toList();
        }

        public int indexOf(String column) {
            return columnNames().indexOf(column);
        }

        public StructType rowType(List<String> requested) {
            return new StructType(requested.stream()
                    .map(c -> new Field(c, new Type(columns.get(indexOf(c)).type().name()))).toList());
        }
    }

    public static final List<String> REFS_COLUMNS = List.of("name", "changeset");
    public static final List<String> CHANGESETS_COLUMNS = List.of("id", "body");
    public static final List<String> ROOT_TREE_COLUMNS = List.of("changeset", "root");

    private static final Map<String, Table> TABLES = Map.of(
            REFS, new Table(REFS, List.of(new Column("name", ColumnType.STRING),
                    new Column("changeset", ColumnType.STRING))),
            CHANGESETS, new Table(CHANGESETS, List.of(new Column("id", ColumnType.STRING),
                    new Column("body", ColumnType.BYTES))),
            ROOT_TREE, new Table(ROOT_TREE, List.of(new Column("changeset", ColumnType.STRING),
                    new Column("root", ColumnType.STRING))));

    public static Optional<Table> table(String name) {
        return Optional.ofNullable(TABLES.get(name));
    }

    private Schema() {
    }
}
