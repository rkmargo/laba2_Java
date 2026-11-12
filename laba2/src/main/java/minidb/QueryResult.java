package minidb;

import java.util.Collections;
import java.util.List;

/**
 * Результат выполнения запроса: либо таблица значений (SELECT, SHOW, DESCRIBE),
 * либо текстовое сообщение (остальные команды). Для SELECT содержит план выполнения.
 */
public final class QueryResult {
    private final List<String> columns;
    private final List<List<Object>> rows;
    private final String message;
    private final String plan;

    private QueryResult(List<String> columns, List<List<Object>> rows, String message, String plan) {
        this.columns = columns;
        this.rows = rows;
        this.message = message;
        this.plan = plan;
    }

    public static QueryResult message(String text) {
        return new QueryResult(null, Collections.<List<Object>>emptyList(), text, null);
    }

    public static QueryResult table(List<String> columns, List<List<Object>> rows, String plan) {
        return new QueryResult(columns, rows, null, plan);
    }

    public boolean hasTable() { return columns != null; }
    public List<String> getColumns() { return columns; }
    public List<List<Object>> getRows() { return rows; }
    public String getMessage() { return message; }
    /** Описание способа выполнения (использован индекс или полный перебор). */
    public String getPlan() { return plan; }
}
