package com.zgenmedia.tvhome;

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
 * Reads the add-on list from a Stremio account, using Stremio's own TV sign-in:
 * a short code entered at link.stremio.com by someone already signed in there.
 * Phone TV never handles the Stremio password, reads only the add-on list, and
 * signs the temporary session out straight afterwards. All calls block.
 */
final class StremioLink {
    private static final String LINK = "https://link.stremio.com/api/v2", API = "https://api.strem.io/api";
    /** Stremio's own built-ins: every Stremio install has them and Phone TV already covers them. */
    private static final String[] SKIP = {"https://v3-cinemeta.strem.io", "https://opensubtitles.strem.io/stremio/v1", "http://127.0.0.1"};

    static final class Code {
        String code, link;
    }

    private StremioLink() {}

    static Code create() throws IOException, JSONException {
        JSONObject o = new JSONObject(get(LINK + "/create?type=Create"));
        JSONObject r = o.optJSONObject("result");
        if (r == null || !r.optBoolean("success", true)) throw new IOException("Stremio didn't give a code");
        Code c = new Code();
        c.code = r.getString("code");
        c.link = r.optString("link", "https://link.stremio.com/" + c.code);
        return c;
    }

    /** The session key once the code has been approved, or null while it's still waiting. */
    static String read(String code) throws IOException, JSONException {
        JSONObject o = new JSONObject(get(LINK + "/read?type=Read&code=" + code));
        JSONObject r = o.optJSONObject("result");
        if (r == null) return null; // "invalid or expired" doubles as "not approved yet"
        String key = r.optString("authKey", "");
        return key.isEmpty() ? null : key;
    }

    /** Every add-on's manifest link on the account, in the account's order, minus Stremio's built-ins. */
    static List<String> addonLinks(String authKey) throws IOException, JSONException {
        JSONObject o = new JSONObject(post("/addonCollectionGet", new JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true)));
        JSONObject r = o.optJSONObject("result");
        if (r == null) throw new IOException(o.optJSONObject("error") != null ? o.getJSONObject("error").optString("message") : "no add-ons");
        JSONArray addons = r.optJSONArray("addons");
        List<String> out = new ArrayList<>();
        if (addons == null) return out;
        outer:
        for (int i = 0; i < addons.length(); i++) {
            String url = addons.getJSONObject(i).optString("transportUrl");
            if (url.isEmpty()) continue;
            for (String skip : SKIP) if (url.startsWith(skip)) continue outer;
            out.add(url);
        }
        return out;
    }

    static void logout(String authKey) {
        try { post("/logout", new JSONObject().put("type", "Logout").put("authKey", authKey)); }
        catch (IOException | JSONException ignored) { /* the session lapses on its own */ }
    }

    private static String get(String url) throws IOException {
        return new String(Http.get(url, new HashMap<String, String>(), 15_000), StandardCharsets.UTF_8);
    }

    private static String post(String path, JSONObject body) throws IOException {
        Map<String, String> h = new HashMap<>();
        return new String(Http.post(API + path, h, body.toString()), StandardCharsets.UTF_8);
    }
}
