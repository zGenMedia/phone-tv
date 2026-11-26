package com.zgenmedia.tvhome;

import android.net.Uri;

/**
 * Links to one thing in Phone TV: a movie, a show, an actor, a network or a studio.
 *
 * A link is an ordinary web address on zGenMedia's site (older ones live on Phone TV's own
 * pages and still work). Opened on a device that has
 * Phone TV, the page hands straight over to the app, which shows the thing. Opened
 * anywhere else, the page says what was shared and offers the download; it also leaves
 * the link on the clipboard, so the first time the freshly installed app opens it can
 * still land on what was shared.
 *
 * The link carries only what is needed to find the subject again: its kind, its public
 * catalog id and its name. Nothing about the person sharing.
 */
final class Share {
    static final String PAGE = "https://zgenmedia.com/tv/";
    /** Every address a link has ever had. Links already out in the world must keep opening. */
    private static final java.util.regex.Pattern LINK = java.util.regex.Pattern.compile(
            "https://(?:(?:www\\.)?zgenmedia\\.com/tv/|zgenmedia\\.github\\.io/phone-tv/s/)\\?\\S+");
    static final String MOVIE = "movie", SERIES = "series", PERSON = "person", COMPANY = "company";

    /** What a link points at, once it has been checked. */
    static final class Target {
        String type, id = "", name = "", kind = "";
    }

    private Share() {}

    static String link(MediaItem m) { return build(m.isShow() ? SERIES : MOVIE, m.id, m.title, null, null); }

    static String link(Library.Person p) { return build(PERSON, p.id, p.name, null, p.photo); }

    static String link(Library.Company c) { return build(COMPANY, c.id, c.name, c.kind, c.logo); }

    /**
     * @param picture the face or logo to put on the card. A title needs none (its artwork is found
     *                from its id); for a person or company it rides along so the page and any
     *                social card can show it without looking anything up. Only pictures from the
     *                public image hosts Phone TV already uses are passed on.
     */
    private static String build(String type, String id, String name, String kind, String picture) {
        Uri.Builder b = Uri.parse(PAGE).buildUpon().appendQueryParameter("t", type);
        if (id != null && !id.isEmpty()) b.appendQueryParameter("id", id);
        b.appendQueryParameter("n", name == null ? "" : name);
        if (kind != null && !kind.isEmpty()) b.appendQueryParameter("k", kind);
        if (picture != null && fromKnownHost(picture)) {
            // The app keeps thumbnails; a card wants the same picture larger.
            b.appendQueryParameter("i", picture.replace("http://", "https://").replaceAll("\\?width=\\d+$", "?width=720"));
        }
        return b.build().toString();
    }

    /** The image hosts a link may point a card at. The share page and the card service hold the same list. */
    static boolean fromKnownHost(String url) {
        String host = Uri.parse(url.replace("http://", "https://")).getHost();
        return host != null && (host.equals("commons.wikimedia.org") || host.equals("upload.wikimedia.org")
                || host.equals("static.tvmaze.com") || host.equals("images.metahub.space"));
    }

    /** True for text that is (or contains) one of these links: what the clipboard check looks for. */
    static boolean looksLikeLink(CharSequence text) {
        return text != null && LINK.matcher(text).find();
    }

    /** Pulls the link out of a longer pasted message ("Watch this! https://…"). */
    static Uri firstLink(CharSequence text) {
        java.util.regex.Matcher m = LINK.matcher(text);
        return m.find() ? Uri.parse(m.group()) : null;
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
