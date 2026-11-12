package minidb;

import java.io.Serializable;

/**
 * Запись таблицы. Объект неизменяемый: при обновлении создаётся новая запись.
 */
public final class Row implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Object[] values;

    public Row(Object[] values) {
        this.values = values;
    }

    public Object get(int i) { return values[i]; }
    public int size() { return values.length; }

    /** Копия значений для построения изменённой записи. */
    public Object[] copyValues() { return values.clone(); }
}
