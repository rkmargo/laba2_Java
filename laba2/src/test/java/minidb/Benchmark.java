package minidb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Сравнение скорости SELECT без индекса и с индексом.
 * Запуск: {@code java -cp out minidb.Benchmark}.
 */
public final class Benchmark {
    private static final int[] SIZES = {10_000, 100_000, 300_000};

    public static void main(String[] args) throws Exception {
        System.out.println("| Строк | Запрос | Без индекса, мкс | С индексом, мкс | Ускорение |");
        System.out.println("|---:|---|---:|---:|---:|");
        for (int n : SIZES) run(n);
        persistence();
    }

    private static Database load(int n) {
        Database db = new Database();
        db.execute("CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, vin STRING, price DOUBLE)");
        Random rnd = new Random(42);
        for (int start = 0; start < n; start += 1000) {
            StringBuilder sb = new StringBuilder("INSERT INTO cars VALUES ");
            for (int i = start; i < Math.min(n, start + 1000); i++) {
                if (i > start) sb.append(',');
                sb.append('(').append(i).append(",'brand").append(rnd.nextInt(50)).append("','VIN").append(i)
                  .append("',").append(1000 + rnd.nextInt(100_000)).append(".5)");
            }
            db.execute(sb.toString());
        }
        return db;
    }

    private static double time(Database db, Statement st, int reps) {
        for (int i = 0; i < Math.max(3, reps / 10); i++) st.execute(db); // прогрев JIT
        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) st.execute(db);
        return (System.nanoTime() - t0) / 1e6 / reps;
    }

    private static void run(int n) {
        Database db = load(n);
        String[][] cases = {
            {"vin = 'VIN" + (n / 2) + "' (1 запись)", "SELECT * FROM cars WHERE vin = 'VIN" + (n / 2) + "'"},
            {"price > 100500 AND price < 100700 (~0.2%)", "SELECT * FROM cars WHERE price > 100500 AND price < 100700"},
            {"brand = 'brand7' (~2%)", "SELECT * FROM cars WHERE brand = 'brand7'"},
            {"id = " + (n / 3) + " (первичный ключ)", "SELECT * FROM cars WHERE id = " + (n / 3)},
        };
        double[] before = new double[cases.length];
        int reps = n >= 300_000 ? 20 : 60;
        for (int i = 0; i < cases.length; i++) before[i] = time(db, Parser.parse(cases[i][1]), reps);
        long t0 = System.nanoTime();
        db.execute("CREATE INDEX idx_vin ON cars (vin)");
        db.execute("CREATE INDEX idx_price ON cars (price)");
        db.execute("CREATE INDEX idx_brand ON cars (brand)");
        double build = (System.nanoTime() - t0) / 1e6;
        for (int i = 0; i < cases.length; i++) {
            double after = time(db, Parser.parse(cases[i][1]), reps * 10);
            System.out.printf("| %,d | `%s` | %,.0f | %,.1f | %,.0f× |%n", n, cases[i][0], before[i] * 1000, after * 1000, before[i] / after);
        }
        System.out.printf("| %,d | построение 3 индексов | - | %.0f мс | - |%n", n, build);
    }

    private static void persistence() throws Exception {
        Path dir = Files.createTempDirectory("minidb-bench");
        Database db = Database.open(dir, false);
        db.execute("CREATE TABLE cars (id INT PRIMARY KEY, vin STRING)");
        for (int s = 0; s < 100_000; s += 1000) {
            StringBuilder sb = new StringBuilder("INSERT INTO cars VALUES ");
            for (int i = s; i < s + 1000; i++) sb.append(i > s ? "," : "").append('(').append(i).append(",'VIN").append(i).append("')");
            db.execute(sb.toString());
        }
        db.execute("CREATE INDEX idx_vin ON cars (vin)");
        long t0 = System.nanoTime();
        db.close();
        double save = (System.nanoTime() - t0) / 1e6;
        long size = 0;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) size += Files.size(p);
        }
        t0 = System.nanoTime();
        Database db2 = Database.open(dir, false);
        double load = (System.nanoTime() - t0) / 1e6;
        System.out.printf("%nПерсистентность (100 000 записей + 1 индекс): сохранение %.0f мс, загрузка %.0f мс, файлы %.1f МБ, план после перезапуска: %s%n",
                save, load, size / 1048576.0, db2.execute("SELECT * FROM cars WHERE vin = 'VIN5'").getPlan());
        db2.close();
    }
}
