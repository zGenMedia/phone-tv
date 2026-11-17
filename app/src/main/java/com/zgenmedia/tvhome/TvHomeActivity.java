package com.zgenmedia.tvhome;

import android.os.Bundle;
import android.view.Display;

/**
 * The launcher as it runs on a TV plugged into the phone.
 *
 * A phone normally mirrors its screen to an attached TV. When the user asks
 * for the phone's own screen to show something else (see {@link CaseScreen}),
 * the home screen is started a second time on the TV's display, where it lays
 * itself out for that screen and opens apps there too. The phone keeps the
 * original {@link HomeActivity}, which is still the system's Home app.
 *
 * Not every phone allows a second screen with its own content. If Android
 * starts this on the phone's display instead, it bows out and the phone goes
 * back to plain mirroring.
 */
public class TvHomeActivity extends HomeActivity {
    /** True while an instance is running on a TV. */
    static boolean alive;
    private static java.lang.ref.WeakReference<TvHomeActivity> running = new java.lang.ref.WeakReference<>(null);

    /** Closes the TV's copy, e.g. when the cable is pulled. */
    static void end() {
        TvHomeActivity tv = running.get();
        if (tv != null) tv.finish();
    }

    @Override boolean isTvInstance() { return true; }

    @Override
    boolean acceptDisplay() {
        if (displayId() != Display.DEFAULT_DISPLAY) return true;
        tvLinkRefused = true;
        return false;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        alive = !isFinishing();
        if (alive) running = new java.lang.ref.WeakReference<>(this);
    }

    /** If the TV was unplugged and Android moved this task to the phone, there is nothing left to do here. */
    @Override
    @android.annotation.SuppressLint("ChromeOsOnConfigurationChanged") // leaving, not redrawing
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (displayId() == Display.DEFAULT_DISPLAY) finish();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        super.onDestroy();
    }
}
