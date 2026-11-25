package com.zgenmedia.tvhome;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * "Pick for me": one suggestion at a time, chosen from what the user has watched.
 *
 * A title is drawn from their history (Trakt, Plex and what was opened from Phone TV;
 * recent ones are likelier), Trakt is asked what people who liked it also liked, and
 * one of those the user hasn't seen is offered, with the reason on screen. "Another
 * pick" draws again and never repeats itself in one sitting. With no history yet, the
 * picks come from what's popular, and the page says so.
 *
 * The point is to end the scrolling: one big answer, a Watch button under the thumb.
 */
final class PickScreen implements Overlay {
    private static final int EITHER = 0, MOVIES = 1, SHOWS = 2;
    private static final int[] KINDS = {R.string.pick_either, R.string.only_movies, R.string.only_shows};

    private final HomeActivity a;
    private final Ui ui;
    private final FrameLayout root;
    private final Random dice = new Random();
    private final Set<String> offered = new HashSet<>(); // never the same pick twice in one sitting

    private MediaItem pick;
    private String reason = "", error;
    private boolean choosing;
    private int kind = EITHER;
    private View firstFocus;
    private String focusTag = "watch";

    PickScreen(HomeActivity activity) {
        a = activity;
        ui = activity.ui;
        root = new FrameLayout(activity);
        root.setClickable(true);
        choose();
        build();
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return firstFocus; }
    @Override public boolean onBack() { return false; }
    @Override public void rebuild() { build(); }

    @Override
    public boolean move(View focused, int direction) {
        View next = HomeActivity.nextFocus(root, focused, direction);
        if (next != null && next != focused) next.requestFocus();
        return true;
    }

    // ------------------------------------------------------------------ choosing

    private void choose() {
        if (choosing) return;
        choosing = true;
        error = null;
        final int wanted = kind;
        // Read on the main thread: the library and the fetched histories live there.
        final List<Library.Watch> history = a.library.history(a.remoteHistory(), 0);
        final List<MediaItem> popular = a.popular();
        new Thread(() -> {
            Set<String> seen = new HashSet<>();
            List<MediaItem> seeds = new ArrayList<>();
            for (Library.Watch w : history) {
                if (w.item.id == null || !w.item.id.startsWith("tt")) continue;
                if (seen.add(w.item.id) && fits(w.item, wanted) && seeds.size() < 40) seeds.add(w.item);
            }
            MediaItem found = null;
            String why = "";
            // Up to three draws: a seed may have nothing new to suggest.
            for (int tries = 0; tries < 3 && found == null && !seeds.isEmpty(); tries++) {
                // Squaring the roll leans the draw toward the front of the list: what was watched lately.
                double roll = dice.nextDouble();
                MediaItem seed = seeds.remove((int) (roll * roll * seeds.size()));
                try {
                    List<MediaItem> similar = a.trakt.related(seed);
                    List<MediaItem> fresh = new ArrayList<>();
                    for (MediaItem m : similar) if (!seen.contains(m.id) && !offered.contains(m.id)) fresh.add(m);
                    if (!fresh.isEmpty()) {
                        // From the closest dozen, so the pick stays near their taste.
                        found = fresh.get(dice.nextInt(Math.min(12, fresh.size())));
                        why = a.getString(R.string.pick_because, seed.title);
                    }
                } catch (IOException | JSONException e) { /* try another seed, then fall back to popular */ }
            }
            if (found == null) {
                List<MediaItem> pool = new ArrayList<>();
                for (MediaItem m : popular) if (fits(m, wanted) && !seen.contains(m.id) && !offered.contains(m.id)) pool.add(m);
                if (!pool.isEmpty()) {
                    found = pool.get(dice.nextInt(pool.size()));
                    why = a.getString(history.isEmpty() ? R.string.pick_popular_new : R.string.pick_popular);
                }
            }
            final MediaItem result = found;
            final String because = why;
            a.main.post(() -> {
                choosing = false;
                if (result != null) { pick = result; reason = because; offered.add(result.id); }
                else error = a.getString(R.string.pick_none);
                build();
            });
        }, "phonetv-pick").start();
    }

    private static boolean fits(MediaItem m, int kind) {
        return kind == EITHER || m.isShow() == (kind == SHOWS);
    }

    // --------------------------------------------------------------------- build

