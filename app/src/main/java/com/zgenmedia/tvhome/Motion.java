package com.zgenmedia.tvhome;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

/**
 * Timing and easing shared by every animation, so the app moves as one piece.
 *
 * Focus changes are short and symmetric; scrolling starts at speed and settles
 * slowly, which reads as a soft spring and, unlike the stock smoothScrollTo,
 * can be re-aimed mid-flight when someone holds a direction on the remote.
 */
final class Motion {
    static final int QUICK = 125, FAST = 180, MEDIUM = 350, CROSSFADE = 400;

    /** Where a focused item comes to rest in its scroller: just short of the middle. */
    static final float PIVOT = 0.42f;

    static final TimeInterpolator STANDARD = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    static final TimeInterpolator SETTLE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private Motion() {}

    static void scale(View v, float to) {
        v.animate().scaleX(to).scaleY(to).setDuration(FAST).setInterpolator(STANDARD).start();
    }

    static void fadeIn(View v, int ms) {
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(ms).setInterpolator(STANDARD).start();
    }

    /** Scrolls a page so that {@code y} is its top edge, clamped to what can actually scroll. */
    static void glideY(ScrollView s, int y) {
        int max = s.getChildCount() == 0 ? 0
                : s.getChildAt(0).getHeight() - (s.getHeight() - s.getPaddingTop() - s.getPaddingBottom());
        glide(s, "scrollY", s.getScrollY(), Math.max(0, Math.min(y, max)));
    }

    /** Same for a shelf. */
    static void glideX(HorizontalScrollView s, int x) {
        int max = s.getChildCount() == 0 ? 0
                : s.getChildAt(0).getWidth() - (s.getWidth() - s.getPaddingLeft() - s.getPaddingRight());
        glide(s, "scrollX", s.getScrollX(), Math.max(0, Math.min(x, max)));
    }

    private static void glide(View scroller, String property, int from, int to) {
        stop(scroller, R.id.scroll_anim);
        if (from == to) return;
        ObjectAnimator a = ObjectAnimator.ofInt(scroller, property, from, to);
        a.setDuration(MEDIUM + 70);
        a.setInterpolator(SETTLE);
        scroller.setTag(R.id.scroll_anim, a);
        a.start();
    }

    /** Animates a view's layout width; {@code onFrame} runs after each step (may be null). */
    static void width(View v, int to, Runnable onFrame) {
        stop(v, R.id.size_anim);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || lp.width == to) return;
        ValueAnimator a = ValueAnimator.ofInt(lp.width, to);
        a.setDuration(MEDIUM);
        a.setInterpolator(STANDARD);
        a.addUpdateListener(x -> {
            lp.width = (int) x.getAnimatedValue();
            v.requestLayout();
            if (onFrame != null) onFrame.run();
        });
        v.setTag(R.id.size_anim, a);
        a.start();
    }

    private static void stop(View v, int tagId) {
        Object running = v.getTag(tagId);
        if (running instanceof Animator) ((Animator) running).cancel();
        v.setTag(tagId, null);
    }
}
