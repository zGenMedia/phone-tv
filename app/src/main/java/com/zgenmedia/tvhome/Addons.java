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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stremio add-ons: the open protocol Stremio and Nuvio use to find streams,
 * subtitles and extra catalogs. An add-on is a web address serving a
 * manifest.json; everything else is a JSON request under the same address.
 *
 * Phone TV installs none by itself. The user adds the ones they choose by
 * link, and is responsible for what those add-ons serve.
 *
 * Network calls block; the list itself is kept in memory and on disk.
 */
final class Addons {
    /** One installed add-on, as its manifest describes it. */
    static final class Addon {
        String base;            // the manifest's address without "/manifest.json"
        String id, name, version, description, logo;
        boolean enabled = true;
        final List<String> resources = new ArrayList<>();   // "stream", "subtitles", "catalog", "meta"
        final List<String> types = new ArrayList<>();       // "movie", "series", ...
        final List<String> idPrefixes = new ArrayList<>();  // "tt", "kitsu:" ... empty: any
        final List<Catalog> catalogs = new ArrayList<>();

        boolean serves(String resource, String type, String id) {
            if (!enabled || !resources.contains(resource)) return false;
            if (!types.isEmpty() && type != null && !types.contains(type)) return false;
            if (idPrefixes.isEmpty() || id == null) return true;
            for (String p : idPrefixes) if (id.startsWith(p)) return true;
            return false;
        }
    }

    /** A catalog an add-on offers, shown as a row when it needs no search words. */
    static final class Catalog {
        String type, id, name;
        boolean needsInput; // requires "search" or another extra the home screen can't supply
    }

    /** One way to watch something, as an add-on offers it. */
    static final class Stream {
        String addon;           // add-on name, for grouping
        String name, title;     // add-on's own labels: quality on the first line, details below
        String url;             // direct http(s) link: plays in Phone TV
        String infoHash;        // torrent: Phone TV can't play these, Stremio can
        int fileIdx = -1;
        String externalUrl;     // a web page the add-on wants opened
        String ytId;            // a YouTube video
        String bingeGroup;      // same group for the next episode
        String filename;
        long size;
        final java.util.Map<String, String> headers = new java.util.HashMap<>();

        boolean playable() { return url != null && (url.startsWith("http://") || url.startsWith("https://")); }
    }

    /** A subtitle file an add-on offers. */
    static final class Subtitle {
        String id, url, lang, addon;
    }

    private final File file;
    private final List<Addon> installed = new CopyOnWriteArrayList<>();

    private static Addons shared;

    static synchronized Addons of(Context c) {
        if (shared == null) shared = new Addons(c.getApplicationContext());
        return shared;
    }

    private Addons(Context c) {
        file = new File(c.getFilesDir(), "addons.json");
        load();
    }

    List<Addon> all() { return Collections.unmodifiableList(installed); }

    boolean hasStreams(String type, String id) {
        for (Addon a : installed) if (a.serves("stream", type, id)) return true;
        return false;
    }

    // ================================================================ managing

    /** The manifest address as typed: "stremio://" and a missing "/manifest.json" are fine. */
    static String manifestUrl(String typed) {
        String u = typed.trim();
        if (u.startsWith("stremio://")) u = "https://" + u.substring("stremio://".length());
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        if (!u.endsWith("/manifest.json")) u = u.replaceAll("/+$", "") + "/manifest.json";
        return u;
    }

    /** Fetches a manifest and installs (or refreshes) the add-on. Blocks. */
    Addon install(String typed) throws IOException, JSONException {
        String url = manifestUrl(typed);
        Addon a = parseManifest(new JSONObject(get(url)), url.substring(0, url.length() - "/manifest.json".length()));
        for (int i = 0; i < installed.size(); i++) {
            if (installed.get(i).id.equals(a.id) || installed.get(i).base.equals(a.base)) {
                a.enabled = installed.get(i).enabled;
                installed.set(i, a);
                save();
                return a;
            }
        }
        installed.add(a);
        save();
        return a;
    }

    void remove(Addon a) { installed.remove(a); save(); }

    void setEnabled(Addon a, boolean on) { a.enabled = on; save(); }

