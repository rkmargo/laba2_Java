package minidb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ядро СУБД (фасад и репозиторий таблиц): хранит таблицы, проверяет ограничения целостности
 * (уникальность первичного ключа, внешние ключи, типы), выбирает план выполнения запроса
 * (поиск по ключу, по индексу или полный перебор) и обеспечивает долговечность через
 * журнал предзаписи и контрольные точки.
 * <p>
 * Любой изменяющий запрос сначала полностью проверяется и только потом применяется, поэтому
 * при ошибке данные остаются без изменений.
 */
public class Database {
    private static final int CHECKPOINT_EVERY = 1000;

    private final Map<String, Table> tables = new LinkedHashMap<>();
    private StorageManager storage; // null - база только в памяти
    private boolean indexesEnabled = true;
    private int walCount;

    /** Создаёт базу данных только в памяти (для тестов и бенчмарков). */
    public Database() { }

    /**
     * Открывает базу в каталоге: загружает таблицы и индексы, применяет журнал
     * (восстановление после аварии) и делает контрольную точку.
     *
     * @param syncWal сбрасывать ли журнал на диск после каждого запроса (надёжнее, но медленнее)
     */
    public static Database open(Path dir, boolean syncWal) {
        Database db = new Database();
        db.storage = new StorageManager(dir, syncWal);
        db.tables.putAll(db.storage.loadTables());
        List<String> log = db.storage.readWal();
        boolean dirtyWal = db.storage.walSize() > 0;
        db.storage.openWal();
        for (String sql : log) {
            try {
                Parser.parse(sql).execute(db); // без повторной записи в журнал
            } catch (DbException e) {
                // запись уже была применена до аварии (контрольная точка не успела очистить журнал)
            }
        }
        if (dirtyWal) db.checkpoint();
        return db;
    }

    /** Включает/выключает использование вторичных индексов (нужно для сравнения в бенчмарке). */
    public void setIndexesEnabled(boolean enabled) { this.indexesEnabled = enabled; }

    /** Разбирает и выполняет запрос; изменяющие запросы попадают в журнал. */
    public synchronized QueryResult execute(String sql) {
        Statement st = Parser.parse(sql);
        QueryResult r = st.execute(this);
        if (storage != null && st.isModifying()) {
            storage.logStatement(sql);
            if (++walCount >= CHECKPOINT_EVERY) checkpoint();
        }
        return r;
    }

    /** Записывает все данные на диск и очищает журнал. */
    public synchronized void checkpoint() {
        if (storage == null) return;
        storage.saveAll(tables);
        walCount = 0;
    }

    /** Контрольная точка и закрытие файлов. */
    public synchronized void close() {
        if (storage == null) return;
        checkpoint();
        try {
            storage.close();
        } catch (IOException e) {
            throw new DbException("Cannot close storage: " + e.getMessage(), e);
        }
        storage = null;
    }

