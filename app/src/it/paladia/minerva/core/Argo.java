package it.paladia.minerva.core;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client for the Argo DidUp Famiglia app API (unofficial, reverse-engineered). Mirrors the Python client of the Paladia project:
 * OAuth2 + PKCE following the SSO redirects by hand, app login keeping every student profile, GET profilo for the
 * name, POST dashboard/dashboard per profile.
 *
 * READ-ONLY BY CONSTRUCTION (never write anything on Argo): api() refuses anything outside
 * READ_ONLY_CALLS before a byte is sent. Never widen it without the PO's explicit ok.
 */
public final class Argo {
    public static final String API_BASE = "https://www.portaleargo.it/appfamiglia/api/rest/";
    public static final String OAUTH_AUTH_URL = "https://auth.portaleargo.it/oauth2/auth";
    public static final String OAUTH_TOKEN_URL = "https://auth.portaleargo.it/oauth2/token";
    public static final String SSO_LOGIN_URL = "https://www.portaleargo.it/auth/sso/login";
    static final String CLIENT_ID = "72fd6dea-d0ab-4bb9-8eaa-3ac24c84886c";
    static final String REDIRECT_URI = "it.argosoft.didup.famiglia.new://login-callback";
    static final String SCOPES = "openid offline profile user.roles argo";
    static final String APP_LOOKUP_URL = "https://itunes.apple.com/lookup?bundleId=it.argosoft.didup.famiglia.new";
    static final String DEFAULT_VERSION = "1.29.2";
    static final String FIRST_UPDATE = "2000-01-01 00:00:00.000";
    public static final Set<String> READ_ONLY_CALLS = new HashSet<>(Arrays.asList("POST login", "GET profilo", "POST dashboard/dashboard"));

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class ArgoException extends IOException {
        public ArgoException(String message) {
            super(message);
        }
    }

    /** One student reachable from the account. */
    public static final class Profile {
        public final String token;
        public final String codMin;
        public final Map<String, Object> options;
        public String firstName = "";
        public String lastName = "";
        public String fullName = "";

        Profile(String token, String codMin, Map<String, Object> options) {
            this.token = token;
            this.codMin = codMin;
            this.options = options;
        }

        /** "Alice" for "ROSSI ALICE": the first name with a capital initial, for the screen. */
        public String displayName() {
            String n = firstName.isEmpty() ? fullName : firstName;
            StringBuilder b = new StringBuilder();
            for (String w : n.toLowerCase(Locale.ITALIAN).split(" ")) {
                if (w.isEmpty()) continue;
                if (b.length() > 0) b.append(' ');
                b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            return b.toString();
        }
    }

    private final String school;
    private final String username;
    private final String password;
    private final Http.Transport http;
    String version;
    String accessToken;
    long expiresAt;  // epoch millis, 0 = unknown

    public Argo(String school, String username, String password, Http.Transport http) {
        this.school = school;
        this.username = username;
        this.password = password;
        this.http = http;
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    static String randomString(int length) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < length; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }

    static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String queryParam(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return null;
    }

    static List<String> cookiePairs(Http.Response r) {
        List<String> pairs = new ArrayList<>();
        for (String v : r.all("set-cookie")) {
            String pair = v.split(";", 2)[0].trim();
            if (!pair.isEmpty()) pairs.add(pair);
        }
        return pairs;
    }

    static String form(String... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (b.length() > 0) b.append('&');
            b.append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return b.toString();
    }

