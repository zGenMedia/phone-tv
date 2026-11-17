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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The user's own shelf: titles and actors they saved, and what they watched.
 *
 * It is one small JSON file in the app's private storage. Nothing here is sent
 * anywhere. Phone TV doesn't play video itself, so "watched" means a title was
 * opened in a player from here or marked by hand; history kept by Plex and
 * Trakt is merged in at display time (see {@link #history}) and never stored.
 *
 * All methods are for the main thread; saving to disk happens in the background.
 */
final class Library {
    // Orders for titles. Stored in preferences: keep the numbers.
    static final int SORT_RECENT = 0, SORT_TITLE = 1, SORT_NEWEST = 2, SORT_RATING = 3, SORT_OLDEST = 4;
    static final int ONLY_ALL = 0, ONLY_MOVIES = 1, ONLY_SHOWS = 2;
    // Orders for history.
    static final int HISTORY_RECENT = 0, HISTORY_TITLE = 1, HISTORY_MOST = 2;

    private static final int HISTORY_KEPT = 300;
    /** One writer thread, so saves reach the disk in the order they were made. */
    private static final java.util.concurrent.ExecutorService DISK = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "phonetv-library");
        t.setDaemon(true);
        return t;
    });

    /** An actor the user follows. */
    static final class Person {
        String id;         // Wikidata id, e.g. Q234544
        String name;
        String photo = ""; // thumbnail URL, may be empty
        String role = "";  // the part they play in the title on screen; never stored
        long savedAt;
    }

    /** One line of watch history. */
    static final class Watch {
        MediaItem item;
        String episode = ""; // "S1 · E3" for an episode of a show, otherwise empty
        String via = "";     // the app it was opened in, or where the record came from
        long at;
        int times = 1;

        /** Same title and episode, whichever source reported it. */
        String key() { return (item.source == MediaItem.Source.PLEX ? "plex:" : "") + item.id + "|" + episode; }
    }

    private final File file;
    private final Map<String, MediaItem> titles = new LinkedHashMap<>();
    private final Map<String, Person> people = new LinkedHashMap<>();
    private final List<Watch> watched = new ArrayList<>();

    private static Library shared;

    /**
     * The one library for the whole app. The home screen on the phone and the one running
     * on an attached TV are separate activities; with a copy each, the last to save would
     * silently undo the other.
     */
    static synchronized Library of(Context context) {
        if (shared == null) shared = new Library(context.getApplicationContext());
        return shared;
    }

    Library(Context context) {
        file = new File(context.getFilesDir(), "library.json");
        load();
    }

    // ------------------------------------------------------------------ titles

    boolean has(MediaItem m) { return titles.containsKey(m.id); }

    void add(MediaItem m) {
        if (m.source != MediaItem.Source.CATALOG || has(m)) return;
        m.savedAt = System.currentTimeMillis();
        titles.put(m.id, m);
        save();
    }

    void remove(MediaItem m) { if (titles.remove(m.id) != null) save(); }

    int titleCount() { return titles.size(); }

    List<MediaItem> titles(int sort, int only) {
        List<MediaItem> out = new ArrayList<>(titles.values());
        return sortTitles(filter(out, only), sort);
    }

    static List<MediaItem> filter(List<MediaItem> items, int only) {
        if (only == ONLY_ALL) return items;
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem m : items) if (m.isShow() == (only == ONLY_SHOWS)) out.add(m);
        return out;
    }

    /** Orders a list of titles in place and returns it. Unknown years and ratings go last. */
    static List<MediaItem> sortTitles(List<MediaItem> items, int sort) {
        Comparator<MediaItem> order;
        switch (sort) {
            case SORT_TITLE: order = (a, b) -> sortName(a.title).compareTo(sortName(b.title)); break;
            case SORT_NEWEST: order = (a, b) -> Integer.compare(year(b, 0), year(a, 0)); break;
            case SORT_OLDEST: order = (a, b) -> Integer.compare(year(a, 9999), year(b, 9999)); break;
            case SORT_RATING: order = (a, b) -> Float.compare(rating(b), rating(a)); break;
            default: order = (a, b) -> Long.compare(b.savedAt, a.savedAt);
        }
        Collections.sort(items, order);
        return items;
    }

    // ------------------------------------------------------------------ actors

    boolean has(Person p) { return p.id != null && people.containsKey(p.id); }

    void add(Person p) {
        if (p.id == null || has(p)) return;
        p.savedAt = System.currentTimeMillis();
        people.put(p.id, p);
        save();
    }

    void remove(Person p) { if (people.remove(p.id) != null) save(); }

    int peopleCount() { return people.size(); }

    /** @param byName true for A to Z, false for most recently saved first. */
    List<Person> people(boolean byName) {
        List<Person> out = new ArrayList<>(people.values());
        if (byName) Collections.sort(out, (a, b) -> sortName(a.name).compareTo(sortName(b.name)));
        else Collections.sort(out, (a, b) -> Long.compare(b.savedAt, a.savedAt));
        return out;
    }

    // ----------------------------------------------------------------- history

    /** Notes that a title (or one episode of it) was opened in a player, or marked watched by hand. */
    void watched(MediaItem m, String episode, String via) {
        if (m.source != MediaItem.Source.CATALOG) return; // Plex keeps its own history
        Watch entry = null;
        for (Watch w : watched) if (w.item.id.equals(m.id) && w.episode.equals(episode)) entry = w;
        if (entry == null) {
            entry = new Watch();
            entry.item = m;
            entry.episode = episode;
            entry.times = 0;
            watched.add(entry);
        }
        entry.times++;
        entry.via = via;
        entry.at = System.currentTimeMillis();
        while (watched.size() > HISTORY_KEPT) watched.remove(oldest());
        save();
    }

    private Watch oldest() {
        Watch first = watched.get(0);
        for (Watch w : watched) if (w.at < first.at) first = w;
        return first;
    }

    /** True if any record of this title is kept here (the film itself, or any episode of the show). */
    boolean isWatched(MediaItem m) {
        for (Watch w : watched) if (w.item.id.equals(m.id)) return true;
        return false;
    }

    /** Removes every record of a title. */
    void forget(MediaItem m) {
        boolean changed = false;
        for (int i = watched.size() - 1; i >= 0; i--) {
            if (watched.get(i).item.id.equals(m.id)) { watched.remove(i); changed = true; }
        }
        if (changed) save();
    }

    void clearHistory() { watched.clear(); save(); }

    int historyCount() { return watched.size(); }

    /**
     * History for display: what is kept here plus what other services report.
     * The same title and episode from two sources becomes one line that names both.
     */
    List<Watch> history(List<Watch> elsewhere, int sort) {
        // A service lists each play separately, so its repeats add up; the same play
        // seen by two services is still one play, which is why sources are merged by the larger count.
        Map<String, Watch> perSource = new LinkedHashMap<>();
        for (Watch w : elsewhere) {
            String key = w.via + "|" + w.key();
            Watch seen = perSource.get(key);
            if (seen == null) { perSource.put(key, copy(w)); continue; }
            seen.times += w.times;
            seen.at = Math.max(seen.at, w.at);
        }
        Map<String, Watch> merged = new LinkedHashMap<>();
        List<Watch> all = new ArrayList<>(watched);
        all.addAll(perSource.values());
        for (Watch w : all) {
            Watch seen = merged.get(w.key());
            if (seen == null) { merged.put(w.key(), copy(w)); continue; }
            if (!seen.via.contains(w.via)) seen.via = seen.via.isEmpty() ? w.via : seen.via + ", " + w.via;
            seen.times = Math.max(seen.times, w.times);
            seen.at = Math.max(seen.at, w.at);
        }
        List<Watch> out = new ArrayList<>(merged.values());
        Comparator<Watch> order;
        switch (sort) {
            case HISTORY_TITLE: order = (a, b) -> sortName(a.item.title).compareTo(sortName(b.item.title)); break;
            case HISTORY_MOST: order = (a, b) -> a.times != b.times ? Integer.compare(b.times, a.times) : Long.compare(b.at, a.at); break;
            default: order = (a, b) -> Long.compare(b.at, a.at);
        }
        Collections.sort(out, order);
        return out;
    }

    private static Watch copy(Watch w) {
        Watch c = new Watch();
        c.item = w.item;
        c.episode = w.episode;
        c.via = w.via;
        c.at = w.at;
        c.times = w.times;
        return c;
    }

    // ----------------------------------------------------------------- helpers

    /** How an episode is written everywhere, so records from different sources line up. */
    static String episodeLabel(int season, int number) { return "S" + season + " · E" + number; }

    /** "tt0944947:1:2" (the catalog's id for an episode) to "S1 · E2"; empty for anything else. */
    static String episodeLabel(String episodeId) {
        String[] parts = episodeId == null ? new String[0] : episodeId.split(":");
        if (parts.length < 3) return "";
        try { return episodeLabel(Integer.parseInt(parts[parts.length - 2]), Integer.parseInt(parts[parts.length - 1])); }
        catch (NumberFormatException e) { return ""; }
    }

    /** Lower case, without a leading article, so "The Wire" files under W. */
    static String sortName(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        for (String article : new String[]{"the ", "a ", "an "}) if (t.startsWith(article)) return t.substring(article.length());
        return t;
    }

    private static int year(MediaItem m, int unknown) {
        String digits = m.year == null ? "" : m.year.replaceAll("[^0-9].*$", "");
        return digits.length() == 4 ? Integer.parseInt(digits) : unknown;
    }

    private static float rating(MediaItem m) {
        try { return m.rating == null || m.rating.isEmpty() ? -1 : Float.parseFloat(m.rating); }
        catch (NumberFormatException e) { return -1; }
    }

    // -------------------------------------------------------------------- disk

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            JSONObject o = new JSONObject(new String(Http.readAll(in), StandardCharsets.UTF_8));
            JSONArray t = o.optJSONArray("titles");
            for (int i = 0; t != null && i < t.length(); i++) {
                MediaItem m = title(t.getJSONObject(i));
                titles.put(m.id, m);
            }
            JSONArray p = o.optJSONArray("people");
            for (int i = 0; p != null && i < p.length(); i++) {
                JSONObject j = p.getJSONObject(i);
                Person person = new Person();
                person.id = j.getString("id");
                person.name = j.optString("name");
                person.photo = j.optString("photo");
                person.savedAt = j.optLong("savedAt");
                people.put(person.id, person);
            }
            JSONArray w = o.optJSONArray("watched");
            for (int i = 0; w != null && i < w.length(); i++) {
                JSONObject j = w.getJSONObject(i);
                Watch entry = new Watch();
                entry.item = title(j);
                entry.episode = j.optString("episode");
                entry.via = j.optString("via");
                entry.at = j.optLong("at");
                entry.times = Math.max(1, j.optInt("times", 1));
                watched.add(entry);
            }
        } catch (IOException | JSONException e) {
            // A damaged file must not take the launcher down. Keep it aside rather than overwrite it,
            // so nothing the user saved is lost for good.
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(new File(file.getPath() + ".damaged"));
        }
    }

    private static MediaItem title(JSONObject j) throws JSONException {
        MediaItem m = MediaItem.catalog(j.getString("id"), j.optString("type", "movie"), j.optString("title"), j.optString("year"));
        m.genres = j.optString("genres");
        m.rating = j.optString("rating");
        m.summary = j.optString("summary");
        m.savedAt = j.optLong("savedAt");
        return m;
    }

    private static JSONObject json(MediaItem m) throws JSONException {
        return new JSONObject().put("id", m.id).put("type", m.type).put("title", m.title).put("year", m.year)
                .put("genres", m.genres).put("rating", m.rating).put("summary", m.summary).put("savedAt", m.savedAt);
    }

    /** Snapshots on the calling thread, writes on the disk thread; the temp file makes the swap all-or-nothing. */
    private void save() {
        final String snapshot;
        try {
            JSONArray t = new JSONArray(), p = new JSONArray(), w = new JSONArray();
            for (MediaItem m : titles.values()) t.put(json(m));
            for (Person person : people.values()) {
                p.put(new JSONObject().put("id", person.id).put("name", person.name).put("photo", person.photo).put("savedAt", person.savedAt));
            }
            for (Watch entry : watched) {
                w.put(json(entry.item).put("episode", entry.episode).put("via", entry.via).put("at", entry.at).put("times", entry.times));
            }
            snapshot = new JSONObject().put("titles", t).put("people", p).put("watched", w).toString();
        } catch (JSONException e) {
            return;
        }
        DISK.execute(() -> {
            File temp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(snapshot.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(file);
        });
    }
}
