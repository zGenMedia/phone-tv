package com.zgenmedia.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone TV home screen.
 *
 * Layout follows the Google TV "For you" tab, top to bottom:
 * <pre>
 *   top bar     search · tabs · clock · settings   (overlays content, hides on scroll)
 *   hero        featured titles, auto-advances, swipeable
 *   Your apps   favorites as 16:9 tiles
 *   rows        Plex (when linked) and catalog rows
 *   footer      version · Ko-fi
 * </pre>
 *
 * Pages that cover the whole screen (a title's details, Settings) are
 * {@link Overlay}s added above this layout; see {@link #openOverlay}.
 *
 * Views are built in code instead of XML so the screen can be rebuilt in one
 * pass when the size setting or orientation changes. Rows are short (at most a
 * few dozen cards) and artwork is lazy-loaded, so we skip RecyclerView and the
 * androidx dependency chain it brings; the APK stays around 150 KB.
 *
 * Copyright (c) 2026 zGenMedia — https://zgenmedia.com
 */
public class HomeActivity extends Activity {

    static final String KOFI_URL = "https://ko-fi.com/zgenmedia/tiers";
    private static final String STREMIO_PACKAGE = "com.stremio.one";
    private static final String[] NUVIO_PACKAGES = {"com.nuvio.tv", "com.nuvio.app"};
    private static final String NUVIO_URL = "https://github.com/NuvioMedia";
    /** Share of the screen width the banner artwork takes when it sits beside the details. */
    private static final float HERO_ART_WIDTH = 0.72f;
    private static final String TVMATE_HINT = "tvmate";

    private static final int TAB_FOR_YOU = 0, TAB_MOVIES = 1, TAB_SHOWS = 2, TAB_LIBRARY = 3, TAB_APPS = 4, TAB_DISCOVER = 5;
    private static final int[] TAB_LABELS = {R.string.tab_for_you, R.string.tab_movies, R.string.tab_shows, R.string.tab_library, R.string.tab_apps, R.string.tab_discover};

    private static final long HERO_INTERVAL_MS = 9_000;
    private static final long CONTENT_TTL_MS = 3 * 60 * 60 * 1000L;
    private static final long PLEX_POLL_MS = 2_000;
    private static final int REQUEST_TILE_IMAGE = 41, REQUEST_BACKUP_SAVE = 42, REQUEST_BACKUP_OPEN = 43, REQUEST_VOICE = 44, REQUEST_PLAYER = 45;

    // Rows on "For you" that can be switched off in Settings. Keys are stored; don't rename.
    static final String ROW_PLEX_CONTINUE = "plexContinue", ROW_PLEX_RECENT = "plexRecent",
            ROW_LIBRARY = "library", ROW_CONTINUE = "continue", ROW_TOP_PICKS = "topPicks", ROW_SHOWS = "popularShows", ROW_MOVIES = "popularMovies", ROW_TOP_RATED = "topRated",
            ROW_TRAKT = "trakt";
    /** The For you rows in their starting order; Settings › Home screen can rearrange and hide them. */
    static final String[] ROW_ORDER = {ROW_CONTINUE, ROW_LIBRARY, ROW_TOP_PICKS, ROW_PLEX_CONTINUE, ROW_PLEX_RECENT, ROW_TRAKT, ROW_SHOWS, ROW_MOVIES, ROW_TOP_RATED};

    static int rowLabel(String key) {
        switch (key) {
            case ROW_CONTINUE: return R.string.row_continue;
            case ROW_LIBRARY: return R.string.row_library;
            case ROW_TOP_PICKS: return R.string.row_top_picks;
            case ROW_PLEX_CONTINUE: return R.string.row_plex_continue;
            case ROW_PLEX_RECENT: return R.string.row_plex_recent;
            case ROW_TRAKT: return R.string.row_trakt_lists;
            case ROW_SHOWS: return R.string.row_popular_shows;
            case ROW_MOVIES: return R.string.row_popular_movies;
            default: return R.string.row_top_rated;
        }
    }

    /** Show or hide a For you row, or move it up or down the page. */
    void arrangeRow(String key, Runnable changed) {
        List<String> order = prefs.rowOrder(ROW_ORDER);
        int at = order.indexOf(key);
        boolean shown = prefs.rowShown(key);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add(getString(shown ? R.string.row_hide : R.string.row_show));
        acts.add(() -> { prefs.setRowShown(key, !shown); changed.run(); });
        if (at > 0) { labels.add(getString(R.string.row_move_up)); acts.add(() -> { java.util.Collections.swap(order, at, at - 1); prefs.setRowOrder(order); changed.run(); }); }
        if (at >= 0 && at < order.size() - 1) { labels.add(getString(R.string.row_move_down)); acts.add(() -> { java.util.Collections.swap(order, at, at + 1); prefs.setRowOrder(order); changed.run(); }); }
        if (at > 0) { labels.add(getString(R.string.row_move_top)); acts.add(() -> { order.remove(at); order.add(0, key); prefs.setRowOrder(order); changed.run(); }); }
        labels.add(getString(R.string.cancel));
        acts.add(null);
        showNotice(getString(rowLabel(key)), getString(R.string.row_arrange_body, at + 1, order.size()), labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "phonetv-io");
        t.setDaemon(true);
        return t;
    });

    Prefs prefs;
    Ui ui;
    ImageLoader images;
    AppRepository apps;
    private TileArt tileArt;
    CatalogRepository catalog;
    PlexClient plex;
    TraktClient trakt;
    private HeroMood heroMood;
    Updater updater;
    boolean tvDevice;          // Shield, Fire TV, Google TV: remote-first, no touch
    private boolean autoFocusHero = true;

    // Remote navigation: named focus targets so focus survives a rebuild of the screen.
    final Map<String, View> focusTargets = new HashMap<>();
    private String restoreFocusKey;
    private boolean insetsWaiting; // insets changed while a text box was in use
    private int rowSeq, heroHeightPx, heroBackdropWidthPx, selectedChipId, heroWatchId;
    private volatile AlertDialog plexDialog;

    // Banner pieces kept so it can tuck itself away while the top menu has focus.
    private FrameLayout heroFrame;
    private View heroButtons, heroPager;
    private int heroFullHeightPx;
    private boolean heroCollapsed;
    Updater.Release readyUpdate;   // downloaded and verified, waiting for the user's tap
    private java.io.File readyApk;
    private List<MediaItem> moodItems = Collections.emptyList();
    private boolean moodLoading;

    private List<MediaItem> plexContinue = Collections.emptyList();
    private List<MediaItem> plexRecent = Collections.emptyList();
    private String plexError;   // last Plex failure, shown on the home screen until it clears
    private List<TraktClient.TraktRow> traktRows = Collections.emptyList();
    private List<String> traktErrors = Collections.emptyList();
    private volatile boolean plexLinking;

    // Screen state. "home" holds the launcher; pages (details, settings) are added to root above it.
    private FrameLayout root, home;
    private Overlay overlay; // the page on top, if any
    private final ArrayDeque<Overlay> pages = new ArrayDeque<>(); // every open page, newest first
    private final Map<Overlay, View> pageFocus = new HashMap<>();                     // where the remote was on a covered page
    private String overlayReturnKey;

    // The user's shelf, and watch history reported by services other than this app.
    Library library;
    People people;
    private List<MediaItem> plexHistory = Collections.emptyList();
    private List<MediaItem> traktPaused = Collections.emptyList();
    private List<Library.Watch> traktHistory = Collections.emptyList();
    private List<MediaItem> traktWatchlist = Collections.emptyList(), traktCollection = Collections.emptyList();
    private TraktClient.DeviceCode traktCode; // a Trakt sign-in waiting for the user, if any
    private String historyError;
    private boolean historyLoading;

    // TV link: what the phone's own screen does while a TV is attached (see syncTvLink).
    /** Set when Android wouldn't give the TV its own picture; cleared when the displays change. */
    static boolean tvLinkRefused;
    private android.hardware.display.DisplayManager displayManager;
    private boolean caseDismissed, refusalTold, resumed;
    private final android.hardware.display.DisplayManager.DisplayListener displayListener =
            new android.hardware.display.DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { displaysChanged(); }
                @Override public void onDisplayRemoved(int id) { displaysChanged(); }
                @Override public void onDisplayChanged(int id) { }
            };
    private final Runnable screenSaver = () -> {
        if (overlay == null && resumed && hasWindowFocus()) openOverlay(saverPage());
    };
    private ScrollView scroll;
    private View topBar;
    private boolean topBarHidden;
    private int tab = TAB_FOR_YOU;
    int insetTop, insetBottom, insetLeft, insetRight;
    private String appFilter = "";
    private LinearLayout appGrid;
    private String tileImageTarget; // package awaiting a picked image

    // Hero carousel
    private final List<MediaItem> heroItems = new ArrayList<>();
    private int heroIndex;
    private boolean heroOnScreen = true;
    private ImageView[] heroArt;   // two stacked layers, cross-faded
    private int heroArtFront;
    private ImageView heroLogo;
    private TextView heroTitle, heroMeta, heroSummary;
    private LinearLayout heroDots;
    private final Runnable heroAdvance = new Runnable() {
        @Override public void run() {
            // Skip the transition when nobody can see it; saves decode work and battery.
            if (heroItems.size() > 1 && heroOnScreen && overlay == null && hasWindowFocus()) showHero((heroIndex + 1) % heroItems.size());
            main.postDelayed(this, HERO_INTERVAL_MS);
        }
    };

    // =================================================================== lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!acceptDisplay()) { finish(); return; }
        prefs = new Prefs(this);
        ui = new Ui(this, prefs.scale());
        images = new ImageLoader(this);
        tvDevice = isTvDevice() || isTvInstance();
        if (tvDevice) prefs.setDefaultSizeIndex(2);
        applyLook();
        ui.onFocus = this::reveal;
        apps = new AppRepository(this, prefs, tvDevice);
        tileArt = new TileArt(this, ui);
        catalog = new CatalogRepository(this, io);
        plex = new PlexClient(prefs);
        trakt = new TraktClient(this, prefs);
        library = Library.of(this);
        people = new People(this);
        heroMood = new HeroMood(this, new HeroMood.WikidataSource());
        updater = new Updater(this);

        try { HttpResponseCache.install(new File(getCacheDir(), "http"), 20L * 1024 * 1024); }
        catch (IOException ignored) { /* runs fine uncached */ }

        setupWindow();
        apps.reload();
        apps.seedFavorites(STREMIO_PACKAGE, TVMATE_HINT, PlexClient.PACKAGE, "netflix", "youtube", "primevideo", "disney", "kodi");
        catalog.loadCached();
        render();
        if (state == null && Profiles.several(this) && Profiles.askOnStart(this)) showProfiles();
        refreshContent();
        checkForUpdate(false);
        if (!tvDevice) {
            displayManager = (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (displayManager != null) displayManager.registerDisplayListener(displayListener, main);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemBars();
        if (!plex.isLinked() && prefs.plexPendingPin() != 0 && !plexLinking) pollPlexPin(prefs.plexPendingPin(), null);
        boolean appsChanged = apps.reload();
        if (catalog.isOlderThan(CONTENT_TTL_MS)) refreshContent();
        // Coming back from Plex: what was watched or marked there should be gone from the row now.
        else if (plex.isLinked() && System.currentTimeMillis() - plexRefreshedAt > 15_000) refreshPlex();
        if (updater.due()) checkForUpdate(false);
        if (appsChanged) renderPreservingScroll();
        else restartHeroTimer();
        resumed = true;
        scheduleTrailer();
        syncTvLink();
        restartScreenSaver();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        stopTrailer();
        forgetOldTrailers();
        main.removeCallbacks(heroAdvance);
        main.removeCallbacks(screenSaver);
    }

    /** Trailer records are kept only for titles the home screen still features. */
    private void forgetOldTrailers() {
        if (trailer == null || catalog.isEmpty()) return;
        java.util.Set<String> featured = new java.util.HashSet<>();
        for (String key : new String[]{CatalogRepository.MOVIES, CatalogRepository.SHOWS}) {
            List<MediaItem> list = catalog.get(key);
            if (list != null) for (MediaItem m : list) featured.add(m.id);
        }
        for (MediaItem m : moodItems) featured.add(m.id);
        for (MediaItem m : heroItems) featured.add(m.id);
        trailer.keepOnly(featured);
    }

    /** Pushes the look settings into the theme and the view helpers. Call before rebuilding views. */
    void applyLook() {
        Ui.applyTheme(prefs);
        ui.setScale(prefs.scale());
        ui.focusStyle = prefs.focusStyle();
        ui.focusScale = Ui.FOCUS_SCALES[prefs.focusZoom()];
        ui.cardRadius = Ui.CARD_RADII[prefs.cardCorners()];
        if (root != null) root.setBackgroundColor(Ui.BG);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (images == null) return; // never got past onCreate (see acceptDisplay)
        if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
        images.shutdown();
        io.shutdownNow();
    }

    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) applySystemBars(); }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        render();
        for (Overlay page : pages) page.rebuild();
    }

    /** Home pressed while already home: go back to the top of "For you", like a TV. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) { onInstallStatus(intent); return; }
        goHome();
        caseDismissed = false; // Home on the phone puts the case screen back
        syncTvLink();
    }

    /**
     * As the Home app, Back never exits. When Phone TV is just an app (a Fire TV
     * or Shield without the home replacement on), Back at the top leaves it,
     * like any other TV app.
     */
    @Override
    public void onBackPressed() {
        if (overlay != null) { if (!overlay.onBack()) closeOverlay(); return; }
        boolean atTop = tab == TAB_FOR_YOU && (scroll == null || scroll.getScrollY() == 0);
        if (atTop && !isTvInstance() && !isDefaultHome() && !homeKeeperEnabled()) { moveTaskToBack(true); return; }
        goHome();
    }

    /** Remote MENU button: options for the focused app tile, otherwise Settings. */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            if (overlay != null) return true;
            View focused = focused();
            Object tag = focused == null ? null : focused.getTag();
            if (tag instanceof AppRepository.App) showAppMenu((AppRepository.App) tag); else showSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * All D-pad movement on the home screen is decided here, not by ScrollView.
     *
     * ScrollView's own arrow handling only looks half a screen ahead: when the next
     * item is further than that it scrolls, takes focus for itself, and the following
     * key press restarts from the bottom of the page. On a tall screen that made Up
     * from the rows bounce back down instead of reaching the banner and the top menu.
     * We pick the next view ourselves and let {@link #reveal} glide it into place.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (overlay instanceof CaseScreen && !e.isSystem()) {
            // Volume, Back and Home pass through; anything else is for the case screen to answer.
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) ((CaseScreen) overlay).onInput(false);
            return true;
        }
        if (overlay instanceof ArtSaverScreen && !e.isSystem()) {
            // Any key wakes the screen; OK also opens the title that was showing.
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                MediaItem showing = ((ArtSaverScreen) overlay).current();
                closeOverlay();
                int k = e.getKeyCode();
                if (showing != null && (k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER)) showDetails(showing);
            }
            restartScreenSaver();
            return true;
        }
        restartScreenSaver();
        // A remote's search or mic key, when the system passes it to the home screen.
        if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_SEARCH && overlay == null) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) startVoiceSearch();
            return true;
        }
        int direction = focusDirection(e.getKeyCode());
        boolean select = e.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_CENTER;
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && (direction != 0 || select)) {
            View focused = focused();
            if (overlay != null) {
                // A page is open: it owns the remote until it closes.
                if (focused == null || !isInside(focused, overlay.view())) {
                    View first = overlay.firstFocus();
                    if (first != null && first.requestFocus()) return true;
                } else if (direction != 0 && overlay.move(focused, direction)) {
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
            if (focused == null || focused == scroll) {
                // Nothing selected yet (e.g. after touch use): land on the banner, or the current tab.
                View t = focusTargets.get("hero:watch");
                if (t == null || !t.isShown()) t = focusTargets.get("tab:" + tab);
                if (t != null && t.requestFocus()) return true;
            } else if (direction != 0 && moveFocus(focused, direction)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private static int focusDirection(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return 0;
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        restartScreenSaver();
        return super.dispatchTouchEvent(e);
    }

    /**
     * The tile with this focus name. Rows are numbered top to bottom, so a row appearing above
     * (the library row, after the first save) renumbers the ones below it; when the exact name
     * is gone, this finds the same title in the nearest row instead.
     */
    private View focusTarget(String key) {
        View exact = focusTargets.get(key);
        int colon = key.indexOf(':');
        if (exact != null || !key.startsWith("row") || colon < 0) return exact;
        String title = key.substring(colon);
        int row = rowNumber(key), nearest = Integer.MAX_VALUE;
        View best = null;
        for (Map.Entry<String, View> e : focusTargets.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("row") || !k.endsWith(title) || k.indexOf(':') != k.length() - title.length()) continue;
            int away = Math.abs(rowNumber(k) - row);
            if (away < nearest) { nearest = away; best = e.getValue(); }
        }
        return best;
    }

    private static int rowNumber(String key) {
        try { return Integer.parseInt(key.substring(3, key.indexOf(':'))); }
        catch (NumberFormatException e) { return 0; }
    }

    /** A row the D-pad moves along sideways: a scrolling shelf, or one line of a grid. */
    private static boolean isShelf(android.view.ViewParent p) {
        return p instanceof HorizontalScrollView || (p instanceof View && ((View) p).getTag(R.id.focus_row) != null);
    }

    /** The view under the remote, on the home screen or on a page above it. */
    View focused() { return root == null ? null : root.findFocus(); }

    static boolean isInside(View v, View ancestor) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == ancestor) return true;
        return v == ancestor;
    }

    /**
     * The D-pad rule every screen shares: Left and Right stay inside the current shelf
     * (or button group); Up and Down look across the whole page.
     */
    static View nextFocus(ViewGroup page, View focused, int direction) {
        android.view.FocusFinder finder = android.view.FocusFinder.getInstance();
        if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
            View next = finder.findNextFocus(page, focused, direction);
            // Arriving at a strip of chips from another row: land on the chosen chip, not the nearest one.
            if (next != null && next.getParent() instanceof View) {
                View strip = (View) next.getParent();
                Object chosen = strip.getTag(R.id.selected_chip);
                if (chosen instanceof View && focused.getParent() != strip) return (View) chosen;
            }
            return next;
        }
        ViewGroup group = null;
        for (android.view.ViewParent p = focused.getParent(); p instanceof ViewGroup && p != page; p = p.getParent()) {
            if (isShelf(p)) { group = (ViewGroup) p; break; }
        }
        if (group == null && focused.getParent() instanceof ViewGroup) group = (ViewGroup) focused.getParent();
        return group == null ? null : finder.findNextFocus(group, focused, direction);
    }

    /** @return true if the key was used (even when there was nowhere further to go). */
    private boolean moveFocus(View focused, int direction) {
        boolean horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT;
        Object key = focused.getTag(R.id.focus_key);
        // Past the edge of the banner buttons, Left/Right page the carousel like a TV remote.
        if ("hero:watch".equals(key) && direction == View.FOCUS_LEFT) { stepHero(-1); return true; }
        if ("hero:tune".equals(key) && direction == View.FOCUS_RIGHT) { stepHero(1); return true; }

        // Leaving the top menu downwards: bring the banner's buttons back before looking for them.
        if (direction == View.FOCUS_DOWN) setHeroCollapsed(false);

        android.view.FocusFinder finder = android.view.FocusFinder.getInstance();
        View next = null;
        if (horizontal) {
            if (focused instanceof EditText) return false; // let the cursor move inside text boxes
            // Stay inside the current shelf or button group; never jump to another row sideways.
            ViewGroup group = null;
            boolean inTopBar = false;
            for (android.view.ViewParent p = focused.getParent(); p instanceof ViewGroup; p = p.getParent()) {
                if (group == null && isShelf(p)) group = (ViewGroup) p;
                if (p == topBar) inTopBar = true;
            }
            if (group == null && focused.getParent() instanceof ViewGroup) group = (ViewGroup) focused.getParent();
            if (group != null) next = finder.findNextFocus(group, focused, direction);
            if (next == null && inTopBar) next = finder.findNextFocus((ViewGroup) topBar, focused, direction);
        } else {
            next = finder.findNextFocus(home, focused, direction);
            // Reaching the top menu from the page always lands on the current tab.
            if (next != null && isInside(next, topBar) && !isInside(focused, topBar)) {
                View current = focusTargets.get("tab:" + tab);
                if (current != null) next = current;
            }
            // Arriving at a strip of chips (the Library's parts): land on the chosen one, not the nearest.
            if (next != null && next.getParent() instanceof View) {
                View strip = (View) next.getParent();
                Object chosen = strip.getTag(R.id.selected_chip);
                if (chosen instanceof View && focused.getParent() != strip) next = (View) chosen;
            }
        }
        if (next != null && next != focused) next.requestFocus();
        return true;
    }

    /** Names a focusable view so {@link #render} can return focus to it after rebuilding. */
    private <T extends View> T focusKey(T v, String key) {
        v.setTag(R.id.focus_key, key);
        focusTargets.put(key, v);
        return v;
    }

    /**
     * Glides the focused view to its resting place: a little before the middle of its
     * shelf, and its shelf a little above the middle of the page. Works for the home
     * screen and for any page above it. Anything in a page's top zone (the banner, a
     * details header) scrolls that page back to the top instead.
     */
    void reveal(View v) {
        ScrollView page = null;
        HorizontalScrollView shelf = null;
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (shelf == null && p instanceof HorizontalScrollView) shelf = (HorizontalScrollView) p;
            if (p instanceof ScrollView) { page = (ScrollView) p; break; }
        }
        if (page == null) return;
        boolean onHome = page == scroll;
        if (onHome) setHeroCollapsed(false);

        android.graphics.Rect r = new android.graphics.Rect();
        if (shelf != null && shelf.getChildCount() > 0) {
            v.getDrawingRect(r);
            shelf.offsetDescendantRectToMyCoords(v, r);
            int visible = shelf.getWidth() - shelf.getPaddingLeft() - shelf.getPaddingRight();
            Motion.glideX(shelf, r.centerX() - shelf.getPaddingLeft() - (int) (visible * Motion.PIVOT));
        }
        v.getDrawingRect(r);
        page.offsetDescendantRectToMyCoords(v, r);
        Object zone = page.getTag(R.id.top_zone);
        int topZone = onHome ? heroHeightPx : zone instanceof Integer ? (Integer) zone : 0;
        int y = r.bottom <= topZone ? 0 : r.centerY() - (int) (page.getHeight() * Motion.PIVOT);
        Motion.glideY(page, y);
        if (onHome && y <= 0) setTopBarVisible(true);
    }

    /** TV on-screen keyboards only open on request; do it whenever a text box is selected. */
    private void keyboardOnSelect(EditText box) {
        box.setOnClickListener(v -> showKeyboard(box));
        // Insets held back while typing are applied once the box is let go.
        box.setOnFocusChangeListener((v, has) -> { if (!has && insetsWaiting) root.post(root::requestApplyInsets); });
        box.setOnKeyListener((v, code, e) -> {
            boolean select = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                    || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER || code == android.view.KeyEvent.KEYCODE_BUTTON_A;
            if (select && e.getAction() == android.view.KeyEvent.ACTION_UP) { showKeyboard(box); return true; }
            return false;
        });
    }

    private void showKeyboard(EditText box) {
        box.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(box, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private boolean isTvDevice() {
        android.app.UiModeManager ui = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        android.content.pm.PackageManager pm = getPackageManager();
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature("amazon.hardware.fire_tv");
    }

    /** Amazon's boxes and sticks: their Settings menus are laid out differently from Android TV and Google TV. */
    boolean isFireTv() {
        return getPackageManager().hasSystemFeature("amazon.hardware.fire_tv") || "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
    }

    boolean isDefaultHome() {
        android.content.pm.ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && home.activityInfo != null && getPackageName().equals(home.activityInfo.packageName);
    }

    boolean homeKeeperEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/" + HomeKeeperService.class.getName());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PLAYER) {
            renderPreservingScroll(); // Continue watching changed
            MediaItem fromPlex = plexInPlayer;
            plexInPlayer = null;
            if (fromPlex != null) {
                // Played from Plex: the row should show the new position; a failed play can go to the Plex app.
                main.postDelayed(this::refreshPlex, 1_500);
                if (resultCode == PlayerActivity.RESULT_OTHER_SOURCE) openPlexApp(fromPlex);
                return;
            }
            if (data == null || (resultCode != PlayerActivity.RESULT_NEXT && resultCode != PlayerActivity.RESULT_OTHER_SOURCE)) return;
            MediaItem m = MediaItem.catalog(data.getStringExtra(PlayerActivity.X_ID), data.getStringExtra(PlayerActivity.X_TYPE),
                    data.getStringExtra(PlayerActivity.X_TITLE), "");
            showStreams(m, data.getStringExtra(PlayerActivity.X_EPISODE));
            return;
        }
        if (requestCode == REQUEST_VOICE) {
            java.util.ArrayList<String> heard = resultCode == RESULT_OK && data != null
                    ? data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS) : null;
            if (heard != null && !heard.isEmpty()) {
                // Said in Discover: a title or an actor. Said anywhere else: an app, a title or an actor.
                if (tab == TAB_DISCOVER) discoverQuery = heard.get(0); else { appFilter = heard.get(0); tab = TAB_APPS; }
                render();
            }
            return;
        }
        if (requestCode == REQUEST_BACKUP_SAVE || requestCode == REQUEST_BACKUP_OPEN) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                if (requestCode == REQUEST_BACKUP_SAVE) writeSetup(data.getData()); else readSetup(data.getData());
            }
            return;
        }
        if (requestCode != REQUEST_TILE_IMAGE || tileImageTarget == null) return;
        String pkg = tileImageTarget;
        tileImageTarget = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri picked = data.getData();
            io.execute(() -> {
                boolean ok = tileArt.saveCustomImage(pkg, picked);
                main.post(() -> { if (ok) renderPreservingScroll(); else toast(getString(R.string.open_failed, picked.getLastPathSegment())); });
            });
        }
    }

    // ================================================================ setup backup

    /**
     * Saves the Library (titles, actors, history) and the settings to a file the user picks:
     * Google Drive, Downloads, a USB stick. Opening that file on another device with
     * "Restore setup" brings everything across. Sign-ins and the Plex link stay behind:
     * the file holds no tokens, so it is safe to keep in a shared folder.
     */
    void saveSetup() {
        Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "PhoneTV-setup.json");
        try { startActivityForResult(pick, REQUEST_BACKUP_SAVE); }
        catch (android.content.ActivityNotFoundException e) { toast(getString(R.string.setup_no_picker)); }
    }

    void restoreSetup() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try { startActivityForResult(pick, REQUEST_BACKUP_OPEN); }
        catch (android.content.ActivityNotFoundException e) { toast(getString(R.string.setup_no_picker)); }
    }

    /** The whole setup as one JSON text: settings (no sign-ins) and the Library. */
    private String setupJson() throws JSONException {
        return new JSONObject().put("phoneTvSetup", 1).put("version", BuildConfig.VERSION_NAME)
                .put("settings", prefs.exportSettings()).put("library", new JSONObject(library.export()))
                .put("addons", Addons.of(this).exportList()).toString();
    }

    private void writeSetup(Uri to) {
        final String json;
        try {
            json = setupJson();
        } catch (JSONException e) {
            toast(getString(R.string.setup_failed, e.getMessage()));
            return;
        }
        io.execute(() -> {
            String problem = null;
            // Some file providers (Android TV's among them) reject "wt"; plain "w" is the fallback.
            // Anything a provider throws is reported, never allowed to take the launcher down.
            try (java.io.OutputStream out = openForWriting(to)) {
                if (out == null) throw new IOException("no file");
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) { problem = String.valueOf(e.getMessage()); }
            final String p = problem;
            main.post(() -> toast(p == null ? getString(R.string.setup_saved, library.titleCount(), library.peopleCount()) : getString(R.string.setup_failed, p)));
        });
    }

    private void readSetup(Uri from) {
        io.execute(() -> {
            String text;
            try (java.io.InputStream in = getContentResolver().openInputStream(from)) {
                if (in == null) throw new IOException("no file");
                text = new String(Http.readAll(in), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                final String p = String.valueOf(e.getMessage());
                main.post(() -> toast(getString(R.string.setup_failed, p)));
                return;
            }
            main.post(() -> applySetup(text));
        });
    }

    private java.io.OutputStream openForWriting(Uri to) throws IOException {
        try { return getContentResolver().openOutputStream(to, "wt"); }
        catch (IllegalArgumentException | UnsupportedOperationException e) { return getContentResolver().openOutputStream(to, "w"); }
    }

    /** Puts a setup in place: settings replaced, Library merged. */
    void applySetup(String text) {
        int added;
        try {
            JSONObject o = new JSONObject(text);
            if (o.optInt("phoneTvSetup") != 1) { toast(getString(R.string.setup_not_ours)); return; }
            prefs.importSettings(o.getJSONObject("settings"));
            added = library.merge(o.getJSONObject("library").toString());
            JSONArray links = o.optJSONArray("addons");
            if (links != null && links.length() > 0) {
                List<String> list = new ArrayList<>();
                for (int i = 0; i < links.length(); i++) list.add(links.optString(i));
                installAddons(list);
            }
        } catch (JSONException | RuntimeException e) {
            toast(getString(R.string.setup_not_ours));
            return;
        }
        Ui.applyTheme(prefs);
        applyLook();
        render();
        for (Overlay page : pages) page.rebuild();
        toast(getString(R.string.setup_restored, added));
    }

    // ------------------------------------------------- device to device, built in

    private SetupTransfer transfer;

    private SetupTransfer transfer() {
        if (transfer == null) transfer = new SetupTransfer(this);
        return transfer;
    }

    /**
     * This device offers its setup on the local network and shows a code. Nothing to install
     * or carry: the other device finds it in "Get setup from another device".
     */
    void sendSetup() {
        final String json;
        try { json = setupJson(); }
        catch (JSONException e) { toast(getString(R.string.setup_failed, e.getMessage())); return; }
        final String code = SetupTransfer.newCode();
        final String name = SetupTransfer.deviceName(this);
        TextView body = dialogText(getString(R.string.setup_send_help));
        TextView codeView = dialogCode(code.substring(0, 3) + " " + code.substring(3));
        TextView where = dialogText("");
        where.setTextSize(13);
        LinearLayout form = dialogForm(body, codeView, where);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.setup_send_title).setView(form)
                .setNegativeButton(R.string.setup_stop, null).create();
        dialog.setOnDismissListener(d -> transfer().stopOffering());
        dialog.show();
        io.execute(() -> {
            try {
                int port = transfer().offer(name, code, json, new SetupTransfer.Offering() {
                    @Override public void sent(String to) {
                        main.post(() -> { if (dialog.isShowing()) dialog.dismiss(); toast(getString(R.string.setup_sent)); });
                    }
                    @Override public void ended(String why) {
                        main.post(() -> {
                            if (dialog.isShowing()) dialog.dismiss();
                            toast(getString("wrong".equals(why) ? R.string.setup_too_many : R.string.setup_offer_ended));
                        });
                    }
                });
                String ip = SetupTransfer.localAddress();
                main.post(() -> where.setText(getString(R.string.setup_send_where, name, ip.isEmpty() ? "?" : ip + ":" + port)));
            } catch (IOException | RuntimeException e) {
                main.post(() -> { if (dialog.isShowing()) dialog.dismiss(); toast(getString(R.string.setup_failed, String.valueOf(e.getMessage()))); });
            }
        });
    }

    /**
     * Lists devices on the network that are offering their setup. Picking one asks for its
     * code. A box for the address covers networks that block discovery.
     */
    void receiveSetup() {
        LinearLayout found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        TextView looking = dialogText(getString(R.string.setup_looking));
        EditText address = new EditText(this);
        address.setHint(R.string.setup_address_hint);
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        keyboardOnSelect(address);
        LinearLayout form = dialogForm(dialogText(getString(R.string.setup_get_help)), looking, found, address);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.setup_get_title).setView(form)
                .setPositiveButton(R.string.setup_use_address, null)
                .setNegativeButton(R.string.cancel, null).create();
        java.util.Map<String, View> rows = new java.util.HashMap<>();
        dialog.setOnDismissListener(d -> transfer().stopFinding());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String typed = address.getText().toString().trim();
            int colon = typed.lastIndexOf(':');
            if (colon <= 0) { address.setError(getString(R.string.setup_address_hint)); return; }
            try {
                int port = Integer.parseInt(typed.substring(colon + 1));
                dialog.dismiss();
                askCode(typed, typed.substring(0, colon), port);
            } catch (NumberFormatException e) { address.setError(getString(R.string.setup_address_hint)); }
        }));
        dialog.show();
        transfer().find(new SetupTransfer.Finding() {
            @Override public void found(String name, String host, int port) {
                main.post(() -> {
                    if (!dialog.isShowing() || rows.containsKey(name)) return;
                    looking.setText(R.string.setup_pick);
                    TextView row = ui.pill(name, 0x33FFFFFF, Ui.TEXT);
                    row.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
                    row.setOnClickListener(v -> { dialog.dismiss(); askCode(name, host, port); });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.topMargin = ui.dp(8);
                    found.addView(row, lp);
                    rows.put(name, row);
                    if (found.getChildCount() == 1) row.requestFocus();
                });
            }
            @Override public void lost(String name) {
                main.post(() -> { View row = rows.remove(name); if (row != null) found.removeView(row); });
            }
        });
    }

    private void askCode(String name, String host, int port) {
        EditText code = new EditText(this);
        code.setHint(R.string.setup_code_hint);
        code.setSingleLine(true);
        code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(24);
        code.setLetterSpacing(0.2f);
        keyboardOnSelect(code);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(name).setView(dialogForm(dialogText(getString(R.string.setup_code_help)), code))
                .setPositiveButton(R.string.setup_get, null)
                .setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(d -> {
            code.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String typed = code.getText().toString().replaceAll("\\D", "");
                if (typed.length() != 6) { code.setError(getString(R.string.setup_code_hint)); return; }
                v.setEnabled(false);
                io.execute(() -> {
                    try {
                        String json = SetupTransfer.fetch(host, port, typed);
                        main.post(() -> { dialog.dismiss(); applySetup(json); });
                    } catch (SetupTransfer.WrongCode e) {
                        main.post(() -> { v.setEnabled(true); code.setError(getString(R.string.setup_wrong_code)); });
                    } catch (IOException | RuntimeException e) {
                        main.post(() -> { dialog.dismiss(); toast(getString(R.string.setup_failed, String.valueOf(e.getMessage()))); });
                    }
                });
            });
        });
        dialog.show();
    }

    private TextView dialogText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Ui.TEXT_DIM);
        t.setTextSize(15);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, ui.dp(6), 0, ui.dp(6));
        return t;
    }

    private TextView dialogCode(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Ui.ACCENT);
        t.setTextSize(40);
        t.setLetterSpacing(0.15f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(14), 0, ui.dp(10));
        return t;
    }

    private LinearLayout dialogForm(View... parts) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        for (View part : parts) form.addView(part, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        LinearLayout holder = new LinearLayout(this);
        holder.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        return holder;
    }

    @Override
    public boolean onSearchRequested() { startVoiceSearch(); return true; }

    /** Voice search, also reached with a remote's search or mic key. */
    void startVoiceSearch() {
        Intent speak = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try { startActivityForResult(speak, REQUEST_VOICE); }
        catch (android.content.ActivityNotFoundException | SecurityException e) { toast(getString(R.string.voice_missing)); }
    }

    private void goHome() {
        boolean caseUp = overlay instanceof CaseScreen && ((CaseScreen) overlay).forTv();
        if (caseUp) return; // with a TV attached, Home on the phone is the case screen
        while (overlay != null) closeOverlay();
        if (tab != TAB_FOR_YOU) { tab = TAB_FOR_YOU; render(); }
        else if (scroll != null) { Motion.glideY(scroll, 0); setTopBarVisible(true); }
    }

    // ====================================================================== window

    private void setupWindow() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        else legacyImmersiveFlags(false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        home = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(images::flush);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int t, b, l, r;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                t = i.top; b = i.bottom; l = i.left; r = i.right;
            } else {
                t = in.getSystemWindowInsetTop(); b = in.getSystemWindowInsetBottom();
                l = in.getSystemWindowInsetLeft(); r = in.getSystemWindowInsetRight();
            }
            // Typing: the keyboard brings the navigation bar back, and on older Android its height
            // arrives as an inset. Rebuilding now would replace the text box and close the keyboard,
            // so the new insets wait until the box is let go.
            boolean typing = focused() instanceof EditText && (Build.VERSION.SDK_INT < 30 || in.isVisible(WindowInsets.Type.ime()));
            if (typing) {
                if (t != insetTop || b != insetBottom || l != insetLeft || r != insetRight) insetsWaiting = true;
                return in;
            }
            if (insetsWaiting || t != insetTop || b != insetBottom || l != insetLeft || r != insetRight) {
                insetsWaiting = false;
                insetTop = t; insetBottom = b; insetLeft = l; insetRight = r;
                // Rebuild after the insets pass finishes rather than mutating the tree mid-dispatch.
                main.post(() -> { render(); for (Overlay page : pages) page.rebuild(); });
            }
            return in;
        });
    }

    /** Hides status and navigation bars (the side buttons in landscape) when enabled. */
    void applySystemBars() {
        boolean hide = prefs.hideSystemBars();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            int bars = WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars();
            if (hide) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(bars);
            } else {
                c.show(bars);
            }
        } else {
            legacyImmersiveFlags(hide);
        }
    }

    @SuppressWarnings("deprecation") // pre-Android 11 path
    private void legacyImmersiveFlags(boolean hide) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (hide) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    // ===================================================================== content

    void refreshContent() {
        catalog.refresh((error, usedCacheOnly) -> {
            if (usedCacheOnly) toast(getString(R.string.offline_cached));
            renderPreservingScroll();
        });
        refreshPlex();
        refreshTrakt();
        refreshMood();
        loadAddonRows();
        refreshTraktPaused();
        if (tab == TAB_LIBRARY) refreshHistory();
    }

    // ==================================================================== updates

    /**
     * Checks the published manifest; a newer build is downloaded and verified in
     * the background so the "Update ready" card installs with a single tap.
     */
    void checkForUpdate(boolean manual) {
        Runnable work = () -> {
            try {
                Updater.Release r = updater.check();
                if (r == null) { if (manual) main.post(() -> toast(getString(R.string.update_current, BuildConfig.VERSION_NAME))); return; }
                if (manual) main.post(() -> toast(getString(R.string.update_downloading, r.versionName)));
                java.io.File apk = updater.download(r);
                main.post(() -> {
                    readyUpdate = r;
                    readyApk = apk;
                    renderPreservingScroll();
                    // Asked for from Settings: show it there too, and go straight on to installing.
                    if (overlay instanceof SettingsScreen) overlay.rebuild();
                    if (manual) installUpdate();
                });
            } catch (IOException | JSONException e) {
                if (manual) { final String reason = CatalogRepository.describe(e); main.post(() -> toast(getString(R.string.update_failed, reason))); }
            }
        };
        // A check the user asked for answers now; it doesn't queue behind the home screen's loading.
        if (manual) new Thread(work, "phonetv-update").start(); else io.execute(work);
    }

    void installUpdate() {
        if (readyApk == null) return;
        if (!updater.canInstall()) {
            // One-time permission: Android asks the user to allow this app to install updates.
            // Fire TV has no per-app screen for this; it lives under My Fire TV > Developer options.
            boolean opened = Build.VERSION.SDK_INT >= 26
                    && startSafely(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            toast(getString(opened ? R.string.update_allow : isFireTv() ? R.string.update_allow_tv : R.string.update_allow_android_tv));
            return;
        }
        java.io.File apk = readyApk;
        io.execute(() -> {
            try { updater.install(this, apk); }
            catch (IOException | RuntimeException e) {
                final String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                main.post(() -> toast(getString(R.string.update_failed, reason)));
            }
        });
    }

    @SuppressWarnings("deprecation") // getParcelableExtra(String) is the API 26-32 path
    private void onInstallStatus(Intent intent) {
        int status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startSafely(confirm);
        } else if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
            toast(getString(R.string.update_failed, msg != null ? msg : "status " + status));
        }
    }

    private View updateCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        c.setBackground(ui.round(0xFF1F2B3D, 14));
        c.addView(ui.label(getString(R.string.update_ready, readyUpdate.versionName), 14, Ui.TEXT, Ui.MEDIUM));
        if (!readyUpdate.notes.isEmpty()) {
            TextView notes = ui.label(readyUpdate.notes, 12, Ui.TEXT_DIM, null);
            notes.setPadding(0, ui.dp(4), 0, 0);
            c.addView(notes);
        }
        TextView install = ui.primary(getString(R.string.update_install));
        install.setOnClickListener(v -> installUpdate());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.topMargin = ui.dp(10);
        c.addView(install, ilp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(gutter(), ui.dp(18), insetRight + ui.dp(20), 0);
        c.setLayoutParams(lp);
        return c;
    }

    /** Loads titles for the user's typed hero words. No-op when the hero is on trending. */
    void refreshMood() {
        String words = prefs.heroMood();
        if (words.isEmpty()) {
            if (!moodItems.isEmpty()) { moodItems = Collections.emptyList(); heroIndex = 0; renderPreservingScroll(); }
            return;
        }
        moodLoading = true;
        io.execute(() -> {
            List<MediaItem> found;
            String error = null;
            try { found = heroMood.resolve(words, 16); }
            catch (Http.StatusException e) {
                found = Collections.emptyList();
                // Wikidata throttles busy periods with 429; say so plainly rather than show a status code.
                error = e.code == 429 ? getString(R.string.mood_busy) : CatalogRepository.describe(e);
            }
            catch (IOException | JSONException e) { found = Collections.emptyList(); error = CatalogRepository.describe(e); }
            final List<MediaItem> f = found;
            final String err = error;
            main.post(() -> {
                moodLoading = false;
                moodItems = f;
                heroIndex = 0;
                if (err != null) toast(getString(R.string.mood_failed, err));
                else if (f.isEmpty()) toast(getString(R.string.mood_empty, words));
                renderPreservingScroll();
            });
        });
    }

    private long plexRefreshedAt;

    void refreshPlex() {
        if (!plex.isLinked()) return;
        plexRefreshedAt = System.currentTimeMillis();
        io.execute(() -> {
            List<MediaItem> cont, recent;
            try {
                cont = plex.continueWatching();
                recent = plex.recentlyAdded();
            } catch (IOException | JSONException e) {
                final String reason = CatalogRepository.describe(e);
                main.post(() -> { plexError = reason; renderPreservingScroll(); });
                return;
            }
            final List<MediaItem> c = cont, r = recent;
            main.post(() -> { plexContinue = c; plexRecent = r; plexError = null; renderPreservingScroll(); });
        });
    }

    void disconnectPlex() {
        prefs.clearPlex();
        prefs.clearPlexPendingPin();
        plexContinue = Collections.emptyList();
        plexRecent = Collections.emptyList();
        plexError = null;
        renderPreservingScroll();
    }

    /** Opt-in rows from public Trakt lists; a no-op unless the user configured them. */
    void refreshTrakt() {
        if (!trakt.isConfigured()) {
            if (!traktRows.isEmpty() || !traktErrors.isEmpty()) {
                traktRows = Collections.emptyList(); traktErrors = Collections.emptyList(); renderPreservingScroll();
            }
            return;
        }
        io.execute(() -> {
            List<String> errors = new ArrayList<>();
            List<TraktClient.TraktRow> rows = trakt.fetchAll(errors);
            main.post(() -> { traktRows = rows; traktErrors = errors; renderPreservingScroll(); });
        });
    }

    boolean land() { return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; }
    int gutter() { return insetLeft + ui.dp(land() ? 40 : 20); }
    DisplayMetrics metrics() { return getResources().getDisplayMetrics(); }

    // ====================================================================== render

    void renderPreservingScroll() {
        int y = scroll == null ? 0 : scroll.getScrollY();
        // Don't yank a remote user's focus (and the scroll position) back to the hero mid-browse.
        autoFocusHero = y == 0;
        render();
        autoFocusHero = true;
        if (y > 0) scroll.post(() -> scroll.scrollTo(0, y));
    }

    void render() {
        if (root == null) return;
        main.removeCallbacks(heroAdvance);
        images.clearDeferred(home);
        View had = focused();
        Object key = had == null ? null : had.getTag(R.id.focus_key);
        restoreFocusKey = key instanceof String ? (String) key : null;
        focusTargets.clear();
        rowSeq = 0;
        heroHeightPx = 0;
        heroBackdropWidthPx = 0;
        heroWatchId = 0;
        heroFrame = null;
        heroArt = null;
        selectedChipId = View.generateViewId();
        home.removeAllViews();
        topBarHidden = false;
        heroOnScreen = true;

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false); // focus belongs to the items, never the page itself
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ViewGroup.LayoutParams(-1, -2));
        home.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (y > oldY + 6 && y > ui.dp(48)) setTopBarVisible(false);
            else if (y < oldY - 6 || y < ui.dp(16)) setTopBarVisible(true);
            boolean was = heroOnScreen;
            heroOnScreen = y < metrics().heightPixels / 2;
            if (!heroOnScreen) stopTrailer();
            else if (!was) scheduleTrailer();
            images.flush();
        });

        if (tab == TAB_APPS) {
            column.setPadding(0, insetTop + ui.dp(62), 0, insetBottom + ui.dp(24));
            buildAppsTab(column);
        } else if (tab == TAB_LIBRARY) {
            column.setPadding(0, insetTop + ui.dp(54), 0, insetBottom + ui.dp(24));
            buildLibraryTab(column);
        } else if (tab == TAB_DISCOVER) {
            column.setPadding(0, insetTop + ui.dp(62), 0, insetBottom + ui.dp(24));
            buildDiscoverTab(column);
        } else {
            buildMediaTab(column);
        }
        topBar = buildTopBar();
        home.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        View target = restoreFocusKey == null ? null : focusTarget(restoreFocusKey);
        if (target != null) target.post(target::requestFocus);
    }

    private void buildMediaTab(LinearLayout column) {
        List<MediaItem> movies = catalog.get(CatalogRepository.MOVIES);
        List<MediaItem> shows = catalog.get(CatalogRepository.SHOWS);
        List<MediaItem> rated = catalog.get(CatalogRepository.TOP_RATED);

        heroItems.clear();
        if (tab == TAB_FOR_YOU && !moodItems.isEmpty()) {
            // The user asked for something specific ("asian romance"): it owns the hero.
            heroItems.addAll(moodItems.subList(0, Math.min(8, moodItems.size())));
        } else if (tab == TAB_FOR_YOU) {
            // Lead with what the user is already watching, then interleave movies and shows.
            for (int i = 0; i < Math.min(2, plexContinue.size()); i++) heroItems.add(plexContinue.get(i));
            for (int i = 0; i < 4; i++) {
                if (i < movies.size()) heroItems.add(movies.get(i));
                if (i < shows.size()) heroItems.add(shows.get(i));
            }
        } else {
            List<MediaItem> src = tab == TAB_MOVIES ? movies : shows;
            heroItems.addAll(src.subList(0, Math.min(7, src.size())));
        }
        if (heroIndex >= heroItems.size()) heroIndex = 0;

        if (prefs.showHero()) column.addView(buildHero());
        else column.setPadding(0, insetTop + ui.dp(58), 0, 0); // no banner: start below the top menu

        if (tab == TAB_FOR_YOU) {
            if (readyUpdate != null) column.addView(updateCard());
            column.addView(rowTitle(getString(R.string.row_your_apps)));
            column.addView(buildFavoritesRow());
            if (moodItems.size() > 8) posterRow(column, getString(R.string.row_more_mood, prefs.heroMood()), moodItems.subList(8, moodItems.size()));
            // The rows, in the order chosen in Settings › Home screen.
            for (String key : prefs.rowOrder(ROW_ORDER)) {
                if (!prefs.rowShown(key)) continue;
                switch (key) {
                    case ROW_CONTINUE: continueRow(column); break;
                    case ROW_LIBRARY: posterRow(column, getString(R.string.row_library), library.titles(Library.SORT_RECENT, Library.ONLY_ALL)); break;
                    case ROW_TOP_PICKS: if (!catalog.isEmpty()) backdropRow(column, getString(R.string.row_top_picks), slice(movies, 4, 20)); break;
                    case ROW_PLEX_CONTINUE: if (plex.isLinked()) backdropRow(column, getString(R.string.row_plex_continue), plexContinue); break;
                    case ROW_PLEX_RECENT: if (plex.isLinked()) posterRow(column, getString(R.string.row_plex_recent), plexRecent); break;
                    case ROW_TRAKT: for (TraktClient.TraktRow row : traktRows) posterRow(column, row.title, row.items); break;
                    case ROW_SHOWS: if (!catalog.isEmpty()) posterRow(column, getString(R.string.row_popular_shows), slice(shows, 4, 40)); break;
                    case ROW_MOVIES: if (!catalog.isEmpty()) posterRow(column, getString(R.string.row_popular_movies), slice(movies, 20, 48)); break;
                    case ROW_TOP_RATED: if (!catalog.isEmpty()) posterRow(column, getString(R.string.row_top_rated), rated); break;
                    default: break;
                }
            }
            if (plex.isLinked()) {
                if (plexError != null) column.addView(noticeCard(getString(R.string.plex_status_title),
                        getString(R.string.plex_status_body, plexError), this::refreshPlex));
            } else if (apps.byPackage(PlexClient.PACKAGE) != null && !prefs.plexPromptDismissed()) {
                column.addView(plexPrompt());
            }
            if (!traktErrors.isEmpty()) column.addView(noticeCard(getString(R.string.trakt_status_title),
                    TextUtils.join("\n", traktErrors), this::refreshTrakt));
        }

        if (catalog.isEmpty()) {
            column.addView(statusCard());
        } else if (tab == TAB_FOR_YOU) {
            // rows are placed above, in the user's order
        } else if (tab == TAB_MOVIES) {
            backdropRow(column, getString(R.string.row_trending_movies), slice(movies, 7, 20));
            posterRow(column, getString(R.string.row_popular_movies), slice(movies, 20, 48));
            posterRow(column, getString(R.string.row_top_rated), rated);
            addonRowsFor(column, "movie");
        } else {
            backdropRow(column, getString(R.string.row_trending_shows), slice(shows, 7, 20));
            posterRow(column, getString(R.string.row_popular_shows), slice(shows, 20, 50));
            posterRow(column, getString(R.string.row_top_rated_shows), catalog.get(CatalogRepository.TOP_RATED_SHOWS));
            addonRowsFor(column, "series");
        }
        column.addView(footer());

        if (!heroItems.isEmpty()) { showHero(heroIndex); restartHeroTimer(); }
    }

    private static List<MediaItem> slice(List<MediaItem> l, int from, int to) {
        return from >= l.size() ? l : l.subList(from, Math.min(to, l.size()));
    }

    // ============================================================ continue watching

    /**
     * What was being watched in Phone TV's player: an unfinished movie or episode resumes where
     * it stopped; a finished episode offers the next one. Trakt's paused items (from any app
     * that reports to Trakt) join in when the user is signed in to Trakt.
     */
    private void continueRow(LinearLayout column) {
        List<MediaItem> items = new ArrayList<>();
        Map<String, Runnable> actions = new HashMap<>();
        for (Progress.Entry e : Progress.of(this).recent()) {
            MediaItem m = e.item();
            if (e.finished()) {
                if (e.episodeId.isEmpty()) continue;
                CatalogRepository.Details d = catalog.cachedDetails(m);
                CatalogRepository.Episode next = d == null ? null : nextEpisode(d, e.episodeId);
                if (d != null && next == null) continue; // the show is done
                m.subtitle = next == null ? getString(R.string.continue_next) : getString(R.string.continue_next_episode, next.season, next.number);
                m.progressPercent = 0;
                actions.put(m.id, () -> openNextEpisode(m, e.episodeId));
            } else {
                m.subtitle = (e.episodeLabel.isEmpty() ? "" : e.episodeLabel + "  ·  ") + getString(R.string.continue_left, PlayerActivity.clock(e.durationMs - e.positionMs));
                m.progressPercent = Math.max(1, Math.round(e.fraction() * 100));
                actions.put(m.id, () -> resume(e));
            }
            items.add(m);
        }
        for (MediaItem m : traktPaused) {
            if (actions.containsKey(m.id)) continue;
            items.add(m);
            actions.put(m.id, () -> showDetails(m));
        }
        if (items.isEmpty()) return;
        column.addView(rowTitle(getString(R.string.row_continue)));
        LinearLayout row = rowContainer(column);
        int w = cardWidth(land() ? 280 : 236), h = w * 9 / 16;
        for (MediaItem m : items) {
            FrameLayout card = card();
            wideArt(card, m, w, true);
            if (m.progressPercent > 0) addProgressBar(card, w, m.progressPercent);
            if (m.subtitle != null && !m.subtitle.isEmpty()) {
                // "S2 E4 · 42:10 left" or "Next: S2 E5", in a small tag on the artwork.
                TextView tag = ui.label(m.subtitle, 11, Ui.TEXT, Ui.MEDIUM);
                tag.setMaxLines(1);
                tag.setEllipsize(TextUtils.TruncateAt.END);
                tag.setPadding(ui.dp(7), ui.dp(3), ui.dp(7), ui.dp(3));
                tag.setBackground(ui.round(0xB3000000, 8));
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                tlp.setMargins(ui.dp(8), ui.dp(8), ui.dp(8), 0);
                card.addView(tag, tlp);
            }
            focusKey(card, "row" + rowSeq + ":" + m.id);
            Runnable go = actions.get(m.id);
            card.setOnClickListener(x -> go.run());
            card.setOnLongClickListener(x -> { continueOptions(m); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = ui.dp(10);
            row.addView(card, lp);
        }
    }

    /** Resume straight into the player when the saved link still works; otherwise pick a source again. */
    private void resume(Progress.Entry e) {
        MediaItem m = e.item();
        String episode = e.episodeId.isEmpty() ? null : e.episodeId;
        if (e.url.isEmpty()) { showStreams(m, episode); return; }
        String next = null, nextLabel = null;
        CatalogRepository.Details d = catalog.cachedDetails(m);
        if (episode != null && d != null) {
            CatalogRepository.Episode after = nextEpisode(d, episode);
            if (after != null) { next = after.id; nextLabel = Library.episodeLabel(after.season, after.number); }
        }
        startPlayer(m, episode, e.episodeLabel, e.url, e.headers, e.addon, e.positionMs, next, nextLabel);
    }

    private void openNextEpisode(MediaItem m, String lastEpisode) {
        io.execute(() -> {
            CatalogRepository.Details d;
            try { d = catalog.details(m); } catch (IOException | JSONException | RuntimeException ex) { d = null; }
            final CatalogRepository.Details found = d;
            main.post(() -> {
                CatalogRepository.Episode next = found == null ? null : nextEpisode(found, lastEpisode);
                if (next == null) { showDetails(m); return; }
                if (Prefs.PLAY_HERE.equals(prefs.playWith()) || Prefs.PLAY_ASK.equals(prefs.playWith())) showStreams(m, next.id);
                else openWith(prefs.playWith(), m, next.id);
            });
        });
    }

    private void refreshTraktPaused() {
        if (!trakt.signedIn()) { traktPaused = Collections.emptyList(); return; }
        io.execute(() -> {
            List<MediaItem> found;
            try { found = trakt.paused(); } catch (IOException | JSONException | RuntimeException e) { return; }
            main.post(() -> { traktPaused = found; if (tab == TAB_FOR_YOU) renderPreservingScroll(); });
        });
    }

    private void continueOptions(MediaItem m) {
        showNotice(m.title, m.subtitle, new String[]{getString(R.string.continue_details), getString(R.string.continue_remove)},
                new Runnable[]{() -> showDetails(m), () -> { Progress.of(this).remove(m.id); renderPreservingScroll(); }});
    }

    // ================================================================ add-on catalogs

    private final Map<String, List<MediaItem>> addonRows = new java.util.LinkedHashMap<>(); // "type|Add-on · Catalog" → titles
    private boolean addonRowsLoading;

    private void loadAddonRows() {
        if (addonRowsLoading) return;
        addonRowsLoading = true;
        io.execute(() -> {
            Map<String, List<MediaItem>> found = new java.util.LinkedHashMap<>();
            for (Addons.Addon a : Addons.of(this).all()) {
                if (!a.enabled || !a.resources.contains("catalog")) continue;
                for (Addons.Catalog c : a.catalogs) {
                    if (c.needsInput || !("movie".equals(c.type) || "series".equals(c.type))) continue;
                    try {
                        List<MediaItem> items = Addons.of(this).catalog(a, c);
                        if (!items.isEmpty()) found.put(c.type + "|" + c.name + " · " + a.name, items);
                    } catch (IOException | JSONException ignored) { /* skip this catalog */ }
                }
            }
            main.post(() -> {
                addonRowsLoading = false;
                addonRows.clear();
                addonRows.putAll(found);
                if (tab == TAB_MOVIES || tab == TAB_SHOWS) renderPreservingScroll();
            });
        });
    }

    private void addonRowsFor(LinearLayout column, String type) {
        for (Map.Entry<String, List<MediaItem>> e : addonRows.entrySet()) {
            if (!e.getKey().startsWith(type + "|")) continue;
            posterRow(column, e.getKey().substring(type.length() + 1), e.getValue());
        }
    }

    private void restartHeroTimer() {
        main.removeCallbacks(heroAdvance);
        if (heroItems.size() > 1) main.postDelayed(heroAdvance, HERO_INTERVAL_MS);
    }

    private void setTopBarVisible(boolean visible) {
        if (topBar == null || visible == !topBarHidden) return;
        topBarHidden = !visible;
        topBar.animate().translationY(visible ? 0 : -topBar.getHeight()).alpha(visible ? 1f : 0f).setDuration(200).start();
    }

    // ===================================================================== top bar

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(gutter() - ui.dp(4), insetTop + ui.dp(10), insetRight + ui.dp(land() ? 36 : 16), ui.dp(12));
        bar.setBackground(Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0xCC000000, 0x00000000));

        bar.addView(focusKey(roundIcon(android.R.drawable.ic_menu_search, v -> { tab = TAB_DISCOVER; render(); }), "top:search"),
                new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));

        HorizontalScrollView tabsScroller = new HorizontalScrollView(this);
        tabsScroller.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(ui.dp(8), 0, 0, 0);
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int index = i;
            boolean selected = i == tab;
            // Google TV style: tabs are plain text (current one brighter); only the tab under
            // the remote or finger gets a light pill with dark text.
            TextView chip = ui.label(getString(TAB_LABELS[i]), 13, Ui.TEXT, selected ? Ui.BOLD : Ui.MEDIUM);
            int[][] states = {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}};
            chip.setTextColor(new ColorStateList(states, new int[]{Ui.ON_LIGHT, Ui.ON_LIGHT, selected ? Ui.TEXT : Ui.MUTED}));
            android.graphics.drawable.StateListDrawable pill = new android.graphics.drawable.StateListDrawable();
            pill.addState(states[0], ui.round(0xE6FFFFFF, 16));
            pill.addState(states[1], ui.round(0xE6FFFFFF, 16));
            chip.setPadding(ui.dp(13), ui.dp(6), ui.dp(13), ui.dp(6));
            chip.setBackground(pill);
            chip.setRevealOnFocusHint(false);
            chip.setFocusable(true);
            focusKey(chip, "tab:" + i);
            if (selected) chip.setId(selectedChipId);
            if (heroWatchId != 0) chip.setNextFocusDownId(heroWatchId);
            chip.setOnFocusChangeListener((v, has) -> { if (has) { setTopBarVisible(true); setHeroCollapsed(true); } });
            chip.setOnClickListener(v -> {
                if (tab == index) return;
                tab = index;
                heroIndex = 0;
                render();
                if (tab == TAB_LIBRARY) refreshHistory();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ui.dp(2);
            tabs.addView(chip, lp);
        }
        tabsScroller.addView(tabs);
        bar.addView(tabsScroller, new LinearLayout.LayoutParams(0, -2, 1));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(Ui.TEXT);
        clock.setTypeface(Ui.MEDIUM);
        ui.text(clock, 14);
        clock.setPadding(ui.dp(8), 0, ui.dp(10), 0);
        bar.addView(clock);

        if (Profiles.several(this)) {
            TextView me = ProfileScreen.avatar(this, Profiles.current(this), 30, 13);
            me.setOnClickListener(v -> showProfiles());
            me.setOnFocusChangeListener((v, has) -> { Motion.scale(v, has ? 1.12f : 1f); if (has) { setTopBarVisible(true); setHeroCollapsed(true); } });
            if (heroWatchId != 0) me.setNextFocusDownId(heroWatchId);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30));
            mlp.rightMargin = ui.dp(8);
            bar.addView(focusKey(me, "top:profile"), mlp);
        }
        bar.addView(focusKey(roundIcon(android.R.drawable.ic_menu_preferences, v -> showSettings()), "top:settings"),
                new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        return bar;
    }

    private ImageView roundIcon(int res, View.OnClickListener onClick) {
        ImageView iv = new Art(this);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
        iv.setPadding(ui.dp(7), ui.dp(7), ui.dp(7), ui.dp(7));
        iv.setBackground(ui.ripple(ui.round(0x26FFFFFF, 18)));
        iv.setFocusable(true);
        ui.focusRing(iv, 18);
        if (heroWatchId != 0) iv.setNextFocusDownId(heroWatchId);
        iv.setOnFocusChangeListener((v, has) -> { if (has) { setTopBarVisible(true); setHeroCollapsed(true); } });
        iv.setOnClickListener(onClick);
        return iv;
    }

    // ======================================================================== hero

    // The touch listener only feeds a GestureDetector; taps still route through performClick().
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private View buildHero() {
        DisplayMetrics dm = metrics();
        boolean land = land();
        // A little over half the screen, so "Your apps" and the first row sit above the fold.
        int layout = prefs.heroLayout();
        boolean full = land && layout == Prefs.HERO_FULL;
        boolean detailsRight = land && layout == Prefs.HERO_DETAILS_RIGHT;
        int height = land ? (int) (dm.heightPixels * (full ? 0.70f : 0.58f)) : Math.max(ui.dp(330), (int) (dm.widthPixels * 0.82f));
        heroHeightPx = heroFullHeightPx = height;
        heroCollapsed = false;
        boolean sideArt = land && !full;
        FrameLayout hero = new FrameLayout(this);
        heroFrame = hero;
        hero.setLayoutParams(new LinearLayout.LayoutParams(-1, height));

        // Artwork always keeps its top edge (faces, title treatments) and crops from the bottom.
        // Two layers, so a new title fades in over the old one instead of blinking.
        float keep = sideArt ? (detailsRight ? 0f : 1f) : 0.5f;
        stopTrailer();
        FrameLayout art = new FrameLayout(this);
        heroArtBox = art;
        art.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        art.setBackground(Ui.gradient(GradientDrawable.Orientation.TL_BR, 0xFF2F3D56, 0xFF1D2331, 0xFF28223B));
        heroArt = new ImageView[]{new Ui.TopCropImageView(this, keep), new Ui.TopCropImageView(this, keep)};
        heroArtFront = 0;
        for (ImageView layer : heroArt) art.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        if (sideArt) {
            // Landscape: artwork fills the side opposite the details and its inner edge
            // dissolves into the page, so the picture has no hard border (see Settings for the side).
            int w = (int) (dm.widthPixels * HERO_ART_WIDTH);
            heroBackdropWidthPx = w;
            hero.addView(art, new FrameLayout.LayoutParams(w, -1, detailsRight ? Gravity.START : Gravity.END));
            int fadeW = (int) (w * 0.48f);
            View fade = new View(this);
            fade.setBackground(detailsRight
                    ? Ui.fade(true, Ui.BG, new float[]{0f, 0.24f, 0.54f, 0.78f, 1f}, new float[]{0f, 0.16f, 0.56f, 0.86f, 1f})
                    : Ui.fade(true, Ui.BG, new float[]{0f, 0.22f, 0.46f, 0.76f, 1f}, new float[]{1f, 0.86f, 0.56f, 0.16f, 0f}));
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(fadeW, -1, Gravity.START);
            flp.leftMargin = detailsRight ? w - fadeW : dm.widthPixels - w;
            hero.addView(fade, flp);
        } else {
            // Portrait, or the full-width banner option: artwork fills the banner.
            hero.addView(art, new FrameLayout.LayoutParams(-1, -1));
            if (full) {
                View left = new View(this); // darken the left so the details stay readable
                left.setBackground(Ui.gradient(GradientDrawable.Orientation.LEFT_RIGHT, Ui.bg(0xF0), Ui.bg(0x80), Ui.bg(0)));
                hero.addView(left, new FrameLayout.LayoutParams((int) (dm.widthPixels * 0.65f), -1));
            }
        }

        // Scrims: top for the menu, bottom for the text block and the hand-off to the rows.
        View top = new View(this);
        top.setBackground(Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, Ui.bg(0xB3), Ui.bg(0x40), Ui.bg(0)));
        hero.addView(top, new FrameLayout.LayoutParams(-1, (int) (height * 0.35f)));
        View bottom = new View(this);
        bottom.setBackground(land
                ? Ui.fade(false, Ui.BG, new float[]{0f, 0.40f, 0.75f, 1f}, new float[]{0f, 0.22f, 0.62f, 1f})
                : Ui.fade(false, Ui.BG, new float[]{0f, 0.30f, 0.55f, 0.80f, 1f}, new float[]{0f, 0f, 0.40f, 0.90f, 1f}));
        hero.addView(bottom, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM | Gravity.START);
        if (detailsRight) info.setPadding(ui.dp(28), 0, insetRight + ui.dp(40), ui.dp(18));
        else info.setPadding(gutter(), 0, ui.dp(20), ui.dp(land ? 18 : 16));
        hero.addView(info, new FrameLayout.LayoutParams(land ? (int) (dm.widthPixels * 0.44f) : -1, -2,
                Gravity.BOTTOM | (detailsRight ? Gravity.END : Gravity.START)));

        heroLogo = new Ui.LogoView(this);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                (int) (dm.widthPixels * (land ? 0.26f : 0.55f)), ui.dp(land ? 56 : 68));
        logoLp.bottomMargin = ui.dp(8);
        info.addView(heroLogo, logoLp);

        heroTitle = ui.label("", 26, Ui.TEXT, Ui.BOLD);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(heroTitle);

        heroMeta = ui.label("", 12, 0xFFDADCE0, Ui.MEDIUM);
        heroMeta.setPadding(0, ui.dp(3), 0, ui.dp(5));
        info.addView(heroMeta);

        heroSummary = ui.label("", land ? 15 : 14, Ui.TEXT_DIM, null);
        heroSummary.setLineSpacing(0, 1.15f);
        // A phone on its side is wide but short: one line less keeps the title clear of the top menu.
        heroSummary.setMaxLines(land && dm.heightPixels >= ui.dp(560) ? 3 : 2);
        heroSummary.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(heroSummary);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, ui.dp(14), 0, 0);
        TextView watch = ui.primary(getString(R.string.hero_watch));
        watch.setOnClickListener(v -> { MediaItem m = currentHero(); if (m != null) play(m); });
        buttons.addView(watch);
        TextView details = ui.pill(getString(R.string.hero_details), 0x40FFFFFF, Ui.TEXT);
        details.setOnClickListener(v -> { MediaItem m = currentHero(); if (m != null) showDetails(m); });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = ui.dp(8);
        buttons.addView(details, dlp);
        String mood = prefs.heroMood();
        TextView tune = ui.pill(moodLoading ? getString(R.string.mood_loading)
                : mood.isEmpty() ? getString(R.string.hero_tune) : "✎  " + mood, 0x26FFFFFF, Ui.TEXT);
        tune.setOnClickListener(v -> tuneHero());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = ui.dp(8);
        buttons.addView(tune, tlp);
        info.addView(buttons);
        // Focus plumbing: names for focus restore, an explicit Up path to the tabs, and
        // focusing any banner button brings the page (and the top menu) back to the top.
        heroWatchId = View.generateViewId();
        watch.setId(heroWatchId);
        focusKey(watch, "hero:watch");
        focusKey(details, "hero:details");
        focusKey(tune, "hero:tune");
        for (View b : new View[]{watch, details, tune}) b.setNextFocusUpId(selectedChipId);
        if (!heroItems.isEmpty() && restoreFocusKey == null && autoFocusHero && (tvDevice || !root.isInTouchMode())) watch.post(watch::requestFocus);

        // Dots with previous/next arrows for mouse users (BlueStacks, Chromebooks).
        LinearLayout pager = new LinearLayout(this);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        pager.setPadding(0, ui.dp(10), 0, 0);
        pager.addView(focusKey(pagerArrow("‹", -1), "hero:prev"), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        heroDots = new LinearLayout(this);
        heroDots.setGravity(Gravity.CENTER_VERTICAL);
        heroDots.setPadding(ui.dp(6), 0, ui.dp(1), 0);
        pager.addView(heroDots);
        pager.addView(focusKey(pagerArrow("›", 1), "hero:next"), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        if (heroItems.size() < 2) pager.setVisibility(View.GONE);
        info.addView(pager);
        heroButtons = buttons;
        heroPager = pager;

        if (heroItems.isEmpty()) {
            heroTitle.setText(R.string.app_name);
            heroMeta.setText(catalog.isFetching() ? getString(R.string.status_loading_title) : "");
            heroSummary.setText(R.string.hero_placeholder_desc);
            buttons.setVisibility(View.GONE);
            heroWatchId = 0; // nothing to focus in the banner yet
        }

        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (heroItems.size() < 2 || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                return stepHero(vx < 0 ? 1 : -1);
            }
            @Override public boolean onSingleTapUp(MotionEvent e) { return hero.performClick(); }
        });
        hero.setOnClickListener(v -> { MediaItem m = currentHero(); if (m != null) showDetails(m); });
        hero.setOnTouchListener((v, e) -> gestures.onTouchEvent(e));
        return hero;
    }

    /**
     * While the top menu has focus the banner tucks away its buttons and shrinks a
     * little, so "Your apps" slides up, as on Google TV. Any focus in the page restores it.
     */
    private void setHeroCollapsed(boolean collapsed) {
        if (heroFrame == null || collapsed == heroCollapsed || tab == TAB_APPS) return;
        heroCollapsed = collapsed;
        // Moving down into the rows ends the trailer; coming back to the banner offers it again.
        if (collapsed) stopTrailer(); else scheduleTrailer();
        int vis = collapsed ? View.GONE : View.VISIBLE;
        if (!heroItems.isEmpty()) {
            if (heroButtons != null) heroButtons.setVisibility(vis);
            if (heroPager != null && heroItems.size() > 1) heroPager.setVisibility(vis);
        }
        heroHeightPx = collapsed ? (int) (heroFullHeightPx * 0.80f) : heroFullHeightPx;
        heroFrame.getLayoutParams().height = heroHeightPx;
        heroFrame.requestLayout();
    }

    private TextView pagerArrow(String glyph, int step) {
        TextView a = ui.label(glyph, 18, Ui.TEXT, Ui.BOLD);
        a.setGravity(Gravity.CENTER);
        a.setBackground(ui.ripple(ui.round(0x26FFFFFF, 14)));
        a.setFocusable(true);
        ui.focusRing(a, 14);
        a.setContentDescription(step < 0 ? "Previous" : "Next");
        a.setOnClickListener(v -> stepHero(step));
        return a;
    }

    /** Moves the carousel by one and holds auto-advance off for a moment. */
    private boolean stepHero(int step) {
        int n = heroItems.size();
        if (n < 2) return false;
        showHero((heroIndex + step + n) % n);
        restartHeroTimer();
        return true;
    }

    private MediaItem currentHero() { return heroItems.isEmpty() ? null : heroItems.get(Math.min(heroIndex, heroItems.size() - 1)); }

    private void showHero(int index) {
        if (heroItems.isEmpty() || heroArt == null) return;
        heroIndex = index;
        MediaItem m = heroItems.get(index);
        heroTitle.setText(m.title);
        heroTitle.setVisibility(View.VISIBLE);
        heroLogo.setImageDrawable(null);
        heroLogo.setVisibility(View.GONE);
        heroMeta.setText(meta(m));
        heroSummary.setText(m.summary);

        int screenW = metrics().widthPixels;
        // Portrait crops a 16:9 backdrop into a tall frame, so it needs ~1.75x screen width.
        int backdropW = heroBackdropWidthPx > 0 ? heroBackdropWidthPx : land() ? screenW : screenW * 7 / 4;
        showHeroArt(m.backdrop(backdropW), backdropW);
        final ImageView logo = heroLogo;
        final TextView title = heroTitle;
        images.load(heroLogo, m.logo(), screenW / 2, true, () -> { logo.setVisibility(View.VISIBLE); title.setVisibility(View.GONE); });

        heroDots.removeAllViews();
        for (int i = 0; i < heroItems.size(); i++) {
            View dot = new View(this);
            dot.setBackground(ui.round(i == index ? 0xFFFFFFFF : 0x66FFFFFF, 3));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(i == index ? ui.dp(16) : ui.dp(5), ui.dp(5));
            p.rightMargin = ui.dp(5);
            heroDots.addView(dot, p);
        }
        scheduleTrailer();
    }

    // ------------------------------------------------------------ banner trailer

    private static final long TRAILER_DELAY_MS = 3_000;
    private FrameLayout heroArtBox;
    private HeroTrailer trailer;
    private final Runnable trailerStart = this::startTrailer;

    /** Three seconds on a title, and its trailer takes over from the picture (when it has one and trailers are on). */
    private void scheduleTrailer() {
        stopTrailer();
        if (prefs.heroTrailer() != Prefs.TRAILER_OFF && !heroItems.isEmpty()) main.postDelayed(trailerStart, TRAILER_DELAY_MS);
    }

    void stopTrailer() {
        main.removeCallbacks(trailerStart);
        if (trailer != null) trailer.stop();
    }

    private void startTrailer() {
        if (!resumed || overlay != null || !heroOnScreen || heroCollapsed || heroArtBox == null || heroItems.isEmpty() || !hasWindowFocus()) return;
        if (tab != TAB_FOR_YOU && tab != TAB_MOVIES && tab != TAB_SHOWS) return;
        final MediaItem m = heroItems.get(Math.min(heroIndex, heroItems.size() - 1));
        final FrameLayout box = heroArtBox;
        if (trailer == null) trailer = new HeroTrailer(this);
        final HeroTrailer t = trailer;
        new Thread(() -> {
            final String yt = t.lookup(m);
            if (yt.isEmpty()) return; // no trailer: the picture stays
            main.post(() -> {
                // Still on the same title, same banner, and nothing has covered it meanwhile?
                if (box != heroArtBox || heroItems.isEmpty() || heroItems.get(Math.min(heroIndex, heroItems.size() - 1)) != m) return;
                if (!resumed || overlay != null || !heroOnScreen || heroCollapsed) return;
                main.removeCallbacks(heroAdvance); // hold this title while its trailer loads and plays
                t.play(box, m.id, yt, prefs.heroTrailer() == Prefs.TRAILER_MUTED, new HeroTrailer.Listener() {
                    @Override public void onPlaying() { main.removeCallbacks(heroAdvance); }
                    @Override public void onFinished(boolean played) {
                        if (played && heroItems.size() > 1 && heroOnScreen && overlay == null) showHero((heroIndex + 1) % heroItems.size());
                        restartHeroTimer();
                    }
                });
            });
        }, "phonetv-trailer").start();
    }

    /** Loads the next banner image into the hidden layer and cross-fades to it once it's ready. */
    private void showHeroArt(String url, int widthPx) {
        if (url == null) return;
        final ImageView[] layers = heroArt;
        final ImageView front = layers[heroArtFront], back = layers[1 - heroArtFront];
        if (front.getDrawable() == null) { images.load(front, url, widthPx, false, null); return; }
        images.load(back, url, widthPx, false, () -> {
            if (layers != heroArt) return; // the banner was rebuilt while this was loading
            back.animate().cancel();
            back.setAlpha(0f);
            back.bringToFront();
            back.animate().alpha(1f).setDuration(Motion.CROSSFADE).setInterpolator(Motion.STANDARD).start();
            heroArtFront = back == layers[0] ? 0 : 1;
        });
    }

    String meta(MediaItem m) {
        StringBuilder sb = new StringBuilder();
        if (!m.subtitle.isEmpty()) sb.append(m.subtitle);
        else sb.append(getString("episode".equals(m.type) ? R.string.meta_episode : m.isShow() ? R.string.meta_show : R.string.meta_movie));
        if (!m.year.isEmpty()) sb.append("  ·  ").append(m.year);
        if (!m.genres.isEmpty()) sb.append("  ·  ").append(m.genres);
        if (!m.rating.isEmpty()) sb.append("  ·  ★ ").append(m.rating);
        if (m.source == MediaItem.Source.PLEX) sb.append("  ·  Plex");
        return sb.toString();
    }

    // ================================================================ apps / tiles

    private View buildFavoritesRow() {
        LinearLayout row = rowContainer(null);
        View scroller = (View) row.getParent();
        int w = ui.dp(land() ? 168 : 150), h = w * 9 / 16;
        for (AppRepository.App a : apps.favorites()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = ui.dp(10);
            row.addView(appTile(a, h, true), lp);
        }
        boolean empty = apps.favoriteCount() == 0;
        FrameLayout add = new FrameLayout(this);
        add.setBackground(ui.round(0x26FFFFFF, ui.cardRadius));
        add.setForeground(ui.ripple(null));
        add.setClipToOutline(true);
        TextView plus = ui.label(empty ? getString(R.string.fav_add_tile) : "+", empty ? 13 : 24, Ui.TEXT, null);
        plus.setGravity(Gravity.CENTER);
        add.addView(plus, new FrameLayout.LayoutParams(-1, -1));
        ui.focusable(add);
        focusKey(add, "fav:+");
        if (heroWatchId != 0) add.setNextFocusUpId(heroWatchId);
        add.setOnClickListener(v -> { tab = TAB_APPS; render(); toast(getString(R.string.fav_add_hint)); });
        row.addView(add, new LinearLayout.LayoutParams(empty ? w * 3 / 2 : h, h));
        return scroller;
    }

    private View appTile(AppRepository.App app, int heightPx, boolean onShelf) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(ui.round(Ui.SURFACE, ui.cardRadius));
        tileArt.bind(tile, app, heightPx, prefs.tvStyleTiles());
        if (!onShelf && apps.isFavorite(app.pkg)) tile.addView(badge("★"), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        if (!onShelf && app.tvEdition) tile.addView(badge("TV"), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        tile.setClipToOutline(true);
        ui.focusable(tile);
        tile.setTag(app); // read by the remote's MENU key
        focusKey(tile, (onShelf ? "fav:" : "app:") + app.pkg);
        if (onShelf && heroWatchId != 0) tile.setNextFocusUpId(heroWatchId); // Up from the shelf always reaches the banner
        tile.setOnClickListener(v -> { if (!launch(app)) toast(getString(R.string.open_failed, app.label)); });
        tile.setOnLongClickListener(v -> { showAppMenu(app); return true; });
        return tile;
    }

    private TextView badge(String s) {
        TextView b = ui.label(s, 9, Ui.TEXT, Ui.BOLD);
        b.setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1));
        b.setBackground(ui.round(0x99000000, 6));
        return b;
    }

    // ===================================================================== discover

    private static final String[] GENRES = {"Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary",
            "Drama", "Family", "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western"};
    private static final int BY_POPULAR = 0, BY_RATING = 1, BY_YEAR = 2, BY_SERVICE = 3;
    /** Streaming services for TMDB's "where to watch" (needs the user's TMDB key). TMDB provider ids. */
    private static final String[] SERVICES = {"Netflix", "Prime Video", "Disney+", "Max", "Apple TV+", "Hulu", "Paramount+", "Peacock"};
    private static final int[] SERVICE_IDS = {8, 9, 337, 1899, 350, 15, 531, 386};
    private String discoverQuery = "";
    private boolean discoverShows;
    private int discoverBy = BY_POPULAR, discoverPick = -1; // genre, year or service index; -1 = all
    private final List<MediaItem> discovered = new ArrayList<>();
    private String discoverKey = "", discoverError;
    private String discoverLoadingKey; // the choice a page is being fetched for, if any
    private boolean discoverEnd;

    /**
     * Search and browse, Nuvio-style: a search box for titles and actors on top; under it,
     * chips to pick movies or shows, how to sort, and a genre, year or streaming service,
     * and a grid of what matches that keeps going as far as the catalog does.
     */
    private void buildDiscoverTab(LinearLayout column) {
        int g = gutter();
        EditText box = new EditText(this);
        box.setSingleLine(true);
        box.setTextColor(Ui.TEXT);
        box.setHintTextColor(Ui.MUTED);
        box.setHint(R.string.discover_search_hint);
        ui.text(box, 14);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        box.setBackground(ui.round(Ui.SURFACE, 22));
        box.setText(discoverQuery);
        keyboardOnSelect(box);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.addView(box, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
        ImageView mic = roundIcon(android.R.drawable.ic_btn_speak_now, v -> startVoiceSearch());
        mic.setContentDescription(getString(R.string.voice_search));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
        mlp.leftMargin = ui.dp(10);
        searchRow.addView(mic, mlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(g, ui.dp(6), insetRight + ui.dp(20), ui.dp(4));
        column.addView(searchRow, slp);

        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        column.addView(searchResults);
        final LinearLayout browse = new LinearLayout(this);
        browse.setOrientation(LinearLayout.VERTICAL);
        column.addView(browse);
        appGrid = null;
        searchElsewhere(discoverQuery);
        browse.setVisibility(discoverQuery.trim().length() >= 3 ? View.GONE : View.VISIBLE);
        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {
                discoverQuery = t.toString();
                browse.setVisibility(discoverQuery.trim().length() >= 3 ? View.GONE : View.VISIBLE);
                searchElsewhere(discoverQuery);
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        // What to browse
        LinearLayout typeBar = chipBar(browse);
        addChoice(typeBar, "disc:type:0", getString(R.string.tab_movies), !discoverShows, () -> { discoverShows = false; discoverPick = discoverBy == BY_SERVICE ? discoverPick : -1; restartDiscover(); });
        addChoice(typeBar, "disc:type:1", getString(R.string.tab_shows), discoverShows, () -> { discoverShows = true; discoverPick = discoverBy == BY_SERVICE ? discoverPick : -1; restartDiscover(); });
        LinearLayout sortBar = chipBar(browse);
        String[] sorts = {getString(R.string.discover_popular), getString(R.string.discover_rated), getString(R.string.discover_year), getString(R.string.discover_service)};
        for (int i = 0; i < sorts.length; i++) {
            final int by = i;
            addChoice(sortBar, "disc:by:" + i, sorts[i], discoverBy == i, () -> { discoverBy = by; discoverPick = -1; restartDiscover(); });
        }
        LinearLayout pickBar = chipBar(browse);
        String[] picks;
        if (discoverBy == BY_YEAR) {
            int now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            picks = new String[30];
            for (int i = 0; i < picks.length; i++) picks[i] = String.valueOf(now - i);
        } else if (discoverBy == BY_SERVICE) {
            picks = SERVICES;
        } else {
            picks = GENRES;
        }
        if (discoverBy != BY_YEAR && discoverBy != BY_SERVICE) {
            addChoice(pickBar, "disc:pick:-1", getString(R.string.discover_all_genres), discoverPick < 0, () -> { discoverPick = -1; restartDiscover(); });
        }
        if ((discoverBy == BY_YEAR || discoverBy == BY_SERVICE) && discoverPick < 0) discoverPick = 0;
        for (int i = 0; i < picks.length; i++) {
            final int pick = i;
            addChoice(pickBar, "disc:pick:" + i, picks[i], discoverPick == i, () -> { discoverPick = pick; restartDiscover(); });
        }

        if (discoverBy == BY_SERVICE && prefs.tmdbKey().isEmpty()) {
            browse.addView(emptyNote(getString(R.string.discover_needs_tmdb)));
            return;
        }
        String key = discoverShows + "|" + discoverBy + "|" + discoverPick;
        if (!key.equals(discoverKey)) { discoverKey = key; discovered.clear(); discoverEnd = false; discoverError = null; loadDiscover(); }
        boolean discoverLoading = discoverKey.equals(discoverLoadingKey);
        if (discovered.isEmpty()) {
            browse.addView(emptyNote(discoverLoading ? getString(R.string.discover_loading) : discoverError != null ? discoverError : getString(R.string.library_none_match)));
            return;
        }
        int[] plan = gridPlan(cardWidth(land() ? 116 : 104));
        List<View> cells = new ArrayList<>();
        for (MediaItem m : discovered) cells.add(posterCell(m, "disc:t:" + m.id, m.year, plan[1], () -> showDetails(m), null));
        addGrid(browse, cells, plan);
        if (!discoverEnd) {
            TextView more = chip(getString(discoverLoading ? R.string.discover_loading : R.string.discover_more), false);
            focusKey(more, "disc:more");
            more.setOnClickListener(v -> loadDiscover());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(g, ui.dp(12), 0, ui.dp(12));
            browse.addView(more, lp);
        }
    }

    private LinearLayout chipBar(LinearLayout parent) {
        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        strip.setPadding(gutter(), ui.dp(8), insetRight + ui.dp(20), ui.dp(2));
        LinearLayout row = new LinearLayout(this);
        strip.addView(row);
        parent.addView(strip);
        return row;
    }

    private void addChoice(LinearLayout row, String key, String label, boolean chosen, Runnable pick) {
        TextView chip = chip(label, chosen);
        focusKey(chip, key);
        chip.setOnClickListener(v -> { restoreFocusKey = key; pick.run(); });
        row.addView(chip, chipSpacing(6));
        if (chosen) row.setTag(R.id.selected_chip, chip);
    }

    private void restartDiscover() { renderPreservingScroll(); }

    private void loadDiscover() {
        final String key = discoverKey;
        if (key.equals(discoverLoadingKey)) return;
        discoverLoadingKey = key;
        final boolean shows = discoverShows;
        final int by = discoverBy, pick = discoverPick, skip = discovered.size();
        final String tmdb = prefs.tmdbKey();
        io.execute(() -> {
            List<MediaItem> page;
            String problem = null;
            try {
                String type = shows ? "series" : "movie";
                if (by == BY_SERVICE) page = tmdbByService(tmdb, shows, SERVICE_IDS[Math.max(0, pick)], skip / 20 + 1);
                else if (by == BY_YEAR) page = CatalogRepository.browse(type, "year", String.valueOf(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - Math.max(0, pick)), skip);
                else page = CatalogRepository.browse(type, by == BY_RATING ? "imdbRating" : "top", pick < 0 ? null : GENRES[pick], skip);
            } catch (IOException | JSONException | RuntimeException e) {
                page = new ArrayList<>();
                problem = getString(R.string.discover_failed, CatalogRepository.describe(e instanceof IOException || e instanceof JSONException ? (Exception) e : new IOException(e.getMessage())));
            }
            final List<MediaItem> got = page;
            final String err = problem;
            main.post(() -> {
                if (key.equals(discoverLoadingKey)) discoverLoadingKey = null;
                if (!key.equals(discoverKey)) return; // the choice changed meanwhile
                java.util.Set<String> have = new java.util.HashSet<>();
                for (MediaItem m : discovered) have.add(m.id);
                int before = discovered.size();
                for (MediaItem m : got) if (have.add(m.id)) discovered.add(m);
                if (discovered.size() == before) discoverEnd = true;
                discoverError = err;
                if (tab == TAB_DISCOVER) renderPreservingScroll();
            });
        });
    }

    /** One page of what a streaming service carries (TMDB "where to watch"), as IMDb-numbered titles. */
    private static List<MediaItem> tmdbByService(String key, boolean shows, int provider, int page) throws IOException, JSONException {
        String kind = shows ? "tv" : "movie";
        String region = java.util.Locale.getDefault().getCountry().isEmpty() ? "US" : java.util.Locale.getDefault().getCountry();
        JSONObject o = new JSONObject(new String(Http.get("https://api.themoviedb.org/3/discover/" + kind + "?api_key=" + key
                + "&with_watch_providers=" + provider + "&watch_region=" + region + "&sort_by=popularity.desc&page=" + page), java.nio.charset.StandardCharsets.UTF_8));
        JSONArray results = o.optJSONArray("results");
        List<MediaItem> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            try {
                JSONObject ext = new JSONObject(new String(Http.get("https://api.themoviedb.org/3/" + kind + "/" + r.getInt("id") + "/external_ids?api_key=" + key), java.nio.charset.StandardCharsets.UTF_8));
                String imdb = ext.optString("imdb_id", "");
                if (!imdb.startsWith("tt")) continue;
                String date = r.optString(shows ? "first_air_date" : "release_date", "");
                MediaItem m = MediaItem.catalog(imdb, shows ? "series" : "movie", r.optString(shows ? "name" : "title"), date.length() >= 4 ? date.substring(0, 4) : "");
                m.summary = r.optString("overview", "");
                out.add(m);
            } catch (IOException | JSONException skip) { /* one title fewer */ }
        }
        return out;
    }

    private void buildAppsTab(LinearLayout column) {
        int g = gutter();
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED);
        search.setHint(R.string.apps_search_hint);
        ui.text(search, 14);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        search.setBackground(ui.round(Ui.SURFACE, 22));
        search.setText(appFilter);
        keyboardOnSelect(search);
        // The box and a mic beside it. The mic opens the system's own speech screen, so it
        // works the same on phones and TV boxes, and needs no microphone permission here.
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
        ImageView mic = roundIcon(android.R.drawable.ic_btn_speak_now, v -> startVoiceSearch());
        mic.setContentDescription(getString(R.string.voice_search));
        mic.setOnFocusChangeListener(null);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
        mlp.leftMargin = ui.dp(10);
        searchRow.addView(mic, mlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(g, ui.dp(6), insetRight + ui.dp(20), ui.dp(6));
        column.addView(searchRow, slp);

        TextView hint = ui.label(getString(R.string.apps_hint), 11, Ui.MUTED, null);
        hint.setPadding(g, ui.dp(2), ui.dp(20), ui.dp(14));
        column.addView(hint);

        // Actors and titles matching the search, above the apps. Filled from the network, so
        // they arrive a moment after typing stops.
        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        column.addView(searchResults);

        appGrid = new LinearLayout(this);
        appGrid.setOrientation(LinearLayout.VERTICAL);
        appGrid.setPadding(g - ui.dp(5), 0, insetRight + ui.dp(15), 0);
        column.addView(appGrid);
        renderAppGrid();
        searchElsewhere(appFilter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { appFilter = s.toString(); renderAppGrid(); searchElsewhere(appFilter); }
            @Override public void afterTextChanged(Editable e) {}
        });
    }

    private LinearLayout searchResults;
    private int searchTicket;                               // newer typing makes older answers stale
    private String shownSearch = "";
    private List<Library.Person> foundActors = Collections.emptyList();
    private List<MediaItem> foundTitles = Collections.emptyList();

    /**
     * Looks the typed text up as actors (Wikidata) and as movies and shows (the catalog,
     * which also matches cast names), once typing has paused.
     */
    private void searchElsewhere(String typed) {
        final String q = typed.trim();
        final int ticket = ++searchTicket;
        if (q.length() < 3) { foundActors = Collections.emptyList(); foundTitles = Collections.emptyList(); shownSearch = ""; fillSearchResults(); return; }
        if (q.equalsIgnoreCase(shownSearch)) { fillSearchResults(); return; } // a rebuild of the tab, same words
        main.postDelayed(() -> {
            if (ticket != searchTicket) return;
            io.execute(() -> {
                List<Library.Person> actors;
                List<MediaItem> titles = new ArrayList<>();
                try { actors = people.search(q); } catch (IOException | JSONException e) { actors = Collections.emptyList(); }
                for (String type : new String[]{"movie", "series"}) {
                    try { titles.addAll(CatalogRepository.search(type, q)); } catch (IOException | JSONException ignored) { /* the other type may still answer */ }
                }
                final List<Library.Person> a = actors;
                main.post(() -> {
                    if (ticket != searchTicket) return;
                    foundActors = a;
                    foundTitles = titles;
                    shownSearch = q;
                    fillSearchResults();
                });
            });
        }, 450);
    }

    private void fillSearchResults() {
        if (searchResults == null) return;
        View focused = focused();
        boolean inside = focused != null && isInside(focused, searchResults);
        searchResults.removeAllViews();
        int g = gutter();
        if (!foundActors.isEmpty()) {
            searchResults.addView(rowTitle(getString(R.string.search_actors)));
            LinearLayout row = searchShelf(g);
            int size = cardWidth(land() ? 88 : 72);
            for (Library.Person p : foundActors) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size + ui.dp(30), -2);
                lp.rightMargin = ui.dp(8);
                row.addView(personCell(p, null, size, () -> showPerson(p)), lp);
            }
        }
        if (!foundTitles.isEmpty()) {
            searchResults.addView(rowTitle(getString(R.string.search_titles)));
            LinearLayout row = searchShelf(g);
            int w = cardWidth(land() ? 116 : 104);
            for (MediaItem m : foundTitles) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
                lp.rightMargin = ui.dp(12);
                row.addView(posterCell(m, null, m.year, w, () -> showDetails(m), null), lp);
            }
        }
        if (searchResults.getChildCount() > 0) searchResults.addView(rowTitle(getString(R.string.search_apps)));
        if (inside && usingRemote()) { View first = searchResults.findFocus(); if (first == null) searchResults.requestFocus(View.FOCUS_DOWN); }
        searchResults.post(images::flush);
    }

    private LinearLayout searchShelf(int g) {
        HorizontalScrollView shelf = new HorizontalScrollView(this);
        shelf.setHorizontalScrollBarEnabled(false);
        shelf.setClipToPadding(false);
        shelf.setClipChildren(false);
        shelf.setPadding(g, ui.dp(8), insetRight + ui.dp(12), ui.dp(12));
        shelf.setOnScrollChangeListener((v, x, y, ox, oy) -> images.flush());
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        shelf.addView(row);
        searchResults.addView(shelf);
        return row;
    }

    /** Rebuilds only the grid, so typing in the search box doesn't lose focus. */
    private void renderAppGrid() {
        if (appGrid == null) return;
        appGrid.removeAllViews();
        String q = appFilter.toLowerCase(Locale.ROOT).trim();
        int cols = land() ? 6 : 3;
        int avail = metrics().widthPixels - appGrid.getPaddingLeft() - appGrid.getPaddingRight();
        int tileH = (avail / cols - ui.dp(10)) * 9 / 16;
        int count = 0;
        LinearLayout row = null;
        for (AppRepository.App app : apps.visible()) {
            if (!app.label.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (count % cols == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = ui.dp(10);
                appGrid.addView(row, rlp);
            }
            row.addView(appTile(app, tileH, false), gridCell(tileH));
            count++;
        }
        if (row != null) while (row.getChildCount() < cols) row.addView(new View(this), gridCell(tileH));
        if (count == 0) {
            TextView empty = ui.label(getString(R.string.apps_empty), 13, Ui.MUTED, null);
            empty.setPadding(ui.dp(5), ui.dp(12), 0, 0);
            appGrid.addView(empty);
        }
    }

    private LinearLayout.LayoutParams gridCell(int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1);
        lp.setMargins(ui.dp(5), 0, ui.dp(5), 0);
        return lp;
    }

    // ============================================================== library tab

    private static final String PART_SAVED = "saved", PART_ACTORS = "actors", PART_HISTORY = "history";
    private static final int[] TITLE_SORTS = {R.string.sort_recent, R.string.sort_title, R.string.sort_newest, R.string.sort_rating};
    private static final int[] TITLE_SORT_VALUES = {Library.SORT_RECENT, Library.SORT_TITLE, Library.SORT_NEWEST, Library.SORT_RATING};
    private static final int[] ONLY_LABELS = {R.string.only_all, R.string.only_movies, R.string.only_shows};
    private static final int FROM_ALL = 0, FROM_PHONE_TV = 1, FROM_TRAKT = 2;
    private static final int[] FROM_LABELS = {R.string.from_all, R.string.from_phone_tv, R.string.from_trakt};
    private static final int[] HISTORY_SORTS = {R.string.sort_last_watched, R.string.sort_title, R.string.sort_most_watched};
    private static final java.util.regex.Pattern PLEX_EPISODE = java.util.regex.Pattern.compile("^S\\d+ · E\\d+");

    /**
     * The user's shelf in three parts: saved titles, saved actors, and watch history.
     * Each part remembers its own order; the chips beside the part names change it.
     */
    private void buildLibraryTab(LinearLayout column) {
        column.setClipChildren(false);
        int part = prefs.librarySection();
        LinearLayout bar = rowContainer(column);
        rowSeq--; // a row of controls, not a shelf of titles
        String[] parts = {getString(R.string.library_saved, library.titleCount()),
                getString(R.string.library_actors, library.peopleCount()), getString(R.string.library_history)};
        for (int i = 0; i < parts.length; i++) {
            final int index = i;
            TextView chip = chip(parts[i], i == part);
            chip.setOnClickListener(v -> { prefs.setLibrarySection(index); renderPreservingScroll(); });
            focusKey(chip, "lib:part:" + i);
            bar.addView(chip, chipSpacing(i == parts.length - 1 ? 18 : 6));
            if (i == part) bar.setTag(R.id.selected_chip, chip);
        }

        if (part == 0) {
            int sort = prefs.librarySort(PART_SAVED) % TITLE_SORTS.length, only = prefs.librarySort(PART_SAVED + "Only") % ONLY_LABELS.length;
            bar.addView(cycleChip("lib:sort", TITLE_SORTS, sort, PART_SAVED), chipSpacing(6));
            bar.addView(cycleChip("lib:only", ONLY_LABELS, only, PART_SAVED + "Only"), chipSpacing(6));
            // With Trakt connected, its watchlist and collection sit on the same shelf, sorted together.
            boolean hasTrakt = !traktWatchlist.isEmpty() || !traktCollection.isEmpty();
            int from = hasTrakt ? prefs.librarySort(PART_SAVED + "From") % FROM_LABELS.length : FROM_PHONE_TV;
            if (hasTrakt) bar.addView(cycleChip("lib:from", FROM_LABELS, from, PART_SAVED + "From"), chipSpacing(6));
            List<MediaItem> titles = new ArrayList<>();
            java.util.Set<String> mine = new java.util.HashSet<>(), seen = new java.util.HashSet<>();
            for (MediaItem m : library.titles(Library.SORT_RECENT, Library.ONLY_ALL)) {
                mine.add(m.id);
                if (from != FROM_TRAKT && seen.add(m.id)) titles.add(m);
            }
            if (from != FROM_PHONE_TV) {
                for (MediaItem m : traktWatchlist) if (seen.add(m.id)) titles.add(m);
                for (MediaItem m : traktCollection) if (seen.add(m.id)) titles.add(m);
            }
            titles = Library.sortTitles(Library.filter(titles, only), TITLE_SORT_VALUES[sort]);
            if (titles.isEmpty()) { column.addView(emptyNote(getString(library.titleCount() == 0 && !hasTrakt ? R.string.library_saved_empty : R.string.library_none_match))); return; }
            int[] plan = gridPlan(cardWidth(land() ? 116 : 104));
            List<View> cells = new ArrayList<>();
            for (MediaItem m : titles) cells.add(posterCell(m, "lib:t:" + Library.key(m), m.year, plan[1], () -> showDetails(m), mine.contains(m.id) ? null : "Trakt"));
            addGrid(column, cells, plan);
        } else if (part == 1) {
            boolean byName = prefs.librarySort(PART_ACTORS) % 2 == 1;
            bar.addView(cycleChip("lib:sort", new int[]{R.string.sort_recent, R.string.sort_name}, byName ? 1 : 0, PART_ACTORS), chipSpacing(6));
            TextView add = chip(getString(R.string.library_add_actor), false);
            add.setOnClickListener(v -> askActorName());
            focusKey(add, "lib:add");
            bar.addView(add, chipSpacing(6));
            if (library.peopleCount() > 0) {
                TextView clear = chip(getString(R.string.library_clear_actors), false);
                clear.setOnClickListener(v -> showNotice(getString(R.string.library_clear_actors_title),
                        getString(R.string.library_clear_actors_body),
                        new String[]{getString(R.string.library_clear_actors), getString(R.string.cancel)},
                        new Runnable[]{() -> { library.clearPeople(); renderPreservingScroll(); }, null}));
                focusKey(clear, "lib:clearActors");
                bar.addView(clear, chipSpacing(6));
            }
            List<Library.Person> actors = library.people(byName);
            if (actors.isEmpty()) { column.addView(emptyNote(getString(R.string.library_actors_empty))); return; }
            int[] plan = gridPlan(cardWidth(land() ? 116 : 104));
            List<View> cells = new ArrayList<>();
            for (Library.Person p : actors) cells.add(personCell(p, "lib:p:" + p.id, plan[1], () -> showPerson(p)));
            addGrid(column, cells, plan);
        } else {
            int sort = prefs.librarySort(PART_HISTORY) % HISTORY_SORTS.length;
            bar.addView(cycleChip("lib:sort", HISTORY_SORTS, sort, PART_HISTORY), chipSpacing(6));
            if (library.historyCount() > 0) {
                TextView clear = chip(getString(R.string.library_clear), false);
                clear.setOnClickListener(v -> showNotice(getString(R.string.library_clear_title), getString(R.string.library_clear_body),
                        new String[]{getString(R.string.library_clear), getString(R.string.cancel)},
                        new Runnable[]{() -> { library.clearHistory(); renderPreservingScroll(); }, null}));
                focusKey(clear, "lib:clear");
                bar.addView(clear, chipSpacing(6));
            }
            column.addView(emptyNote(historySources()));
            if (!traktWatchlist.isEmpty()) {
                // The Trakt watchlist: what the user means to watch, above what they have.
                column.addView(rowTitle(getString(R.string.library_watchlist, traktWatchlist.size())));
                int[] plan = gridPlan(cardWidth(land() ? 116 : 104));
                List<View> cells = new ArrayList<>();
                for (MediaItem m : traktWatchlist) cells.add(posterCell(m, "lib:w:" + m.id, m.year, plan[1], () -> showDetails(m), "Trakt"));
                addGrid(column, cells, plan);
                column.addView(rowTitle(getString(R.string.library_watched)));
            }
            List<Library.Watch> history = library.history(remoteHistory(), sort);
            if (history.isEmpty()) { column.addView(emptyNote(getString(historyLoading ? R.string.library_history_loading : R.string.library_history_empty))); return; }
            int[] plan = gridPlan(cardWidth(land() ? 116 : 104));
            List<View> cells = new ArrayList<>();
            for (Library.Watch w : history) {
                String when = DateUtils.getRelativeTimeSpanString(w.at, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
                String line = (w.episode.isEmpty() ? "" : w.episode + "  ·  ") + when;
                boolean own = w.item.source == MediaItem.Source.PLEX;
                cells.add(posterCell(w.item, "lib:h:" + w.key(), line, plan[1],
                        () -> { if (own) play(w.item); else showDetails(w.item); }, w.times > 1 ? w.via + "  ·  " + w.times + "×" : w.via));
            }
            addGrid(column, cells, plan);
        }
    }

    /** Says where the history comes from, so an empty or short list explains itself. */
    private String historySources() {
        List<String> on = new ArrayList<>();
        on.add(getString(R.string.app_name));
        if (plex.isLinked()) on.add("Plex");
        if (trakt.hasHistory()) on.add("Trakt");
        String line = getString(R.string.library_history_sources, TextUtils.join(", ", on));
        if (historyError != null) line += "\n" + historyError;
        else if (!trakt.hasHistory()) line += "  " + getString(R.string.library_history_trakt_hint);
        return line;
    }

    /** History reported by Plex and Trakt, in the library's own shape. */
    List<Library.Watch> remoteHistory() {
        List<Library.Watch> out = new ArrayList<>(traktHistory);
        for (MediaItem m : plexHistory) {
            Library.Watch w = new Library.Watch();
            w.item = m;
            w.via = "Plex";
            w.at = m.watchedAt;
            // Plex folds the episode into the item's subtitle ("S2 · E4 · Name"); a movie's has none.
            java.util.regex.Matcher episode = PLEX_EPISODE.matcher(m.subtitle == null ? "" : m.subtitle);
            if (episode.find()) w.episode = episode.group();
            out.add(w);
        }
        return out;
    }

    /** Fetches Plex and Trakt history in the background, then redraws the Library if it is showing. */
    void refreshHistory() {
        if (historyLoading || !(plex.isLinked() || trakt.hasHistory())) return;
        historyLoading = true;
        io.execute(() -> {
            // A source that fails keeps what it showed last time; one that is switched off is emptied.
            List<MediaItem> fromPlex = plex.isLinked() ? null : Collections.<MediaItem>emptyList();
            List<Library.Watch> fromTrakt = trakt.hasHistory() ? null : Collections.<Library.Watch>emptyList();
            List<MediaItem> toWatch = trakt.hasHistory() ? null : Collections.<MediaItem>emptyList();
            List<String> problems = new ArrayList<>();
            if (plex.isLinked()) {
                try { fromPlex = plex.history(); }
                catch (IOException | JSONException e) { problems.add("Plex: " + CatalogRepository.describe(e)); }
            }
            if (trakt.hasHistory()) {
                try { fromTrakt = trakt.history(); toWatch = trakt.watchlist(); }
                catch (IOException | JSONException e) { problems.add("Trakt: " + CatalogRepository.describe(e)); }
            }
            List<MediaItem> owned = null;
            if (trakt.hasHistory()) {
                try { owned = trakt.collection(); }
                catch (IOException | JSONException e) { /* the collection is a bonus; the watchlist and history still show */ }
            } else owned = Collections.emptyList();
            final List<MediaItem> collectionResult = owned;
            final List<MediaItem> watchlistResult = toWatch;
            final List<MediaItem> plexResult = fromPlex;
            final List<Library.Watch> traktResult = fromTrakt;
            main.post(() -> {
                historyLoading = false;
                if (plexResult != null) plexHistory = plexResult;
                if (traktResult != null) traktHistory = traktResult;
                if (watchlistResult != null) traktWatchlist = watchlistResult;
                if (collectionResult != null) traktCollection = collectionResult;
                historyError = problems.isEmpty() ? null : TextUtils.join("\n", problems);
                if (tab == TAB_LIBRARY) renderPreservingScroll();
            });
        });
    }

    // ----------------------------------------------------------- grids and cells

    TextView chip(CharSequence text, boolean chosen) {
        TextView chip = ui.label(text, 13, Ui.TEXT, Ui.MEDIUM);
        chip.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        chip.setFocusable(true);
        chip.setClickable(true);
        ui.chipStyle(chip, chosen);
        return chip;
    }

    LinearLayout.LayoutParams chipSpacing(float rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(rightDp);
        return lp;
    }

    /** A chip that steps through a few choices each time it is pressed, showing the current one. */
    private TextView cycleChip(String key, int[] labels, int current, String pref) {
        TextView chip = chip(getString(labels[current]) + "  ⇅", false);
        chip.setOnClickListener(v -> { prefs.setLibrarySort(pref, (current + 1) % labels.length); renderPreservingScroll(); });
        focusKey(chip, key + ":" + pref);
        return chip;
    }

    private TextView emptyNote(String text) {
        TextView t = ui.label(text, 13, Ui.MUTED, null);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(gutter(), ui.dp(4), insetRight + ui.dp(24), ui.dp(10));
        return t;
    }

    /**
     * How many cells fit across the screen and how wide each should be to fill it exactly.
     * @return {cells per row, cell width in px}
     */
    int[] gridPlan(int idealWidth) {
        int gap = ui.dp(12);
        int available = metrics().widthPixels - gutter() - insetRight - ui.dp(16);
        int perRow = Math.max(2, (available + gap) / (idealWidth + gap));
        return new int[]{perRow, (available - gap * (perRow - 1)) / perRow};
    }

    /** Lays cells out left to right, wrapping into rows. Each row is a shelf to the D-pad. */
    void addGrid(LinearLayout column, List<View> cells, int[] plan) {
        LinearLayout row = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % plan[0] == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setPadding(gutter(), ui.dp(8), 0, ui.dp(10));
                row.setTag(R.id.focus_row, Boolean.TRUE);
                column.addView(row);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(plan[1], -2);
            lp.rightMargin = ui.dp(12);
            row.addView(cells.get(i), lp);
        }
        main.post(images::flush);
    }

    /**
     * A poster with its title underneath and up to two dim lines of detail.
     * @param key focus name on the home screen, or null on a page that keeps no names.
     */
    View posterCell(MediaItem m, String key, String line1, int width, Runnable onClick, String line2) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        FrameLayout card = card();
        TextView fallback = ui.label(m.title, 12, Ui.MUTED, null);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        card.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView art = new Art(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        images.defer(card, () -> images.load(art, m.poster(width), width, false, null));
        card.setOnClickListener(v -> onClick.run());
        if (key != null) focusKey(card, key);
        cell.addView(card, new LinearLayout.LayoutParams(width, width * 3 / 2));
        cell.addView(caption(m.title, 12, Ui.TEXT, 8));
        if (line1 != null && !line1.isEmpty()) cell.addView(caption(line1, 11, Ui.MUTED, 1));
        if (line2 != null && !line2.isEmpty()) cell.addView(caption(line2, 11, Ui.MUTED, 1));
        return cell;
    }

    /**
     * An actor as a round photo with their name underneath, and the part they play when
     * that is known. Initials stand in for a missing photo. The same cell is used for a
     * title's cast and for saved actors.
     * @param key focus name on the home screen, or null on a page that keeps no names.
     */
    View personCell(Library.Person p, String key, int diameter, Runnable onClick) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        FrameLayout face = new FrameLayout(this);
        ui.focusableCircle(face);
        TextView initials = ui.label(initials(p.name), 22, Ui.MUTED, Ui.BOLD);
        initials.setGravity(Gravity.CENTER);
        face.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        if (!p.photo.isEmpty()) {
            ImageView photo = new Ui.TopCropImageView(this);
            face.addView(photo, new FrameLayout.LayoutParams(-1, -1));
            images.defer(face, () -> images.load(photo, p.photo, diameter, false, null));
        }
        face.setContentDescription(p.role.isEmpty() ? p.name : p.name + ", " + p.role);
        face.setOnClickListener(v -> onClick.run());
        face.setOnLongClickListener(v -> { toggleSaved(p); return true; });
        if (key != null) focusKey(face, key);
        cell.addView(face, new LinearLayout.LayoutParams(diameter, diameter));
        TextView name = caption(p.name, 12, Ui.TEXT, 9);
        name.setMaxLines(2);
        cell.addView(name);
        if (!p.role.isEmpty()) cell.addView(caption(p.role, 11, Ui.MUTED, 2));
        return cell;
    }

    /**
     * Long press on an actor: saves them, or takes them out of the Library if already saved.
     * A cast name the catalog gave without a Wikidata match is looked up first.
     */
    void toggleSaved(Library.Person p) {
        if (p.id != null && library.has(p)) {
            library.remove(p);
            toast(getString(R.string.person_removed, p.name));
            libraryChanged(false);
            return;
        }
        if (p.id != null) {
            library.add(p);
            toast(getString(R.string.person_added, p.name));
            libraryChanged(false);
            return;
        }
        new Thread(() -> {
            Library.Person found;
            try { found = people.find(p.name); } catch (IOException | JSONException e) { found = null; }
            final Library.Person who = found;
            main.post(() -> {
                if (who == null) { toast(getString(R.string.person_unknown, p.name)); return; }
                library.add(who);
                toast(getString(R.string.person_added, who.name));
                libraryChanged(false);
            });
        }, "phonetv-person").start();
    }

    TextView caption(String text, float sp, int color, float topDp) {
        TextView t = ui.label(text, sp, color, null);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(ui.dp(2), ui.dp(topDp), ui.dp(2), 0);
        return t;
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String word : name.trim().split("\\s+")) if (!word.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(word.charAt(0)));
        return sb.toString();
    }

    // ------------------------------------------------------------------ actors

    /** Opens an actor's page from a saved entry. */
    void showPerson(Library.Person p) { openOverlay(new PersonScreen(this, p, null)); }

    /** Opens an actor's page from just a name, e.g. one tapped in a title's cast. */
    void showPerson(String name) { openOverlay(new PersonScreen(this, null, name)); }

    private void askActorName() {
        float d = getResources().getDisplayMetrics().density;
        EditText name = new EditText(this);
        name.setHint(R.string.library_actor_hint);
        name.setSingleLine(true);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        keyboardOnSelect(name);
        FrameLayout form = new FrameLayout(this);
        form.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);
        form.addView(name);
        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.library_add_actor)
                .setView(form)
                .setPositiveButton(R.string.library_find, (dlg, w) -> {
                    String typed = name.getText().toString().trim();
                    if (!typed.isEmpty()) showPerson(typed);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ================================================================ media rows

    /** Media card width: the given dp, scaled by Display size and by the Card size setting. */
    private int cardWidth(float dp) { return (int) (ui.dp(dp) * Prefs.CARD_SCALES[prefs.cardSize()]); }

    private void backdropRow(LinearLayout column, String title, List<MediaItem> items) {
        if (items.isEmpty()) return;
        column.addView(rowTitle(title));
        LinearLayout row = rowContainer(column);
        int w = cardWidth(land() ? 280 : 236), h = w * 9 / 16;
        for (MediaItem m : items) {
            FrameLayout card = card();
            wideArt(card, m, w, true);
            if (m.progressPercent > 0) addProgressBar(card, w, m.progressPercent);
            bindItem(card, m);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = ui.dp(10);
            row.addView(card, lp);
        }
    }

    /**
     * Fills a card with 16:9 artwork, a bottom scrim and the title (its logo when one exists).
     * @param lazy wait until the card is near the screen before downloading anything.
     */
    private View wideArt(FrameLayout card, MediaItem m, int w, boolean lazy) {
        FrameLayout layer = new FrameLayout(this);
        ImageView art = new Art(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        layer.addView(art, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0x00000000, 0x00000000, 0xCC000000));
        layer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        TextView name = ui.label(m.title, 13, Ui.TEXT, Ui.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(m.progressPercent > 0 ? 12 : 8));
        layer.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        ImageView logo = new Ui.LogoView(this);
        logo.setVisibility(View.GONE);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams((int) (w * 0.55f), ui.dp(32), Gravity.BOTTOM | Gravity.START);
        llp.leftMargin = ui.dp(10);
        llp.bottomMargin = ui.dp(8);
        layer.addView(logo, llp);
        card.addView(layer, new FrameLayout.LayoutParams(-1, -1));

        Runnable load = () -> {
            images.load(art, m.backdrop(w), w, false, null);
            images.load(logo, m.logo(), (int) (w * 0.55f), true,
                    () -> { logo.setVisibility(View.VISIBLE); name.setVisibility(View.GONE); });
        };
        if (lazy) images.defer(card, load); else load.run();
        return layer;
    }

    private void posterRow(LinearLayout column, String title, List<MediaItem> items) {
        if (items.isEmpty()) return;
        if (prefs.wideCards()) { backdropRow(column, title, items); return; }
        column.addView(rowTitle(title));
        LinearLayout row = rowContainer(column);
        boolean labels = prefs.posterLabels();
        int w = cardWidth(land() ? 116 : 104), h = w * 3 / 2;
        for (MediaItem m : items) {
            FrameLayout card = card();
            TextView fallback = ui.label(m.title, 12, Ui.MUTED, null);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(ui.dp(8), 0, ui.dp(8), 0);
            card.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
            ImageView art = new Art(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
            images.defer(card, () -> images.load(art, m.poster(w), w, false, null));
            bindItem(card, m);
            expandOnPause(card, m, w, h);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(labels ? -2 : w, labels ? -2 : h);
            lp.rightMargin = ui.dp(10);
            if (!labels) { row.addView(card, lp); continue; }
            // Title under the poster: the card stays the focus target, the caption rides along.
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            cell.addView(card, new LinearLayout.LayoutParams(w, h));
            TextView caption = ui.label(m.title, 11, Ui.TEXT_DIM, null);
            caption.setMaxLines(1);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setPadding(ui.dp(2), ui.dp(9), ui.dp(2), 0);
            cell.addView(caption, new LinearLayout.LayoutParams(w, -2));
            row.addView(cell, lp);
        }
    }

    /**
     * Rest on a poster for a moment and it widens to 16:9 to show the title's artwork,
     * then folds back when focus moves on. Remote and keyboard only; touch never focuses a card.
     */
    private void expandOnPause(FrameLayout card, MediaItem m, int w, int h) {
        int delayMs = prefs.expandDelay() * 1000;
        if (delayMs <= 0 || m.backdropRef == null || m.backdropRef.isEmpty()) return;
        final int wide = h * 16 / 9;
        final View[] layer = new View[1];
        Runnable expand = () -> {
            if (!card.isFocused() || !card.isAttachedToWindow()) return;
            if (layer[0] == null) {
                layer[0] = wideArt(card, m, wide, false);
                layer[0].setAlpha(0f);
            }
            layer[0].animate().alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.STANDARD).start();
            Motion.width(card, wide, () -> reveal(card)); // keep the growing card on its pivot
        };
        View.OnFocusChangeListener base = card.getOnFocusChangeListener();
        card.setOnFocusChangeListener((v, has) -> {
            if (base != null) base.onFocusChange(v, has);
            main.removeCallbacks(expand);
            if (has) { main.postDelayed(expand, delayMs); return; }
            if (layer[0] != null) layer[0].animate().alpha(0f).setDuration(Motion.FAST).start();
            Motion.width(card, w, null);
        });
    }

    private void addProgressBar(FrameLayout card, int cardWidth, int percent) {
        FrameLayout.LayoutParams track = new FrameLayout.LayoutParams(-1, ui.dp(3), Gravity.BOTTOM);
        View bg = new View(this);
        bg.setBackgroundColor(0x55FFFFFF);
        card.addView(bg, track);
        View fill = new View(this);
        fill.setBackgroundColor(0xFFE5A00D); // Plex amber
        card.addView(fill, new FrameLayout.LayoutParams(cardWidth * percent / 100, ui.dp(3), Gravity.BOTTOM | Gravity.START));
    }

    private FrameLayout card() {
        FrameLayout c = new FrameLayout(this);
        c.setBackground(ui.round(Ui.SURFACE, ui.cardRadius));
        c.setClipToOutline(true);
        c.setForeground(ui.ripple(null));
        ui.focusable(c);
        return c;
    }

    private void bindItem(View v, MediaItem m) {
        focusKey(v, "row" + rowSeq + ":" + m.id);
        // Your own library plays straight away (Continue watching); catalog titles show details first.
        boolean own = m.source == MediaItem.Source.PLEX;
        v.setOnClickListener(x -> { if (own) play(m); else showDetails(m); });
        v.setOnLongClickListener(x -> { if (own) showDetails(m); else play(m); return true; });
    }

    private TextView rowTitle(String s) {
        TextView t = ui.label(s, 15, Ui.TEXT, Ui.MEDIUM);
        t.setPadding(gutter(), ui.dp(16), ui.dp(20), ui.dp(2));
        return t;
    }

    /** Horizontal shelf. Pass a parent to attach it; the returned row's parent is the scroller. */
    private LinearLayout rowContainer(LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        // Room above and below (and no clipping) so a zoomed card isn't cut off by its row.
        hs.setPadding(gutter(), ui.dp(10), insetRight + ui.dp(10), ui.dp(10));
        hs.setClipChildren(false);
        hs.setOnScrollChangeListener((v, x, y, ox, oy) -> images.flush());
        rowSeq++;
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        hs.addView(row);
        if (parent != null) parent.addView(hs);
        return row;
    }

    private View statusCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        c.setBackground(ui.round(Ui.SURFACE, 14));
        String error = catalog.lastError();
        if (error != null && !catalog.isFetching()) {
            c.addView(ui.label(getString(R.string.status_error_title), 15, Ui.TEXT, Ui.MEDIUM));
            TextView body = ui.label(getString(R.string.status_error_body, error), 12, Ui.MUTED, null);
            body.setPadding(0, ui.dp(6), 0, ui.dp(12));
            c.addView(body);
            TextView retry = ui.primary(getString(R.string.retry));
            retry.setOnClickListener(v -> { refreshContent(); render(); });
            c.addView(retry, new LinearLayout.LayoutParams(-2, -2));
        } else {
            c.addView(ui.label(getString(R.string.status_loading_title), 15, Ui.TEXT, Ui.MEDIUM));
            TextView body = ui.label(getString(R.string.status_loading_body), 12, Ui.MUTED, null);
            body.setPadding(0, ui.dp(6), 0, 0);
            c.addView(body);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(gutter(), ui.dp(20), insetRight + ui.dp(20), 0);
        c.setLayoutParams(lp);
        return c;
    }

    /** Small card that explains a failure and offers a retry, instead of a silent toast. */
    private View noticeCard(String title, String body, Runnable retry) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        c.setBackground(ui.round(Ui.SURFACE, 14));
        c.addView(ui.label(title, 14, Ui.TEXT, Ui.MEDIUM));
        TextView b = ui.label(body, 12, Ui.MUTED, null);
        b.setPadding(0, ui.dp(4), 0, ui.dp(10));
        c.addView(b);
        TextView again = ui.pill(getString(R.string.retry), 0x26FFFFFF, Ui.TEXT);
        again.setOnClickListener(v -> retry.run());
        c.addView(again, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(gutter(), ui.dp(18), insetRight + ui.dp(20), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private View plexPrompt() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        c.setBackground(ui.round(Ui.SURFACE, 14));
        c.addView(ui.label(getString(R.string.plex_prompt_title), 14, Ui.TEXT, Ui.MEDIUM));
        TextView body = ui.label(getString(R.string.plex_prompt_body), 12, Ui.MUTED, null);
        body.setPadding(0, ui.dp(4), 0, ui.dp(10));
        c.addView(body);
        LinearLayout actions = new LinearLayout(this);
        TextView link = ui.pill(getString(R.string.plex_prompt_link), 0xFFE5A00D, Ui.ON_LIGHT);
        link.setOnClickListener(v -> linkPlex());
        actions.addView(link);
        TextView later = ui.pill(getString(R.string.not_now), 0x26FFFFFF, Ui.TEXT);
        later.setOnClickListener(v -> { prefs.dismissPlexPrompt(); renderPreservingScroll(); });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.leftMargin = ui.dp(8);
        actions.addView(later, llp);
        c.addView(actions);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(gutter(), ui.dp(18), insetRight + ui.dp(20), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private View footer() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setGravity(Gravity.CENTER_HORIZONTAL);
        f.setPadding(0, ui.dp(28), 0, insetBottom + ui.dp(24));
        f.addView(ui.label(getString(R.string.footer, BuildConfig.VERSION_NAME), 11, Ui.MUTED, null));
        TextView donate = ui.label(getString(R.string.footer_donate), 12, 0xFFFF8A80, Ui.MEDIUM);
        donate.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        donate.setBackground(ui.ripple(null));
        donate.setOnClickListener(v -> openUrl(KOFI_URL, null));
        f.addView(donate);
        return f;
    }

    // ===================================================================== TV link

    /** Overridden by {@link TvHomeActivity}, the copy of this screen that runs on an attached TV. */
    boolean isTvInstance() { return false; }

    /** False when this instance was started on a display it has no business on; it then ends at once. */
    boolean acceptDisplay() { return true; }

    /** Id of the display this instance is showing on. */
    @SuppressWarnings("deprecation") // getDefaultDisplay() is the only route before Android 11
    int displayId() {
        android.view.Display d = null;
        if (Build.VERSION.SDK_INT >= 30) {
            try { d = getDisplay(); }
            catch (UnsupportedOperationException notAttachedYet) { /* fall through to the window manager */ }
        }
        if (d == null) d = getWindowManager().getDefaultDisplay();
        return d == null ? android.view.Display.DEFAULT_DISPLAY : d.getDisplayId();
    }

    /** The TV or monitor plugged into (or cast from) this phone, if there is one. */
    android.view.Display externalDisplay() {
        if (displayManager == null) return null;
        for (android.view.Display d : displayManager.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) return d;
        }
        return null;
    }

    private void displaysChanged() {
        tvLinkRefused = false;
        refusalTold = false;
        caseDismissed = false;
        if (resumed) syncTvLink();
    }

    /**
     * Brings the phone's screen in line with the "Phone screen while on a TV" setting.
     *
     * Left alone, a phone mirrors itself to the TV, so both show the same thing. For
     * the phone to show a cooling loop (or nothing) the TV needs a picture of its own:
     * we start {@link TvHomeActivity} on the TV's display and cover this screen with a
     * {@link CaseScreen}. Phones that can only mirror refuse that start, and we fall
     * back to mirroring and say so once.
     */
    void syncTvLink() {
        if (tvDevice || displayManager == null) return;
        android.view.Display tv = externalDisplay();
        boolean caseUp = overlay instanceof CaseScreen && ((CaseScreen) overlay).forTv();
        boolean wanted = prefs.caseMode() != Prefs.CASE_MIRROR && tv != null && !tvLinkRefused && !caseDismissed
                && Build.VERSION.SDK_INT >= 26;
        if (!wanted) {
            if (caseUp) closeOverlay();
            if (tv == null) TvHomeActivity.end();
            if (tvLinkRefused && !refusalTold && prefs.caseMode() != Prefs.CASE_MIRROR) {
                refusalTold = true;
                showNotice(getString(R.string.case_refused_title), getString(R.string.case_refused_body),
                        new String[]{getString(R.string.close)}, new Runnable[]{null});
            }
            return;
        }
        // A running screen saver gives way to the TV screen; closing it comes back through here.
        if (overlay instanceof CaseScreen && !caseUp) { closeOverlay(); return; }
        if (overlay != null && !caseUp) return; // a page is open; applied when it closes
        if (!caseUp) openOverlay(new CaseScreen(this, true));
        if (!TvHomeActivity.alive && !startOnTv(new Intent(this, TvHomeActivity.class), tv)) {
            tvLinkRefused = true;
            syncTvLink();
        }
    }

    /** Starts one of our activities on the TV's display. @return false if Android refused. */
    @android.annotation.TargetApi(26)
    private boolean startOnTv(Intent intent, android.view.Display tv) {
        android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
        options.setLaunchDisplayId(tv.getDisplayId());
        // Desktop-style modes open new windows small; ask for the whole screen.
        android.graphics.Point size = new android.graphics.Point();
        tv.getRealSize(size);
        options.setLaunchBounds(new android.graphics.Rect(0, 0, size.x, size.y));
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION), options.toBundle());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Key presses go to the display that was used last, so after a tap on the phone
     * the remote would be talking to the phone. This makes the TV the active display again.
     */
    void returnRemoteToTv() {
        android.view.Display tv = externalDisplay();
        if (tv != null && TvHomeActivity.alive && Build.VERSION.SDK_INT >= 26) startOnTv(new Intent(this, TvFocusActivity.class), tv);
    }

    /** "Use this screen": the phone goes back to being a phone until Home is pressed on it. */
    void dismissCase() {
        caseDismissed = true;
        closeOverlay();
    }

    /** When this screen runs on a TV, apps it opens should appear on that TV as well. */
    @android.annotation.TargetApi(26)
    private Bundle launchOptions() {
        if (!isTvInstance() || Build.VERSION.SDK_INT < 26) return null;
        android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId());
        return options.toBundle();
    }

    /** Starts the idle countdown again; the screen saver shows when it runs out. */
    void restartScreenSaver() {
        main.removeCallbacks(screenSaver);
        int minutes = prefs == null ? 0 : prefs.saverMinutes();
        if (minutes > 0 && resumed) main.postDelayed(screenSaver, minutes * 60_000L);
    }

    /** Shows the cooling loop right now, as the screen saver would. */
    void previewScreenSaver() { openOverlay(saverPage()); }

    /** The chosen screen saver; artwork falls back to the loop until there are titles to show. */
    private Overlay saverPage() {
        if (prefs.saverStyle() == Prefs.SAVER_ART) {
            List<MediaItem> pool = new ArrayList<>(heroItems);
            pool.addAll(moodItems);
            for (String key : new String[]{CatalogRepository.MOVIES, CatalogRepository.SHOWS, CatalogRepository.TOP_RATED}) {
                List<MediaItem> got = catalog.get(key);
                if (got != null) pool.addAll(got);
            }
            for (List<MediaItem> row : addonRows.values()) pool.addAll(row);
            ArtSaverScreen art = new ArtSaverScreen(this, pool);
            if (art.hasArt()) return art;
        }
        return new CaseScreen(this, false);
    }

    // ============================================================ pages above home

    /** Opens the full-screen details page for a title. Back returns to where the user was. */
    void showDetails(MediaItem m) { openOverlay(new DetailsScreen(this, m)); }

    void showSettings() { openOverlay(new SettingsScreen(this)); }

    // ============================================================ profiles

    void showProfiles() { openOverlay(new ProfileScreen(this)); }

    /** Moves the household over to another person's saved titles, history and Continue watching. */
    void switchProfile(String id) {
        boolean changed = !id.equals(Profiles.currentId(this));
        Profiles.switchTo(this, id);
        if (overlay instanceof ProfileScreen) closeOverlay();
        if (!changed) return;
        library = Library.of(this);
        toast(getString(R.string.profile_switched, Profiles.current(this).name));
        render();
        refreshHistory();
    }

    void addProfile(Runnable done) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.profile_name_hint);
        keyboardOnSelect(name);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        box.addView(name);
        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.profile_add)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Profiles.add(this, name.getText().toString());
                    render(); // the top bar gains its profile button
                    if (done != null) done.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    void manageProfile(Profiles.Profile p, Runnable done) {
        List<String> options = new ArrayList<>();
        options.add(getString(R.string.profile_rename));
        if (!p.id.isEmpty()) options.add(getString(R.string.profile_remove));
        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(p.name)
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        EditText name = new EditText(this);
                        name.setSingleLine(true);
                        name.setText(p.name);
                        keyboardOnSelect(name);
                        FrameLayout box = new FrameLayout(this);
                        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
                        box.addView(name);
                        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                                .setTitle(R.string.profile_rename)
                                .setView(box)
                                .setPositiveButton(R.string.save, (d2, w) -> { Profiles.rename(this, p.id, name.getText().toString()); render(); if (done != null) done.run(); })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else {
                        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                                .setTitle(getString(R.string.profile_remove_title, p.name))
                                .setMessage(R.string.profile_remove_message)
                                .setPositiveButton(R.string.profile_remove, (d2, w) -> {
                                    boolean wasCurrent = p.id.equals(Profiles.currentId(this));
                                    Profiles.remove(this, p.id);
                                    if (wasCurrent) { library = Library.of(this); refreshHistory(); }
                                    render();
                                    if (done != null) done.run();
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    }
                })
                .show();
    }

    /**
     * Shows a page above the home screen. Home stays built underneath with its
     * scroll position, so closing the page lands exactly where the user left off.
     */
    private void openOverlay(Overlay page) {
        stopTrailer();
        if (overlay == null) {
            View focused = focused();
            Object key = focused == null ? null : focused.getTag(R.id.focus_key);
            overlayReturnKey = key instanceof String ? (String) key : null;
        } else {
            // Pages stack: the one underneath is hidden, not closed, so Back returns to it as it was.
            pageFocus.put(overlay, focused());
            overlay.view().setVisibility(View.GONE);
        }
        pages.push(page);
        overlay = page;
        main.removeCallbacks(heroAdvance);
        // Nothing underneath may take focus or be read out while a page is open.
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        home.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        View v = page.view();
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setAlpha(0f);
        v.setTranslationY(ui.dp(16));
        v.animate().alpha(1f).translationY(0).setDuration(Motion.MEDIUM - 90).setInterpolator(Motion.STANDARD).start();
        if (usingRemote()) { View first = page.firstFocus(); if (first != null) first.requestFocus(); }
    }

    /** Closes the page on top and returns to the one beneath it, or to the home screen. */
    void closeOverlay() {
        if (overlay == null) return;
        Overlay closing = pages.pop();
        pageFocus.remove(closing);
        final View v = closing.view();
        overlay = pages.peek();
        if (overlay != null) {
            overlay.view().setVisibility(View.VISIBLE);
            if (usingRemote()) {
                View back = pageFocus.remove(overlay);
                if (back == null || !back.isShown()) back = overlay.firstFocus();
                if (back != null) back.requestFocus();
            }
        } else {
            scheduleTrailer();
            home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            home.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (usingRemote()) {
                // Same tile as before; the home screen may have been rebuilt meanwhile, hence the lookup by name.
                View back = overlayReturnKey == null ? null : focusTarget(overlayReturnKey);
                if (back == null || !back.isShown()) back = focusTargets.get("hero:watch");
                if (back == null || !back.isShown()) back = focusTargets.get("tab:" + tab);
                if (back != null) back.requestFocus();
            }
        }
        v.animate().alpha(0f).translationY(ui.dp(16)).setDuration(Motion.FAST).setInterpolator(Motion.STANDARD)
                .withEndAction(() -> root.removeView(v)).start();
        restartHeroTimer();
        restartScreenSaver();
        // Titles may have been saved from the TV while this screen sat behind the case screen.
        if (closing instanceof CaseScreen && ((CaseScreen) closing).forTv()) renderPreservingScroll();
        syncTvLink();
    }

    /**
     * The library changed while a page was open or a player was starting; the home screen
     * underneath catches up. Only saved titles appear on For you, so only they redraw it.
     */
    void libraryChanged(boolean savedTitles) {
        if (tab == TAB_LIBRARY || (savedTitles && tab == TAB_FOR_YOU && prefs.rowShown(ROW_LIBRARY))) renderPreservingScroll();
    }

    /** True when the user is driving with a remote, keyboard or controller rather than touch. */
    boolean usingRemote() { return tvDevice || !root.isInTouchMode(); }

    /**
     * A small centred card with a message and a few actions; the first action takes focus.
     * Used when something can't be done, so a tap is never met with silence.
     */
    void showNotice(String title, String body, String[] labels, Runnable[] actions) {
        Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(Ui.PANEL, 18));
        card.setPadding(ui.dp(22), ui.dp(20), ui.dp(22), ui.dp(18));
        card.addView(ui.label(title, 18, Ui.TEXT, Ui.BOLD));
        TextView text = ui.label(body, 13, Ui.TEXT_DIM, null);
        text.setLineSpacing(0, 1.2f);
        text.setPadding(0, ui.dp(8), 0, ui.dp(16));
        card.addView(text);

        boolean stacked = !land();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final Runnable action = actions[i];
            TextView b = i == 0 ? ui.primary(labels[i]) : ui.pill(labels[i], 0x33FFFFFF, Ui.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> { d.dismiss(); if (action != null) action.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(stacked ? -1 : -2, -2);
            if (stacked) lp.bottomMargin = ui.dp(8); else lp.rightMargin = ui.dp(8);
            row.addView(b, lp);
        }
        card.addView(row);

        int w = Math.min(metrics().widthPixels - ui.dp(32), ui.dp(460));
        d.setContentView(card, new ViewGroup.LayoutParams(w, -2));
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.show();
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    // ==================================================================== dialogs

    private void showAppMenu(AppRepository.App app) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int index = apps.favoriteIndex(app.pkg);
        if (index > 0) { labels.add(getString(R.string.menu_move_left)); actions.add(() -> apps.moveFavorite(app.pkg, -1)); }
        if (index >= 0 && index < apps.favoriteCount() - 1) { labels.add(getString(R.string.menu_move_right)); actions.add(() -> apps.moveFavorite(app.pkg, 1)); }
        labels.add(getString(index >= 0 ? R.string.menu_fav_remove : R.string.menu_fav_add));
        actions.add(() -> {
            boolean added = apps.toggleFavorite(app.pkg);
            toast(getString(added ? R.string.toast_fav_added : R.string.toast_fav_removed, app.label));
        });
        labels.add(getString(R.string.menu_tile_image));
        actions.add(() -> pickTileImage(app.pkg));
        if (tileArt.hasCustomImage(app.pkg)) { labels.add(getString(R.string.menu_tile_reset)); actions.add(() -> tileArt.resetCustomImage(app.pkg)); }
        boolean hidden = apps.isHidden(app.pkg);
        labels.add(getString(hidden ? R.string.menu_unhide : R.string.menu_hide));
        actions.add(() -> apps.setHidden(app.pkg, !hidden));
        labels.add(getString(R.string.menu_app_info));
        actions.add(() -> startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))));
        labels.add(getString(R.string.menu_uninstall));
        actions.add(() -> { if (!startSafely(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)))) toast(getString(R.string.open_failed, app.label)); });

        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(app.label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> { actions.get(which).run(); renderPreservingScroll(); })
                .show();
    }

    private void pickTileImage(String pkg) {
        tileImageTarget = pkg;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try { startActivityForResult(pick, REQUEST_TILE_IMAGE); }
        catch (RuntimeException e) { tileImageTarget = null; toast(getString(R.string.open_failed, "image picker")); }
    }

    // ------------------------------------------------------ Plex Home profiles

    void choosePlexProfile() {
        toast(getString(R.string.plex_profile_loading));
        io.execute(() -> {
            try {
                List<PlexClient.HomeUser> users = plex.homeUsers();
                main.post(() -> {
                    if (users.size() < 2) { toast(getString(R.string.plex_profile_single)); return; }
                    String[] names = new String[users.size()];
                    for (int i = 0; i < names.length; i++) {
                        PlexClient.HomeUser u = users.get(i);
                        names[i] = u.title + (u.needsPin ? getString(R.string.plex_profile_pin_suffix) : "");
                    }
                    new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                            .setTitle(R.string.plex_profile_title)
                            .setItems(names, (d, which) -> {
                                PlexClient.HomeUser u = users.get(which);
                                if (u.needsPin) askPlexPin(u); else switchPlexProfile(u, null);
                            }).show();
                });
            } catch (IOException e) {
                final String reason = CatalogRepository.describe(e);
                main.post(() -> toast(getString(R.string.plex_failed, reason)));
            }
        });
    }

    private void askPlexPin(PlexClient.HomeUser user) {
        EditText pin = new EditText(this);
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setSingleLine(true);
        keyboardOnSelect(pin);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(pin);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(getString(R.string.plex_profile_pin_title, user.title))
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> switchPlexProfile(user, pin.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .create();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        pin.requestFocus();
    }

    private void switchPlexProfile(PlexClient.HomeUser user, String pin) {
        io.execute(() -> {
            try {
                String token = plex.switchUser(user, pin);
                prefs.setPlexProfile(token, user.title);
                try { plex.refreshServer(); } catch (IOException | JSONException ignored) { /* refreshPlex retries and reports */ }
                main.post(() -> {
                    toast(getString(R.string.plex_profile_switched, user.title));
                    plexContinue = Collections.emptyList();
                    plexRecent = Collections.emptyList();
                    refreshPlex();
                    renderPreservingScroll();
                });
            } catch (Http.StatusException e) {
                final boolean denied = e.code == 401 || e.code == 403;
                main.post(() -> toast(denied ? getString(R.string.plex_profile_wrong_pin) : getString(R.string.plex_failed, "HTTP " + e.code)));
            } catch (IOException e) {
                final String reason = CatalogRepository.describe(e);
                main.post(() -> toast(getString(R.string.plex_failed, reason)));
            }
        });
    }

    /** Explains the experimental home replacement and opens the system screen that turns it on. */
    void explainHomeKeeper() {
        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.keeper_title)
                .setMessage(getString(isFireTv() ? R.string.keeper_body_fire : R.string.keeper_body, getPackageName() + "/" + HomeKeeperService.class.getName()))
                .setPositiveButton(R.string.keeper_open_settings, (d, w) -> {
                    if (!startSafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) toast(getString(R.string.keeper_no_settings));
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** "Tune the hero": two or three words, e.g. "asian romance" or "latest spanish". */
    void tuneHero() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding((int) (24 * d), (int) (4 * d), (int) (24 * d), 0);
        TextView help = new TextView(this);
        help.setText(R.string.mood_help);
        help.setTextColor(Ui.MUTED);
        help.setTextSize(13);
        form.addView(help);
        EditText words = new EditText(this);
        words.setSingleLine(true);
        words.setHint(R.string.mood_hint);
        words.setText(prefs.heroMood());
        words.setImeOptions(EditorInfo.IME_ACTION_DONE);
        keyboardOnSelect(words);
        form.addView(words);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.mood_title)
                .setView(form)
                .setPositiveButton(R.string.mood_show, null)
                .setNeutralButton(R.string.mood_reset, (dlg, w) -> { prefs.setHeroMood(""); refreshMood(); })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = words.getText().toString().trim();
            HeroMood.Query q = HeroMood.parse(text);
            if (text.isEmpty() || q.isEmpty()) { words.setError(getString(R.string.mood_not_understood)); return; }
            prefs.setHeroMood(text);
            if (!q.unknown.isEmpty()) toast(getString(R.string.mood_ignored, TextUtils.join(", ", q.unknown)));
            dialog.dismiss();
            refreshMood();
            renderPreservingScroll();
        }));
        // Enter / Done on the keyboard submits, so a remote user never has to reach the button.
        words.setOnEditorActionListener((v, action, e) -> { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return true; });
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        words.requestFocus();
    }

    /** Advanced: Trakt Client ID + public list URLs. Casual users never need this. */
    /** The optional TMDB key: checked against TMDB before it's kept, so a typo shows up straight away. */
    void editTmdb(Runnable done) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);
        TextView help = new TextView(this);
        help.setText(R.string.tmdb_help);
        help.setTextColor(Ui.MUTED);
        help.setTextSize(13);
        form.addView(help);
        EditText key = new EditText(this);
        key.setHint(R.string.tmdb_key_hint);
        key.setSingleLine(true);
        key.setText(prefs.tmdbKey());
        keyboardOnSelect(key);
        form.addView(key);
        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.tmdb_title)
                .setView(form)
                .setPositiveButton(R.string.save, (dlg, w) -> {
                    final String typed = key.getText().toString().trim();
                    if (typed.isEmpty()) { prefs.setTmdbKey(""); if (done != null) done.run(); return; }
                    toast(getString(R.string.tmdb_checking));
                    io.execute(() -> {
                        boolean ok;
                        try { Http.get("https://api.themoviedb.org/3/configuration?api_key=" + Uri.encode(typed)); ok = true; }
                        catch (IOException e) { ok = false; }
                        final boolean good = ok;
                        main.post(() -> {
                            if (good) { prefs.setTmdbKey(typed); toast(getString(R.string.tmdb_saved)); }
                            else toast(getString(R.string.tmdb_rejected));
                            if (done != null) done.run();
                        });
                    });
                })
                .setNeutralButton(R.string.trakt_turn_off, (dlg, w) -> { prefs.setTmdbKey(""); if (done != null) done.run(); })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    void editTrakt() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);
        TextView help = new TextView(this);
        help.setText(R.string.trakt_help);
        help.setTextColor(Ui.MUTED);
        help.setTextSize(13);
        form.addView(help);
        EditText clientId = new EditText(this);
        clientId.setHint(R.string.trakt_client_hint);
        clientId.setSingleLine(true);
        clientId.setText(prefs.traktClientId());
        keyboardOnSelect(clientId);
        form.addView(clientId);
        EditText lists = new EditText(this);
        lists.setHint(R.string.trakt_lists_hint);
        lists.setMinLines(3);
        lists.setGravity(Gravity.TOP | Gravity.START);
        lists.setText(prefs.traktLists());
        keyboardOnSelect(lists);
        form.addView(lists);
        EditText user = new EditText(this);
        user.setHint(R.string.trakt_user_hint);
        user.setSingleLine(true);
        user.setText(prefs.traktUser());
        keyboardOnSelect(user);
        form.addView(user);

        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.trakt_title)
                .setView(form)
                .setPositiveButton(R.string.save, (dlg, w) -> {
                    prefs.saveTrakt(clientId.getText().toString(), lists.getText().toString());
                    prefs.setTraktUser(user.getText().toString());
                    int valid = trakt.refs().size();
                    if (!prefs.traktLists().isEmpty() && valid == 0) toast(getString(R.string.trakt_no_valid_urls));
                    refreshTrakt();
                    refreshHistory();
                })
                .setNeutralButton(R.string.trakt_turn_off, (dlg, w) -> {
                    prefs.saveTrakt("", "");
                    prefs.setTraktUser("");
                    if (!trakt.signedIn()) { traktHistory = Collections.emptyList(); traktWatchlist = Collections.emptyList(); }
                    refreshTrakt();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Trakt sign-in, Nuvio's way: a short code to enter at trakt.tv/activate on any phone or
     * computer. The app checks back by itself and closes the box once the user says yes.
     * Pressing Back keeps the code alive until it expires; opening this again shows the same one.
     */
    void signInTrakt() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);
        TextView help = new TextView(this);
        help.setText(R.string.trakt_signin_help);
        help.setTextColor(Ui.TEXT_DIM);
        help.setTextSize(15);
        form.addView(help);
        TextView code = new TextView(this);
        code.setText(R.string.plex_link_waiting);
        code.setTextColor(0xFFED1C24); // Trakt red
        code.setTextSize(20);
        code.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        code.setGravity(Gravity.CENTER);
        code.setPadding(0, (int) (16 * d), 0, (int) (12 * d));
        form.addView(code, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this);
        note.setText(R.string.trakt_signin_note);
        note.setTextColor(Ui.MUTED);
        note.setTextSize(13);
        form.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.trakt_signin_title)
                .setView(form)
                .setPositiveButton(R.string.plex_open_link, null)
                .setNegativeButton(R.string.plex_cancel_link, (dlg, w) -> traktCode = null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                TraktClient.DeviceCode c = traktCode;
                String url = c == null ? TraktClient.ACTIVATE_URL : TraktClient.ACTIVATE_URL + "/" + c.user;
                if (!openUrl(url, null)) toast(getString(R.string.plex_no_browser));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        });
        dialog.show();

        Runnable showCode = () -> { if (traktCode == null) return; code.setText(traktCode.user); code.setTextSize(40); code.setLetterSpacing(0.2f); };
        if (traktCode != null && System.currentTimeMillis() < traktCode.expiresAt) { showCode.run(); return; } // already being checked
        new Thread(() -> {
            TraktClient.DeviceCode c;
            try { c = trakt.startSignIn(); }
            catch (IOException | JSONException e) {
                final String reason = CatalogRepository.describe(e);
                main.post(() -> { dialog.dismiss(); toast(getString(R.string.trakt_signin_failed, reason)); });
                return;
            }
            traktCode = c;
            main.post(showCode);
            try {
                while (traktCode == c) {
                    Thread.sleep(c.intervalSec * 1000L);
                    if (traktCode != c) return; // cancelled
                    boolean done;
                    try { done = trakt.pollSignIn(c); }
                    catch (TraktClient.SignInEnded e) { throw e; }
                    catch (IOException | JSONException e) { continue; } // network blip: keep checking
                    if (!done) continue;
                    traktCode = null;
                    main.post(() -> {
                        if (dialog.isShowing()) dialog.dismiss();
                        String name = prefs.traktAccount();
                        toast(getString(R.string.trakt_signed_in, name.isEmpty() ? "Trakt" : name));
                        refreshHistory();
                        if (overlay instanceof SettingsScreen) overlay.rebuild();
                    });
                    return;
                }
            } catch (TraktClient.SignInEnded e) {
                traktCode = null;
                main.post(() -> { if (dialog.isShowing()) dialog.dismiss(); toast(getString(R.string.trakt_signin_expired)); });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "phonetv-trakt-signin").start();
    }

    void signOutTrakt() {
        showNotice(getString(R.string.trakt_signout_title), getString(R.string.trakt_signout_body),
                new String[]{getString(R.string.trakt_signout), getString(R.string.cancel)},
                new Runnable[]{() -> {
                    io.execute(trakt::signOut);
                    traktHistory = Collections.emptyList();
                    traktWatchlist = Collections.emptyList();
                    prefs.clearTraktSignIn(); // at once, so the screens below redraw signed out
                    if (overlay instanceof SettingsScreen) overlay.rebuild();
                    if (tab == TAB_LIBRARY) renderPreservingScroll();
                }, null});
    }

    /**
     * Plex PIN link flow. Shows a large code with Copy and Open-link buttons and
     * polls plex.tv until the user approves it. The PIN and its code are
     * persisted and polled on their own thread, so pressing Back (or leaving the
     * app to enter the code) doesn't lose the session: reopening Connect Plex
     * shows the same code until it's approved, cancelled or expires.
     */
    void linkPlex() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);
        TextView help = new TextView(this);
        help.setText(R.string.plex_link_help);
        help.setTextColor(Ui.TEXT_DIM);
        help.setTextSize(15);
        form.addView(help);
        TextView code = new TextView(this);
        code.setText(R.string.plex_link_waiting);
        code.setTextColor(0xFFE5A00D);
        code.setTextSize(20);
        code.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        code.setGravity(Gravity.CENTER);
        code.setPadding(0, (int) (16 * d), 0, (int) (12 * d));
        form.addView(code, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this);
        note.setText(R.string.plex_link_note);
        note.setTextColor(Ui.MUTED);
        note.setTextSize(13);
        form.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.plex_link_title)
                .setView(form)
                .setPositiveButton(R.string.plex_copy_code, null)
                .setNeutralButton(R.string.plex_open_link, null)
                .setNegativeButton(R.string.plex_cancel_link, (dlg, w) -> { prefs.clearPlexPendingPin(); plexLinking = false; })
                .create();
        dialog.setOnShowListener(x -> {
            // Custom listeners so Copy and Open don't close the dialog.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String pending = prefs.plexPendingCode();
                if (pending.isEmpty()) return;
                android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb != null) cb.setPrimaryClip(android.content.ClipData.newPlainText("Plex code", pending));
                toast(getString(R.string.plex_code_copied, pending));
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (!openUrl(PlexClient.LINK_URL, null)) toast(getString(R.string.plex_no_browser));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        });
        dialog.setOnCancelListener(x -> { if (prefs.plexPendingPin() != 0) toast(getString(R.string.plex_still_waiting)); });
        plexDialog = dialog;
        dialog.show();

        Runnable showCode = () -> { code.setText(prefs.plexPendingCode()); code.setTextSize(44); code.setLetterSpacing(0.3f); };
        long pending = prefs.plexPendingPin();
        if (pending != 0 && !prefs.plexPendingCode().isEmpty()) {
            // A link is already in progress: show the same code instead of starting over.
            showCode.run();
            if (!plexLinking) pollPlexPin(pending, dialog);
            return;
        }
        new Thread(() -> {
            try {
                PlexClient.Pin pin = plex.createPin();
                prefs.setPlexPendingPin(pin.id, pin.code, System.currentTimeMillis() + 10 * 60_000);
                main.post(showCode);
                pollPlexPin(pin.id, dialog);
            } catch (IOException | JSONException e) {
                final String reason = CatalogRepository.describe(e);
                main.post(() -> { dialog.dismiss(); toast(getString(R.string.plex_failed, reason)); });
            }
        }, "phonetv-plex-pin").start();
    }

    /** Polls a pending PIN until approved, cancelled or expired. Safe to call from any thread. */
    private void pollPlexPin(long pinId, AlertDialog dialog) {
        if (Looper.myLooper() == Looper.getMainLooper()) { new Thread(() -> pollPlexPin(pinId, dialog), "phonetv-plex-pin").start(); return; }
        plexLinking = true;
        try {
            String token = null;
            while (token == null && prefs.plexPendingPin() == pinId) {
                Thread.sleep(PLEX_POLL_MS);
                try { token = plex.pollPin(pinId); }
                catch (IOException | JSONException e) { /* transient network error: keep polling */ }
            }
            if (token == null) return; // cancelled or expired
            prefs.savePlexAccount(token);
            prefs.clearPlexPendingPin();
            PlexClient.Server server = null;
            String error = null;
            try { server = plex.refreshServer(); }
            catch (IOException | JSONException e) { error = CatalogRepository.describe(e); }
            final PlexClient.Server s = server;
            final String err = error;
            main.post(() -> {
                AlertDialog open = plexDialog; // may be a newer dialog than the one that started polling
                if (open != null && open.isShowing()) open.dismiss();
                if (s == null) toast(err != null ? getString(R.string.plex_failed, err) : getString(R.string.plex_no_server));
                else toast(getString(s.reachable ? R.string.plex_connected : R.string.plex_connected_offline, s.name));
                refreshPlex();
                renderPreservingScroll();
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            plexLinking = false;
        }
    }

    // ==================================================================== actions

    static final String[] PLAYERS = {Prefs.PLAY_HERE, Prefs.PLAY_STREMIO, Prefs.PLAY_NUVIO, Prefs.PLAY_PLEX};

    /** Brand name of a player app; these aren't translated. */
    static String playerName(String player) {
        switch (player) {
            case Prefs.PLAY_NUVIO: return "Nuvio";
            case Prefs.PLAY_PLEX: return "Plex";
            case Prefs.PLAY_HERE: return "Phone TV";
            default: return "Stremio";
        }
    }

    /** Package of the installed app behind a player, or null when Phone TV can't find one. */
    String playerPackage(String player) {
        switch (player) {
            case Prefs.PLAY_PLEX: return isInstalled(PlexClient.PACKAGE) ? PlexClient.PACKAGE : null;
            // Phone TV's own player counts as "installed" once an add-on that finds streams is.
            case Prefs.PLAY_HERE: return hasStreamAddon() ? getPackageName() : null;
            case Prefs.PLAY_NUVIO: return handlerOf("nuvio://meta", "nuvio", NUVIO_PACKAGES);
            default: return handlerOf("stremio:///detail", "stremio", STREMIO_PACKAGE);
        }
    }

    private boolean isInstalled(String pkg) {
        if (apps.byPackage(pkg) != null) return true;
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }

    /**
     * Finds an app by its known package names, then by asking who answers its link
     * scheme. The second step covers renamed builds (beta, TV, store flavors).
     */
    private String handlerOf(String link, String nameHint, String... knownPackages) {
        for (String pkg : knownPackages) if (isInstalled(pkg)) return pkg;
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
        for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(probe, 0)) {
            String pkg = r.activityInfo == null ? null : r.activityInfo.packageName;
            if (pkg != null && pkg.contains(nameHint)) return pkg;
        }
        return null;
    }

    /** "Watch": Plex items play; otherwise the user's chosen app, or the details page to choose. */
    void play(MediaItem m) {
        if (m.source == MediaItem.Source.PLEX) { openPlexItem(m); return; }
        String with = prefs.playWith();
        if (Prefs.PLAY_ASK.equals(with)) showDetails(m); else openWith(with, m, null);
    }

    /**
     * Hands a title (or one episode of a show) to an app. If the app isn't on the
     * device this says so instead of doing nothing.
     */
    boolean hasStreamAddon() {
        for (Addons.Addon a : Addons.of(this).all()) if (a.enabled && a.resources.contains("stream")) return true;
        return false;
    }

    void openWith(String player, MediaItem m, String episodeId) {
        if (Prefs.PLAY_HERE.equals(player)) { showStreams(m, episodeId); return; }
        String pkg = playerPackage(player);
        if (pkg == null) { showMissingApp(player, m); return; }
        String type = m.isShow() ? "series" : "movie";
        boolean opened;
        switch (player) {
            case Prefs.PLAY_PLEX:
                // Plex has no public "open by IMDb id" link, so hand the title to its search.
                opened = startSafely(new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, m.title));
                break;
            case Prefs.PLAY_NUVIO:
                // Nuvio's links only reach a title's page. For one episode, Nuvio TV takes the same
                // extras its own Continue watching row uses, which open that episode's sources.
                opened = (episodeId != null && "com.nuvio.tv".equals(pkg) && openNuvioEpisode(pkg, m, episodeId))
                        || openUrl("nuvio://meta?type=" + type + "&id=" + Uri.encode(m.id), pkg);
                break;
            default:
                // Pinned to Stremio's own package: other apps also register the stremio:// scheme.
                opened = openUrl("stremio:///detail/" + type + "/" + m.id + (episodeId == null ? "" : "/" + Uri.encode(episodeId)), pkg);
        }
        // Phone TV can't see what the player does next; opening a title is what counts as watching it.
        if (opened) { library.watched(m, Library.episodeLabel(episodeId), playerName(player)); libraryChanged(false); return; }
        // The link wasn't accepted (very old or unusual build): open the app and tell the user what to look for.
        AppRepository.App app = apps.byPackage(pkg);
        if (app != null && launch(app)) toast(getString(R.string.opening_search, playerName(player), m.title));
        else showMissingApp(player, m);
    }

    private boolean openNuvioEpisode(String pkg, MediaItem m, String episodeId) {
        Intent start = getPackageManager().getLaunchIntentForPackage(pkg);
        if (start == null) start = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (start == null) return false;
        String[] parts = episodeId.split(":");
        int season = -1, episode = -1;
        try {
            season = Integer.parseInt(parts[parts.length - 2]);
            episode = Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) { /* Nuvio reads them from the id too */ }
        start.putExtra("contentId", m.id).putExtra("contentType", "series").putExtra("launchMode", "stream")
                .putExtra("videoId", episodeId).putExtra("name", m.title)
                .putExtra("season", season).putExtra("episode", episode);
        String poster = m.poster(400), backdrop = m.backdrop(1280);
        if (poster != null) start.putExtra("poster", poster);
        if (backdrop != null) start.putExtra("backdrop", backdrop);
        // Already running: bring it forward and let it act on the new extras.
        start.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return startSafely(start);
    }

    void openPlexItem(MediaItem m) {
        boolean playable = "movie".equals(m.type) || "episode".equals(m.type);
        // Without the Plex app there's still a way to watch: straight from the server.
        if (playable && (prefs.plexPlaysHere() || !isInstalled(PlexClient.PACKAGE))) { playPlexHere(m); return; }
        openPlexApp(m);
    }

    /** Plays a Plex movie or episode in Phone TV's player, straight from the user's server. */
    private void playPlexHere(MediaItem m) {
        new Thread(() -> {
            try {
                PlexClient.Stream s = plex.stream(m);
                main.post(() -> {
                    Bundle server = new Bundle();
                    server.putString("base", s.base);
                    server.putString("token", s.token);
                    server.putString("client", s.clientId);
                    server.putString("key", s.ratingKey);
                    server.putString("fallback", s.transcoded);
                    plexInPlayer = m;
                    Intent i = new Intent(this, PlayerActivity.class)
                            .putExtra(PlayerActivity.X_URL, s.direct).putExtra(PlayerActivity.X_PLEX, server)
                            .putExtra(PlayerActivity.X_TYPE, "episode".equals(m.type) ? "series" : "movie")
                            .putExtra(PlayerActivity.X_TITLE, m.title).putExtra(PlayerActivity.X_LABEL, m.subtitle)
                            .putExtra(PlayerActivity.X_ADDON, "Plex").putExtra(PlayerActivity.X_RESUME, m.viewOffsetMs);
                    try {
                        Bundle onThisScreen = launchOptions();
                        if (onThisScreen != null) startActivityForResult(i, REQUEST_PLAYER, onThisScreen);
                        else startActivityForResult(i, REQUEST_PLAYER);
                    } catch (RuntimeException e) { toast(getString(R.string.player_failed)); }
                });
            } catch (IOException | JSONException e) {
                final String why = CatalogRepository.describe(e);
                main.post(() -> {
                    toast(getString(R.string.plex_play_failed, why));
                    if (isInstalled(PlexClient.PACKAGE)) openPlexApp(m);
                });
            }
        }, "phonetv-plex-play").start();
    }

    private MediaItem plexInPlayer; // the Plex title Phone TV's player was opened for, if any

    void openPlexApp(MediaItem m) {
        if (!isInstalled(PlexClient.PACKAGE)) { showMissingApp(Prefs.PLAY_PLEX, null); return; }
        Intent resume = new Intent(Intent.ACTION_VIEW, Uri.parse(PlexClient.playUri(m))).setPackage(PlexClient.PACKAGE)
                .putExtra("android.intent.extra.START_PLAYBACK", true); // still read by older Plex builds
        if (startSafely(resume)) return;
        if (openUrl(PlexClient.webUrl(m), PlexClient.PACKAGE)) return;
        AppRepository.App app = apps.byPackage(PlexClient.PACKAGE);
        if (app != null && launch(app)) toast(getString(R.string.opening_search, "Plex", m.title));
        else showMissingApp(Prefs.PLAY_PLEX, null);
    }

    /** Explains that an app isn't on the device, with a way to get it or pick another. */
    void showMissingApp(String player, MediaItem m) {
        String name = playerName(player);
        boolean canChoose = m != null && m.source != MediaItem.Source.PLEX && !(overlay instanceof DetailsScreen);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.missing_get, name));
        actions.add(() -> openStore(player));
        if (canChoose) { labels.add(getString(R.string.missing_choose)); actions.add(() -> showDetails(m)); }
        labels.add(getString(R.string.close));
        actions.add(null);
        showNotice(getString(R.string.missing_title, name), getString(R.string.missing_body, name),
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private void openStore(String player) {
        if (Prefs.PLAY_NUVIO.equals(player)) { if (!openUrl(NUVIO_URL, null)) toast(getString(R.string.no_store)); return; }
        String pkg = Prefs.PLAY_PLEX.equals(player) ? PlexClient.PACKAGE : STREMIO_PACKAGE;
        if (startSafely(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)))) return;
        if (!openUrl("https://play.google.com/store/apps/details?id=" + pkg, null)) toast(getString(R.string.no_store));
    }

    /**
     * Plays a trailer. With a known YouTube id the video opens directly; otherwise
     * we search. YouTube's TV apps don't take search URLs, so they get a search intent.
     */
    void openTrailer(MediaItem m, String youtubeId) {
        if (youtubeId != null && !youtubeId.isEmpty() && openUrl("https://www.youtube.com/watch?v=" + youtubeId, null)) return;
        String query = m.title + " " + m.year + " trailer";
        for (String pkg : new String[]{"com.google.android.youtube.tv", "com.amazon.firetv.youtube"}) {
            if (apps.byPackage(pkg) == null) continue;
            if (startSafely(new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query))) return;
        }
        if (openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(query), null)) return;
        showNotice(getString(R.string.no_trailer_title), getString(R.string.no_trailer_app),
                new String[]{getString(R.string.close)}, new Runnable[]{null});
    }

    /** Opens a URL, optionally pinned to one app. @return false if nothing could handle it. */
    boolean openUrl(String url, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (pkg != null) i.setPackage(pkg);
        return startSafely(i);
    }

    /** Opens an installed app. @return false if it couldn't be started. */
    private boolean launch(AppRepository.App app) { return startSafely(apps.launchIntent(app)); }

    boolean startSafely(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle onThisScreen = launchOptions();
        if (onThisScreen != null) {
            try { startActivity(i, onThisScreen); return true; }
            catch (RuntimeException refused) { /* this app won't open on the TV; let Android place it */ }
        }
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ================================================================ built-in player

    /**
     * The sources for a movie or episode from the user's add-ons. With none installed,
     * says so and offers the add-on settings instead of an empty list.
     */
    void showStreams(MediaItem m, String episodeId) {
        if (!hasStreamAddon()) {
            showNotice(getString(R.string.sources_no_addons_title), getString(R.string.sources_no_addons_body),
                    new String[]{getString(R.string.sources_open_addons), getString(R.string.cancel)},
                    new Runnable[]{() -> showSettingsAt(SettingsScreen.ADDONS), null});
            return;
        }
        String label = Library.episodeLabel(episodeId);
        String next = null, nextLabel = null;
        CatalogRepository.Details d = catalog.cachedDetails(m);
        if (episodeId != null && d != null) {
            CatalogRepository.Episode after = nextEpisode(d, episodeId);
            if (after != null) { next = after.id; nextLabel = Library.episodeLabel(after.season, after.number); }
            for (CatalogRepository.Episode e : d.episodes) if (e.id.equals(episodeId) && !e.title.isEmpty()) label += "  ·  " + e.title;
        }
        openOverlay(new StreamScreen(this, m, episodeId, label, next, nextLabel));
    }

    /** The episode after this one, in viewing order, skipping specials; null at the end. */
    static CatalogRepository.Episode nextEpisode(CatalogRepository.Details d, String episodeId) {
        List<CatalogRepository.Episode> order = new ArrayList<>();
        for (int season : d.seasons()) if (season != 0) order.addAll(d.season(season));
        for (int i = 0; i < order.size() - 1; i++) if (order.get(i).id.equals(episodeId)) return order.get(i + 1);
        return null;
    }

    void startPlayer(MediaItem m, String episodeId, String label, String url, Map<String, String> headers, String addon,
                     long resumeMs, String nextEpisodeId, String nextLabel) {
        Bundle h = new Bundle();
        for (Map.Entry<String, String> e : headers.entrySet()) h.putString(e.getKey(), e.getValue());
        Intent i = new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.X_URL, url).putExtra(PlayerActivity.X_HEADERS, h)
                .putExtra(PlayerActivity.X_ID, m.id).putExtra(PlayerActivity.X_TYPE, m.isShow() ? "series" : "movie")
                .putExtra(PlayerActivity.X_EPISODE, episodeId).putExtra(PlayerActivity.X_TITLE, m.title)
                .putExtra(PlayerActivity.X_LABEL, label).putExtra(PlayerActivity.X_ADDON, addon)
                .putExtra(PlayerActivity.X_POSTER, m.posterRef).putExtra(PlayerActivity.X_BACKDROP, m.backdropRef)
                .putExtra(PlayerActivity.X_LOGO, m.logoRef).putExtra(PlayerActivity.X_RESUME, resumeMs)
                .putExtra(PlayerActivity.X_NEXT_EPISODE, nextEpisodeId).putExtra(PlayerActivity.X_NEXT_LABEL, nextLabel);
        try {
            Bundle onThisScreen = launchOptions();
            if (onThisScreen != null) startActivityForResult(i, REQUEST_PLAYER, onThisScreen);
            else startActivityForResult(i, REQUEST_PLAYER);
        } catch (RuntimeException e) {
            toast(getString(R.string.player_failed));
        }
    }

    /** A stream link handed to another video app (VLC, MX Player, Just Player…). */
    void playElsewhere(String url, String title) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", title);
        if (!startSafely(Intent.createChooser(view, getString(R.string.player_open_elsewhere)))) toast(getString(R.string.player_no_other));
    }

    /** Settings, opened on one section. */
    void showSettingsAt(int section) { openOverlay(new SettingsScreen(this, section)); }

    // ===================================================================== add-ons

    /** One link, or many at once (one per line, or separated by spaces): handy for a list copied from Nuvio. */
    void askAddonLinks() {
        EditText box = new EditText(this);
        box.setHint(R.string.addon_link_hint);
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.setMinLines(2);
        keyboardOnSelect(box);
        AlertDialog d = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.set_addon_add)
                .setView(dialogForm(dialogText(getString(R.string.addon_link_help)), box))
                .setPositiveButton(R.string.addon_install, null)
                .setNegativeButton(R.string.cancel, null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> links = new ArrayList<>();
            for (String part : box.getText().toString().split("[\\s,]+")) if (part.contains(".") || part.startsWith("stremio://")) links.add(part);
            if (links.isEmpty()) { box.setError(getString(R.string.addon_link_hint)); return; }
            d.dismiss();
            installAddons(links);
        }));
        d.show();
    }

    /**
     * Installs a list of add-on links, reporting as it goes. Anything that didn't install is
     * named with the reason, so nothing fails silently.
     */
    void installAddons(List<String> links) {
        final TextView body = dialogText(getString(R.string.addon_installing, links.size()));
        final AlertDialog d = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.set_addons).setView(dialogForm(body))
                .setPositiveButton(R.string.close, null).create();
        d.show();
        new Thread(() -> {
            final List<String> added = new ArrayList<>(), failed = new ArrayList<>();
            for (int i = 0; i < links.size(); i++) {
                String link = links.get(i);
                final int n = i + 1;
                main.post(() -> body.setText(getString(R.string.addon_progress, n, links.size())));
                try { added.add(Addons.of(this).install(link).name); }
                catch (IOException | org.json.JSONException | RuntimeException e) {
                    String host = Uri.parse(link.replace("stremio://", "https://")).getHost();
                    failed.add((host == null ? link : host) + ": " + CatalogRepository.describe(e instanceof Exception ? (Exception) e : new IOException()));
                }
            }
            main.post(() -> {
                StringBuilder report = new StringBuilder();
                if (!added.isEmpty()) report.append(getString(R.string.addon_added_list, added.size(), TextUtils.join(", ", added)));
                if (!failed.isEmpty()) {
                    if (report.length() > 0) report.append("\n\n");
                    report.append(getString(R.string.addon_failed_list, failed.size(), TextUtils.join("\n", failed)));
                }
                body.setText(report);
                if (overlay instanceof SettingsScreen) overlay.rebuild();
                refreshAddonRows();
            });
        }, "phonetv-addons").start();
    }

    void manageAddon(Addons.Addon addon, Runnable changed) {
        String[] labels = {getString(addon.enabled ? R.string.addon_turn_off : R.string.addon_turn_on), getString(R.string.addon_move_up),
                getString(R.string.addon_move_down), getString(R.string.addon_remove)};
        Runnable[] acts = {
                () -> { Addons.of(this).setEnabled(addon, !addon.enabled); changed.run(); refreshAddonRows(); },
                () -> { Addons.of(this).move(addon, -1); changed.run(); },
                () -> { Addons.of(this).move(addon, 1); changed.run(); },
                () -> { Addons.of(this).remove(addon); changed.run(); refreshAddonRows(); }};
        showNotice(addon.name, addon.description.isEmpty() ? addon.base : addon.description, labels, acts);
    }

    /**
     * Brings over every add-on on a Stremio account. Stremio's own link sign-in: a code to
     * enter at link.stremio.com on a phone or computer that's signed in, so Phone TV never
     * sees the Stremio password. Only the add-on list is read.
     */
    void importStremioAddons() {
        final TextView body = dialogText(getString(R.string.stremio_link_help));
        final TextView code = dialogCode("…");
        // A QR code for the phone's camera: scanning it opens Stremio's page with the code filled in.
        final ImageView qr = new Art(this);
        qr.setBackgroundColor(0xFFFFFFFF);
        qr.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        qr.setVisibility(View.GONE);
        LinearLayout qrRow = new LinearLayout(this);
        qrRow.setGravity(Gravity.CENTER);
        qrRow.addView(qr, new LinearLayout.LayoutParams(ui.dp(168), ui.dp(168)));
        final AlertDialog d = new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.set_addon_stremio).setView(dialogForm(body, qrRow, code))
                .setPositiveButton(R.string.stremio_open_link, null)
                .setNegativeButton(R.string.cancel, null).create();
        final String[] link = {null};
        final boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (link[0] != null && !openUrl(link[0], null)) toast(getString(R.string.stremio_no_browser));
        }));
        d.show();
        // Its own thread: the wait can last minutes and must not hold up anything else the app loads.
        new Thread(() -> {
            String stage = getString(R.string.stremio_stage_code);
            try {
                StremioLink.Code c = StremioLink.create();
                link[0] = c.link;
                main.post(() -> {
                    code.setText(c.code);
                    if (c.qr != null) images.load(qr, c.qr, ui.dp(168), true, () -> qr.setVisibility(View.VISIBLE));
                });
                stage = getString(R.string.stremio_stage_wait);
                String token = null;
                long until = System.currentTimeMillis() + 10 * 60_000L;
                while (open[0] && System.currentTimeMillis() < until && token == null) {
                    Thread.sleep(2_500);
                    try { token = StremioLink.read(c.code); }
                    catch (IOException | org.json.JSONException hiccup) { /* a dropped request: ask again */ }
                }
                if (!open[0]) return;
                if (token == null) throw new IOException(getString(R.string.stremio_link_expired));
                main.post(() -> { qr.setVisibility(View.GONE); code.setText("✓"); body.setText(R.string.stremio_connected); });
                stage = getString(R.string.stremio_stage_signin);
                String session;
                try { session = StremioLink.session(token); }
                catch (IOException | org.json.JSONException refused) { session = token; } // some accounts hand back a ready session
                stage = getString(R.string.stremio_stage_list);
                final List<String> links;
                try { links = StremioLink.addonLinks(session); }
                finally { StremioLink.logout(session); }
                main.post(() -> {
                    if (links.isEmpty()) {
                        code.setVisibility(View.GONE);
                        body.setText(R.string.stremio_no_addons);
                        d.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
                        d.getButton(AlertDialog.BUTTON_NEGATIVE).setText(R.string.close);
                    } else {
                        d.dismiss();
                        installAddons(links);
                    }
                });
            } catch (IOException | org.json.JSONException e) {
                // Stay on screen and say which step failed and why, so it can be reported or retried.
                final String at = stage, why = String.valueOf(e.getMessage());
                main.post(() -> {
                    if (!open[0]) return;
                    code.setVisibility(View.GONE);
                    body.setText(getString(R.string.stremio_failed, at, why));
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
                    d.getButton(AlertDialog.BUTTON_NEGATIVE).setText(R.string.close);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "phonetv-stremio").start();
    }

    /** Add-on catalogs appear as rows; changing add-ons redraws the screen once the rows are fetched again. */
    void refreshAddonRows() {
        addonRows.clear();
        loadAddonRows();
    }
}
