package com.zgenmedia.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem.SubtitleConfiguration;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phone TV's own player, for streams from the user's add-ons.
 *
 * Plain framework views around Media3's ExoPlayer: a surface sized to the video,
 * subtitles drawn as text, and a bar with the few controls a remote needs.
 * The remote: OK shows the bar and pauses or plays, left and right seek, up and
 * down show the bar, Back hides it and then leaves.
 *
 * Position is saved to {@link Progress} as it plays; past 90 % the title counts as
 * watched. Leaving with "Next episode" or "Other source" hands back to the home
 * screen, which opens the source list for that.
 */
// Media3 marks its data-source and track classes "unstable" (their API may change between
// versions); the version is pinned in build.gradle, so using them here is deliberate.
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
    static final String X_URL = "url", X_HEADERS = "headers", X_ID = "id", X_TYPE = "type", X_EPISODE = "episode",
            X_TITLE = "title", X_LABEL = "label", X_POSTER = "poster", X_BACKDROP = "backdrop", X_LOGO = "logo",
            X_RESUME = "resume", X_ADDON = "addon", X_NEXT_EPISODE = "nextEpisode", X_NEXT_LABEL = "nextLabel",
            X_PLEX = "plex";
    static final int RESULT_NEXT = 2, RESULT_OTHER_SOURCE = 3;
    private static final long SEEK_MS = 10_000, BAR_HIDE_MS = 4_000, SAVE_EVERY_MS = 10_000;

    private ExoPlayer player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Prefs prefs;
    private FrameLayout root, videoBox;
    private SurfaceView surface;
    private TextView captions, timeNow, timeLeft, status, playButton, nextButton;
    private View buffering;
    private LinearLayout bar;
    private SeekBar seek;
    private float videoAspect;
    private boolean finishedMarked, seeking;
    private String id, type, episodeId, title, label, url, addon, nextEpisode;
    private final Map<String, String> headers = new HashMap<>();

    private final Runnable hideBar = this::hideBar;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateTimes();
            main.postDelayed(this, 500);
        }
    };
    private final Runnable saver = new Runnable() {
        @Override public void run() {
            saveProgress();
            main.postDelayed(this, SAVE_EVERY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        Ui.applyTheme(prefs);
        ui = new Ui(this, prefs.scale());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Video is landscape unless the user has held the whole app to portrait (Settings › Look).
        if (prefs.orientationChosen() == Prefs.TURN_PORTRAIT) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        readIntent(getIntent());
        buildViews();
        setContentView(root);
        hideSystemBars();
        startPlayer(getIntent().getLongExtra(X_RESUME, 0));
    }

    /** Set when the title comes from the user's Plex server: where to report progress, and a second link to try. */
    private Bundle plex;
    private boolean triedPlexFallback;

    private void readIntent(Intent i) {
        url = i.getStringExtra(X_URL);
        id = i.getStringExtra(X_ID);
        type = i.getStringExtra(X_TYPE);
        episodeId = i.getStringExtra(X_EPISODE);
        title = nz(i.getStringExtra(X_TITLE));
        label = nz(i.getStringExtra(X_LABEL));
        addon = nz(i.getStringExtra(X_ADDON));
        nextEpisode = i.getStringExtra(X_NEXT_EPISODE);
        plex = i.getBundleExtra(X_PLEX);
        Bundle h = i.getBundleExtra(X_HEADERS);
        if (h != null) for (String k : h.keySet()) headers.put(k, h.getString(k));
    }

    // ===================================================================== views

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);
        root.setOnClickListener(v -> toggleBar());

        videoBox = new FrameLayout(this);
        surface = new SurfaceView(this);
        videoBox.addView(surface);
        root.addView(videoBox, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());

        captions = new TextView(this);
        captions.setTextColor(Color.WHITE);
        captions.setTextSize(prefs.subtitleSize());
        captions.setGravity(Gravity.CENTER);
        captions.setShadowLayer(ui.dp(3), 0, ui.dp(1), Color.BLACK);
        captions.setPadding(ui.dp(40), 0, ui.dp(40), 0);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        clp.bottomMargin = ui.dp(36);
        root.addView(captions, clp);

        status = ui.label("", 15, Ui.TEXT, Ui.MEDIUM);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        // While the video is loading or catching up: three dots over the picture, no words needed.
        buffering = new Loading.Dots(this, ui.dp(12), 0xFFFFFFFF);
        buffering.setVisibility(View.GONE);
        root.addView(buffering, new FrameLayout.LayoutParams(ui.dp(76), ui.dp(40), Gravity.CENTER));

        // The bar: title on top, the timeline and the buttons below, over a soft shade.
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(ui.dp(36), ui.dp(60), ui.dp(36), ui.dp(24));
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000});
        bar.setBackground(shade);
        TextView name = ui.label(title, 22, Color.WHITE, Ui.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(name);
        if (!label.isEmpty() || !addon.isEmpty()) {
            TextView sub = ui.label(TextUtils.join("  ·  ", nonEmpty(label, addon)), 13, Ui.TEXT_DIM, null);
            sub.setPadding(0, ui.dp(2), 0, ui.dp(10));
            bar.addView(sub);
        }
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        timeNow = ui.label("0:00", 13, Color.WHITE, Ui.MEDIUM);
        timeLeft = ui.label("", 13, Ui.TEXT_DIM, Ui.MEDIUM);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setFocusable(true);
        seek.setKeyProgressIncrement(10);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0) {
                    player.seekTo(player.getDuration() * p / 1000);
                    keepBar();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { seeking = false; }
        });
        line.addView(timeNow);
        line.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(timeLeft);
        bar.addView(line);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, ui.dp(10), 0, 0);
        playButton = button(buttons, "❚❚", this::togglePlay);
        button(buttons, "−10", () -> seekBy(-SEEK_MS));
        button(buttons, "+30", () -> seekBy(3 * SEEK_MS));
        button(buttons, getString(R.string.player_audio), () -> pickTrack(C.TRACK_TYPE_AUDIO));
        button(buttons, getString(R.string.player_subtitles), () -> pickTrack(C.TRACK_TYPE_TEXT));
        if (plex == null) button(buttons, getString(R.string.player_sources), () -> leave(RESULT_OTHER_SOURCE));
        if (nextEpisode != null) nextButton = button(buttons, getString(R.string.player_next), () -> leave(RESULT_NEXT));
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.addView(buttons);
        bar.addView(strip);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private TextView button(LinearLayout row, String text, Runnable run) {
        TextView b = ui.pill(text, 0x33FFFFFF, Color.WHITE);
        b.setOnClickListener(v -> { run.run(); keepBar(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(8);
        row.addView(b, lp);
        return b;
    }

    /** Letterboxes the surface to the video's shape. */
    private void fitVideo() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        int vw = w, vh = h;
        if (videoAspect > 0) {
            if (w / (float) h > videoAspect) vw = Math.round(h * videoAspect); else vh = Math.round(w / videoAspect);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER);
        if (surface.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) surface.getLayoutParams();
            if (old.width == vw && old.height == vh && old.gravity == Gravity.CENTER) return;
        }
        surface.setLayoutParams(lp);
    }

    // ==================================================================== player

    private void startPlayer(long resumeMs) {
        if (url == null) { finish(); return; }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Http.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(headers);
        // Sound: the device's own decoders first; for formats it lacks (Dolby and DTS on most phones
        // and PC emulators) the bundled software decoder takes over, so a film is never silent.
        androidx.media3.exoplayer.DefaultRenderersFactory renderers = new androidx.media3.exoplayer.DefaultRenderersFactory(this)
                .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .build();
        // Tell Android this is a film: it takes audio focus and gets the movie sound profile.
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setVolume(1f);
        player.setVideoSurfaceView(surface);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredTextLanguage(prefs.subtitleLanguage())
                .setPreferredAudioLanguage(prefs.audioLanguage())
                .build());
        // A device often decodes more than it advertises (4K on a chip that claims 1080p).
        // Without this the player quietly drops such a picture and plays the sound alone;
        // with it the picture is tried, and only a real failure is reported.
        if (player.getTrackSelector() instanceof androidx.media3.exoplayer.trackselection.DefaultTrackSelector) {
            androidx.media3.exoplayer.trackselection.DefaultTrackSelector selector =
                    (androidx.media3.exoplayer.trackselection.DefaultTrackSelector) player.getTrackSelector();
            selector.setParameters(selector.buildUponParameters()
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .clearViewportSizeConstraints());
        }
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) { videoAspect = size.width * size.pixelWidthHeightRatio / size.height; fitVideo(); }
            }
            @Override public void onCues(CueGroup cues) {
                StringBuilder text = new StringBuilder();
                for (Cue c : cues.cues) if (c.text != null) { if (text.length() > 0) text.append('\n'); text.append(c.text); }
                captions.setText(text);
            }
            @Override public void onPlaybackStateChanged(int state) {
                status.setText("");
                buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_ENDED) { markFinished(); if (nextEpisode != null) leave(RESULT_NEXT); else finish(); }
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                playButton.setText(playing ? "❚❚" : "▶");
                if (!playing) { saveProgress(); showBar(); } else keepBar();
            }
            @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                // Sound that exists but can't be decoded here would otherwise just be silence. Say so.
                boolean hasAudio = false, playable = false;
                for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
                    hasAudio = true;
                    playable |= g.isSupported();
                }
                if (hasAudio && !playable) {
                    status.setText(R.string.player_no_sound_format);
                    main.postDelayed(() -> status.setText(""), 8000);
                }
                // The same for the picture: a film with sound and a black screen needs explaining.
                String unplayed = null;
                boolean hasVideo = false, showing = false;
                for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
                    hasVideo = true;
                    showing |= g.isSelected();
                    if (unplayed == null && g.length > 0) unplayed = describeVideo(g.getTrackFormat(0));
                }
                if (hasVideo && !showing && !toldNoPicture) { toldNoPicture = true; noPicture(unplayed); }
            }
            @Override public void onPlayerError(PlaybackException e) { failed(e); }
        });
        buffering.setVisibility(View.VISIBLE);
        // Subtitles from add-ons are fetched first (a few seconds at most), then playback starts.
        new Thread(() -> {
            List<Addons.Subtitle> subs = id == null ? new ArrayList<>()
                    : Addons.of(this).subtitles(type, episodeId != null ? episodeId : id);
            main.post(() -> {
                if (isFinishing() || player == null) return;
                androidx.media3.common.MediaItem.Builder item = new androidx.media3.common.MediaItem.Builder().setUri(Uri.parse(url));
                List<SubtitleConfiguration> configs = new ArrayList<>();
                for (Addons.Subtitle s : subs.subList(0, Math.min(subs.size(), 40))) {
                    configs.add(new SubtitleConfiguration.Builder(Uri.parse(s.url))
                            .setMimeType(subtitleMime(s.url)).setLanguage(s.lang).setLabel(language(s.lang) + " · " + s.addon)
                            .setId(s.id).build());
                }
                item.setSubtitleConfigurations(configs);
                player.setMediaItem(item.build(), Math.max(0, resumeMs));
                player.prepare();
                player.play();
                main.post(tick);
                main.postDelayed(saver, SAVE_EVERY_MS);
            });
        }, "phonetv-subtitles").start();
        showBar();
    }

    private static String subtitleMime(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(".vtt")) return MimeTypes.TEXT_VTT;
        if (u.contains(".ass") || u.contains(".ssa")) return MimeTypes.TEXT_SSA;
        if (u.contains(".ttml") || u.contains(".dfxp")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.APPLICATION_SUBRIP; // what subtitle add-ons almost always serve, often without an extension
    }

    private boolean toldNoPicture;

    /** "4K HDR (Dolby Vision)", "4K (HEVC)": the picture format in the words on the box. */
    private static String describeVideo(androidx.media3.common.Format f) {
        String mime = f.sampleMimeType == null ? "" : f.sampleMimeType;
        String codec = mime.contains("dolby-vision") ? "Dolby Vision" : mime.contains("hevc") ? "HEVC" : mime.contains("av01") ? "AV1"
                : mime.contains("avc") ? "H.264" : mime.contains("vp9") ? "VP9" : mime.replace("video/", "");
        String size = f.height >= 2000 || f.width >= 3800 ? "4K" : f.height >= 1000 ? "1080p" : f.height > 0 ? f.height + "p" : "";
        boolean hdr = f.colorInfo != null && (f.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 || f.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG);
        return (size + (hdr && !mime.contains("dolby-vision") ? " HDR" : "") + " (" + codec + ")").trim();
    }

    /** The sound is playing but this device has no way to show the picture: say which format, and offer the way out. */
    private void noPicture(String format) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.player_no_picture)
                .setMessage(getString(R.string.player_no_picture_body, format == null || format.isEmpty() ? "?" : format))
                .setPositiveButton(plex != null ? R.string.player_open_plex : R.string.player_try_other, (d, w) -> leave(RESULT_OTHER_SOURCE))
                .setNeutralButton(R.string.player_open_elsewhere, (d, w) -> openElsewhere())
                .setNegativeButton(R.string.player_keep_listening, null)
                .show();
    }

    private void failed(PlaybackException e) {
        if (plex != null && !triedPlexFallback && plex.getString("fallback") != null) {
            // The file itself wouldn't play here (a codec this device lacks, usually): let the server convert it.
            triedPlexFallback = true;
            long at = player == null ? 0 : Math.max(0, player.getCurrentPosition());
            if (at == 0) at = getIntent().getLongExtra(X_RESUME, 0);
            url = plex.getString("fallback");
            main.removeCallbacks(saver);
            if (player != null) { player.release(); player = null; }
            startPlayer(at);
            return;
        }
        saveProgress();
        buffering.setVisibility(View.GONE);
        String why = e.getErrorCodeName().replace("ERROR_CODE_", "").replace('_', ' ').toLowerCase(Locale.ROOT);
        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(R.string.player_failed)
                .setMessage(getString(R.string.player_failed_body, why))
                .setPositiveButton(plex != null ? R.string.player_open_plex : R.string.player_try_other, (d, w) -> leave(RESULT_OTHER_SOURCE))
                .setNeutralButton(R.string.player_open_elsewhere, (d, w) -> openElsewhere())
                .setNegativeButton(R.string.player_close, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    /** Hands the same stream to another installed player (VLC, MX Player, Just Player…). */
    private void openElsewhere() {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
                .putExtra("title", title).putExtra("position", (int) (player == null ? 0 : player.getCurrentPosition()));
        if (!headers.isEmpty()) view.putExtra("headers", toHeaderArray());
        try { startActivity(Intent.createChooser(view, getString(R.string.player_open_elsewhere))); }
        catch (android.content.ActivityNotFoundException ignored) { /* nothing else can play video */ }
        finish();
    }

    private String[] toHeaderArray() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : headers.entrySet()) { out.add(e.getKey()); out.add(e.getValue()); }
        return out.toArray(new String[0]);
    }

    private void pickTrack(int trackType) {
        if (player == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> picks = new ArrayList<>();
        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add(getString(R.string.player_off));
            picks.add(() -> player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()));
        }
        int chosen = trackType == C.TRACK_TYPE_TEXT && player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ? 0 : -1;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != trackType) continue;
            TrackGroup group = g.getMediaTrackGroup();
            for (int i = 0; i < group.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = group.getFormat(i);
                if (g.isTrackSelected(i) && chosen < 0) chosen = labels.size();
                labels.add(trackName(f, trackType));
                final int index = i;
                picks.add(() -> player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(trackType, false)
                        .setOverrideForType(new TrackSelectionOverride(group, index)).build()));
            }
        }
        if (labels.isEmpty() || (trackType == C.TRACK_TYPE_TEXT && labels.size() == 1)) {
            status.setText(trackType == C.TRACK_TYPE_TEXT ? R.string.player_no_subtitles : R.string.player_no_audio);
            main.postDelayed(() -> status.setText(""), 2500);
            return;
        }
        new AlertDialog.Builder(this, R.style.PhoneTvDialog)
                .setTitle(trackType == C.TRACK_TYPE_TEXT ? R.string.player_subtitles : R.string.player_audio)
                .setSingleChoiceItems(labels.toArray(new String[0]), Math.max(0, chosen), (d, which) -> { picks.get(which).run(); d.dismiss(); })
                .show();
    }

    private String trackName(Format f, int trackType) {
        List<String> parts = new ArrayList<>();
        if (f.label != null && !f.label.isEmpty()) parts.add(f.label);
        else if (f.language != null && !"und".equals(f.language)) parts.add(language(f.language));
        if (trackType == C.TRACK_TYPE_AUDIO) {
            if (f.channelCount > 0) parts.add(f.channelCount >= 6 ? (f.channelCount - 1) + ".1" : f.channelCount == 2 ? "Stereo" : f.channelCount + " ch");
            if (f.sampleMimeType != null) parts.add(codec(f.sampleMimeType));
        }
        return parts.isEmpty() ? getString(R.string.player_track_unnamed) : TextUtils.join(" · ", parts);
    }

    private static String codec(String mime) {
        switch (mime) {
            case MimeTypes.AUDIO_AC3: return "Dolby Digital";
            case MimeTypes.AUDIO_E_AC3: case MimeTypes.AUDIO_E_AC3_JOC: return "Dolby Digital Plus";
            case MimeTypes.AUDIO_DTS: case MimeTypes.AUDIO_DTS_HD: return "DTS";
            case MimeTypes.AUDIO_TRUEHD: return "TrueHD";
            case MimeTypes.AUDIO_AAC: return "AAC";
            case MimeTypes.AUDIO_OPUS: return "Opus";
            default: return mime.substring(mime.indexOf('/') + 1).toUpperCase(Locale.ROOT);
        }
    }

    private static String language(String code) {
        if (code == null || code.isEmpty()) return "";
        String name = new Locale(code).getDisplayLanguage();
        return name.isEmpty() || name.equalsIgnoreCase(code) ? code : name;
    }

    // ================================================================== progress

    private void saveProgress() {
        if (player != null && plex != null) { reportToPlex(isFinishing() ? "stopped" : player.isPlaying() ? "playing" : "paused"); return; }
        if (player == null || id == null) return;
        long duration = player.getDuration(), position = player.getCurrentPosition();
        if (duration <= 0 || duration == C.TIME_UNSET) return;
        if (position / (float) duration >= Progress.DONE) { markFinished(); return; }
        if (position < 30_000) return; // a peek isn't worth a Continue watching card
        Progress.Entry e = new Progress.Entry();
        e.id = id;
        e.type = type;
        e.episodeId = episodeId == null ? "" : episodeId;
        e.title = title;
        e.episodeLabel = label;
        e.posterRef = nz(getIntent().getStringExtra(X_POSTER));
        e.backdropRef = nz(getIntent().getStringExtra(X_BACKDROP));
        e.logoRef = nz(getIntent().getStringExtra(X_LOGO));
        e.url = url;
        e.addon = addon;
        e.headers.putAll(headers);
        e.positionMs = position;
        e.durationMs = duration;
        Progress.of(this).update(e);
    }

    /** Plex keeps its own Continue Watching: tell the server where this got to. */
    private void reportToPlex(String state) {
        long duration = player.getDuration(), position = player.getCurrentPosition();
        if (duration <= 0 || duration == C.TIME_UNSET) return;
        if (position / (float) duration >= Progress.DONE) { markFinished(); return; }
        final Bundle p = plex;
        new Thread(() -> PlexClient.report(p.getString("base"), p.getString("token"), p.getString("client"), p.getString("key"), state, position, duration),
                "phonetv-plex-progress").start();
    }

    /** Past 90 %: watched. A movie leaves Continue watching; a show stays as "next episode". */
    private void markFinished() {
        if (plex != null && !finishedMarked) {
            finishedMarked = true;
            final Bundle p = plex;
            new Thread(() -> PlexClient.markWatched(p.getString("base"), p.getString("token"), p.getString("client"), p.getString("key")), "phonetv-plex-watched").start();
            return;
        }
        if (finishedMarked || id == null) return;
        finishedMarked = true;
        MediaItem m = MediaItem.catalog(id, type, title, "");
        Library.of(this).watched(m, Library.episodeLabel(episodeId), getString(R.string.player_name));
        Progress progress = Progress.of(this);
        if (episodeId == null) { progress.remove(id); return; }
        Progress.Entry e = new Progress.Entry();
        e.id = id;
        e.type = type;
        e.episodeId = episodeId;
        e.title = title;
        e.episodeLabel = label;
        e.posterRef = nz(getIntent().getStringExtra(X_POSTER));
        e.backdropRef = nz(getIntent().getStringExtra(X_BACKDROP));
        e.logoRef = nz(getIntent().getStringExtra(X_LOGO));
        e.positionMs = 1;
        e.durationMs = 1; // finished: the home screen offers the next episode
        progress.update(e);
    }

    // ==================================================================== remote

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean barUp = bar.getVisibility() == View.VISIBLE;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_SPACE: togglePlay(); showBar(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: if (player != null) player.play(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: if (player != null) player.pause(); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekBy(3 * SEEK_MS); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: seekBy(-SEEK_MS); return true;
            case KeyEvent.KEYCODE_BACK:
                if (barUp && e.getRepeatCount() == 0) { hideBar(); return true; }
                return super.dispatchKeyEvent(e);
            default: break;
        }
        if (!barUp) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: seekBy(-SEEK_MS); showBar(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: seekBy(SEEK_MS); showBar(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    togglePlay(); showBar(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: showBar(); return true;
                default: break;
            }
        }
        keepBar();
        return super.dispatchKeyEvent(e);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
    }

    private void seekBy(long ms) {
        if (player == null) return;
        long to = Math.max(0, player.getCurrentPosition() + ms);
        if (player.getDuration() > 0) to = Math.min(to, player.getDuration() - 1000);
        player.seekTo(to);
        updateTimes();
    }

    private void toggleBar() { if (bar.getVisibility() == View.VISIBLE) hideBar(); else showBar(); }

    private void showBar() {
        if (bar.getVisibility() != View.VISIBLE) {
            bar.setVisibility(View.VISIBLE);
            bar.setAlpha(0f);
            bar.animate().alpha(1f).setDuration(Motion.FAST).start();
            playButton.requestFocus();
        }
        keepBar();
    }

    private void keepBar() {
        main.removeCallbacks(hideBar);
        if (player != null && player.isPlaying()) main.postDelayed(hideBar, BAR_HIDE_MS);
    }

    private void hideBar() {
        main.removeCallbacks(hideBar);
        bar.animate().alpha(0f).setDuration(Motion.FAST).withEndAction(() -> bar.setVisibility(View.GONE)).start();
        root.requestFocus();
    }

    private void updateTimes() {
        if (player == null) return;
        long d = player.getDuration(), p = player.getCurrentPosition();
        timeNow.setText(clock(p));
        boolean known = d > 0 && d != C.TIME_UNSET;
        timeLeft.setText(known ? "−" + clock(d - p) : "");
        if (known && !seeking && !seek.isFocused()) seek.setProgress((int) (1000 * p / d));
        if (known && nextButton != null && d - p < 90_000) nextButton.setVisibility(View.VISIBLE);
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ================================================================= lifecycle

    private void leave(int result) {
        saveProgress();
        Intent data = new Intent().putExtra(X_ID, id).putExtra(X_TYPE, type).putExtra(X_TITLE, title)
                .putExtra(X_EPISODE, result == RESULT_NEXT ? nextEpisode : episodeId)
                .putExtra(X_LABEL, result == RESULT_NEXT ? getIntent().getStringExtra(X_NEXT_LABEL) : label)
                .putExtra(X_ADDON, addon);
        setResult(result, data);
        finish();
    }

    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static List<String> nonEmpty(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) out.add(p);
        return out;
    }
}
