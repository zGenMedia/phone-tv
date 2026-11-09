package com.zgenmedia.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Installed apps and the user's favorites.
 *
 * Phones only list CATEGORY_LAUNCHER apps, but sideloaded Android TV editions
 * (e.g. TV builds of IPTV players) only declare CATEGORY_LEANBACK_LAUNCHER. We
 * query both so those apps show up and can be launched.
 */
final class AppRepository {

    static final class App {
        final ResolveInfo info;
        final String pkg, label;
        final boolean tvEdition;
        App(ResolveInfo info, String label, boolean tvEdition) {
            this.info = info; this.pkg = info.activityInfo.packageName; this.label = label; this.tvEdition = tvEdition;
        }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final List<App> apps = new ArrayList<>();
    private String signature = "";

    AppRepository(Context ctx, Prefs prefs) { this.ctx = ctx; this.prefs = prefs; }

    /** Re-reads installed apps. @return true if the set changed since the last call. */
    boolean reload() {
        Map<String, App> byPackage = new LinkedHashMap<>();
        collect(byPackage, Intent.CATEGORY_LAUNCHER, false);
        collect(byPackage, Intent.CATEGORY_LEANBACK_LAUNCHER, true); // phone entry wins if both exist
        apps.clear();
        apps.addAll(byPackage.values());
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));

        StringBuilder sig = new StringBuilder();
        for (App a : apps) sig.append(a.pkg).append(a.tvEdition ? '+' : ';');
        boolean changed = !sig.toString().equals(signature);
        signature = sig.toString();
        return changed;
    }

    private void collect(Map<String, App> into, String category, boolean tvEdition) {
        PackageManager pm = ctx.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo r : pm.queryIntentActivities(query, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || into.containsKey(pkg)) continue;
            into.put(pkg, new App(r, r.loadLabel(pm).toString(), tvEdition));
        }
    }

    List<App> all() { return apps; }

    App byPackage(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /** Finds an app by exact package, then by a case/space-insensitive label match. */
    App find(String pkg, String... labelHints) {
        if (pkg != null) { App a = byPackage(pkg); if (a != null) return a; }
        for (App a : apps) {
            String l = a.label.toLowerCase(Locale.ROOT).replace(" ", "");
            for (String hint : labelHints) if (l.contains(hint)) return a;
        }
        return null;
    }

    /**
     * Launches through the package's own launch intent. Since Android 14, explicit
     * intents must match the target's intent filter, so a TV-only app has to be
     * started with its LEANBACK intent rather than a generic LAUNCHER one.
     */
    boolean launch(App a) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = a.tvEdition ? pm.getLeanbackLaunchIntentForPackage(a.pkg) : pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) i = a.tvEdition ? pm.getLaunchIntentForPackage(a.pkg) : pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(a.tvEdition ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                    .setClassName(a.pkg, a.info.activityInfo.name);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { ctx.startActivity(i); return true; }
        catch (RuntimeException e) { return false; }
    }

    // ------------------------------------------------------------- favorites

    /** First run only: seed favorites with the streaming apps the user already has. */
    void seedFavorites(String... preferredPackagesOrLabels) {
        if (prefs.hasFavorites()) return;
        List<String> seed = new ArrayList<>();
        for (String hint : preferredPackagesOrLabels) {
            App a = find(hint, hint.toLowerCase(Locale.ROOT));
            if (a != null && !seed.contains(a.pkg)) seed.add(a.pkg);
        }
        prefs.setFavorites(seed);
    }

    /** Favorites in user order; uninstalled entries are skipped but kept in prefs. */
    List<App> favorites() {
        List<App> out = new ArrayList<>();
        for (String pkg : prefs.favorites()) { App a = byPackage(pkg); if (a != null) out.add(a); }
        return out;
    }

    boolean isFavorite(String pkg) { return prefs.favorites().contains(pkg); }

    /** @return true if the app is now a favorite. */
    boolean toggleFavorite(String pkg) {
        List<String> favs = prefs.favorites();
        boolean added = !favs.remove(pkg);
        if (added) favs.add(pkg);
        prefs.setFavorites(favs);
        return added;
    }

    void moveFavorite(String pkg, int delta) {
        List<String> favs = prefs.favorites();
        int from = favs.indexOf(pkg), to = from + delta;
        if (from < 0 || to < 0 || to >= favs.size()) return;
        Collections.swap(favs, from, to);
        prefs.setFavorites(favs);
    }

    int favoriteIndex(String pkg) { return prefs.favorites().indexOf(pkg); }
    int favoriteCount() { return prefs.favorites().size(); }
}
