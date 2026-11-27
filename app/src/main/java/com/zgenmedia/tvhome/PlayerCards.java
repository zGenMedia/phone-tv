package com.zgenmedia.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The things that come and go over a film while it plays, the way they do on Netflix
 * and YouTube. The player owns the picture and the controls; this owns the rest:
 *
 *  - the line as it starts: age rating, score, year, length, genres. Top left, a few seconds;
 *  - "Skip intro" / "Skip recap", bottom right, for as long as the intro runs. OK presses it;
 *  - the card as the credits roll: the next episode, or for a film a few others its lead
 *    is in ("If you love …") or titles like it;
 *  - "More like this", a row of titles under the controls. Down from the buttons opens
 *    it, and the film keeps playing while the viewer looks along it.
 *
 * Everything here is optional and arrives late (see {@link WatchExtras}); each piece shows
 * only once its facts are in, and each has its own switch in Settings › Playback.
 */
final class PlayerCards {
    /** What this needs from the player, and what it asks of it. */
    interface Host {
        void seekTo(long positionMs);
        void playNext();
        void openTitle(MediaItem title);
        boolean barShowing();
        /** Keeps the controls on screen a while longer. */
        void holdBar();
        /** Puts the remote back on the row of control buttons. */
        void focusControls();
        /** The viewer is using a remote or keys, so something should always hold the focus. */
        boolean usingRemote();
    }

    private static final long OPENING_MS = 7_000;
    private static final int STRIP_TITLES = 14;

    private final Activity a;
    private final Ui ui;
    private final Prefs prefs;
    private final FrameLayout root;
    private final LinearLayout bar;
    private final Host host;
    private final ImageLoader images;
    private final boolean show;
    private final String nextLabel, nextTitle, backdrop;

    private WatchExtras extras;
    private LinearLayout skip;
    private TextView skipLabel, skipKey;
    private GradientDrawable skipResting, skipChosen;
    private boolean skipArmed;
    private WatchExtras.Span skipping;
    private LinearLayout opening, end, strip, stripRow;
    private View endPrimary;
    private boolean started, openingDone, endDone, stripFilled;

    /**
     * @param nextLabel "S1 · E3" when another episode follows, else null
     * @param backdrop  the title's wide artwork, for the next-episode card
     */
    PlayerCards(Activity activity, Ui ui, Prefs prefs, FrameLayout root, LinearLayout bar, Host host,
                boolean show, String nextLabel, String nextTitle, String backdrop) {
        this.a = activity;
        this.ui = ui;
        this.prefs = prefs;
        this.root = root;
        this.bar = bar;
        this.host = host;
        this.show = show;
        this.nextLabel = nextLabel;
        this.nextTitle = nextTitle == null ? "" : nextTitle;
        this.backdrop = backdrop;
        this.images = new ImageLoader(activity);
        buildSkip();
        buildStrip();
    }

    /** More facts have arrived. */
    void setExtras(WatchExtras x) {
        extras = x;
        if (started) showOpening();
        if (strip.getVisibility() == View.VISIBLE) fillStrip();
    }

    /** The first picture is up. */
    void onStarted() {
        started = true;
        showOpening();
    }

    /** Twice a second while there's a film on. */
    void onTick(long position, long duration) {
        float lift = host.barShowing() ? -(bar.getHeight() - ui.dp(20)) : 0; // sit above the controls, not under them
        skip.setTranslationY(lift);
        if (end != null) end.setTranslationY(lift);
        if (extras == null) return;

        WatchExtras.Span now = !prefs.skipButton() ? null
                : extras.intro != null && extras.intro.holds(position) ? extras.intro
                : extras.recap != null && extras.recap.holds(position) ? extras.recap : null;
        if (now != skipping) {
            skipping = now;
            if (now != null) {
                skipLabel.setText(now == extras.intro ? R.string.player_skip_intro : R.string.player_skip_recap);
                Motion.fadeIn(skip, Motion.FAST);
            }
            skip.setVisibility(now != null ? View.VISIBLE : View.GONE);
        }
        if (skipping != null) armSkip(host.usingRemote() && !host.barShowing());

        // Only a real film or episode gets an ending card; a two-minute clip doesn't.
        if (!endDone && prefs.endCard() && duration > 5 * 60_000 && position >= extras.endCardAt(duration, show) && position < duration) showEnd();
    }

