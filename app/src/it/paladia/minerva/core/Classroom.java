package it.paladia.minerva.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client for the Google Classroom API, signed in with a child's school account: OAuth2 + PKCE through the browser, with a
 * loopback redirect (the "Desktop app" OAuth client), then the child's courses.
 *
 * READ-ONLY BY CONSTRUCTION, like Argo: only read-only scopes are requested and api() refuses anything outside
 * READ_ONLY_CALLS before a byte is sent. Never widen either without the PO's explicit ok.
 */
public final class Classroom {
    public static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String API_BASE = "https://classroom.googleapis.com/v1/";
    // The PO's "Desktop app" OAuth client. Its secret ships in the APK by design: Google treats it as public for installed apps.
    static final String CLIENT_ID = "";
    static final String CLIENT_SECRET = "";
    static final String SCOPES = "https://www.googleapis.com/auth/classroom.courses.readonly "
            + "https://www.googleapis.com/auth/classroom.coursework.me.readonly";
    public static final Set<String> READ_ONLY_CALLS = new HashSet<>(Arrays.asList("GET courses"));

    public static final class ClassroomException extends IOException {
        public ClassroomException(String message) {
            super(message);
        }
    }

    public static final class Course {
        public final String id;
        public final String name;

        Course(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final Http.Transport http;
    String accessToken;
    String refreshToken;

    public Classroom(Http.Transport http) {
        this.http = http;
    }

    public static boolean configured() {
        return !CLIENT_ID.isEmpty();
    }

    // --- OAuth ------------------------------------------------------------------------------------------------------

    public static String newVerifier() {
        return Argo.randomString(64);
    }

    public static String newState() {
        return Argo.randomString(22);
    }

    public static String authorizationUrl(String redirectUri, String verifier, String state) {
        return AUTH_URL + "?client_id=" + Argo.enc(CLIENT_ID) + "&redirect_uri=" + Argo.enc(redirectUri) + "&response_type=code"
                + "&scope=" + Argo.enc(SCOPES) + "&state=" + state + "&code_challenge=" + Argo.pkceChallenge(verifier)
                + "&code_challenge_method=S256&access_type=offline&prompt=select_account%20consent";
    }

    /**
     * Waits on the loopback socket for the browser's redirect and returns the authorization code. Requests without a
     * code or an error (a favicon) get a 404 and connections closed without a request (a browser preconnect) are
     * dropped: the wait goes on, until the socket's timeout.
     */
    public static String awaitCode(ServerSocket server, String state) throws IOException {
        while (true) {
            try (Socket s = server.accept()) {
                String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
                if (line == null) continue;
                String[] parts = line.split(" ");
                String target = parts.length == 3 && parts[0].equals("GET") ? parts[1] : "";
                String code = Argo.queryParam(target, "code");
                String error = Argo.queryParam(target, "error");
                if (code == null && error == null) {
                    reply(s, "404 Not Found", "");
                    continue;
                }
                boolean ok = code != null && state.equals(Argo.queryParam(target, "state"));
                reply(s, "200 OK", ok ? "Fatto! Puoi tornare a Minerva." : "Accesso non riuscito. Torna a Minerva.");
                if (error != null) throw new ClassroomException("Google sign-in: " + error);
                if (!ok) throw new ClassroomException("Google sign-in: state mismatch");
                return code;
            }
        }
    }

    private static void reply(Socket s, String status, String message) throws IOException {
        byte[] body = ("<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<title>Minerva</title><p style=\"font:18px sans-serif;margin:2em\">" + message + "</p>").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    public void exchangeCode(String code, String verifier, String redirectUri) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/x-www-form-urlencoded");
        String body = Argo.form("code", code, "client_id", CLIENT_ID, "client_secret", CLIENT_SECRET, "redirect_uri", redirectUri,
                "grant_type", "authorization_code", "code_verifier", verifier);
        Map<String, Object> data = Json.obj(Json.parse(http.request("POST", TOKEN_URL, headers, body.getBytes(StandardCharsets.UTF_8)).text()));
        if (data.containsKey("error") || Json.str(data, "access_token").isEmpty()) {
            throw new ClassroomException("Google token: " + (data.containsKey("error") ? data.get("error") : "no access_token"));
        }
        accessToken = Json.str(data, "access_token");
        refreshToken = Json.str(data, "refresh_token");
    }

    // --- API --------------------------------------------------------------------------------------------------------

    Map<String, Object> api(String method, String path, String query) throws IOException {
        if (!READ_ONLY_CALLS.contains(method + " " + path)) throw new ClassroomException("Refused " + method + " " + path + ": Minerva is read-only on Classroom");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json");
        headers.put("authorization", "Bearer " + accessToken);
        Http.Response r = http.request(method, API_BASE + path + (query.isEmpty() ? "" : "?" + query), headers, null);
        if (r.status >= 400) throw new ClassroomException(method + " " + path + ": HTTP " + r.status + errorMessage(r));
        return Json.obj(Json.parse(r.text()));
    }

    /** Google's reason for a refusal (" The caller does not have permission"), or "" when the body is not Google's JSON. */
    static String errorMessage(Http.Response r) {
        try {
            String msg = Json.str(Json.obj(Json.obj(Json.parse(r.text())), "error"), "message");
            return msg.isEmpty() ? "" : " " + msg;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** The child's active courses, every page. */
    public List<Course> courses() throws IOException {
        List<Course> courses = new ArrayList<>();
        String page = "";
        do {
            Map<String, Object> data = api("GET", "courses", "studentId=me&courseStates=ACTIVE&pageSize=100" + (page.isEmpty() ? "" : "&pageToken=" + Argo.enc(page)));
            for (Object c : Json.list(data, "courses")) courses.add(new Course(Json.str(Json.obj(c), "id"), Json.str(Json.obj(c), "name")));
            page = Json.str(data, "nextPageToken");
        } while (!page.isEmpty());
        return courses;
    }
}
