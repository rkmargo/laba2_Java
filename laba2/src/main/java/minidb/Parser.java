package minidb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import minidb.Lexer.Token;
import minidb.Lexer.Type;

/**
 * Рекурсивный нисходящий парсер SQL-подобного языка.
 * <pre>
 * CREATE TABLE t (col TYPE [PRIMARY KEY] [REFERENCES t2(col)], ...)
 * DROP TABLE t | CREATE INDEX i ON t (col) | DROP INDEX i
 * INSERT INTO t [(c1, c2)] VALUES (v1, v2) [, (...)]
 * SELECT * | c1, c2 FROM t [WHERE cond] [ORDER BY c [ASC|DESC]] [LIMIT n]
 * UPDATE t SET c = v [, ...] [WHERE cond]
 * DELETE FROM t [WHERE cond]
 * SHOW TABLES | DESCRIBE t | CHECKPOINT
 * cond := or-выражение из сравнений (=, !=, &lt;&gt;, &lt;, &lt;=, &gt;, &gt;=), IS [NOT] NULL, AND, OR, скобок
 * </pre>
 */
public final class Parser {
    private final List<Token> tokens;
    private int p;

    private Parser(List<Token> tokens) { this.tokens = tokens; }

    /** Разбирает один запрос (завершающая {@code ;} необязательна). */
    public static Statement parse(String sql) {
        Parser parser = new Parser(Lexer.tokenize(sql));
        Statement st = parser.statement();
        if (parser.peek().isSymbol(";")) parser.p++;
        if (parser.peek().type != Type.EOF) {
            throw new DbException("Unexpected '" + parser.peek().text + "' at position " + parser.peek().pos);
        }
        return st;
    }

