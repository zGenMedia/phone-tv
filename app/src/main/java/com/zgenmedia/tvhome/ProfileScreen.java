package com.zgenmedia.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * "Who's watching?": a circle per profile in its own color, plus one to add a
 * profile. Picking one switches the saved titles, actors, history and Continue
 * watching over to that person. Long-press a circle to rename or remove it.
 */
final class ProfileScreen implements Overlay {
    private final HomeActivity a;
    private final Ui ui;
    private final FrameLayout root;
    private View firstFocus;

    ProfileScreen(HomeActivity activity) {
        a = activity;
        ui = activity.ui;
        root = new FrameLayout(activity);
        root.setClickable(true);
        build();
    }

    /** The round initial used here and in the top bar. */
    static TextView avatar(HomeActivity a, Profiles.Profile p, int sizeDp, int textSp) {
        TextView t = a.ui.label(p.name.isEmpty() ? "?" : p.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT), textSp, 0xFFFFFFFF, Ui.BOLD);
        t.setGravity(Gravity.CENTER);
        a.ui.focusableCircle(t);
        ((GradientDrawable) t.getBackground()).setColor(p.color);
        t.setContentDescription(p.name);
        t.setLayoutParams(new LinearLayout.LayoutParams(a.ui.dp(sizeDp), a.ui.dp(sizeDp)));
        return t;
    }

    private void build() {
        root.removeAllViews();
        root.setBackgroundColor(Ui.BG);
        firstFocus = null;
        boolean land = a.land();
        int size = land ? 108 : 84;

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView heading = ui.label(a.getString(R.string.profile_who), land ? 30 : 24, Ui.TEXT, Ui.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, 0, 0, ui.dp(28));
        column.addView(heading);

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        row.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(16));
        row.setClipChildren(false);
        List<Profiles.Profile> all = Profiles.all(a);
        String current = Profiles.currentId(a);
        for (Profiles.Profile p : all) {
            LinearLayout cell = cell(avatar(a, p, size, land ? 40 : 32), p.name);
            View circle = cell.getChildAt(0);
            circle.setOnClickListener(v -> a.switchProfile(p.id));
            circle.setOnLongClickListener(v -> { a.manageProfile(p, this::rebuild); return true; });
            row.addView(cell);
            if (p.id.equals(current) || firstFocus == null) firstFocus = circle;
        }
        TextView plus = ui.label("+", land ? 40 : 32, Ui.TEXT, Ui.MEDIUM);
        plus.setGravity(Gravity.CENTER);
        ui.focusableCircle(plus);
        plus.setContentDescription(a.getString(R.string.profile_add));
        plus.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(size), ui.dp(size)));
        plus.setOnClickListener(v -> a.addProfile(this::rebuild));
        row.addView(cell(plus, a.getString(R.string.profile_add)));

        HorizontalScrollView strip = new HorizontalScrollView(a);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        strip.setClipChildren(false);
        strip.addView(row);
        column.addView(strip, new LinearLayout.LayoutParams(-2, -2));

        TextView hint = ui.label(a.getString(R.string.profile_hint), 13, Ui.MUTED, null);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), 0);
        column.addView(hint);
        root.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    private LinearLayout cell(View circle, String name) {
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        cell.setPadding(ui.dp(18), 0, ui.dp(18), 0);
        cell.addView(circle);
        TextView label = ui.label(name, 14, Ui.TEXT, Ui.MEDIUM);
        label.setMaxLines(1);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, ui.dp(12), 0, 0);
        cell.addView(label);
        return cell;
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return firstFocus; }
    @Override public boolean onBack() { return false; }
    @Override public void rebuild() { build(); if (a.usingRemote() && firstFocus != null) firstFocus.requestFocus(); }

    @Override
    public boolean move(View focused, int direction) {
        View next = HomeActivity.nextFocus(root, focused, direction);
        if (next != null && next != focused) next.requestFocus();
        return true;
    }
}
