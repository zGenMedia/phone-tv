package com.zgenmedia.tvhome;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Tune the hero": the user types two or three words ("asian romance",
 * "latest spanish", "nigerian comedy") and the hero fills with matching titles.
 *
 * Words are matched against a small vocabulary of languages, countries, genres
 * and time words, then turned into Wikidata SPARQL queries (free, no API key).
 * Matches are enriched with Cinemeta metadata so the hero keeps full artwork,
 * logos and synopses.
 *
 * The lookup sits behind {@link Source} so a zGenMedia-hosted service (e.g.
 * TMDB-backed, with better coverage of African-American cinema) can replace
 * Wikidata later without touching the UI.
 *
 * All network methods block; call them from a background thread.
 */
final class HeroMood {

    /** Where matching titles come from. Wikidata today; a hosted service later. */
    interface Source {
        List<String> findImdbIds(Query q, int limit) throws IOException, JSONException;
    }

    /** Parsed form of what the user typed. Each set is OR within itself, AND across sets. */
    static final class Query {
        final Set<String> languages = new LinkedHashSet<>();   // Wikidata QIDs, P364
        final Set<String> countries = new LinkedHashSet<>();   // P495
        final Set<String> genres = new LinkedHashSet<>();      // P136
        final Set<String> subjects = new LinkedHashSet<>();    // P921
        final List<String> matched = new ArrayList<>();        // words we understood
        final List<String> unknown = new ArrayList<>();        // words we didn't
        boolean series;
        int fromYear, toYear;

        boolean isEmpty() { return languages.isEmpty() && countries.isEmpty() && genres.isEmpty() && subjects.isEmpty(); }
    }

    // ------------------------------------------------------------- vocabulary
    // QIDs verified against Wikidata labels. Keep entries lowercase.

    private static final Map<String, String[]> LANGUAGES = new HashMap<>();
    private static final Map<String, String[]> COUNTRIES = new HashMap<>();
    private static final Map<String, String[]> GENRES = new HashMap<>();
    private static final Set<String> FILLER = new LinkedHashSet<>();

    static {
        String ko = "Q9176", ja = "Q5287", zh = "Q7850", th = "Q9217", hi = "Q1568", tl = "Q34057", ml = "Q36236";
        lang(new String[]{"spanish", "espanol", "español"}, "Q1321");
        lang(new String[]{"korean", "kdrama", "k-drama"}, ko);
        lang(new String[]{"japanese", "anime"}, ja);
        lang(new String[]{"chinese", "mandarin", "cantonese"}, zh);
        lang(new String[]{"thai"}, th);
        lang(new String[]{"hindi", "bollywood"}, hi);
        lang(new String[]{"tagalog", "filipino"}, tl);
        lang(new String[]{"malayalam"}, ml);
        lang(new String[]{"french"}, "Q150");
        lang(new String[]{"italian"}, "Q652");
        lang(new String[]{"german"}, "Q188");
        lang(new String[]{"portuguese", "brazilian"}, "Q5146", "Q750553");
        lang(new String[]{"asian"}, ko, ja, zh, th, hi, tl);
        lang(new String[]{"latin", "latino", "latina", "hispanic"}, "Q1321", "Q750553");

        country(new String[]{"mexican", "mexico"}, "Q96");
        country(new String[]{"spain"}, "Q29");
        country(new String[]{"colombian", "colombia"}, "Q739");
        country(new String[]{"argentine", "argentinian", "argentina"}, "Q414");
        country(new String[]{"chilean", "chile"}, "Q298");
        country(new String[]{"japan"}, "Q17");
        country(new String[]{"china"}, "Q148");
        country(new String[]{"korea"}, "Q884");
        country(new String[]{"india", "indian"}, "Q668");
        country(new String[]{"thailand"}, "Q869");
        country(new String[]{"vietnamese", "vietnam"}, "Q881");
        country(new String[]{"indonesian", "indonesia"}, "Q252");
        country(new String[]{"philippines"}, "Q928");
        country(new String[]{"nigerian", "nigeria", "nollywood"}, "Q1033");
        country(new String[]{"ghanaian", "ghana"}, "Q117");
        country(new String[]{"south-african", "southafrican"}, "Q258");
        country(new String[]{"african", "africa"}, "Q1033", "Q117", "Q258");

        genre(new String[]{"romance", "romantic", "love"}, "Q1054574");
        genre(new String[]{"romcom", "rom-com"}, "Q860626");
        genre(new String[]{"horror", "scary"}, "Q200092");
        genre(new String[]{"comedy", "comedies", "funny"}, "Q157443");
        genre(new String[]{"action"}, "Q188473");
        genre(new String[]{"drama", "dramas"}, "Q130232");
        genre(new String[]{"thriller", "thrillers", "suspense"}, "Q2484376");
        genre(new String[]{"animated", "animation", "cartoon", "anime"}, "Q202866");
        genre(new String[]{"documentary", "documentaries", "docs"}, "Q93204");
        genre(new String[]{"scifi", "sci-fi", "science-fiction"}, "Q471839");
        genre(new String[]{"biopic", "biographical"}, "Q645928");

        Collections.addAll(FILLER, "film", "films", "movie", "movies", "the", "and", "with", "some", "of", "in", "a", "an");
    }

