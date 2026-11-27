package com.zgenmedia.tvhome;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sources for one movie or episode, from the user's add-ons, the way Nuvio
 * lists them: the title's artwork behind, a chip per add-on to narrow the list,
 * and each source as a card.
 *
 * A card answers "which one?" at a glance: the picture quality large on the left with
 * any extras under it (HDR, Atmos), then the file's name, where it comes from and how
 * big it is. Anything unusual is said in words: a source that opens in another app, or
 * one this device probably can't show. The one most likely to play well here is marked.
 *
 * A direct link plays in Phone TV's player. A torrent can't, so it offers Stremio
 * instead; a web page or YouTube video opens where it belongs. Long-press any
 * playable source to send it to another video app.
 */
final class StreamScreen implements Overlay {
    private final HomeActivity a;
    private final Ui ui;
    private final FrameLayout root;
    private final MediaItem item;
    private final String episodeId, episodeLabel;
    private final String nextEpisodeId, nextLabel;
    /** "Play the first source" may act on this page. False when the viewer asked to see the list. */
    private final boolean mayAutoPlay;
    /** How many other sources ride along with an auto-played one, to fall back on. */
    private static final int SPARES = 5;

    private List<Addons.Stream> streams;  // null while loading
    private String onlyAddon;             // null: all add-ons
    private View firstFocus;

    StreamScreen(HomeActivity activity, MediaItem item, String episodeId, String episodeLabel, String nextEpisodeId, String nextLabel, boolean mayAutoPlay) {
        this.mayAutoPlay = mayAutoPlay;
        a = activity;
        ui = activity.ui;
        this.item = item;
        this.episodeId = episodeId;
        this.episodeLabel = episodeLabel == null ? "" : episodeLabel;
        this.nextEpisodeId = nextEpisodeId;
        this.nextLabel = nextLabel;
        root = new FrameLayout(activity);
        root.setClickable(true);
        build();
        fetch();
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return firstFocus; }
    @Override public boolean onBack() { return false; }
    @Override public void rebuild() { build(); if (a.usingRemote() && root.getVisibility() == View.VISIBLE && firstFocus != null) firstFocus.requestFocus(); }

    @Override
    public boolean move(View focused, int direction) {
        View next = HomeActivity.nextFocus(root, focused, direction);
        if (next != null && next != focused) next.requestFocus();
        return true;
    }

    private void fetch() {
        final String type = item.isShow() ? "series" : "movie";
        final String id = episodeId != null ? episodeId : item.id;
        new Thread(() -> {
            List<Addons.Stream> found = Addons.of(a).streams(type, id);
            // The quality chosen in Settings goes to the top; everything else stays, underneath.
            VideoSupport.sortByPreference(found, StreamScreen::describe, a.prefs.preferredQuality());
            a.main.post(() -> {
                streams = found;
                if (mayAutoPlay && a.prefs.autoPlayFirst()) {
                    // Auto-play takes the first source this device can show, and brings the next few
                    // along: if the first turns out to be dead, the player moves on by itself.
                    // Sources this device likely can't show a picture for go to the back of the line.
                    List<Addons.Stream> line = new ArrayList<>(), doubtful = new ArrayList<>();
                    for (Addons.Stream s : found) {
                        if (!s.playable()) continue;
                        (VideoSupport.likelyToShow(describe(s)) ? line : doubtful).add(s);
                    }
                    line.addAll(doubtful);
                    if (!line.isEmpty()) {
                        a.closeOverlay();
                        play(line.get(0), line.subList(1, Math.min(line.size(), 1 + SPARES)));
                        return;
                    }
                }
                View f = a.focused();
                boolean had = f != null && HomeActivity.isInside(f, root);
                build();
                if (had && a.usingRemote() && firstFocus != null) firstFocus.requestFocus();
            });
        }, "phonetv-sources").start();
    }

