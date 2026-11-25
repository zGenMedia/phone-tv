package com.zgenmedia.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * People who share the TV. Each profile has its own Library (saved titles, actors,
 * history) and its own Continue watching; settings, sign-ins and add-ons are shared
 * by the household, as on a streaming box.
 *
 * The first profile is the one every install already has: its id is empty, so its
 * files keep their original names and nothing moves when profiles are first used.
 */
final class Profiles {
    static final int[] COLORS = {0xFFE5A50A, 0xFF3D8BFD, 0xFFE0457B, 0xFF2BB673, 0xFF9B5DE5, 0xFFF26B38, 0xFF13B5C8};
    private static final String PREFS = "profiles", K_LIST = "list", K_CURRENT = "current", K_ASK = "askOnStart";

    static final class Profile {
        String id = "", name = "";
        int color = COLORS[0];
    }

    private Profiles() {}

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String currentId(Context c) { return sp(c).getString(K_CURRENT, ""); }

    /** "library.json" for the first profile, "library-ab12cd.json" for the others. */
    static String fileName(String base, String profileId) {
        return profileId == null || profileId.isEmpty() ? base + ".json" : base + "-" + profileId + ".json";
    }

    static List<Profile> all(Context c) {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString(K_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Profile p = new Profile();
                p.id = o.optString("id");
                p.name = o.optString("name");
                p.color = o.optInt("color", COLORS[i % COLORS.length]);
                out.add(p);
            }
        } catch (JSONException ignored) { /* start over with the default below */ }
        if (out.isEmpty() || !out.get(0).id.isEmpty()) {
            Profile first = new Profile();
            first.name = c.getString(R.string.profile_default_name);
            out.add(0, first);
        }
        return out;
    }

    static Profile current(Context c) {
        String id = currentId(c);
        for (Profile p : all(c)) if (p.id.equals(id)) return p;
        return all(c).get(0);
    }

    static boolean several(Context c) { return all(c).size() > 1; }

    static Profile add(Context c, String name) {
        List<Profile> list = all(c);
        Profile p = new Profile();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.name = name.trim().isEmpty() ? c.getString(R.string.profile_new_name, list.size() + 1) : name.trim();
        p.color = COLORS[list.size() % COLORS.length];
        list.add(p);
        save(c, list);
        return p;
    }

    static void rename(Context c, String id, String name) {
        List<Profile> list = all(c);
        for (Profile p : list) if (p.id.equals(id) && !name.trim().isEmpty()) p.name = name.trim();
        save(c, list);
    }

    /** Removes a profile and its files. The first profile can't be removed. */
    static void remove(Context c, String id) {
        if (id.isEmpty()) return;
        List<Profile> list = all(c);
        list.removeIf(p -> p.id.equals(id));
        save(c, list);
        for (String base : new String[]{"library", "progress"}) {
            //noinspection ResultOfMethodCallIgnored
            new java.io.File(c.getFilesDir(), fileName(base, id)).delete();
        }
        if (currentId(c).equals(id)) switchTo(c, "");
    }

    static void switchTo(Context c, String id) { sp(c).edit().putString(K_CURRENT, id).apply(); }

    static boolean askOnStart(Context c) { return sp(c).getBoolean(K_ASK, true); }
    static void setAskOnStart(Context c, boolean ask) { sp(c).edit().putBoolean(K_ASK, ask).apply(); }

    private static void save(Context c, List<Profile> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Profile p : list) arr.put(new JSONObject().put("id", p.id).put("name", p.name).put("color", p.color));
        } catch (JSONException ignored) { /* plain values only */ }
        sp(c).edit().putString(K_LIST, arr.toString()).apply();
    }
}
