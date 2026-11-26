package com.zgenmedia.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Setting up a TV from any phone's browser.
 *
 * Typing a long add-on link with a remote is misery, and not everyone has an Android
 * phone to set Phone TV up on and transfer from. So, while its screen is open, the TV
 * serves one small web page to the home network. Any phone or computer (iPhones too)
 * opens the address the TV shows, enters the TV's code, and pastes what the TV should
 * have: add-on links, a TMDB key, a Trakt name and lists.
 *
 * It listens only while the user keeps the screen open, takes nothing without the code
 * shown on the TV, gives up after a few wrong codes, and can only ever fill in those
 * few fields. Sign-ins don't go through it: Trakt, Plex and Stremio already sign in
 * with a code entered on the phone.
 */
final class BrowserSetup {
    static final int PORT = 41501;
    private static final int MAX_WRONG = 5, MAX_BODY = 64 * 1024;

    interface Receiver {
        /** A form arrived with the right code. Called on the network thread. */
        void received(Map<String, String> fields);
        /** Too many wrong codes: the page has shut. Called on the network thread. */
        void locked();
    }

    private ServerSocket server;

    /** Starts serving. Returns the port (the usual one unless something else holds it). */
    int start(final String code, final String deviceName, final Receiver receiver) throws IOException {
        stop();
        final ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try { socket.bind(new InetSocketAddress(PORT)); }
        catch (IOException taken) { socket.bind(new InetSocketAddress(0)); }
        server = socket;
        new Thread(() -> {
            int wrong = 0;
            while (server == socket) {
                try (Socket peer = socket.accept()) {
                    peer.setSoTimeout(10_000);
                    Request r = read(peer.getInputStream());
                    OutputStream out = peer.getOutputStream();
                    if (r == null) { reply(out, "400 Bad Request", "Bad request"); continue; }
                    if ("GET".equals(r.method)) { reply(out, "200 OK", page(deviceName, null, false)); continue; }
                    Map<String, String> form = form(r.body);
                    String given = String.valueOf(form.get("code")).replaceAll("\\D", "");
                    if (!code.equals(given)) {
                        if (++wrong >= MAX_WRONG) {
                            reply(out, "403 Forbidden", page(deviceName, "Too many wrong codes. Open the setup screen on the TV again for a new one.", false));
                            receiver.locked();
                            break;
                        }
                        reply(out, "200 OK", page(deviceName, "That code doesn't match the one on the TV. Check it and send again.", false));
                        continue;
                    }
                    form.remove("code");
                    receiver.received(form);
                    reply(out, "200 OK", page(deviceName, "Sent. Look at the TV: it's adding what you sent. You can send more.", true));
                } catch (IOException oneVisitor) {
                    // A dropped connection or the socket closing: the loop condition decides what's next.
                }
            }
            if (server == socket) stop();
        }, "phonetv-browser-setup").start();
        return socket.getLocalPort();
    }

    void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { /* already closed */ }
    }

    // ------------------------------------------------------------------- http

    private static final class Request {
        String method = "";
        String body = "";
    }

    /** The little of HTTP this needs: the method, and a form body when there is one. */
    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0; // how much of "\r\n\r\n" has just gone by
        for (int c; matched < 4 && (c = in.read()) != -1; ) {
            head.write(c);
            matched = (c == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (c == '\r' ? 1 : 0);
            if (head.size() > 16 * 1024) return null;
        }
        String[] lines = head.toString("UTF-8").split("\r\n");
        if (lines.length == 0 || !lines[0].contains(" ")) return null;
        Request r = new Request();
        r.method = lines[0].substring(0, lines[0].indexOf(' '));
        int length = 0;
        for (String line : lines) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                try { length = Integer.parseInt(line.substring(15).trim()); } catch (NumberFormatException e) { return null; }
            }
        }
        if (length < 0 || length > MAX_BODY) return null;
        byte[] body = new byte[length];
        int got = 0;
        while (got < length) {
            int n = in.read(body, got, length - got);
            if (n < 0) break;
            got += n;
        }
        r.body = new String(body, 0, got, StandardCharsets.UTF_8);
        return r;
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try { out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim()); }
            catch (java.io.UnsupportedEncodingException | IllegalArgumentException skip) { /* a mangled field is left out */ }
        }
        return out;
    }

    private static void reply(OutputStream out, String status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The one page: a form, with a line at the top saying how the last send went. */
    private static String page(String deviceName, String message, boolean good) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Set up Phone TV</title><style>"
                + "body{margin:0;background:#0b0c10;color:#eef0f5;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:20px 16px 40px}"
                + "main{max-width:520px;margin:0 auto}h1{font-size:24px;margin:0 0 4px}p{color:#a3a9b8;margin:6px 0 16px}"
                + "label{display:block;font-weight:600;margin:18px 0 6px}small{display:block;color:#6f7688;font-weight:400;margin-top:2px}"
                + "input,textarea{width:100%;box-sizing:border-box;background:#161922;color:#eef0f5;border:1px solid #242836;border-radius:12px;padding:12px;font:inherit}"
                + "textarea{min-height:110px}button{margin-top:22px;width:100%;border:0;border-radius:999px;padding:14px;font:inherit;font-weight:700;background:#eef0f5;color:#0b0c10}"
                + ".msg{border-radius:12px;padding:12px 14px;margin:14px 0;background:#3a1f25;color:#ffb3c0}.msg.ok{background:#16332a;color:#8ff0c2}"
                + ".tip{border:1px solid #242836;border-radius:12px;padding:12px 14px;margin-top:26px;color:#a3a9b8;font-size:14px}"
                + "</style></head><body><main><h1>Set up Phone TV</h1><p>Sending to <b>" + esc(deviceName) + "</b>. Fill in only what you want to send.</p>"
                + (message == null ? "" : "<div class=\"msg" + (good ? " ok" : "") + "\">" + esc(message) + "</div>")
                + "<form method=\"post\" action=\"/\">"
                + "<label>Code on the TV<input name=\"code\" inputmode=\"numeric\" autocomplete=\"off\" required placeholder=\"6 digits\"></label>"
                + "<label>Add-on links<small>One per line. Stremio and Nuvio add-on (manifest) links both work.</small><textarea name=\"addons\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"></textarea></label>"
                + "<label>TMDB key<small>Optional. The \"API Key\" from themoviedb.org.</small><input name=\"tmdb\" autocapitalize=\"off\" autocorrect=\"off\"></label>"
                + "<label>Trakt username<small>Optional, for a public profile. To sign in instead, choose Trakt on the TV and enter its code at trakt.tv/activate.</small><input name=\"traktUser\" autocapitalize=\"off\" autocorrect=\"off\"></label>"
                + "<label>Trakt list links<small>Optional. One per line.</small><textarea name=\"traktLists\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"></textarea></label>"
                + "<button type=\"submit\">Send to the TV</button></form>"
                + "<div class=\"tip\"><b>Signing in</b> to Trakt, Plex or Stremio doesn't happen here. Choose it on the TV: it shows a short code to enter on this phone, and that works from an iPhone as well.</div>"
                + "</main></body></html>";
    }
}
