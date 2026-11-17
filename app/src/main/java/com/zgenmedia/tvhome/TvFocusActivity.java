package com.zgenmedia.tvhome;

import android.app.Activity;
import android.os.Bundle;

/**
 * Hands the remote back to the TV.
 *
 * Android sends key presses to whichever display was used last, so a tap on
 * the phone's glass would leave the remote talking to the phone. Starting this
 * (it has no window and ends at once) on the TV's display makes the TV the
 * active display again without disturbing whatever is playing there.
 */
public class TvFocusActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        finish();
    }
}
