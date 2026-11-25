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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Downloads, downsamples and caches artwork.
 *
 * Tuned the way the fastest TV apps load theirs, without bundling their libraries:
 *  - a picture already on disk never waits behind a download: disk reads and network
 *    fetches run on separate threads, so a screen seen before paints at once;
 *  - downloads run eight at a time over kept-alive connections, newest request first,
 *    so after a fast scroll the cards now on screen load before the ones flung past;
 *  - two cards asking for the same picture share one download;
 *  - images are decoded straight to the size they're shown at, never at source size,
 *    and the downsized result is what's kept on disk (a ~40 KB JPEG, not a 1 MB original);
 *  - the picture is shown first and written to disk afterwards;
 *  - what's about to be needed can be warmed with {@link #prefetch};
 *  - off-screen cards are queued with {@link #defer} and fetched as they near the screen.
 *
 * Should a download fail it is tried once more, then left: callers keep their text fallback.
 */
final class ImageLoader {
    private static final int DOWNLOADS = 8, DISK_READERS = 2;
    private static final int FETCH_TIMEOUT_MS = 7_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = pool(DISK_READERS, "phonetv-img-disk");
    private final ExecutorService network = pool(DOWNLOADS, "phonetv-img-net");
    private final LruCache<String, Bitmap> memory;
    /** Everyone waiting on a picture that's already being fetched, by cache key. Main thread only. */
    private final Map<String, List<Waiter>> waiting = new HashMap<>();
    private final File diskDir;
    private final DisplayMetrics metrics;
    private final List<View> deferredViews = new ArrayList<>();
    private final List<Runnable> deferredLoads = new ArrayList<>();
    private final int[] loc = new int[2];

    /** A view (or nobody, for a prefetch) that wants a picture once it's ready. */
    private static final class Waiter {
        final ImageView target;
        final Runnable onLoaded;
        Waiter(ImageView target, Runnable onLoaded) { this.target = target; this.onLoaded = onLoaded; }
    }

    /**
     * Worker threads that take the newest job first. They run just below the UI's priority:
     * low enough never to stall a frame, but not "background", which Android throttles to a
     * sliver of one core and which made artwork crawl in.
     */
    private static ExecutorService pool(int threads, String name) {
        LinkedBlockingDeque<Runnable> newestFirst = new LinkedBlockingDeque<Runnable>() {
            @Override public boolean offer(Runnable r) { return offerFirst(r); }
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, newestFirst, r -> {
            Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE * 3); r.run(); }, name);
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    ImageLoader(Context context) {
        diskDir = new File(context.getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        diskDir.mkdirs();
        metrics = context.getResources().getDisplayMetrics();
        // Let that many connections to one image host stay open between pictures (the default is 5).
        if (System.getProperty("http.maxConnections") == null) System.setProperty("http.maxConnections", String.valueOf(DOWNLOADS + 4));
        // A fifth of the heap: about two screens of artwork, so going back up a page is instant.
        memory = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 5)) {
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
        request(key, url, width, keepAlpha, new Waiter(target, onLoaded));
    }

    /** Fetches a picture into memory before any view asks for it (the next banner, the next row). */
    void prefetch(String url, int widthPx, boolean keepAlpha) {
        if (url == null || url.isEmpty()) return;
        final int width = Math.max(64, widthPx);
        final String key = url + "#" + width;
        if (memory.get(key) == null) request(key, url, width, keepAlpha, null);
    }

    private void request(final String key, final String url, final int width, final boolean keepAlpha, Waiter waiter) {
        List<Waiter> queue = waiting.get(key);
        if (queue != null) { if (waiter != null) queue.add(waiter); return; } // already on its way
        queue = new ArrayList<>();
        if (waiter != null) queue.add(waiter);
        waiting.put(key, queue);

        final File file = new File(diskDir, Integer.toHexString(key.hashCode()) + (keepAlpha ? ".png" : ".jpg"));
        disk.execute(() -> {
            Bitmap fromDisk = file.exists() ? BitmapFactory.decodeFile(file.getPath(), options(keepAlpha, 1)) : null;
            if (fromDisk != null) { deliver(key, fromDisk, false); return; }
            network.execute(() -> {
                Bitmap bmp = download(url, width, keepAlpha);
                deliver(key, bmp, true);
                if (bmp != null) store(bmp, file, keepAlpha); // after it's on screen, not before
            });
        });
    }

    /** Hands a finished picture to everyone who asked for it. Any thread. */
    private void deliver(final String key, final Bitmap bmp, final boolean fade) {
        // Upload to the GPU now, on this thread, rather than on the first frame that draws it.
        if (bmp != null) bmp.prepareToDraw();
        main.post(() -> {
            List<Waiter> queue = waiting.remove(key);
            if (bmp == null || queue == null) return;
            memory.put(key, bmp);
            for (Waiter w : queue) {
                // The view may have been reused for another item while this was loading.
                if (!key.equals(w.target.getTag())) continue;
                w.target.setImageBitmap(bmp);
                if (fade) {
                    w.target.setAlpha(0f);
                    w.target.animate().alpha(1f).setDuration(160).start();
                }
                if (w.onLoaded != null) w.onLoaded.run();
            }
        });
    }

    /** Queues {@code load} until {@code anchor} is near the screen. Call {@link #flush} on scroll. */
    void defer(View anchor, Runnable load) {
        deferredViews.add(anchor);
        deferredLoads.add(load);
    }

    /** Forgets queued loads for views inside {@code screen}, which is about to be rebuilt. */
    void clearDeferred(View screen) {
        for (int i = deferredViews.size() - 1; i >= 0; i--) {
            View v = deferredViews.get(i);
            boolean inside = false;
            for (android.view.ViewParent p = v.getParent(); p != null && !inside; p = p.getParent()) inside = p == screen;
            // Never attached, or attached under this screen: either way it won't be needed again.
            if (inside || !v.isAttachedToWindow()) { deferredViews.remove(i); deferredLoads.remove(i); }
        }
    }

    /** Starts any deferred loads whose anchor is within about two screens of the viewport. */
    void flush() {
        for (int i = deferredViews.size() - 1; i >= 0; i--) {
            View v = deferredViews.get(i);
            if (!v.isAttachedToWindow() || v.getWidth() == 0) continue;
            v.getLocationOnScreen(loc);
            // Two screens below and to the side: by the time a card scrolls in, its picture is in memory.
            boolean near = loc[1] < metrics.heightPixels * 2f && loc[1] + v.getHeight() > -metrics.heightPixels * 0.5f
                    && loc[0] < metrics.widthPixels * 2f && loc[0] + v.getWidth() > -metrics.widthPixels * 0.5f;
            if (near) {
                deferredViews.remove(i);
                deferredLoads.remove(i).run();
            }
        }
    }

    void shutdown() { disk.shutdownNow(); network.shutdownNow(); }

    /** Downloads and decodes one picture at the size it will be shown. Null when there is none to show. */
    private static Bitmap download(String url, int width, boolean keepAlpha) {
        byte[] data;
        try {
            data = Http.get(url, new HashMap<String, String>(), FETCH_TIMEOUT_MS);
        } catch (Http.StatusException gone) {
            return null; // 404 and friends: not every title has a logo or a backdrop
        } catch (IOException slow) {
            // A stalled connection: one more try is usually all it takes.
            try { data = Http.get(url, new HashMap<String, String>(), FETCH_TIMEOUT_MS); }
            catch (IOException e) { return null; }
        }
        try {
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
            return bmp;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Keeps the downsized picture for next time. Written beside its final name, then swapped in whole. */
    private static void store(Bitmap bmp, File file, boolean keepAlpha) {
        File part = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            bmp.compress(keepAlpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(file);
    }

    private static BitmapFactory.Options options(boolean keepAlpha, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = keepAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return o;
    }
}
