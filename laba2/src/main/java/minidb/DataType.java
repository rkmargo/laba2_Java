package minidb;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Поддерживаемые типы данных столбцов и правила приведения значений к ним.
 * <ul>
 * <li>INT - {@link Long}</li>
 * <li>DOUBLE - {@link Double}</li>
 * <li>STRING - {@link String}</li>
 * <li>DATE - {@link LocalDate} (формат YYYY-MM-DD)</li>
 * <li>BOOLEAN - {@link Boolean}</li>
 * </ul>
 */
public enum DataType {
    INT, DOUBLE, STRING, DATE, BOOLEAN;

    /** Разбирает имя типа из SQL (допускаются синонимы INTEGER, VARCHAR, BOOL и т.д.). */
    public static DataType fromSql(String name) {
        switch (name.toUpperCase()) {
            case "INT": case "INTEGER": case "BIGINT": return INT;
            case "DOUBLE": case "FLOAT": case "REAL": return DOUBLE;
            case "STRING": case "VARCHAR": case "TEXT": return STRING;
            case "DATE": return DATE;
            case "BOOLEAN": case "BOOL": return BOOLEAN;
            default: throw new DbException("Unknown data type: " + name);
        }
    }

    /**
     * Приводит значение (литерал из запроса) к типу столбца.
     *
     * @throws DbException если преобразование невозможно
     */
    public Object coerce(Object v) {
        if (v == null) {
            return null;
        }
        switch (this) {
            case INT:
                if (v instanceof Long) return v;
                break;
            case DOUBLE:
                if (v instanceof Double) return v;
                if (v instanceof Long) return ((Long) v).doubleValue();
                break;
            case STRING:
                if (v instanceof String) return v;
                break;
            case DATE:
                if (v instanceof LocalDate) return v;
                if (v instanceof String) {
                    try {
                        return LocalDate.parse((String) v);
                    } catch (DateTimeParseException e) {
                        throw new DbException("Invalid date '" + v + "' (expected YYYY-MM-DD)");
                    }
                }
                break;
            case BOOLEAN:
                if (v instanceof Boolean) return v;
                break;
            default:
                break;
        }
        throw new DbException("Cannot use value " + v + " as " + this);
    }
}
