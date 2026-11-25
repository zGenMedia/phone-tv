package com.zgenmedia.tvhome;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The page for one network or studio: its logo and name, a button to keep it in
 * the Library, and everything it has made as a grid of posters.
 *
 * The grid opens on the company's best known work. Two chips re-order it (best
 * known, newest, oldest, A to Z) and narrow it to movies or shows; the choice is
 * remembered, since someone who wants "newest from HBO" wants it every time.
 *
 * A company opened before shows last time's list at once and refreshes quietly
 * when that list is over a week old.
 */
final class CompanyScreen implements Overlay {
    private static final int SORT_KNOWN = -1; // the order the list arrives in
    private static final int[] SORTS = {R.string.sort_best_known, R.string.sort_newest, R.string.sort_oldest, R.string.sort_title};
    private static final int[] SORT_VALUES = {SORT_KNOWN, Library.SORT_NEWEST, Library.SORT_OLDEST, Library.SORT_TITLE};
    private static final int[] ONLY = {R.string.only_all, R.string.only_movies, R.string.only_shows};
    private static final String SORT = "company", FILTER = "companyOnly";

    private final HomeActivity a;
    private final Ui ui;
    private final FrameLayout root;
    private final Library.Company company;

    private List<MediaItem> titles; // null until loaded
    private String error;
    private View firstControl;
    private String focusAfterBuild;

    CompanyScreen(HomeActivity activity, Library.Company company) {
        a = activity;
        ui = activity.ui;
        this.company = company;
        root = new FrameLayout(activity);
        root.setClickable(true);
        titles = a.studios.cached(company);
        if (titles == null || a.studios.stale(company)) fetch();
        build();
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return firstControl; }
    @Override public boolean onBack() { return false; }

    @Override
    public void rebuild() {
        build();
        if (a.usingRemote() && root.getVisibility() == View.VISIBLE && firstControl != null) firstControl.requestFocus();
    }

    @Override
    public boolean move(View focused, int direction) {
        View next = HomeActivity.nextFocus(root, focused, direction);
        if (next != null && next != focused) next.requestFocus();
        return true;
    }

    private void fetch() {
        error = null;
        new Thread(() -> {
            List<MediaItem> got = null;
            String problem = null;
            try { got = a.studios.titles(company); }
            catch (IOException | JSONException e) { problem = CatalogRepository.describe(e); }
            final List<MediaItem> loaded = got;
            final String err = problem;
            a.main.post(() -> {
                if (loaded != null) titles = loaded;
                else if (titles == null) error = err; // an old list stays up if the refresh failed
                View focused = a.focused();
                boolean hadFocus = focused != null && HomeActivity.isInside(focused, root);
                build();
                if (hadFocus && a.usingRemote() && firstControl != null) firstControl.requestFocus();
            });
        }, "phonetv-company").start();
    }

    private void build() {
        root.removeAllViews();
        root.setBackgroundColor(Ui.BG);
        firstControl = null;
        boolean land = a.land();

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> a.images.flush());
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setPadding(0, a.insetTop + ui.dp(58), 0, a.insetBottom + ui.dp(28));
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // Header: the logo tile, then name, what it is, and Save.
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(a.gutter(), 0, a.insetRight + ui.dp(20), ui.dp(14));
        int tileW = ui.dp(land ? 168 : 120);
        head.addView(a.companyTile(company, tileW), new LinearLayout.LayoutParams(tileW, tileW * 9 / 16));
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(ui.dp(18), 0, 0, 0);
        TextView name = ui.label(company.name, land ? 30 : 22, Ui.TEXT, Ui.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name);
        String what = a.getString(Studios.NETWORK.equals(company.kind) ? R.string.company_network : R.string.company_studio);
        TextView kind = ui.label(titles == null ? what : what + "  ·  " + a.getResources().getQuantityString(R.plurals.company_titles, titles.size(), titles.size()),
                13, Ui.TEXT_DIM, Ui.MEDIUM);
        kind.setPadding(0, ui.dp(4), 0, ui.dp(12));
        text.addView(kind);
        boolean saved = a.library.has(company);
        TextView save = saved ? ui.pill(a.getString(R.string.company_saved), Ui.SURFACE, Ui.TEXT) : ui.primary(a.getString(R.string.company_save));
        save.setOnClickListener(v -> { a.toggleSaved(company); rebuild(); });
        LinearLayout buttons = new LinearLayout(a);
        buttons.addView(save);
        text.addView(buttons);
        head.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        column.addView(head);
        firstControl = save;
        head.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> scroll.setTag(R.id.top_zone, b));

        if (titles != null && !titles.isEmpty()) {
            int sort = a.prefs.librarySort(SORT) % SORTS.length, only = a.prefs.librarySort(FILTER) % ONLY.length;
            LinearLayout bar = new LinearLayout(a);
            bar.setPadding(a.gutter(), ui.dp(4), a.insetRight + ui.dp(20), ui.dp(10));
            bar.addView(cycle(SORTS, sort, SORT), a.chipSpacing(6));
            bar.addView(cycle(ONLY, only, FILTER), a.chipSpacing(6));
            column.addView(bar);

            List<MediaItem> shown = Library.filter(new ArrayList<>(titles), only);
            if (SORT_VALUES[sort] != SORT_KNOWN) Library.sortTitles(shown, SORT_VALUES[sort]);
            if (shown.isEmpty()) {
                column.addView(note(a.getString(R.string.library_none_match)));
            } else {
                int[] plan = a.gridPlan((int) (ui.dp(land ? 116 : 104) * Prefs.CARD_SCALES[a.prefs.cardSize()]));
                List<View> cells = new ArrayList<>();
                for (MediaItem m : shown) cells.add(a.posterCell(m, null, m.year, plan[1], () -> a.showDetails(m), null));
                a.addGrid(column, cells, plan);
            }
        } else if (error != null) {
            column.addView(note(a.getString(R.string.company_failed, error)));
            TextView retry = ui.pill(a.getString(R.string.retry), Ui.SURFACE, Ui.TEXT);
            retry.setOnClickListener(v -> { fetch(); build(); });
            LinearLayout line = new LinearLayout(a);
            line.setPadding(a.gutter(), 0, 0, 0);
            line.addView(retry);
            column.addView(line);
        } else {
            column.addView(note(a.getString(titles == null ? R.string.company_loading : R.string.company_none)));
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
        column.post(a.images::flush);

        if (focusAfterBuild != null) {
            View again = root.findViewWithTag(focusAfterBuild);
            focusAfterBuild = null;
            if (again != null && a.usingRemote()) again.post(again::requestFocus);
        }
    }

    /** A chip that steps through its choices on each press and keeps the remote on itself. */
    private TextView cycle(int[] labels, int current, String pref) {
        TextView chip = a.chip(a.getString(labels[current]) + "  ⇅", false);
        chip.setTag(pref);
        chip.setOnClickListener(v -> {
            a.prefs.setLibrarySort(pref, (current + 1) % labels.length);
            focusAfterBuild = pref;
            build();
        });
        return chip;
    }

    private TextView note(String text) {
        TextView t = ui.label(text, 14, Ui.MUTED, null);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(a.gutter(), ui.dp(16), a.insetRight + ui.dp(20), ui.dp(12));
        return t;
    }
}
