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
import org.json.JSONException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private static final int TAB_FOR_YOU = 0, TAB_MOVIES = 1, TAB_SHOWS = 2, TAB_APPS = 3;
    private static final int[] TAB_LABELS = {R.string.tab_for_you, R.string.tab_movies, R.string.tab_shows, R.string.tab_apps};

    private static final long HERO_INTERVAL_MS = 9_000;
    private static final long CONTENT_TTL_MS = 3 * 60 * 60 * 1000L;
    private static final long PLEX_POLL_MS = 2_000;
    private static final int REQUEST_TILE_IMAGE = 41;

    // Rows on "For you" that can be switched off in Settings. Keys are stored; don't rename.
    static final String ROW_PLEX_CONTINUE = "plexContinue", ROW_PLEX_RECENT = "plexRecent",
            ROW_TOP_PICKS = "topPicks", ROW_SHOWS = "popularShows", ROW_MOVIES = "popularMovies", ROW_TOP_RATED = "topRated";

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
    private final java.util.Map<String, View> focusTargets = new java.util.HashMap<>();
    private String restoreFocusKey;
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
    private Overlay overlay;
    private String overlayReturnKey;
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
        prefs = new Prefs(this);
        ui = new Ui(this, prefs.scale());
        images = new ImageLoader(this);
        tvDevice = isTvDevice();
        if (tvDevice) prefs.setDefaultSizeIndex(2);
        applyLook();
        ui.onFocus = this::reveal;
        apps = new AppRepository(this, prefs, tvDevice);
        tileArt = new TileArt(this, ui);
        catalog = new CatalogRepository(this, io);
        plex = new PlexClient(prefs);
        trakt = new TraktClient(this, prefs);
        heroMood = new HeroMood(this, new HeroMood.WikidataSource());
        updater = new Updater(this);

        try { HttpResponseCache.install(new File(getCacheDir(), "http"), 20L * 1024 * 1024); }
        catch (IOException ignored) { /* runs fine uncached */ }

        setupWindow();
        apps.reload();
        apps.seedFavorites(STREMIO_PACKAGE, TVMATE_HINT, PlexClient.PACKAGE, "netflix", "youtube", "primevideo", "disney", "kodi");
        catalog.loadCached();
        render();
        refreshContent();
        checkForUpdate(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemBars();
        if (!plex.isLinked() && prefs.plexPendingPin() != 0 && !plexLinking) pollPlexPin(prefs.plexPendingPin(), null);
        boolean appsChanged = apps.reload();
        if (catalog.isOlderThan(CONTENT_TTL_MS)) refreshContent();
        if (updater.due()) checkForUpdate(false);
        if (appsChanged) renderPreservingScroll();
        else restartHeroTimer();
    }

    @Override protected void onPause() { super.onPause(); main.removeCallbacks(heroAdvance); }

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
        images.shutdown();
        io.shutdownNow();
    }

    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) applySystemBars(); }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        render();
        if (overlay != null) overlay.rebuild();
    }

    /** Home pressed while already home: go back to the top of "For you", like a TV. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) { onInstallStatus(intent); return; }
        goHome();
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
        if (atTop && !isDefaultHome() && !homeKeeperEnabled()) { moveTaskToBack(true); return; }
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
            if (p instanceof HorizontalScrollView) { group = (ViewGroup) p; break; }
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
                if (group == null && p instanceof HorizontalScrollView) group = (ViewGroup) p;
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

    private void goHome() {
        closeOverlay();
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
            if (t != insetTop || b != insetBottom || l != insetLeft || r != insetRight) {
                insetTop = t; insetBottom = b; insetLeft = l; insetRight = r;
                // Rebuild after the insets pass finishes rather than mutating the tree mid-dispatch.
                main.post(() -> { render(); if (overlay != null) overlay.rebuild(); });
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
    }

    // ==================================================================== updates

    /**
     * Checks the published manifest; a newer build is downloaded and verified in
     * the background so the "Update ready" card installs with a single tap.
     */
    void checkForUpdate(boolean manual) {
        io.execute(() -> {
            try {
                Updater.Release r = updater.check();
                if (r == null) { if (manual) main.post(() -> toast(getString(R.string.update_current, BuildConfig.VERSION_NAME))); return; }
                java.io.File apk = updater.download(r);
                main.post(() -> { readyUpdate = r; readyApk = apk; renderPreservingScroll(); });
            } catch (IOException | JSONException e) {
                if (manual) { final String reason = CatalogRepository.describe(e); main.post(() -> toast(getString(R.string.update_failed, reason))); }
            }
        });
    }

    void installUpdate() {
        if (readyApk == null) return;
        if (!updater.canInstall()) {
            // One-time permission: Android asks the user to allow this app to install updates.
            // Fire TV has no per-app screen for this; it lives under My Fire TV > Developer options.
            boolean opened = Build.VERSION.SDK_INT >= 26
                    && startSafely(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            toast(getString(opened ? R.string.update_allow : R.string.update_allow_tv));
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

    void refreshPlex() {
        if (!plex.isLinked()) return;
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
        images.clearDeferred();
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
            heroOnScreen = y < metrics().heightPixels / 2;
            images.flush();
        });

        if (tab == TAB_APPS) {
            column.setPadding(0, insetTop + ui.dp(62), 0, insetBottom + ui.dp(24));
            buildAppsTab(column);
        } else {
            buildMediaTab(column);
        }
        topBar = buildTopBar();
        home.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        View target = restoreFocusKey == null ? null : focusTargets.get(restoreFocusKey);
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
            if (plex.isLinked()) {
                if (prefs.rowShown(ROW_PLEX_CONTINUE)) backdropRow(column, getString(R.string.row_plex_continue), plexContinue);
                if (prefs.rowShown(ROW_PLEX_RECENT)) posterRow(column, getString(R.string.row_plex_recent), plexRecent);
                if (plexError != null) column.addView(noticeCard(getString(R.string.plex_status_title),
                        getString(R.string.plex_status_body, plexError), this::refreshPlex));
            } else if (apps.byPackage(PlexClient.PACKAGE) != null && !prefs.plexPromptDismissed()) {
                column.addView(plexPrompt());
            }
            for (TraktClient.TraktRow row : traktRows) posterRow(column, row.title, row.items);
            if (!traktErrors.isEmpty()) column.addView(noticeCard(getString(R.string.trakt_status_title),
                    TextUtils.join("\n", traktErrors), this::refreshTrakt));
        }

        if (catalog.isEmpty()) {
            column.addView(statusCard());
        } else if (tab == TAB_FOR_YOU) {
            if (prefs.rowShown(ROW_TOP_PICKS)) backdropRow(column, getString(R.string.row_top_picks), slice(movies, 4, 20));
            if (prefs.rowShown(ROW_SHOWS)) posterRow(column, getString(R.string.row_popular_shows), slice(shows, 4, 40));
            if (prefs.rowShown(ROW_MOVIES)) posterRow(column, getString(R.string.row_popular_movies), slice(movies, 20, 48));
            if (prefs.rowShown(ROW_TOP_RATED)) posterRow(column, getString(R.string.row_top_rated), rated);
        } else if (tab == TAB_MOVIES) {
            backdropRow(column, getString(R.string.row_trending_movies), slice(movies, 7, 20));
            posterRow(column, getString(R.string.row_popular_movies), slice(movies, 20, 48));
            posterRow(column, getString(R.string.row_top_rated), rated);
        } else {
            backdropRow(column, getString(R.string.row_trending_shows), slice(shows, 7, 20));
            posterRow(column, getString(R.string.row_popular_shows), slice(shows, 20, 50));
        }
        column.addView(footer());

        if (!heroItems.isEmpty()) { showHero(heroIndex); restartHeroTimer(); }
    }

    private static List<MediaItem> slice(List<MediaItem> l, int from, int to) {
        return from >= l.size() ? l : l.subList(from, Math.min(to, l.size()));
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

        bar.addView(focusKey(roundIcon(android.R.drawable.ic_menu_search, v -> { tab = TAB_APPS; render(); }), "top:search"),
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
            chip.setOnClickListener(v -> { if (tab != index) { tab = index; heroIndex = 0; render(); } });
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

        bar.addView(focusKey(roundIcon(android.R.drawable.ic_menu_preferences, v -> showSettings()), "top:settings"),
                new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        return bar;
    }

    private ImageView roundIcon(int res, View.OnClickListener onClick) {
        ImageView iv = new ImageView(this);
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
        FrameLayout art = new FrameLayout(this);
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
        tile.setOnClickListener(v -> { if (!apps.launch(app)) toast(getString(R.string.open_failed, app.label)); });
        tile.setOnLongClickListener(v -> { showAppMenu(app); return true; });
        return tile;
    }

    private TextView badge(String s) {
        TextView b = ui.label(s, 9, Ui.TEXT, Ui.BOLD);
        b.setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1));
        b.setBackground(ui.round(0x99000000, 6));
        return b;
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
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, ui.dp(44));
        slp.setMargins(g, ui.dp(6), insetRight + ui.dp(20), ui.dp(6));
        column.addView(search, slp);

        TextView hint = ui.label(getString(R.string.apps_hint), 11, Ui.MUTED, null);
        hint.setPadding(g, ui.dp(2), ui.dp(20), ui.dp(14));
        column.addView(hint);

        appGrid = new LinearLayout(this);
        appGrid.setOrientation(LinearLayout.VERTICAL);
        appGrid.setPadding(g - ui.dp(5), 0, insetRight + ui.dp(15), 0);
        column.addView(appGrid);
        renderAppGrid();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { appFilter = s.toString(); renderAppGrid(); }
            @Override public void afterTextChanged(Editable e) {}
        });
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
        ImageView art = new ImageView(this);
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
            ImageView art = new ImageView(this);
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

    // ============================================================ pages above home

    /** Opens the full-screen details page for a title. Back returns to where the user was. */
    void showDetails(MediaItem m) { openOverlay(new DetailsScreen(this, m)); }

    void showSettings() { openOverlay(new SettingsScreen(this)); }

    /**
     * Shows a page above the home screen. Home stays built underneath with its
     * scroll position, so closing the page lands exactly where the user left off.
     */
    private void openOverlay(Overlay page) {
        if (overlay != null) root.removeView(overlay.view());
        else {
            View focused = focused();
            Object key = focused == null ? null : focused.getTag(R.id.focus_key);
            overlayReturnKey = key instanceof String ? (String) key : null;
        }
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

    void closeOverlay() {
        if (overlay == null) return;
        final View v = overlay.view();
        overlay = null;
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        home.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (usingRemote()) {
            // Same tile as before; the home screen may have been rebuilt meanwhile, hence the lookup by name.
            View back = overlayReturnKey == null ? null : focusTargets.get(overlayReturnKey);
            if (back == null || !back.isShown()) back = focusTargets.get("hero:watch");
            if (back == null || !back.isShown()) back = focusTargets.get("tab:" + tab);
            if (back != null) back.requestFocus();
        }
        v.animate().alpha(0f).translationY(ui.dp(16)).setDuration(Motion.FAST).setInterpolator(Motion.STANDARD)
                .withEndAction(() -> root.removeView(v)).start();
        restartHeroTimer();
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

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.keeper_title)
                .setMessage(getString(R.string.keeper_body, getPackageName() + "/" + HomeKeeperService.class.getName()))
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

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.trakt_title)
                .setView(form)
                .setPositiveButton(R.string.save, (dlg, w) -> {
                    prefs.saveTrakt(clientId.getText().toString(), lists.getText().toString());
                    int valid = trakt.refs().size();
                    if (!prefs.traktLists().isEmpty() && valid == 0) toast(getString(R.string.trakt_no_valid_urls));
                    refreshTrakt();
                })
                .setNeutralButton(R.string.trakt_turn_off, (dlg, w) -> { prefs.saveTrakt("", ""); refreshTrakt(); })
                .setNegativeButton(R.string.cancel, null)
                .show();
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

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

    static final String[] PLAYERS = {Prefs.PLAY_STREMIO, Prefs.PLAY_NUVIO, Prefs.PLAY_PLEX};

    /** Brand name of a player app; these aren't translated. */
    static String playerName(String player) {
        switch (player) {
            case Prefs.PLAY_NUVIO: return "Nuvio";
            case Prefs.PLAY_PLEX: return "Plex";
            default: return "Stremio";
        }
    }

    /** Package of the installed app behind a player, or null when Phone TV can't find one. */
    String playerPackage(String player) {
        switch (player) {
            case Prefs.PLAY_PLEX: return isInstalled(PlexClient.PACKAGE) ? PlexClient.PACKAGE : null;
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
    void openWith(String player, MediaItem m, String episodeId) {
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
                opened = openUrl("nuvio://meta?type=" + type + "&id=" + Uri.encode(m.id), pkg);
                break;
            default:
                // Pinned to Stremio's own package: other apps also register the stremio:// scheme.
                opened = openUrl("stremio:///detail/" + type + "/" + m.id + (episodeId == null ? "" : "/" + Uri.encode(episodeId)), pkg);
        }
        if (opened) return;
        // The link wasn't accepted (very old or unusual build): open the app and tell the user what to look for.
        AppRepository.App app = apps.byPackage(pkg);
        if (app != null && apps.launch(app)) toast(getString(R.string.opening_search, playerName(player), m.title));
        else showMissingApp(player, m);
    }

    void openPlexItem(MediaItem m) {
        if (!isInstalled(PlexClient.PACKAGE)) { showMissingApp(Prefs.PLAY_PLEX, null); return; }
        Intent resume = new Intent(Intent.ACTION_VIEW, Uri.parse(PlexClient.playUri(m))).setPackage(PlexClient.PACKAGE)
                .putExtra("android.intent.extra.START_PLAYBACK", true); // still read by older Plex builds
        if (startSafely(resume)) return;
        if (openUrl(PlexClient.webUrl(m), PlexClient.PACKAGE)) return;
        AppRepository.App app = apps.byPackage(PlexClient.PACKAGE);
        if (app != null && apps.launch(app)) toast(getString(R.string.opening_search, "Plex", m.title));
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

    boolean startSafely(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
