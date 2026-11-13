package com.zgenmedia.tvhome;

import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONException;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The page for one movie or show: artwork, what it is, where to watch it and,
 * for shows, every season and episode.
 *
 * It opens instantly with what the row card already knew (title, year,
 * summary, artwork) and fills in runtime, cast and episodes when the catalog
 * answers. Phone TV never plays anything itself; every button hands the title
 * to an app the user has installed.
 */
final class DetailsScreen implements Overlay {
    private final HomeActivity a;
    private final Ui ui;
    private final MediaItem item;
    private final FrameLayout root;

    private CatalogRepository.Details details; // null until fetched (always null for Plex items)
    private String loadError;
    private boolean loading;
    private int season = Integer.MIN_VALUE;

    private ScrollView scroll;
    private LinearLayout extras, episodeRow;
    private TextView metaLine, summary, credits;
    private View primaryAction;
    private final List<TextView> seasonChips = new ArrayList<>();
    // Episode thumbnails load as their cards scroll near; kept here rather than in the
    // home screen's lazy queue, which is cleared whenever the launcher rebuilds itself.
    private final List<View> pendingCards = new ArrayList<>();
    private final List<Runnable> pendingLoads = new ArrayList<>();

    DetailsScreen(HomeActivity activity, MediaItem item) {
        this.a = activity;
        this.ui = activity.ui;
        this.item = item;
        root = new FrameLayout(activity);
        root.setClickable(true);
        if (item.source == MediaItem.Source.CATALOG) {
            details = a.catalog.cachedDetails(item);
            if (details == null) fetch();
        }
        build();
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return primaryAction; }
    @Override public boolean onBack() { return false; }

    @Override
    public void rebuild() {
        build();
        if (a.usingRemote() && primaryAction != null) primaryAction.requestFocus();
    }

    @Override
    public boolean move(View focused, int direction) {
        View next = HomeActivity.nextFocus(root, focused, direction);
        if (next != null && next != focused) next.requestFocus();
        return true;
    }

    private void fetch() {
        loading = true;
        loadError = null;
        new Thread(() -> {
            CatalogRepository.Details d = null;
            String error = null;
            try { d = a.catalog.details(item); }
            catch (IOException | JSONException e) { error = CatalogRepository.describe(e); }
            final CatalogRepository.Details got = d;
            final String err = error;
            a.main.post(() -> {
                loading = false;
                details = got;
                loadError = err;
                if (extras != null) { fillHeader(); fillExtras(); }
            });
        }, "phonetv-details").start();
    }

    // ======================================================================= build