    private void build() {
        root.removeAllViews();
        root.setBackgroundColor(Ui.BG);
        firstFocus = null;
        boolean land = a.land();
        int screenW = a.getResources().getDisplayMetrics().widthPixels;

        if (pick != null) {
            int w = land ? screenW : screenW * 7 / 4;
            ImageView art = new Ui.TopCropImageView(a, 0.5f);
            root.addView(art, new FrameLayout.LayoutParams(-1, -1));
            a.images.load(art, pick.backdrop(w), w, false, null);
            View shade = new View(a);
            shade.setBackground(Ui.fade(false, Ui.BG, new float[]{0f, 0.35f, 0.70f, 1f}, new float[]{0.25f, 0.45f, 0.90f, 1f}));
            root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.BOTTOM);
        column.setPadding(a.gutter(), a.insetTop + ui.dp(64), a.insetRight + ui.dp(28), a.insetBottom + ui.dp(28));
        root.addView(column, new FrameLayout.LayoutParams(land ? (int) (screenW * 0.62f) : -1, -1));

        TextView eyebrow = ui.label(a.getString(R.string.pick_title).toUpperCase(java.util.Locale.getDefault()), 12, Ui.ACCENT, Ui.BOLD);
        eyebrow.setLetterSpacing(0.12f);
        column.addView(eyebrow);

        if (pick != null) {
            final TextView title = ui.label(pick.title, land ? 34 : 26, Ui.TEXT, Ui.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, ui.dp(6), 0, 0);
            column.addView(title);
            TextView meta = ui.label(a.meta(pick), 13, Ui.TEXT_DIM, Ui.MEDIUM);
            meta.setPadding(0, ui.dp(6), 0, 0);
            column.addView(meta);
            if (!pick.summary.isEmpty()) {
                TextView summary = ui.label(pick.summary, 14, Ui.TEXT, null);
                summary.setMaxLines(land ? 3 : 4);
                summary.setEllipsize(TextUtils.TruncateAt.END);
                summary.setLineSpacing(0, 1.2f);
                summary.setPadding(0, ui.dp(10), 0, 0);
                column.addView(summary);
            }
            TextView why = ui.label(reason, 13, Ui.MUTED, null);
            why.setPadding(0, ui.dp(10), 0, ui.dp(14));
            column.addView(why);
        } else {
            TextView status = ui.label(error != null ? error : a.getString(R.string.pick_choosing), land ? 24 : 20, Ui.TEXT, Ui.BOLD);
            status.setPadding(0, ui.dp(8), 0, ui.dp(16));
            column.addView(status);
        }

        // Watch is first and under the thumb; "Another pick" is one step to the right.
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        final MediaItem m = pick;
        if (m != null) {
            buttons.addView(button(ui.primary("▶  " + a.getString(R.string.pick_watch)), "watch", () -> a.play(m)), gap());
            buttons.addView(button(ui.pill(a.getString(R.string.pick_details), Ui.SURFACE, Ui.TEXT), "details", () -> a.showDetails(m)), gap());
        }
        buttons.addView(button(ui.pill(a.getString(choosing ? R.string.pick_choosing : R.string.pick_another), Ui.SURFACE, Ui.TEXT), "another", this::choose), gap());
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(a);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setPadding(ui.dp(4), ui.dp(6), 0, ui.dp(6));
        strip.addView(buttons);
        column.addView(strip);

        LinearLayout kinds = new LinearLayout(a);
        kinds.setPadding(0, ui.dp(10), 0, 0);
        for (int i = 0; i < KINDS.length; i++) {
            final int k = i;
            TextView chip = a.chip(a.getString(KINDS[i]), kind == i);
            chip.setTag("kind" + i);
            chip.setOnClickListener(v -> { if (kind != k) { kind = k; focusTag = "kind" + k; choose(); build(); } });
            kinds.addView(chip, a.chipSpacing(6));
        }
        column.addView(kinds);

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

        // Put the remote back where it was: on Watch for a new pick, or on the control just used.
        View want = root.findViewWithTag(focusTag);
        if (want == null) want = root.findViewWithTag("another");
        firstFocus = want != null ? want : back;
        if (a.usingRemote() && root.getVisibility() == View.VISIBLE && root.isAttachedToWindow()) firstFocus.requestFocus();
        focusTag = "watch";
    }

    private View button(TextView b, String tag, Runnable act) {
        b.setTag(tag);
        b.setOnClickListener(v -> { if ("another".equals(tag)) focusTag = "watch"; act.run(); if ("another".equals(tag)) build(); });
        return b;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(10);
        return lp;
    }
}
