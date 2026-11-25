package com.zgenmedia.tvhome;

import android.content.Context;
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
 * The parts of a title page beyond the catalog's own details, each from the source
 * that knows it best, all without keys unless the user added a TMDB one:
 * Trakt's rating, "More like this" and comments; the film series it belongs to
 * (Wikidata); episode ratings (TVmaze); TMDB's rating and recommendations with a key.
 *
 * Every part is optional. A source that fails leaves its part out; the page never waits
 * on it. All calls block.
 */
final class TitleExtras {
    static final class Comment {
        String user, text, date;
        int likes;
        boolean spoiler;
    }

    String traktRating = "", tmdbRating = "";
    final List<MediaItem> related = new ArrayList<>(), series = new ArrayList<>();
    String seriesName = "";
    final List<Comment> comments = new ArrayList<>();
    /** Networks first, then production companies. */
    final List<Library.Company> companies = new ArrayList<>();
    /** "season:episode" → TVmaze rating out of 10. */
    final Map<String, String> episodeRatings = new HashMap<>();

    private TitleExtras() {}

    static TitleExtras load(Context c, Prefs prefs, MediaItem m) {
        TitleExtras x = new TitleExtras();
        String kind = m.isShow() ? "shows" : "movies";
        String id = m.id;
        if (!id.startsWith("tt")) return x;
        try {
            JSONObject r = new JSONObject(trakt("/" + kind + "/" + id + "/ratings"));
            double v = r.optDouble("rating", 0);
            if (v > 0 && r.optInt("votes") >= 10) x.traktRating = Math.round(v * 10) + "%";
        } catch (IOException | JSONException ignored) { /* no Trakt rating */ }
        try {
            JSONArray rel = new JSONArray(trakt("/" + kind + "/" + id + "/related?extended=full&limit=20"));
            for (int i = 0; i < rel.length(); i++) {
                JSONObject o = rel.getJSONObject(i);
                JSONObject ids = o.optJSONObject("ids");
                String imdb = ids == null ? "" : ids.optString("imdb", "");
                if (!imdb.startsWith("tt")) continue;
                MediaItem r = MediaItem.catalog(imdb, m.isShow() ? "series" : "movie", o.optString("title"),
                        o.isNull("year") ? "" : String.valueOf(o.optInt("year")));
                r.summary = o.optString("overview", "");
                double rating = o.optDouble("rating", 0);
                if (rating > 0) r.rating = String.format(java.util.Locale.US, "%.1f", rating);
                x.related.add(r);
            }
        } catch (IOException | JSONException ignored) { /* no suggestions */ }
        try {
            JSONArray cm = new JSONArray(trakt("/" + kind + "/" + id + "/comments/likes?limit=12"));
            for (int i = 0; i < cm.length(); i++) {
                JSONObject o = cm.getJSONObject(i);
                Comment k = new Comment();
                JSONObject user = o.optJSONObject("user");
                k.user = user == null ? "" : user.optString("username", "");
                k.text = o.optString("comment", "").trim();
                k.date = o.optString("created_at", "");
                k.likes = o.optInt("likes");
                k.spoiler = o.optBoolean("spoiler");
                if (!k.text.isEmpty()) x.comments.add(k);
            }
        } catch (IOException | JSONException ignored) { /* no comments */ }
        try { x.companies.addAll(Studios.behind(id)); } catch (IOException | JSONException ignored) { /* the row is left out */ }
        if (!m.isShow()) {
            try { filmSeries(x, id); } catch (IOException | JSONException ignored) { /* not part of a series */ }
        } else {
            try { episodeRatings(x, id); } catch (IOException | JSONException ignored) { /* no ratings */ }
        }
        String key = prefs.tmdbKey();
        if (!key.isEmpty()) {
            try { tmdb(x, key, m); } catch (IOException | JSONException ignored) { /* key wrong or TMDB down */ }
        }
        return x;
    }

