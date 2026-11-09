package com.zgenmedia.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads, downsamples and caches artwork.
 *
 * Built for older phones, where heat and memory are the real constraints:
 *  - images are decoded straight to the size they're displayed at (inSampleSize
 *    plus a final scale), never at source resolution;
 *  - the downsized result is written to disk, so a cold start re-reads a ~40 KB
 *    JPEG instead of re-downloading and re-decoding a 1 MB original;
 *  - work runs on three background-priority threads;
 *  - off-screen cards are queued with {@link #defer} and only fetched once they
 *    scroll near the viewport.
 */
final class ImageLoader {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "phonetv-img");
        t.setDaemon(true);
        return t;
    });
    private final LruCache<String, Bitmap> memory;
    private final File diskDir;
    private final DisplayMetrics metrics;
    private final List<View> deferredViews = new ArrayList<>();
    private final List<Runnable> deferredLoads = new ArrayList<>();
    private final int[] loc = new int[2];

    ImageLoader(Context context) {
        diskDir = new File(context.getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        diskDir.mkdirs();
        metrics = context.getResources().getDisplayMetrics();
        // One sixth of the heap: enough for a screen and a half of artwork.
        memory = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 6)) {
            @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount() / 1024; }
        };
    }

    /**
     * Loads {@code url} into {@code target}, scaled to {@code widthPx}.
     * @param keepAlpha decode as ARGB_8888 (needed for transparent logos); otherwise RGB_565.
     * @param onLoaded optional, runs on the main thread after the image is set.
     */
    void load(ImageView target, String url, int widthPx, boolean keepAlpha, Runnable onLoaded) {
        if (url == null || url.isEmpty()) return;
        final int width = Math.max(64, widthPx);
        final String key = url + "#" + width;
        target.setTag(key);

        Bitmap cached = memory.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            if (onLoaded != null) onLoaded.run();
            return;
        }
        pool.execute(() -> {
            Bitmap bmp = fetch(url, key, width, keepAlpha);
            main.post(() -> {
                if (bmp == null) return;
                memory.put(key, bmp);
                // The view may have been recycled for another item while we were working.
                if (!key.equals(target.getTag())) return;
                target.setImageBitmap(bmp);
                target.setAlpha(0f);
                target.animate().alpha(1f).setDuration(250).start();
                if (onLoaded != null) onLoaded.run();
            });
        });
    }

    /** Queues {@code load} until {@code anchor} is near the screen. Call {@link #flush} on scroll. */
    void defer(View anchor, Runnable load) {
        deferredViews.add(anchor);
        deferredLoads.add(load);
    }

    void clearDeferred() { deferredViews.clear(); deferredLoads.clear(); }

    /** Starts any deferred loads whose anchor is within ~1.5 screens of the viewport. */
    void flush() {
        for (int i = deferredViews.size() - 1; i >= 0; i--) {
            View v = deferredViews.get(i);
            if (!v.isAttachedToWindow() || v.getWidth() == 0) continue;
            v.getLocationOnScreen(loc);
            boolean near = loc[1] < metrics.heightPixels * 1.6f && loc[1] + v.getHeight() > -metrics.heightPixels * 0.5f
                    && loc[0] < metrics.widthPixels * 1.5f && loc[0] + v.getWidth() > -metrics.widthPixels * 0.5f;
            if (near) {
                deferredViews.remove(i);
                deferredLoads.remove(i).run();
            }
        }
    }

    void shutdown() { pool.shutdownNow(); }

    private Bitmap fetch(String url, String key, int width, boolean keepAlpha) {
        File file = new File(diskDir, Integer.toHexString(key.hashCode()) + (keepAlpha ? ".png" : ".jpg"));
        try {
            if (file.exists()) {
                Bitmap fromDisk = BitmapFactory.decodeFile(file.getPath(), options(keepAlpha, 1));
                if (fromDisk != null) return fromDisk;
            }
            byte[] data = Http.get(url);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2;

            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, options(keepAlpha, sample));
            if (bmp == null) return null;
            if (bmp.getWidth() > width * 1.3f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, width, Math.max(1, bmp.getHeight() * width / bmp.getWidth()), true);
                if (scaled != bmp) { bmp.recycle(); bmp = scaled; }
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                bmp.compress(keepAlpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 85, out);
            }
            return bmp;
        } catch (IOException | RuntimeException e) {
            // Missing artwork is normal (not every title has a logo); callers keep their text fallback.
            return null;
        }
    }

    private static BitmapFactory.Options options(boolean keepAlpha, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = keepAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return o;
    }
}
