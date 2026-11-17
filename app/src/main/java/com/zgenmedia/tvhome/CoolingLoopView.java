package com.zgenmedia.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.view.View;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A liquid-cooling loop, drawn to make the phone look like a part of the build
 * when it sits in a case behind the TV: radiator, water block, pump and three
 * runs of hard tubing with coolant moving through them.
 *
 * Everything is drawn in a tall "portrait" space and turned on its side for a
 * phone lying sideways, so the loop always runs along the long edge. The
 * readouts are real: the battery's temperature and charge, and the time.
 *
 * Two things keep it kind to a screen that may show it for hours: the frame
 * rate is capped, and the whole drawing drifts by a few pixels every so often
 * so no line sits on the same pixels all evening.
 */
final class CoolingLoopView extends View {
    private static final int FRAME_MS = 33;
    private static final long READOUT_EVERY_MS = 30_000;
    private static final long DRIFT_EVERY_MS = 90_000;
    private static final float[][] DRIFT = {{0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private static final int PANEL = 0xFF050608, METAL = 0xFF14171D, METAL_EDGE = 0xFF2B303A, FITTING = 0xFF3B414D;
    private static final int DEFAULT_COOLANT = 0xFF2FD3FF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] tubes = {new Path(), new Path(), new Path()};
    private final Path streak = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float[] point = new float[2];
    private final RectF rect = new RectF();

    private int coolant = DEFAULT_COOLANT;
    private boolean sideways;
    private float vw, vh, u;                 // drawing space (always taller than wide) and 1% of its width
    private float radTop, radBottom, pumpY, pumpR, blockY, blockH;
    private boolean running;
    private long readAt;
    private String temperature = "", status = "";

    CoolingLoopView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    /** Coolant takes the app's accent; the near-white Classic accent falls back to a cool blue. */
    void setAccent(int accent) {
        int r = (accent >> 16) & 0xFF, g = (accent >> 8) & 0xFF, b = accent & 0xFF;
        boolean colorless = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 28;
        coolant = colorless ? DEFAULT_COOLANT : accent | 0xFF000000;
        invalidate();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); running = true; invalidate(); }
    @Override protected void onDetachedFromWindow() { running = false; super.onDetachedFromWindow(); }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        sideways = w > h;
        vw = Math.min(w, h);
        vh = Math.max(w, h);
        u = vw / 100f;
        radTop = vh * 0.075f;
        radBottom = radTop + vh * 0.15f;
        pumpY = vh * 0.47f;
        pumpR = 14 * u;
        blockH = Math.min(vh * 0.11f, 30 * u);
        blockY = vh * 0.745f;

