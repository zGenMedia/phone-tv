package com.zgenmedia.tvhome;

/**
 * How the picture is fitted to the screen.
 *
 * Seven of these are plain sizing: the same ones Nuvio offers (fit, crop, stretch, two
 * zooms, fit to height, fit to width). The eighth is the one Windows Media Center called
 * "Zoom 4" and TV makers call Panorama, Wide Zoom or Just: for an old 4:3 programme on a
 * widescreen TV. Stretching it evenly makes everyone wide; cropping it loses heads and
 * captions. Panorama leaves the middle of the picture, where faces are, in its true
 * shape and spreads the stretch toward the sides, where the eye forgives it, with a
 * little trimmed from the top and bottom so the sides need stretching less.
 *
 * The maths for that lives here, in plain Java, so it can be checked without a screen;
 * {@link PanoramaView} draws with the same formula in a shader.
 */
final class Picture {
    static final int FIT = 0, FILL = 1, STRETCH = 2, SLIGHT_ZOOM = 3, CINEMA_ZOOM = 4, FIT_HEIGHT = 5, FIT_WIDTH = 6, PANORAMA = 7;
    /** In the order they're offered. */
    static final int[] MODES = {FIT, FILL, STRETCH, PANORAMA, SLIGHT_ZOOM, CINEMA_ZOOM, FIT_HEIGHT, FIT_WIDTH};
    static final int[] NAMES = {R.string.picture_fit, R.string.picture_fill, R.string.picture_stretch, R.string.picture_slight_zoom,
            R.string.picture_cinema_zoom, R.string.picture_fit_height, R.string.picture_fit_width, R.string.picture_panorama};

    /** A picture this close to the screen's own shape has nothing to gain from Panorama. */
    static final float WORTH_BENDING = 1.03f;
    /** The middle is drawn at most this much "faster" than the average, which keeps the sides from over-stretching. */
    private static final float STEEPEST_CENTRE = 1.35f;

    private Picture() {}

    static int name(int mode) { return mode >= 0 && mode < NAMES.length ? NAMES[mode] : NAMES[FIT]; }

    /**
     * The size to draw the video at, centred, for every mode but Panorama. Larger than the
     * screen means the overflow is cropped.
     *
     * @param shape the video's width over its height
     * @return {width, height} in pixels
     */
    static int[] size(int mode, float shape, int screenW, int screenH) {
        if (shape <= 0) return new int[]{screenW, screenH};
        boolean wider = screenW / (float) screenH > shape; // the screen is wider than the picture: bars at the sides
        int fitW = wider ? Math.round(screenH * shape) : screenW;
        int fitH = wider ? screenH : Math.round(screenW / shape);
        switch (mode) {
            case FILL:        return wider ? new int[]{screenW, Math.round(screenW / shape)} : new int[]{Math.round(screenH * shape), screenH};
            case STRETCH:     return new int[]{screenW, screenH};
            case SLIGHT_ZOOM: return new int[]{Math.round(fitW * 1.15f), Math.round(fitH * 1.15f)};
            case CINEMA_ZOOM: return new int[]{Math.round(fitW * 1.33f), Math.round(fitH * 1.33f)};
            case FIT_HEIGHT:  return new int[]{Math.round(screenH * shape), screenH};
            case FIT_WIDTH:   return new int[]{screenW, Math.round(screenW / shape)};
            default:          return new int[]{fitW, fitH};
        }
    }

    // ------------------------------------------------------------------ panorama

    /**
     * How much taller than the screen the picture is drawn in Panorama (the rest is trimmed,
     * half from the top and half from the bottom).
     *
     * @param widen the screen's shape over the picture's: 1.33 for 4:3 on 16:9
     */
    static float panoramaZoom(float widen) {
        // A quarter of the gap is closed by zooming; more only when the picture is so narrow
        // that the sides would otherwise have to stretch past what looks like a picture.
        return Math.max(1f + (widen - 1f) * 0.25f, widen / STEEPEST_CENTRE);
    }

    /** How steeply the middle of the screen reads across the picture; this is what keeps the middle in shape. */
    static float panoramaCentre(float widen) { return widen / panoramaZoom(widen); }

    /**
     * Where across the picture (-1 left edge, 1 right edge) a place across the screen (-1 … 1) is taken from.
     * A cubic: steep in the middle (true shape), easing off toward the sides (stretched), edge on edge.
     */
    static float panoramaSource(float screenX, float centre) {
        return centre * screenX - (centre - 1f) * screenX * Math.abs(screenX) * Math.abs(screenX);
    }
}