    private void build() {
        root.removeAllViews();
        root.setBackgroundColor(Ui.BG);
        firstFocus = null;
        boolean land = a.land();

        // Artwork behind, faded towards the list, as on the title page.
        String art = item.backdrop(land ? 1280 : 780);
        if (art != null) {
            ImageView back = new Art(a);
            back.setScaleType(ImageView.ScaleType.CENTER_CROP);
            back.setAlpha(0.35f);
            root.addView(back, new FrameLayout.LayoutParams(-1, -1));
            a.images.load(back, art, land ? 1280 : 780, false, null);
            View shade = new View(a);
            shade.setBackground(Ui.fade(land, Ui.BG, new float[]{0f, 0.5f, 1f}, new float[]{0.45f, 0.80f, 0.97f}));
            root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        }

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> a.images.flush());
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(a.gutter(), a.insetTop + ui.dp(64), a.insetRight + ui.dp(24), a.insetBottom + ui.dp(28));
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView name = ui.label(item.title, land ? 28 : 22, Ui.TEXT, Ui.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(name);
        TextView sub = ui.label(episodeLabel.isEmpty() ? a.getString(R.string.sources_heading) : episodeLabel + "  ·  " + a.getString(R.string.sources_heading), 14, Ui.TEXT_DIM, null);
        sub.setPadding(0, ui.dp(4), 0, ui.dp(12));
        column.addView(sub);

        if (streams == null) {
            View asking = Loading.line(a, a.getString(R.string.sources_loading, Addons.of(a).all().size()));
            asking.setPadding(0, ui.dp(10), 0, ui.dp(12)); // this page has its own side margins
            column.addView(asking);
            column.addView(Loading.rows(a, 4, 72));
        } else if (streams.isEmpty()) {
            column.addView(note(a.getString(R.string.sources_none)));
        } else {
            column.addView(filterChips());
            List<Addons.Stream> showing = new ArrayList<>();
            for (Addons.Stream s : streams) if (onlyAddon == null || onlyAddon.equals(s.addon)) showing.add(s);
            TextView how = ui.label(a.getResources().getQuantityString(a.usingRemote() ? R.plurals.sources_how_remote : R.plurals.sources_how_touch,
                    showing.size(), showing.size()), 12, Ui.MUTED, null);
            how.setPadding(0, 0, 0, ui.dp(10));
            column.addView(how);
            // The same pick auto-play would make: the first one that plays here and should show a picture.
            Addons.Stream best = null;
            for (Addons.Stream s : showing) if (s.playable() && VideoSupport.likelyToShow(describe(s))) { best = s; break; }
            for (Addons.Stream s : showing) column.addView(card(s, s == best && showing.size() > 1));
        }

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
        if (firstFocus == null) firstFocus = back;
    }

