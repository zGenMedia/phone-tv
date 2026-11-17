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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Actors: who is in a title, and who they are, from Wikidata.
 *
 * The catalog only gives a few cast names. Wikidata lists a title's cast as
 * people (with photos and the parts they play), and for each person their
 * films and shows by IMDb id, which is all the rest of the app needs for
 * artwork and details. A bare name, typed or from the catalog, is matched to
 * the person with that exact name and the most screen credits. The short
 * biography is the opening of their Wikipedia article. No key or account.
 *
 * Wikidata is edited by volunteers: famous actors are well covered, obscure
 * ones can be thin or missing. Everything fetched is cached on disk, so a
 * saved actor opens instantly and still works offline. All lookups block.
 */
final class People {
    private static final String ENDPOINT = "https://query.wikidata.org/sparql";
    private static final String TV_SERIES = "Q5398426", TV_EPISODE = "Q21191270";
    private static final long FRESH_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final int PHOTO_WIDTH = 330; // Wikimedia only serves a fixed set of thumbnail widths
    private static final int CAST_KEPT = 60;

    /** What is known about a person beyond their name. Any field may be empty. */
    static final class Profile {
        String born = "", died = "";    // as much of the date as Wikidata vouches for: "1972-10-29", "1972-10" or "1972"
        String place = "";              // where they were born, e.g. "Omaha, United States"
        String about = "";              // one line, e.g. "American actress"
        String text = "", textUrl = ""; // opening of their Wikipedia article, and the article itself
        List<MediaItem> credits = new ArrayList<>(); // films and shows, newest first
    }

    private final File dir;

