package com.zgenmedia.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * View-building helpers shared by the home screen.
 *
 * Every size goes through {@link #dp} / {@link #text} so the "Display size"
 * setting scales the whole UI uniformly, independent of the system font scale.
 */
final class Ui {
    static final int BG = 0xFF0E0F13;
    static final int SURFACE = 0xFF1E2027;
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT_DIM = 0xFFC4C7CE;
    static final int MUTED = 0xFFB4B8C2;
    static final int CHIP_SELECTED = 0xFFE8EAED;
    static final int ON_LIGHT = 0xFF101114;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    /** Told whenever a focusable view gains focus, so the screen can scroll it into view smoothly. */
    interface FocusListener { void onFocused(View v); }

    FocusListener onFocus;

    static final int FOCUS_OUTLINE = 0, FOCUS_ZOOM = 1, FOCUS_BOTH = 2;
    private static final float FOCUS_SCALE = 1.12f;
    /** How a focused card shows itself; the user picks this in Settings. */
    int focusStyle = FOCUS_BOTH;

    private final Context ctx;
    private float scale;

    Ui(Context ctx, float scale) { this.ctx = ctx; this.scale = scale; }

    void setScale(float s) { scale = s; }

    int dp(float v) { return (int) (v * metrics().density * scale + 0.5f); }

    /** Sets text size in sp, scaled by the Display size setting and the system font scale. */
    void text(TextView t, float sp) {
        float font = ctx.getResources().getConfiguration().fontScale;
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * metrics().density * font * scale);
    }

    TextView label(CharSequence s, float sp, int color, Typeface face) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        text(t, sp);
        if (face != null) t.setTypeface(face);
        return t;
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    static GradientDrawable gradient(GradientDrawable.Orientation o, int... colors) {
        return new GradientDrawable(o, colors);
    }

    /** Touch ripple; pass null content to use it as a foreground over images. */
    Drawable ripple(Drawable content) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(20));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, content == null ? mask : null);
    }

    TextView pill(CharSequence s, int bg, int fg) {
        TextView b = label(s, 13, fg, MEDIUM);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setBackground(ripple(round(bg, 20)));
        b.setFocusable(true);
        b.setClickable(true);
        focusRing(b, 20);
        return b;
    }

    /**
     * Cards and tiles: an outline, a slight zoom, or both on focus (see focusStyle),
     * so someone ten feet away with a remote can always see where they are.
     */
    void focusable(View v) {
        v.setFocusable(true);
        v.setClickable(true);
        StateListDrawable fx = new StateListDrawable();
        if (focusStyle != FOCUS_ZOOM) fx.addState(new int[]{android.R.attr.state_focused}, ring(12));
        fx.addState(new int[]{}, ripple(null));
        v.setForeground(fx);
        // ScrollView's built-in reveal jumps instantly; we scroll ourselves (see FocusListener).
        v.setRevealOnFocusHint(false);
        v.setOnFocusChangeListener((view, has) -> {
            if (focusStyle != FOCUS_OUTLINE) view.animate().scaleX(has ? FOCUS_SCALE : 1f).scaleY(has ? FOCUS_SCALE : 1f).setDuration(120).start();
            if (has && onFocus != null) onFocus.onFocused(view);
        });
    }

    /** Ring only, for buttons and chips that already have their own ripple background. */
    void focusRing(View v, float radiusDp) {
        StateListDrawable fx = new StateListDrawable();
        fx.addState(new int[]{android.R.attr.state_focused}, ring(radiusDp));
        v.setForeground(fx);
        v.setRevealOnFocusHint(false);
        v.setOnFocusChangeListener((view, has) -> { if (has && onFocus != null) onFocus.onFocused(view); });
    }

    private GradientDrawable ring(float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(Math.max(3, dp(2.5f)), Color.WHITE);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /**
     * Fills its bounds like CENTER_CROP but keeps the top of the picture, so faces and
     * titles near the top of wide artwork aren't cut off in a short, full-width banner.
     */
    static final class TopCropImageView extends android.widget.ImageView {
        TopCropImageView(Context c) { super(c); setScaleType(ScaleType.MATRIX); }

        @Override protected boolean setFrame(int l, int t, int r, int b) {
            boolean changed = super.setFrame(l, t, r, b);
            alignTop();
            return changed;
        }

        @Override public void setImageDrawable(Drawable d) { super.setImageDrawable(d); alignTop(); }

        private void alignTop() {
            Drawable d = getDrawable();
            if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return;
            float s = Math.max(getWidth() / (float) d.getIntrinsicWidth(), getHeight() / (float) d.getIntrinsicHeight());
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(s, s);
            m.postTranslate((getWidth() - d.getIntrinsicWidth() * s) / 2f, 0);
            setImageMatrix(m);
        }
    }

    private DisplayMetrics metrics() { return ctx.getResources().getDisplayMetrics(); }
}