    /** "All · 42", then one chip per add-on with its count. */
    private View filterChips() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Addons.Stream s : streams) counts.put(s.addon, counts.getOrDefault(s.addon, 0) + 1);
        LinearLayout row = new LinearLayout(a);
        TextView all = a.chip(a.getString(R.string.sources_all, streams.size()), onlyAddon == null);
        all.setOnClickListener(v -> { onlyAddon = null; rebuildKeepingChip(0); });
        row.addView(all, a.chipSpacing(6));
        int i = 1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            final String addon = e.getKey();
            final int index = i++;
            TextView chip = a.chip(addon + " · " + e.getValue(), addon.equals(onlyAddon));
            chip.setOnClickListener(v -> { onlyAddon = addon; rebuildKeepingChip(index); });
            row.addView(chip, a.chipSpacing(6));
        }
        if (counts.size() < 2) row.setVisibility(View.GONE); // one add-on: nothing to narrow
        HorizontalScrollView strip = new HorizontalScrollView(a);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        strip.setPadding(0, 0, 0, ui.dp(10));
        strip.addView(row);
        if (row.getVisibility() == View.VISIBLE) firstFocus = all;
        return strip;
    }

    private void rebuildKeepingChip(int index) {
        build();
        if (!a.usingRemote()) return;
        // The chip row is the first thing in the list; put the remote back on the same chip.
        View chips = findChips(root);
        if (chips instanceof LinearLayout && ((LinearLayout) chips).getChildCount() > index) ((LinearLayout) chips).getChildAt(index).requestFocus();
    }

    private static View findChips(View v) {
        if (v instanceof HorizontalScrollView) return ((HorizontalScrollView) v).getChildAt(0);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { View hit = findChips(g.getChildAt(i)); if (hit != null) return hit; }
        }
        return null;
    }

    /** Everything an add-on said about a source, in one string, for reading its quality from. */
    private static String describe(Addons.Stream s) {
        return s.name + " " + s.title + " " + (s.filename == null ? "" : s.filename);
    }

    private View card(Addons.Stream s, boolean best) {
        SourceLabel facts = SourceLabel.of(s);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        card.setBaselineAligned(false);
        // Full-width cards: a ring and a lighter fill on focus, no zoom (zooming a full row crops it).
        card.setFocusable(true);
        card.setClickable(true);
        android.graphics.drawable.StateListDrawable fill = new android.graphics.drawable.StateListDrawable();
        fill.addState(new int[]{android.R.attr.state_focused}, ui.round(0x40FFFFFF, 14));
        fill.addState(new int[]{}, ui.round(0x1AFFFFFF, 14));
        card.setBackground(ui.ripple(fill));
        ui.focusRing(card, 14);

        // Left: the quality, large. An add-on that doesn't state one keeps its own first line there.
        LinearLayout badge = new LinearLayout(a);
        badge.setOrientation(LinearLayout.VERTICAL);
        boolean known = !facts.quality.isEmpty();
        TextView quality = ui.label(known ? facts.quality : s.name.split("\\n")[0].trim(), known ? 20 : 14, Ui.ACCENT, Ui.BOLD);
        quality.setMaxLines(2);
        badge.addView(quality);
        if (!facts.extras.isEmpty()) {
            TextView extras = ui.label(TextUtils.join(" · ", facts.extras), 11, Ui.TEXT_DIM, Ui.MEDIUM);
            extras.setMaxLines(2);
            extras.setPadding(0, ui.dp(2), 0, 0);
            badge.addView(extras);
        }
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ui.dp(a.land() ? 150 : 96), -2);
        qlp.rightMargin = ui.dp(14);
        card.addView(badge, qlp);

        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        if (best) {
            TextView tag = ui.label(a.getString(R.string.sources_best), 11, Ui.ACCENT, Ui.BOLD);
            tag.setLetterSpacing(0.04f);
            tag.setPadding(0, 0, 0, ui.dp(4));
            text.addView(tag);
        }
        TextView name = ui.label(facts.name.isEmpty() ? s.addon : facts.name, 13, Ui.TEXT, null);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(0, 1.15f);
        text.addView(name);
        if (!facts.more.isEmpty()) {
            TextView more = ui.label(facts.more, 12, Ui.MUTED, null);
            more.setMaxLines(2);
            more.setEllipsize(TextUtils.TruncateAt.END);
            more.setPadding(0, ui.dp(3), 0, 0);
            text.addView(more);
        }

        // Where it comes from and how big it is; the add-on's own label is kept whole, on one line.
        List<String> from = new ArrayList<>();
        String label = s.name.replaceAll("\\s+", " ").trim();
        from.add(label.isEmpty() ? s.addon : label.toLowerCase(java.util.Locale.ROOT).contains(s.addon.toLowerCase(java.util.Locale.ROOT)) ? label : s.addon + " · " + label);
        if (facts.size > 0) from.add(Addons.sizeLabel(facts.size));
        TextView meta = ui.label(TextUtils.join("  ·  ", from), 12, Ui.MUTED, Ui.MEDIUM);
        meta.setMaxLines(1);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, ui.dp(6), 0, 0);
        text.addView(meta);

        // Only the exceptions are spelled out: a source that plays right here needs no label.
        String note = !s.playable() ? a.getString(s.infoHash != null ? R.string.sources_torrent : s.ytId != null ? R.string.sources_youtube : R.string.sources_web)
                : !VideoSupport.likelyToShow(describe(s)) ? a.getString(R.string.sources_may_not_show) : null;
        if (note != null) {
            TextView warn = ui.label(note, 12, s.playable() ? 0xFFFFB547 : Ui.TEXT_DIM, Ui.MEDIUM);
            warn.setPadding(0, ui.dp(4), 0, 0);
            text.addView(warn);
        }
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        card.setContentDescription(TextUtils.join(", ", nonEmpty(best ? a.getString(R.string.sources_best) : "", facts.quality,
                TextUtils.join(", ", facts.extras), facts.name, TextUtils.join(", ", from), note == null ? "" : note)));

        card.setOnClickListener(v -> choose(s));
        card.setOnLongClickListener(v -> { if (s.playable()) a.playElsewhere(s.url, item.title); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = ui.dp(8);
        card.setLayoutParams(lp);
        if (firstFocus == null) firstFocus = card;
        return card;
    }

    private static List<String> nonEmpty(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) out.add(p);
        return out;
    }

    private void choose(Addons.Stream s) {
        if (s.playable()) { play(s, null); return; } // chosen by hand: play exactly that one
        if (s.infoHash != null) {
            // Torrents need a torrent engine; Stremio has one. Its detail link opens the same title.
            a.openWith(Prefs.PLAY_STREMIO, item, episodeId);
        } else if (s.ytId != null) {
            a.openUrl("https://www.youtube.com/watch?v=" + s.ytId, null);
        } else if (s.externalUrl != null) {
            a.openUrl(s.externalUrl, null);
        }
    }

    private void play(Addons.Stream s, List<Addons.Stream> spares) {
        // Tuned in from a channel: join where the channel is. Otherwise pick up where the viewer left off.
        long live = a.liveOffset(item.id, episodeId);
        a.startPlayer(item, episodeId, episodeLabel, s.url, s.headers, s.addon,
                live > 0 ? live : Progress.of(a).resumeAt(item.id, episodeId), nextEpisodeId, nextLabel, spares);
    }

    private TextView note(String text) {
        TextView t = ui.label(text, 14, Ui.MUTED, null);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, ui.dp(16), 0, ui.dp(10));
        return t;
    }
}
