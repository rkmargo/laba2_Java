package minidb;

import java.util.List;

/**
 * Условие WHERE в виде дерева выражений. Перед использованием условие привязывается
 * к таблице ({@link #bind(Table)}): определяются позиции столбцов и типы литералов.
 */
public interface Condition {

    /** Привязывает условие к таблице. */
    void bind(Table table);

    /** Проверяет запись. */
    boolean test(Row row);

    /** Складывает в {@code out} условия верхнего уровня, соединённые AND. */
    default void conjuncts(List<Condition> out) {
        out.add(this);
    }

    /** Операторы сравнения. */
    enum Op {
        EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=");
        private final String sql;
        Op(String sql) { this.sql = sql; }
        public String sql() { return sql; }
    }

    /** Сравнение столбца с литералом: {@code col op value}. */
    class Comparison implements Condition {
        private final String column;
        private final Op op;
        private final Object raw;
        private int pos;
        private Object value;

        public Comparison(String column, Op op, Object raw) {
            this.column = column;
            this.op = op;
            this.raw = raw;
        }

        public String getColumn() { return column; }
        public Op getOp() { return op; }
        public Object getValue() { return value; }
        public int getPos() { return pos; }

        @Override
        public void bind(Table table) {
            pos = table.columnIndex(column);
            value = table.getColumns().get(pos).getType().coerce(raw);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean test(Row row) {
            Object cell = row.get(pos);
            if (cell == null || value == null) return false; // семантика NULL как в SQL
            int c = ((Comparable<Object>) cell).compareTo(value);
            switch (op) {
                case EQ: return c == 0;
                case NE: return c != 0;
                case LT: return c < 0;
                case LE: return c <= 0;
                case GT: return c > 0;
                default: return c >= 0;
            }
        }
    }

    /** {@code col IS [NOT] NULL}. */
    class IsNull implements Condition {
        private final String column;
        private final boolean negated;
        private int pos;

        public IsNull(String column, boolean negated) {
            this.column = column;
            this.negated = negated;
        }

        @Override
        public void bind(Table table) { pos = table.columnIndex(column); }

        @Override
        public boolean test(Row row) { return (row.get(pos) == null) != negated; }
    }

    /** Логическое И. */
    class And implements Condition {
        private final Condition left, right;

        public And(Condition left, Condition right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public void bind(Table t) { left.bind(t); right.bind(t); }

        @Override
        public boolean test(Row r) { return left.test(r) && right.test(r); }

        @Override
        public void conjuncts(List<Condition> out) {
            left.conjuncts(out);
            right.conjuncts(out);
        }
    }

    /** Логическое ИЛИ. */
    class Or implements Condition {
        private final Condition left, right;

        public Or(Condition left, Condition right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public void bind(Table t) { left.bind(t); right.bind(t); }

        @Override
        public boolean test(Row r) { return left.test(r) || right.test(r); }
    }
}
