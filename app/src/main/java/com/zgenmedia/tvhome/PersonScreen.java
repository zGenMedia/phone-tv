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
 * The page for one actor, laid out the way Nuvio lays out its own: portrait on
 * the left with the name, a line about when and where they were born and a
 * short biography beside it, then "Filmography" as one shelf of posters.
 *
 * Phone TV adds a button to keep the actor in the Library and two chips that
 * re-order and filter the shelf.
 *
 * It opens either from a known person (a saved actor, or one of a title's
 * cast) or from a bare name. A bare name is matched to a person first; a
 * person seen before shows what was fetched last time at once and refreshes
 * quietly when that is more than a week old.
 */
final class PersonScreen implements Overlay {
    private static final int[] SORTS = {R.string.sort_newest, R.string.sort_oldest, R.string.sort_title};
    private static final int[] SORT_VALUES = {Library.SORT_NEWEST, Library.SORT_OLDEST, Library.SORT_TITLE};
    private static final int[] ONLY = {R.string.only_all, R.string.only_movies, R.string.only_shows};
    private static final String SORT = "person", FILTER = "personOnly"; // where this page's order is remembered
    private static final int BIO_LONG = 240; // characters past which the biography is worth unfolding

    private final HomeActivity a;
    private final Ui ui;
    private final FrameLayout root;
    private final String askedName;

    private Library.Person person;   // null until a bare name has been matched
    private People.Profile profile;  // null until loaded
    private String error;
    private boolean loading, bioOpen;
    private View firstControl, sortChip, filterChip, bioText;

    PersonScreen(HomeActivity activity, Library.Person known, String name) {
        a = activity;
        ui = activity.ui;
        person = known;
        askedName = known != null ? known.name : name;
        root = new FrameLayout(activity);
        root.setClickable(true);
        if (person != null) profile = a.people.cached(person);
        if (person == null || profile == null || a.people.stale(person)) fetch();
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
        // The biography is as wide as the page, so "nearest below" would skip the button right under it.
        if (focused == bioText && direction == View.FOCUS_DOWN && firstControl != null) { firstControl.requestFocus(); return true; }
        View next = HomeActivity.nextFocus(root, focused, direction);
        if (next != null && next != focused) next.requestFocus();
        return true;
    }

    private void fetch() {
        loading = true;
        error = null;
        new Thread(() -> {
            Library.Person found = person;
            People.Profile loaded = null;
            String problem = null;
            try {
                if (found == null) found = a.people.find(askedName);
                if (found != null) loaded = a.people.load(found);
            } catch (IOException | JSONException e) {
                problem = CatalogRepository.describe(e);
            }
            final Library.Person who = found;
            final People.Profile got = loaded;
            final String err = problem;
            a.main.post(() -> {
                loading = false;
                if (who != null) person = who;
                if (got != null) profile = got;
                else if (profile == null) error = err != null ? err : a.getString(R.string.person_unknown, askedName);
                View focused = a.focused();
                boolean hadFocus = focused != null && HomeActivity.isInside(focused, root);
                build();
                if (hadFocus && a.usingRemote() && firstControl != null) firstControl.requestFocus();
            });
        }, "phonetv-person").start();
    }

    // ======================================================================= build

    private void build() {
        root.removeAllViews();
        root.setBackgroundColor(Ui.BG);
        firstControl = sortChip = filterChip = bioText = null;

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

        View hero = buildHero();
        column.addView(hero);
        // Anything focused up here keeps the page at the top.
        hero.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> scroll.setTag(R.id.top_zone, b));

