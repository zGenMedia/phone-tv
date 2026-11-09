package com.zgenmedia.tvhome;

import android.net.Uri;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to plex.tv and the user's Plex Media Server.
 *
 * Linking uses Plex's PIN flow (the same one TVs use): we request a short code,
 * the user enters it at plex.tv/link, and we poll until an auth token appears.
 * No password ever touches this app.
 *
 * All methods block; call them from a background thread.
 */
final class PlexClient {
    static final String PACKAGE = "com.plexapp.android";
    static final String LINK_URL = "https://plex.tv/link";

    private static final String PLEX_TV = "https://plex.tv/api/v2";
    private static final int PROBE_TIMEOUT_MS = 4_000;

    static final class Pin {
        final long id; final String code;
        Pin(long id, String code) { this.id = id; this.code = code; }
    }

    static final class Server {
        String name, uri, token, machineId;
        boolean reachable;
    }

    private final Prefs prefs;

    PlexClient(Prefs prefs) { this.prefs = prefs; }

    /** Linked = the user approved a PIN. A server may still be unknown or offline. */
    boolean isLinked() { return prefs.plexAccountToken() != null; }

    boolean hasServer() { return prefs.plexServerUri() != null && prefs.plexServerToken() != null; }

    /**
     * Finds the account's server and saves it. Called after linking and again
     * whenever the saved address stops answering (new IP, remote access toggled).
     * @return the server, or null if the account has none.
     */
    Server refreshServer() throws IOException, JSONException {
        Server s = discoverServer(prefs.plexAccountToken());
        if (s != null) save(prefs.plexAccountToken(), s);
        return s;
    }

    // ---------------------------------------------------------------- linking

    Pin createPin() throws IOException, JSONException {
        JSONObject o = json(Http.post(PLEX_TV + "/pins?strong=false", headers(null)));
        return new Pin(o.getLong("id"), o.getString("code"));
    }

    /** @return the account token once the user has entered the code, otherwise null. */
    String pollPin(long pinId) throws IOException, JSONException {
        JSONObject o = json(Http.get(PLEX_TV + "/pins/" + pinId, headers(null), 10_000));
        String token = o.optString("authToken", "");
        return token.isEmpty() || "null".equals(token) ? null : token;
    }