    private static void lang(String[] words, String... q) { for (String w : words) LANGUAGES.put(w, q); }
    private static void country(String[] words, String... q) { for (String w : words) COUNTRIES.put(w, q); }
    private static void genre(String[] words, String... q) { for (String w : words) GENRES.put(w, q); }

    /** Wikidata subject + genres used for "black films"; coverage of recent US titles is thin. */
    private static final String AFRICAN_AMERICANS = "Q49085";
    private static final String[] BLACK_CINEMA_GENRES = {"Q124693085", "Q5897543", "Q883179"};

    // ----------------------------------------------------------------- parse

    static Query parse(String text) {
        Query q = new Query();
        int year = Calendar.getInstance().get(Calendar.YEAR);
        q.fromYear = year - 4;
        q.toYear = year;
        String norm = text.toLowerCase(Locale.ROOT)
                .replace("south african", "south-african").replace("science fiction", "science-fiction")
                .replace("k drama", "kdrama").replace("rom com", "romcom");
        for (String w : norm.split("[^\\p{L}\\p{N}-]+")) {
            if (w.isEmpty() || FILLER.contains(w)) continue;
            boolean hit = false;
            if (LANGUAGES.containsKey(w)) { Collections.addAll(q.languages, LANGUAGES.get(w)); hit = true; }
            if (COUNTRIES.containsKey(w)) { Collections.addAll(q.countries, COUNTRIES.get(w)); hit = true; }
            if (GENRES.containsKey(w)) { Collections.addAll(q.genres, GENRES.get(w)); hit = true; }
            switch (w) {
                case "black": case "african-american": case "africanamerican":
                    q.subjects.add(AFRICAN_AMERICANS); hit = true; break;
                case "latest": case "new": case "newest": case "recent":
                    q.fromYear = year - 1; hit = true; break;
                case "classic": case "classics": case "old": case "retro":
                    q.fromYear = 1950; q.toYear = 1999; hit = true; break;
                case "show": case "shows": case "series": case "tv": case "kdrama": case "k-drama":
                    q.series = true; hit = true; break;
                default:
                    if (w.matches("(19|20)\\d\\d")) { q.fromYear = q.toYear = Integer.parseInt(w); hit = true; }
                    else if (w.matches("(19|20)\\d0s")) { q.fromYear = Integer.parseInt(w.substring(0, 4)); q.toYear = q.fromYear + 9; hit = true; }
            }
            (hit ? q.matched : q.unknown).add(w);
        }
        // Genre-only searches over all of film history are slow and vague; keep them recent.
        if (q.languages.isEmpty() && q.countries.isEmpty() && q.subjects.isEmpty() && q.fromYear < year - 4) q.fromYear = Math.max(q.fromYear, year - 10);
        return q;
    }

    // -------------------------------------------------------------- Wikidata

    static final class WikidataSource implements Source {
        private static final String ENDPOINT = "https://query.wikidata.org/sparql";

