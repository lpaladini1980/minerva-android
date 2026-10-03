package it.paladia.minerva.core;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Raw Argo dashboard (camelCase JSON) to dated items per section. Mirrors the Python client's report: same filters,
 * same texts, same order (stable sort by day then subject); TestMain checks it against the shared
 * fixtures/expected_report.json.
 */
public final class Report {
    public static final List<String> SECTIONS = Arrays.asList("homework", "grades", "reminders", "notices", "absences", "notes");
    static final int DEADLINE_HORIZON_DAYS = 60;

    public static final class Item {
        public final String day;      // YYYY-MM-DD
        public final String section;
        public final String subject;  // subject, teacher or category
        public final String text;

        public Item(String day, String section, String subject, String text) {
            this.day = day;
            this.section = section;
            this.subject = subject;
            this.text = collapse(text);
        }
    }

    public static final class Range {
        public final LocalDate start;
        public final LocalDate end;

        public Range(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }
    }

    private Report() {
    }

    static LocalDate parseDay(Object text) {
        if (!(text instanceof String) || ((String) text).length() < 10) return null;
        try {
            return LocalDate.parse(((String) text).substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static boolean flag(Object v) {
        if (v instanceof String) {
            String s = ((String) v).trim().toUpperCase(Locale.ROOT);
            return s.equals("S") || s.equals("SI") || s.equals("TRUE") || s.equals("1");
        }
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) return (Double) v != 0;
        return v != null && !(v instanceof List && ((List<?>) v).isEmpty()) && !(v instanceof Map && ((Map<?, ?>) v).isEmpty());
    }

    /** Python's str() of a JSON value: 8.0 -> "8.0", 8 stays "8" only when it came as text. */
    static String pyStr(Object v) {
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && !Double.isInfinite(d)) return String.format(Locale.ROOT, "%.1f", d);
            return Double.toString(d);
        }
        return v == null ? "" : v.toString();
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Double) return (Double) v != 0;
        if (v instanceof Boolean) return (Boolean) v;
        return true;
    }

    public static Map<String, Range> defaultRanges(LocalDate today) {
        int weekday = today.getDayOfWeek().getValue() - 1;  // Monday = 0, like Python
        LocalDate weekEnd = today.plusDays(6 - weekday);
        if (weekday >= 4) weekEnd = weekEnd.plusDays(7);
        Range lastMonth = new Range(today.minusDays(29), today);
        Map<String, Range> r = new LinkedHashMap<>();
        r.put("homework", new Range(today, weekEnd));
        r.put("grades", new Range(today.minusDays(6), today));
        r.put("reminders", new Range(today, today.plusDays(30)));
        r.put("notices", lastMonth);
        r.put("absences", lastMonth);
        r.put("notes", lastMonth);
        return r;
    }

    public static List<Item> homework(Map<String, Object> d) {
        List<Item> items = new ArrayList<>();
        for (Object o : Json.list(d, "registro")) {
            Map<String, Object> lesson = Json.obj(o);
            LocalDate assigned = parseDay(lesson.get("datGiorno"));
            for (Object t : Json.list(lesson, "compiti")) {
                Map<String, Object> task = Json.obj(t);
                LocalDate day = parseDay(task.get("dataConsegna"));
                if (day == null) continue;
                String text = Json.str(task, "compito").trim();
                if (truthy(lesson.get("docente"))) text += " - " + lesson.get("docente");
                if (assigned != null) text += " (assigned " + assigned + ")";
                items.add(new Item(day.toString(), "homework", Json.str(lesson, "materia"), text));
            }
        }
        return items;
    }

    public static List<Item> grades(Map<String, Object> d) {
        List<Item> items = new ArrayList<>();
        for (Object o : Json.list(d, "voti")) {
            Map<String, Object> g = Json.obj(o);
            LocalDate day = parseDay(g.get("datGiorno"));
            if (day == null) continue;
            Object value = truthy(g.get("codCodice")) ? g.get("codCodice") : truthy(g.get("valore")) ? g.get("valore")
                    : truthy(g.get("descrizioneVoto")) ? g.get("descrizioneVoto") : "";
            String text = pyStr(value);
            if (truthy(g.get("descrizioneProva"))) text += " (" + g.get("descrizioneProva") + ")";
            if (truthy(g.get("docente"))) text += " - " + g.get("docente");
            if (truthy(g.get("desCommento"))) text += " [" + g.get("desCommento") + "]";
            items.add(new Item(day.toString(), "grades", Json.str(g, "desMateria"), text));
        }
        return items;
    }

    public static List<Item> reminders(Map<String, Object> d) {
        List<Item> items = new ArrayList<>();
        for (Object o : Json.list(d, "promemoria")) {
            Map<String, Object> r = Json.obj(o);
            if (r.containsKey("flgVisibileFamiglia") && !flag(r.get("flgVisibileFamiglia"))) continue;
            LocalDate day = parseDay(r.get("datGiorno"));
            if (day == null) continue;
            String text = Json.str(r, "desAnnotazioni").trim();
            String start = left(Json.str(r, "oraInizio"), 5), end = left(Json.str(r, "oraFine"), 5);
            if (!start.isEmpty() && !end.isEmpty() && !(start.equals("00:00") && end.equals("00:00"))) text += " [" + start + "-" + end + "]";
            items.add(new Item(day.toString(), "reminders", Json.str(r, "docente"), text));
        }
        return items;
    }

    public static List<Item> notices(Map<String, Object> d, LocalDate today) {
        List<Item> items = new ArrayList<>();
        for (Object o : Json.list(d, "bacheca")) {
            Map<String, Object> n = Json.obj(o);
            LocalDate day = parseDay(n.get("data"));
            if (day == null) continue;
            String text = Json.str(n, "messaggio").trim();
            if (truthy(n.get("autore"))) text += " - " + n.get("autore");
            LocalDate deadline = parseDay(n.get("dataScadenza"));
            if (deadline != null && (today == null || (!deadline.isBefore(today) && !deadline.isAfter(today.plusDays(DEADLINE_HORIZON_DAYS))))) {
                text += " (deadline " + deadline + ")";
            }
            if (flag(n.get("pvRichiesta")) && !flag(n.get("isPresaVisione"))) text += " [acknowledgement required]";
            if (flag(n.get("adRichiesta")) && !flag(n.get("isPresaAdesioneConfermata"))) text += " [consent required]";
            String category = Json.str(n, "categoria");
            items.add(new Item(day.toString(), "notices", category.isEmpty() ? "General" : category, text));
        }
        return items;
    }

    public static List<Item> absences(Map<String, Object> d) {
        List<Item> items = new ArrayList<>();
        for (Object o : Json.list(d, "appello")) {
            Map<String, Object> e = Json.obj(o);
            LocalDate day = parseDay(e.get("data"));
            if (day == null) continue;
            String code = Json.str(e, "codEvento").toUpperCase(Locale.ROOT);
            String kind = code.equals("A") ? "absence" : code.equals("I") ? "late entry" : code.equals("U") ? "early exit" : "event";
            List<String> details = new ArrayList<>();
            if (truthy(e.get("descrizione"))) details.add(e.get("descrizione").toString());
            if (flag(e.get("daGiustificare"))) details.add("to justify");
            else if (flag(e.get("giustificata"))) details.add("justified");
            if (truthy(e.get("nota"))) details.add(e.get("nota").toString());
            items.add(new Item(day.toString(), "absences", kind, String.join("; ", details)));
        }
        return items;
    }

    public static List<Item> notes(Map<String, Object> d) {
        List<Item> items = new ArrayList<>();
        for (Object o : Json.list(d, "noteDisciplinari")) {
            Map<String, Object> n = Json.obj(o);
            LocalDate day = parseDay(truthy(n.get("data")) ? n.get("data") : n.get("datGiorno"));
            if (day != null) items.add(new Item(day.toString(), "notes", Json.str(n, "docente"), Json.str(n, "descrizione").trim()));
        }
        return items;
    }

    public static List<Item> collect(Map<String, Object> d, String section, LocalDate today) {
        switch (section) {
            case "homework": return homework(d);
            case "grades": return grades(d);
            case "reminders": return reminders(d);
            case "notices": return notices(d, today);
            case "absences": return absences(d);
            case "notes": return notes(d);
            default: throw new IllegalArgumentException(section);
        }
    }

    /** Items of the section inside the range, sorted by day then subject (stable). */
    public static List<Item> build(Map<String, Object> d, String section, Range range, LocalDate today) {
        List<Item> chosen = new ArrayList<>();
        String start = range.start.toString(), end = range.end.toString();
        for (Item item : collect(d, section, today)) {
            if (item.day.compareTo(start) >= 0 && item.day.compareTo(end) <= 0) chosen.add(item);
        }
        chosen.sort(Comparator.comparing((Item i) -> i.day).thenComparing(i -> i.subject));
        return chosen;
    }

    /** Python's " ".join(text.split()): one line, Unicode spaces included (String.strip() needs Android 13). */
    static String collapse(String text) {
        // No regex: Android's ICU rejects the (?U) flag that a desktop JVM accepts.
        StringBuilder b = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSpace(c)) {
                pendingSpace = b.length() > 0;
                continue;
            }
            if (pendingSpace) b.append(' ');
            pendingSpace = false;
            b.append(c);
        }
        return b.toString();
    }

    /** Python's str.isspace(): ASCII whitespace, separators (no-break spaces included) and NEL. */
    static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
    }

    private static String left(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
