package com.zgenmedia.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

/**
 * Minimal blocking HTTP client. Call from a background thread only.
 *
 * We deliberately avoid OkHttp/Retrofit: the app makes a handful of simple GETs,
 * and keeping zero dependencies keeps the APK tiny (around 150 KB) for older devices.
 */
final class Http {
    static final String USER_AGENT = "PhoneTV/" + BuildConfig.VERSION_NAME + " (Android; +https://zgenmedia.com)";
    private static final int MAX_REDIRECTS = 5;

    /** Thrown for any non-2xx response so callers can surface the status code. */
    static final class StatusException extends IOException {
        final int code;
        StatusException(int code, String url) { super("HTTP " + code + " for " + url); this.code = code; }
    }

    private Http() {}

    static byte[] get(String url) throws IOException {
        return request("GET", url, Collections.emptyMap(), 15_000, 0);
    }

    static byte[] get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        return request("GET", url, headers, timeoutMs, 0);
    }

    static byte[] post(String url, Map<String, String> headers) throws IOException {
        return request("POST", url, headers, 15_000, 0);
    }

    private static byte[] request(String method, String url, Map<String, String> headers,
                                  int timeoutMs, int redirects) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs + 5_000);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if ("POST".equals(method)) { c.setDoOutput(true); c.setFixedLengthStreamingMode(0); }

            int code = c.getResponseCode();
            // HttpURLConnection won't follow redirects that switch protocol; handle those here.
            if (code >= 300 && code < 400 && redirects < MAX_REDIRECTS) {
                String location = c.getHeaderField("Location");
                if (location != null) {
                    return request(method, new URL(new URL(url), location).toString(), headers, timeoutMs, redirects + 1);
                }
            }
            if (code < 200 || code >= 300) throw new StatusException(code, url);
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
