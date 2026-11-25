package com.zgenmedia.tvhome;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional rows built from public Trakt lists, for users who want to curate
 * their own home screen (e.g. Black cinema, Bollywood, anime, festival picks).
 *
 * Also the user's Trakt account, when they sign in: their watch history and
 * watchlist for the Library. Sign-in is Trakt's device flow, the same one
 * Nuvio uses: the TV shows a short code, the user enters it at
 * trakt.tv/activate on any phone or computer, and the app is let in. Phone TV
 * is registered with Trakt as a public (PKCE) app, so its Client ID below is
 * meant to be public and there is no secret to protect. Users who prefer can
 * still enter their own Client ID for list rows.
 *
 * Trakt doesn't serve artwork, so items are matched to metahub art by IMDb id,
 * the same source the built-in catalog uses. All methods block; call them
 * from a background thread.
 */
final class TraktClient {
    static final int MAX_LISTS = 5;
    /** Phone TV's own Trakt app (zGenMedia). Public by design; see the class note. */
    static final String APP_ID = "xMP2zUN8jBPGYd-GAjuUrfycdFD6W34bFEYW_3HpDUk";
    static final String ACTIVATE_URL = "https://trakt.tv/activate";
    private static final String API = "https://api.trakt.tv";

    /** A sign-in in progress: the code the user types, and how long it lasts. */
    static final class DeviceCode {
        String device, user, url;
        int intervalSec;
        long expiresAt;
    }

    /** The user said no at trakt.tv, or the code ran out. */
    static final class SignInEnded extends IOException {
        SignInEnded(String why) { super(why); }
    }
    private static final Pattern LIST_URL =
            Pattern.compile("trakt\\.tv/users/([^/?#]+)/(?:lists/([^/?#]+)|(watchlist))", Pattern.CASE_INSENSITIVE);

    /** A parsed list URL: either /users/{user}/lists/{slug} or /users/{user}/watchlist. */
    static final class ListRef {
        final String user, slug; // slug is null for a watchlist
        ListRef(String user, String slug) { this.user = user; this.slug = slug; }
        String cacheKey() { return "trakt_" + (user + "_" + (slug == null ? "watchlist" : slug)).replaceAll("[^A-Za-z0-9_-]", "_"); }
    }

    static final class TraktRow {
        final String title;
        final List<MediaItem> items;
        TraktRow(String title, List<MediaItem> items) { this.title = title; this.items = items; }
    }

    private final File dir;
    private final Prefs prefs;

    TraktClient(Context context, Prefs prefs) { this.dir = context.getFilesDir(); this.prefs = prefs; }

    boolean isConfigured() { return !refs().isEmpty(); }

    boolean signedIn() { return !prefs.traktAccess().isEmpty(); }

    /** The user's own Client ID if they entered one, otherwise Phone TV's. */
    private String clientId() { return prefs.traktClientId().isEmpty() ? APP_ID : prefs.traktClientId(); }

    // ------------------------------------------------------------- sign-in

    DeviceCode startSignIn() throws IOException, JSONException {
        JSONObject o = new JSONObject(post("/oauth/device/code", new JSONObject().put("client_id", APP_ID)));
        DeviceCode c = new DeviceCode();
        c.device = o.getString("device_code");
        c.user = o.getString("user_code");
        c.url = o.optString("verification_url", ACTIVATE_URL);
        c.intervalSec = Math.max(1, o.optInt("interval", 5));
        c.expiresAt = System.currentTimeMillis() + o.optInt("expires_in", 600) * 1000L;
        return c;
    }