        List<MediaItem> credits = profile == null ? null : profile.credits;
        if (credits != null && !credits.isEmpty()) {
            int sort = a.prefs.librarySort(SORT) % SORTS.length, only = a.prefs.librarySort(FILTER) % ONLY.length;
            List<MediaItem> shown = Library.sortTitles(Library.filter(new ArrayList<>(credits), only), SORT_VALUES[sort]);
            column.addView(filmographyHeader(shown.size(), sort, only));
            if (shown.isEmpty()) column.addView(note(a.getString(R.string.library_none_match)));
            else column.addView(shelf(shown));
        } else if (error != null) {
            column.addView(note(error));
        } else if (credits != null) {
            column.addView(note(a.getString(R.string.person_no_credits)));
        }
        View sources = sources();
        if (sources != null) column.addView(sources);

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
        if (firstControl == null) firstControl = back;
        column.post(a.images::flush);
    }

    /** Portrait on the left; name, the facts, the biography and the Save button beside it. */
    private View buildHero() {
        boolean land = a.land();
        LinearLayout hero = new LinearLayout(a);
        hero.setPadding(a.gutter(), 0, a.insetRight + ui.dp(20), ui.dp(8));

        int photoW = ui.dp(land ? 150 : 108);
        FrameLayout portrait = new FrameLayout(a);
        portrait.setBackground(ui.round(Ui.SURFACE, ui.cardRadius));
        portrait.setClipToOutline(true);
        TextView initials = ui.label(HomeActivity.initials(askedName), 34, Ui.MUTED, Ui.BOLD);
        initials.setGravity(Gravity.CENTER);
        portrait.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        if (person != null && !person.photo.isEmpty()) {
            ImageView face = new Ui.TopCropImageView(a);
            portrait.addView(face, new FrameLayout.LayoutParams(-1, -1));
            a.images.load(face, person.photo, photoW, false, null);
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(photoW, photoW * 3 / 2);
        plp.rightMargin = ui.dp(land ? 24 : 16);
        hero.addView(portrait, plp);

        LinearLayout about = new LinearLayout(a);
        about.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.label(person != null ? person.name : askedName, land ? 28 : 22, Ui.TEXT, Ui.BOLD);
        name.setMaxLines(land ? 1 : 2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        about.addView(name);

        if (profile == null) {
            if (loading) {
                View finding = Loading.line(a, a.getString(R.string.person_loading));
                finding.setPadding(0, ui.dp(8), 0, 0);
                about.addView(finding);
            }
        } else {
            String lived = lived();
            if (!lived.isEmpty()) about.addView(fact(lived, 8));
            if (!profile.place.isEmpty()) about.addView(fact(profile.place, lived.isEmpty() ? 8 : 3));
            String bio = !profile.text.isEmpty() ? profile.text : sentence(profile.about);
            if (!bio.isEmpty()) about.addView(biography(bio, land));
        }

        LinearLayout actions = new LinearLayout(a);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, ui.dp(12), 0, ui.dp(2));
        if (person != null) {
            boolean saved = a.library.has(person);
            TextView save = saved ? ui.pill(a.getString(R.string.person_saved), 0x33FFFFFF, Ui.TEXT) : ui.primary(a.getString(R.string.person_save));
            save.setOnClickListener(v -> {
                if (a.library.has(person)) a.library.remove(person); else a.library.add(person);
                a.libraryChanged(false);
                rebuild();
            });
            actions.addView(save);
            TextView share = ui.pill(a.getString(R.string.share), 0x33FFFFFF, Ui.TEXT);
            share.setOnClickListener(v -> a.share(Share.link(person), person.name, person.photo.replaceAll("\\?width=\\d+$", "?width=720")));
            LinearLayout.LayoutParams shareAt = new LinearLayout.LayoutParams(-2, -2);
            shareAt.leftMargin = ui.dp(10);
            actions.addView(share, shareAt);
            firstControl = save;
        }
        if (error != null) {
            TextView retry = ui.pill(a.getString(R.string.retry), 0x26FFFFFF, Ui.TEXT);
            retry.setOnClickListener(v -> { fetch(); rebuild(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = actions.getChildCount() == 0 ? 0 : ui.dp(8);
            actions.addView(retry, lp);
            if (firstControl == null) firstControl = retry;
        }
        if (actions.getChildCount() > 0) about.addView(actions);
        hero.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
        return hero;
    }

    private TextView fact(String text, float topDp) {
        TextView t = ui.label(text, 13, Ui.TEXT_DIM, null);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, ui.dp(topDp), 0, 0);
        return t;
    }

    /** A few lines of biography; a long one unfolds when pressed and folds again on a second press. */
    private TextView biography(String text, boolean land) {
        TextView bio = ui.label(text, 13, Ui.TEXT_DIM, null);
        bio.setLineSpacing(0, 1.3f);
        bio.setMaxLines(bioOpen ? 80 : land ? 4 : 5);
        bio.setEllipsize(TextUtils.TruncateAt.END);
        bio.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = -ui.dp(6); // the text lines up with the name; the focus ring sits outside it
        bio.setLayoutParams(lp);
        if (text.length() > BIO_LONG) {
            bio.setFocusable(true);
            bio.setClickable(true);
            ui.focusRing(bio, 8);
            bioText = bio;
            bio.setOnClickListener(v -> {
                bioOpen = !bioOpen;
                bio.setMaxLines(bioOpen ? 80 : land ? 4 : 5);
            });
        }
        return bio;
    }

    /** "Filmography", how many are showing, and the two chips that re-order and filter them. */
    private View filmographyHeader(int count, int sort, int only) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ui.label(a.getString(R.string.person_filmography), 15, Ui.TEXT, Ui.MEDIUM));
        TextView badge = ui.label(String.valueOf(count), 11, Ui.MUTED, Ui.MEDIUM);
        badge.setPadding(ui.dp(7), ui.dp(2), ui.dp(7), ui.dp(2));
        badge.setBackground(ui.round(Ui.SURFACE, 6));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = ui.dp(10);
        blp.rightMargin = ui.dp(16);
        row.addView(badge, blp);
        sortChip = cycle(row, SORTS, sort, SORT);
        filterChip = cycle(row, ONLY, only, FILTER);

        HorizontalScrollView strip = new HorizontalScrollView(a);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        strip.setPadding(a.gutter(), ui.dp(12), a.insetRight + ui.dp(12), ui.dp(2));
        strip.addView(row);
        return strip;
    }

    /** A chip that steps through a few choices each time it is pressed. */
    private View cycle(LinearLayout row, int[] labels, int current, String pref) {
        TextView chip = ui.pill(a.getString(labels[current]) + "  ⇅", 0x26FFFFFF, Ui.TEXT);
        chip.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6));
        chip.setOnClickListener(v -> {
            a.prefs.setLibrarySort(pref, (current + 1) % labels.length);
            build();
            // Put the remote back on the chip that was just pressed.
            View same = SORT.equals(pref) ? sortChip : filterChip;
            if (a.usingRemote() && same != null) same.requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(8);
        row.addView(chip, lp);
        return chip;
    }

    private View shelf(List<MediaItem> titles) {
        HorizontalScrollView shelf = new HorizontalScrollView(a);
        shelf.setHorizontalScrollBarEnabled(false);
        shelf.setClipToPadding(false);
        shelf.setClipChildren(false);
        shelf.setPadding(a.gutter(), ui.dp(10), a.insetRight + ui.dp(12), ui.dp(12));
        shelf.setOnScrollChangeListener((v, x, y, ox, oy) -> a.images.flush());
        LinearLayout row = new LinearLayout(a);
        row.setClipChildren(false);
        int w = (int) (ui.dp(a.land() ? 116 : 104) * Prefs.CARD_SCALES[a.prefs.cardSize()]);
        for (MediaItem m : titles) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
            lp.rightMargin = ui.dp(12);
            row.addView(a.posterCell(m, null, m.year, w, () -> a.showDetails(m), null), lp);
        }
        shelf.addView(row);
        return shelf;
    }

    /** Where the photo and the biography come from, each linked to its source. Null when there is neither. */
    private View sources() {
        final String photo = person == null ? null : People.photoCredit(person);
        final String text = profile == null || profile.text.isEmpty() ? "" : profile.textUrl;
        if (photo == null && text.isEmpty()) return null;
        LinearLayout row = new LinearLayout(a);
        if (photo != null) source(row, R.string.person_photo_credit, photo);
        if (!text.isEmpty()) source(row, R.string.person_text_credit, text);
        HorizontalScrollView strip = new HorizontalScrollView(a);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        strip.setPadding(a.gutter(), ui.dp(6), a.insetRight + ui.dp(12), 0);
        strip.addView(row);
        return strip;
    }

    private void source(LinearLayout row, int label, String url) {
        TextView pill = ui.pill(a.getString(label), 0x14FFFFFF, Ui.MUTED);
        pill.setTextSize(11);
        pill.setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5));
        pill.setOnClickListener(v -> a.openUrl(url, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(8);
        row.addView(pill, lp);
    }

    private TextView note(String text) {
        TextView t = ui.label(text, 13, Ui.MUTED, null);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(a.gutter(), ui.dp(14), a.insetRight + ui.dp(24), ui.dp(10));
        return t;
    }

    // ======================================================================= words

    /** "Born October 29, 1972 (age 54)", or both dates for someone who has died. Empty when the birth date is unknown. */
    private String lived() {
        if (profile.born.isEmpty()) return "";
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int age = years(profile.born, profile.died.isEmpty() ? today : profile.died);
        String line = profile.died.isEmpty() ? a.getString(R.string.person_born, day(profile.born))
                : a.getString(R.string.person_lived, day(profile.born), day(profile.died));
        return age < 0 ? line : line + a.getString(R.string.person_age, age);
    }

    /** "1972-10-29" as "October 29, 1972", "1972-10" as "October 1972"; a bare year stays as it is. */
    private static String day(String iso) {
        try {
            if (iso.length() == 10) return DateFormat.getDateInstance(DateFormat.LONG).format(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso));
            if (iso.length() == 7) return new SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(new SimpleDateFormat("yyyy-MM", Locale.US).parse(iso));
        } catch (ParseException | NullPointerException ignored) { /* shown as written */ }
        return iso;
    }

    /** Whole years from one yyyy-MM-dd date to another; -1 unless both are exact to the day. */
    private static int years(String from, String to) {
        if (from.length() != 10 || to.length() != 10) return -1;
        try {
            int years = Integer.parseInt(to.substring(0, 4)) - Integer.parseInt(from.substring(0, 4));
            return to.substring(5).compareTo(from.substring(5)) < 0 ? years - 1 : years;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Wikidata's descriptions are fragments ("American actress"); shown as a sentence when they stand in for a biography. */
    private static String sentence(String fragment) {
        if (fragment.isEmpty()) return "";
        return Character.toUpperCase(fragment.charAt(0)) + fragment.substring(1) + (fragment.endsWith(".") ? "" : ".");
    }
}
