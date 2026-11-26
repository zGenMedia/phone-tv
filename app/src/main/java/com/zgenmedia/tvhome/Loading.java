package com.zgenmedia.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * How Phone TV says "working on it".
 *
 * Two pieces, used together or apart:
 *  - {@link Dots}: three dots that rise and fall in a wave, for a line of status text
 *    ("Asking your add-ons", "Choosing"). Small, calm, clearly alive.
 *  - {@link Skeleton}: grey outlines of the cards that are about to arrive, with a soft
 *    light sweeping across them. The page keeps its shape, so nothing jumps when the
 *    real cards land, and the wait reads as "filling in" rather than "stuck".
 *
 * Both draw themselves from the clock while they're on screen and stop the moment they
 * leave it; there is nothing to start, stop or clean up.
 */
final class Loading {
    private Loading() {}

    /** Dots and a sentence, set like the notes they replace. */
    static View line(HomeActivity a, String text) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.gutter(), a.ui.dp(10), a.insetRight + a.ui.dp(24), a.ui.dp(12));
        row.addView(new Dots(a, a.ui.dp(7), Ui.ACCENT), new LinearLayout.LayoutParams(a.ui.dp(44), a.ui.dp(22)));
        TextView label = a.ui.label(text, 14, Ui.TEXT_DIM, Ui.MEDIUM);
        label.setPadding(a.ui.dp(10), 0, 0, 0);
        row.addView(label);
        return row;
    }

    /** Three dots in a travelling wave. */
    static final class Dots extends View {
        private static final long PERIOD_MS = 1_100;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dot;

        Dots(Context c, float dotPx, int color) {
            super(c);
            dot = dotPx;
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float t = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
            float gap = getWidth() / 3f, mid = getHeight() / 2f;
            for (int i = 0; i < 3; i++) {
                // Each dot runs the same rise-and-fall, a beat behind the one before it.
                float phase = t - i * 0.16f;
                phase -= (float) Math.floor(phase);
                float lift = phase < 0.5f ? (float) Math.sin(phase * 2 * Math.PI) : 0f; // up and back, then rest
                paint.setAlpha((int) (255 * (0.35f + 0.65f * lift)));
                float r = dot / 2f * (0.75f + 0.35f * lift);
                canvas.drawCircle(gap * i + gap / 2f, mid - lift * dot * 0.55f, r, paint);
            }
            if (isShown()) postInvalidateOnAnimation();
        }
    }

    /**
     * Placeholder cards with a light passing over them.
     * One view draws the whole block, however many cards it stands for.
     */
    static final class Skeleton extends View {
        private static final long SWEEP_MS = 1_400;
        private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix slide = new Matrix();
        private final RectF card = new RectF();
        private final int columns, rows;
        private final float cardW, cardH, gapX, gapY, radius;
        private LinearGradient light;

        /**
         * @param cardH height of one card; the caption lines under a poster are not drawn
         * @param gapY space between rows, to match the real grid's captions and spacing
         */
        Skeleton(Context c, int columns, int rows, float cardW, float cardH, float gapX, float gapY, float radius) {
            super(c);
            this.columns = columns;
            this.rows = rows;
            this.cardW = cardW;
            this.cardH = cardH;
            this.gapX = gapX;
            this.gapY = gapY;
            this.radius = radius;
            base.setColor(0x14FFFFFF);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) (rows * cardH + (rows - 1) * gapY) + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            // A narrow band of light, tilted, as wide as a third of the block.
            light = new LinearGradient(0, 0, Math.max(1, w / 3f), 0,
                    new int[]{0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            glow.setShader(light);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (light == null) return;
            float t = (SystemClock.uptimeMillis() % SWEEP_MS) / (float) SWEEP_MS;
            slide.setTranslate(-getWidth() / 3f + t * getWidth() * 1.34f, 0);
            slide.postRotate(18, getWidth() / 2f, getHeight() / 2f);
            light.setLocalMatrix(slide);
            float left = getPaddingLeft(), top = getPaddingTop();
            for (int r = 0; r < rows; r++) {
                for (int col = 0; col < columns; col++) {
                    float x = left + col * (cardW + gapX), y = top + r * (cardH + gapY);
                    if (x >= getWidth()) break;
                    card.set(x, y, x + cardW, y + cardH);
                    canvas.drawRoundRect(card, radius, radius, base);
                    canvas.drawRoundRect(card, radius, radius, glow);
                }
            }
            if (isShown()) postInvalidateOnAnimation();
        }
    }

    /** Posters about to arrive: as many across as the real grid will have. */
    static View posters(HomeActivity a, int[] plan, int rows) {
        float w = plan[1], gap = a.ui.dp(12);
        Skeleton s = new Skeleton(a, plan[0], rows, w, w * 1.5f, gap, a.ui.dp(46), a.ui.dp(a.ui.cardRadius));
        s.setPadding(a.gutter(), a.ui.dp(4), 0, a.ui.dp(8));
        return s;
    }

    /** Full-width rows about to arrive (sources, list items). */
    static View rows(HomeActivity a, int count, int rowHeightDp) {
        float w = a.getResources().getDisplayMetrics().widthPixels;
        Skeleton s = new Skeleton(a, 1, count, w, a.ui.dp(rowHeightDp), 0, a.ui.dp(8), a.ui.dp(14));
        return s;
    }
}