    void move(Addon a, int by) {
        int from = installed.indexOf(a), to = Math.max(0, Math.min(installed.size() - 1, from + by));
        if (from < 0 || from == to) return;
        installed.remove(from);
        installed.add(to, a);
        save();
    }

    static Addon parseManifest(JSONObject m, String base) throws JSONException {
        Addon a = new Addon();
        a.base = base;
        a.id = m.optString("id", base);
        a.name = m.optString("name", a.id);
        a.version = m.optString("version");
        a.description = m.optString("description");
        a.logo = m.optString("logo");
        strings(m.optJSONArray("types"), a.types);
        strings(m.optJSONArray("idPrefixes"), a.idPrefixes);
        JSONArray res = m.optJSONArray("resources");
        if (res != null) {
            for (int i = 0; i < res.length(); i++) {
                Object r = res.get(i);
                if (r instanceof String) a.resources.add((String) r);
                else if (r instanceof JSONObject) {
                    JSONObject o = (JSONObject) r;
                    a.resources.add(o.optString("name"));
                    // A resource can narrow types and prefixes for itself; the widest set is kept.
                    for (String t : strings(o.optJSONArray("types"), new ArrayList<>())) if (!a.types.contains(t)) a.types.add(t);
                    for (String p : strings(o.optJSONArray("idPrefixes"), new ArrayList<>())) if (!a.idPrefixes.contains(p)) a.idPrefixes.add(p);
                }
            }
        }
        JSONArray cats = m.optJSONArray("catalogs");
        if (cats != null) {
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.getJSONObject(i);
                Catalog cat = new Catalog();
                cat.type = c.optString("type");
                cat.id = c.optString("id");
                cat.name = c.optString("name", cat.id);
                JSONArray extra = c.optJSONArray("extra");
                if (extra != null) {
                    for (int j = 0; j < extra.length(); j++) {
                        JSONObject e = extra.optJSONObject(j);
                        if (e != null && e.optBoolean("isRequired")) cat.needsInput = true;
                    }
                }
                JSONArray required = c.optJSONArray("extraRequired");
                if (required != null && required.length() > 0) cat.needsInput = true;
                if (!cat.type.isEmpty() && !cat.id.isEmpty()) a.catalogs.add(cat);
            }
        }
        return a;
    }

    // ================================================================= asking

    /**
     * Streams for a movie ("tt…") or an episode ("tt…:season:episode") from every add-on that
     * serves them, in the add-ons' order. A slow or broken add-on is skipped, not fatal.
     */
    List<Stream> streams(String type, String id) {
        List<Stream> out = Collections.synchronizedList(new ArrayList<>());
        List<Thread> askers = new ArrayList<>();
        final List<List<Stream>> perAddon = new ArrayList<>();
        for (Addon a : installed) {
            if (!a.serves("stream", type, id)) continue;
            final List<Stream> mine = new ArrayList<>();
            perAddon.add(mine);
            Thread t = new Thread(() -> {
                try {
                    JSONArray arr = new JSONObject(get(a.base + "/stream/" + type + "/" + Uri.encode(id) + ".json")).optJSONArray("streams");
                    if (arr != null) for (int i = 0; i < arr.length(); i++) mine.add(stream(arr.getJSONObject(i), a.name));
                } catch (IOException | JSONException ignored) { /* this add-on had nothing or failed */ }
            }, "phonetv-streams");
            askers.add(t);
            t.start();
        }
        long until = System.currentTimeMillis() + 25_000;
        for (Thread t : askers) {
            try { t.join(Math.max(1, until - System.currentTimeMillis())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        for (List<Stream> mine : perAddon) synchronized (mine) { out.addAll(mine); }
        return new ArrayList<>(out);
    }

    static Stream stream(JSONObject o, String addon) {
        Stream s = new Stream();
        s.addon = addon;
        s.name = o.optString("name", addon).trim();
        s.title = o.optString("title", o.optString("description", "")).trim();
        s.url = o.has("url") ? o.optString("url") : null;
        s.infoHash = o.has("infoHash") ? o.optString("infoHash") : null;
        s.fileIdx = o.optInt("fileIdx", -1);
        s.externalUrl = o.has("externalUrl") ? o.optString("externalUrl") : null;
        s.ytId = o.has("ytId") ? o.optString("ytId") : null;
        JSONObject hints = o.optJSONObject("behaviorHints");
        if (hints != null) {
            s.bingeGroup = hints.optString("bingeGroup", null);
            s.filename = hints.optString("filename", null);
            s.size = hints.optLong("videoSize", 0);
            JSONObject proxy = hints.optJSONObject("proxyHeaders");
            JSONObject request = proxy == null ? null : proxy.optJSONObject("request");
            if (request != null) for (java.util.Iterator<String> k = request.keys(); k.hasNext(); ) {
                String key = k.next();
                s.headers.put(key, request.optString(key));
            }
        }
        return s;
    }

    /** Subtitles for a title or episode from every subtitle add-on. Blocks; failures are skipped. */
    List<Subtitle> subtitles(String type, String id) {
        List<Subtitle> out = new ArrayList<>();
        for (Addon a : installed) {
            if (!a.serves("subtitles", type, id)) continue;
            try {
                JSONArray arr = new JSONObject(get(a.base + "/subtitles/" + type + "/" + Uri.encode(id) + ".json")).optJSONArray("subtitles");
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Subtitle sub = new Subtitle();
                    sub.id = o.optString("id", String.valueOf(i));
                    sub.url = o.optString("url");
                    sub.lang = o.optString("lang", "und");
                    sub.addon = a.name;
                    if (sub.url.startsWith("http")) out.add(sub);
                }
            } catch (IOException | JSONException ignored) { /* next add-on */ }
        }
        return out;
    }

    /** Titles from one add-on catalog. Only IMDb-numbered titles: those are the ones the rest of Phone TV can describe. */
    List<MediaItem> catalog(Addon a, Catalog c) throws IOException, JSONException {
        List<MediaItem> items = CatalogRepository.parse(get(a.base + "/catalog/" + c.type + "/" + Uri.encode(c.id) + ".json"));
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem m : items) if (m.id.startsWith("tt")) out.add(m);
        return out;
    }

    // ================================================================= storage

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            JSONArray arr = new JSONArray(new String(Http.readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Addon a = parseManifest(o.getJSONObject("manifest"), o.getString("base"));
                a.enabled = o.optBoolean("enabled", true);
                installed.add(a);
            }
        } catch (IOException | JSONException e) {
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(new File(file.getPath() + ".damaged"));
        }
    }

    /** The add-ons are re-saved from what their manifests said, so a reload rebuilds them the same way. */
    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Addon a : installed) arr.put(new JSONObject().put("base", a.base).put("enabled", a.enabled).put("manifest", manifestJson(a)));
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(arr.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file)) throw new IOException("rename");
        } catch (IOException | JSONException ignored) { /* kept in memory for this session */ }
    }

    private static JSONObject manifestJson(Addon a) throws JSONException {
        JSONArray cats = new JSONArray();
        for (Catalog c : a.catalogs) {
            JSONObject o = new JSONObject().put("type", c.type).put("id", c.id).put("name", c.name);
            if (c.needsInput) o.put("extraRequired", new JSONArray().put("search"));
            cats.put(o);
        }
        return new JSONObject().put("id", a.id).put("name", a.name).put("version", a.version)
                .put("description", a.description).put("logo", a.logo)
                .put("resources", new JSONArray(a.resources)).put("types", new JSONArray(a.types))
                .put("idPrefixes", new JSONArray(a.idPrefixes)).put("catalogs", cats);
    }

    /** Settings backups carry the add-on list; sign-ins inside add-on links are the user's choice to share. */
    JSONArray exportList() {
        JSONArray arr = new JSONArray();
        for (Addon a : installed) arr.put(a.base + "/manifest.json");
        return arr;
    }

    private static List<String> strings(JSONArray arr, List<String> into) {
        if (arr != null) for (int i = 0; i < arr.length(); i++) into.add(arr.optString(i));
        return into;
    }

    private static String get(String url) throws IOException {
        return new String(Http.get(url, new java.util.HashMap<String, String>(), 20_000), StandardCharsets.UTF_8);
    }

    static String sizeLabel(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / 1_073_741_824.0;
        return gb >= 1 ? String.format(Locale.US, "%.1f GB", gb) : String.format(Locale.US, "%d MB", bytes / 1_048_576);
    }
}