    /**
     * Picks the account's best server and the first connection that answers.
     * Owned servers win over shared ones; local connections are tried before
     * remote, and Plex relay is the last resort (it's bandwidth-capped).
     */
    Server discoverServer(String accountToken) throws IOException, JSONException {
        JSONArray resources = new JSONArray(new String(Http.get(
                PLEX_TV + "/resources?includeHttps=1&includeRelay=1", headers(accountToken), 15_000),
                StandardCharsets.UTF_8));
        JSONObject best = null;
        for (int i = 0; i < resources.length(); i++) {
            JSONObject r = resources.getJSONObject(i);
            if (!r.optString("provides").contains("server")) continue;
            if (best == null || (r.optBoolean("owned") && !best.optBoolean("owned"))) best = r;
        }
        if (best == null) return null;

        String token = best.optString("accessToken", accountToken);
        JSONArray conns = best.optJSONArray("connections");
        if (conns == null) return null;
        Server fallback = null;
        int fallbackPass = -1;
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < conns.length(); i++) {
                JSONObject c = conns.getJSONObject(i);
                boolean local = c.optBoolean("local"), relay = c.optBoolean("relay");
                boolean wanted = pass == 0 ? local && !relay : pass == 1 ? !local && !relay : relay;
                String uri = c.optString("uri");
                // Cleartext is disabled app-wide; Plex offers https (plex.direct) for every connection.
                if (!wanted || !uri.startsWith("https://")) continue;
                Server s = new Server();
                s.name = best.optString("name", "Plex");
                s.uri = uri;
                s.token = token;
                s.machineId = best.optString("clientIdentifier");
                if (reachable(uri, token)) { s.reachable = true; return s; }
                // Keep the best remote address even if it didn't answer this time, so
                // the link is saved and the next refresh simply retries.
                // Prefer a remote (pass 1) address as the fallback: it works away from home too.
                if (fallback == null || (pass == 1 && fallbackPass != 1)) { fallback = s; fallbackPass = pass; }
            }
        }
        return fallback;
    }

    void save(String accountToken, Server s) { prefs.savePlex(accountToken, s.uri, s.token, s.machineId, s.name); }

    // ---------------------------------------------------------------- library

    List<MediaItem> continueWatching() throws IOException, JSONException {
        if (!hasServer() && refreshServer() == null) throw new IOException("no Plex Media Server on this account");
        try {
            return metadata("/library/onDeck?X-Plex-Container-Size=20");
        } catch (IOException first) {
            // The saved address may be stale; look the server up again and retry once.
            if (refreshServer() == null) throw first;
            return metadata("/library/onDeck?X-Plex-Container-Size=20");
        }
    }

    List<MediaItem> recentlyAdded() throws IOException, JSONException {
        return metadata("/library/recentlyAdded?X-Plex-Container-Size=30");
    }

    /**
     * Deep link that makes the Plex app start (or resume) playback. Plex changed
     * this format in 2025: playback now needs autoPlay=1 in the URI instead of
     * the old START_PLAYBACK intent extra.
     */
    static String playUri(MediaItem m) {
        return "plex://server://" + m.plexMachineId + "/com.plexapp.plugins.library/library/metadata/" + m.id
                + "?viewOffset=" + m.viewOffsetMs + "&autoPlay=1";
    }

    // ----------------------------------------------------- Plex Home profiles

    static final class HomeUser {
        String id, title;
        boolean needsPin, admin;
    }

    /** Profiles in the account's Plex Home. One entry (or none) means there's nothing to switch. */
    List<HomeUser> homeUsers() throws IOException {
        List<HomeUser> out = new ArrayList<>();
        String xml = new String(Http.get("https://plex.tv/api/home/users", xmlHeaders(prefs.plexOwnerToken()), 15_000), StandardCharsets.UTF_8);
        try {
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(new java.io.StringReader(xml));
            for (int e = p.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e != org.xmlpull.v1.XmlPullParser.START_TAG || !"User".equals(p.getName())) continue;
                HomeUser u = new HomeUser();
                u.id = p.getAttributeValue(null, "id");
                u.title = p.getAttributeValue(null, "title");
                u.needsPin = "1".equals(p.getAttributeValue(null, "protected"));
                u.admin = "1".equals(p.getAttributeValue(null, "admin"));
                if (u.id != null && u.title != null) out.add(u);
            }
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new IOException("unexpected reply from plex.tv", e);
        }
        return out;
    }

    /** @return that profile's token. A wrong or missing PIN comes back as an HTTP 401/403. */
    String switchUser(HomeUser user, String pin) throws IOException {
        String url = "https://plex.tv/api/home/users/" + user.id + "/switch" + (pin == null || pin.isEmpty() ? "" : "?pin=" + Uri.encode(pin));
        String xml = new String(Http.post(url, xmlHeaders(prefs.plexOwnerToken())), StandardCharsets.UTF_8);
        try {
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(new java.io.StringReader(xml));
            for (int e = p.getEventType(); e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                String token = p.getAttributeValue(null, "authenticationToken");
                if (token == null) token = p.getAttributeValue(null, "authToken");
                if (token != null && !token.isEmpty()) return token;
                break; // the token lives on the root element
            }
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new IOException("unexpected reply from plex.tv", e);
        }
        throw new IOException("plex.tv didn't return a token for " + user.title);
    }

    private Map<String, String> xmlHeaders(String token) {
        Map<String, String> h = headers(token);
        h.put("Accept", "application/xml");
        return h;
    }

    /** Plex Web link to an item; the Plex app opens these when installed. */
    static String webUrl(MediaItem m) {
        return "https://app.plex.tv/desktop/#!/server/" + m.plexMachineId
                + "/details?key=" + Uri.encode("/library/metadata/" + m.id);
    }

    private List<MediaItem> metadata(String path) throws IOException, JSONException {
        String base = prefs.plexServerUri(), token = prefs.plexServerToken();
        JSONObject container = json(Http.get(base + path, headers(token), 12_000)).optJSONObject("MediaContainer");
        List<MediaItem> out = new ArrayList<>();
        JSONArray items = container == null ? null : container.optJSONArray("Metadata");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            // Films and TV only: Plex libraries can also hold music, music videos, photos and clips.
            String kind = o.optString("type");
            boolean musicVideo = "clip".equals(kind) || "music".equalsIgnoreCase(o.optString("librarySectionTitle"))
                    || o.optString("subtype").contains("music");
            if (musicVideo || !("movie".equals(kind) || "episode".equals(kind) || "season".equals(kind) || "show".equals(kind))) continue;
            MediaItem m = new MediaItem(MediaItem.Source.PLEX);
            m.id = o.optString("ratingKey");
            m.type = o.optString("type");
            m.summary = o.optString("summary", "");
            m.year = o.has("year") ? String.valueOf(o.optInt("year")) : "";
            m.plexBaseUri = base;
            m.plexToken = token;
            m.plexMachineId = prefs.plexServerId();

            switch (m.type) {
                case "episode":
                    m.title = o.optString("grandparentTitle", o.optString("title"));
                    m.subtitle = "S" + o.optInt("parentIndex") + " · E" + o.optInt("index") + " · " + o.optString("title");
                    m.posterRef = o.optString("grandparentThumb", o.optString("thumb"));
                    m.backdropRef = o.optString("grandparentArt", o.optString("art"));
                    break;
                case "season":
                    m.title = o.optString("parentTitle", o.optString("title"));
                    m.subtitle = o.optString("title");
                    m.posterRef = o.optString("thumb", o.optString("parentThumb"));
                    m.backdropRef = o.optString("art", o.optString("parentArt"));
                    break;
                default:
                    m.title = o.optString("title");
                    m.posterRef = o.optString("thumb");
                    m.backdropRef = o.optString("art");
            }
            long duration = o.optLong("duration"), offset = o.optLong("viewOffset");
            if (duration > 0 && offset > 0) m.progressPercent = (int) Math.min(100, offset * 100 / duration);
            m.viewOffsetMs = offset;
            if (!m.id.isEmpty() && !m.title.isEmpty()) out.add(m);
        }
        return out;
    }

    private boolean reachable(String uri, String token) {
        try { Http.get(uri + "/identity", headers(token), PROBE_TIMEOUT_MS); return true; }
        catch (IOException e) { return false; }
    }

    private Map<String, String> headers(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("X-Plex-Product", "Phone TV");
        h.put("X-Plex-Version", BuildConfig.VERSION_NAME);
        h.put("X-Plex-Client-Identifier", prefs.plexClientId());
        h.put("X-Plex-Platform", "Android");
        h.put("X-Plex-Device-Name", "Phone TV (" + Build.MODEL + ")");
        if (token != null) h.put("X-Plex-Token", token);
        return h;
    }

    private static JSONObject json(byte[] body) throws JSONException {
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
    }
}