    static String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(millis));
    }

    // --- OAuth ------------------------------------------------------------------------------------------------------

    public List<Profile> login() throws IOException {
        if (version == null) version = appVersion();
        String verifier = randomString(43);
        String code = authorizationCode(verifier);
        exchangeCode(code, verifier);
        List<Profile> profiles = appLogin();
        for (Profile p : profiles) loadStudent(p);
        return profiles;
    }

    String appVersion() {
        try {
            Map<String, Object> data = Json.obj(Json.parse(http.request("GET", APP_LOOKUP_URL, null, null).text()));
            List<Object> results = Json.list(data, "results");
            String v = results.isEmpty() ? "" : Json.str(Json.obj(results.get(0)), "version");
            return v.isEmpty() ? DEFAULT_VERSION : v;
        } catch (IOException | RuntimeException e) {
            return DEFAULT_VERSION;
        }
    }

    String authorizationUrl(String verifier) {
        return OAUTH_AUTH_URL + "?redirect_uri=" + enc(REDIRECT_URI) + "&client_id=" + CLIENT_ID + "&response_type=code&prompt=login"
                + "&state=" + randomString(22) + "&nonce=" + randomString(22) + "&scope=" + enc(SCOPES)
                + "&code_challenge=" + pkceChallenge(verifier) + "&code_challenge_method=S256";
    }

    String authorizationCode(String verifier) throws IOException {
        List<String> cookies = new ArrayList<>();
        Http.Response start = http.request("GET", authorizationUrl(verifier), new LinkedHashMap<>(), null);
        cookies.addAll(cookiePairs(start));
        String location = start.header("location");
        String challenge = location == null ? null : queryParam(location, "login_challenge");
        if (challenge == null) throw new ArgoException("OAuth start: no login_challenge (HTTP " + start.status + ")");

        Map<String, String> formHeaders = new LinkedHashMap<>();
        formHeaders.put("content-type", "application/x-www-form-urlencoded");
        String body = form("challenge", challenge, "client_id", CLIENT_ID, "prefill", "false", "famiglia_customer_code", school,
                "username", username, "password", password, "login", "true");
        Http.Response sso = http.request("POST", SSO_LOGIN_URL, formHeaders, body.getBytes(StandardCharsets.UTF_8));
        String url = sso.header("location");
        if (url == null) throw new ArgoException("SSO login failed: wrong credentials or changed flow (HTTP " + sso.status + ")");

        // Post-login redirect chain: cookies only on the 1st and 3rd hop, as the app does.
        boolean[] withCookies = {true, false, true};
        for (int hop = 1; hop <= 3; hop++) {
            Map<String, String> headers = new LinkedHashMap<>();
            if (withCookies[hop - 1]) headers.put("cookie", String.join("; ", cookies));
            Http.Response r = http.request("GET", url, headers, null);
            if (hop == 1) cookies.addAll(cookiePairs(r));
            url = r.header("location");
            if (url == null) throw new ArgoException("OAuth redirect chain broken at hop " + hop + " (HTTP " + r.status + ")");
        }
        String code = queryParam(url, "code");
        if (code == null) throw new ArgoException("OAuth: no code in the final redirect");
        return code;
    }

    void exchangeCode(String code, String verifier) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/x-www-form-urlencoded");
        String body = form("code", code, "grant_type", "authorization_code", "redirect_uri", REDIRECT_URI, "code_verifier", verifier,
                "client_id", CLIENT_ID);
        Map<String, Object> data = Json.obj(Json.parse(http.request("POST", OAUTH_TOKEN_URL, headers, body.getBytes(StandardCharsets.UTF_8)).text()));
        if (data.containsKey("error") || Json.str(data, "access_token").isEmpty()) {
            throw new ArgoException("OAuth token: " + (data.containsKey("error") ? data.get("error") : "no access_token"));
        }
        accessToken = Json.str(data, "access_token");
        Object expiresIn = data.get("expires_in");
        expiresAt = expiresIn instanceof Double ? System.currentTimeMillis() + (long) ((Double) expiresIn * 1000) : 0;
    }

    // --- app API ----------------------------------------------------------------------------------------------------

    Map<String, String> headers(Profile profile) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("accept", "application/json");
        h.put("argo-client-version", version == null ? DEFAULT_VERSION : version);
        h.put("content-type", "application/json; charset=utf-8");
        h.put("authorization", "Bearer " + accessToken);
        if (expiresAt > 0) h.put("x-date-exp-auth", formatDate(expiresAt));
        if (profile != null) {
            h.put("x-auth-token", profile.token);
            h.put("x-cod-min", profile.codMin);
        }
        return h;
    }

    public Object api(String method, String path, Profile profile, String jsonBody) throws IOException {
        if (!READ_ONLY_CALLS.contains(method + " " + path)) throw new ArgoException("Refused " + method + " " + path + ": Minerva is read-only on Argo");
        byte[] body = jsonBody == null ? null : jsonBody.getBytes(StandardCharsets.UTF_8);
        Http.Response r = http.request(method, API_BASE + path, headers(profile), body);
        if (r.status >= 400) throw new ArgoException(method + " " + path + ": HTTP " + r.status);
        Object data = r.body.length == 0 ? null : Json.parse(r.text());
        if (data instanceof Map && Boolean.FALSE.equals(Json.obj(data).get("success"))) {
            String msg = Json.str(Json.obj(data), "msg");
            throw new ArgoException(method + " " + path + ": " + (msg.isEmpty() ? "request failed" : msg));
        }
        return data;
    }

    List<Profile> appLogin() throws IOException {
        String body = "{\"lista-opzioni-notifiche\":\"{}\",\"lista-x-auth-token\":\"[]\",\"clientID\":" + Json.quote(randomString(163)) + "}";
        List<Object> entries = Json.list(Json.obj(api("POST", "login", null, body)), "data");
        if (entries.isEmpty()) throw new ArgoException("App login: no profile returned");
        List<Profile> profiles = new ArrayList<>();
        for (Object e : entries) {
            Map<String, Object> entry = Json.obj(e);
            if (Boolean.TRUE.equals(entry.get("profiloDisabilitato"))) continue;
            Map<String, Object> options = new LinkedHashMap<>();
            for (Object o : Json.list(entry, "opzioni")) {
                Map<String, Object> opt = Json.obj(o);
                if (opt.containsKey("chiave")) options.put(Json.str(opt, "chiave"), opt.get("valore"));
            }
            profiles.add(new Profile(Json.str(entry, "token"), Json.str(entry, "codMin"), options));
        }
        return profiles;
    }

    void loadStudent(Profile p) throws IOException {
        Map<String, Object> student = Json.obj(Json.obj(Json.obj(api("GET", "profilo", p, null)), "data"), "alunno");
        p.firstName = Json.str(student, "nome");
        p.lastName = Json.str(student, "cognome");
        String full = Json.str(student, "nominativo");
        p.fullName = full.isEmpty() ? (p.lastName + " " + p.firstName).trim() : full;
    }

    public Map<String, Object> dashboard(Profile p) throws IOException {
        StringBuilder options = new StringBuilder("{");
        for (Map.Entry<String, Object> o : p.options.entrySet()) {
            if (options.length() > 1) options.append(',');
            options.append(Json.quote(o.getKey())).append(':').append(o.getValue() == null ? "null" : o.getValue().toString());
        }
        options.append('}');
        String body = "{\"dataultimoaggiornamento\":" + Json.quote(FIRST_UPDATE) + ",\"opzioni\":" + Json.quote(options.toString()) + "}";
        return unwrapDashboard(api("POST", "dashboard/dashboard", p, body));
    }

    /** Argo wraps the dashboard as {"data": {"dati": [{...}]}}. */
    static Map<String, Object> unwrapDashboard(Object data) {
        Map<String, Object> m = Json.obj(data);
        Object content = m.containsKey("data") ? m.get("data") : m;
        Map<String, Object> c = Json.obj(content);
        if (c.containsKey("dati")) {
            List<Object> items = Json.list(c, "dati");
            return items.isEmpty() ? new LinkedHashMap<>() : Json.obj(items.get(0));
        }
        return c;
    }
}
