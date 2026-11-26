package com.zgenmedia.tvhome;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Today's real premieres, from TVmaze's free schedule.
 *
 * A complete round-the-clock guide for channels like HBO is licensed data that no free
 * source carries. What is free is narrower but real: the episodes that actually premiere
 * today, on which network and at what time. Channels uses these to put tonight's real
 * showings at their true times and fills the rest of the day with its own line-up.
 *
 * Only scripted and animated series are taken: no news, talk, sport or game shows.
 * Fetched once a day and kept on disk. Blocking.
 */
final class TvGuide {
    /** One real showing. */
    static final class Airing {
        String imdb, title, episodeId;
        long start, end;
    }

    private static final long MINUTE = 60_000L;

    private TvGuide() {}

    /**
     * Today's premieres by network or streaming service name, as TVmaze spells them
     * ("HBO", "FX", "Netflix"). Empty when the schedule can't be had: the channels then
     * simply run their own line-up all day.
     */
    static Map<String, List<Airing>> today(Context c) {
        Map<String, List<Airing>> out = new HashMap<>();
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        try {
            read(fetch(c, "tv-" + day, "https://api.tvmaze.com/schedule?country=US&date=" + day), false, out);
        } catch (IOException | JSONException ignored) { /* no broadcast premieres today */ }
        try {
            read(fetch(c, "web-" + day, "https://api.tvmaze.com/schedule/web?date=" + day), true, out);
        } catch (IOException | JSONException ignored) { /* no streaming premieres today */ }
        for (List<Airing> list : out.values()) Collections.sort(list, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** One day's schedule, from disk when it has been fetched already today. Yesterday's files are cleared out. */
    private static String fetch(Context c, String name, String url) throws IOException {
        File dir = new File(c.getCacheDir(), "guide");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File file = new File(dir, name + ".json");
        if (file.exists()) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                return new String(Http.readAll(in), StandardCharsets.UTF_8);
            }
        }
        String body = new String(Http.get(url, new HashMap<String, String>(), 20_000), StandardCharsets.UTF_8);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!f.getName().endsWith(name.substring(name.length() - 10) + ".json")) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        try (FileOutputStream w = new FileOutputStream(file)) { w.write(body.getBytes(StandardCharsets.UTF_8)); }
        return body;
    }

    private static void read(String json, boolean streaming, Map<String, List<Airing>> out) throws JSONException {
        JSONArray entries = new JSONArray(json);
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        // A streaming service has no airtime: its new episodes are lined up from 8 in the evening, local time.
        Map<String, Long> eveningCursor = new HashMap<>();
        Calendar eight = Calendar.getInstance();
        eight.set(Calendar.HOUR_OF_DAY, 20);
        eight.set(Calendar.MINUTE, 0);
        eight.set(Calendar.SECOND, 0);
        eight.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            JSONObject show = streaming ? (e.optJSONObject("_embedded") == null ? null : e.getJSONObject("_embedded").optJSONObject("show")) : e.optJSONObject("show");
            if (show == null) continue;
            String kind = show.optString("type");
            if (!"Scripted".equals(kind) && !"Animation".equals(kind)) continue;
            JSONObject where = show.optJSONObject(streaming ? "webChannel" : "network");
            JSONObject ids = show.optJSONObject("externals");
            String imdb = ids == null ? "" : ids.optString("imdb", "");
            int season = e.optInt("season"), number = e.optInt("number");
            if (where == null || !imdb.startsWith("tt") || season <= 0 || number <= 0) continue;

            int minutes = e.optInt("runtime", 0);
            if (minutes <= 0) minutes = show.optInt("averageRuntime", 45);
            minutes = Math.max(10, Math.min(240, minutes));
            Airing a = new Airing();
            a.imdb = imdb;
            a.title = show.optString("name");
            a.episodeId = imdb + ":" + season + ":" + number;
            String network = where.optString("name");
            if (streaming) {
                Long at = eveningCursor.get(network);
                a.start = at == null ? eight.getTimeInMillis() : at;
            } else {
                try { a.start = stamp.parse(e.optString("airstamp")).getTime(); }
                catch (ParseException | RuntimeException bad) { continue; }
            }
            a.end = a.start + ((minutes + 4) / 5) * 5 * MINUTE;
            if (streaming) eveningCursor.put(network, a.end);
            List<Airing> list = out.get(network);
            if (list == null) { list = new ArrayList<>(); out.put(network, list); }
            // Two entries for the same slot (a double bill listed twice): keep the first.
            boolean clash = false;
            for (Airing other : list) clash |= a.start < other.end && other.start < a.end;
            if (!clash) list.add(a);
        }
    }
}
