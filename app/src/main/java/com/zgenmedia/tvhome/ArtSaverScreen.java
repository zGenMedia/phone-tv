package com.zgenmedia.tvhome;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The artwork screen saver: one title's backdrop at a time, drifting slowly
 * (the Ken Burns pan), cross-fading to the next every few seconds, with its name
 * or logo and a clock in the corner. Any key or tap goes back to where the user was;
 * OK opens the title on screen.
 */
final class ArtSaverScreen implements Overlay {
    private static final long SLIDE_MS = 9_000;

    private final HomeActivity a;
    private final FrameLayout root;
    private final ImageView[] art = new ImageView[2];
    private final ImageView logo;
    private final TextView name, meta;
    private final List<MediaItem> titles;
    private int index = -1, front;
    private final Runnable advance = new Runnable() {
        @Override public void run() {
            show(index + 1);
            a.main.postDelayed(this, SLIDE_MS);
        }
    };

    ArtSaverScreen(HomeActivity activity, List<MediaItem> pool) {
        a = activity;
        Ui ui = a.ui;
        titles = new ArrayList<>();
        for (MediaItem m : pool) if (m.backdrop(1280) != null && !titles.contains(m)) titles.add(m);
        Collections.shuffle(titles);

        root = new FrameLayout(a);
        root.setBackgroundColor(0xFF000000);
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnClickListener(v -> a.closeOverlay());
        for (int i = 0; i < 2; i++) {
            art[i] = new Art(a);
            art[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            art[i].setAlpha(0f);
            root.addView(art[i], new FrameLayout.LayoutParams(-1, -1));
        }
        View shade = new View(a);
        shade.setBackground(Ui.gradient(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, 0xCC000000, 0x00000000));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        root.addView(shade, slp);

        LinearLayout caption = new LinearLayout(a);
        caption.setOrientation(LinearLayout.VERTICAL);
        logo = new Art(a);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        caption.addView(logo, new LinearLayout.LayoutParams(ui.dp(260), ui.dp(80)));
        name = ui.label("", a.land() ? 30 : 24, Ui.TEXT, Ui.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        caption.addView(name);
        meta = ui.label("", 14, Ui.TEXT_DIM, Ui.MEDIUM);
        meta.setPadding(0, ui.dp(4), 0, 0);
        caption.addView(meta);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
        clp.setMargins(a.gutter(), 0, a.insetRight + ui.dp(24), a.insetBottom + ui.dp(36));
        root.addView(caption, clp);

        TextClock clock = new TextClock(a);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(Ui.TEXT);
        clock.setTypeface(Ui.MEDIUM);
        ui.text(clock, 22);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tlp.setMargins(0, a.insetTop + ui.dp(20), a.insetRight + ui.dp(28), 0);
        root.addView(clock, tlp);

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { a.main.removeCallbacks(advance); advance.run(); }
            @Override public void onViewDetachedFromWindow(View v) { a.main.removeCallbacks(advance); }
        });
    }

    boolean hasArt() { return !titles.isEmpty(); }

    /** The title on screen now, for OK. */
    MediaItem current() { return index < 0 || titles.isEmpty() ? null : titles.get(index % titles.size()); }

    private void show(int next) {
        if (titles.isEmpty()) return;
        index = next % titles.size();
        MediaItem m = titles.get(index);
        final ImageView incoming = art[1 - front], outgoing = art[front];
        front = 1 - front;
        incoming.animate().cancel();
        incoming.setAlpha(0f);
        incoming.setScaleX(1.04f);
        incoming.setScaleY(1.04f);
        incoming.setTranslationX(0);
        a.images.load(incoming, m.backdrop(1280), 1280, false, () -> {
            // Fade in, then drift: a slow push-in with a small sideways pan, alternating direction.
            float pan = a.ui.dp(index % 2 == 0 ? 18 : -18);
            incoming.animate().alpha(1f).setDuration(Motion.CROSSFADE * 3).setInterpolator(Motion.STANDARD).start();
            incoming.animate().scaleX(1.14f).scaleY(1.14f).translationX(pan)
                    .setDuration(SLIDE_MS + Motion.CROSSFADE * 3).setInterpolator(new android.view.animation.LinearInterpolator()).start();
            outgoing.animate().alpha(0f).setDuration(Motion.CROSSFADE * 3).start();
        });
        // The name shows until the logo arrives; a logo that never loads leaves the name.
        logo.setVisibility(View.GONE);
        logo.setImageDrawable(null);
        name.setVisibility(View.VISIBLE);
        name.setText(m.title);
        String l = m.logo();
        if (l != null) {
            final MediaItem showing = m;
            a.images.load(logo, l, a.ui.dp(260), true, () -> {
                if (current() != showing) return;
                logo.setVisibility(View.VISIBLE);
                name.setVisibility(View.GONE);
            });
        }
        List<String> facts = new ArrayList<>();
        if (m.year != null && !m.year.isEmpty()) facts.add(m.year);
        if (m.rating != null && !m.rating.isEmpty()) facts.add("IMDb " + m.rating);
        meta.setText(TextUtils.join("  ·  ", facts));
        Motion.fadeIn(name, Motion.CROSSFADE * 2);
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return root; }
    @Override public void rebuild() { }
    @Override public boolean onBack() { return false; }

    @Override
    public boolean move(View focused, int direction) {
        a.closeOverlay(); // any direction wakes the screen
        return true;
    }
}
