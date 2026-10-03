package it.paladia.minerva.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One HTTP request; redirects are never followed (the OAuth chain reads Location by hand), cookies are handled by the caller. */
public final class Http {
    public interface Transport {
        Response request(String method, String url, Map<String, String> headers, byte[] body) throws IOException;
    }

    public static final class Response {
        public final int status;
        public final Map<String, List<String>> headers;  // lower-case names
        public final byte[] body;

        public Response(int status, Map<String, List<String>> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase());
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        public List<String> all(String name) {
            List<String> v = headers.get(name.toLowerCase());
            return v == null ? new ArrayList<>() : v;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public static final Transport DEFAULT = (method, url, headers, body) -> {
        @SuppressWarnings("deprecation")  // new URL() is lenient with redirect URLs that URI.create() would reject
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
        }
        int status = c.getResponseCode();
        Map<String, List<String>> collected = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
            if (h.getKey() != null) collected.put(h.getKey().toLowerCase(), h.getValue());
        }
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream stream = in) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = stream.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
        }
        c.disconnect();
        return new Response(status, collected, buf.toByteArray());
    };

    private Http() {
    }
}
