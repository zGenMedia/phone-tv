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
