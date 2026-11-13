package com.zgenmedia.tvhome;

import android.view.View;

/**
 * A full-screen page shown above the home screen (title details, settings).
 *
 * Pages are plain views added over the launcher rather than separate
 * activities: the home screen stays built underneath with its scroll position
 * and artwork cache, so Back returns to exactly where the user was.
 */
interface Overlay {
    /** Root view. It must swallow touches so nothing underneath reacts. */
    View view();

    /** Builds the page again for a new orientation, size or theme. */
    void rebuild();

    /** Where the remote lands when the page opens. */
    View firstFocus();

    /** D-pad pressed with focus inside this page. @return true when handled. */
    boolean move(View focused, int direction);

    /** @return true if Back was used inside the page; false to close it. */
    boolean onBack();
}