    private void build() {
        root.removeAllViews();
        root.setBackgroundColor(Ui.BG);
        seasonChips.clear();
        DisplayMetrics dm = a.metrics();
        boolean land = a.land();
        int screenW = dm.widthPixels, screenH = dm.heightPixels;

        // Artwork keeps its top edge. Landscape: it fills the right of the screen and melts
        // into the page on the left, where the details sit. Portrait: a band across the top.
        int artW = land ? (int) (screenW * 0.78f) : screenW;
        int artH = land ? screenH : Math.max(ui.dp(300), (int) (screenW * 0.86f));
        ImageView art = new Ui.TopCropImageView(a, land ? 1f : 0.5f);
        root.addView(art, new FrameLayout.LayoutParams(artW, artH, Gravity.TOP | Gravity.END));
        int request = land ? artW : screenW * 7 / 4; // portrait crops 16:9 into a tall frame
        String backdrop = item.backdrop(request);
        if (backdrop != null) a.images.load(art, backdrop, request, false, null);
        if (land) {
            View side = new View(a);
            side.setBackground(Ui.fade(true, Ui.BG, new float[]{0f, 0.25f, 0.55f, 0.80f, 1f}, new float[]{1f, 0.90f, 0.62f, 0.22f, 0f}));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (artW * 0.62f), -1, Gravity.START);
            lp.leftMargin = screenW - artW;
            root.addView(side, lp);
        }
        View floor = new View(a);
        floor.setBackground(land
                ? Ui.fade(false, Ui.BG, new float[]{0f, 0.45f, 0.78f, 1f}, new float[]{0f, 0.15f, 0.70f, 1f})
                : Ui.fade(false, Ui.BG, new float[]{0f, 0.35f, 0.62f, 0.85f, 1f}, new float[]{0.25f, 0f, 0.55f, 0.95f, 1f}));
        root.addView(floor, new FrameLayout.LayoutParams(-1, artH, Gravity.TOP));

        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // Header: sits at the bottom-left of the first screen, like the banner on the home screen.
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.BOTTOM | Gravity.START);
        head.setPadding(a.gutter(), land ? a.insetTop + ui.dp(60) : (int) (artH * 0.56f), land ? 0 : a.insetRight + ui.dp(20), ui.dp(land ? 20 : 16));
        if (land) head.setMinimumHeight((int) (screenH * 0.82f));
        column.addView(head, new LinearLayout.LayoutParams(land ? (int) (screenW * 0.56f) : -1, -2));
        // Anything focused inside the header keeps the page at the top.
        head.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> scroll.setTag(R.id.top_zone, b));

        TextView title = ui.label(item.title, land ? 30 : 26, Ui.TEXT, Ui.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        ImageView logo = new Ui.LogoView(a);
        logo.setVisibility(View.GONE);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams((int) (screenW * (land ? 0.30f : 0.62f)), ui.dp(land ? 78 : 72));
        logoLp.bottomMargin = ui.dp(10);
        head.addView(logo, logoLp);
        head.addView(title);
        a.images.load(logo, item.logo(), screenW / 2, true, () -> { logo.setVisibility(View.VISIBLE); title.setVisibility(View.GONE); });

        metaLine = ui.label("", 13, 0xFFDADCE0, Ui.MEDIUM);
        metaLine.setPadding(0, ui.dp(6), 0, ui.dp(8));
        head.addView(metaLine);

        summary = ui.label("", land ? 15 : 14, Ui.TEXT_DIM, null);
        summary.setLineSpacing(0, 1.18f);
        summary.setMaxLines(land ? 4 : 6);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(summary);

        credits = ui.label("", 12, Ui.MUTED, null);
        credits.setLineSpacing(0, 1.2f);
        credits.setMaxLines(3);
        credits.setEllipsize(TextUtils.TruncateAt.END);
        credits.setPadding(0, ui.dp(8), 0, 0);
        head.addView(credits);

        head.addView(buildActions());

        extras = new LinearLayout(a);
        extras.setOrientation(LinearLayout.VERTICAL);
        extras.setPadding(0, 0, 0, a.insetBottom + ui.dp(28));
        column.addView(extras);

        // An on-screen way out for touch and mouse; remotes use Back.
        TextView back = ui.label("←", 17, Ui.TEXT, Ui.BOLD);
        back.setGravity(Gravity.CENTER);
        back.setBackground(ui.ripple(ui.round(0x66000000, 19)));
        back.setFocusable(true);
        back.setContentDescription(a.getString(R.string.close));
        ui.focusRing(back, 19);
        back.setOnClickListener(v -> a.closeOverlay());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ui.dp(38), ui.dp(38), Gravity.TOP | Gravity.START);
        blp.setMargins(a.gutter() - ui.dp(4), a.insetTop + ui.dp(12), 0, 0);
        root.addView(back, blp);

        fillHeader();
        fillExtras();
    }

    /** One button per way of watching. The user's chosen app leads; apps that aren't installed still explain themselves when tapped. */
    private View buildActions() {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        primaryAction = null;
        if (item.source == MediaItem.Source.PLEX) {
            addAction(row, a.getString(item.progressPercent > 0 ? R.string.details_resume_plex : R.string.details_play_plex), true, true, () -> a.openPlexItem(item));
        } else {
            for (String player : orderedPlayers()) {
                boolean first = row.getChildCount() == 0;
                boolean installed = a.playerPackage(player) != null;
                String name = HomeActivity.playerName(player);
                addAction(row, first ? a.getString(R.string.details_watch_on, name) : name, first, installed, () -> a.openWith(player, item, null));
            }
            addAction(row, a.getString(R.string.details_trailer), false, true, () -> a.openTrailer(item, details == null ? null : details.trailerId));
        }
        HorizontalScrollView strip = new HorizontalScrollView(a);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        strip.setPadding(0, ui.dp(16), a.insetRight + ui.dp(8), ui.dp(4));
        strip.addView(row);
        return strip;
    }

    private void addAction(LinearLayout row, String label, boolean primary, boolean installed, Runnable action) {
        TextView b = primary ? ui.primary(label) : ui.pill(label, 0x33FFFFFF, installed ? Ui.TEXT : Ui.MUTED);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(8);
        row.addView(b, lp);
        if (primaryAction == null) primaryAction = b;
    }

    /** The chosen player first, then installed ones, then the rest. */
    private List<String> orderedPlayers() {
        List<String> out = new ArrayList<>();
        String chosen = a.prefs.playWith();
        for (String p : HomeActivity.PLAYERS) if (p.equals(chosen)) out.add(p);
        for (String p : HomeActivity.PLAYERS) if (!out.contains(p) && a.playerPackage(p) != null) out.add(p);
        for (String p : HomeActivity.PLAYERS) if (!out.contains(p)) out.add(p);
        return out;
    }

    // ===================================================================== content

    private void fillHeader() {
        List<String> parts = new ArrayList<>();
        if (!item.subtitle.isEmpty()) parts.add(item.subtitle);
        else parts.add(a.getString("episode".equals(item.type) ? R.string.meta_episode : item.isShow() ? R.string.meta_show : R.string.meta_movie));
        if (!item.year.isEmpty()) parts.add(item.year);
        String runtime = details == null ? "" : runtime(details.runtime);
        if (!runtime.isEmpty()) parts.add(runtime);
        String rating = details != null && !details.rating.isEmpty() ? details.rating : item.rating;
        if (!rating.isEmpty()) parts.add("★ " + rating);
        String genres = details != null && !details.genres.isEmpty()
                ? TextUtils.join(", ", details.genres.subList(0, Math.min(3, details.genres.size()))) : item.genres;
        if (!genres.isEmpty()) parts.add(genres);
        if (item.source == MediaItem.Source.PLEX) parts.add("Plex");
        metaLine.setText(TextUtils.join("  ·  ", parts));

        String text = details != null && details.summary.length() > item.summary.length() ? details.summary : item.summary;
        summary.setText(text);
        summary.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);

        StringBuilder who = new StringBuilder();
        if (details != null && !details.directors.isEmpty()) who.append(a.getString(R.string.details_director, TextUtils.join(", ", details.directors)));
        if (details != null && !details.cast.isEmpty()) {
            if (who.length() > 0) who.append('\n');
            who.append(a.getString(R.string.details_cast, TextUtils.join(", ", details.cast)));
        }
        credits.setText(who);
        credits.setVisibility(who.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Everything below the header: a status line while loading, then seasons and episodes for shows. */
    private void fillExtras() {
        extras.removeAllViews();
        seasonChips.clear();
        episodeRow = null;
        if (details == null) {
            if (loading) extras.addView(note(a.getString(R.string.details_loading)));
            else if (loadError != null) {
                extras.addView(note(a.getString(R.string.details_failed, loadError)));
                TextView retry = ui.pill(a.getString(R.string.retry), 0x26FFFFFF, Ui.TEXT);
                retry.setOnClickListener(v -> { fetch(); fillExtras(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.leftMargin = a.gutter();
                extras.addView(retry, lp);
            }
            return;
        }
        if (details.episodes.isEmpty()) return;

        List<Integer> seasons = details.seasons();
        if (!seasons.contains(season)) season = seasons.get(0);
        TextView heading = ui.label(a.getString(R.string.details_episodes), 15, Ui.TEXT, Ui.MEDIUM);
        heading.setPadding(a.gutter(), ui.dp(10), ui.dp(20), 0);
        extras.addView(heading);

        LinearLayout chips = strip(ui.dp(8), ui.dp(2));
        for (int number : seasons) {
            TextView chip = ui.label(number == 0 ? a.getString(R.string.details_specials) : a.getString(R.string.details_season, number), 13, Ui.TEXT, Ui.MEDIUM);
            chip.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setTag(number);
            ui.focusRing(chip, 18);
            View.OnFocusChangeListener base = chip.getOnFocusChangeListener();
            // Moving along the seasons with a remote switches season straight away.
            chip.setOnFocusChangeListener((v, has) -> { if (base != null) base.onFocusChange(v, has); if (has) showSeason(number); });
            chip.setOnClickListener(v -> showSeason(number));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ui.dp(6);
            chips.addView(chip, lp);
            seasonChips.add(chip);
        }
        episodeRow = strip(ui.dp(10), ui.dp(12));
        ((HorizontalScrollView) episodeRow.getParent()).setOnScrollChangeListener((v, x, y, ox, oy) -> flushThumbnails());
        int shown = season;
        season = Integer.MIN_VALUE; // force the first fill
        showSeason(shown);
    }

    private void showSeason(int number) {
        if (number == season || episodeRow == null) return;
        season = number;
        LinearLayout chips = seasonChips.isEmpty() ? null : (LinearLayout) seasonChips.get(0).getParent();
        for (TextView chip : seasonChips) {
            boolean on = Integer.valueOf(number).equals(chip.getTag());
            chip.setBackground(ui.ripple(ui.round(on ? Ui.ACCENT : 0x26FFFFFF, 18)));
            chip.setTextColor(on ? Ui.ON_ACCENT : Ui.TEXT);
            if (on && chips != null) chips.setTag(R.id.selected_chip, chip);
        }

        episodeRow.removeAllViews();
        pendingCards.clear();
        pendingLoads.clear();
        ((HorizontalScrollView) episodeRow.getParent()).scrollTo(0, 0);
        int w = ui.dp(a.land() ? 250 : 216), h = w * 9 / 16;
        for (CatalogRepository.Episode e : details.season(number)) {
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);

            FrameLayout card = new FrameLayout(a);
            card.setBackground(ui.round(Ui.SURFACE, ui.cardRadius));
            card.setClipToOutline(true);
            ui.focusable(card);
            ImageView thumb = new ImageView(a);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            TextView badge = ui.label(String.valueOf(e.number), 11, Ui.TEXT, Ui.BOLD);
            badge.setPadding(ui.dp(7), ui.dp(2), ui.dp(7), ui.dp(2));
            badge.setBackground(ui.round(0xB3000000, 6));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            blp.setMargins(ui.dp(8), ui.dp(8), 0, 0);
            card.addView(badge, blp);
            card.setOnClickListener(v -> openEpisode(e));
            cell.addView(card, new LinearLayout.LayoutParams(w, h));
            if (!e.thumbnail.isEmpty()) {
                pendingCards.add(card);
                pendingLoads.add(() -> a.images.load(thumb, e.thumbnail, w, false, null));
            }

            TextView name = ui.label(e.title.isEmpty() ? a.getString(R.string.details_episode_n, e.number) : e.title, 13, Ui.TEXT, Ui.MEDIUM);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), 0);
            cell.addView(name, new LinearLayout.LayoutParams(w, -2));
            String aired = date(e.released);
            String blurb = aired.isEmpty() ? e.overview : e.overview.isEmpty() ? aired : aired + "  ·  " + e.overview;
            TextView about = ui.label(blurb, 11, Ui.MUTED, null);
            about.setMaxLines(2);
            about.setEllipsize(TextUtils.TruncateAt.END);
            about.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), 0);
            cell.addView(about, new LinearLayout.LayoutParams(w, -2));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ui.dp(12);
            episodeRow.addView(cell, lp);
        }
        episodeRow.post(this::flushThumbnails);
    }

    /** Starts thumbnail downloads for episode cards within about a screen of the visible part of the row. */
    private void flushThumbnails() {
        if (episodeRow == null) return;
        HorizontalScrollView shelf = (HorizontalScrollView) episodeRow.getParent();
        int from = shelf.getScrollX() - shelf.getWidth() / 2, to = shelf.getScrollX() + shelf.getWidth() * 3 / 2;
        for (int i = pendingCards.size() - 1; i >= 0; i--) {
            View cell = (View) pendingCards.get(i).getParent();
            if (cell.getWidth() == 0 || cell.getRight() < from || cell.getLeft() > to) continue;
            pendingCards.remove(i);
            pendingLoads.remove(i).run();
        }
    }

    /** Opens one episode in the chosen app; with "Ask each time" it asks. */
    private void openEpisode(CatalogRepository.Episode e) {
        String with = a.prefs.playWith();
        if (!Prefs.PLAY_ASK.equals(with)) { a.openWith(with, item, e.id); return; }
        List<String> players = orderedPlayers();
        String[] labels = new String[players.size()];
        Runnable[] actions = new Runnable[players.size()];
        for (int i = 0; i < labels.length; i++) {
            final String player = players.get(i);
            labels[i] = HomeActivity.playerName(player);
            actions[i] = () -> a.openWith(player, item, e.id);
        }
        String heading = a.getString(R.string.details_episode_heading, e.season, e.number) + (e.title.isEmpty() ? "" : "  ·  " + e.title);
        a.showNotice(heading, a.getString(R.string.details_open_with), labels, actions);
    }

    // ===================================================================== helpers

    /** A horizontal shelf attached to {@link #extras}; returns the row inside it. */
    private LinearLayout strip(int padTop, int padBottom) {
        HorizontalScrollView shelf = new HorizontalScrollView(a);
        shelf.setHorizontalScrollBarEnabled(false);
        shelf.setClipToPadding(false);
        shelf.setClipChildren(false);
        shelf.setPadding(a.gutter(), padTop, a.insetRight + ui.dp(12), padBottom);
        LinearLayout row = new LinearLayout(a);
        row.setClipChildren(false);
        shelf.addView(row);
        extras.addView(shelf);
        return row;
    }

    private TextView note(String text) {
        TextView t = ui.label(text, 12, Ui.MUTED, null);
        t.setPadding(a.gutter(), ui.dp(6), ui.dp(20), ui.dp(10));
        return t;
    }

    /** "148 min" becomes "2h 28m"; anything unexpected is shown as it came. */
    private static String runtime(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 4) return raw;
        int minutes = Integer.parseInt(digits);
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    /** ISO timestamp to the device's medium date, e.g. "Apr 17, 2011". */
    private static String date(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso.substring(0, 10));
            return d == null ? "" : DateFormat.getDateInstance(DateFormat.MEDIUM).format(d);
        } catch (ParseException e) {
            return "";
        }
    }
}
