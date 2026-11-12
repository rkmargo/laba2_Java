package minidb;

/**
 * Разобранный запрос (паттерн Command). Парсер создаёт объекты этого типа,
 * {@link Database} их исполняет.
 */
public interface Statement {

    /** Выполняет запрос над базой данных. */
    QueryResult execute(Database db);

    /** {@code true}, если запрос изменяет данные или схему и должен попасть в журнал. */
    boolean isModifying();
}