    /** Делит скрипт на отдельные запросы по {@code ;} (точки с запятой внутри строк игнорируются). */
    public static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\'') inStr = !inStr;
            if (!inStr && c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i < script.length() && script.charAt(i) != '\n') i++; // комментарий
                continue;
            }
            if (c == ';' && !inStr) {
                if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    // ---- служебные методы ----

    private Token peek() { return tokens.get(p); }
    private Token next() { return tokens.get(p++); }

    private boolean accept(String kw) {
        if (peek().isKeyword(kw)) { p++; return true; }
        return false;
    }

    private void expect(String kw) {
        if (!accept(kw)) throw error("Expected " + kw.toUpperCase());
    }

    private void expectSymbol(String s) {
        if (!peek().isSymbol(s)) throw error("Expected '" + s + "'");
        p++;
    }

    private boolean acceptSymbol(String s) {
        if (peek().isSymbol(s)) { p++; return true; }
        return false;
    }

    private String ident() {
        Token t = next();
        if (t.type != Type.IDENT) { p--; throw error("Expected identifier"); }
        return t.text;
    }

    private DbException error(String msg) {
        Token t = peek();
        String near = t.type == Type.EOF ? "end of query" : "'" + t.text + "'";
        return new DbException("Syntax error: " + msg + " near " + near + " (position " + t.pos + ")");
    }

    // ---- команды ----

    private Statement statement() {
        if (accept("create")) {
            if (accept("table")) return createTable();
            if (accept("index")) return createIndex();
            throw error("Expected TABLE or INDEX");
        }
        if (accept("drop")) {
            if (accept("table")) return new Statements.DropTable(ident());
            if (accept("index")) return new Statements.DropIndex(ident());
            throw error("Expected TABLE or INDEX");
        }
        if (accept("insert")) return insert();
        if (accept("select")) return select();
        if (accept("update")) return update();
        if (accept("delete")) return delete();
        if (accept("show")) { expect("tables"); return new Statements.ShowTables(); }
        if (accept("describe")) return new Statements.Describe(ident());
        if (accept("checkpoint")) return new Statements.Checkpoint();
        throw error("Unknown command");
    }

    private Statement createTable() {
        String table = ident();
        expectSymbol("(");
        List<Column> cols = new ArrayList<>();
        do {
            String name = ident();
            DataType type = DataType.fromSql(ident());
            if (acceptSymbol("(")) { // VARCHAR(50) - размер игнорируется
                next();
                expectSymbol(")");
            }
            boolean pk = false;
            String refT = null, refC = null;
            while (true) {
                if (accept("primary")) { expect("key"); pk = true; }
                else if (accept("references")) {
                    refT = ident();
                    expectSymbol("(");
                    refC = ident();
                    expectSymbol(")");
                } else break;
            }
            cols.add(new Column(name, type, pk, refT, refC));
        } while (acceptSymbol(","));
        expectSymbol(")");
        return new Statements.CreateTable(table, cols);
    }

    private Statement createIndex() {
        String name = ident();
        expect("on");
        String table = ident();
        expectSymbol("(");
        String col = ident();
        expectSymbol(")");
        return new Statements.CreateIndex(name, table, col);
    }

    private Statement insert() {
        expect("into");
        String table = ident();
        List<String> cols = null;
        if (acceptSymbol("(")) {
            cols = new ArrayList<>();
            do { cols.add(ident()); } while (acceptSymbol(","));
            expectSymbol(")");
        }
        expect("values");
        List<List<Object>> rows = new ArrayList<>();
        do {
            expectSymbol("(");
            List<Object> row = new ArrayList<>();
            do { row.add(literal()); } while (acceptSymbol(","));
            expectSymbol(")");
            rows.add(row);
        } while (acceptSymbol(","));
        return new Statements.Insert(table, cols, rows);
    }

    private Statement select() {
        List<String> cols = null;
        if (!acceptSymbol("*")) {
            cols = new ArrayList<>();
            do { cols.add(ident()); } while (acceptSymbol(","));
        }
        expect("from");
        String table = ident();
        Condition where = accept("where") ? or() : null;
        String orderBy = null;
        boolean desc = false;
        if (accept("order")) {
            expect("by");
            orderBy = ident();
            if (accept("desc")) desc = true; else accept("asc");
        }
        int limit = -1;
        if (accept("limit")) {
            Token t = next();
            if (t.type != Type.NUMBER || t.text.contains(".") || t.text.startsWith("-")) {
                p--;
                throw error("Expected non-negative integer after LIMIT");
            }
            limit = Integer.parseInt(t.text);
        }
        return new Statements.Select(table, cols, where, orderBy, desc, limit);
    }

    private Statement update() {
        String table = ident();
        expect("set");
        Map<String, Object> sets = new LinkedHashMap<>();
        do {
            String col = ident();
            expectSymbol("=");
            if (sets.containsKey(col)) throw new DbException("Column '" + col + "' is assigned twice");
            sets.put(col, literal());
        } while (acceptSymbol(","));
        Condition where = accept("where") ? or() : null;
        return new Statements.Update(table, sets, where);
    }

    private Statement delete() {
        expect("from");
        String table = ident();
        Condition where = accept("where") ? or() : null;
        return new Statements.Delete(table, where);
    }

    // ---- выражения ----

    private Condition or() {
        Condition left = and();
        while (accept("or")) left = new Condition.Or(left, and());
        return left;
    }

    private Condition and() {
        Condition left = primary();
        while (accept("and")) left = new Condition.And(left, primary());
        return left;
    }

    private Condition primary() {
        if (acceptSymbol("(")) {
            Condition c = or();
            expectSymbol(")");
            return c;
        }
        String col = ident();
        if (accept("is")) {
            boolean not = accept("not");
            expect("null");
            return new Condition.IsNull(col, not);
        }
        Token t = next();
        Condition.Op op;
        if (t.type != Type.SYMBOL) { p--; throw error("Expected comparison operator"); }
        switch (t.text) {
            case "=": op = Condition.Op.EQ; break;
            case "!=": case "<>": op = Condition.Op.NE; break;
            case "<": op = Condition.Op.LT; break;
            case "<=": op = Condition.Op.LE; break;
            case ">": op = Condition.Op.GT; break;
            case ">=": op = Condition.Op.GE; break;
            default: p--; throw error("Expected comparison operator");
        }
        return new Condition.Comparison(col, op, literal());
    }

    private Object literal() {
        Token t = next();
        switch (t.type) {
            case NUMBER:
                try {
                    return t.text.contains(".") ? (Object) Double.valueOf(t.text) : (Object) Long.valueOf(t.text);
                } catch (NumberFormatException e) {
                    throw new DbException("Invalid number: " + t.text);
                }
            case STRING:
                return t.text;
            case IDENT:
                if (t.isKeyword("true")) return Boolean.TRUE;
                if (t.isKeyword("false")) return Boolean.FALSE;
                if (t.isKeyword("null")) return null;
                break;
            default:
                break;
        }
        p--;
        throw error("Expected literal value");
    }
}
