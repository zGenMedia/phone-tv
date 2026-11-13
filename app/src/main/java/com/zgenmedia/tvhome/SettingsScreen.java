package com.zgenmedia.tvhome;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Settings, as a full page instead of a dialog.
 *
 * Sideways (TV, PC, phone in landscape) it is two panes: sections down the
 * left, the chosen section's options on the right. Upright it stacks: section
 * chips across the top, options underneath. Every option applies at once and
 * the page stays open, so the user can see what a change does before leaving.
 *
 * Options that need typing or an outside step (Plex linking, Trakt lists, the
 * banner's mood words) keep their own dialogs in {@link HomeActivity}; this
 * page refreshes itself when one of them closes.
 */
final class SettingsScreen implements Overlay {
    private static final int LOOK = 0, HOME = 1, PLAYBACK = 2, SOURCES = 3, SYSTEM = 4;
    private static final int[] SECTION_NAMES = {R.string.set_look, R.string.set_home, R.string.set_playback, R.string.set_sources, R.string.set_system};
    private static final int[] SECTION_HINTS = {R.string.set_look_hint, R.string.set_home_hint, R.string.set_playback_hint, R.string.set_sources_hint, R.string.set_system_hint};

    /** How much has to be redrawn after a change. */
    private static final int PANE = 0, HOME_SCREEN = 1, EVERYTHING = 2;

    private static final String SITE_URL = "https://zgenmedia.com";
    private static final String SOURCE_URL = "https://github.com/zGenMedia/phone-tv";

    private final HomeActivity a;
    private final Ui ui;
    private final Prefs prefs;
    private final FrameLayout root;
    private final Map<String, View> controls = new HashMap<>();
    private final List<View> tabs = new ArrayList<>();

    private int section;
    private String lastUsed;     // the control to put the remote back on after a redraw
    private boolean redrawing;   // focus hops around while views are swapped; ignore it
    private View rail;           // sideways only: the section list
    private ScrollView paneScroll;
    private LinearLayout pane;

    SettingsScreen(HomeActivity activity) {
        a = activity;
        ui = activity.ui;
        prefs = activity.prefs;
        root = new FrameLayout(activity);
        root.setClickable(true);
        // When a dialog opened from here closes, the values it changed are shown straight away.
        ViewTreeObserver.OnWindowFocusChangeListener onReturn = has -> { if (has && root.isAttachedToWindow()) fillPane(); };
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { v.getViewTreeObserver().addOnWindowFocusChangeListener(onReturn); }
            @Override public void onViewDetachedFromWindow(View v) { v.getViewTreeObserver().removeOnWindowFocusChangeListener(onReturn); }
        });
        build();
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return tabs.isEmpty() ? null : tabs.get(section); }
    @Override public boolean onBack() { return false; }
    @Override public void rebuild() { build(); }

    @Override
    public boolean move(View focused, int direction) {
        View next;
        if (rail == null) {
            next = HomeActivity.nextFocus(root, focused, direction);
        } else if (HomeActivity.isInside(focused, rail)) {
            // Right crosses into the options; Up and Down walk the section list.
            if (direction == View.FOCUS_RIGHT) next = paneEntry();
            else next = direction == View.FOCUS_LEFT ? null : FocusFinder.getInstance().findNextFocus((LinearLayout) rail, focused, direction);
        } else {
            next = HomeActivity.nextFocus(pane, focused, direction);
            if ((next == null || next == focused) && direction == View.FOCUS_LEFT) next = tabs.get(section);
        }
        if (next != null && next != focused) next.requestFocus();
        return true;
    }

    /** Where the remote lands when it crosses from the sections into the options. */
    private View paneEntry() {
        View last = lastUsed == null ? null : controls.get(lastUsed);
        if (last != null) return last;
        List<View> all = pane.getFocusables(View.FOCUS_DOWN);
        if (all.isEmpty()) return null;
        View first = all.get(0);
        // A strip of chips is entered at the chosen chip, not at its first one.
        Object chosen = first.getParent() instanceof View ? ((View) first.getParent()).getTag(R.id.selected_chip) : null;
        return chosen instanceof View ? (View) chosen : first;
    }

    // ======================================================================= frame

    private void build() {
        redrawing = true;
        root.removeAllViews();
        root.setBackgroundColor(Ui.BG);
        tabs.clear();
        boolean land = a.land();
        LinearLayout frame = new LinearLayout(a);
        frame.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));

        if (land) {
            rail = buildRail();
            frame.addView(rail, new LinearLayout.LayoutParams(Math.max(ui.dp(220), (int) (a.metrics().widthPixels * 0.27f)), -1));
            View edge = new View(a);
            edge.setBackgroundColor(0x14FFFFFF);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(Math.max(1, ui.dp(1)), -1);
            elp.setMargins(0, a.insetTop + ui.dp(24), 0, a.insetBottom + ui.dp(24));
            frame.addView(edge, elp);
        } else {
            rail = null;
            frame.addView(buildTopStrip(), new LinearLayout.LayoutParams(-1, -2));
        }

        paneScroll = new ScrollView(a);
        paneScroll.setVerticalScrollBarEnabled(false);
        paneScroll.setFocusable(false);
        pane = new LinearLayout(a);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(land ? ui.dp(28) : a.gutter(), land ? a.insetTop + ui.dp(26) : ui.dp(8),
                a.insetRight + ui.dp(land ? 36 : 20), a.insetBottom + ui.dp(28));
        paneScroll.addView(pane, new FrameLayout.LayoutParams(-1, -2));
        frame.addView(paneScroll, new LinearLayout.LayoutParams(land ? 0 : -1, land ? -1 : 0, 1));

        styleTabs();
        fillPane();
    }

    /** Sideways: title, the sections, and Done at the bottom. */
    private LinearLayout buildRail() {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(a.gutter(), a.insetTop + ui.dp(26), ui.dp(16), a.insetBottom + ui.dp(20));
        r.addView(ui.label(a.getString(R.string.set_title), 24, Ui.TEXT, Ui.BOLD));
        TextView version = ui.label(a.getString(R.string.set_version, BuildConfig.VERSION_NAME), 12, Ui.MUTED, null);
        version.setPadding(0, ui.dp(2), 0, ui.dp(18));
        r.addView(version);

        for (int i = 0; i < SECTION_NAMES.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(a);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(ui.dp(10), 0, ui.dp(12), 0);
            View bar = new View(a);
            item.addView(bar, new LinearLayout.LayoutParams(ui.dp(3), ui.dp(18)));
            TextView name = ui.label(a.getString(SECTION_NAMES[i]), 15, Ui.TEXT, Ui.MEDIUM);
            name.setPadding(ui.dp(11), 0, 0, 0);
            item.addView(name);
            sectionTab(item, index, 14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ui.dp(46));
            lp.bottomMargin = ui.dp(4);
            r.addView(item, lp);
        }
        r.addView(new View(a), new LinearLayout.LayoutParams(-1, 0, 1));
        r.addView(doneButton(), new LinearLayout.LayoutParams(-2, -2));
        return r;
    }

    /** Upright: title and Done on one line, section chips on the next. */
    private View buildTopStrip() {
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(a.gutter(), a.insetTop + ui.dp(18), a.insetRight + ui.dp(16), ui.dp(6));
        line.addView(ui.label(a.getString(R.string.set_title), 22, Ui.TEXT, Ui.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(doneButton());
        top.addView(line);

        HorizontalScrollView shelf = new HorizontalScrollView(a);
        shelf.setHorizontalScrollBarEnabled(false);
        shelf.setClipToPadding(false);
        shelf.setPadding(a.gutter(), ui.dp(6), a.insetRight + ui.dp(12), ui.dp(8));
        LinearLayout chips = new LinearLayout(a);
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            TextView chip = ui.label(a.getString(SECTION_NAMES[i]), 13, Ui.TEXT, Ui.MEDIUM);
            chip.setPadding(ui.dp(15), ui.dp(8), ui.dp(15), ui.dp(8));
            sectionTab(chip, i, 18);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ui.dp(6);
            chips.addView(chip, lp);
        }
        shelf.addView(chips);
        top.addView(shelf);
        return top;
    }

    /** Makes a view pick its section when tapped, or as soon as the remote lands on it. */
    private void sectionTab(View tab, int index, float radiusDp) {
        tab.setFocusable(true);
        tab.setClickable(true);
        ui.focusRing(tab, radiusDp);
        View.OnFocusChangeListener base = tab.getOnFocusChangeListener();
        tab.setOnFocusChangeListener((v, has) -> {
            if (base != null) base.onFocusChange(v, has);
            if (has && !redrawing) select(index);
        });
        tab.setOnClickListener(v -> select(index));
        tabs.add(tab);
    }

    private TextView doneButton() {
        TextView done = ui.pill(a.getString(R.string.done), 0x26FFFFFF, Ui.TEXT);
        done.setOnClickListener(v -> a.closeOverlay());
        return done;
    }

    private void select(int index) {
        if (index == section) return;
        section = index;
        lastUsed = null;
        styleTabs();
        paneScroll.scrollTo(0, 0);
        fillPane();
    }

    private void styleTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            View tab = tabs.get(i);
            boolean on = i == section;
            if (tab instanceof LinearLayout) {
                LinearLayout item = (LinearLayout) tab;
                item.setBackground(ui.ripple(ui.round(on ? 0x1AFFFFFF : 0x00000000, 14)));
                item.getChildAt(0).setBackground(ui.round(on ? Ui.ACCENT : 0x00000000, 2));
                TextView name = (TextView) item.getChildAt(1);
                name.setTextColor(on ? Ui.TEXT : Ui.MUTED);
                name.setTypeface(on ? Ui.BOLD : Ui.MEDIUM);
            } else {
                TextView chip = (TextView) tab;
                chip.setBackground(ui.ripple(ui.round(on ? Ui.ACCENT : 0x26FFFFFF, 18)));
                chip.setTextColor(on ? Ui.ON_ACCENT : Ui.TEXT);
                if (on) ((View) chip.getParent()).setTag(R.id.selected_chip, chip);
            }
        }
    }

    // ==================================================================== sections

    private void fillPane() {
        redrawing = true;
        View focused = a.focused();
        boolean hadFocus = focused != null && HomeActivity.isInside(focused, root);
        boolean inTabs = hadFocus && tabs.contains(focused);
        pane.removeAllViews();
        controls.clear();

        if (rail != null) {
            pane.addView(ui.label(a.getString(SECTION_NAMES[section]), 22, Ui.TEXT, Ui.BOLD));
            TextView hint = ui.label(a.getString(SECTION_HINTS[section]), 13, Ui.MUTED, null);
            hint.setPadding(0, ui.dp(3), 0, ui.dp(6));
            pane.addView(hint);
        }
        switch (section) {
            case LOOK: look(); break;
            case HOME: home(); break;
            case PLAYBACK: playback(); break;
            case SOURCES: sources(); break;
            default: system();
        }
        redrawing = false;
        if (!a.usingRemote() || inTabs) return;
        // Views were swapped under the remote: put it back where it was, or on the section.
        View target = lastUsed == null ? null : controls.get(lastUsed);
        if (target == null && !tabs.isEmpty()) target = tabs.get(section);
        if (target != null) target.requestFocus();
    }

    private void look() {
        LinearLayout color = group(R.string.set_group_color);
        accentRow(color);
        toggle(color, "black", R.string.set_black, R.string.set_black_hint, prefs.pureBlack(),
                () -> { prefs.setPureBlack(!prefs.pureBlack()); apply(EVERYTHING); });

        LinearLayout size = group(R.string.set_group_size);
        choice(size, "size", R.string.set_size, R.string.set_size_hint, names(R.string.size_compact, R.string.size_standard, R.string.size_large),
                prefs.sizeIndex(), i -> { prefs.setSizeIndex(i); apply(EVERYTHING); });
        choice(size, "focus", R.string.set_focus, R.string.set_focus_hint, names(R.string.focus_outline, R.string.focus_zoom, R.string.focus_both),
                prefs.focusStyle() % 3, i -> { prefs.setFocusStyle(i); apply(EVERYTHING); });
        choice(size, "zoom", R.string.set_zoom, R.string.set_zoom_hint, names(R.string.zoom_subtle, R.string.zoom_medium, R.string.zoom_strong),
                prefs.focusZoom(), i -> { prefs.setFocusZoom(i); apply(EVERYTHING); });
        choice(size, "corners", R.string.set_corners, 0, names(R.string.corners_sharp, R.string.corners_soft, R.string.corners_classic, R.string.corners_round),
                prefs.cardCorners(), i -> { prefs.setCardCorners(i); apply(EVERYTHING); });

        LinearLayout screen = group(R.string.set_group_screen);
        toggle(screen, "bars", R.string.set_bars, R.string.set_bars_hint, prefs.hideSystemBars(),
                () -> { prefs.setHideSystemBars(!prefs.hideSystemBars()); a.applySystemBars(); apply(PANE); });
    }

    private void home() {
        LinearLayout banner = group(R.string.set_group_banner);
        toggle(banner, "hero", R.string.set_hero, R.string.set_hero_hint, prefs.showHero(),
                () -> { prefs.setShowHero(!prefs.showHero()); apply(HOME_SCREEN); });
        choice(banner, "heroLayout", R.string.set_hero_layout, R.string.set_hero_layout_hint,
                names(R.string.hero_layout_left, R.string.hero_layout_right, R.string.hero_layout_full),
                prefs.heroLayout() % 3, i -> { prefs.setHeroLayout(i); apply(HOME_SCREEN); });
        String mood = prefs.heroMood();
        action(banner, "tune", a.getString(R.string.set_tune), a.getString(R.string.set_tune_hint),
                mood.isEmpty() ? a.getString(R.string.set_tune_trending) : mood, a::tuneHero);

        LinearLayout shelves = group(R.string.set_group_shelves);
        toggle(shelves, "tiles", R.string.set_tiles, R.string.set_tiles_hint, prefs.tvStyleTiles(),
                () -> { prefs.setTvStyleTiles(!prefs.tvStyleTiles()); apply(HOME_SCREEN); });
        toggle(shelves, "labels", R.string.set_labels, R.string.set_labels_hint, prefs.posterLabels(),
                () -> { prefs.setPosterLabels(!prefs.posterLabels()); apply(HOME_SCREEN); });
        toggle(shelves, "wide", R.string.set_wide, R.string.set_wide_hint, prefs.wideCards(),
                () -> { prefs.setWideCards(!prefs.wideCards()); apply(HOME_SCREEN); });
        choice(shelves, "cardSize", R.string.set_card_size, 0, names(R.string.size_compact, R.string.size_standard, R.string.size_large),
                prefs.cardSize(), i -> { prefs.setCardSize(i); apply(HOME_SCREEN); });
        String[] delays = new String[Prefs.EXPAND_DELAYS.length];
        int current = 0;
        for (int i = 0; i < delays.length; i++) {
            int seconds = Prefs.EXPAND_DELAYS[i];
            delays[i] = seconds == 0 ? a.getString(R.string.off) : a.getString(R.string.set_seconds, seconds);
            if (seconds == prefs.expandDelay()) current = i;
        }
        choice(shelves, "expand", R.string.set_expand, R.string.set_expand_hint, delays, current,
                i -> { prefs.setExpandDelay(Prefs.EXPAND_DELAYS[i]); apply(HOME_SCREEN); });

        LinearLayout rows = group(R.string.set_group_rows);
        if (a.plex.isLinked()) {
            rowToggle(rows, HomeActivity.ROW_PLEX_CONTINUE, R.string.row_plex_continue);
            rowToggle(rows, HomeActivity.ROW_PLEX_RECENT, R.string.row_plex_recent);
        }
        rowToggle(rows, HomeActivity.ROW_TOP_PICKS, R.string.row_top_picks);
        rowToggle(rows, HomeActivity.ROW_SHOWS, R.string.row_popular_shows);
        rowToggle(rows, HomeActivity.ROW_MOVIES, R.string.row_popular_movies);
        rowToggle(rows, HomeActivity.ROW_TOP_RATED, R.string.row_top_rated);

        int hidden = prefs.hiddenApps().size();
        if (hidden > 0) {
            LinearLayout appsGroup = group(R.string.set_group_apps);
            action(appsGroup, "unhide", a.getString(R.string.set_hidden_apps), a.getString(R.string.set_hidden_apps_hint),
                    String.valueOf(hidden), () -> { prefs.setHiddenApps(new ArrayList<>()); apply(HOME_SCREEN); });
        }
    }

    private void rowToggle(LinearLayout group, String key, int title) {
        toggle(group, "row:" + key, title, 0, prefs.rowShown(key), () -> { prefs.setRowShown(key, !prefs.rowShown(key)); apply(HOME_SCREEN); });
    }

    private void playback() {
        LinearLayout open = group(R.string.set_group_open);
        final String[] values = {Prefs.PLAY_ASK, Prefs.PLAY_STREMIO, Prefs.PLAY_NUVIO, Prefs.PLAY_PLEX};
        String[] labels = new String[values.length];
        int current = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = i == 0 ? a.getString(R.string.play_ask) : HomeActivity.playerName(values[i]);
            if (values[i].equals(prefs.playWith())) current = i;
        }
        choice(open, "player", R.string.set_player, R.string.set_player_hint, labels, current,
                i -> { prefs.setPlayWith(values[i]); apply(PANE); });

        // Say plainly which of them Phone TV can actually see, so a missing app is no surprise later.
        List<String> found = new ArrayList<>(), missing = new ArrayList<>();
        for (String p : HomeActivity.PLAYERS) (a.playerPackage(p) != null ? found : missing).add(HomeActivity.playerName(p));
        StringBuilder status = new StringBuilder();
        if (!found.isEmpty()) status.append(a.getString(R.string.set_players_found, TextUtils.join(", ", found)));
        if (!missing.isEmpty()) {
            if (status.length() > 0) status.append('\n');
            status.append(a.getString(R.string.set_players_missing, TextUtils.join(", ", missing)));
        }
        info(open, status.toString());
    }

    private void sources() {
        LinearLayout plexGroup = group(R.string.set_group_plex);
        if (a.plex.isLinked()) {
            String profile = prefs.plexProfileName().isEmpty() ? a.getString(R.string.plex_profile_owner) : prefs.plexProfileName();
            action(plexGroup, "plexProfile", a.getString(R.string.set_plex_profile), a.getString(R.string.set_plex_profile_hint), profile, a::choosePlexProfile);
            String server = prefs.plexServerName().isEmpty() ? a.getString(R.string.plex_no_server_short) : prefs.plexServerName();
            action(plexGroup, "plexOff", a.getString(R.string.set_plex_disconnect), null, server, () -> { a.disconnectPlex(); apply(PANE); });
        } else {
            action(plexGroup, "plexOn", a.getString(R.string.settings_plex_connect), a.getString(R.string.set_plex_hint), null, a::linkPlex);
        }

        LinearLayout lists = group(R.string.set_group_lists);
        action(lists, "trakt", a.getString(R.string.set_trakt), a.getString(R.string.set_trakt_hint),
                a.trakt.isConfigured() ? a.getString(R.string.set_trakt_on, a.trakt.refs().size()) : a.getString(R.string.off), a::editTrakt);
        action(lists, "refresh", a.getString(R.string.settings_refresh), a.getString(R.string.set_refresh_hint), null,
                () -> { a.toast(a.getString(R.string.refreshing)); a.refreshContent(); });
    }

    private void system() {
        LinearLayout homeGroup = group(R.string.set_group_home_app);
        if (a.tvDevice) {
            // TVs have no "choose Home app" setting; the opt-in HomeKeeperService stands in for it.
            boolean on = a.homeKeeperEnabled();
            action(homeGroup, "keeper", a.getString(on ? R.string.settings_keeper_on : R.string.settings_keeper_off),
                    a.getString(R.string.set_keeper_hint), null, a::explainHomeKeeper);
            if (on) {
                action(homeGroup, "keeperPause", a.getString(R.string.settings_keeper_pause), null, null, () -> {
                    prefs.pauseKeeper(System.currentTimeMillis() + 60_000);
                    a.toast(a.getString(R.string.keeper_paused));
                    a.moveTaskToBack(true);
                });
            }
        } else {
            action(homeGroup, "home", a.getString(R.string.settings_home), a.getString(R.string.set_home_app_hint),
                    a.isDefaultHome() ? a.getString(R.string.on) : null,
                    () -> { if (!a.startSafely(new Intent(Settings.ACTION_HOME_SETTINGS))) a.startSafely(new Intent(Settings.ACTION_SETTINGS)); });
        }

        LinearLayout updates = group(R.string.set_group_updates);
        boolean ready = a.readyUpdate != null;
        action(updates, "update", ready ? a.getString(R.string.update_ready, a.readyUpdate.versionName) : a.getString(R.string.settings_check_update),
                a.getString(R.string.set_update_hint, BuildConfig.VERSION_NAME), ready ? a.getString(R.string.update_install) : null,
                () -> { if (a.readyUpdate != null) a.installUpdate(); else { a.toast(a.getString(R.string.update_checking)); a.checkForUpdate(true); } });

        LinearLayout about = group(R.string.set_group_about);
        action(about, "site", a.getString(R.string.set_about, BuildConfig.VERSION_NAME), a.getString(R.string.set_about_hint), "zgenmedia.com",
                () -> a.openUrl(SITE_URL, null));
        action(about, "kofi", a.getString(R.string.settings_donate), a.getString(R.string.set_donate_hint), null,
                () -> a.openUrl(HomeActivity.KOFI_URL, null));
        action(about, "source", a.getString(R.string.set_source), a.getString(R.string.set_source_hint), "GitHub",
                () -> a.openUrl(SOURCE_URL, null));
    }

    /** Applies a change. Look changes redraw both screens; layout changes redraw the home screen. */
    private void apply(int scope) {
        if (scope == EVERYTHING) { a.applyLook(); a.render(); build(); return; }
        if (scope == HOME_SCREEN) a.render();
        fillPane();
    }

    // ===================================================================== controls

    private String[] names(int... ids) {
        String[] out = new String[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = a.getString(ids[i]);
        return out;
    }

    /** A captioned card that rows are added to. */
    private LinearLayout group(int caption) {
        TextView label = ui.label(a.getString(caption).toUpperCase(java.util.Locale.getDefault()), 11, Ui.MUTED, Ui.MEDIUM);
        label.setLetterSpacing(0.08f);
        label.setPadding(ui.dp(4), ui.dp(16), 0, ui.dp(7));
        pane.addView(label);
        LinearLayout g = new LinearLayout(a);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(ui.round(Ui.SURFACE, 16));
        g.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        pane.addView(g, new LinearLayout.LayoutParams(-1, -2));
        return g;
    }

    /** Title and optional hint, stacked. */
    private LinearLayout texts(String title, String hint) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(ui.label(title, 15, Ui.TEXT, Ui.MEDIUM));
        if (hint != null && !hint.isEmpty()) {
            TextView h = ui.label(hint, 12, Ui.MUTED, null);
            h.setLineSpacing(0, 1.15f);
            h.setPadding(0, ui.dp(2), 0, 0);
            t.addView(h);
        }
        return t;
    }

    /** A row that does one thing when pressed; {@code trailing} sits at its right edge. */
    private void pressRow(LinearLayout group, String key, String title, String hint, View trailing, Runnable onPress) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(54));
        row.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        row.setBackground(ui.ripple(ui.round(0x00000000, 12)));
        row.setFocusable(true);
        row.setClickable(true);
        ui.focusRing(row, 12);
        row.addView(texts(title, hint), new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = ui.dp(12);
            row.addView(trailing, lp);
        }
        row.setOnClickListener(v -> { lastUsed = key; onPress.run(); });
        controls.put(key, row);
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void toggle(LinearLayout group, String key, int title, int hint, boolean on, Runnable flip) {
        FrameLayout track = new FrameLayout(a);
        track.setBackground(ui.round(on ? Ui.ACCENT : 0x33FFFFFF, 12));
        View knob = new View(a);
        knob.setBackground(ui.round(on ? Ui.ON_ACCENT : 0xFFE8EAED, 9));
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        klp.setMargins(ui.dp(3), 0, ui.dp(3), 0);
        track.addView(knob, klp);
        track.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(42), ui.dp(24)));
        FrameLayout holder = new FrameLayout(a);
        holder.addView(track, new FrameLayout.LayoutParams(ui.dp(42), ui.dp(24)));
        holder.setContentDescription(a.getString(on ? R.string.on : R.string.off));
        pressRow(group, key, a.getString(title), hint == 0 ? null : a.getString(hint), holder, flip);
    }

    private void action(LinearLayout group, String key, String title, String hint, String value, Runnable run) {
        TextView trailing = ui.label(value == null ? "›" : value + "  ›", 13, Ui.TEXT_DIM, Ui.MEDIUM);
        trailing.setMaxLines(1);
        trailing.setEllipsize(TextUtils.TruncateAt.END);
        trailing.setMaxWidth(ui.dp(170));
        pressRow(group, key, title, hint, trailing, run);
    }

    /** A plain note inside a group; never takes focus. */
    private void info(LinearLayout group, String text) {
        TextView t = ui.label(text, 12, Ui.MUTED, null);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(12));
        group.addView(t);
    }

    /** A setting with a handful of answers, shown as chips so all of them are visible at once. */
    private void choice(LinearLayout group, String key, int title, int hint, String[] options, int selected, IntConsumer pick) {
        LinearLayout chips = chipRow(group, a.getString(title), hint == 0 ? null : a.getString(hint));
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            boolean on = i == selected;
            TextView chip = chip(options[i], on ? Ui.ACCENT : 0x1FFFFFFF, on ? Ui.ON_ACCENT : Ui.TEXT);
            addChip(chips, chip, key + ":" + i, on, () -> pick.accept(index));
        }
    }

    /** Accent colors: each chip carries a dot of its own color. */
    private void accentRow(LinearLayout group) {
        LinearLayout chips = chipRow(group, a.getString(R.string.set_accent), a.getString(R.string.set_accent_hint));
        for (int i = 0; i < Ui.PALETTES.length; i++) {
            final int index = i;
            Ui.Palette p = Ui.PALETTES[i];
            boolean on = i == prefs.accent();
            SpannableString text = new SpannableString("●  " + a.getString(p.name));
            text.setSpan(new ForegroundColorSpan(p.accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView chip = chip(text, 0x1FFFFFFF, Ui.TEXT);
            if (on) {
                GradientDrawable chosen = ui.round(0x33FFFFFF, 18);
                chosen.setStroke(Math.max(2, ui.dp(1.5f)), p.accent);
                chip.setBackground(ui.ripple(chosen));
            }
            addChip(chips, chip, "accent:" + i, on, () -> { prefs.setAccent(index); apply(EVERYTHING); });
        }
    }

    private LinearLayout chipRow(LinearLayout group, String title, String hint) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(14), ui.dp(10), 0, ui.dp(10));
        box.addView(texts(title, hint));
        HorizontalScrollView shelf = new HorizontalScrollView(a);
        shelf.setHorizontalScrollBarEnabled(false);
        shelf.setClipToPadding(false);
        shelf.setPadding(0, ui.dp(10), ui.dp(14), ui.dp(2));
        LinearLayout chips = new LinearLayout(a);
        shelf.addView(chips);
        box.addView(shelf);
        group.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return chips;
    }

    private TextView chip(CharSequence text, int bg, int fg) {
        TextView chip = ui.label(text, 13, fg, Ui.MEDIUM);
        chip.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        chip.setBackground(ui.ripple(ui.round(bg, 18)));
        chip.setFocusable(true);
        chip.setClickable(true);
        ui.focusRing(chip, 18);
        return chip;
    }

    private void addChip(LinearLayout chips, TextView chip, String key, boolean chosen, Runnable onPick) {
        chip.setOnClickListener(v -> { lastUsed = key; onPick.run(); });
        controls.put(key, chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(6);
        chips.addView(chip, lp);
        if (chosen) chips.setTag(R.id.selected_chip, chip);
    }
}
