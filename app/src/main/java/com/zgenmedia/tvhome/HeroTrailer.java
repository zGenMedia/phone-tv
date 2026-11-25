package com.zgenmedia.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;

/**
 * The banner's trailer. After a few seconds on a title, its trailer plays where the
 * picture is, under the same fades, so the banner comes alive without changing shape.
 *
 * The trailer is YouTube's own player in a web view, sized to cover the artwork and
 * shown only once it reports that it is actually playing. A title with no trailer,
 * a trailer that can't be embedded, or a device without a web view simply keeps its
 * picture: nothing appears, nothing breaks.
 *
 * Which title has which trailer (or none that plays) is remembered in a small file
 * and forgotten once the title is no longer one the home screen features.
 */
final class HeroTrailer {
    interface Listener {
        void onPlaying();
        /** The trailer is gone: it ran to its end ({@code played}), or it never started. */
        void onFinished(boolean played);
    }

    /** Pages need a real web address to embed YouTube; the app's own site is the honest one. */
    private static final String ORIGIN = "https://zgenmedia.github.io/phone-tv/";
    private static final String FILE = "trailers.json";
    private static final long GIVE_UP_MS = 25_000;

    private final HomeActivity a;
    private final JSONObject known;
    private WebView web;
    private boolean unavailable; // no web view on this device
    private String playingId;
    private Listener current;
    private final Runnable giveUp = () -> {
        Listener l = current;
        stop();
        if (l != null) l.onFinished(false);
    };

