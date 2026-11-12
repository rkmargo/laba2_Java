package minidb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Реализации {@link Statement} для всех поддерживаемых команд. Классы тонкие:
 * вся логика находится в {@link Database}.
 */
public final class Statements {
    private Statements() { }

    /** CREATE TABLE. */
    public static final class CreateTable implements Statement {
        private final String table;
        private final List<Column> columns;
        public CreateTable(String table, List<Column> columns) { this.table = table; this.columns = columns; }
        @Override public QueryResult execute(Database db) { return db.createTable(table, columns); }
        @Override public boolean isModifying() { return true; }
    }

    /** DROP TABLE. */
    public static final class DropTable implements Statement {
        private final String table;
        public DropTable(String table) { this.table = table; }
        @Override public QueryResult execute(Database db) { return db.dropTable(table); }
        @Override public boolean isModifying() { return true; }
    }

    /** CREATE INDEX. */
    public static final class CreateIndex implements Statement {
        private final String name, table, column;
        public CreateIndex(String name, String table, String column) {
            this.name = name; this.table = table; this.column = column;
        }
        @Override public QueryResult execute(Database db) { return db.createIndex(name, table, column); }
        @Override public boolean isModifying() { return true; }
    }

    /** DROP INDEX. */
    public static final class DropIndex implements Statement {
        private final String name;
        public DropIndex(String name) { this.name = name; }
        @Override public QueryResult execute(Database db) { return db.dropIndex(name); }
        @Override public boolean isModifying() { return true; }
    }

    /** INSERT INTO ... VALUES (...), (...). */
    public static final class Insert implements Statement {
        private final String table;
        private final List<String> columns; // null = все столбцы по порядку
        private final List<List<Object>> rows;
        public Insert(String table, List<String> columns, List<List<Object>> rows) {
            this.table = table; this.columns = columns; this.rows = rows;
        }
        @Override public QueryResult execute(Database db) { return db.insert(table, columns, rows); }
        @Override public boolean isModifying() { return true; }
    }

    /** SELECT. */
    public static final class Select implements Statement {
        private final String table;
        private final List<String> columns; // null = *
        private final Condition where;
        private final String orderBy;
        private final boolean desc;
        private final int limit;
        public Select(String table, List<String> columns, Condition where, String orderBy, boolean desc, int limit) {
            this.table = table; this.columns = columns; this.where = where;
            this.orderBy = orderBy; this.desc = desc; this.limit = limit;
        }
        @Override public QueryResult execute(Database db) {
            return db.select(table, columns, where, orderBy, desc, limit);
        }
        @Override public boolean isModifying() { return false; }
    }

    /** UPDATE ... SET ... [WHERE ...]. */
    public static final class Update implements Statement {
        private final String table;
        private final Map<String, Object> sets;
        private final Condition where;
        public Update(String table, Map<String, Object> sets, Condition where) {
            this.table = table; this.sets = sets; this.where = where;
        }
        @Override public QueryResult execute(Database db) { return db.update(table, sets, where); }
        @Override public boolean isModifying() { return true; }
    }

    /** DELETE FROM ... [WHERE ...]. */
    public static final class Delete implements Statement {
        private final String table;
        private final Condition where;
        public Delete(String table, Condition where) { this.table = table; this.where = where; }
        @Override public QueryResult execute(Database db) { return db.delete(table, where); }
        @Override public boolean isModifying() { return true; }
    }

    /** SHOW TABLES. */
    public static final class ShowTables implements Statement {
        @Override public QueryResult execute(Database db) { return db.showTables(); }
        @Override public boolean isModifying() { return false; }
    }

    /** DESCRIBE table. */
    public static final class Describe implements Statement {
        private final String table;
        public Describe(String table) { this.table = table; }
        @Override public QueryResult execute(Database db) { return db.describe(table); }
        @Override public boolean isModifying() { return false; }
    }

    /** CHECKPOINT - принудительная запись всех данных на диск. */
    public static final class Checkpoint implements Statement {
        @Override public QueryResult execute(Database db) { return db.checkpointCommand(); }
        @Override public boolean isModifying() { return false; }
    }

    static List<String> copy(List<String> l) { return l == null ? null : new ArrayList<>(l); }
}
