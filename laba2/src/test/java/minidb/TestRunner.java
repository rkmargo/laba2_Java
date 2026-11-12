package minidb;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Простой набор тестов без внешних зависимостей. Запуск: {@code java -cp out minidb.TestRunner}.
 */
public final class TestRunner {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        test("insert / select / duplicate PK", TestRunner::basics);
        test("partial insert and NULL", TestRunner::partialInsert);
        test("update / delete with conditions", TestRunner::updateDelete);
        test("types and dates", TestRunner::types);
        test("foreign keys", TestRunner::foreignKeys);
        test("atomic multi-row insert", TestRunner::atomicInsert);
        test("index gives same result as full scan", TestRunner::indexConsistency);
        test("persistence after clean shutdown", TestRunner::persistence);
        test("recovery from WAL after crash", TestRunner::crashRecovery);
        test("torn WAL tail is ignored", TestRunner::tornWal);
        test("parser errors", TestRunner::parserErrors);
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    interface Body { void run() throws Exception; }

    private static void test(String name, Body b) {
        try {
            b.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + ": " + t);
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void fails(Database db, String sql, String fragment) {
        try {
            db.execute(sql);
        } catch (DbException e) {
            check(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' lacks '" + fragment + "'");
            return;
        }
        throw new AssertionError("expected error for: " + sql);
    }

    private static int count(Database db, String sql) { return db.execute(sql).getRows().size(); }

    private static Database cars() {
        Database db = new Database();
        db.execute("CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price DOUBLE, new BOOLEAN)");
        db.execute("INSERT INTO cars VALUES (1, 'Toyota', 25000.0, TRUE)");
        db.execute("INSERT INTO cars (id, brand) VALUES (2, 'BMW')");
        db.execute("INSERT INTO cars VALUES (3, 'Audi', 18000, FALSE), (4, 'Toyota', 31000.5, FALSE)");
        return db;
    }

    private static void basics() {
        Database db = cars();
        check(count(db, "SELECT * FROM cars;") == 4, "4 rows");
        fails(db, "INSERT INTO cars VALUES (1, 'Dup', 1.0, TRUE)", "Duplicate PRIMARY KEY");
        check(count(db, "SELECT brand, price FROM cars WHERE price > 20000") == 2, "price > 20000");
        check(count(db, "SELECT * FROM cars ORDER BY price DESC LIMIT 2") == 2, "limit");
    }

    private static void partialInsert() {
        Database db = cars();
        check(count(db, "SELECT * FROM cars WHERE price IS NULL") == 1, "IS NULL");
        check(count(db, "SELECT * FROM cars WHERE price IS NOT NULL") == 3, "IS NOT NULL");
        fails(db, "INSERT INTO cars (brand) VALUES ('X')", "PRIMARY KEY");
    }

    private static void updateDelete() {
        Database db = cars();
        db.execute("UPDATE cars SET price = 30000 WHERE id = 2");
        check(db.execute("SELECT price FROM cars WHERE id = 2").getRows().get(0).get(0).equals(30000.0), "updated");
        check(db.execute("UPDATE cars SET new = TRUE WHERE brand = 'Toyota' OR price < 20000")
                .getMessage().startsWith("3 "), "or-condition");
        db.execute("DELETE FROM cars WHERE brand = 'Toyota'");
        check(count(db, "SELECT * FROM cars") == 2, "after delete");
        db.execute("UPDATE cars SET id = 10 WHERE id = 2");
        check(count(db, "SELECT * FROM cars WHERE id = 10") == 1, "pk changed");
        fails(db, "UPDATE cars SET id = 3 WHERE id = 10", "Duplicate PRIMARY KEY");
        fails(db, "UPDATE cars SET id = 5", "several rows");
    }

    private static void types() {
        Database db = new Database();
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, d DATE, s VARCHAR(20), f FLOAT)");
        db.execute("INSERT INTO t VALUES (1, '2026-11-15', 'it''s', 1)");
        check(count(db, "SELECT * FROM t WHERE d >= '2026-01-01' AND d < '2027-01-01'") == 1, "date compare");
        check(db.execute("SELECT s FROM t").getRows().get(0).get(0).equals("it's"), "escaped quote");
        fails(db, "INSERT INTO t VALUES (2, 'not-a-date', 'x', 1.0)", "Invalid date");
        fails(db, "INSERT INTO t VALUES ('a', '2026-01-01', 'x', 1.0)", "Column 'id'");
    }

    private static void foreignKeys() {
        Database db = new Database();
        db.execute("CREATE TABLE owners (id INT PRIMARY KEY, name STRING)");
        db.execute("CREATE TABLE cars (id INT PRIMARY KEY, owner_id INT REFERENCES owners(id))");
        db.execute("INSERT INTO owners VALUES (1, 'Ann')");
        db.execute("INSERT INTO cars VALUES (1, 1)");
        fails(db, "INSERT INTO cars VALUES (2, 99)", "Foreign key violation");
        fails(db, "DELETE FROM owners WHERE id = 1", "referenced by");
        fails(db, "DROP TABLE owners", "referenced by");
        db.execute("DELETE FROM cars WHERE id = 1");
        db.execute("DELETE FROM owners WHERE id = 1");
    }

    private static void atomicInsert() {
        Database db = cars();
        fails(db, "INSERT INTO cars VALUES (7, 'A', 1.0, TRUE), (1, 'B', 2.0, TRUE)", "Duplicate");
        check(count(db, "SELECT * FROM cars WHERE id = 7") == 0, "no partial insert");
        fails(db, "INSERT INTO cars VALUES (8, 'A', 1.0, TRUE), (8, 'B', 2.0, TRUE)", "Duplicate");
    }

    private static void indexConsistency() {
        Database db = new Database();
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT, s STRING)");
        StringBuilder sb = new StringBuilder("INSERT INTO t VALUES ");
        for (int i = 0; i < 2000; i++) sb.append(i == 0 ? "" : ",").append("(").append(i).append(",").append(i % 97).append(",'s").append(i % 13).append("')");
        db.execute(sb.toString());
        String[] queries = {
            "SELECT id FROM t WHERE v = 5", "SELECT id FROM t WHERE v > 90", "SELECT id FROM t WHERE v >= 90 AND v < 93",
            "SELECT id FROM t WHERE v < 3 AND s = 's4'", "SELECT id FROM t WHERE s = 's7'", "SELECT id FROM t WHERE v > 50 AND v < 10"
        };
        int[] expected = new int[queries.length];
        for (int i = 0; i < queries.length; i++) {
            QueryResult r = db.execute(queries[i]);
            check(r.getPlan().startsWith("full scan"), "no index yet");
            expected[i] = r.getRows().size();
        }
        db.execute("CREATE INDEX idx_v ON t (v)");
        db.execute("CREATE INDEX idx_s ON t (s)");
        for (int i = 0; i < queries.length; i++) {
            QueryResult r = db.execute(queries[i]);
            check(r.getPlan().startsWith("index"), "index used: " + r.getPlan());
            check(r.getRows().size() == expected[i], "same rows for " + queries[i]);
        }
        db.execute("UPDATE t SET v = 1000 WHERE v = 5");
        db.execute("DELETE FROM t WHERE v = 6");
        check(count(db, "SELECT id FROM t WHERE v = 5") == 0, "index updated after UPDATE");
        check(count(db, "SELECT id FROM t WHERE v = 1000") == expected[0], "index has new key");
        check(count(db, "SELECT id FROM t WHERE v = 6") == 0, "index updated after DELETE");
        db.execute("DROP INDEX idx_v");
        check(db.execute("SELECT id FROM t WHERE v = 1000").getPlan().startsWith("full"), "dropped index");
    }