    public Table getTable(String name) { return table(name); }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw new DbException("Table does not exist: " + name);
        return t;
    }

    // ---- DDL ----

    QueryResult createTable(String name, List<Column> cols) {
        if (tables.containsKey(name)) throw new DbException("Table already exists: " + name);
        Set<String> seen = new HashSet<>();
        for (Column c : cols) {
            if (!seen.add(c.getName())) throw new DbException("Duplicate column: " + c.getName());
            if (c.isForeignKey()) {
                Table ref = tables.get(c.getRefTable());
                if (ref == null) throw new DbException("Referenced table does not exist: " + c.getRefTable());
                int rp = ref.columnIndex(c.getRefColumn());
                if (rp != ref.getPkPos()) {
                    throw new DbException("Foreign key must reference the PRIMARY KEY of " + ref.getName());
                }
                if (ref.getColumns().get(rp).getType() != c.getType()) {
                    throw new DbException("Foreign key type mismatch for column " + c.getName());
                }
            }
        }
        tables.put(name, new Table(name, cols));
        return QueryResult.message("Table created: " + name);
    }

    QueryResult dropTable(String name) {
        table(name);
        for (Table other : tables.values()) {
            if (other.getName().equals(name)) continue;
            for (Column c : other.getColumns()) {
                if (name.equals(c.getRefTable())) {
                    throw new DbException("Cannot drop " + name + ": referenced by " + other.getName() + "." + c.getName());
                }
            }
        }
        tables.remove(name);
        return QueryResult.message("Table dropped: " + name);
    }

    QueryResult createIndex(String indexName, String tableName, String column) {
        for (Table t : tables.values()) {
            if (t.getIndexDefs().containsKey(indexName)) throw new DbException("Index already exists: " + indexName);
        }
        Table t = table(tableName);
        t.createIndex(indexName, column);
        return QueryResult.message("Index created: " + indexName + " on " + tableName + "(" + column + ")");
    }

    QueryResult dropIndex(String indexName) {
        for (Table t : tables.values()) {
            if (t.getIndexDefs().containsKey(indexName)) {
                t.dropIndex(indexName);
                return QueryResult.message("Index dropped: " + indexName);
            }
        }
        throw new DbException("Index does not exist: " + indexName);
    }

    // ---- DML ----

    QueryResult insert(String tableName, List<String> colNames, List<List<Object>> valueRows) {
        Table t = table(tableName);
        List<Column> cols = t.getColumns();
        int[] target;
        if (colNames == null) {
            target = new int[cols.size()];
            for (int i = 0; i < target.length; i++) target[i] = i;
        } else {
            target = new int[colNames.size()];
            Set<Integer> used = new HashSet<>();
            for (int i = 0; i < target.length; i++) {
                target[i] = t.columnIndex(colNames.get(i));
                if (!used.add(target[i])) throw new DbException("Column listed twice: " + colNames.get(i));
            }
        }
        List<Row> newRows = new ArrayList<>(valueRows.size());
        Set<Object> batchKeys = new HashSet<>();
        for (List<Object> vals : valueRows) {
            if (vals.size() != target.length) {
                throw new DbException("Expected " + target.length + " values, got " + vals.size());
            }
            Object[] arr = new Object[cols.size()];
            for (int i = 0; i < target.length; i++) {
                arr[target[i]] = coerceFor(cols.get(target[i]), vals.get(i));
            }
            Object pk = arr[t.getPkPos()];
            if (pk == null) throw new DbException("PRIMARY KEY value must not be NULL");
            if (t.containsPk(pk) || !batchKeys.add(pk)) {
                throw new DbException("Duplicate PRIMARY KEY value: " + pk);
            }
            Row row = new Row(arr);
            checkForeignKeys(t, row);
            newRows.add(row);
        }
        for (Row r : newRows) t.insertRow(r);
        return QueryResult.message(newRows.size() + " row(s) inserted");
    }

    QueryResult select(String tableName, List<String> colNames, Condition where,
                       String orderBy, boolean desc, int limit) {
        Table t = table(tableName);
        int[] pos;
        List<String> names;
        if (colNames == null) {
            pos = new int[t.getColumns().size()];
            names = new ArrayList<>();
            for (int i = 0; i < pos.length; i++) {
                pos[i] = i;
                names.add(t.getColumns().get(i).getName());
            }
        } else {
            pos = new int[colNames.size()];
            names = colNames;
            for (int i = 0; i < pos.length; i++) pos[i] = t.columnIndex(colNames.get(i));
        }
        StringBuilder plan = new StringBuilder();
        List<Row> found = findRows(t, where, plan);
        if (orderBy != null) {
            final int op = t.columnIndex(orderBy);
            Comparator<Row> cmp = new Comparator<Row>() {
                @Override public int compare(Row a, Row b) { return compareNullable(a.get(op), b.get(op)); }
            };
            Collections.sort(found, desc ? Collections.reverseOrder(cmp) : cmp);
        }
        if (limit >= 0 && found.size() > limit) found = found.subList(0, limit);
        List<List<Object>> out = new ArrayList<>(found.size());
        for (Row r : found) {
            List<Object> line = new ArrayList<>(pos.length);
            for (int p : pos) line.add(r.get(p));
            out.add(line);
        }
        return QueryResult.table(names, out, plan.toString());
    }

    QueryResult update(String tableName, Map<String, Object> sets, Condition where) {
        Table t = table(tableName);
        int n = sets.size();
        int[] pos = new int[n];
        Object[] vals = new Object[n];
        boolean pkAssigned = false;
        int i = 0;
        for (Map.Entry<String, Object> e : sets.entrySet()) {
            pos[i] = t.columnIndex(e.getKey());
            vals[i] = coerceFor(t.getColumns().get(pos[i]), e.getValue());
            if (pos[i] == t.getPkPos()) {
                if (vals[i] == null) throw new DbException("PRIMARY KEY value must not be NULL");
                pkAssigned = true;
            }
            i++;
        }
        List<Row> targets = findRows(t, where, new StringBuilder());
        List<Row> updated = new ArrayList<>(targets.size());
        for (Row old : targets) {
            Object[] a = old.copyValues();
            for (int k = 0; k < n; k++) a[pos[k]] = vals[k];
            Row nr = new Row(a);
            checkForeignKeys(t, nr);
            updated.add(nr);
        }
        if (pkAssigned && targets.size() > 1) {
            throw new DbException("Cannot assign the same PRIMARY KEY value to several rows");
        }
        for (int k = 0; k < targets.size(); k++) {
            Object oldPk = targets.get(k).get(t.getPkPos());
            Object newPk = updated.get(k).get(t.getPkPos());
            if (!oldPk.equals(newPk)) {
                if (t.containsPk(newPk)) throw new DbException("Duplicate PRIMARY KEY value: " + newPk);
                ensureNotReferenced(t, Arrays.asList(targets.get(k)));
            }
        }
        for (int k = 0; k < targets.size(); k++) {
            Row old = targets.get(k), nr = updated.get(k);
            if (old.get(t.getPkPos()).equals(nr.get(t.getPkPos()))) {
                t.updateRow(old, nr);
            } else {
                t.deleteRow(old);
                t.insertRow(nr);
            }
        }
        return QueryResult.message(targets.size() + " row(s) updated");
    }

    QueryResult delete(String tableName, Condition where) {
        Table t = table(tableName);
        List<Row> targets = findRows(t, where, new StringBuilder());
        ensureNotReferenced(t, targets);
        for (Row r : targets) t.deleteRow(r);
        return QueryResult.message(targets.size() + " row(s) deleted");
    }

    QueryResult showTables() {
        List<List<Object>> rows = new ArrayList<>();
        for (Table t : tables.values()) {
            StringBuilder cols = new StringBuilder();
            for (Column c : t.getColumns()) {
                if (cols.length() > 0) cols.append(", ");
                cols.append(c.getName());
            }
            rows.add(Arrays.<Object>asList(t.getName(), (long) t.size(), cols.toString(),
                    String.join(", ", t.getIndexDefs().keySet())));
        }
        return QueryResult.table(Arrays.asList("table", "rows", "columns", "indexes"), rows, null);
    }

    QueryResult describe(String tableName) {
        Table t = table(tableName);
        List<List<Object>> rows = new ArrayList<>();
        for (Column c : t.getColumns()) {
            String key = c.isPrimaryKey() ? "PRIMARY KEY"
                    : c.isForeignKey() ? "FOREIGN KEY -> " + c.getRefTable() + "(" + c.getRefColumn() + ")" : "";
            Index idx = t.findIndexByColumn(c.getName());
            rows.add(Arrays.<Object>asList(c.getName(), c.getType().name(), key, idx == null ? "" : idx.getName()));
        }
        return QueryResult.table(Arrays.asList("column", "type", "key", "index"), rows, null);
    }

    QueryResult checkpointCommand() {
        if (storage == null) return QueryResult.message("In-memory database: nothing to save");
        checkpoint();
        return QueryResult.message("Checkpoint done");
    }

    // ---- планировщик ----

    /**
     * Находит записи, удовлетворяющие условию. Порядок выбора способа доступа:
     * 1) равенство по первичному ключу - O(1); 2) вторичный индекс по столбцу из условия
     * (равенство или диапазон) - O(log n + k); 3) полный перебор. Условие всегда
     * проверяется на найденных кандидатах повторно, поэтому результат корректен при любом плане.
     */
    private List<Row> findRows(Table t, Condition where, StringBuilder plan) {
        if (where == null) {
            plan.append("full scan of ").append(t.getName()).append(" (").append(t.size()).append(" rows)");
            List<Row> all = new ArrayList<>(t.size());
            for (Row r : t) all.add(r);
            return all;
        }
        where.bind(t);
        List<Condition> conj = new ArrayList<>();
        where.conjuncts(conj);
        List<Row> candidates = null;

        for (Condition c : conj) {
            if (!(c instanceof Condition.Comparison)) continue;
            Condition.Comparison cmp = (Condition.Comparison) c;
            if (cmp.getOp() == Condition.Op.EQ && cmp.getPos() == t.getPkPos() && cmp.getValue() != null) {
                candidates = new ArrayList<>(1);
                Row r = t.getByPk(cmp.getValue());
                if (r != null) candidates.add(r);
                plan.append("primary key lookup on ").append(cmp.getColumn());
                break;
            }
        }

        if (candidates == null && indexesEnabled) {
            for (Condition c : conj) {
                if (!(c instanceof Condition.Comparison)) continue;
                Condition.Comparison cmp = (Condition.Comparison) c;
                if (cmp.getOp() == Condition.Op.NE || cmp.getValue() == null) continue;
                Index idx = t.findIndexByColumn(cmp.getColumn());
                if (idx == null) continue;
                List<Object> pks;
                if (cmp.getOp() == Condition.Op.EQ) {
                    pks = idx.find(cmp.getValue());
                    plan.append("index lookup using ").append(idx.getName()).append(" on ").append(cmp.getColumn());
                } else {
                    Object lo = null, hi = null;
                    boolean loInc = false, hiInc = false;
                    for (Condition d : conj) {
                        if (!(d instanceof Condition.Comparison)) continue;
                        Condition.Comparison k = (Condition.Comparison) d;
                        if (!k.getColumn().equals(cmp.getColumn()) || k.getValue() == null) continue;
                        if ((k.getOp() == Condition.Op.GT || k.getOp() == Condition.Op.GE) && lo == null) {
                            lo = k.getValue();
                            loInc = k.getOp() == Condition.Op.GE;
                        } else if ((k.getOp() == Condition.Op.LT || k.getOp() == Condition.Op.LE) && hi == null) {
                            hi = k.getValue();
                            hiInc = k.getOp() == Condition.Op.LE;
                        }
                    }
                    pks = idx.range(lo, loInc, hi, hiInc);
                    plan.append("index range scan using ").append(idx.getName()).append(" on ").append(cmp.getColumn());
                }
                candidates = new ArrayList<>(pks.size());
                for (Object pk : pks) {
                    Row r = t.getByPk(pk);
                    if (r != null) candidates.add(r);
                }
                break;
            }
        }

        List<Row> result = new ArrayList<>();
        if (candidates == null) {
            plan.append("full scan of ").append(t.getName()).append(" (").append(t.size()).append(" rows)");
            for (Row r : t) if (where.test(r)) result.add(r);
        } else {
            for (Row r : candidates) if (where.test(r)) result.add(r);
        }
        return result;
    }

    // ---- ограничения целостности ----

    private static Object coerceFor(Column c, Object v) {
        try {
            return c.getType().coerce(v);
        } catch (DbException e) {
            throw new DbException("Column '" + c.getName() + "': " + e.getMessage());
        }
    }

    private void checkForeignKeys(Table t, Row row) {
        List<Column> cols = t.getColumns();
        for (int i = 0; i < cols.size(); i++) {
            Column c = cols.get(i);
            Object v = row.get(i);
            if (c.isForeignKey() && v != null) {
                Table parent = tables.get(c.getRefTable());
                if (parent == null || !parent.containsPk(v)) {
                    throw new DbException("Foreign key violation: " + c.getName() + " = " + v
                            + " not found in " + c.getRefTable() + "(" + c.getRefColumn() + ")");
                }
            }
        }
    }

    /** Запрещает удаление/изменение ключа записей, на которые ссылаются другие таблицы (RESTRICT). */
    private void ensureNotReferenced(Table parent, List<Row> victims) {
        if (victims.isEmpty()) return;
        for (Table child : tables.values()) {
            List<Column> cols = child.getColumns();
            for (int i = 0; i < cols.size(); i++) {
                Column c = cols.get(i);
                if (!c.isForeignKey() || !c.getRefTable().equals(parent.getName())) continue;
                Index idx = child.findIndexByColumn(c.getName());
                Set<Object> used = null;
                if (idx == null) {
                    used = new HashSet<>();
                    for (Row r : child) if (r.get(i) != null) used.add(r.get(i));
                }
                for (Row v : victims) {
                    Object key = v.get(parent.getPkPos());
                    boolean referenced = idx != null ? !idx.find(key).isEmpty() : used.contains(key);
                    if (referenced) {
                        throw new DbException("Cannot delete or change key " + key + " in " + parent.getName()
                                + ": referenced by " + child.getName() + "." + c.getName());
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static int compareNullable(Object a, Object b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        return ((Comparable<Object>) a).compareTo(b);
    }
}
