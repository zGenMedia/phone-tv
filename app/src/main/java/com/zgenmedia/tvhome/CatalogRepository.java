package com.zgenmedia.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Public movie/show catalog backing the hero and content rows.
 *
 * Source: Cinemeta, the open metadata service used by Stremio. It needs no API
 * key, returns current titles with poster/background/logo art, and is the same
 * data whether or not Stremio is installed, so the home screen works for
 * everyone. Responses are cached on disk so the screen is never empty offline.
 */
final class CatalogRepository {
    static final String MOVIES = "movie/top", SHOWS = "series/top", TOP_RATED = "movie/imdbRating";
    private static final String[] KEYS = {MOVIES, SHOWS, TOP_RATED};
    private static final String BASE = "https://v3-cinemeta.strem.io/catalog/";
    private static final String META = "https://v3-cinemeta.strem.io/meta/";

    interface Listener {
        /** @param error null on success, otherwise a short human-readable reason. */
        void onCatalogsUpdated(String error, boolean usedCacheOnly);
    }

    private final File dir;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, List<MediaItem>> lists = new HashMap<>();
    private boolean fetching;
    private long lastSuccess;
    private String lastError;

    CatalogRepository(Context context, Executor io) {
        this.dir = context.getFilesDir();
        this.io = io;
    }

    List<MediaItem> get(String key) {
        List<MediaItem> l = lists.get(key);
        return l == null ? Collections.emptyList() : l;
    }

    boolean isEmpty() { return lists.isEmpty(); }
    boolean isFetching() { return fetching; }
    String lastError() { return lastError; }
    boolean isOlderThan(long ms) { return System.currentTimeMillis() - lastSuccess > ms; }

    /** Synchronously loads the last good responses from disk. Cheap; call from onCreate. */
    void loadCached() {
        for (String key : KEYS) {
            try {
                String json = readFile(cacheFile(key));
                if (json != null) {
                    List<MediaItem> items = parse(json);
                    if (!items.isEmpty()) lists.put(key, items);
                }
            } catch (IOException | JSONException ignored) {
                // A corrupt cache file just means we wait for the network.
            }
        }
    }

    void refresh(Listener listener) {
        if (fetching) return;
        fetching = true;
        io.execute(() -> {
            Map<String, List<MediaItem>> fresh = new HashMap<>();
            String error = null;
            for (String key : KEYS) {
                try {
                    String json = new String(Http.get(BASE + key + ".json"), StandardCharsets.UTF_8);
                    List<MediaItem> items = parse(json);
                    if (items.isEmpty()) { error = key + ": no titles returned"; continue; }
                    fresh.put(key, items);
                    writeFile(cacheFile(key), json);
                } catch (IOException | JSONException e) {
                    error = key + ": " + describe(e);
                }
            }
            final String err = error;
            main.post(() -> {
                fetching = false;
                lists.putAll(fresh);
                if (!fresh.isEmpty()) lastSuccess = System.currentTimeMillis();
                lastError = lists.isEmpty() ? (err != null ? err : "unknown error") : null;
                listener.onCatalogsUpdated(err, fresh.isEmpty() && !lists.isEmpty());
            });
        });
    }

    // ------------------------------------------------------------ title details

    /** One episode of a show, as listed on the details page. */
    static final class Episode {
        String id, title, overview, thumbnail, released;
        int season, number;
    }

    /** What the details page shows beyond what a row card already carries. */
    static final class Details {
        String summary = "", runtime = "", rating = "", country = "", trailerId = "";
        String backdropRef = "", logoRef = "";
        final List<String> genres = new ArrayList<>(), cast = new ArrayList<>(), directors = new ArrayList<>();
        final List<Episode> episodes = new ArrayList<>();

        /** Season numbers in viewing order; specials (season 0) go last. */
        List<Integer> seasons() {
            List<Integer> out = new ArrayList<>();
            for (Episode e : episodes) if (!out.contains(e.season)) out.add(e.season);
            Collections.sort(out, (x, y) -> Integer.compare(x == 0 ? Integer.MAX_VALUE : x, y == 0 ? Integer.MAX_VALUE : y));
            return out;
        }

        List<Episode> season(int number) {
            List<Episode> out = new ArrayList<>();
            for (Episode e : episodes) if (e.season == number) out.add(e);
            return out;
        }
    }