    HeroTrailer(HomeActivity activity) {
        a = activity;
        JSONObject read;
        File file = new File(a.getFilesDir(), FILE);
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] all = new byte[(int) file.length()];
            int got = 0;
            while (got < all.length) { int n = in.read(all, got, all.length - got); if (n < 0) break; got += n; }
            read = new JSONObject(new String(all, 0, got, StandardCharsets.UTF_8));
        } catch (IOException | JSONException | RuntimeException e) { read = new JSONObject(); }
        known = read;
    }

    // ------------------------------------------------------------------ record

    /**
     * The YouTube id of a title's trailer, or "" when it has none that plays. Looked up
     * once (from the catalog's details) and then kept on file. Blocking.
     */
    String lookup(MediaItem m) {
        if (m.id == null || !m.id.startsWith("tt")) return "";
        synchronized (known) {
            JSONObject o = known.optJSONObject(m.id);
            if (o != null) return o.optBoolean("bad") ? "" : o.optString("yt", "");
        }
        String yt = "";
        try {
            JSONObject meta = new JSONObject(new String(Http.get("https://v3-cinemeta.strem.io/meta/" + (m.isShow() ? "series" : "movie") + "/" + m.id + ".json"),
                    StandardCharsets.UTF_8)).optJSONObject("meta");
            JSONArray streams = meta == null ? null : meta.optJSONArray("trailerStreams");
            if (streams != null && streams.length() > 0) yt = streams.getJSONObject(0).optString("ytId", "");
            if (yt.isEmpty()) {
                JSONArray trailers = meta == null ? null : meta.optJSONArray("trailers");
                if (trailers != null && trailers.length() > 0) yt = trailers.getJSONObject(0).optString("source", "");
            }
        } catch (IOException | JSONException e) {
            return ""; // not recorded: asked again next time
        }
        if (!yt.matches("[A-Za-z0-9_-]{6,20}")) yt = "";
        remember(m.id, yt, false);
        return yt;
    }

    private void remember(String id, String yt, boolean bad) {
        synchronized (known) {
            try { known.put(id, new JSONObject().put("yt", yt).put("bad", bad)); } catch (JSONException ignored) { /* plain values */ }
            save();
        }
    }

    /** Forgets every title that the home screen no longer features. */
    void keepOnly(Set<String> featured) {
        synchronized (known) {
            boolean changed = false;
            for (Iterator<String> it = known.keys(); it.hasNext(); ) {
                if (!featured.contains(it.next())) { it.remove(); changed = true; }
            }
            if (changed) save();
        }
    }

    private void save() {
        try (FileOutputStream out = new FileOutputStream(new File(a.getFilesDir(), FILE))) {
            out.write(known.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { /* looked up again next time */ }
    }

    // ------------------------------------------------------------------ player

    boolean playing() { return playingId != null; }

    /**
     * Starts a trailer inside {@code frame}, above the artwork. It stays invisible until
     * YouTube reports it's playing, then fades in. Main thread.
     */
    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    void play(FrameLayout frame, final String id, String youtubeId, boolean muted, final Listener listener) {
        stop();
        if (unavailable) return;
        try {
            web = new WebView(a);
        } catch (RuntimeException | LinkageError e) {
            unavailable = true; // no web view component: the banner stays a picture
            return;
        }
        final WebView w = web;
        w.setBackgroundColor(0x00000000);
        w.setAlpha(0f);
        w.setFocusable(false);
        w.setFocusableInTouchMode(false);
        w.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setOnTouchListener((v, e) -> true); // the banner's own buttons handle touches, not the video
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        w.setWebChromeClient(new WebChromeClient());
        w.addJavascriptInterface(new Object() {
            @JavascriptInterface public void event(final String what) {
                a.main.post(() -> {
                    if (web != w) return; // stopped meanwhile
                    if ("playing".equals(what)) {
                        playingId = id;
                        a.main.removeCallbacks(giveUp);
                        w.bringToFront(); // above a picture that arrived late
                        w.animate().alpha(1f).setDuration(Motion.CROSSFADE * 2L).setInterpolator(Motion.STANDARD).start();
                        listener.onPlaying();
                    } else {
                        // Errors 100/101/150: gone, or its owner doesn't allow it outside youtube.com. Don't ask again.
                        if (what.startsWith("error:1")) remember(id, "", true);
                        boolean wasPlaying = playingId != null;
                        stop();
                        listener.onFinished(wasPlaying);
                    }
                });
            }
        }, "Host");
        current = listener;
        frame.addView(w, new FrameLayout.LayoutParams(-1, -1));
        w.loadDataWithBaseURL(ORIGIN, page(youtubeId, muted), "text/html", "utf-8", null);
        a.main.postDelayed(giveUp, GIVE_UP_MS);
    }

    /** Takes the trailer away at once (the title changed, the page scrolled, the app left the screen). */
    void stop() {
        a.main.removeCallbacks(giveUp);
        playingId = null;
        current = null;
        final WebView w = web;
        web = null;
        if (w == null) return;
        w.animate().cancel();
        w.loadUrl("about:blank");
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        w.destroy();
    }

    /**
     * YouTube's embedded player, filling the frame the way the artwork does (cover, centred)
     * and zoomed a little so the title bar and logo it draws sit outside the visible area.
     */
    private static String page(String youtubeId, boolean muted) {
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}"
                + "#box{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%)}"
                + "#p{width:100%;height:100%;pointer-events:none}</style></head><body><div id=\"box\"><div id=\"p\"></div></div><script>"
                + "var said=false;function fit(){var w=innerWidth,h=innerHeight,r=16/9,bw=Math.max(w,h*r)*1.32,b=document.getElementById('box');"
                + "b.style.width=bw+'px';b.style.height=(bw/r)+'px'}fit();addEventListener('resize',fit);"
                + "function tell(m){try{Host.event(m)}catch(e){}}"
                + "function onYouTubeIframeAPIReady(){new YT.Player('p',{videoId:'" + youtubeId + "',width:'100%',height:'100%',"
                + "playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,modestbranding:1,rel:0,iv_load_policy:3,playsinline:1,cc_load_policy:0,mute:" + (muted ? 1 : 0) + "},"
                + "events:{onReady:function(e){" + (muted ? "e.target.mute();" : "") + "e.target.playVideo()},"
                + "onStateChange:function(e){if(e.data==1&&!said){said=true;setTimeout(function(){tell('playing')},900)}if(e.data==0)tell('ended')},"
                + "onError:function(e){tell('error:'+e.data)}}})}"
                + "</script><script src=\"https://www.youtube.com/iframe_api\"></script></body></html>";
    }
}
