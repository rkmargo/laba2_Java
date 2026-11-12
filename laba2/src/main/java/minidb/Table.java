package minidb;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Таблица: схема, записи (хранятся по первичному ключу, что даёт O(1) поиск по ключу)
 * и вторичные индексы. Реализует {@link Iterable} (паттерн Iterator) для обхода записей.
 * Сама таблица не проверяет ограничения целостности - это делает {@link Database}.
 */
public class Table implements Serializable, Iterable<Row> {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final List<Column> columns;
    private final int pkPos;
    private final LinkedHashMap<Object, Row> rows = new LinkedHashMap<>();
    /** Определения индексов (имя -> столбец) сохраняются вместе с таблицей. */
    private final LinkedHashMap<String, String> indexDefs = new LinkedHashMap<>();
    /** Сами индексы хранятся в отдельных файлах. */
    private transient Map<String, Index> indexes = new LinkedHashMap<>();

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        int pk = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).isPrimaryKey()) {
                if (pk >= 0) throw new DbException("Only one PRIMARY KEY column is supported");
                pk = i;
            }
        }
        if (pk < 0) throw new DbException("Table must have a PRIMARY KEY column");
        this.pkPos = pk;
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        indexes = new LinkedHashMap<>();
    }

    public String getName() { return name; }
    public List<Column> getColumns() { return columns; }
    public int getPkPos() { return pkPos; }
    public int size() { return rows.size(); }

    /** Позиция столбца по имени. */
    public int columnIndex(String col) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(col)) return i;
        }
        throw new DbException("Unknown column '" + col + "' in table " + name);
    }

    @Override
    public Iterator<Row> iterator() {
        return Collections.unmodifiableCollection(rows.values()).iterator();
    }

    public Row getByPk(Object pk) { return rows.get(pk); }
    public boolean containsPk(Object pk) { return rows.containsKey(pk); }

    // ---- изменение данных ----

    /** Добавляет запись и обновляет все индексы. */
    public void insertRow(Row row) {
        Object pk = row.get(pkPos);
        rows.put(pk, row);
        for (Index idx : indexes.values()) idx.add(row.get(idx.getColumnPos()), pk);
    }

    /** Удаляет запись и обновляет все индексы. */
    public void deleteRow(Row row) {
        Object pk = row.get(pkPos);
        rows.remove(pk);
        for (Index idx : indexes.values()) idx.remove(row.get(idx.getColumnPos()), pk);
    }

    /** Заменяет запись с тем же первичным ключом (порядок записей сохраняется). */
    public void updateRow(Row oldRow, Row newRow) {
        Object pk = oldRow.get(pkPos);
        rows.put(pk, newRow);
        for (Index idx : indexes.values()) {
            Object o = oldRow.get(idx.getColumnPos());
            Object n = newRow.get(idx.getColumnPos());
            if (!Objects.equals(o, n)) {
                idx.remove(o, pk);
                idx.add(n, pk);
            }
        }
    }

    // ---- индексы ----

    public Map<String, String> getIndexDefs() { return Collections.unmodifiableMap(indexDefs); }
    public Index getIndex(String indexName) { return indexes.get(indexName); }

    /** Индекс по столбцу или {@code null}. */
    public Index findIndexByColumn(String col) {
        for (Index idx : indexes.values()) {
            if (idx.getColumn().equals(col)) return idx;
        }
        return null;
    }

    /** Строит новый индекс по существующим данным. */
    public Index createIndex(String indexName, String col) {
        int pos = columnIndex(col);
        if (indexDefs.containsKey(indexName)) throw new DbException("Index already exists: " + indexName);
        Index idx = buildIndex(indexName, col, pos);
        indexDefs.put(indexName, col);
        indexes.put(indexName, idx);
        return idx;
    }

    private Index buildIndex(String indexName, String col, int pos) {
        Index idx = new Index(indexName, col, pos);
        for (Map.Entry<Object, Row> e : rows.entrySet()) idx.add(e.getValue().get(pos), e.getKey());
        return idx;
    }

    public void dropIndex(String indexName) {
        indexDefs.remove(indexName);
        indexes.remove(indexName);
    }

    /**
     * Подключает индекс, загруженный с диска. Если он не согласован с данными
     * (например, после аварийного завершения), индекс перестраивается.
     */
    public void attachIndex(String indexName, Index loaded) {
        String col = indexDefs.get(indexName);
        int pos = columnIndex(col);
        int expected = 0;
        for (Row r : rows.values()) if (r.get(pos) != null) expected++;
        if (loaded == null || loaded.size() != expected) loaded = buildIndex(indexName, col, pos);
        indexes.put(indexName, loaded);
    }

    public Iterable<Index> indexes() { return indexes.values(); }
}
