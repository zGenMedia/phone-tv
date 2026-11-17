package com.zgenmedia.tvhome;

import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * What the phone's own screen shows when nobody needs to look at it.
 *
 * It has two jobs. With a TV attached and the launcher running on that TV,
 * it turns the phone into a part of the build: a cooling loop, or a black
 * screen held at the lowest brightness. And as a plain screen saver it covers
 * an idle home screen until the next key or tap.
 */
final class CaseScreen implements Overlay {
    private static final long HINT_MS = 4_000;

    private final HomeActivity a;
    private final FrameLayout root;
    private final boolean forTv;
    private final TextView useScreen;
    private final Runnable hideHint;

    /** @param forTv true while a TV shows the launcher; false for the idle screen saver. */
    CaseScreen(HomeActivity activity, boolean forTv) {
        this.a = activity;
        this.forTv = forTv;
        boolean dark = forTv && a.prefs.caseMode() == Prefs.CASE_OFF;
        root = new FrameLayout(a);
        root.setBackgroundColor(0xFF000000);
        root.setClickable(true);
        if (!dark) {
            CoolingLoopView loop = new CoolingLoopView(a);
            loop.setAccent(Ui.ACCENT);
            root.addView(loop, new FrameLayout.LayoutParams(-1, -1));
        }

        // Tapping the glass offers the phone back; it is never taken by accident.
        useScreen = a.ui.pill(a.getString(R.string.case_use_phone), 0xE61E2027, Ui.TEXT);
        useScreen.setVisibility(View.GONE);
        useScreen.setOnClickListener(v -> a.dismissCase());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = a.insetBottom + a.ui.dp(36);
        root.addView(useScreen, lp);
        hideHint = () -> { useScreen.setVisibility(View.GONE); a.returnRemoteToTv(); };
        root.setOnClickListener(v -> onInput(true));

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { hold(true, dark); }
            @Override public void onViewDetachedFromWindow(View v) { root.removeCallbacks(hideHint); hold(false, false); }
        });
    }

    boolean forTv() { return forTv; }

    /**
     * While the TV depends on this phone the screen must not time out, and "Off"
     * means as dark as Android lets an app make it: black at minimum brightness.
     */
    private void hold(boolean on, boolean dark) {
        if (!forTv) return;
        Window w = a.getWindow();
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = dark ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        w.setAttributes(lp);
    }

    /** A key press or a tap reached the phone while this was showing. */
    void onInput(boolean touch) {
        if (!forTv) { a.closeOverlay(); return; }
        if (!touch) { a.returnRemoteToTv(); return; } // the remote belongs to the TV
        useScreen.setVisibility(View.VISIBLE);
        root.removeCallbacks(hideHint);
        root.postDelayed(hideHint, HINT_MS);
    }

    @Override public View view() { return root; }
    @Override public void rebuild() { /* the drawing sizes itself */ }
    @Override public View firstFocus() { return null; }
    @Override public boolean move(View focused, int direction) { return true; }
    @Override public boolean onBack() { return forTv; }
}
