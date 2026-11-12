package minidb;

import java.io.Serializable;

/**
 * Описание столбца таблицы: имя, тип, признак первичного ключа и (необязательно) внешний ключ.
 */
public final class Column implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final DataType type;
    private final boolean primaryKey;
    private final String refTable;
    private final String refColumn;

    public Column(String name, DataType type, boolean primaryKey, String refTable, String refColumn) {
        this.name = name;
        this.type = type;
        this.primaryKey = primaryKey;
        this.refTable = refTable;
        this.refColumn = refColumn;
    }

    public String getName() { return name; }
    public DataType getType() { return type; }
    public boolean isPrimaryKey() { return primaryKey; }
    /** Имя родительской таблицы или {@code null}, если внешнего ключа нет. */
    public String getRefTable() { return refTable; }
    public String getRefColumn() { return refColumn; }
    public boolean isForeignKey() { return refTable != null; }
}
