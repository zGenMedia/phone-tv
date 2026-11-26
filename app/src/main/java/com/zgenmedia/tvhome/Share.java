package com.zgenmedia.tvhome;

import android.net.Uri;

/**
 * Links to one thing in Phone TV: a movie, a show, an actor, a network or a studio.
 *
 * A link is an ordinary web address on Phone TV's own site. Opened on a device that has
 * Phone TV, the page hands straight over to the app, which shows the thing. Opened
 * anywhere else, the page says what was shared and offers the download; it also leaves
 * the link on the clipboard, so the first time the freshly installed app opens it can
 * still land on what was shared.
 *
 * The link carries only what is needed to find the subject again: its kind, its public
 * catalog id and its name. Nothing about the person sharing.
 */
final class Share {
    static final String PAGE = "https://zgenmedia.github.io/phone-tv/s/";
    static final String MOVIE = "movie", SERIES = "series", PERSON = "person", COMPANY = "company";

    /** What a link points at, once it has been checked. */
    static final class Target {
        String type, id = "", name = "", kind = "";
    }

    private Share() {}

    static String link(MediaItem m) { return build(m.isShow() ? SERIES : MOVIE, m.id, m.title, null); }

    static String link(Library.Person p) { return build(PERSON, p.id, p.name, null); }

    static String link(Library.Company c) { return build(COMPANY, c.id, c.name, c.kind); }

    private static String build(String type, String id, String name, String kind) {
        Uri.Builder b = Uri.parse(PAGE).buildUpon().appendQueryParameter("t", type);
        if (id != null && !id.isEmpty()) b.appendQueryParameter("id", id);
        b.appendQueryParameter("n", name == null ? "" : name);
        if (kind != null && !kind.isEmpty()) b.appendQueryParameter("k", kind);
        return b.build().toString();
    }

    /** True for text that is (or contains) one of these links: what the clipboard check looks for. */
    static boolean looksLikeLink(CharSequence text) {
        return text != null && text.toString().contains("zgenmedia.github.io/phone-tv/s/?");
    }

    /** Pulls the link out of a longer pasted message ("Watch this! https://…"). */
    static Uri firstLink(CharSequence text) {
        String s = text.toString();
        int at = s.indexOf("https://zgenmedia.github.io/phone-tv/s/?");
        if (at < 0) return null;
        int end = at;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        return Uri.parse(s.substring(at, end));
    }

    /**
     * Reads a link (the web form, or the phonetv:// form the page hands over) and checks it.
     * Anything that isn't one of the four kinds with a well-formed id is refused: a link
     * comes from outside, so it gets to name a subject and nothing else.
     */
    static Target parse(Uri link) {
        if (link == null || link.isOpaque()) return null;
        Target t = new Target();
        t.type = String.valueOf(link.getQueryParameter("t"));
        String id = link.getQueryParameter("id"), name = link.getQueryParameter("n"), kind = link.getQueryParameter("k");
        t.id = id == null ? "" : id;
        t.name = name == null ? "" : name.trim();
        if (t.name.length() > 120) t.name = t.name.substring(0, 120);
        t.kind = Studios.NETWORK.equals(kind) ? Studios.NETWORK : Studios.STUDIO;
        switch (t.type) {
            case MOVIE: case SERIES: return t.id.matches("tt\\d{5,10}") ? t : null;
            case COMPANY: return t.id.matches("Q\\d{1,10}") && !t.name.isEmpty() ? t : null;
            case PERSON:
                if (!t.id.isEmpty() && !t.id.matches("Q\\d{1,10}")) return null;
                return t.id.isEmpty() && t.name.isEmpty() ? null : t;
            default: return null;
        }
    }
}
