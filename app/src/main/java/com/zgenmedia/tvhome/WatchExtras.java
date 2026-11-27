package com.zgenmedia.tvhome;

import android.content.Context;
import android.os.Handler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the player shows around a film or episode, beyond the picture itself:
 *
 *  - where the intro, recap and end credits fall, for the Skip button and for timing
 *    the "up next" card (IntroDB, the open database Nuvio also reads);
 *  - the line shown as it starts: its score, year, length and genres (the catalog),
 *    and its age rating when the user has added a TMDB key;
 *  - titles like it (Trakt), for the row under the controls;
 *  - for a film, what else its lead actor has been in (Wikidata), for the card at the end.
 *
 * Each part is fetched on its own and may simply be missing: the player shows whatever
 * has arrived and never waits. {@link #load} returns at once; the listener is called on
 * the given handler each time another part is in.
 */
final class WatchExtras {
    /** A stretch of the video, in milliseconds from its start. */
    static final class Span {
        final long startMs, endMs;

        Span(long startMs, long endMs) { this.startMs = startMs; this.endMs = endMs; }
        boolean holds(long positionMs) { return positionMs >= startMs && positionMs < endMs; }
    }

    interface Listener { void onMore(WatchExtras extras); }

    private static final String SEGMENTS = "https://api.introdb.app/segments";
    private static final String META = "https://v3-cinemeta.strem.io/meta/";
    private static final String TMDB = "https://api.themoviedb.org/3";
    /** An "intro" longer than this is a mistake in the data, not an intro. */
    private static final long LONGEST_INTRO_MS = 6 * 60_000;

    volatile Span intro, recap, credits;
    volatile String score = "", year = "", runtime = "", ageRating = "", star = "";
    volatile List<String> genres = new ArrayList<>();
    volatile List<MediaItem> related = new ArrayList<>(), starTitles = new ArrayList<>();
    /** True once the titles like this one have been asked for, found or not. */
    volatile boolean relatedTried;

    /** Empty: the parts are filled in as they arrive. */
    WatchExtras() {}

    /**
     * @param episodeId "tt0903747:1:2" for an episode, null for a film
     */
    static WatchExtras load(Context context, Prefs prefs, String id, String type, String episodeId, Handler main, Listener listener) {
        final WatchExtras x = new WatchExtras();
        if (id == null || !id.startsWith("tt")) { x.relatedTried = true; return x; } // a Plex title, or one the catalog doesn't know
        final Context app = context.getApplicationContext();
        final boolean show = "series".equals(type);
        final Runnable tell = () -> main.post(() -> listener.onMore(x));

        // The skip times matter in the first seconds, so they don't queue behind anything else.
        // They serve the Skip button and the timing of the ending card; with both off, nobody is asked.
        if (prefs.skipButton() || prefs.endCard()) new Thread(() -> {
            try { x.readSegments(text(SEGMENTS + "?imdb_id=" + id + episodeQuery(episodeId, show))); }
            catch (IOException | JSONException none) { /* no skip times for this one */ }
            tell.run();
        }, "phonetv-skip-times").start();

        new Thread(() -> {
            String lead = "";
            int tmdbId = 0;
            try {
                JSONObject meta = new JSONObject(text(META + (show ? "series" : "movie") + "/" + id + ".json")).optJSONObject("meta");
                if (meta != null) {
                    lead = x.readAbout(meta);
                    tmdbId = meta.optInt("moviedb_id", 0);
                }
            } catch (IOException | JSONException none) { /* the opening line goes without */ }
            tell.run();

            String key = prefs.tmdbKey();
            if (!key.isEmpty() && tmdbId > 0) {
                try {
                    x.ageRating = show ? tvRating(new JSONObject(text(TMDB + "/tv/" + tmdbId + "/content_ratings?api_key=" + key)))
                            : filmRating(new JSONObject(text(TMDB + "/movie/" + tmdbId + "/release_dates?api_key=" + key)));
                    tell.run();
                } catch (IOException | JSONException none) { /* no age rating */ }
            }

            MediaItem playing = MediaItem.catalog(id, type, "", "");
            try { x.related = new TraktClient(app, prefs).related(playing); }
            catch (IOException | JSONException | RuntimeException none) { /* nothing to suggest */ }
            x.relatedTried = true;
            tell.run();

            // "If you love <the lead>": only films end on a recommendation; a show ends on its next episode.
            if (!show && !lead.isEmpty()) {
                try {
                    People people = new People(app);
                    Library.Person person = people.find(lead);
                    if (person != null) {
                        List<MediaItem> also = new ArrayList<>();
                        for (MediaItem m : people.load(person).credits) {
                            if (m.id == null || !m.id.startsWith("tt") || m.id.equals(id) || m.isShow()) continue;
                            also.add(m);
                            if (also.size() == 6) break;
                        }
                        if (also.size() >= 2) { x.star = person.name; x.starTitles = also; tell.run(); }
                    }
                } catch (IOException | JSONException | RuntimeException none) { /* the card falls back to "more like this" */ }
            }
        }, "phonetv-watch-extras").start();
        return x;
    }

    private static String episodeQuery(String episodeId, boolean show) {
        if (!show || episodeId == null) return "&is_movie=true";
        String[] p = episodeId.split(":");
        return p.length >= 3 ? "&season=" + p[p.length - 2] + "&episode=" + p[p.length - 1] : "";
    }

    void readSegments(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Span in = span(o.optJSONObject("intro")), re = span(o.optJSONObject("recap"));
        intro = in != null && in.endMs - in.startMs <= LONGEST_INTRO_MS ? in : null;
        recap = re != null && re.endMs - re.startMs <= LONGEST_INTRO_MS ? re : null;
        credits = span(o.optJSONObject("outro"));
    }

    private static Span span(JSONObject o) {
        if (o == null) return null;
        long start = o.has("start_ms") && !o.isNull("start_ms") ? o.optLong("start_ms") : Math.round(o.optDouble("start_sec", 0) * 1000);
        long end = o.has("end_ms") && !o.isNull("end_ms") ? o.optLong("end_ms") : Math.round(o.optDouble("end_sec", 0) * 1000);
        return end - start >= 3_000 && start >= 0 ? new Span(start, end) : null;
    }

    /** Reads the catalog's facts for the opening line. @return the first-billed actor, or "" */
    String readAbout(JSONObject meta) {
        String rating = meta.optString("imdbRating", "");
        score = rating.isEmpty() || rating.equals("null") ? "" : rating;
        String info = meta.isNull("releaseInfo") ? "" : meta.optString("releaseInfo", "");
        year = info.length() >= 4 ? info.substring(0, 4) : "";
        runtime = meta.isNull("runtime") ? "" : meta.optString("runtime", "");
        List<String> kinds = new ArrayList<>();
        JSONArray g = meta.optJSONArray("genres");
        if (g == null) g = meta.optJSONArray("genre");
        for (int i = 0; g != null && i < g.length() && kinds.size() < 3; i++) kinds.add(g.optString(i));
        genres = kinds;
        JSONArray cast = meta.optJSONArray("cast");
        return cast != null && cast.length() > 0 ? cast.optString(0, "") : "";
    }

    /** The certificate a film carries where the user is, else in the US: "PG-13", "15", "R". */
    static String filmRating(JSONObject releaseDates) {
        JSONArray results = releaseDates.optJSONArray("results");
        for (String country : countries()) {
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null || !country.equals(r.optString("iso_3166_1"))) continue;
                JSONArray dates = r.optJSONArray("release_dates");
                for (int k = 0; dates != null && k < dates.length(); k++) {
                    String c = dates.optJSONObject(k) == null ? "" : dates.optJSONObject(k).optString("certification", "");
                    if (!c.isEmpty()) return c;
                }
            }
        }
        return "";
    }

    /** The same for a show: "TV-MA", "TV-14". */
    static String tvRating(JSONObject contentRatings) {
        JSONArray results = contentRatings.optJSONArray("results");
        for (String country : countries()) {
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r != null && country.equals(r.optString("iso_3166_1")) && !r.optString("rating", "").isEmpty()) return r.optString("rating");
            }
        }
        return "";
    }

    private static String[] countries() {
        String here = Locale.getDefault().getCountry();
        return here.isEmpty() || here.equals("US") ? new String[]{"US"} : new String[]{here, "US"};
    }

    /** Where the "up next" card should come up: at the credits when they're known, else near the very end. */
    long endCardAt(long durationMs, boolean show) {
        Span c = credits;
        if (c != null && c.startMs > durationMs / 2 && c.startMs < durationMs) return c.startMs;
        return show ? durationMs - 40_000 : Math.round(durationMs * 0.965);
    }

    private static String text(String url) throws IOException {
        return new String(Http.get(url), StandardCharsets.UTF_8);
    }
}