    private static Path tmp() throws IOException { return Files.createTempDirectory("minidb-test"); }

    private static void persistence() throws Exception {
        Path dir = tmp();
        Database db = Database.open(dir, false);
        db.execute("CREATE TABLE a (id INT PRIMARY KEY, name STRING, born DATE, ok BOOLEAN, x DOUBLE)");
        db.execute("CREATE TABLE b (id INT PRIMARY KEY, a_id INT REFERENCES a(id))");
        db.execute("INSERT INTO a VALUES (1, 'one', '2020-02-29', TRUE, 1.5), (2, 'two', '2021-01-01', FALSE, 2.5)");
        db.execute("INSERT INTO b VALUES (1, 2)");
        db.execute("CREATE INDEX idx_name ON a (name)");
        db.close();
        Database db2 = Database.open(dir, false);
        check(count(db2, "SELECT * FROM a") == 2, "rows loaded");
        QueryResult r = db2.execute("SELECT id FROM a WHERE name = 'two'");
        check(r.getPlan().startsWith("index lookup"), "index restored: " + r.getPlan());
        check(r.getRows().get(0).get(0).equals(2L), "index content");
        fails(db2, "INSERT INTO b VALUES (2, 77)", "Foreign key violation");
        db2.execute("DROP TABLE b");
        db2.close();
        check(!Files.exists(dir.resolve("b.tbl")), "dropped table file removed");
        delete(dir);
    }

