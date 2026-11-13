package com.zgenmedia.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.PaintDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * View-building helpers shared by every screen.
 *
 * Every size goes through {@link #dp} / {@link #text} so the "Display size"
 * setting scales the whole UI uniformly, independent of the system font scale.
 * Colors that the user can change (background, accent) live in the static
 * theme fields and are set once by {@link #applyTheme} before views are built.
 */
final class Ui {
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT_DIM = 0xFFC4C7CE;
    static final int MUTED = 0xFFB4B8C2;
    static final int CHIP_SELECTED = 0xFFE8EAED;
    static final int ON_LIGHT = 0xFF101114;

    // Theme. Defaults are the look Phone TV has always had.
    static int BG = 0xFF0E0F13;
    static int SURFACE = 0xFF1E2027;
    static int PANEL = 0xFF16171C;
    static int ACCENT = 0xFFF1F3F4;
    static int ON_ACCENT = ON_LIGHT;
    static int RING = 0xFFFFFFFF;

    /** An accent the user can pick in Settings. */
    static final class Palette {
        final int name, accent, onAccent, ring;
        Palette(int name, int accent, int onAccent, int ring) { this.name = name; this.accent = accent; this.onAccent = onAccent; this.ring = ring; }
    }

    static final Palette[] PALETTES = {
            new Palette(R.string.accent_classic, 0xFFF1F3F4, ON_LIGHT, 0xFFFFFFFF),
            new Palette(R.string.accent_ember, 0xFFFFB547, 0xFF1A1205, 0xFFFFD08A),
            new Palette(R.string.accent_crimson, 0xFFE5484D, 0xFFFFFFFF, 0xFFFF9396),
            new Palette(R.string.accent_ocean, 0xFF3E8BFF, 0xFFFFFFFF, 0xFF9CC2FF),
            new Palette(R.string.accent_violet, 0xFF8E6BFF, 0xFFFFFFFF, 0xFFC2B0FF),
            new Palette(R.string.accent_emerald, 0xFF2FBF71, 0xFF04210F, 0xFF8BE3B1),
            new Palette(R.string.accent_rose, 0xFFF2619C, 0xFFFFFFFF, 0xFFFFA6C8),
    };

    static void applyTheme(Prefs prefs) {
        Palette p = PALETTES[Math.max(0, Math.min(PALETTES.length - 1, prefs.accent()))];
        ACCENT = p.accent;
        ON_ACCENT = p.onAccent;
        RING = p.ring;
        boolean black = prefs.pureBlack();
        BG = black ? 0xFF000000 : 0xFF0E0F13;
        SURFACE = black ? 0xFF15161A : 0xFF1E2027;
        PANEL = black ? 0xFF0B0B0D : 0xFF16171C;
    }

    /** The page background at the given opacity (0-255), for scrims that melt into it. */
    static int bg(int alpha) { return (BG & 0x00FFFFFF) | (alpha << 24); }

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    /** Told whenever a focusable view gains focus, so the screen can scroll it into view smoothly. */
    interface FocusListener { void onFocused(View v); }

    FocusListener onFocus;

    static final int FOCUS_OUTLINE = 0, FOCUS_ZOOM = 1, FOCUS_BOTH = 2;
    static final float[] FOCUS_SCALES = {1.04f, 1.08f, 1.12f};
    static final float[] CARD_RADII = {2f, 8f, 12f, 20f};

    /** How a focused card shows itself; the user picks these in Settings. */
    int focusStyle = FOCUS_BOTH;
    float focusScale = FOCUS_SCALES[2];
    float cardRadius = CARD_RADII[2];

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

    /**
     * A scrim that fades one color through uneven stops, which GradientDrawable
     * can't do before Android 10. {@code alphas[i]} (0-1) applies at {@code stops[i]}
     * (0-1) along the axis; horizontal runs left to right, vertical top to bottom.
     */
    static Drawable fade(boolean horizontal, int rgb, float[] stops, float[] alphas) {
        final int[] colors = new int[alphas.length];
        for (int i = 0; i < alphas.length; i++) colors[i] = (rgb & 0x00FFFFFF) | (Math.round(alphas[i] * 255) << 24);
        PaintDrawable d = new PaintDrawable();
        d.setShape(new RectShape());
        d.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override public Shader resize(int w, int h) {
                return new LinearGradient(0, 0, horizontal ? w : 0, horizontal ? 0 : h, colors, stops, Shader.TileMode.CLAMP);
            }
        });
        return d;
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

    /** The main action on a screen, in the accent color. */
    TextView primary(CharSequence s) { return pill(s, ACCENT, ON_ACCENT); }

    /**
     * Cards and tiles: an outline, a slight zoom, or both on focus (see focusStyle),
     * so someone ten feet away with a remote can always see where they are.
     */
    void focusable(View v) {
        v.setFocusable(true);
        v.setClickable(true);
        StateListDrawable fx = new StateListDrawable();
        if (focusStyle != FOCUS_ZOOM) fx.addState(new int[]{android.R.attr.state_focused}, ring(cardRadius));
        fx.addState(new int[]{}, ripple(null));
        v.setForeground(fx);
        // ScrollView's built-in reveal jumps instantly; we scroll ourselves (see FocusListener).
        v.setRevealOnFocusHint(false);
        v.setOnFocusChangeListener((view, has) -> {
            if (focusStyle != FOCUS_OUTLINE) Motion.scale(view, has ? focusScale : 1f);
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

    GradientDrawable ring(float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(Math.max(3, dp(2.5f)), RING);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** An ImageView that places its picture by a rule the stock scale types don't offer. */
    abstract static class PlacedImageView extends android.widget.ImageView {
        PlacedImageView(Context c) { super(c); setScaleType(ScaleType.MATRIX); }

        @Override protected boolean setFrame(int l, int t, int r, int b) {
            boolean changed = super.setFrame(l, t, r, b);
            place();
            return changed;
        }

        @Override public void setImageDrawable(Drawable d) { super.setImageDrawable(d); place(); }

        private void place() {
            Drawable d = getDrawable();
            if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return;
            android.graphics.Matrix m = new android.graphics.Matrix();
            arrange(m, getWidth(), getHeight(), d.getIntrinsicWidth(), d.getIntrinsicHeight());
            setImageMatrix(m);
        }

        /** Fills {@code m} to map a picture of {@code iw x ih} into a view of {@code w x h}. */
        abstract void arrange(android.graphics.Matrix m, float w, float h, float iw, float ih);
    }

    /**
     * Fills its bounds like CENTER_CROP but keeps the top of the picture, so faces and
     * titles near the top of wide artwork aren't cut off in a short, wide banner.
     * {@code biasX} picks which part survives a sideways crop: 0 left, 0.5 centre, 1 right.
     */
    static final class TopCropImageView extends PlacedImageView {
        private final float biasX;

        TopCropImageView(Context c) { this(c, 0.5f); }

        TopCropImageView(Context c, float biasX) { super(c); this.biasX = biasX; }

        @Override void arrange(android.graphics.Matrix m, float w, float h, float iw, float ih) {
            float s = Math.max(w / iw, h / ih);
            m.setScale(s, s);
            m.postTranslate((w - iw * s) * biasX, 0);
        }
    }

    /** Fits a title logo inside its box, resting bottom-left so it lines up with the text under it. */
    static final class LogoView extends PlacedImageView {
        LogoView(Context c) { super(c); }

        @Override void arrange(android.graphics.Matrix m, float w, float h, float iw, float ih) {
            float s = Math.min(w / iw, h / ih);
            m.setScale(s, s);
            m.postTranslate(0, h - ih * s);
        }
    }

    private DisplayMetrics metrics() { return ctx.getResources().getDisplayMetrics(); }
}
