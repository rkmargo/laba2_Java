package minidb;

import java.util.ArrayList;
import java.util.List;

/**
 * Лексический анализатор SQL-подобного языка. Идентификаторы приводятся к нижнему регистру,
 * строковые литералы сохраняются как есть.
 */
final class Lexer {

    /** Тип токена. */
    enum Type { IDENT, NUMBER, STRING, SYMBOL, EOF }

    /** Токен. */
    static final class Token {
        final Type type;
        final String text;
        final int pos;

        Token(Type type, String text, int pos) {
            this.type = type;
            this.text = text;
            this.pos = pos;
        }

        boolean isKeyword(String kw) { return type == Type.IDENT && text.equalsIgnoreCase(kw); }
        boolean isSymbol(String s) { return type == Type.SYMBOL && text.equals(s); }
    }

    private Lexer() { }

    static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                while (i < n && s.charAt(i) != '\n') i++; // комментарий до конца строки
            } else if (Character.isLetter(c) || c == '_') {
                int st = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                out.add(new Token(Type.IDENT, s.substring(st, i).toLowerCase(), st));
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int st = i++;
                boolean dot = false;
                while (i < n && (Character.isDigit(s.charAt(i)) || (!dot && s.charAt(i) == '.'))) {
                    if (s.charAt(i) == '.') dot = true;
                    i++;
                }
                out.add(new Token(Type.NUMBER, s.substring(st, i), st));
            } else if (c == '\'') {
                int st = i++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char d = s.charAt(i++);
                    if (d == '\'') {
                        if (i < n && s.charAt(i) == '\'') { sb.append('\''); i++; } else { closed = true; break; }
                    } else {
                        sb.append(d);
                    }
                }
                if (!closed) throw new DbException("Unterminated string literal at position " + st);
                out.add(new Token(Type.STRING, sb.toString(), st));
            } else if (c == '<' || c == '>' || c == '!') {
                int st = i++;
                if (i < n && (s.charAt(i) == '=' || (c == '<' && s.charAt(i) == '>'))) i++;
                String sym = s.substring(st, i);
                if (sym.equals("!")) throw new DbException("Unexpected '!' at position " + st);
                out.add(new Token(Type.SYMBOL, sym, st));
            } else if ("(),;*=.".indexOf(c) >= 0) {
                out.add(new Token(Type.SYMBOL, String.valueOf(c), i++));
            } else {
                throw new DbException("Unexpected character '" + c + "' at position " + i);
            }
        }
        out.add(new Token(Type.EOF, "", n));
        return out;
    }
}