    private static void crashRecovery() throws Exception {
        Path dir = tmp();
        Database db = Database.open(dir, true);
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
        db.execute("CREATE INDEX idx_v ON t (v)");
        db.execute("INSERT INTO t VALUES (1, 10), (2, 20)");
        db.checkpoint();
        db.execute("INSERT INTO t VALUES (3, 30)");
        db.execute("UPDATE t SET v = 99 WHERE id = 1");
        db.execute("DELETE FROM t WHERE id = 2");
        // "авария": close() не вызывается, контрольной точки нет
        Database db2 = Database.open(dir, true);
        check(count(db2, "SELECT * FROM t") == 2, "rows after replay");
        check(count(db2, "SELECT * FROM t WHERE v = 99") == 1, "update replayed");
        check(db2.execute("SELECT * FROM t WHERE v = 99").getPlan().startsWith("index"), "index consistent");
        check(count(db2, "SELECT * FROM t WHERE id = 2") == 0, "delete replayed");
        db2.close();
        delete(dir);
    }

    private static void tornWal() throws Exception {
        Path dir = tmp();
        Database db = Database.open(dir, true);
        db.execute("CREATE TABLE t (id INT PRIMARY KEY)");
        db.execute("INSERT INTO t VALUES (1)");
        db.execute("INSERT INTO t VALUES (2)");
        try (FileOutputStream f = new FileOutputStream(dir.resolve("wal.log").toFile(), true)) {
            f.write(new byte[] {0, 0, 0, 40, 'I', 'N', 'S'}); // оборванная запись
        }
        Database db2 = Database.open(dir, true);
        check(count(db2, "SELECT * FROM t") == 2, "intact records recovered");
        db2.execute("INSERT INTO t VALUES (3)");
        db2.close();
        Database db3 = Database.open(dir, true);
        check(count(db3, "SELECT * FROM t") == 3, "writes after recovery are durable");
        db3.close();
        delete(dir);
    }

    private static void parserErrors() {
        Database db = cars();
        fails(db, "SELEC * FROM cars", "Unknown command");
        fails(db, "SELECT * FROM", "Expected identifier");
        fails(db, "SELECT * FROM nope", "does not exist");
        fails(db, "SELECT * FROM cars WHERE price >", "Expected literal");
        fails(db, "INSERT INTO cars VALUES (1, 'x'", "Expected");
        fails(db, "SELECT * FROM cars WHERE zzz = 1", "Unknown column");
        fails(db, "INSERT INTO cars VALUES ('unterminated)", "Unterminated");
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
