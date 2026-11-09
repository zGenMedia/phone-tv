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
 * Views are built in code instead of XML so the screen can be rebuilt in one
 * pass when the size setting or orientation changes. Rows are short (at most a
 * few dozen cards) and artwork is lazy-loaded, so we skip RecyclerView and the
 * androidx dependency chain it brings; the APK stays well under 100 KB.
 *
 * Copyright (c) 2026 zGenMedia — https://zgenmedia.com
 */
public class HomeActivity extends Activity {

    static final String KOFI_URL = "https://ko-fi.com/zgenmedia/tiers";
    private static final String STREMIO_PACKAGE = "com.stremio.one";
    private static final String TVMATE_HINT = "tvmate";

    private static final int TAB_FOR_YOU = 0, TAB_MOVIES = 1, TAB_SHOWS = 2, TAB_APPS = 3;
    private static final int[] TAB_LABELS = {R.string.tab_for_you, R.string.tab_movies, R.string.tab_shows, R.string.tab_apps};
    private static final int[] SIZE_LABELS = {R.string.size_compact, R.string.size_standard, R.string.size_large};

    private static final long HERO_INTERVAL_MS = 9_000;
    private static final long CONTENT_TTL_MS = 3 * 60 * 60 * 1000L;
    private static final long PLEX_POLL_MS = 2_000;
    private static final int REQUEST_TILE_IMAGE = 41;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "phonetv-io");
        t.setDaemon(true);
        return t;
    });

    private Prefs prefs;
    private Ui ui;
    private ImageLoader images;
    private AppRepository apps;
    private TileArt tileArt;
    private CatalogRepository catalog;
    private PlexClient plex;
    private TraktClient trakt;
    private HeroMood heroMood;
    private List<MediaItem> moodItems = Collections.emptyList();
    private boolean moodLoading;

    private List<MediaItem> plexContinue = Collections.emptyList();
    private List<MediaItem> plexRecent = Collections.emptyList();
    private String plexError;   // last Plex failure, shown on the home screen until it clears
    private List<TraktClient.TraktRow> traktRows = Collections.emptyList();
    private List<String> traktErrors = Collections.emptyList();
    private volatile boolean plexLinking;

    // Screen state
    private FrameLayout root;
    private ScrollView scroll;
    private View topBar;
    private boolean topBarHidden;
    private int tab = TAB_FOR_YOU;
    private int insetTop, insetBottom, insetLeft, insetRight;
    private String appFilter = "";
    private LinearLayout appGrid;
    private String tileImageTarget; // package awaiting a picked image

    // Hero carousel
    private final List<MediaItem> heroItems = new ArrayList<>();
    private int heroIndex;
    private boolean heroOnScreen = true;
    private ImageView heroBackdrop, heroLogo;
    private TextView heroTitle, heroMeta, heroSummary;
    private LinearLayout heroDots;
    private final Runnable heroAdvance = new Runnable() {
        @Override public void run() {
            // Skip the transition when nobody can see it; saves decode work and battery.
            if (heroItems.size() > 1 && heroOnScreen && hasWindowFocus()) showHero((heroIndex + 1) % heroItems.size());
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
        apps = new AppRepository(this, prefs);
        tileArt = new TileArt(this, ui);
        catalog = new CatalogRepository(this, io);
        plex = new PlexClient(prefs);
        trakt = new TraktClient(this, prefs);
        heroMood = new HeroMood(this, new HeroMood.WikidataSource());

        try { HttpResponseCache.install(new File(getCacheDir(), "http"), 20L * 1024 * 1024); }
        catch (IOException ignored) { /* runs fine uncached */ }

        setupWindow();
        apps.reload();
        apps.seedFavorites(STREMIO_PACKAGE, TVMATE_HINT, PlexClient.PACKAGE);
        catalog.loadCached();
        render();
        refreshContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemBars();
        if (!plex.isLinked() && prefs.plexPendingPin() != 0 && !plexLinking) pollPlexPin(prefs.plexPendingPin(), null);
        boolean appsChanged = apps.reload();
        if (catalog.isOlderThan(CONTENT_TTL_MS)) refreshContent();
        if (appsChanged) renderPreservingScroll();
        else restartHeroTimer();
    }

    @Override protected void onPause() { super.onPause(); main.removeCallbacks(heroAdvance); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        images.shutdown();
        io.shutdownNow();
    }

    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) applySystemBars(); }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); render(); }

    /** Home pressed while already home: go back to the top of "For you", like a TV. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        goHome();
    }

    @Override public void onBackPressed() { goHome(); } // a launcher never finishes on Back

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
        if (tab != TAB_FOR_YOU) { tab = TAB_FOR_YOU; render(); }
        else if (scroll != null) { scroll.smoothScrollTo(0, 0); setTopBarVisible(true); }
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
                main.post(this::render);
            }
            return in;
        });
    }

    /** Hides status and navigation bars (the side buttons in landscape) when enabled. */
    private void applySystemBars() {
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

    private void refreshContent() {
        catalog.refresh((error, usedCacheOnly) -> {
            if (usedCacheOnly) toast(getString(R.string.offline_cached));
            renderPreservingScroll();
        });
        refreshPlex();
        refreshTrakt();
        refreshMood();
    }

    /** Loads titles for the user's typed hero words. No-op when the hero is on trending. */
    private void refreshMood() {
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

    private void refreshPlex() {
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

    /** Opt-in rows from public Trakt lists; a no-op unless the user configured them. */
    private void refreshTrakt() {
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

    private boolean land() { return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; }
    private int gutter() { return insetLeft + ui.dp(land() ? 40 : 20); }
    private DisplayMetrics metrics() { return getResources().getDisplayMetrics(); }

    // ====================================================================== render

    private void renderPreservingScroll() {
        int y = scroll == null ? 0 : scroll.getScrollY();
        render();
        if (y > 0) scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void render() {
        if (root == null) return;
        main.removeCallbacks(heroAdvance);
        images.clearDeferred();
        root.removeAllViews();
        topBarHidden = false;
        heroOnScreen = true;

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
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
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
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

        column.addView(buildHero());

        if (tab == TAB_FOR_YOU) {
            column.addView(rowTitle(getString(R.string.row_your_apps)));
            column.addView(buildFavoritesRow());
            if (moodItems.size() > 8) posterRow(column, getString(R.string.row_more_mood, prefs.heroMood()), moodItems.subList(8, moodItems.size()));
            if (plex.isLinked()) {
                backdropRow(column, getString(R.string.row_plex_continue), plexContinue);
                posterRow(column, getString(R.string.row_plex_recent), plexRecent);
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
            backdropRow(column, getString(R.string.row_top_picks), slice(movies, 4, 20));
            posterRow(column, getString(R.string.row_popular_shows), slice(shows, 4, 40));
            posterRow(column, getString(R.string.row_popular_movies), slice(movies, 20, 48));
            posterRow(column, getString(R.string.row_top_rated), rated);
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

        bar.addView(roundIcon(android.R.drawable.ic_menu_search, v -> { tab = TAB_APPS; render(); }),
                new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));

        HorizontalScrollView tabsScroller = new HorizontalScrollView(this);
        tabsScroller.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(ui.dp(8), 0, 0, 0);
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int index = i;
            boolean selected = i == tab;
            TextView chip = ui.label(getString(TAB_LABELS[i]), 13, selected ? Ui.ON_LIGHT : 0xFFE3E4E8, Ui.MEDIUM);
            chip.setPadding(ui.dp(13), ui.dp(6), ui.dp(13), ui.dp(6));
            chip.setBackground(ui.ripple(ui.round(selected ? Ui.CHIP_SELECTED : Color.TRANSPARENT, 16)));
            chip.setFocusable(true);
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

        bar.addView(roundIcon(android.R.drawable.ic_menu_preferences, v -> showSettings()),
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
        iv.setOnClickListener(onClick);
        return iv;
    }

    // ======================================================================== hero

    // The touch listener only feeds a GestureDetector; taps still route through performClick().
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private View buildHero() {
        DisplayMetrics dm = metrics();
        boolean land = land();
        // Short enough that the "Your apps" row peeks above the fold, as on Google TV.
        int height = land ? (int) (dm.heightPixels * 0.72f) : Math.max(ui.dp(380), (int) (dm.widthPixels * 0.98f));
        FrameLayout hero = new FrameLayout(this);
        hero.setLayoutParams(new LinearLayout.LayoutParams(-1, height));

        heroBackdrop = new ImageView(this);
        heroBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBackdrop.setBackground(Ui.gradient(GradientDrawable.Orientation.TL_BR, 0xFF2F3D56, 0xFF1D2331, 0xFF28223B));
        hero.addView(heroBackdrop, new FrameLayout.LayoutParams(-1, -1));

        // Scrims: top for the menu, bottom (and left in landscape) for the text block.
        View top = new View(this);
        top.setBackground(Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0xB30E0F13, 0x400E0F13, 0x000E0F13));
        hero.addView(top, new FrameLayout.LayoutParams(-1, (int) (height * 0.35f)));
        View bottom = new View(this);
        bottom.setBackground(land
                ? Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0x00000000, 0x000E0F13, 0x990E0F13, Ui.BG)
                : Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0x00000000, 0x000E0F13, 0x660E0F13, 0xE60E0F13, Ui.BG));
        hero.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
        if (land) {
            View left = new View(this);
            left.setBackground(Ui.gradient(GradientDrawable.Orientation.LEFT_RIGHT, 0xF00E0F13, 0x800E0F13, 0x000E0F13));
            hero.addView(left, new FrameLayout.LayoutParams((int) (dm.widthPixels * 0.65f), -1));
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM | Gravity.START);
        info.setPadding(gutter(), 0, ui.dp(20), ui.dp(land ? 22 : 16));
        hero.addView(info, new FrameLayout.LayoutParams(land ? (int) (dm.widthPixels * 0.46f) : -1, -2, Gravity.BOTTOM | Gravity.START));

        heroLogo = new ImageView(this);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        heroLogo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                (int) (dm.widthPixels * (land ? 0.26f : 0.55f)), ui.dp(land ? 64 : 76));
        logoLp.bottomMargin = ui.dp(8);
        info.addView(heroLogo, logoLp);

        heroTitle = ui.label("", 26, Ui.TEXT, Ui.BOLD);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(heroTitle);

        heroMeta = ui.label("", 12, 0xFFDADCE0, Ui.MEDIUM);
        heroMeta.setPadding(0, ui.dp(3), 0, ui.dp(5));
        info.addView(heroMeta);

        heroSummary = ui.label("", 13, Ui.TEXT_DIM, null);
        heroSummary.setLineSpacing(0, 1.15f);
        heroSummary.setMaxLines(2);
        heroSummary.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(heroSummary);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, ui.dp(14), 0, 0);
        TextView watch = ui.pill(getString(R.string.hero_watch), 0xFFF1F3F4, Ui.ON_LIGHT);
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
        // Keyboard / D-pad: Left on Watch and Right on Details page the carousel,
        // the same way a TV remote moves past the edge of the hero buttons.
        watch.setOnKeyListener((v, code, e) -> e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && stepHero(-1));
        tune.setOnKeyListener((v, code, e) -> e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && stepHero(1));
        if (!heroItems.isEmpty() && !root.isInTouchMode()) watch.post(watch::requestFocus);

        // Dots with previous/next arrows for mouse users (BlueStacks, Chromebooks).
        LinearLayout pager = new LinearLayout(this);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        pager.setPadding(0, ui.dp(10), 0, 0);
        pager.addView(pagerArrow("‹", -1), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        heroDots = new LinearLayout(this);
        heroDots.setGravity(Gravity.CENTER_VERTICAL);
        heroDots.setPadding(ui.dp(6), 0, ui.dp(1), 0);
        pager.addView(heroDots);
        pager.addView(pagerArrow("›", 1), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        if (heroItems.size() < 2) pager.setVisibility(View.GONE);
        info.addView(pager);

        if (heroItems.isEmpty()) {
            heroTitle.setText(R.string.app_name);
            heroMeta.setText(catalog.isFetching() ? getString(R.string.status_loading_title) : "");
            heroSummary.setText(R.string.hero_placeholder_desc);
            buttons.setVisibility(View.GONE);
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

    private TextView pagerArrow(String glyph, int step) {
        TextView a = ui.label(glyph, 18, Ui.TEXT, Ui.BOLD);
        a.setGravity(Gravity.CENTER);
        a.setBackground(ui.ripple(ui.round(0x26FFFFFF, 14)));
        a.setFocusable(true);
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
        if (heroItems.isEmpty() || heroBackdrop == null) return;
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
        images.load(heroBackdrop, m.backdrop(land() ? screenW : screenW * 7 / 4), land() ? screenW : screenW * 7 / 4, false, null);
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

    private String meta(MediaItem m) {
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
        add.setBackground(ui.round(0x26FFFFFF, 12));
        add.setForeground(ui.ripple(null));
        add.setClipToOutline(true);
        TextView plus = ui.label(empty ? getString(R.string.fav_add_tile) : "+", empty ? 13 : 24, Ui.TEXT, null);
        plus.setGravity(Gravity.CENTER);
        add.addView(plus, new FrameLayout.LayoutParams(-1, -1));
        ui.focusable(add);
        add.setOnClickListener(v -> { tab = TAB_APPS; render(); toast(getString(R.string.fav_add_hint)); });
        row.addView(add, new LinearLayout.LayoutParams(empty ? w * 3 / 2 : h, h));
        return scroller;
    }

    private View appTile(AppRepository.App app, int heightPx, boolean onShelf) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(ui.round(Ui.SURFACE, 12));
        tileArt.bind(tile, app, heightPx, prefs.tvStyleTiles());
        if (!onShelf && apps.isFavorite(app.pkg)) tile.addView(badge("★"), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        if (!onShelf && app.tvEdition) tile.addView(badge("TV"), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        tile.setForeground(ui.ripple(null));
        tile.setClipToOutline(true);
        ui.focusable(tile);
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
        for (AppRepository.App app : apps.all()) {
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

    private void backdropRow(LinearLayout column, String title, List<MediaItem> items) {
        if (items.isEmpty()) return;
        column.addView(rowTitle(title));
        LinearLayout row = rowContainer(column);
        int w = ui.dp(land() ? 280 : 236), h = w * 9 / 16;
        for (MediaItem m : items) {
            FrameLayout card = card();
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
            View scrim = new View(this);
            scrim.setBackground(Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0x00000000, 0x00000000, 0xCC000000));
            card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

            TextView name = ui.label(m.title, 13, Ui.TEXT, Ui.BOLD);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(m.progressPercent > 0 ? 12 : 8));
            card.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            ImageView logo = new ImageView(this);
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            logo.setVisibility(View.GONE);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams((int) (w * 0.55f), ui.dp(32), Gravity.BOTTOM | Gravity.START);
            llp.leftMargin = ui.dp(10);
            llp.bottomMargin = ui.dp(8);
            card.addView(logo, llp);

            if (m.progressPercent > 0) addProgressBar(card, w, m.progressPercent);

            images.defer(card, () -> {
                images.load(art, m.backdrop(w), w, false, null);
                images.load(logo, m.logo(), (int) (w * 0.55f), true,
                        () -> { logo.setVisibility(View.VISIBLE); name.setVisibility(View.GONE); });
            });
            bindItem(card, m);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = ui.dp(10);
            row.addView(card, lp);
        }
    }

    private void posterRow(LinearLayout column, String title, List<MediaItem> items) {
        if (items.isEmpty()) return;
        column.addView(rowTitle(title));
        LinearLayout row = rowContainer(column);
        int w = ui.dp(land() ? 116 : 104), h = w * 3 / 2;
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = ui.dp(10);
            row.addView(card, lp);
        }
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
        c.setBackground(ui.round(Ui.SURFACE, 10));
        c.setClipToOutline(true);
        c.setForeground(ui.ripple(null));
        ui.focusable(c);
        return c;
    }

    private void bindItem(View v, MediaItem m) {
        v.setOnClickListener(x -> showDetails(m));
        v.setOnLongClickListener(x -> { play(m); return true; });
    }

    private TextView rowTitle(String s) {
        TextView t = ui.label(s, 15, Ui.TEXT, Ui.MEDIUM);
        t.setPadding(gutter(), ui.dp(20), ui.dp(20), ui.dp(10));
        return t;
    }

    /** Horizontal shelf. Pass a parent to attach it; the returned row's parent is the scroller. */
    private LinearLayout rowContainer(LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(gutter(), ui.dp(2), insetRight + ui.dp(10), ui.dp(4));
        hs.setOnScrollChangeListener((v, x, y, ox, oy) -> images.flush());
        LinearLayout row = new LinearLayout(this);
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
            TextView retry = ui.pill(getString(R.string.retry), 0xFFF1F3F4, Ui.ON_LIGHT);
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

    // ==================================================================== dialogs

    /** Full-width details sheet with one button per app that can play the title. */
    private void showDetails(MediaItem m) {
        Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(ui.round(0xFF16171C, 18));
        sheet.setClipToOutline(true);

        int w = Math.min(metrics().widthPixels - ui.dp(32), ui.dp(560));
        FrameLayout header = new FrameLayout(this);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        header.addView(art, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0x00000000, 0xFF16171C));
        header.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        sheet.addView(header, new LinearLayout.LayoutParams(-1, w * 9 / 16));
        images.load(art, m.backdrop(w), w, false, null);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(20));
        body.addView(ui.label(m.title, 20, Ui.TEXT, Ui.BOLD));
        TextView metaLine = ui.label(meta(m), 12, 0xFFDADCE0, Ui.MEDIUM);
        metaLine.setPadding(0, ui.dp(4), 0, ui.dp(8));
        body.addView(metaLine);
        if (!m.summary.isEmpty()) {
            TextView summary = ui.label(m.summary, 13, Ui.TEXT_DIM, null);
            summary.setLineSpacing(0, 1.15f);
            summary.setMaxLines(7);
            summary.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(summary);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, ui.dp(16), 0, 0);
        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(actions);
        if (m.source == MediaItem.Source.PLEX) {
            addAction(actions, d, getString(R.string.details_play_plex), true, () -> openPlexItem(m));
        } else {
            boolean first = true;
            if (apps.byPackage(STREMIO_PACKAGE) != null) { addAction(actions, d, getString(R.string.details_stremio), true, () -> openInStremio(m)); first = false; }
            if (apps.byPackage(PlexClient.PACKAGE) != null) { addAction(actions, d, getString(R.string.details_plex), first, () -> searchInPlex(m)); first = false; }
            addAction(actions, d, getString(R.string.details_trailer), first, () -> openTrailer(m));
        }
        addAction(actions, d, getString(R.string.close), false, () -> {});
        body.addView(actionScroll);
        sheet.addView(body);

        d.setContentView(sheet, new ViewGroup.LayoutParams(w, -2));
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.show();
    }

    private void addAction(LinearLayout row, Dialog d, String label, boolean primary, Runnable action) {
        TextView b = ui.pill(label, primary ? 0xFFF1F3F4 : 0x33FFFFFF, primary ? Ui.ON_LIGHT : Ui.TEXT);
        b.setOnClickListener(v -> { d.dismiss(); action.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(8);
        row.addView(b, lp);
    }

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
        labels.add(getString(R.string.menu_app_info));
        actions.add(() -> startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))));

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

    private void showSettings() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(getString(R.string.settings_size, getString(SIZE_LABELS[prefs.sizeIndex()])));
        actions.add(this::chooseSize);

        labels.add(getString(R.string.settings_bars, getString(prefs.hideSystemBars() ? R.string.bars_hidden : R.string.bars_shown)));
        actions.add(() -> { prefs.setHideSystemBars(!prefs.hideSystemBars()); applySystemBars(); });

        labels.add(getString(R.string.settings_tiles, getString(prefs.tvStyleTiles() ? R.string.tiles_tv : R.string.tiles_phone)));
        actions.add(() -> { prefs.setTvStyleTiles(!prefs.tvStyleTiles()); renderPreservingScroll(); });

        labels.add(getString(R.string.settings_play, playWithLabel(prefs.playWith())));
        actions.add(this::choosePlayer);

        if (plex.isLinked()) {
            String server = prefs.plexServerName().isEmpty() ? getString(R.string.plex_no_server_short) : prefs.plexServerName();
            labels.add(getString(R.string.settings_plex_disconnect, server));
            actions.add(() -> {
                prefs.clearPlex(); prefs.clearPlexPendingPin();
                plexContinue = Collections.emptyList(); plexRecent = Collections.emptyList(); plexError = null;
                renderPreservingScroll();
            });
        } else {
            labels.add(getString(R.string.settings_plex_connect));
            actions.add(this::linkPlex);
        }

        labels.add(trakt.isConfigured()
                ? getString(R.string.settings_trakt_on, trakt.refs().size())
                : getString(R.string.settings_trakt_off));
        actions.add(this::editTrakt);

        labels.add(getString(R.string.settings_refresh));
        actions.add(() -> { toast(getString(R.string.refreshing)); refreshContent(); });

        labels.add(getString(R.string.settings_home));
        actions.add(() -> { if (!startSafely(new Intent(Settings.ACTION_HOME_SETTINGS))) startSafely(new Intent(Settings.ACTION_SETTINGS)); });

        labels.add(getString(R.string.settings_donate));
        actions.add(() -> openUrl(KOFI_URL, null));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setCustomTitle(settingsHeader())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** "Phone TV" on the left, version on the right, inset from the dialog edge. */
    private View settingsHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        h.setPadding(pad, pad, pad, pad / 2);
        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(20);
        name.setTypeface(Ui.MEDIUM);
        h.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = new TextView(this);
        version.setText(getString(R.string.settings_version, BuildConfig.VERSION_NAME));
        version.setTextColor(Ui.MUTED);
        version.setTextSize(14);
        h.addView(version);
        return h;
    }

    /** "Tune the hero": two or three words, e.g. "asian romance" or "latest spanish". */
    private void tuneHero() {
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
        dialog.show();
    }

    /** Advanced: Trakt Client ID + public list URLs. Casual users never need this. */
    private void editTrakt() {
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
        form.addView(clientId);
        EditText lists = new EditText(this);
        lists.setHint(R.string.trakt_lists_hint);
        lists.setMinLines(3);
        lists.setGravity(Gravity.TOP | Gravity.START);
        lists.setText(prefs.traktLists());
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

    private void chooseSize() {
        String[] names = new String[SIZE_LABELS.length];
        for (int i = 0; i < names.length; i++) names[i] = getString(SIZE_LABELS[i]);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.settings_size, ""))
                .setSingleChoiceItems(names, prefs.sizeIndex(), (d, which) -> {
                    prefs.setSizeIndex(which);
                    ui.setScale(prefs.scale());
                    d.dismiss();
                    render();
                }).show();
    }

    private void choosePlayer() {
        String[] values = {Prefs.PLAY_ASK, Prefs.PLAY_STREMIO, Prefs.PLAY_PLEX};
        String[] names = new String[values.length];
        int current = 0;
        for (int i = 0; i < values.length; i++) {
            names[i] = playWithLabel(values[i]);
            if (values[i].equals(prefs.playWith())) current = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.settings_play, ""))
                .setSingleChoiceItems(names, current, (d, which) -> { prefs.setPlayWith(values[which]); d.dismiss(); })
                .show();
    }

    private String playWithLabel(String value) {
        switch (value) {
            case Prefs.PLAY_STREMIO: return getString(R.string.details_stremio);
            case Prefs.PLAY_PLEX: return getString(R.string.details_plex);
            default: return getString(R.string.play_ask);
        }
    }

    /**
     * Plex PIN link flow. Shows the code and polls plex.tv until the user
     * approves it. Polling runs on its own thread and the PIN is persisted, so
     * linking still completes if the user switches to a browser to enter the
     * code and Android closes this dialog (or the whole activity) meanwhile.
     */
    private void linkPlex() {
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.plex_link_title)
                .setMessage(R.string.plex_link_waiting)
                .setPositiveButton(R.string.plex_open_link, null)
                .setNegativeButton(R.string.cancel, (d, w) -> { prefs.clearPlexPendingPin(); plexLinking = false; })
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> openUrl(PlexClient.LINK_URL, null)));
        dialog.show();

        new Thread(() -> {
            try {
                PlexClient.Pin pin = plex.createPin();
                prefs.setPlexPendingPin(pin.id, System.currentTimeMillis() + 10 * 60_000);
                main.post(() -> dialog.setMessage(getString(R.string.plex_link_body, pin.code)));
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
                if (dialog != null) dialog.dismiss();
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

    /** Plays a title with the user's chosen app, or shows the details sheet to choose. */
    private void play(MediaItem m) {
        if (m.source == MediaItem.Source.PLEX) { openPlexItem(m); return; }
        String with = prefs.playWith();
        if (Prefs.PLAY_STREMIO.equals(with) && apps.byPackage(STREMIO_PACKAGE) != null) openInStremio(m);
        else if (Prefs.PLAY_PLEX.equals(with) && apps.byPackage(PlexClient.PACKAGE) != null) searchInPlex(m);
        else showDetails(m);
    }

    private void openInStremio(MediaItem m) {
        Intent deep = new Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///detail/" + m.type + "/" + m.id));
        if (startSafely(deep)) return;
        openAppOrStore(STREMIO_PACKAGE, "Stremio", m.title);
    }

    /** Hands the title to Plex's search. Falls back to just opening Plex. */
    private void searchInPlex(MediaItem m) {
        Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(PlexClient.PACKAGE).putExtra(SearchManager.QUERY, m.title);
        if (startSafely(search)) return;
        openAppOrStore(PlexClient.PACKAGE, "Plex", m.title);
    }

    private void openPlexItem(MediaItem m) {
        if (openUrl(PlexClient.webUrl(m), PlexClient.PACKAGE)) return;
        openAppOrStore(PlexClient.PACKAGE, "Plex", m.title);
    }

    private void openTrailer(MediaItem m) {
        openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(m.title + " " + m.year + " trailer"), null);
    }

    private void openAppOrStore(String pkg, String name, String searchHint) {
        AppRepository.App a = apps.byPackage(pkg);
        if (a != null) {
            toast(getString(R.string.opening_search, name, searchHint));
            apps.launch(a);
        } else {
            toast(getString(R.string.not_installed, name));
            if (!startSafely(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)))) {
                openUrl("https://play.google.com/store/apps/details?id=" + pkg, null);
            }
        }
    }

    /** Opens a URL, optionally pinned to one app. @return false if nothing could handle it. */
    private boolean openUrl(String url, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (pkg != null) i.setPackage(pkg);
        return startSafely(i);
    }

    private boolean startSafely(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