        @Override
        public List<String> findImdbIds(Query q, int limit) throws IOException, JSONException {
            // Each "branch" starts the query from its most selective pattern. Wikidata
            // times out on broad UNIONs, so branches run as separate small queries.
            List<String> branches = new ArrayList<>();
            if (!q.subjects.isEmpty()) {
                branches.add("?f wdt:P921 wd:" + AFRICAN_AMERICANS + ".");
                branches.add("VALUES ?bg { " + qids(BLACK_CINEMA_GENRES) + " } ?f wdt:P136 ?bg.");
            } else if (!q.languages.isEmpty()) {
                branches.add("VALUES ?l { " + qids(q.languages) + " } ?f wdt:P364 ?l.");
            } else if (!q.countries.isEmpty()) {
                branches.add("VALUES ?c { " + qids(q.countries) + " } ?f wdt:P495 ?c.");
            } else {
                branches.add("VALUES ?g { " + qids(q.genres) + " } ?f wdt:P136 ?g.");
            }

            // Constraints shared by every branch (AND across categories).
            StringBuilder common = new StringBuilder();
            if (!q.subjects.isEmpty() && !q.languages.isEmpty()) common.append("VALUES ?l { ").append(qids(q.languages)).append(" } ?f wdt:P364 ?l. ");
            if (!q.countries.isEmpty() && (!q.languages.isEmpty() || !q.subjects.isEmpty()))
                common.append("VALUES ?c { ").append(qids(q.countries)).append(" } ?f wdt:P495 ?c. ");
            if (!q.genres.isEmpty() && !(q.languages.isEmpty() && q.countries.isEmpty() && q.subjects.isEmpty()))
                common.append("VALUES ?g { ").append(qids(q.genres)).append(" } ?f wdt:P136 ?g. ");
            String type = q.series ? "wd:Q5398426" : "wd:Q11424";

            Map<String, String> newest = new LinkedHashMap<>(); // imdb -> latest date
            for (String branch : branches) {
                String sparql = "SELECT ?imdb (MAX(?d) AS ?date) WHERE { " + branch + " " + common
                        + "?f wdt:P31 " + type + "; wdt:P345 ?imdb; wdt:P577 ?d. "
                        + "FILTER(?d >= \"" + q.fromYear + "-01-01\"^^xsd:dateTime && ?d <= NOW() && ?d <= \"" + q.toYear + "-12-31\"^^xsd:dateTime) "
                        + "} GROUP BY ?imdb ORDER BY DESC(?date) LIMIT " + limit;
                Map<String, String> h = new HashMap<>();
                h.put("Accept", "application/sparql-results+json");
                byte[] body = Http.get(ENDPOINT + "?query=" + Uri.encode(sparql), h, 30_000);
                JSONArray rows = new JSONObject(new String(body, StandardCharsets.UTF_8))
                        .getJSONObject("results").getJSONArray("bindings");
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    String id = r.getJSONObject("imdb").getString("value");
                    String date = r.getJSONObject("date").getString("value");
                    if (id.startsWith("tt") && (!newest.containsKey(id) || date.compareTo(newest.get(id)) > 0)) newest.put(id, date);
                }
            }
            List<Map.Entry<String, String>> sorted = new ArrayList<>(newest.entrySet());
            Collections.sort(sorted, (a, b) -> b.getValue().compareTo(a.getValue()));
            List<String> ids = new ArrayList<>();
            for (Map.Entry<String, String> e : sorted) if (ids.size() < limit) ids.add(e.getKey());
            return ids;
        }

        private static String qids(Iterable<String> ids) {
            StringBuilder sb = new StringBuilder();
            for (String id : ids) sb.append("wd:").append(id).append(' ');
            return sb.toString().trim();
        }

        private static String qids(String[] ids) {
            List<String> l = new ArrayList<>();
            Collections.addAll(l, ids);
            return qids(l);
        }
    }

    // ------------------------------------------------------------ resolution

    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1000L;
    private final File dir;
    private final Source source;

    HeroMood(Context ctx, Source source) { this.dir = new File(ctx.getFilesDir(), "mood"); this.source = source; dir.mkdirs(); }

    /**
     * Resolves typed words to titles with artwork and synopsis. Results are
     * cached per phrase for 12 hours; a stale cache is used if the network fails.
     */
    List<MediaItem> resolve(String text, int want) throws IOException, JSONException {
        Query q = parse(text);
        if (q.isEmpty()) throw new IOException("no recognizable words in \"" + text.trim() + "\"");
        File cache = new File(dir, Integer.toHexString(text.trim().toLowerCase(Locale.ROOT).hashCode()) + ".json");
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS) {
            List<MediaItem> cached = fromJson(read(cache));
            if (!cached.isEmpty()) return cached;
        }
        try {
            List<String> ids = source.findImdbIds(q, want * 2);
            JSONArray metas = new JSONArray();
            String kind = q.series ? "series" : "movie";
            for (String id : ids) {
                if (metas.length() >= want) break;
                try {
                    JSONObject meta = new JSONObject(new String(Http.get(
                            "https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json"), StandardCharsets.UTF_8)).optJSONObject("meta");
                    // Skip titles Cinemeta doesn't know or has no backdrop for: they'd look broken in the hero.
                    if (meta != null && !meta.optString("background").isEmpty()) metas.put(meta);
                } catch (IOException | JSONException ignored) { /* skip this title */ }
            }
            write(cache, metas.toString());
            return fromJson(metas.toString());
        } catch (IOException | JSONException e) {
            if (cache.exists()) return fromJson(read(cache));
            throw e;
        }
    }

    private static List<MediaItem> fromJson(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<MediaItem> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            MediaItem m = new MediaItem(MediaItem.Source.CATALOG);
            m.id = o.optString("imdb_id", o.optString("id"));
            m.type = o.optString("type", "movie");
            m.title = o.optString("name");
            m.summary = o.optString("description", "");
            m.year = o.optString("releaseInfo", o.optString("year", ""));
            m.rating = o.optString("imdbRating", "");
            JSONArray g = o.optJSONArray("genres");
            if (g == null) g = o.optJSONArray("genre");
            if (g != null && g.length() > 0) m.genres = g.optString(0) + (g.length() > 1 ? ", " + g.optString(1) : "");
            m.posterRef = o.optString("poster", "");
            m.backdropRef = o.optString("background", "");
            m.logoRef = o.optString("logo", "");
            if (!m.id.isEmpty() && !m.title.isEmpty()) out.add(m);
        }
        return out;
    }

    private static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) { return new String(Http.readAll(in), StandardCharsets.UTF_8); }
    }

    private static void write(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(s.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { /* cache is best-effort */ }
    }
}
