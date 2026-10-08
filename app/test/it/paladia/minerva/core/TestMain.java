package it.paladia.minerva.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests of the app's core on a plain JVM (no JUnit: the app is built without Gradle). Run with ./test.sh.
 * The fixtures (fixtures/) are shared with the Python client: both clients must give the same report.
 */
public final class TestMain {
    static int failures = 0;
    static int checks = 0;
    static Path fixtures;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void equal(Object expected, Object actual, String what) {
        check(expected == null ? actual == null : expected.equals(actual), what + ": expected <" + expected + "> got <" + actual + ">");
    }

    static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(fixtures.resolve(name)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        fixtures = Paths.get(args.length > 0 ? args[0] : "fixtures");
        testJson();
        testExpectedReport();
        testDefaultRanges();
        testHelpers();
        testReadOnly();
        testLogin();
        testCards();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static void testJson() {
        Map<String, Object> m = Json.obj(Json.parse("{\"a\": [1, 2.5, \"x\\n\\u00e8\"], \"b\": {\"c\": null, \"d\": true}}"));
        equal(Arrays.asList(1.0, 2.5, "x\nè"), m.get("a"), "json array");
        check(Json.obj(m, "b").containsKey("c") && Json.obj(m, "b").get("c") == null, "json null");
        equal(Boolean.TRUE, Json.obj(m, "b").get("d"), "json bool");
        equal("\"a\\\"b\\n\"", Json.quote("a\"b\n"), "json quote");
        boolean failed = false;
        try {
            Json.parse("{\"a\": }");
        } catch (IllegalArgumentException e) {
            failed = true;
        }
        check(failed, "json rejects bad input");
    }

    static void testExpectedReport() throws IOException {
        Map<String, Object> dashboard = Argo.unwrapDashboard(Json.parse(fixture("dashboard.json")));
        Map<String, Object> expected = Json.obj(Json.parse(fixture("expected_report.json")));
        LocalDate from = LocalDate.parse(Json.str(expected, "from")), to = LocalDate.parse(Json.str(expected, "to"));
        LocalDate today = LocalDate.parse(Json.str(expected, "today"));
        Map<String, Object> sections = Json.obj(expected, "sections");
        for (String section : Report.SECTIONS) {
            List<Object> want = Json.list(sections, section);
            List<Report.Item> got = Report.build(dashboard, section, new Report.Range(from, to), today);
            equal(want.size(), got.size(), section + " count");
            for (int i = 0; i < Math.min(want.size(), got.size()); i++) {
                Map<String, Object> w = Json.obj(want.get(i));
                Report.Item g = got.get(i);
                equal(Json.str(w, "day"), g.day, section + "[" + i + "].day");
                equal(Json.str(w, "section"), g.section, section + "[" + i + "].section");
                equal(Json.str(w, "subject"), g.subject, section + "[" + i + "].subject");
                equal(Json.str(w, "text"), g.text, section + "[" + i + "].text");
            }
        }
    }

    static void testDefaultRanges() {
        Map<String, Report.Range> monday = Report.defaultRanges(LocalDate.of(2026, 10, 5));
        equal(LocalDate.of(2026, 10, 11), monday.get("homework").end, "homework range on Monday");
        equal(LocalDate.of(2026, 9, 29), monday.get("grades").start, "grades range");
        equal(LocalDate.of(2026, 10, 18), Report.defaultRanges(LocalDate.of(2026, 10, 9)).get("homework").end, "homework range on Friday");
    }

    static void testHelpers() {
        equal("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Argo.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "PKCE RFC 7636");
        equal("a b c", Report.collapse("  a\n b  c  "), "collapse whitespace");
        equal("CH1", Argo.queryParam("https://x/y?a=1&login_challenge=CH1", "login_challenge"), "query param");
        Argo.Profile p = new Argo.Profile("t", "c", new LinkedHashMap<>());
        p.firstName = "BIANCA MARIA";
        equal("Bianca Maria", p.displayName(), "display name");
    }

    static void testReadOnly() {
        equal(new java.util.HashSet<>(Arrays.asList("POST login", "GET profilo", "POST dashboard/dashboard")), Argo.READ_ONLY_CALLS, "allowlist");
        List<String> sent = new ArrayList<>();
        Argo argo = new Argo("s", "u", "p", (m, u, h, b) -> {
            sent.add(u);
            return new Http.Response(200, new LinkedHashMap<>(), new byte[0]);
        });
        for (String[] call : new String[][]{{"POST", "presavisionebachecanuova"}, {"POST", "adesione"}, {"POST", "rimuoviprofilo"},
                {"POST", "downloadallegatobacheca"}, {"GET", "login"}, {"POST", "profilo"}}) {
            boolean refused = false;
            try {
                argo.api(call[0], call[1], null, null);
            } catch (IOException e) {
                refused = e.getMessage().startsWith("Refused");
            }
            check(refused, "refused " + call[0] + " " + call[1]);
        }
        check(sent.isEmpty(), "nothing sent for refused calls");
    }

    static List<String> titles(List<Cards.Card> cards) {
        List<String> t = new ArrayList<>();
        for (Cards.Card c : cards) t.add(c.title);
        return t;
    }

    static void testCards() throws IOException {
        // Same fixture plus a task due today: it must show up in neither "Per domani" nor the following days.
        String withToday = fixture("dashboard.json").replace("\"dataConsegna\": \"2026-10-07\"}",
                "\"dataConsegna\": \"2026-10-07\"}, {\"compito\": \"Ripassare le formule\", \"dataConsegna\": \"2026-10-05\"}");
        Map<String, Object> d = Argo.unwrapDashboard(Json.parse(withToday));
        List<Cards.Card> cards = Cards.build(d, LocalDate.of(2026, 10, 5));  // Monday
        equal(Arrays.asList("Per domani (mar 6/10)", "Verifiche e interrogazioni", "Compiti dei prossimi giorni", "Voti",
                "Da firmare nell'app DidUp", "Assenze e note"), titles(cards), "card titles");
        Cards.Row tomorrow = cards.get(0).rows.get(0);
        equal("Italiano", tomorrow.label, "tomorrow subject");
        equal("Leggere il capitolo 3", tomorrow.main, "tomorrow task");
        equal("(Prof.ssa BIANCHI ANNA) · assegnati lun 5/10", tomorrow.detail, "tomorrow detail");
        equal(2, cards.get(1).rows.size(), "tests count (hidden reminder left out)");
        equal("Interrogazione di storia", cards.get(1).rows.get(0).main, "test text");
        equal("ore 09:00-10:00 · (Prof. NERI PAOLO)", cards.get(1).rows.get(0).detail, "test hours");
        equal(1, cards.get(2).rows.size(), "rest of the week without tomorrow");
        equal("Media generale 7,25", cards.get(3).rows.get(0).main, "average first");
        equal("6½", cards.get(3).rows.get(1).main, "newest grade first");
        Cards.Row sign = cards.get(4).rows.get(0);
        equal("Uscita didattica al museo - Dirigente", sign.main, "to sign text");
        equal("entro sab 10/10 · presa visione · adesione", sign.detail, "to sign tags");
        check(sign.alert, "to sign is an alert");
        equal("Nota", cards.get(5).rows.get(0).label, "note first (newest)");
        equal(3, cards.get(5).rows.size(), "absences and notes");

        List<Cards.Card> quiet = Cards.build(new LinkedHashMap<>(), LocalDate.of(2026, 10, 9));  // Friday, empty register
        equal(Arrays.asList("Per lunedì (lun 12/10)"), titles(quiet), "only Tomorrow when empty, Friday looks at Monday");
        equal("Niente da consegnare", quiet.get(0).empty, "empty text");
    }

    /** The whole login against a fake Argo: OAuth chain, app login, two profiles (one disabled is skipped), dashboard. */
    static void testLogin() throws IOException {
        List<String[]> calls = new ArrayList<>();
        Http.Transport fake = (method, url, headers, body) -> {
            calls.add(new String[]{method, url, headers == null ? "" : headers.getOrDefault("cookie", "-"),
                    headers == null ? "" : headers.getOrDefault("x-auth-token", "")});
            Map<String, List<String>> h = new LinkedHashMap<>();
            String json = null;
            if (url.startsWith("https://itunes.apple.com/")) json = "{\"results\":[{\"version\":\"9.9.9\"}]}";
            else if (url.startsWith(Argo.OAUTH_AUTH_URL)) {
                h.put("location", List.of("https://www.portaleargo.it/auth/sso/login?login_challenge=CH1"));
                h.put("set-cookie", List.of("a=1; Path=/"));
            } else if (url.equals(Argo.SSO_LOGIN_URL)) {
                String form = new String(body, StandardCharsets.UTF_8);
                if (form.contains("password=s3cret%26%3D")) h.put("location", List.of("https://auth.portaleargo.it/hop1"));
            } else if (url.endsWith("/hop1")) {
                h.put("location", List.of("https://auth.portaleargo.it/hop2"));
                h.put("set-cookie", List.of("b=2; HttpOnly"));
            } else if (url.endsWith("/hop2")) h.put("location", List.of("https://auth.portaleargo.it/hop3"));
            else if (url.endsWith("/hop3")) h.put("location", List.of("it.argosoft.didup.famiglia.new://login-callback?code=CODE42&state=x"));
            else if (url.equals(Argo.OAUTH_TOKEN_URL)) json = "{\"access_token\":\"AT\",\"expires_in\":3600}";
            else if (url.equals(Argo.API_BASE + "login")) json = fixture("login.json");
            else if (url.equals(Argo.API_BASE + "profilo")) json = fixture(headers.get("x-auth-token").equals("token-alice") ? "profilo_alice.json" : "profilo_bianca.json");
            else if (url.equals(Argo.API_BASE + "dashboard/dashboard")) json = fixture("dashboard.json");
            int status = h.containsKey("location") ? 302 : json != null ? 200 : 404;
            return new Http.Response(status, h, json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
        };
        Argo argo = new Argo("SC00001", "parent.example", "s3cret&=", fake);
        List<Argo.Profile> profiles = argo.login();
        equal(2, profiles.size(), "enabled profiles");
        equal("ALICE", profiles.get(0).firstName, "first student");
        equal("ROSSI BIANCA MARIA", profiles.get(1).fullName, "second student");
        equal("9.9.9", argo.version, "app version");
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String[] c : calls) if (c[1].contains("/hop")) cookies.put(c[1].substring(c[1].lastIndexOf('/') + 1), c[2]);
        equal("a=1", cookies.get("hop1"), "cookies on hop 1");
        equal("-", cookies.get("hop2"), "no cookies on hop 2");
        equal("a=1; b=2", cookies.get("hop3"), "cookies on hop 3");
        Map<String, Object> d = argo.dashboard(profiles.get(1));
        equal(7.25, d.get("mediaGenerale"), "dashboard unwrapped");
        equal("token-bianca", calls.get(calls.size() - 1)[3], "dashboard uses the profile token");

        boolean failed = false;
        try {
            new Argo("SC00001", "parent.example", "wrong", fake).login();
        } catch (Argo.ArgoException e) {
            failed = e.getMessage().startsWith("SSO login failed");
        }
        check(failed, "wrong password fails");
    }
}