    /** The other films in the same series (Wikidata "part of the series"), in release order. */
    private static void filmSeries(TitleExtras x, String imdb) throws IOException, JSONException {
        JSONArray rows = People.sparql("SELECT ?film ?imdb (SAMPLE(?t) AS ?title) (SAMPLE(?sn) AS ?seriesName) (MIN(?d) AS ?date) WHERE { "
                + "?me wdt:P345 \"" + imdb + "\"; wdt:P179 ?series. ?film wdt:P179 ?series; wdt:P345 ?imdb. "
                + "OPTIONAL { ?film rdfs:label ?t. FILTER(LANG(?t) IN (\"en\", \"mul\")) } "
                + "OPTIONAL { ?series rdfs:label ?sn. FILTER(LANG(?sn) IN (\"en\", \"mul\")) } "
                + "OPTIONAL { ?film wdt:P577 ?d } } GROUP BY ?film ?imdb ORDER BY ?date LIMIT 30");
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String id = People.value(r, "imdb"), title = People.value(r, "title"), date = People.value(r, "date");
            if (!id.startsWith("tt") || title.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            if (x.seriesName.isEmpty()) x.seriesName = People.value(r, "seriesName");
            x.series.add(MediaItem.catalog(id, "movie", title, date.length() >= 4 ? date.substring(0, 4) : ""));
        }
        if (x.series.size() < 2) x.series.clear(); // only this film: nothing to show
    }

    private static void episodeRatings(TitleExtras x, String imdb) throws IOException, JSONException {
        JSONObject show = new JSONObject(get("https://api.tvmaze.com/lookup/shows?imdb=" + imdb, null));
        JSONArray eps = new JSONArray(get("https://api.tvmaze.com/shows/" + show.getInt("id") + "/episodes?specials=0", null));
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.getJSONObject(i);
            JSONObject rating = e.optJSONObject("rating");
            double v = rating == null ? 0 : rating.optDouble("average", 0);
            if (v > 0) x.episodeRatings.put(e.optInt("season") + ":" + e.optInt("number"), String.format(java.util.Locale.US, "%.1f", v));
        }
    }

    /** With the user's TMDB key: TMDB's score, and its recommendations when Trakt had none. */
    private static void tmdb(TitleExtras x, String key, MediaItem m) throws IOException, JSONException {
        JSONObject found = new JSONObject(get("https://api.themoviedb.org/3/find/" + m.id + "?external_source=imdb_id&api_key=" + key, null));
        JSONArray hits = found.optJSONArray(m.isShow() ? "tv_results" : "movie_results");
        if (hits == null || hits.length() == 0) return;
        JSONObject hit = hits.getJSONObject(0);
        double v = hit.optDouble("vote_average", 0);
        if (v > 0 && hit.optInt("vote_count") >= 20) x.tmdbRating = String.format(java.util.Locale.US, "%.1f", v);
        if (!x.related.isEmpty()) return;
        String kind = m.isShow() ? "tv" : "movie";
        JSONArray recs = new JSONObject(get("https://api.themoviedb.org/3/" + kind + "/" + hit.getInt("id")
                + "/recommendations?api_key=" + key, null)).optJSONArray("results");
        if (recs == null) return;
        // TMDB lists TMDB ids; only IMDb-numbered titles open in Phone TV, so each is looked up once.
        for (int i = 0; i < recs.length() && x.related.size() < 12; i++) {
            JSONObject r = recs.getJSONObject(i);
            JSONObject ext = new JSONObject(get("https://api.themoviedb.org/3/" + kind + "/" + r.getInt("id") + "/external_ids?api_key=" + key, null));
            String imdb = ext.optString("imdb_id", "");
            if (!imdb.startsWith("tt")) continue;
            String date = r.optString(m.isShow() ? "first_air_date" : "release_date", "");
            x.related.add(MediaItem.catalog(imdb, m.isShow() ? "series" : "movie", r.optString(m.isShow() ? "name" : "title"),
                    date.length() >= 4 ? date.substring(0, 4) : ""));
        }
    }

    private static String trakt(String path) throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("trakt-api-version", "2");
        h.put("trakt-api-key", TraktClient.APP_ID);
        return get("https://api.trakt.tv" + path, h);
    }

    private static String get(String url, Map<String, String> headers) throws IOException {
        return new String(Http.get(url, headers == null ? new HashMap<>() : headers, 15_000), StandardCharsets.UTF_8);
    }
}