    /**
     * One check on a sign-in. True once the user has approved it (the account is then saved),
     * false while Trakt is still waiting for them.
     * @throws SignInEnded when the code expired or was refused; start again.
     */
    boolean pollSignIn(DeviceCode c) throws IOException, JSONException {
        if (System.currentTimeMillis() > c.expiresAt) throw new SignInEnded("expired");
        String reply;
        try {
            reply = post("/oauth/device/token", new JSONObject().put("code", c.device).put("client_id", APP_ID));
        } catch (Http.StatusException e) {
            switch (e.code) {
                case 400: case 429: return false;          // still waiting, or asked to slow down
                case 404: case 409: case 410: case 418: throw new SignInEnded("code " + e.code);
                default: throw e;
            }
        }
        keep(new JSONObject(reply));
        try {
            JSONObject me = new JSONObject(authorized("/users/settings")).optJSONObject("user");
            if (me != null) prefs.setTraktAccount(me.optString("username"));
        } catch (IOException | JSONException ignored) { /* the name is only for the Settings line */ }
        return true;
    }

    void signOut() {
        String token = prefs.traktAccess();
        prefs.clearTraktSignIn();
        if (token.isEmpty()) return;
        try { post("/oauth/revoke", new JSONObject().put("token", token).put("client_id", APP_ID)); }
        catch (IOException | JSONException ignored) { /* signed out here either way */ }
    }

    private void keep(JSONObject token) {
        long created = token.optLong("created_at", System.currentTimeMillis() / 1000L);
        prefs.saveTraktSignIn(token.optString("access_token"), token.optString("refresh_token"),
                (created + token.optLong("expires_in", 86_400)) * 1000L);
    }

    /** A GET as the signed-in user, renewing the sign-in first when it is about to lapse. */
    private String authorized(String path) throws IOException, JSONException {
        if (System.currentTimeMillis() > prefs.traktExpires() - 60_000) renew();
        try {
            return get(path, prefs.traktAccess());
        } catch (Http.StatusException e) {
            if (e.code != 401) throw e;
            renew();
            return get(path, prefs.traktAccess());
        }
    }

