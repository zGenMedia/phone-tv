package com.zgenmedia.tvhome;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Networks and production companies: who made a title and who first showed it, and
 * everything else that company has made.
 *
 * Both come from Wikidata, which needs no key: "original broadcaster" is the network
 * (Netflix, HBO, AMC) and "production company" is the studio (A24, Pixar). A company's
 * list of titles is kept on disk for a week, so a saved studio opens at once.
 *
 * All calls block; run them off the main thread.
 */
final class Studios {
    static final String NETWORK = "network", STUDIO = "studio";
    private static final long FRESH_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MOST = 120; // titles per company: its best known work, not its whole ledger

    private final File dir;

    Studios(Context c) {
        dir = new File(c.getCacheDir(), "studios");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** The networks, then the studios, behind one title. A company that is both shows once, as the network. */
    static List<Library.Company> behind(String imdb) throws IOException, JSONException {
        JSONArray rows = People.sparql("SELECT ?c ?kind (SAMPLE(?n) AS ?name) (SAMPLE(?l) AS ?logo) WHERE { "
                + "?t wdt:P345 \"" + imdb + "\". "
                + "{ ?t wdt:P449 ?c. BIND(\"" + NETWORK + "\" AS ?kind) } UNION { ?t wdt:P272 ?c. BIND(\"" + STUDIO + "\" AS ?kind) } "
                + "OPTIONAL { ?c rdfs:label ?n. FILTER(LANG(?n) IN (\"en\", \"mul\")) } "
                + "OPTIONAL { ?c wdt:P154 ?l } } GROUP BY ?c ?kind LIMIT 16");
        Map<String, Library.Company> found = new LinkedHashMap<>();
        for (String wanted : new String[]{NETWORK, STUDIO}) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                if (!wanted.equals(People.value(r, "kind"))) continue;
                String entity = People.value(r, "c"), name = People.value(r, "name");
                String id = entity.substring(entity.lastIndexOf('/') + 1);
                if (name.isEmpty() || !id.startsWith("Q") || found.containsKey(id)) continue;
                Library.Company c = new Library.Company();
                c.id = id;
                c.name = name;
                c.kind = wanted;
                c.logo = logo(People.value(r, "logo"));
                found.put(id, c);
            }
        }
        return new ArrayList<>(found.values());
    }

    /**
     * Wikidata gives a logo as a file on Wikimedia Commons, usually an SVG. Asking Commons
     * for it at a width returns a small PNG of it instead, which is what a card can show.
     */
    private static String logo(String commons) {
        if (commons.isEmpty()) return "";
        return commons.replace("http://", "https://") + "?width=360";
    }

    /** What was fetched last time, however old, or null. Cheap enough for the main thread. */
    List<MediaItem> cached(Library.Company c) {
        File f = file(c);
        if (!f.exists()) return null;
        try { return parse(new JSONArray(new String(readAll(f), StandardCharsets.UTF_8))); }
        catch (IOException | JSONException e) { return null; }
    }

    boolean stale(Library.Company c) {
        File f = file(c);
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > FRESH_MS;
    }

    /**
     * A company's titles, best known first (by how many Wikipedias write about them, which
     * tracks fame well and costs nothing extra to ask for). Saved to disk on the way out.
     */
    List<MediaItem> titles(Library.Company c) throws IOException, JSONException {
        JSONArray rows = People.sparql("SELECT ?imdb (SAMPLE(?t) AS ?title) (MIN(?d) AS ?date) (MAX(?links) AS ?fame) (SAMPLE(?aired) AS ?show) (MAX(?mins) AS ?minutes) WHERE { "
                + "?f wdt:P272|wdt:P449 wd:" + c.id + "; wdt:P345 ?imdb; wikibase:sitelinks ?links. FILTER(STRSTARTS(?imdb, \"tt\")) "
                + "OPTIONAL { ?f rdfs:label ?t. FILTER(LANG(?t) IN (\"en\", \"mul\")) } "
                + "OPTIONAL { ?f wdt:P577|wdt:P580 ?d } "
                + "OPTIONAL { ?f wdt:P2047 ?mins } " // running time, which the channel guide schedules by
                + "BIND(EXISTS { ?f wdt:P449 [] } AS ?aired) } GROUP BY ?imdb ORDER BY DESC(?fame) LIMIT " + MOST);
        JSONArray keep = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String title = People.value(r, "title"), date = People.value(r, "date");
            if (title.isEmpty()) continue;
            int minutes = 0;
            try { minutes = (int) Math.round(Double.parseDouble(People.value(r, "minutes"))); } catch (NumberFormatException unknown) { /* stays 0 */ }
            keep.put(new JSONObject().put("id", People.value(r, "imdb")).put("title", title).put("minutes", minutes)
                    .put("year", date.length() >= 4 ? date.substring(0, 4) : "")
                    // Something with an original broadcaster aired on television: a show.
                    .put("type", "true".equals(People.value(r, "show")) ? "series" : "movie"));
        }
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(keep.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { /* fetched again next time */ }
        return parse(keep);
    }

    private static List<MediaItem> parse(JSONArray arr) throws JSONException {
        List<MediaItem> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            MediaItem m = MediaItem.catalog(o.getString("id"), o.optString("type", "movie"), o.optString("title"), o.optString("year"));
            m.minutes = o.optInt("minutes");
            out.add(m);
        }
        return out;
    }

    /** "-2": the second shape of this file (running times were added); older files are just fetched again. */
    private File file(Library.Company c) { return new File(dir, c.id.replaceAll("[^A-Za-z0-9]", "") + "-2.json"); }

    private static byte[] readAll(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { return Http.readAll(in); }
    }

    /** Where a company's page on Wikidata lives, for the "data from" line. */
    static Uri source(Library.Company c) { return Uri.parse("https://www.wikidata.org/wiki/" + c.id); }
}
