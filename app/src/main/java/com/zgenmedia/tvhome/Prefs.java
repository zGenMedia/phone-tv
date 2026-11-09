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
            K_PLEX_SERVER_ID = "plexServerId", K_PLEX_SERVER_NAME = "plexServerName",
            K_PLEX_PIN_ID = "plexPinId", K_PLEX_PIN_EXPIRES = "plexPinExpires",
            K_TRAKT_CLIENT = "traktClientId", K_TRAKT_LISTS = "traktLists", K_HERO_MOOD = "heroMood",
            K_HIDDEN = "hiddenApps", K_KEEPER_PAUSE = "keeperPausedUntil";

    private final SharedPreferences sp;

    Prefs(Context context) { sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    // --- display ---
    private int defaultSize = 1;
    /** TVs are read from across the room, so they start one size up unless the user picked a size. */
    void setDefaultSizeIndex(int i) { defaultSize = i; }
    int sizeIndex() { return Math.max(0, Math.min(SIZE_SCALES.length - 1, sp.getInt(K_SIZE, defaultSize))); }

    /** Landscape banner: details on the left with artwork on the right (default), or the reverse. */
    boolean heroDetailsRight() { return sp.getBoolean("heroDetailsRight", false); }
    void setHeroDetailsRight(boolean v) { sp.edit().putBoolean("heroDetailsRight", v).apply(); }
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

    // --- hidden apps (kept out of the Apps tab; a de-bloat tool) ---
    List<String> hiddenApps() {
        List<String> out = new ArrayList<>();
        for (String p : sp.getString(K_HIDDEN, "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }
    void setHiddenApps(List<String> packages) { sp.edit().putString(K_HIDDEN, TextUtils.join(",", packages)).apply(); }

    // --- HomeKeeperService: lets the user reach the stock TV launcher for a minute ---
    long keeperPausedUntil() { return sp.getLong(K_KEEPER_PAUSE, 0); }
    void pauseKeeper(long untilMs) { sp.edit().putLong(K_KEEPER_PAUSE, untilMs).apply(); }

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

    /** Saved as soon as the PIN is approved, so the link survives even if no server answers yet. */
    void savePlexAccount(String accountToken) {
        sp.edit().putString(K_PLEX_ACCOUNT, accountToken).putString("plexOwnerToken", accountToken).remove("plexProfileName").apply();
    }

    /** Token of whoever linked the device; used to list and switch Plex Home profiles. */
    String plexOwnerToken() { return sp.getString("plexOwnerToken", plexAccountToken()); }
    String plexProfileName() { return sp.getString("plexProfileName", ""); }

    /** Switches the active Plex Home profile. The server is looked up again with the new token. */
    void setPlexProfile(String profileToken, String name) {
        sp.edit().putString(K_PLEX_ACCOUNT, profileToken).putString("plexProfileName", name)
                .remove(K_PLEX_URI).remove(K_PLEX_SERVER_TOKEN).apply();
    }

    // A PIN the user may still be entering at plex.tv/link. Persisted so linking
    // completes even if Android kills the activity while they're in the browser.
    long plexPendingPin() { return sp.getLong(K_PLEX_PIN_EXPIRES, 0) > System.currentTimeMillis() ? sp.getLong(K_PLEX_PIN_ID, 0) : 0; }
    String plexPendingCode() { return plexPendingPin() != 0 ? sp.getString("plexPinCode", "") : ""; }
    void setPlexPendingPin(long id, String code, long expiresAt) {
        sp.edit().putLong(K_PLEX_PIN_ID, id).putString("plexPinCode", code).putLong(K_PLEX_PIN_EXPIRES, expiresAt).apply();
    }
    void clearPlexPendingPin() { sp.edit().remove(K_PLEX_PIN_ID).remove("plexPinCode").remove(K_PLEX_PIN_EXPIRES).apply(); }

    void savePlex(String accountToken, String uri, String serverToken, String serverId, String serverName) {
        sp.edit().putString(K_PLEX_ACCOUNT, accountToken).putString(K_PLEX_URI, uri)
                .putString(K_PLEX_SERVER_TOKEN, serverToken).putString(K_PLEX_SERVER_ID, serverId)
                .putString(K_PLEX_SERVER_NAME, serverName).apply();
    }
    void clearPlex() {
        sp.edit().remove(K_PLEX_ACCOUNT).remove(K_PLEX_URI).remove(K_PLEX_SERVER_TOKEN)
                .remove(K_PLEX_SERVER_ID).remove(K_PLEX_SERVER_NAME).remove("plexOwnerToken").remove("plexProfileName").apply();
    }
    // --- Hero mood: a few typed words, e.g. "asian romance"; empty = trending ---
    String heroMood() { return sp.getString(K_HERO_MOOD, "").trim(); }
    void setHeroMood(String words) { sp.edit().putString(K_HERO_MOOD, words.trim()).apply(); }

    // --- Trakt (advanced, opt-in) ---
    String traktClientId() { return sp.getString(K_TRAKT_CLIENT, "").trim(); }
    String traktLists() { return sp.getString(K_TRAKT_LISTS, ""); }
    void saveTrakt(String clientId, String lists) {
        sp.edit().putString(K_TRAKT_CLIENT, clientId.trim()).putString(K_TRAKT_LISTS, lists.trim()).apply();
    }

    boolean plexPromptDismissed() { return sp.getBoolean(K_PLEX_PROMPT, false); }
    void dismissPlexPrompt() { sp.edit().putBoolean(K_PLEX_PROMPT, true).apply(); }

    static List<String> listOf(String... items) { return new ArrayList<>(Arrays.asList(items)); }
}