        float bend = 7 * u, left = 24 * u, right = 76 * u, fit = 2.2f * u;
        // Radiator down the left side and into the block.
        route(tubes[0], bend, left, radBottom + fit, left, blockY, 35 * u - fit, blockY);
        // Out of the block, up the right side and into the pump.
        route(tubes[1], bend, 65 * u + fit, blockY, right, blockY, right, pumpY, 50 * u + pumpR + fit, pumpY);
        // Pump straight up to the radiator.
        route(tubes[2], bend, 50 * u, pumpY - pumpR - fit, 50 * u, radBottom + fit);
    }

    /** A run of tube through the given corners, each corner rounded by {@code bend}. */
    private static void route(Path path, float bend, float... xy) {
        path.rewind();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) {
            float x = xy[i], y = xy[i + 1];
            boolean last = i + 2 >= xy.length;
            if (last) { path.lineTo(x, y); break; }
            float px = xy[i - 2], py = xy[i - 1], nx = xy[i + 2], ny = xy[i + 3];
            float in = (float) Math.hypot(x - px, y - py), out = (float) Math.hypot(nx - x, ny - y);
            float r = Math.min(bend, Math.min(in, out) / 2f);
            path.lineTo(x - (x - px) / in * r, y - (y - py) / in * r);
            path.quadTo(x, y, x + (nx - x) / out * r, y + (ny - y) / out * r);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (vw == 0) return;
        long now = SystemClock.uptimeMillis();
        float seconds = (now % 3_600_000L) / 1000f;
        if (now - readAt > READOUT_EVERY_MS || readAt == 0) { readAt = now; readStatus(); }

        canvas.drawColor(PANEL);
        float[] drift = DRIFT[(int) ((now / DRIFT_EVERY_MS) % DRIFT.length)];
        canvas.save();
        canvas.translate(drift[0] * 1.2f * u, drift[1] * 1.2f * u);

        canvas.save();
        if (sideways) { canvas.translate(0, vw); canvas.rotate(-90); } // the loop's top becomes the left edge
        drawFrame(canvas);
        drawRadiator(canvas);
        for (int i = 0; i < tubes.length; i++) drawTube(canvas, tubes[i], seconds, i);
        drawBlock(canvas, seconds);
        drawPump(canvas, seconds);
        canvas.restore();

        drawReadouts(canvas);
        canvas.restore();
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    // ------------------------------------------------------------------- parts

    private void drawFrame(Canvas c) {
        line.setPathEffect(null);
        line.setColor(0xFF1A1D24);
        line.setStrokeWidth(0.4f * u);
        rect.set(3.5f * u, 3.5f * u, vw - 3.5f * u, vh - 3.5f * u);
        c.drawRoundRect(rect, 6 * u, 6 * u, line);
        for (int i = 0; i < 4; i++) {
            float x = i % 2 == 0 ? 8.5f * u : vw - 8.5f * u, y = i < 2 ? 8.5f * u : vh - 8.5f * u;
            fill.setColor(METAL);
            c.drawCircle(x, y, 1.7f * u, fill);
            line.setColor(METAL_EDGE);
            line.setStrokeWidth(0.35f * u);
            c.drawCircle(x, y, 1.7f * u, line);
            c.drawLine(x - 0.9f * u, y - 0.9f * u, x + 0.9f * u, y + 0.9f * u, line); // screw slot
        }
    }

    private void drawRadiator(Canvas c) {
        float l = 14 * u, r = 86 * u, tank = 2.6f * u;
        rect.set(l, radTop, r, radBottom);
        fill.setColor(0xFF0B0D11);
        c.drawRoundRect(rect, 2.4f * u, 2.4f * u, fill);
        line.setPathEffect(null);
        line.setColor(0xFF1B1F27);
        line.setStrokeWidth(0.45f * u);
        for (float x = l + 3 * u; x < r - 2 * u; x += 2.1f * u) c.drawLine(x, radTop + tank, x, radBottom - tank, line); // fins
        fill.setColor(METAL);
        c.drawRect(l, radTop, r, radTop + tank, fill);
        c.drawRect(l, radBottom - tank, r, radBottom, fill);
        line.setColor(METAL_EDGE);
        line.setStrokeWidth(0.5f * u);
        c.drawRoundRect(rect, 2.4f * u, 2.4f * u, line);
        fitting(c, 24 * u, radBottom, false);
        fitting(c, 50 * u, radBottom, false);
    }

    /** The knurled nut where a tube meets a part; {@code across} for tubes that arrive sideways. */
    private void fitting(Canvas c, float x, float y, boolean across) {
        float longSide = 7.6f * u, shortSide = 2.4f * u;
        fill.setColor(FITTING);
        if (across) rect.set(x - shortSide / 2, y - longSide / 2, x + shortSide / 2, y + longSide / 2);
        else rect.set(x - longSide / 2, y - shortSide / 2 + u, x + longSide / 2, y + shortSide / 2 + u);
        c.drawRoundRect(rect, 0.6f * u, 0.6f * u, fill);
    }

    private void drawTube(Canvas c, Path tube, float seconds, int index) {
        line.setStrokeCap(Paint.Cap.BUTT);
        line.setPathEffect(null);
        line.setColor(withAlpha(coolant, 16)); // light spilling onto the panel
        line.setStrokeWidth(12 * u);
        c.drawPath(tube, line);
        line.setColor(0xFF242932); // tube wall
        line.setStrokeWidth(5.8f * u);
        c.drawPath(tube, line);
        float breathe = 0.86f + 0.14f * (float) Math.sin(seconds * 1.4f + index);
        line.setColor(withAlpha(coolant, (int) (150 * breathe)));
        line.setStrokeWidth(4.2f * u);
        c.drawPath(tube, line);

        // A thin highlight along one side reads as light catching the glass.
        c.save();
        c.translate(-1.3f * u, -1.3f * u);
        line.setColor(0x30FFFFFF);
        line.setStrokeWidth(0.5f * u);
        c.drawPath(tube, line);
        c.restore();

        // Streaks of lighter coolant slide along the run to show which way it flows.
        measure.setPath(tube, false);
        float length = measure.getLength(), dash = 6 * u, period = 17 * u, speed = 16 * u;
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(withAlpha(blend(coolant, 0xFFFFFFFF, 0.55f), 190));
        line.setStrokeWidth(1.3f * u);
        for (float at = (seconds * speed) % period - period; at < length; at += period) {
            streak.rewind();
            if (measure.getSegment(Math.max(0, at), Math.min(length, at + dash), streak, true)) c.drawPath(streak, line);
        }

        float spacing = 21 * u;
        fill.setColor(0xB0FFFFFF);
        for (int i = 0; i * spacing < length; i++) {
            float at = (seconds * speed * 0.8f + i * spacing + index * 7 * u) % length;
            measure.getPosTan(at, point, null);
            float wobble = (float) Math.sin(seconds * 3 + i * 1.7f) * 0.9f * u;
            c.drawCircle(point[0] + wobble, point[1] + wobble * 0.5f, (0.45f + (i % 3) * 0.22f) * u, fill);
        }
    }

    private void drawBlock(Canvas c, float seconds) {
        float l = 35 * u, r = 65 * u, t = blockY - blockH / 2, b = blockY + blockH / 2;
        rect.set(l, t, r, b);
        fill.setColor(METAL);
        c.drawRoundRect(rect, 3 * u, 3 * u, fill);
        // A window onto the coolant channel, with the fine fins a real block has.
        float glow = 0.75f + 0.25f * (float) Math.sin(seconds * 1.1f);
        rect.inset(3.2f * u, 3.2f * u);
        fill.setColor(withAlpha(coolant, (int) (70 * glow)));
        c.drawRoundRect(rect, 1.6f * u, 1.6f * u, fill);
        line.setPathEffect(null);
        line.setColor(withAlpha(coolant, (int) (150 * glow)));
        line.setStrokeWidth(0.4f * u);
        for (float x = rect.left + 1.6f * u; x < rect.right - u; x += 1.7f * u) c.drawLine(x, rect.top + u, x, rect.bottom - u, line);
        rect.inset(-3.2f * u, -3.2f * u);
        line.setColor(METAL_EDGE);
        line.setStrokeWidth(0.5f * u);
        c.drawRoundRect(rect, 3 * u, 3 * u, line);
        fitting(c, l, blockY, true);
        fitting(c, r, blockY, true);
    }

    private void drawPump(Canvas c, float seconds) {
        float x = 50 * u, y = pumpY;
        fill.setColor(0xFF0B0D11);
        c.drawCircle(x, y, pumpR, fill);
        line.setPathEffect(null);
        line.setColor(METAL_EDGE);
        line.setStrokeWidth(2 * u);
        c.drawCircle(x, y, pumpR - u, line);
        line.setColor(withAlpha(coolant, 70)); // soft halo, then the bright ring inside it
        line.setStrokeWidth(4.4f * u);
        c.drawCircle(x, y, pumpR - 4.6f * u, line);
        line.setColor(withAlpha(coolant, 235));
        line.setStrokeWidth(0.9f * u);
        c.drawCircle(x, y, pumpR - 4.6f * u, line);
        fill.setColor(withAlpha(coolant, 40));
        c.drawCircle(x, y, pumpR - 7 * u, fill);

        c.save();
        c.rotate(seconds * 210f, x, y);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(withAlpha(blend(coolant, 0xFFFFFFFF, 0.3f), 215));
        line.setStrokeWidth(1.5f * u);
        for (int i = 0; i < 6; i++) {
            c.drawLine(x + 2.6f * u, y, x + pumpR - 8.2f * u, y - 1.6f * u, line); // impeller blade
            c.rotate(60, x, y);
        }
        c.restore();
        fill.setColor(0xFF1E222A);
        c.drawCircle(x, y, 2.6f * u, fill);
        fitting(c, x + pumpR, y, true);
        fitting(c, x, y - pumpR - 2 * u, false);
    }

    /** Battery temperature, charge and the time, upright whichever way the phone lies. */
    private void drawReadouts(Canvas c) {
        float cx = sideways ? vh * 0.915f : vw / 2f;
        float cy = sideways ? vw / 2f : vh * 0.905f;
        boolean hasTemperature = !temperature.isEmpty();
        text.setTextAlign(Paint.Align.CENTER);
        text.setLetterSpacing(0.04f);
        text.setColor(withAlpha(blend(coolant, 0xFFFFFFFF, 0.35f), 235));
        text.setTextSize((hasTemperature ? 7.4f : 5.2f) * u);
        c.drawText(hasTemperature ? temperature : status, cx, cy, text);
        if (!hasTemperature) return; // some devices don't report it: the time stands alone
        text.setLetterSpacing(0.18f);
        text.setColor(0xFF6E7684);
        text.setTextSize(2.5f * u);
        c.drawText("BATTERY", cx, cy - 8.6f * u, text);
        c.drawText(status, cx, cy + 4.8f * u, text);
    }

    /** Refreshes the readouts from the battery's last report; cheap enough to do twice a minute. */
    private void readStatus() {
        status = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int tenths = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (tenths != Integer.MIN_VALUE) {
            boolean fahrenheit = "US".equals(Locale.getDefault().getCountry());
            float degrees = fahrenheit ? tenths / 10f * 9 / 5 + 32 : tenths / 10f;
            temperature = String.format(Locale.getDefault(), fahrenheit ? "%.0f°F" : "%.1f°C", degrees);
        }
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) status = (level * 100 / scale) + "%  ·  " + status;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static int blend(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = (int) (((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = (int) ((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
