package com.zgenmedia.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Typed wrapper around the app's SharedPreferences.
 *
 * Key names are part of the on-device data format; renaming one silently resets
 * that setting for existing users, so treat them as frozen.
 */
final class Prefs {
    static final String PLAY_ASK = "ask", PLAY_STREMIO = "stremio", PLAY_PLEX = "plex";
    static final float[] SIZE_SCALES = {0.70f, 0.80f, 0.94f};

    private static final String FILE = "home";
    private static final String K_SIZE = "size", K_HIDE_BARS = "hideBars", K_TV_TILES = "tvBanners",
            K_PLAY_WITH = "playWith", K_FAVORITES = "favs", K_PLEX_PROMPT = "plexPromptDismissed",
            K_PLEX_CLIENT = "plexClientId", K_PLEX_ACCOUNT = "plexAccountToken",
            K_PLEX_URI = "plexServerUri", K_PLEX_SERVER_TOKEN = "plexServerToken",
            K_PLEX_SERVER_ID = "plexServerId", K_PLEX_SERVER_NAME = "plexServerName";

    private final SharedPreferences sp;

    Prefs(Context context) { sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    // --- display ---
    int sizeIndex() { return Math.max(0, Math.min(SIZE_SCALES.length - 1, sp.getInt(K_SIZE, 1))); }
    void setSizeIndex(int i) { sp.edit().putInt(K_SIZE, i).apply(); }
    float scale() { return SIZE_SCALES[sizeIndex()]; }

    boolean hideSystemBars() { return sp.getBoolean(K_HIDE_BARS, true); }
    void setHideSystemBars(boolean v) { sp.edit().putBoolean(K_HIDE_BARS, v).apply(); }

    boolean tvStyleTiles() { return sp.getBoolean(K_TV_TILES, true); }
    void setTvStyleTiles(boolean v) { sp.edit().putBoolean(K_TV_TILES, v).apply(); }

    String playWith() { return sp.getString(K_PLAY_WITH, PLAY_ASK); }
    void setPlayWith(String v) { sp.edit().putString(K_PLAY_WITH, v).apply(); }

    // --- favorites (ordered package names) ---
    boolean hasFavorites() { return sp.contains(K_FAVORITES); }
    List<String> favorites() {
        String raw = sp.getString(K_FAVORITES, "");
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }
    void setFavorites(List<String> packages) { sp.edit().putString(K_FAVORITES, TextUtils.join(",", packages)).apply(); }

    // --- Plex ---
    /** Stable per-install id; Plex uses it to list this device under "Authorized Devices". */
    String plexClientId() {
        String id = sp.getString(K_PLEX_CLIENT, null);
        if (id == null) { id = "phonetv-" + UUID.randomUUID(); sp.edit().putString(K_PLEX_CLIENT, id).apply(); }
        return id;
    }
    String plexAccountToken() { return sp.getString(K_PLEX_ACCOUNT, null); }
    String plexServerUri() { return sp.getString(K_PLEX_URI, null); }
    String plexServerToken() { return sp.getString(K_PLEX_SERVER_TOKEN, null); }
    String plexServerId() { return sp.getString(K_PLEX_SERVER_ID, null); }
    String plexServerName() { return sp.getString(K_PLEX_SERVER_NAME, ""); }

    void savePlex(String accountToken, String uri, String serverToken, String serverId, String serverName) {
        sp.edit().putString(K_PLEX_ACCOUNT, accountToken).putString(K_PLEX_URI, uri)
                .putString(K_PLEX_SERVER_TOKEN, serverToken).putString(K_PLEX_SERVER_ID, serverId)
                .putString(K_PLEX_SERVER_NAME, serverName).apply();
    }
    void clearPlex() {
        sp.edit().remove(K_PLEX_ACCOUNT).remove(K_PLEX_URI).remove(K_PLEX_SERVER_TOKEN)
                .remove(K_PLEX_SERVER_ID).remove(K_PLEX_SERVER_NAME).apply();
    }
    boolean plexPromptDismissed() { return sp.getBoolean(K_PLEX_PROMPT, false); }
    void dismissPlexPrompt() { sp.edit().putBoolean(K_PLEX_PROMPT, true).apply(); }

    static List<String> listOf(String... items) { return new ArrayList<>(Arrays.asList(items)); }
}
