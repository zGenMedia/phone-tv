package com.zgenmedia.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds 16:9 app tiles that look at home on a TV-style shelf.
 *
 * Phone apps don't ship Android TV banners, so we pick the best art available,
 * in this order:
 *   1. a custom image the user assigned to the app;
 *   2. the app's real Android TV banner (TV editions, and some phone apps);
 *   3. a banner generated from the adaptive icon: the icon's background layer
 *      fills the tile and its foreground logo sits large in the middle, which
 *      reads much like the official banners;
 *   4. legacy icons: the icon on a gradient sampled from its own colours.
 */
final class TileArt {
    private final Context ctx;
    private final Ui ui;
    private final File customDir;
    private final Map<String, Integer> colorCache = new HashMap<>();

    TileArt(Context ctx, Ui ui) {
        this.ctx = ctx;
        this.ui = ui;
        customDir = new File(ctx.getFilesDir(), "tiles");
        //noinspection ResultOfMethodCallIgnored
        customDir.mkdirs();
    }

    /** Fills {@code tile} (already sized) with art for {@code app}. */
    void bind(FrameLayout tile, AppRepository.App app, int heightPx, boolean tvStyle) {
        Bitmap custom = customImage(app.pkg);
        if (custom != null) { tile.addView(cover(custom)); return; }

        if (tvStyle) {
            Drawable banner = tvBanner(app);
            if (banner != null) { tile.setBackgroundColor(Color.BLACK); tile.addView(cover(banner)); return; }

            Drawable icon = icon(app);
            if (Build.VERSION.SDK_INT >= 26 && icon instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) icon;
                if (adaptive.getBackground() != null) {
                    ImageView bg = new ImageView(ctx);
                    bg.setScaleType(ImageView.ScaleType.FIT_XY);
                    bg.setImageDrawable(adaptive.getBackground());
                    tile.addView(bg, new FrameLayout.LayoutParams(-1, -1));
                }
                if (adaptive.getForeground() != null) {
                    // Foreground art is drawn on a 108dp canvas with a 72dp safe zone;
                    // sizing the canvas at ~1.25x tile height makes the logo fill ~80%.
                    int size = (int) (heightPx * 1.25f);
                    ImageView fg = new ImageView(ctx);
                    fg.setImageDrawable(adaptive.getForeground());
                    tile.addView(fg, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
                }
                tile.addView(nameTag(app.label, adaptive.getBackground()),
                        new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                return;
            }
        }
        // Legacy (non-adaptive) icon, or the user prefers plain phone icons.
        int c = dominantColor(app);
        GradientDrawable g = Ui.gradient(GradientDrawable.Orientation.TL_BR, c, darken(c));
        g.setCornerRadius(ui.dp(12));
        tile.setBackground(g);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon(app));
        int s = (int) (heightPx * 0.42f);
        col.addView(iv, new LinearLayout.LayoutParams(s, s));
        TextView name = ui.label(app.label, 11, Ui.TEXT, Ui.MEDIUM);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0);
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));
        tile.addView(col, new FrameLayout.LayoutParams(-1, -1));
    }

    // -------------------------------------------------------- custom images

    boolean hasCustomImage(String pkg) { return customFile(pkg).exists(); }

    /** Copies a user-picked image into app storage, downscaled to banner size. */
    boolean saveCustomImage(String pkg, Uri source) {
        try (InputStream in = ctx.getContentResolver().openInputStream(source)) {
            if (in == null) return false;
            byte[] data = Http.readAll(in);
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, b);
            int sample = 1;
            while (b.outWidth / (sample * 2) >= 640) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bmp == null) return false;
            try (FileOutputStream out = new FileOutputStream(customFile(pkg))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    void resetCustomImage(String pkg) {
        //noinspection ResultOfMethodCallIgnored
        customFile(pkg).delete();
    }

    // -------------------------------------------------------------- helpers

    private File customFile(String pkg) { return new File(customDir, pkg + ".jpg"); }

    private Bitmap customImage(String pkg) {
        File f = customFile(pkg);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    private Drawable tvBanner(AppRepository.App app) {
        try {
            // ComponentInfo.loadBanner already falls back to the application banner.
            return app.info.activityInfo.loadBanner(ctx.getPackageManager());
        } catch (RuntimeException e) {
            return null;
        }
    }

    Drawable icon(AppRepository.App app) {
        try { return app.info.loadIcon(ctx.getPackageManager()); }
        catch (RuntimeException e) { return null; }
    }

    private ImageView cover(Object art) {
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (art instanceof Bitmap) iv.setImageBitmap((Bitmap) art); else iv.setImageDrawable((Drawable) art);
        iv.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return iv;
    }

    /** App name in the bottom-left corner, dark or light depending on the background layer. */
    private TextView nameTag(String label, Drawable background) {
        boolean lightBg = background != null && luminance(sample(background)) > 0.6f;
        TextView t = ui.label(label, 10, lightBg ? 0xCC000000 : 0xE6FFFFFF, Ui.MEDIUM);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(5));
        return t;
    }

    private int dominantColor(AppRepository.App app) {
        Integer cached = colorCache.get(app.pkg);
        if (cached != null) return cached;
        int result = 0xFF3A3D46;
        Drawable d = icon(app);
        if (d != null) {
            Bitmap b = render(d, 24);
            long r = 0, g = 0, bl = 0, n = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 160) continue;
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < 0.18f) continue; // ignore white, grey and black
                    r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
                }
            }
            if (n > 8) {
                Color.colorToHSV(Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n)), hsv);
                hsv[2] = 0.46f;
                result = Color.HSVToColor(hsv);
            }
        }
        colorCache.put(app.pkg, result);
        return result;
    }

    private static int sample(Drawable d) { return render(d, 8).getPixel(4, 4); }

    private static Bitmap render(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, size, size);
        copy.draw(new Canvas(b));
        return b;
    }

    private static float luminance(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    private static int darken(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= 0.55f;
        return Color.HSVToColor(hsv);
    }
}
