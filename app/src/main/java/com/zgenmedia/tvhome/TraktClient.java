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
 * Off by default. Turning it on needs the user's own Trakt API Client ID
 * (free at trakt.tv/oauth/applications) plus one or more public list URLs, so
 * casual users never see it and the app ships with no shared API key.
 *
 * Trakt doesn't serve artwork, so items are matched to metahub art by IMDb id,
 * the same source the built-in catalog uses. All methods block; call them
 * from a background thread.
 */
final class TraktClient {
    static final int MAX_LISTS = 5;
    private static final String API = "https://api.trakt.tv";
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

    boolean isConfigured() { return !prefs.traktClientId().isEmpty() && !refs().isEmpty(); }

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

    private String listName(ListRef ref) throws IOException, JSONException {
        JSONObject meta = new JSONObject(get("/users/" + ref.user + "/lists/" + ref.slug));
        return meta.optString("name", ref.slug);
    }

    private String get(String path) throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("trakt-api-version", "2");
        h.put("trakt-api-key", prefs.traktClientId());
        return new String(Http.get(API + path, h, 15_000), StandardCharsets.UTF_8);
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
