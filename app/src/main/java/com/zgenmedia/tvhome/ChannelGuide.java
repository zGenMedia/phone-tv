package com.zgenmedia.tvhome;

import android.graphics.drawable.StateListDrawable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Channels tab: a TV guide for {@link Channels}.
 *
 * One row per channel, each showing what's on right now (with how far in it is) and
 * what's next. OK tunes in at the live point; a long press offers the title's page,
 * the start of the title, and everything else that channel's network has made.
 *
 * Rows fill in as their line-ups arrive, and the clock on each row moves by itself:
 * the page is never rebuilt just because a minute passed, so the remote stays put.
 */
final class ChannelGuide {
    private static final long TICK_MS = 30_000;
    private static final String[] GROUP_KEYS = {"all", "networks", "studios", "genres", "mine"};

    private final HomeActivity a;
    private final Ui ui;
    private List<Channels.Channel> channels = new ArrayList<>();
    private String builtFor = "";  // the saved companies the list was built with
    private boolean loading, guideLoaded;
    private int group = -1;        // -1: all groups
    /** Each visible row's "show what's on now", by channel key. Main thread only. */
    private final Map<String, Runnable> rows = new HashMap<>();
    private View page;             // the column the rows live in, to know when to stop ticking
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (page == null || !page.isAttachedToWindow()) return; // the tab was left
            for (Runnable row : rows.values()) row.run();
            a.main.postDelayed(this, TICK_MS);
        }
    };

    ChannelGuide(HomeActivity activity) {
        a = activity;
        ui = activity.ui;
    }

    void build(LinearLayout column) {
        rows.clear();
        page = column;
        refreshChannelList();

        TextView title = ui.label(a.getString(R.string.channels_title), a.land() ? 26 : 22, Ui.TEXT, Ui.BOLD);
        title.setPadding(a.gutter(), ui.dp(4), a.insetRight + ui.dp(20), 0);
        column.addView(title);
        TextView about = ui.label(a.getString(R.string.channels_about), 13, Ui.MUTED, null);
        about.setLineSpacing(0, 1.2f);
        about.setPadding(a.gutter(), ui.dp(4), a.insetRight + ui.dp(20), ui.dp(6));
        column.addView(about);

        // Narrow the guide: everything, or one kind of channel.
        boolean anyMine = false;
        for (Channels.Channel c : channels) anyMine |= c.group == Channels.MINE;
        if (group == Channels.MINE && !anyMine) group = -1;
        int[] labels = {R.string.channels_all, R.string.channels_networks, R.string.channels_studios, R.string.channels_genres, R.string.channels_mine};
        LinearLayout bar = a.chipBar(column);
        for (int i = 0; i < labels.length; i++) {
            final int g = i - 1;
            if (g == Channels.MINE && !anyMine) continue;
            a.addChoice(bar, "ch:group:" + GROUP_KEYS[i], a.getString(labels[i]), group == g, () -> { group = g; a.renderPreservingScroll(); });
        }

        for (Channels.Channel c : channels) {
            if (group >= 0 && c.group != group) continue;
            column.addView(row(c));
        }
        column.post(a.images::flush);
        load();
        a.main.removeCallbacks(tick);
        a.main.postDelayed(tick, TICK_MS);
    }

    /** Saved networks and studios become channels of their own; rebuild the list when that set changes. */
    private void refreshChannelList() {
        List<Library.Company> saved = a.library.companies(false);
        StringBuilder sig = new StringBuilder();
        for (Library.Company c : saved) sig.append(c.id).append(',');
        if (!channels.isEmpty() && sig.toString().equals(builtFor)) return;
        Map<String, Channels.Channel> had = new HashMap<>();
        for (Channels.Channel c : channels) had.put(c.key(), c);
        List<Channels.Channel> fresh = Channels.guide(saved);
        for (int i = 0; i < fresh.size(); i++) {
            Channels.Channel old = had.get(fresh.get(i).key());
            if (old != null && old.titles != null) fresh.get(i).setTitles(old.titles); // keep what's loaded
        }
        channels = fresh;
        builtFor = sig.toString();
    }

    // -------------------------------------------------------------------- rows

    private View row(final Channels.Channel c) {
        boolean land = a.land();
        LinearLayout card = new LinearLayout(a);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        card.setFocusable(true);
        card.setClickable(true);
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[]{android.R.attr.state_focused}, ui.round(0x33FFFFFF, 14));
        fill.addState(new int[]{}, ui.round(0x14FFFFFF, 14));
        card.setBackground(ui.ripple(fill));
        ui.focusRing(card, 14);
        a.focusKey(card, "ch:" + c.key());

        // Channel number and name, the way a guide's left column reads.
        LinearLayout id = new LinearLayout(a);
        id.setOrientation(LinearLayout.VERTICAL);
        id.addView(ui.label(String.valueOf(c.number), 11, Ui.MUTED, Ui.MEDIUM));
        TextView name = ui.label(c.name, land ? 15 : 13, Ui.TEXT, Ui.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        id.addView(name);
        card.addView(id, new LinearLayout.LayoutParams(ui.dp(land ? 124 : 78), -2));

        // A still from what's on.
        final int thumbW = ui.dp(land ? 132 : 92);
        FrameLayout thumb = new FrameLayout(a);
        thumb.setBackground(ui.round(Ui.SURFACE, 8));
        thumb.setClipToOutline(true);
        final ImageView still = new Art(a);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.addView(still, new FrameLayout.LayoutParams(-1, -1));
        final View waiting = new Loading.Dots(a, ui.dp(7), Ui.ACCENT);
        thumb.addView(waiting, new FrameLayout.LayoutParams(ui.dp(44), ui.dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(thumbW, thumbW * 9 / 16);
        tlp.rightMargin = ui.dp(12);
        card.addView(thumb, tlp);

        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        final TextView now = ui.label("", land ? 16 : 14, Ui.TEXT, Ui.BOLD);
        now.setMaxLines(1);
        now.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(now);
        final TextView when = ui.label("", 12, Ui.TEXT_DIM, Ui.MEDIUM);
        when.setMaxLines(1);
        when.setPadding(0, ui.dp(2), 0, ui.dp(6));
        text.addView(when);
        final FrameLayout track = new FrameLayout(a);
        track.setBackground(ui.round(0x33FFFFFF, 2));
        final View done = new View(a);
        done.setBackground(ui.round(Ui.ACCENT, 2));
        track.addView(done, new FrameLayout.LayoutParams(0, -1));
        text.addView(track, new LinearLayout.LayoutParams(-1, ui.dp(3)));
        final TextView next = ui.label("", 12, Ui.MUTED, null);
        next.setMaxLines(1);
        next.setEllipsize(TextUtils.TruncateAt.END);
        next.setPadding(0, ui.dp(6), 0, 0);
        text.addView(next);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

        final String[] showing = {null}; // the title whose still is up, so it's only loaded on a change
        final Runnable show = () -> {
            List<Channels.Slot> on = Channels.lineup(c, System.currentTimeMillis(), 2);
            if (on.isEmpty()) {
                now.setText(c.error != null ? a.getString(R.string.channels_failed) : a.getString(R.string.channels_loading));
                when.setText(c.error != null ? c.error : "");
                next.setText("");
                track.setVisibility(View.INVISIBLE);
                waiting.setVisibility(c.error != null ? View.GONE : View.VISIBLE);
                return;
            }
            waiting.setVisibility(View.GONE);
            Channels.Slot s = on.get(0);
            long t = System.currentTimeMillis();
            now.setText(s.item.title);
            long left = Math.max(1, (s.end - t + 59_999) / 60_000);
            String times = a.getString(R.string.channels_when, clock(s.start), clock(s.end),
                    a.getResources().getQuantityString(R.plurals.channels_left, (int) left, (int) left));
            // A real premiere at its true time is worth pointing out.
            when.setText(s.real ? a.getString(R.string.channels_real, times) : times);
            when.setTextColor(s.real ? Ui.ACCENT : Ui.TEXT_DIM);
            track.setVisibility(View.VISIBLE);
            int w = track.getWidth() > 0 ? track.getWidth() : ui.dp(160);
            done.getLayoutParams().width = w * s.percent(t) / 100;
            done.requestLayout();
            next.setText(a.getString(R.string.channels_next, clock(s.end), on.get(1).item.title));
            if (!s.item.id.equals(showing[0])) {
                showing[0] = s.item.id;
                still.setImageDrawable(null);
                a.images.defer(card, () -> a.images.load(still, s.item.backdrop(thumbW), thumbW, false, null));
                a.images.flush();
            }
            card.setContentDescription(c.name + ": " + s.item.title);
        };
        rows.put(c.key(), show);
        show.run();
        track.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> { if (r - l != or - ol) show.run(); });

        card.setOnClickListener(v -> {
            List<Channels.Slot> on = Channels.lineup(c, System.currentTimeMillis(), 1);
            if (on.isEmpty()) { if (c.error != null) { c.error = null; show.run(); load(); } return; }
            a.tuneChannel(c);
        });
        card.setOnLongClickListener(v -> { options(c); return true; });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(a.gutter(), ui.dp(8), a.insetRight + ui.dp(20), 0);
        card.setLayoutParams(lp);
        return card;
    }

    private void options(Channels.Channel c) {
        List<Channels.Slot> on = Channels.lineup(c, System.currentTimeMillis(), 1);
        if (on.isEmpty()) return;
        final Channels.Slot s = on.get(0);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add(a.getString(R.string.channels_join_live));
        acts.add(() -> a.tuneIn(c, s, true));
        labels.add(a.getString(R.string.channels_from_start));
        acts.add(() -> a.tuneIn(c, s, false));
        labels.add(a.getString(R.string.channels_about_title));
        acts.add(() -> a.showDetails(s.item));
        if (c.company != null) {
            labels.add(a.getString(R.string.channels_everything, c.name));
            acts.add(() -> a.showCompany(c.company));
        }
        labels.add(a.getString(R.string.cancel));
        acts.add(null);
        a.showNotice(c.number + "  " + c.name, a.getString(R.string.channels_on_now, s.item.title), labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    private String clock(long at) {
        return android.text.format.DateFormat.getTimeFormat(a).format(new java.util.Date(at));
    }

    // ----------------------------------------------------------------- loading

    /**
     * Fills in every channel that has no line-up yet, one after another. What was fetched
     * before comes straight off disk; only stale or missing line-ups go to the network,
     * with a short pause between them so the data source isn't hammered.
     */
    private void load() {
        if (loading) return;
        loading = true;
        final List<Channels.Channel> todo = new ArrayList<>(channels);
        new Thread(() -> {
            // Tonight's real premieres first: one small download a day, shared by every channel.
            if (!guideLoaded) {
                final java.util.Map<String, List<TvGuide.Airing>> tonight = TvGuide.today(a);
                a.main.post(() -> {
                    guideLoaded = true;
                    for (Channels.Channel c : channels) {
                        List<TvGuide.Airing> aired = new ArrayList<>();
                        for (String name : c.guideNames) if (tonight.get(name) != null) aired.addAll(tonight.get(name));
                        java.util.Collections.sort(aired, (x, y) -> Long.compare(x.start, y.start));
                        c.aired = aired;
                        Runnable row = rows.get(c.key());
                        if (row != null) row.run();
                    }
                });
            }
            for (final Channels.Channel c : todo) {
                if (c.titles != null || c.error != null) continue;
                List<MediaItem> got = null;
                String problem = null;
                boolean asked = false;
                try {
                    if (c.company != null) {
                        got = a.studios.cached(c.company);
                        if (got == null || got.isEmpty() || a.studios.stale(c.company)) {
                            final List<MediaItem> old = got;
                            if (old != null && !old.isEmpty()) a.main.post(() -> ready(c, old, null)); // last week's, meanwhile
                            asked = true;
                            got = a.studios.titles(c.company);
                        }
                    } else {
                        asked = true;
                        got = CatalogRepository.browse("movie", "top", c.genre, 0);
                    }
                } catch (IOException | JSONException e) {
                    problem = CatalogRepository.describe(e);
                }
                final List<MediaItem> titles = got;
                final String err = problem;
                a.main.post(() -> ready(c, titles, err));
                if (asked) { try { Thread.sleep(350); } catch (InterruptedException e) { break; } }
            }
            a.main.post(() -> loading = false);
        }, "phonetv-channels").start();
    }

    private void ready(Channels.Channel c, List<MediaItem> titles, String err) {
        if (titles != null && !titles.isEmpty()) c.setTitles(titles);
        else if (c.titles == null) c.error = err != null ? err : a.getString(R.string.company_none);
        Runnable row = rows.get(c.key());
        if (row != null) row.run();
    }
}