    private void renew() throws IOException, JSONException {
        try {
            keep(new JSONObject(post("/oauth/token", new JSONObject().put("refresh_token", prefs.traktRefresh())
                    .put("client_id", APP_ID).put("grant_type", "refresh_token").put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob"))));
        } catch (Http.StatusException e) {
            // Refused for good (revoked at trakt.tv, or too old): sign out so Settings offers to sign in again.
            if (e.code == 400 || e.code == 401) prefs.clearTraktSignIn();
            throw e;
        }
    }

    /** Valid list references from the saved URLs, in the user's order. */
    List<ListRef> refs() {
        List<ListRef> out = new ArrayList<>();
        for (String line : prefs.traktLists().split("\n")) {
            ListRef r = parse(line.trim());
            if (r != null && out.size() < MAX_LISTS) out.add(r);
        }
        return out;
    }

    static ListRef parse(String url) {
        Matcher m = LIST_URL.matcher(url);
        if (!m.find()) return null;
        return new ListRef(m.group(1), m.group(3) != null ? null : m.group(2));
    }

    /**
     * Fetches every configured list. A list that fails falls back to its last
     * cached copy; if there's no cache either, the error is reported so the UI
     * can say which list broke instead of silently dropping it.
     */
    List<TraktRow> fetchAll(List<String> errors) {
        List<TraktRow> rows = new ArrayList<>();
        for (ListRef ref : refs()) {
            File cache = new File(dir, ref.cacheKey() + ".json");
            try {
                String title = ref.slug == null ? ref.user + "'s watchlist" : listName(ref);
                String path = ref.slug == null
                        ? "/users/" + ref.user + "/watchlist?extended=full"
                        : "/users/" + ref.user + "/lists/" + ref.slug + "/items?extended=full&limit=60&page=1";
                String items = get(path);
                JSONObject bundle = new JSONObject().put("title", title).put("items", new JSONArray(items));
                write(cache, bundle.toString());
                rows.add(new TraktRow(title, toItems(bundle.getJSONArray("items"))));
            } catch (IOException | JSONException e) {
                String reason = (ref.slug == null ? "watchlist" : ref.slug) + ": " + CatalogRepository.describe(e);
                try {
                    String cached = read(cache);
                    if (cached != null) {
                        JSONObject bundle = new JSONObject(cached);
                        rows.add(new TraktRow(bundle.optString("title"), toItems(bundle.getJSONArray("items"))));
                        continue;
                    }
                } catch (IOException | JSONException ignored) { /* fall through to error */ }
                errors.add(reason);
            }
        }
        return rows;
    }

    /** Signed in, or a public profile's username. */
    boolean hasHistory() { return signedIn() || !prefs.traktUser().isEmpty(); }

    /**
     * The latest plays on the user's Trakt profile, which other apps (Stremio, Nuvio, Plex
     * scrobblers) write to. The profile must be public: Phone TV holds no Trakt login.
     */
    List<Library.Watch> history() throws IOException, JSONException {
        if (signedIn()) return parseHistory(authorized("/sync/history?limit=80&extended=full"));
        return parseHistory(get("/users/" + android.net.Uri.encode(prefs.traktUser()) + "/history?limit=80&extended=full"));
    }

    /**
     * Things paused part-way in any app that reports to Trakt (Stremio, Nuvio, Kodi…), newest
     * first, with how far along they are. Reading only: Phone TV doesn't write progress back.
     */
    List<MediaItem> paused() throws IOException, JSONException {
        JSONArray arr = new JSONArray(authorized("/sync/playback?extended=full&limit=30"));
        List<MediaItem> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            boolean movie = "movie".equals(o.optString("type"));
            JSONObject about = o.optJSONObject(movie ? "movie" : "show");
            JSONObject ids = about == null ? null : about.optJSONObject("ids");
            String imdb = ids == null ? "" : ids.optString("imdb", "");
            if (!imdb.startsWith("tt")) continue;
            MediaItem m = MediaItem.catalog(imdb, movie ? "movie" : "series", about.optString("title"),
                    about.isNull("year") ? "" : String.valueOf(about.optInt("year")));
            m.progressPercent = (int) Math.round(o.optDouble("progress", 0));
            JSONObject ep = o.optJSONObject("episode");
            m.subtitle = (ep == null ? "" : Library.episodeLabel(ep.optInt("season"), ep.optInt("number")) + "  ·  ") + "Trakt";
            out.add(m);
        }
        return out;
    }

    /** Movies and shows on the user's Trakt watchlist, most recently added first. */
    List<MediaItem> watchlist() throws IOException, JSONException {
        String path = "/watchlist?extended=full&sort_by=added&sort_how=desc&limit=100";
        String json = signedIn() ? authorized("/sync" + path)
                : get("/users/" + android.net.Uri.encode(prefs.traktUser()) + path);
        return toItems(new JSONArray(json));
    }

    /** Movies and shows in the user's Trakt collection. Needs a sign-in; a public profile has only the watchlist. */
    List<MediaItem> collection() throws IOException, JSONException {
        List<MediaItem> out = new ArrayList<>();
        if (!signedIn()) return out;
        for (String kind : new String[]{"movie", "show"}) {
            JSONArray arr = new JSONArray(authorized("/sync/collection/" + kind + "s?extended=full"));
            for (int i = 0; i < arr.length(); i++) arr.getJSONObject(i).put("type", kind); // the collection reply leaves it out
            out.addAll(toItems(arr));
        }
        return out;
    }

    /** Trakt's history reply, one entry per play, in the library's own shape. Plays without an IMDb id are skipped. */
    static List<Library.Watch> parseHistory(String json) throws JSONException {
        JSONArray plays = new JSONArray(json);
        java.text.SimpleDateFormat stamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        stamp.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        List<Library.Watch> out = new ArrayList<>();
        for (int i = 0; i < plays.length(); i++) {
            JSONObject play = plays.getJSONObject(i);
            boolean movie = "movie".equals(play.optString("type"));
            JSONObject about = play.optJSONObject(movie ? "movie" : "show");
            JSONObject ids = about == null ? null : about.optJSONObject("ids");
            String imdb = ids == null ? "" : ids.optString("imdb", "");
            if (!imdb.startsWith("tt")) continue;
            Library.Watch w = new Library.Watch();
            w.item = MediaItem.catalog(imdb, movie ? "movie" : "series", about.optString("title"),
                    about.isNull("year") ? "" : String.valueOf(about.optInt("year")));
            w.item.summary = about.optString("overview", "");
            w.via = "Trakt";
            JSONObject episode = play.optJSONObject("episode");
            if (episode != null) w.episode = Library.episodeLabel(episode.optInt("season"), episode.optInt("number"));
            String at = play.optString("watched_at");
            try { if (at.length() >= 19) w.at = stamp.parse(at.substring(0, 19)).getTime(); }
            catch (java.text.ParseException | NullPointerException ignored) { /* leave it undated */ }
            out.add(w);
        }
        return out;
    }

    private String listName(ListRef ref) throws IOException, JSONException {
        JSONObject meta = new JSONObject(get("/users/" + ref.user + "/lists/" + ref.slug));
        return meta.optString("name", ref.slug);
    }

    private String get(String path) throws IOException { return get(path, null); }

    private String get(String path, String bearer) throws IOException {
        return new String(Http.get(API + path, headers(bearer), 15_000), StandardCharsets.UTF_8);
    }

    private String post(String path, JSONObject body) throws IOException {
        return new String(Http.post(API + path, headers(null), body.toString()), StandardCharsets.UTF_8);
    }

    private Map<String, String> headers(String bearer) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("trakt-api-version", "2");
        // Requests made as the user go under Phone TV's app, which issued the sign-in.
        h.put("trakt-api-key", bearer == null ? clientId() : APP_ID);
        if (bearer != null) h.put("Authorization", "Bearer " + bearer);
        return h;
    }

    private static long stamp(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try { return f.parse(iso.substring(0, 19)).getTime(); }
        catch (java.text.ParseException e) { return 0; }
    }

    private static List<MediaItem> toItems(JSONArray arr) {
        List<MediaItem> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null) continue;
            String kind = entry.optString("type");
            JSONObject o = entry.optJSONObject(kind);
            if (o == null || !("movie".equals(kind) || "show".equals(kind))) continue;
            JSONObject ids = o.optJSONObject("ids");
            String imdb = ids == null ? "" : ids.optString("imdb", "");
            if (!imdb.startsWith("tt")) continue; // no IMDb id means no artwork to show

            MediaItem m = new MediaItem(MediaItem.Source.CATALOG);
            m.id = imdb;
            m.type = "show".equals(kind) ? "series" : "movie";
            m.title = o.optString("title");
            m.year = o.has("year") && !o.isNull("year") ? String.valueOf(o.optInt("year")) : "";
            m.summary = o.optString("overview", "");
            double rating = o.optDouble("rating", 0);
            m.rating = rating > 0 ? String.format(Locale.US, "%.1f", rating) : "";
            JSONArray g = o.optJSONArray("genres");
            if (g != null && g.length() > 0) m.genres = capitalize(g.optString(0)) + (g.length() > 1 ? ", " + capitalize(g.optString(1)) : "");
            m.posterRef = "https://images.metahub.space/poster/small/" + imdb + "/img";
            m.backdropRef = "https://images.metahub.space/background/medium/" + imdb + "/img";
            m.logoRef = "https://images.metahub.space/logo/medium/" + imdb + "/img";
            m.listedAt = stamp(entry.optString("listed_at", entry.optString("collected_at", entry.optString("last_collected_at"))));
            if (!m.title.isEmpty()) out.add(m);
        }
        return out;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('-', ' ');
    }

    private static String read(File f) throws IOException {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) { return new String(Http.readAll(in), StandardCharsets.UTF_8); }
    }

    private static void write(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(s.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { /* cache is best-effort */ }
    }
}
