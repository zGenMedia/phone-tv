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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where playback stopped in Phone TV's own player, for Continue watching.
 *
 * One entry per movie or show: a show remembers only its latest episode, the way a
 * person thinks of it ("I'm on season 2"). The stream link is kept too, so resuming
 * can skip the source picker; links from some add-ons expire, and then the picker
 * opens instead.
 */
final class Progress {
    /** Finished means at least this share watched; then the entry turns into "next episode". */
    static final float DONE = 0.90f;
    private static final int KEPT = 60;

    static final class Entry {
        String id;              // the movie's or show's IMDb id
        String type;            // "movie" or "series"
        String episodeId = "";  // "tt…:season:episode", empty for a movie
        String title = "", episodeLabel = "";
        String posterRef = "", backdropRef = "", logoRef = "";
        String url = "", addon = "";
        final Map<String, String> headers = new LinkedHashMap<>();
        long positionMs, durationMs, updatedAt;

        float fraction() { return durationMs <= 0 ? 0 : Math.min(1f, positionMs / (float) durationMs); }
        boolean finished() { return fraction() >= DONE; }

        MediaItem item() {
            MediaItem m = MediaItem.catalog(id, type, title, "");
            if (!posterRef.isEmpty()) m.posterRef = posterRef;
            if (!backdropRef.isEmpty()) m.backdropRef = backdropRef;
            if (!logoRef.isEmpty()) m.logoRef = logoRef;
            m.subtitle = episodeLabel;
            m.progressPercent = Math.round(fraction() * 100);
            m.viewOffsetMs = positionMs;
            return m;
        }
    }

    private static final ExecutorService DISK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "phonetv-progress");
        t.setDaemon(true);
        return t;
    });
    private static Progress shared;
    private static String sharedProfile;

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** The current profile's progress. Switching profiles switches the file. */
    static synchronized Progress of(Context c) {
        String profile = Profiles.currentId(c);
        if (shared == null || !profile.equals(sharedProfile)) {
            shared = new Progress(new File(c.getApplicationContext().getFilesDir(), Profiles.fileName("progress", profile)));
            sharedProfile = profile;
        }
        return shared;
    }

    Progress(File file) {
        this.file = file;
        load();
    }

    /** Another profile's positions, for a setup transfer. The current profile's come from {@link #of}. */
    static Progress forProfile(Context c, String profileId) {
        return new Progress(new File(c.getApplicationContext().getFilesDir(), Profiles.fileName("progress", profileId)));
    }

    synchronized Entry get(String id) { return entries.get(id); }

    /** The saved position for exactly this movie or episode, or 0. */
    synchronized long resumeAt(String id, String episodeId) {
        Entry e = entries.get(id);
        if (e == null || e.finished()) return 0;
        return (episodeId == null ? "" : episodeId).equals(e.episodeId) ? e.positionMs : 0;
    }

    synchronized void update(Entry e) {
        e.updatedAt = System.currentTimeMillis();
        entries.remove(e.id);
        entries.put(e.id, e);
        while (entries.size() > KEPT) entries.remove(entries.keySet().iterator().next());
        save();
    }

    synchronized void remove(String id) { if (entries.remove(id) != null) save(); }

    /** Unfinished things, most recent first. Finished episodes are handled as "next up" by the caller. */
    synchronized List<Entry> recent() {
        List<Entry> out = new ArrayList<>(entries.values());
        Collections.reverse(out);
        return out;
    }

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            for (Entry e : parse(new String(Http.readAll(in), StandardCharsets.UTF_8))) entries.put(e.id, e);
        } catch (IOException | JSONException e) {
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(new File(file.getPath() + ".damaged"));
        }
    }

    private static List<Entry> parse(String json) throws JSONException {
        List<Entry> out = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Entry e = new Entry();
            e.id = o.getString("id");
            e.type = o.optString("type", "movie");
            e.episodeId = o.optString("episodeId");
            e.title = o.optString("title");
            e.episodeLabel = o.optString("episodeLabel");
            e.posterRef = o.optString("poster");
            e.backdropRef = o.optString("backdrop");
            e.logoRef = o.optString("logo");
            e.url = o.optString("url");
            e.addon = o.optString("addon");
            JSONObject h = o.optJSONObject("headers");
            if (h != null) for (java.util.Iterator<String> k = h.keys(); k.hasNext(); ) { String key = k.next(); e.headers.put(key, h.optString(key)); }
            e.positionMs = o.optLong("position");
            e.durationMs = o.optLong("duration");
            e.updatedAt = o.optLong("updated");
            out.add(e);
        }
        return out;
    }

    /** Everything here as text, for a setup transfer. */
    synchronized String export() {
        try { return snapshot(); } catch (JSONException e) { return "[]"; }
    }

    /**
     * Adds another device's positions to this one. Nothing here is removed: a title both
     * sides know keeps whichever position was saved more recently.
     */
    synchronized void merge(String json) throws JSONException {
        for (Entry theirs : parse(json)) {
            Entry mine = entries.get(theirs.id);
            if (mine == null || theirs.updatedAt > mine.updatedAt) entries.put(theirs.id, theirs);
        }
        save();
    }

    private String snapshot() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Entry e : entries.values()) {
            arr.put(new JSONObject().put("id", e.id).put("type", e.type).put("episodeId", e.episodeId)
                    .put("title", e.title).put("episodeLabel", e.episodeLabel)
                    .put("poster", e.posterRef).put("backdrop", e.backdropRef).put("logo", e.logoRef)
                    .put("url", e.url).put("addon", e.addon).put("headers", new JSONObject(e.headers))
                    .put("position", e.positionMs).put("duration", e.durationMs).put("updated", e.updatedAt));
        }
        return arr.toString();
    }

    private void save() {
        final String json;
        try { json = snapshot(); }
        catch (JSONException e) { return; }
        DISK.execute(() -> {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        });
    }
}