    People(Context context) {
        dir = new File(context.getFilesDir(), "people");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** @return the actor with this name, or null if Wikidata knows none. */
    Library.Person find(String name) throws IOException, JSONException {
        String literal = '"' + name.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\"@en";
        // Exact name first; the "also known as" names only when that finds nobody.
        for (String property : new String[]{"rdfs:label", "skos:altLabel"}) {
            JSONArray rows = query("SELECT ?p ?img (COUNT(?f) AS ?n) WHERE { ?p " + property + " " + literal + "; wdt:P31 wd:Q5. "
                    + "?f wdt:P161 ?p. OPTIONAL { ?p wdt:P18 ?img } } GROUP BY ?p ?img ORDER BY DESC(?n) LIMIT 1");
            if (rows.length() == 0) continue;
            JSONObject r = rows.getJSONObject(0);
            Library.Person p = new Library.Person();
            p.id = value(r, "p").substring(value(r, "p").lastIndexOf('/') + 1);
            p.name = name.trim();
            String image = value(r, "img");
            if (!image.isEmpty()) p.photo = image.replaceFirst("^http:", "https:") + "?width=" + PHOTO_WIDTH;
            return p;
        }
        return null;
    }

    /** The page on Wikimedia Commons that credits a photo's author and licence, or null. */
    static String photoCredit(Library.Person p) {
        int at = p.photo.indexOf("Special:FilePath/");
        if (at < 0) return null;
        String file = p.photo.substring(at + "Special:FilePath/".length()).replaceFirst("\\?.*$", "");
        return "https://commons.wikimedia.org/wiki/File:" + file;
    }

    // ------------------------------------------------------------- one person

    /** The profile from the last successful lookup, or null if there hasn't been one. */
    Profile cached(Library.Person p) {
        try {
            String json = read(cache(p));
            if (json == null) return null;
            Profile profile = new Profile();
            if (json.startsWith("[")) { // written by the first preview: credits only
                profile.credits = parse(new JSONArray(json));
                return profile;
            }
            JSONObject o = new JSONObject(json);
            profile.born = o.optString("born");
            profile.died = o.optString("died");
            profile.place = o.optString("place");
            profile.about = o.optString("about");
            profile.text = o.optString("text");
            profile.textUrl = o.optString("textUrl");
            profile.credits = parse(o.getJSONArray("credits"));
            return profile;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    boolean stale(Library.Person p) { return System.currentTimeMillis() - cache(p).lastModified() > FRESH_MS; }

    /**
     * Looks a person up afresh and updates the cache. The credits are what matters and
     * their failure is the caller's to report; the facts and biography are extras, and
     * are simply left empty when they can't be had.
     */
    Profile load(Library.Person p) throws IOException, JSONException {
        JSONArray rows = query("SELECT ?imdb ?title (MIN(?d) AS ?date) (SAMPLE(?show) AS ?isShow) WHERE { "
                + "?f wdt:P161 wd:" + p.id + "; wdt:P345 ?imdb; rdfs:label ?title. FILTER(LANG(?title) = \"en\") "
                + "FILTER NOT EXISTS { ?f wdt:P31 wd:" + TV_EPISODE + " } "
                + "OPTIONAL { ?f wdt:P577 ?d } OPTIONAL { ?f wdt:P580 ?d } "
                + "BIND(EXISTS { ?f wdt:P31/wdt:P279* wd:" + TV_SERIES + " } AS ?show) "
                + "} GROUP BY ?imdb ?title ORDER BY DESC(?date) LIMIT 250");
        JSONArray slim = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String imdb = value(r, "imdb"), date = value(r, "date");
            if (!imdb.startsWith("tt")) continue;
            slim.put(new JSONObject().put("id", imdb).put("title", value(r, "title"))
                    .put("year", date.length() >= 4 ? date.substring(0, 4) : "")
                    .put("show", "true".equals(value(r, "isShow"))));
        }
        Profile before = cached(p);
        Profile profile = new Profile();
        profile.credits = parse(slim);
        boolean whole = true;
        try {
            facts(p, profile);
        } catch (IOException | JSONException e) {
            // Wikipedia in particular turns requests away when it is busy. Whatever was
            // learned on an earlier visit is kept rather than blanked.
            whole = false;
            if (before != null) {
                if (profile.born.isEmpty()) profile.born = before.born;
                if (profile.died.isEmpty()) profile.died = before.died;
                if (profile.place.isEmpty()) profile.place = before.place;
                if (profile.about.isEmpty()) profile.about = before.about;
                if (profile.text.isEmpty()) { profile.text = before.text; profile.textUrl = before.textUrl; }
            }
        }
        File file = cache(p);
        write(file, new JSONObject().put("born", profile.born).put("died", profile.died).put("place", profile.place)
                .put("about", profile.about).put("text", profile.text).put("textUrl", profile.textUrl)
                .put("credits", slim).toString());
        // An incomplete answer is dated as already old, so the next visit asks again instead of waiting a week.
        if (!whole) //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis() - FRESH_MS - 1);
        return profile;
    }

    /** Birth, death, birthplace and a one-line description from Wikidata; the biography from Wikipedia. */
    private void facts(Library.Person p, Profile into) throws IOException, JSONException {
        JSONArray rows = query("SELECT ?born ?bornPrecision ?died ?diedPrecision ?place ?country ?about ?wiki WHERE { BIND(wd:" + p.id + " AS ?p) "
                + "OPTIONAL { ?p p:P569/psv:P569 [ wikibase:timeValue ?born; wikibase:timePrecision ?bornPrecision ] } "
                + "OPTIONAL { ?p p:P570/psv:P570 [ wikibase:timeValue ?died; wikibase:timePrecision ?diedPrecision ] } "
                + "OPTIONAL { ?p wdt:P19 ?pl. ?pl rdfs:label ?place. FILTER(LANG(?place) = \"en\") "
                + "OPTIONAL { ?pl wdt:P17 ?c. ?c rdfs:label ?country. FILTER(LANG(?country) = \"en\") } } "
                + "OPTIONAL { ?p schema:description ?about. FILTER(LANG(?about) = \"en\") } "
                + "OPTIONAL { ?article schema:about ?p; schema:isPartOf <https://en.wikipedia.org/>; schema:name ?wiki } } LIMIT 1");
        if (rows.length() == 0) return;
        JSONObject r = rows.getJSONObject(0);
        into.born = date(value(r, "born"), value(r, "bornPrecision"));
        into.died = date(value(r, "died"), value(r, "diedPrecision"));
        String place = value(r, "place"), country = value(r, "country");
        into.place = country.isEmpty() || country.equals(place) ? place : place.isEmpty() ? country : place + ", " + country;
        into.about = value(r, "about");
        String article = value(r, "wiki");
        if (article.isEmpty()) return;
        JSONObject summary = new JSONObject(new String(Http.get("https://en.wikipedia.org/api/rest_v1/page/summary/"
                + Uri.encode(article.replace(' ', '_')), new HashMap<String, String>(), 20_000), StandardCharsets.UTF_8));
        into.text = summary.optString("extract").trim();
        JSONObject links = summary.optJSONObject("content_urls");
        JSONObject desktop = links == null ? null : links.optJSONObject("desktop");
        into.textUrl = desktop == null ? "" : desktop.optString("page");
    }

    /** Cuts a Wikidata timestamp to the part it is sure of: precision 11 is a day, 10 a month, 9 a year. */
    private static String date(String stamp, String precision) {
        if (stamp.length() < 10 || stamp.startsWith("-")) return "";
        int sure;
        try { sure = Integer.parseInt(precision); } catch (NumberFormatException e) { sure = 9; }
        return sure < 9 ? "" : stamp.substring(0, sure >= 11 ? 10 : sure == 10 ? 7 : 4);
    }

    // ------------------------------------------------------- a title's cast

    /** The cast from the last successful lookup, or null if there hasn't been one. */
    List<Library.Person> cachedCast(String imdbId) {
        try {
            String json = read(castCache(imdbId));
            if (json == null) return null;
            JSONArray rows = new JSONArray(json);
            List<Library.Person> out = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                Library.Person p = new Library.Person();
                p.id = r.getString("id");
                p.name = r.optString("name");
                p.photo = r.optString("photo");
                p.role = r.optString("role");
                out.add(p);
            }
            return out;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    boolean castStale(String imdbId) { return System.currentTimeMillis() - castCache(imdbId).lastModified() > FRESH_MS; }

    /** Who is in a title, with photos and the parts they play, in one request. Updates the cache. */
    List<Library.Person> cast(String imdbId) throws IOException, JSONException {
        if (!imdbId.matches("tt\\d+")) return new ArrayList<>();
        JSONArray rows = query("SELECT ?p ?name ?img ?role ?roleName WHERE { ?f wdt:P345 \"" + imdbId + "\"; p:P161 ?st. ?st ps:P161 ?p. "
                + "?p rdfs:label ?name. FILTER(LANG(?name) = \"en\") OPTIONAL { ?p wdt:P18 ?img } "
                + "OPTIONAL { ?st pq:P453 ?c. ?c rdfs:label ?role. FILTER(LANG(?role) = \"en\") } "
                + "OPTIONAL { ?st pq:P4633 ?roleName } } LIMIT 300");
        JSONArray slim = new JSONArray();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < rows.length() && slim.length() < CAST_KEPT; i++) {
            JSONObject r = rows.getJSONObject(i);
            String id = value(r, "p").substring(value(r, "p").lastIndexOf('/') + 1);
            if (seen.contains(id)) continue; // a second photo or a second part repeats the person
            seen.add(id);
            String image = value(r, "img"), role = value(r, "role");
            slim.put(new JSONObject().put("id", id).put("name", value(r, "name"))
                    .put("photo", image.isEmpty() ? "" : image.replaceFirst("^http:", "https:") + "?width=" + PHOTO_WIDTH)
                    .put("role", role.isEmpty() ? value(r, "roleName") : role));
        }
        write(castCache(imdbId), slim.toString());
        return cachedCast(imdbId);
    }

    /**
     * The cast in the order it should be shown: the catalog's names first, since those are
     * the top-billed ones (Wikidata keeps no billing order), each swapped for the person
     * Wikidata knows by that name when there is one; then everyone else Wikidata lists.
     */
    static List<Library.Person> billed(List<String> names, List<Library.Person> known, int max) {
        List<Library.Person> rest = new ArrayList<>(known == null ? new ArrayList<Library.Person>() : known);
        List<Library.Person> out = new ArrayList<>();
        for (String name : names) {
            Library.Person match = null;
            for (Library.Person p : rest) if (plain(p.name).equals(plain(name))) { match = p; break; }
            if (match != null) rest.remove(match);
            else { match = new Library.Person(); match.name = name; } // no id: looked up by name when opened
            out.add(match);
        }
        // Among the others, faces first: a row of photos reads better than a row of initials.
        for (Library.Person p : rest) if (!p.photo.isEmpty()) out.add(p);
        for (Library.Person p : rest) if (p.photo.isEmpty()) out.add(p);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** A name without accents or case, so "Ismaël Cruz Córdova" and "Ismael Cruz Cordova" are the same person. */
    private static String plain(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT).trim();
    }

    // ------------------------------------------------------------- plumbing

    private static List<MediaItem> parse(JSONArray rows) throws JSONException {
        List<MediaItem> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String id = r.getString("id");
            if (seen.contains(id)) continue; // one title can carry several English labels
            seen.add(id);
            out.add(MediaItem.catalog(id, r.optBoolean("show") ? "series" : "movie", r.optString("title"), r.optString("year")));
        }
        return out;
    }

    private File cache(Library.Person p) { return new File(dir, p.id.replaceAll("[^A-Za-z0-9]", "") + ".json"); }

    private File castCache(String imdbId) { return new File(dir, "cast-" + imdbId.replaceAll("[^A-Za-z0-9]", "") + ".json"); }

    private static JSONArray query(String sparql) throws IOException, JSONException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/sparql-results+json");
        byte[] body = Http.get(ENDPOINT + "?query=" + Uri.encode(sparql), headers, 30_000);
        return new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONObject("results").getJSONArray("bindings");
    }

    private static String value(JSONObject row, String name) {
        JSONObject cell = row.optJSONObject(name);
        return cell == null ? "" : cell.optString("value", "");
    }

    private static String read(File f) throws IOException {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) { return new String(Http.readAll(in), StandardCharsets.UTF_8); }
    }

    private static void write(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(s.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { /* the cache is best-effort */ }
    }
}
