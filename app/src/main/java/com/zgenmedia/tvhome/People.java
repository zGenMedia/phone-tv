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
import java.util.Locale;
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
    private static final int CAST_KEPT = 80;
    private static final int PROFILE_VERSION = 4;

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
        String quoted = '"' + name.trim().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        // Exact name first; the "also known as" names only when that finds nobody. Names are
        // matched in English and in Wikidata's "mul" (same in every language), where many
        // people's names now live: Denzel Washington's has no English copy at all.
        for (String property : new String[]{"rdfs:label", "skos:altLabel"}) {
            JSONArray rows = query("SELECT ?p ?img (COUNT(?f) AS ?n) WHERE { VALUES ?name { " + quoted + "@en " + quoted + "@mul } ?p " + property + " ?name; wdt:P31 wd:Q5. "
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

    /**
     * Actors whose names match what the user typed, best known first. Wikidata's own
     * name search finds the candidates; only people with screen credits are kept.
     */
    List<Library.Person> search(String typed) throws IOException, JSONException {
        String q = typed.trim();
        if (q.length() < 3) return new ArrayList<>();
        String url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&language=en&uselang=en&limit=25&search=" + Uri.encode(q);
        JSONObject found;
        try {
            found = new JSONObject(new String(Http.get(url, new HashMap<String, String>(), 15_000), StandardCharsets.UTF_8));
        } catch (Http.StatusException e) {
            // Wikidata's name search sheds load with 429s. The exact-name lookup goes through
            // a different service, so a full name still finds its person.
            List<Library.Person> out = new ArrayList<>();
            Library.Person exact = find(q);
            if (exact != null) out.add(exact);
            return out;
        }
        JSONArray hits = found.optJSONArray("search");
        if (hits == null || hits.length() == 0) return new ArrayList<>();
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < hits.length(); i++) ids.append(" wd:").append(hits.getJSONObject(i).optString("id"));
        JSONArray rows = query("SELECT ?p (SAMPLE(?en) AS ?nameEn) (SAMPLE(?mul) AS ?nameMul) (SAMPLE(?img) AS ?photo) (COUNT(DISTINCT ?f) AS ?n) WHERE { VALUES ?p {" + ids + " } "
                + "?p wdt:P31 wd:Q5. ?f wdt:P161 ?p. OPTIONAL { ?p rdfs:label ?en. FILTER(LANG(?en) = \"en\") } "
                + "OPTIONAL { ?p rdfs:label ?mul. FILTER(LANG(?mul) = \"mul\") } OPTIONAL { ?p wdt:P18 ?img } "
                + "} GROUP BY ?p ORDER BY DESC(?n) LIMIT 12");
        List<Library.Person> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            Library.Person p = new Library.Person();
            p.id = value(r, "p").substring(value(r, "p").lastIndexOf('/') + 1);
            if (!seen.add(p.id)) continue;
            p.name = value(r, "nameEn").isEmpty() ? value(r, "nameMul") : value(r, "nameEn");
            if (p.name.isEmpty()) continue;
            String image = value(r, "photo");
            if (!image.isEmpty()) p.photo = image.replaceFirst("^http:", "https:") + "?width=" + PHOTO_WIDTH;
            out.add(p);
        }
        return out;
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

    boolean stale(Library.Person p) {
        if (System.currentTimeMillis() - cache(p).lastModified() > FRESH_MS) return true;
        // Profiles saved before filmographies drew on awards and the catalog are looked up again.
        try { String json = read(cache(p)); return json == null || !json.contains("\"v\":" + PROFILE_VERSION); }
        catch (IOException e) { return true; }
    }

    /**
     * Looks a person up afresh and updates the cache. The credits are what matters and
     * their failure is the caller's to report; the facts and biography are extras, and
     * are simply left empty when they can't be had.
     */
    Profile load(Library.Person p) throws IOException, JSONException {
        JSONArray rows = query("SELECT ?imdb (SAMPLE(?en) AS ?titleEn) (SAMPLE(?mul) AS ?titleMul) (MIN(?d) AS ?date) (SAMPLE(?show) AS ?isShow) WHERE { "
                // Cast lists on Wikidata have gaps (Glory has no Denzel Washington), so films
                // named in their awards and nominations count too.
                + "{ ?f wdt:P161 wd:" + p.id + " } UNION { wd:" + p.id + " (p:P166|p:P1411) ?award. ?award pq:P1686 ?f } "
                + "?f wdt:P345 ?imdb. OPTIONAL { ?f rdfs:label ?en. FILTER(LANG(?en) = \"en\") } "
                + "OPTIONAL { ?f rdfs:label ?mul. FILTER(LANG(?mul) = \"mul\") } "
                + "FILTER NOT EXISTS { ?f wdt:P31 wd:" + TV_EPISODE + " } "
                + "OPTIONAL { ?f wdt:P577 ?d } OPTIONAL { ?f wdt:P580 ?d } "
                + "BIND(EXISTS { ?f wdt:P31/wdt:P279* wd:" + TV_SERIES + " } AS ?show) "
                + "} GROUP BY ?imdb ORDER BY DESC(?date) LIMIT 300");
        JSONArray slim = new JSONArray();
        java.util.Set<String> have = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String imdb = value(r, "imdb"), date = value(r, "date");
            String title = value(r, "titleEn").isEmpty() ? value(r, "titleMul") : value(r, "titleEn");
            if (!imdb.startsWith("tt") || title.isEmpty() || !have.add(imdb)) continue;
            slim.put(new JSONObject().put("id", imdb).put("title", title)
                    .put("year", date.length() >= 4 ? date.substring(0, 4) : "")
                    .put("show", "true".equals(value(r, "isShow"))));
        }
        // The catalog's own search finds films by cast name too, which fills more gaps.
        // Only movies: its show results for a name are mostly talk shows and specials.
        try {
            for (MediaItem m : CatalogRepository.search("movie", p.name)) {
                if (!have.add(m.id) || m.title.toLowerCase(Locale.ROOT).contains(p.name.toLowerCase(Locale.ROOT))) continue;
                slim.put(new JSONObject().put("id", m.id).put("title", m.title).put("year", m.year).put("show", false));
            }
        } catch (IOException | JSONException ignored) { /* Wikidata's list stands on its own */ }
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
        write(file, new JSONObject().put("v", PROFILE_VERSION).put("born", profile.born).put("died", profile.died).put("place", profile.place)
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
                + "OPTIONAL { ?p wdt:P19 ?pl. ?pl rdfs:label ?place. FILTER(LANG(?place) IN (\"en\", \"mul\")) "
                + "OPTIONAL { ?pl wdt:P17 ?c. ?c rdfs:label ?country. FILTER(LANG(?country) IN (\"en\", \"mul\")) } } "
                + "OPTIONAL { ?p schema:description ?about. FILTER(LANG(?about) IN (\"en\", \"mul\")) } "
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
            // Lists kept by earlier versions stand in until the fuller one has been fetched.
            for (String older : new String[]{"cast2-", "cast-"}) {
                if (json == null) json = read(new File(dir, older + imdbId.replaceAll("[^A-Za-z0-9]", "") + ".json"));
            }
            if (json == null) return null;
            JSONArray rows = new JSONArray(json);
            List<Library.Person> out = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                Library.Person p = new Library.Person();
                p.id = r.optString("id").isEmpty() ? null : r.optString("id"); // none: looked up by name when opened
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
    /**
     * Who is in a title, from every source that knows, merged and without repeats. For shows
     * TVmaze comes first: it keeps every show's main cast in billing order with photos and
     * parts, and lists who appeared in each episode, which covers new shows Wikidata hasn't
     * caught up with. Wikidata then adds anyone else, and its ids for those it knows, so their
     * pages open without a name search. If one source fails the other still counts; only
     * when both fail is the old list kept.
     */
    List<Library.Person> cast(String imdbId, boolean show) throws IOException, JSONException {
        if (!imdbId.matches("tt\\d+")) return new ArrayList<>();
        JSONArray fromTvmaze = new JSONArray(), fromWikidata;
        IOException failed = null;
        if (show) {
            try { fromTvmaze = tvmazeCast(imdbId); }
            catch (IOException | JSONException e) { failed = e instanceof IOException ? (IOException) e : new IOException(e.getMessage()); }
        }
        try {
            fromWikidata = wikidataCast(imdbId);
        } catch (IOException | JSONException e) {
            if (fromTvmaze.length() == 0) throw failed != null ? failed : e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
            fromWikidata = new JSONArray();
        }
        JSONArray merged = new JSONArray();
        java.util.Map<String, JSONObject> byName = new java.util.LinkedHashMap<>();
        for (JSONArray source : new JSONArray[]{fromTvmaze, fromWikidata}) {
            for (int i = 0; i < source.length(); i++) {
                JSONObject p = source.getJSONObject(i);
                String key = plain(p.optString("name"));
                JSONObject seen = byName.get(key);
                if (seen == null) { byName.put(key, p); continue; }
                // Same person twice: keep the first entry's place, fill in what it lacks.
                if (seen.optString("id").isEmpty()) seen.put("id", p.optString("id"));
                if (seen.optString("photo").isEmpty()) seen.put("photo", p.optString("photo"));
                if (seen.optString("role").isEmpty()) seen.put("role", p.optString("role"));
            }
        }
        for (JSONObject p : byName.values()) { if (merged.length() >= CAST_KEPT) break; merged.put(p); }
        write(castCache(imdbId), merged.toString());
        return cachedCast(imdbId);
    }

    private static final String TVMAZE = "https://api.tvmaze.com";
    private static final int GUEST_EPISODES = 12;

    /** Main cast in billing order, then those seen most often in the latest episodes. */
    private static JSONArray tvmazeCast(String imdbId) throws IOException, JSONException {
        JSONObject showInfo = new JSONObject(tvmaze("/lookup/shows?imdb=" + imdbId));
        int id = showInfo.getInt("id");
        JSONArray out = new JSONArray();
        JSONArray main = new JSONArray(tvmaze("/shows/" + id + "/cast"));
        for (int i = 0; i < main.length(); i++) out.put(tvmazePerson(main.getJSONObject(i)));
        // Guest and recurring players: counted over the most recent episodes that have aired.
        try {
            JSONArray episodes = new JSONArray(tvmaze("/shows/" + id + "/episodes"));
            java.util.Map<String, JSONObject> guests = new java.util.LinkedHashMap<>();
            java.util.Map<String, Integer> count = new java.util.HashMap<>();
            int asked = 0;
            for (int i = episodes.length() - 1; i >= 0 && asked < GUEST_EPISODES; i--) {
                JSONObject ep = episodes.getJSONObject(i);
                if (ep.optString("airdate").isEmpty() || ep.optString("airdate").compareTo(today()) > 0) continue;
                asked++;
                JSONArray gc = new JSONArray(tvmaze("/episodes/" + ep.getInt("id") + "/guestcast"));
                for (int j = 0; j < gc.length(); j++) {
                    JSONObject p = tvmazePerson(gc.getJSONObject(j));
                    String key = plain(p.optString("name"));
                    if (!guests.containsKey(key)) guests.put(key, p);
                    count.put(key, count.getOrDefault(key, 0) + 1);
                }
            }
            List<String> order = new ArrayList<>(guests.keySet());
            java.util.Collections.sort(order, (x, y) -> Integer.compare(count.get(y), count.get(x))); // stable: first seen wins ties
            for (String key : order) out.put(guests.get(key));
        } catch (IOException | JSONException ignored) { /* the main cast is already in hand */ }
        return out;
    }

    private static JSONObject tvmazePerson(JSONObject credit) throws JSONException {
        JSONObject person = credit.optJSONObject("person"), character = credit.optJSONObject("character");
        JSONObject image = person == null ? null : person.optJSONObject("image");
        String photo = image == null ? "" : image.optString("medium", image.optString("original", ""));
        return new JSONObject().put("id", "").put("name", person == null ? "" : person.optString("name"))
                .put("photo", photo.replaceFirst("^http:", "https:"))
                .put("role", character == null ? "" : character.optString("name"));
    }

    private static String tvmaze(String path) throws IOException {
        try {
            return new String(Http.get(TVMAZE + path, new HashMap<String, String>(), 15_000), StandardCharsets.UTF_8);
        } catch (Http.StatusException e) {
            if (e.code != 429) throw e; // TVmaze allows about 20 calls in 10 seconds
            try { Thread.sleep(2_000); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw e; }
            return new String(Http.get(TVMAZE + path, new HashMap<String, String>(), 15_000), StandardCharsets.UTF_8);
        }
    }

    private static String today() { return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()); }

    private static JSONArray wikidataCast(String imdbId) throws IOException, JSONException {
        // One row per person. Their name is taken in English, else from Wikidata's
        // all-languages field; the part they play likewise. Billing order, where Wikidata
        // records it, comes from the statement's ordinal.
        JSONArray rows = query("SELECT ?p (SAMPLE(?en) AS ?nameEn) (SAMPLE(?mul) AS ?nameMul) (SAMPLE(?img) AS ?photo) "
                + "(SAMPLE(?r) AS ?role) (SAMPLE(?rm) AS ?roleMul) (SAMPLE(?rn) AS ?roleName) (MIN(?o) AS ?order) (MAX(?sl) AS ?fame) WHERE { "
                + "?f wdt:P345 \"" + imdbId + "\"; p:P161 ?st. ?st ps:P161 ?p. "
                + "OPTIONAL { ?p rdfs:label ?en. FILTER(LANG(?en) = \"en\") } OPTIONAL { ?p rdfs:label ?mul. FILTER(LANG(?mul) = \"mul\") } "
                + "OPTIONAL { ?p wdt:P18 ?img } OPTIONAL { ?p wikibase:sitelinks ?sl } "
                + "OPTIONAL { ?st pq:P453 ?c. OPTIONAL { ?c rdfs:label ?r. FILTER(LANG(?r) = \"en\") } OPTIONAL { ?c rdfs:label ?rm. FILTER(LANG(?rm) = \"mul\") } } "
                + "OPTIONAL { ?st pq:P4633 ?rn } OPTIONAL { ?st pq:P1545 ?ord. BIND(xsd:integer(?ord) AS ?o) } "
                + "} GROUP BY ?p");
        List<JSONObject> people = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String name = value(r, "nameEn").isEmpty() ? value(r, "nameMul") : value(r, "nameEn");
            if (name.isEmpty()) continue; // no name we can show
            String image = value(r, "photo");
            String role = !value(r, "role").isEmpty() ? value(r, "role") : !value(r, "roleMul").isEmpty() ? value(r, "roleMul") : value(r, "roleName");
            int order;
            try { order = Integer.parseInt(value(r, "order")); } catch (NumberFormatException e) { order = Integer.MAX_VALUE; }
            people.add(new JSONObject().put("id", value(r, "p").substring(value(r, "p").lastIndexOf('/') + 1)).put("name", name)
                    .put("photo", image.isEmpty() ? "" : image.replaceFirst("^http:", "https:") + "?width=" + PHOTO_WIDTH)
                    .put("role", role).put("order", order).put("fame", r.has("fame") ? Integer.parseInt(value(r, "fame")) : 0));
        }
        // Billed first, in order. Wikidata rarely records billing, so the rest go best known
        // first (how many Wikipedias have an article on them), which puts the stars ahead of
        // the bit parts, and faces ahead of initials among equals.
        java.util.Collections.sort(people, (x, y) -> {
            int byOrder = Integer.compare(x.optInt("order"), y.optInt("order"));
            if (byOrder != 0) return byOrder;
            int byFame = Integer.compare(y.optInt("fame"), x.optInt("fame"));
            if (byFame != 0) return byFame;
            return Boolean.compare(x.optString("photo").isEmpty(), y.optString("photo").isEmpty());
        });
        JSONArray slim = new JSONArray();
        for (int i = 0; i < people.size() && i < CAST_KEPT; i++) slim.put(people.get(i));
        return slim;
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
        out.addAll(rest); // already in billing order, faces ahead of initials (see cast())
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

    private File castCache(String imdbId) { return new File(dir, "cast3-" + imdbId.replaceAll("[^A-Za-z0-9]", "") + ".json"); }

    private static JSONArray query(String sparql) throws IOException, JSONException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/sparql-results+json");
        byte[] body;
        try {
            body = Http.get(ENDPOINT + "?query=" + Uri.encode(sparql), headers, 30_000);
        } catch (Http.StatusException e) {
            // Wikidata turns busy clients away with 429 or 503 for a moment; one patient retry
            // gets most lookups through instead of showing a thinner page.
            if (e.code != 429 && e.code != 503) throw e;
            try { Thread.sleep(1_500); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw e; }
            body = Http.get(ENDPOINT + "?query=" + Uri.encode(sparql), headers, 30_000);
        }
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
