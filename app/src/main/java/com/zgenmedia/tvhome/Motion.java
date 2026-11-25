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
        Object running = scroller.getTag(R.id.scroll_anim);
        if (running instanceof Glide && ((Glide) running).alive) { ((Glide) running).target = to; return; }
        if (from == to) return;
        Glide g = new Glide(scroller, property.equals("scrollX"), to);
        scroller.setTag(R.id.scroll_anim, g);
        g.start();
    }

    /**
     * A critically damped spring driving a scroller, the way Compose (and so Nuvio) moves its
     * rows. The point of a spring over a fixed-length animation: when the remote is held and
     * a new target arrives every key repeat, the spring keeps its speed and simply re-aims,
     * so the page flows. Restarting an eased animation each time made it lurch on every
     * press, which is the jerky feel. It lets go as soon as a finger moves the page.
     */
    static final class Glide implements android.view.Choreographer.FrameCallback {
        /** Stiffness: settles in about a third of a second without overshoot. */
        private static final float OMEGA = 13f;
        private final View view;
        private final boolean horizontal;
        int target;
        boolean alive = true;
        private float pos, velocity;
        private int written;
        private long lastNanos;

        Glide(View view, boolean horizontal, int target) {
            this.view = view;
            this.horizontal = horizontal;
            this.target = target;
            pos = written = current();
        }

        private int current() { return horizontal ? view.getScrollX() : view.getScrollY(); }

        void start() { android.view.Choreographer.getInstance().postFrameCallback(this); }

        void cancel() {
            alive = false;
            android.view.Choreographer.getInstance().removeFrameCallback(this);
            if (view.getTag(R.id.scroll_anim) == this) view.setTag(R.id.scroll_anim, null);
        }

        @Override
        public void doFrame(long now) {
            if (!alive) return;
            if (!view.isAttachedToWindow() || Math.abs(current() - written) > 1) { cancel(); return; } // a finger took over
            float dt = lastNanos == 0 ? 1f / 60 : Math.min(1f / 30, (now - lastNanos) / 1e9f);
            lastNanos = now;
            // Critically damped: a = w²(target - x) - 2w·v. Semi-implicit Euler is stable at these steps.
            velocity += (OMEGA * OMEGA * (target - pos) - 2 * OMEGA * velocity) * dt;
            pos += velocity * dt;
            if (Math.abs(target - pos) < 0.5f && Math.abs(velocity) < 12f) pos = target;
            written = Math.round(pos);
            if (horizontal) view.scrollTo(written, view.getScrollY()); else view.scrollTo(view.getScrollX(), written);
            // The scroller clamps; once it can't follow, there's nowhere further to go.
            written = current();
            if (pos == target || (Math.abs(written - Math.round(pos)) > 1 && Math.abs(velocity) < 200f)) { cancel(); return; }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
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
        if (running instanceof Glide) ((Glide) running).cancel();
        v.setTag(tagId, null);
    }
}
