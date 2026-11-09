package com.zgenmedia.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Optional, experimental: keeps Phone TV in front of the stock TV home screen.
 *
 * Fire TV and Android TV / Google TV don't let users pick a different Home
 * app, so pressing Home always opens the manufacturer's launcher. When the
 * user turns this service on, it watches for that launcher coming to the
 * foreground and brings Phone TV forward instead.
 *
 * It reads no screen content: it only looks at which package owns the window
 * (canRetrieveWindowContent is false in the service config).
 *
 * Safety valves, so a user can never get locked out of the stock launcher:
 *  - "Pause" in Phone TV's settings stands the service down for a minute;
 *  - if we redirect more than {@link #MAX_REDIRECTS} times in
 *    {@link #WINDOW_MS} (e.g. Phone TV is crashing), we back off for five
 *    minutes;
 *  - the service can be switched off in the system Accessibility settings.
 */
public class HomeKeeperService extends AccessibilityService {

    /** Stock launchers we step in front of. Settings and store apps are separate packages and unaffected. */
    private static final Set<String> STOCK_LAUNCHERS = new HashSet<>(Arrays.asList(
            "com.amazon.tv.launcher",               // Fire TV
            "com.amazon.firelauncher",              // Fire tablets
            "com.google.android.tvlauncher",        // Android TV (Shield)
            "com.google.android.apps.tv.launcherx", // Google TV
            "com.google.android.leanbacklauncher"   // older Android TV
    ));

    private static final int MAX_REDIRECTS = 3;
    private static final long WINDOW_MS = 20_000;
    private static final long BACKOFF_MS = 5 * 60_000;

    private long windowStart;
    private int redirects;
    private long backoffUntil;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !STOCK_LAUNCHERS.contains(pkg.toString())) return;

        long now = System.currentTimeMillis();
        if (now < backoffUntil || now < new Prefs(this).keeperPausedUntil()) return;
        if (now - windowStart > WINDOW_MS) { windowStart = now; redirects = 0; }
        if (++redirects > MAX_REDIRECTS) { backoffUntil = now + BACKOFF_MS; return; }

        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override public void onInterrupt() { /* nothing to interrupt */ }
}
