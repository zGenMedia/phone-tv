package com.zgenmedia.tvhome;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import java.util.Locale;

/**
 * What picture formats this device can actually decode.
 *
 * A TV box such as the Shield shows 4K HDR and Dolby Vision; a phone or a PC emulator
 * usually stops at 1080p. Add-ons label their sources ("2160p", "4K", "DV", "HDR"), so the
 * sources page can say in advance which ones are likely to play as sound over a black
 * screen here, and auto-play can pass them over.
 *
 * Asked once and remembered: the answer can't change while the app runs.
 */
final class VideoSupport {
    private static final java.util.regex.Pattern FOUR_K = java.util.regex.Pattern.compile("\\b4k\\b"),
            DOLBY_VISION = java.util.regex.Pattern.compile("\\b(dv|dovi)\\b");
    private static Boolean fourK, dolbyVision;

    private VideoSupport() {}

    /** False when a source's own label names a format this device has no decoder for. */
    static boolean likelyToShow(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        // Labels run over several lines ("Torrentio\n4K DV"), so these look anywhere in the text.
        boolean is4k = l.contains("2160") || FOUR_K.matcher(l).find() || l.contains("uhd");
        boolean isDv = DOLBY_VISION.matcher(l).find() || l.contains("dolby vision");
        if (isDv && !l.contains("hdr") && !dolbyVision()) return false; // Dolby Vision with no HDR10 layer to fall back to
        return !is4k || fourK();
    }

    static synchronized boolean fourK() {
        if (fourK == null) fourK = decodes("video/hevc", 3840, 2160) || decodes("video/avc", 3840, 2160);
        return fourK;
    }

    static synchronized boolean dolbyVision() {
        if (dolbyVision == null) dolbyVision = decodes("video/dolby-vision", 1920, 1080);
        return dolbyVision;
    }

    private static boolean decodes(String mime, int width, int height) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mime)) continue;
                    MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(type).getVideoCapabilities();
                    if (video != null && video.isSizeSupported(width, height)) return true;
                }
            }
        } catch (RuntimeException unknown) {
            return true; // can't tell: don't warn about something that may well work
        }
        return false;
    }
}
