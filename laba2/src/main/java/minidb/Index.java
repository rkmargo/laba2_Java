package minidb;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Вторичный индекс по одному столбцу. Реализован на упорядоченном дереве (красно-чёрное дерево,
 * {@link TreeMap}): ключ - значение столбца, значение - список первичных ключей записей.
 * Поддерживает точный поиск и поиск по диапазону за O(log n + k). Значения NULL не индексируются.
 * Индекс сериализуется на диск отдельным файлом.
 */
public class Index implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String column;
    private final int columnPos;
    private final TreeMap<Object, List<Object>> tree = new TreeMap<>();
    private int entries;

    public Index(String name, String column, int columnPos) {
        this.name = name;
        this.column = column;
        this.columnPos = columnPos;
    }

    public String getName() { return name; }
    public String getColumn() { return column; }
    public int getColumnPos() { return columnPos; }
    /** Количество проиндексированных (не NULL) записей. */
    public int size() { return entries; }

    /** Добавляет запись в индекс. */
    public void add(Object key, Object pk) {
        if (key == null) return;
        List<Object> list = tree.get(key);
        if (list == null) {
            list = new ArrayList<>(1);
            tree.put(key, list);
        }
        list.add(pk);
        entries++;
    }

    /** Удаляет запись из индекса. */
    public void remove(Object key, Object pk) {
        if (key == null) return;
        List<Object> list = tree.get(key);
        if (list != null && list.remove(pk)) {
            entries--;
            if (list.isEmpty()) tree.remove(key);
        }
    }

    /** Первичные ключи записей со значением, равным {@code key}. */
    public List<Object> find(Object key) {
        List<Object> list = tree.get(key);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * Первичные ключи записей из диапазона. Любая из границ может быть {@code null} (не ограничена).
     */
    @SuppressWarnings("unchecked")
    public List<Object> range(Object lo, boolean loInc, Object hi, boolean hiInc) {
        if (lo != null && hi != null) {
            int c = ((Comparable<Object>) lo).compareTo(hi);
            if (c > 0 || (c == 0 && !(loInc && hiInc))) return Collections.emptyList();
        }
        NavigableMap<Object, List<Object>> view;
        if (lo != null && hi != null) view = tree.subMap(lo, loInc, hi, hiInc);
        else if (lo != null) view = tree.tailMap(lo, loInc);
        else if (hi != null) view = tree.headMap(hi, hiInc);
        else view = tree;
        List<Object> result = new ArrayList<>();
        for (List<Object> l : view.values()) result.addAll(l);
        return result;
    }
}
