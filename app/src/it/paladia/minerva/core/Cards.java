package it.paladia.minerva.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dashboard of one student as cards: Tomorrow, Tests and oral exams, This week, Grades,
 * To sign, Absences and notes. Empty cards are left out, except Tomorrow. Built on Report, so the filters are the
 * Python client's; the texts here are the Italian ones the family reads in the app.
 */
public final class Cards {
    public static final class Row {
        public final String day;     // "lun 5/10"
        public final String label;   // subject, teacher, category
        public final String main;
        public final String detail;  // may be empty
        public final boolean alert;  // needs a parent: to sign, to justify, a disciplinary note

        Row(String day, String label, String main, String detail, boolean alert) {
            this.day = day;
            this.label = label;
            this.main = main;
            this.detail = detail;
            this.alert = alert;
        }
    }

    public static final class Card {
        public final String title;
        public final String empty;   // shown when there are no rows
        public final List<Row> rows = new ArrayList<>();

        Card(String title, String empty) {
            this.title = title;
            this.empty = empty;
        }
    }

    private static final String[] DAYS = {"lun", "mar", "mer", "gio", "ven", "sab", "dom"};
    private static final String[] DAY_NAMES = {"lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"};
    private static final Pattern ASSIGNED = Pattern.compile("^(.*?)(?: - (.+?))?(?: \\(assigned (\\d{4}-\\d{2}-\\d{2})\\))?$");
    private static final Pattern HOURS = Pattern.compile(" \\[(\\d{2}:\\d{2})-(\\d{2}:\\d{2})\\]$");

    private Cards() {
    }

    public static String day(String iso) {
        LocalDate d = LocalDate.parse(iso);
        return DAYS[d.getDayOfWeek().getValue() - 1] + " " + d.getDayOfMonth() + "/" + d.getMonthValue();
    }

    /** The next day with lessons: Friday and Saturday look at Monday. */
    public static LocalDate nextSchoolDay(LocalDate today) {
        LocalDate next = today.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) next = next.plusDays(1);
        return next;
    }

    static String capitalize(String s) {
        String lower = s.toLowerCase(java.util.Locale.ITALIAN);
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    static Row homeworkRow(Report.Item i, boolean withDay) {
        Matcher m = ASSIGNED.matcher(i.text);
        String task = i.text, detail = "";
        if (m.matches()) {
            task = m.group(1);
            List<String> parts = new ArrayList<>();
            if (m.group(2) != null) parts.add(m.group(2));
            if (m.group(3) != null) parts.add("assegnati " + day(m.group(3)));
            detail = String.join(" · ", parts);
        }
        return new Row(withDay ? day(i.day) : "", capitalize(i.subject), task, detail, false);
    }

    static Row reminderRow(Report.Item i) {
        Matcher m = HOURS.matcher(i.text);
        String main = i.text, detail = i.subject;
        if (m.find()) {
            main = i.text.substring(0, m.start());
            detail = "ore " + m.group(1) + "-" + m.group(2) + (i.subject.isEmpty() ? "" : " · " + i.subject);
        }
        return new Row(day(i.day), "", main, detail, false);
    }

    static Row gradeRow(Report.Item i) {
        // "<value> (<test>) - <teacher> [<comment>]"
        String text = i.text, comment = "";
        int bracket = text.lastIndexOf(" [");
        if (bracket > 0 && text.endsWith("]")) {
            comment = text.substring(bracket + 2, text.length() - 1);
            text = text.substring(0, bracket);
        }
        String teacher = "";
        int dash = text.indexOf(" - ");
        if (dash > 0) {
            teacher = text.substring(dash + 3);
            text = text.substring(0, dash);
        }
        String detail = teacher + (comment.isEmpty() ? "" : (teacher.isEmpty() ? "" : " · ") + comment);
        return new Row(day(i.day), capitalize(i.subject), text, detail, false);
    }

    static Row noticeRow(Report.Item i) {
        String text = i.text;
        boolean ack = text.contains(" [acknowledgement required]");
        boolean consent = text.contains(" [consent required]");
        text = text.replace(" [consent required]", "").replace(" [acknowledgement required]", "");
        List<String> tags = new ArrayList<>();
        Matcher deadline = Pattern.compile(" \\(deadline (\\d{4}-\\d{2}-\\d{2})\\)$").matcher(text);
        if (deadline.find()) {
            tags.add("entro " + day(deadline.group(1)));
            text = text.substring(0, deadline.start());
        }
        if (ack) tags.add("presa visione");
        if (consent) tags.add("adesione");
        return new Row(day(i.day), capitalize(i.subject), text, String.join(" · ", tags), ack || consent);
    }

    static Row absenceRow(Report.Item i) {
        String kind = i.subject.equals("absence") ? "Assenza" : i.subject.equals("late entry") ? "Ingresso in ritardo"
                : i.subject.equals("early exit") ? "Uscita anticipata" : "Evento";
        boolean toJustify = i.text.contains("to justify");
        String detail = i.text.replace("to justify", "da giustificare").replace("justified", "giustificata");
        return new Row(day(i.day), "", kind, detail, toJustify);
    }

    public static List<Card> build(Map<String, Object> dashboard, LocalDate today) {
        List<Card> cards = new ArrayList<>();
        LocalDate next = nextSchoolDay(today);
        Map<String, Report.Range> ranges = Report.defaultRanges(today);

        String when = next.equals(today.plusDays(1)) ? "domani" : DAY_NAMES[next.getDayOfWeek().getValue() - 1];
        Card tomorrow = new Card("Per " + when + " (" + day(next.toString()) + ")", "Niente da consegnare");
        for (Report.Item i : Report.build(dashboard, "homework", new Report.Range(next, next), today)) tomorrow.rows.add(homeworkRow(i, false));
        cards.add(tomorrow);

        Card tests = new Card("Verifiche e interrogazioni", "");
        for (Report.Item i : Report.build(dashboard, "reminders", ranges.get("reminders"), today)) tests.rows.add(reminderRow(i));
        cards.add(tests);

        Card week = new Card("Compiti dei prossimi giorni", "");
        for (Report.Item i : Report.build(dashboard, "homework", ranges.get("homework"), today)) {
            if (i.day.compareTo(next.toString()) > 0) week.rows.add(homeworkRow(i, true));
        }
        cards.add(week);

        Card grades = new Card("Voti", "");
        for (Report.Item i : Report.build(dashboard, "grades", new Report.Range(today.minusDays(30), today), today)) grades.rows.add(0, gradeRow(i));
        Object average = dashboard.get("mediaGenerale");
        if (average instanceof Double && !grades.rows.isEmpty()) {
            grades.rows.add(0, new Row("", "", "Media generale " + String.format(java.util.Locale.ITALIAN, "%.2f", (Double) average), "", false));
        }
        cards.add(grades);

        Card sign = new Card("Da firmare nell'app DidUp", "");
        for (Report.Item i : Report.build(dashboard, "notices", new Report.Range(today.minusDays(365), today), today)) {
            Row r = noticeRow(i);
            if (r.alert) sign.rows.add(0, r);
        }
        cards.add(sign);

        Card absences = new Card("Assenze e note", "");
        Report.Range month = new Report.Range(today.minusDays(29), today);
        for (Report.Item i : Report.build(dashboard, "absences", month, today)) absences.rows.add(0, absenceRow(i));
        for (Report.Item i : Report.build(dashboard, "notes", month, today)) absences.rows.add(0, new Row(day(i.day), "Nota", i.text, i.subject, true));
        cards.add(absences);

        cards.removeIf(c -> c.rows.isEmpty() && c.empty.isEmpty());
        return cards;
    }
}
