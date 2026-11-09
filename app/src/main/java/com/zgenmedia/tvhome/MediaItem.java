package com.zgenmedia.tvhome;

import android.net.Uri;

/**
 * A movie, show or episode shown on the home screen, from either the public
 * catalog or the user's own Plex server.
 *
 * Artwork is resolved lazily per requested pixel width so each surface only
 * downloads what it needs (row cards use ~480 px art, the hero uses ~1280 px).
 */
final class MediaItem {
    enum Source { CATALOG, PLEX }

    final Source source;
    String id;            // IMDb id (catalog) or Plex ratingKey
    String type;          // "movie", "series", "episode", "season"
    String title;
    String subtitle = ""; // e.g. "S2 · E4 · The Episode"
    String summary = "";
    String year = "";
    String rating = "";
    String genres = "";
    int progressPercent;  // Plex resume position, 0 when unwatched
    long viewOffsetMs;    // same position in milliseconds, passed to Plex when resuming

    // Raw artwork references: full URLs for the catalog, server paths for Plex.
    String posterRef, backdropRef, logoRef;

    // Plex connection details, needed to build image and deep-link URLs.
    String plexBaseUri, plexToken, plexMachineId;

    MediaItem(Source source) { this.source = source; }

    boolean isShow() { return "series".equals(type) || "season".equals(type); }

    String poster(int widthPx) {
        if (posterRef == null || posterRef.isEmpty()) return null;
        if (source == Source.PLEX) return plexImage(posterRef, widthPx, widthPx * 3 / 2);
        // Catalog artwork is served by metahub in fixed sizes; pick the smallest that's sharp enough.
        return posterRef.replaceFirst("/poster/(small|medium|large)/", "/poster/" + (widthPx <= 320 ? "small" : "medium") + "/");
    }

    String backdrop(int widthPx) {
        if (backdropRef == null || backdropRef.isEmpty()) return null;
        if (source == Source.PLEX) return plexImage(backdropRef, widthPx, widthPx * 9 / 16);
        return backdropRef.replaceFirst("/background/(small|medium|large)/", "/background/" + (widthPx <= 600 ? "small" : "medium") + "/");
    }

    String logo() { return logoRef == null || logoRef.isEmpty() ? null : logoRef; }

    private String plexImage(String path, int w, int h) {
        return plexBaseUri + "/photo/:/transcode?width=" + w + "&height=" + h
                + "&minSize=1&upscale=1&url=" + Uri.encode(path) + "&X-Plex-Token=" + plexToken;
    }
}
