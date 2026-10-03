package it.paladia.minerva.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer (no libraries: the app is built without Gradle, and the core runs on a plain JVM
 * for the tests). Objects become LinkedHashMap, arrays ArrayList, numbers Double, plus String, Boolean and null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("trailing characters");
        return v;
    }

    // --- typed helpers (tolerant: wrong type or missing key gives the default) ---------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : "";
    }

    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        return obj(m.get(key));
    }

    public static List<Object> list(Map<String, Object> m, String key) {
        return list(m.get(key));
    }

    // --- writer ---------------------------------------------------------------------------------------------------

    public static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    // --- parser ---------------------------------------------------------------------------------------------------

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected '" + c + "'");
        }
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw error("bad literal");
        i += w.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) return m;
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw error("expected key");
            String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            if (peek('}')) return m;
            expect(',');
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) return l;
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(']')) return l;
            expect(',');
        }
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("bad unicode escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: b.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException ex) {
            throw error("bad number");
        }
    }
}