    /**
     * @param inButtons the remote is on the row of control buttons (anywhere but the timeline above them)
     * @return true when the key was used here
     */
    boolean onKey(int code, boolean barUp, boolean inButtons) {
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (end != null) { dismissEnd(); return true; }
            if (stripOpen()) { closeStrip(); return true; }
            return false;
        }
        if (!barUp && skipping != null && ok) { host.seekTo(skipping.endMs); return true; }
        if (barUp && inButtons && code == KeyEvent.KEYCODE_DPAD_DOWN && !stripOpen()) { openStrip(); return true; }
        return false;
    }

    /** The controls have gone: if the ending card is up, the remote belongs on it. */
    void onBarHidden() {
        if (end != null && endPrimary != null && host.usingRemote()) focus(endPrimary);
    }

    /** The remote is on one of the cards: its keys are the card's, not the player's. */
    boolean hasFocus() { return (end != null && end.hasFocus()) || (stripOpen() && strip.hasFocus()); }

    boolean stripOpen() { return strip.getVisibility() == View.VISIBLE; }

    // ---------------------------------------------------------------- opening line

    private void showOpening() {
        if (openingDone || extras == null || !prefs.openingCard()) return;
        List<String> facts = new ArrayList<>();
        if (!extras.score.isEmpty()) facts.add("IMDb " + extras.score);
        if (!extras.year.isEmpty()) facts.add(extras.year);
        if (!extras.runtime.isEmpty()) facts.add(extras.runtime);
        if (!extras.genres.isEmpty()) facts.add(TextUtils.join(", ", extras.genres));
        if (facts.isEmpty() && extras.ageRating.isEmpty()) return; // nothing known yet; a later part may bring it
        openingDone = true;

        opening = new LinearLayout(a);
        opening.setGravity(Gravity.CENTER_VERTICAL);
        View rule = new View(a);
        rule.setBackground(ui.round(Ui.ACCENT, 2));
        opening.addView(rule, new LinearLayout.LayoutParams(ui.dp(4), -1));
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(ui.dp(12), ui.dp(2), 0, ui.dp(2));
        // The age rating leads when it's known; otherwise the score does.
        String headline = !extras.ageRating.isEmpty() ? a.getString(R.string.player_rated, extras.ageRating) : facts.remove(0);
        TextView top = ui.label(headline, 18, Color.WHITE, Ui.BOLD);
        top.setShadowLayer(ui.dp(4), 0, ui.dp(1), 0xCC000000);
        text.addView(top);
        if (!facts.isEmpty()) {
            TextView rest = ui.label(TextUtils.join("  ·  ", facts), 13, 0xFFE6E8EE, Ui.MEDIUM);
            rest.setShadowLayer(ui.dp(4), 0, ui.dp(1), 0xCC000000);
            text.addView(rest);
        }
        opening.addView(text);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(ui.dp(36), ui.dp(32), ui.dp(36), 0);
        root.addView(opening, lp);
        Motion.fadeIn(opening, Motion.CROSSFADE * 2);
        final View card = opening;
        root.postDelayed(() -> card.animate().alpha(0f).setDuration(Motion.CROSSFADE * 2).withEndAction(() -> root.removeView(card)).start(), OPENING_MS);
    }

    // ------------------------------------------------------------------ skip intro

    /**
     * The button has two looks, the same two every button in the app has. Resting: dark and
     * outlined, for a finger or while the remote is on the controls. Chosen: solid white,
     * larger, ringed, with an "OK" key drawn beside the words. That is how it looks whenever
     * pressing OK would skip, so there is never a doubt about what OK will do.
     */
    private void buildSkip() {
        skip = new LinearLayout(a);
        skip.setGravity(Gravity.CENTER_VERTICAL);
        skip.setPadding(ui.dp(10), ui.dp(9), ui.dp(18), ui.dp(9));
        skipResting = ui.round(0xD916181F, 24);
        skipResting.setStroke(ui.dp(1), 0x80FFFFFF);
        skipChosen = ui.round(0xFFFFFFFF, 24);
        skipChosen.setStroke(Math.max(4, ui.dp(3)), Ui.RING);
        skipKey = ui.label("OK", 11, Color.WHITE, Ui.BOLD);
        skipKey.setBackground(ui.round(0xFF111318, 6));
        skipKey.setPadding(ui.dp(7), ui.dp(3), ui.dp(7), ui.dp(3));
        skip.addView(skipKey);
        skipLabel = ui.label("", 14, Color.WHITE, Ui.MEDIUM);
        skipLabel.setPadding(ui.dp(8), 0, 0, 0);
        skip.addView(skipLabel);
        skip.setVisibility(View.GONE);
        skip.setClickable(true);
        skip.setOnClickListener(v -> { if (skipping != null) host.seekTo(skipping.endMs); });
        skipArmed = true;
        armSkip(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, ui.dp(40), ui.dp(48));
        root.addView(skip, lp);
    }

    private void armSkip(boolean chosen) {
        if (chosen == skipArmed) return;
        skipArmed = chosen;
        skip.setBackground(chosen ? skipChosen : skipResting);
        skipLabel.setTextColor(chosen ? 0xFF111318 : Color.WHITE);
        skipLabel.setTypeface(chosen ? Ui.BOLD : Ui.MEDIUM);
        skipKey.setVisibility(chosen ? View.VISIBLE : View.GONE);
        skip.setPadding(ui.dp(chosen ? 10 : 18), ui.dp(9), ui.dp(18), ui.dp(9));
        skip.setPivotX(skip.getWidth());   // grow away from the screen's edge, not into it
        skip.setPivotY(skip.getHeight());
        Motion.scale(skip, chosen ? 1.12f : 1f);
    }

    // -------------------------------------------------------------- the ending card

    private void showEnd() {
        List<MediaItem> picks = new ArrayList<>();
        if (!show) {
            List<MediaItem> from = extras.starTitles.isEmpty() ? extras.related : extras.starTitles;
            for (MediaItem m : from) { if (picks.size() < 3) picks.add(m); }
            if (picks.isEmpty()) { if (extras.relatedTried) endDone = true; return; } // still loading: look again next tick
        } else if (nextLabel == null) {
            endDone = true; // the last episode: nothing follows
            return;
        }
        endDone = true;

        end = new LinearLayout(a);
        end.setOrientation(LinearLayout.VERTICAL);
        end.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        GradientDrawable back = ui.round(0xF0161922, 16);
        back.setStroke(ui.dp(1), 0x33FFFFFF);
        end.setBackground(back);
        end.setClickable(true); // a tap on the card's own body isn't a tap on the film

        if (show) {
            end.addView(caption(a.getString(R.string.player_up_next)));
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (backdrop != null && !backdrop.isEmpty()) {
                ImageView still = new Art(a);
                still.setScaleType(ImageView.ScaleType.CENTER_CROP);
                still.setBackground(ui.round(0xFF22252E, 8));
                still.setClipToOutline(true);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.dp(112), ui.dp(63));
                slp.rightMargin = ui.dp(12);
                row.addView(still, slp);
                images.load(still, backdrop, ui.dp(112), false, null);
            }
            LinearLayout words = new LinearLayout(a);
            words.setOrientation(LinearLayout.VERTICAL);
            words.addView(ui.label(nextLabel, 15, Color.WHITE, Ui.BOLD));
            if (!nextTitle.isEmpty()) {
                TextView name = ui.label(nextTitle, 13, Ui.TEXT_DIM, null);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(name);
            }
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            end.addView(row);
            TextView play = ui.pill("▶  " + a.getString(R.string.player_play_now), 0x33FFFFFF, Color.WHITE);
            play.setOnClickListener(v -> host.playNext());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
            plp.topMargin = ui.dp(12);
            end.addView(play, plp);
            endPrimary = play;
        } else {
            boolean byStar = !extras.starTitles.isEmpty();
            end.addView(caption(byStar ? a.getString(R.string.player_if_you_love, extras.star) : a.getString(R.string.player_more_like)));
            LinearLayout row = new LinearLayout(a);
            row.setClipChildren(false);
            for (MediaItem m : picks) {
                View p = poster(m, 86);
                if (endPrimary == null) endPrimary = p;
                row.addView(p);
            }
            end.setClipChildren(false);
            end.addView(row);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(show ? ui.dp(340) : -2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(ui.dp(16), 0, ui.dp(36), ui.dp(44));
        root.addView(end, lp);
        end.setAlpha(0f);
        end.setTranslationX(ui.dp(24));
        end.animate().alpha(1f).translationX(0).setDuration(Motion.CROSSFADE).setInterpolator(Motion.STANDARD).start();
        // With the controls away, the remote lands on the card, as it does on Netflix.
        if (!host.barShowing() && endPrimary != null && host.usingRemote()) focus(endPrimary);
    }

    private void dismissEnd() {
        if (end == null) return;
        final View card = end;
        end = null;
        endPrimary = null;
        card.animate().alpha(0f).setDuration(Motion.FAST).withEndAction(() -> root.removeView(card)).start();
        root.requestFocus();
    }

    /** Puts the remote on a view even if the screen was last touched. */
    private static void focus(View v) {
        if (!v.requestFocus()) v.requestFocusFromTouch();
    }

    private TextView caption(String text) {
        TextView t = ui.label(text.toUpperCase(java.util.Locale.getDefault()), 11, Ui.ACCENT, Ui.BOLD);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, 0, 0, ui.dp(10));
        return t;
    }

    // ----------------------------------------------------------- more like this

    private void buildStrip() {
        strip = new LinearLayout(a);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setVisibility(View.GONE);
        strip.setPadding(0, ui.dp(16), 0, 0);
        strip.setClipChildren(false);
        TextView heading = ui.label(a.getString(R.string.player_more_like), 14, Color.WHITE, Ui.BOLD);
        heading.setPadding(0, 0, 0, ui.dp(8));
        strip.addView(heading);
        stripRow = new LinearLayout(a);
        stripRow.setClipChildren(false);
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        scroll.addView(stripRow);
        strip.addView(scroll);
        bar.addView(strip);
        bar.setClipChildren(false);
    }

    void openStrip() {
        if (stripOpen()) return;
        strip.setVisibility(View.VISIBLE);
        Motion.fadeIn(strip, Motion.FAST);
        fillStrip();
        // Opened a second time, the titles are already there: the remote still goes to the first.
        if (stripFilled && stripRow.getChildCount() > 0 && stripRow.getChildAt(0).isFocusable() && host.usingRemote()) focus(stripRow.getChildAt(0));
        host.holdBar();
    }

    void closeStrip() {
        if (!stripOpen()) return;
        boolean hadFocus = strip.hasFocus();
        strip.setVisibility(View.GONE);
        if (hadFocus) host.focusControls(); // back where Down was pressed, so Down opens it again
    }

    /** Puts the titles in once they're known; until then, three dots say they're on their way. */
    private void fillStrip() {
        if (stripFilled) return;
        List<MediaItem> titles = extras == null ? new ArrayList<>() : extras.related;
        stripRow.removeAllViews();
        if (titles.isEmpty()) {
            if (extras != null && extras.relatedTried) {
                stripRow.addView(ui.label(a.getString(R.string.player_nothing_similar), 13, Ui.TEXT_DIM, null));
                stripFilled = true;
            } else {
                stripRow.addView(new Loading.Dots(a, ui.dp(8), 0xFFFFFFFF), new LinearLayout.LayoutParams(ui.dp(56), ui.dp(30)));
            }
            return;
        }
        stripFilled = true;
        View first = null;
        for (int i = 0; i < titles.size() && i < STRIP_TITLES; i++) {
            View p = poster(titles.get(i), 92);
            if (first == null) first = p;
            stripRow.addView(p);
        }
        if (first != null && host.usingRemote()) focus(first);
    }

    /** A poster with its name under it. Choosing it leaves the film for that title's page. */
    private View poster(MediaItem m, int widthDp) {
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        ImageView art = new Art(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(ui.round(0xFF22252E, 8));
        art.setClipToOutline(true);
        ui.focusRing(art, 8);
        art.setDuplicateParentStateEnabled(true); // the ring follows the cell's focus
        cell.addView(art, new LinearLayout.LayoutParams(ui.dp(widthDp), ui.dp(widthDp * 3 / 2)));
        String url = m.poster(ui.dp(widthDp));
        if (url != null) images.load(art, url, ui.dp(widthDp), false, null);
        TextView name = ui.label(m.title, 11, 0xFFE6E8EE, null);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, ui.dp(4), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(ui.dp(widthDp), -2));
        cell.setContentDescription(m.title);
        cell.setOnClickListener(v -> host.openTitle(m));
        cell.setOnFocusChangeListener((v, has) -> { Motion.scale(v, has ? 1.06f : 1f); if (has) host.holdBar(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(10);
        cell.setLayoutParams(lp);
        return cell;
    }
}
