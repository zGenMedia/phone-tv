package com.zgenmedia.tvhome;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.widget.ImageView;

/**
 * An ImageView for artwork whose size is set by its layout, not by its picture.
 *
 * A plain ImageView asks the whole window to lay out again whenever a picture of a
 * new size arrives. Artwork arrives while the user scrolls, one card at a time, so on
 * a long home screen that meant a full layout pass mid-scroll for every poster: the
 * stutter Phone TV had while moving along a shelf. When the frame is fixed, the
 * picture can't change it, so the request is skipped and only a redraw is done.
 */
class Art extends ImageView {
    private boolean settingPicture;

    Art(Context c) { super(c); }

    private boolean sizeIsFixed() {
        ViewGroup.LayoutParams lp = getLayoutParams();
        return lp != null && !getAdjustViewBounds()
                && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    @Override
    public void setImageDrawable(Drawable d) {
        settingPicture = true;
        try { super.setImageDrawable(d); } finally { settingPicture = false; }
    }

    @Override
    public void requestLayout() {
        if (settingPicture && sizeIsFixed() && !isLayoutRequested()) { invalidate(); return; }
        super.requestLayout();
    }
}
