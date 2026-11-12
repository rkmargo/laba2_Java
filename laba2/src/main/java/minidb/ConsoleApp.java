package minidb;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Консольный интерфейс. Запуск: {@code java -cp out minidb.ConsoleApp [каталог_данных] [--script файл.sql]}.
 * Запросы вводятся до символа {@code ;} и могут занимать несколько строк.
 */
public final class ConsoleApp {
    private ConsoleApp() { }

    public static void main(String[] args) throws IOException {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        Path dir = Paths.get("data");
        String script = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--script") && i + 1 < args.length) script = args[++i];
            else dir = Paths.get(args[i]);
        }
        final Database db = Database.open(dir, true);
        try {
            if (script != null) {
                String text = new String(Files.readAllBytes(Paths.get(script)), StandardCharsets.UTF_8);
                for (String sql : Parser.splitStatements(text)) run(db, sql);
            } else {
                repl(db);
            }
        } finally {
            db.close();
        }
    }

    private static void repl(Database db) throws IOException {
        System.out.println("MiniDB. End each command with ';'. Type help for syntax, exit to quit.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder buf = new StringBuilder();
        while (true) {
            System.out.print(buf.length() == 0 ? "minidb> " : "     -> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) break;
            String trimmed = line.trim();
            if (buf.length() == 0) {
                if (trimmed.isEmpty()) continue;
                if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) break;
                if (trimmed.equalsIgnoreCase("help")) { printHelp(); continue; }
            }
            buf.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                for (String sql : Parser.splitStatements(buf.toString())) run(db, sql);
                buf.setLength(0);
            }
        }
    }

    private static void run(Database db, String sql) {
        long t0 = System.nanoTime();
        try {
            QueryResult r = db.execute(sql);
            double ms = (System.nanoTime() - t0) / 1e6;
            if (r.hasTable()) {
                print(r);
                System.out.printf("%d row(s), %.2f ms%s%n", r.getRows().size(), ms,
                        r.getPlan() == null ? "" : "  [plan: " + r.getPlan() + "]");
            } else {
                System.out.printf("OK: %s (%.2f ms)%n", r.getMessage(), ms);
            }
        } catch (DbException e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    /** Вывод результата в виде текстовой таблицы. */
    static void print(QueryResult r) {
        int n = r.getColumns().size();
        List<String[]> cells = new ArrayList<>();
        int[] w = new int[n];
        for (int i = 0; i < n; i++) w[i] = r.getColumns().get(i).length();
        for (List<Object> row : r.getRows()) {
            String[] s = new String[n];
            for (int i = 0; i < n; i++) {
                Object v = row.get(i);
                s[i] = v == null ? "NULL" : v.toString();
                w[i] = Math.max(w[i], s[i].length());
            }
            cells.add(s);
        }
        StringBuilder sep = new StringBuilder("+");
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < w[i] + 2; k++) sep.append('-');
            sep.append('+');
        }
        System.out.println(sep);
        System.out.println(line(r.getColumns().toArray(new String[0]), w));
        System.out.println(sep);
        for (String[] s : cells) System.out.println(line(s, w));
        System.out.println(sep);
    }

    private static String line(String[] s, int[] w) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < s.length; i++) {
            sb.append(' ').append(s[i]);
            for (int k = s[i].length(); k < w[i]; k++) sb.append(' ');
            sb.append(" |");
        }
        return sb.toString();
    }

    private static void printHelp() {
        System.out.println("CREATE TABLE t (id INT PRIMARY KEY, name STRING, price DOUBLE, d DATE, ok BOOLEAN,");
        System.out.println("                owner_id INT REFERENCES owners(id));");
        System.out.println("DROP TABLE t;   CREATE INDEX idx ON t (col);   DROP INDEX idx;");
        System.out.println("INSERT INTO t [(c1, c2)] VALUES (...), (...);");
        System.out.println("SELECT * | c1, c2 FROM t [WHERE ...] [ORDER BY c [DESC]] [LIMIT n];");
        System.out.println("UPDATE t SET c = v [, ...] [WHERE ...];   DELETE FROM t [WHERE ...];");
        System.out.println("SHOW TABLES;   DESCRIBE t;   CHECKPOINT;   exit");
    }
}