    private static final int DETAILS_KEPT = 12;
    private final Map<String, Details> detailsCache = new java.util.LinkedHashMap<String, Details>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Details> eldest) { return size() > DETAILS_KEPT; }
    };

    /** Details already fetched this session, or null. Safe on the main thread. */
    Details cachedDetails(MediaItem m) {
        synchronized (detailsCache) { return detailsCache.get(m.id); }
    }

    /** Fetches cast, runtime, trailer and (for shows) the episode list. Blocking. */
    Details details(MediaItem m) throws IOException, JSONException {
        Details cached = cachedDetails(m);
        if (cached != null) return cached;
        String type = m.isShow() ? "series" : "movie";
        String json = new String(Http.get(META + type + "/" + m.id + ".json"), StandardCharsets.UTF_8);
        JSONObject o = new JSONObject(json).optJSONObject("meta");
        if (o == null) throw new IOException("no details for this title");

        Details d = new Details();
        d.summary = o.optString("description", "");
        d.runtime = o.optString("runtime", "");
        d.rating = o.optString("imdbRating", "");
        d.country = o.optString("country", "");
        d.backdropRef = o.optString("background", "");
        d.logoRef = o.optString("logo", "");
        JSONArray g = o.optJSONArray("genres");
        addAll(d.genres, g != null ? g : o.optJSONArray("genre"));
        addAll(d.cast, o.optJSONArray("cast"));
        addAll(d.directors, o.optJSONArray("director"));
        JSONArray trailers = o.optJSONArray("trailers");
        if (trailers != null && trailers.length() > 0) d.trailerId = trailers.getJSONObject(0).optString("source", "");

        JSONArray videos = o.optJSONArray("videos");
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            Episode e = new Episode();
            e.id = v.optString("id");
            e.season = v.optInt("season", 1);
            e.number = v.optInt("episode", v.optInt("number", i + 1));
            e.title = v.optString("name", v.optString("title", ""));
            e.overview = v.optString("overview", v.optString("description", ""));
            e.thumbnail = v.optString("thumbnail", "");
            e.released = v.optString("released", v.optString("firstAired", ""));
            if (!e.id.isEmpty()) d.episodes.add(e);
        }
        Collections.sort(d.episodes, (x, y) -> x.season != y.season ? Integer.compare(x.season, y.season) : Integer.compare(x.number, y.number));
        synchronized (detailsCache) { detailsCache.put(m.id, d); }
        return d;
    }

    private static void addAll(List<String> into, JSONArray from) {
        for (int i = 0; from != null && i < from.length(); i++) {
            String s = from.optString(i, "");
            if (!s.isEmpty()) into.add(s);
        }
    }

    private static List<MediaItem> parse(String json) throws JSONException {
        JSONArray metas = new JSONObject(json).optJSONArray("metas");
        List<MediaItem> out = new ArrayList<>();
        if (metas == null) return out;
        for (int i = 0; i < metas.length(); i++) {
            JSONObject o = metas.getJSONObject(i);
            MediaItem m = new MediaItem(MediaItem.Source.CATALOG);
            m.id = o.optString("id");
            m.title = o.optString("name");
            if (m.id.isEmpty() || m.title.isEmpty()) continue;
            m.type = o.optString("type", "movie");
            m.summary = o.optString("description", "");
            m.year = o.optString("releaseInfo", "");
            m.rating = o.optString("imdbRating", "");
            m.posterRef = o.optString("poster", "");
            m.backdropRef = o.optString("background", "");
            m.logoRef = o.optString("logo", "");
            JSONArray g = o.optJSONArray("genres");
            if (g == null) g = o.optJSONArray("genre");
            if (g != null && g.length() > 0) {
                m.genres = g.optString(0) + (g.length() > 1 ? ", " + g.optString(1) : "");
            }
            out.add(m);
        }
        return out;
    }

    static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private File cacheFile(String key) { return new File(dir, "cat_" + key.replace('/', '_') + ".json"); }

    private static String readFile(File f) throws IOException {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) { return new String(Http.readAll(in), StandardCharsets.UTF_8); }
    }

    private static void writeFile(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(s.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { /* cache is best-effort */ }
    }
}
