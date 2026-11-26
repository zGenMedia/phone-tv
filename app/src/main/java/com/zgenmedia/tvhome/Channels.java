package com.zgenmedia.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Channels: line-ups you tune in to instead of choosing from.
 *
 * Each channel is a pool of titles (a network's catalog, a studio's films, a genre)
 * laid end to end on a clock that never stops. Ask what's on now and you get a title
 * and how far into it the channel is; tuning in starts playback from that point, the
 * way switching to a TV channel does. Nothing is broadcast: the schedule is arithmetic
 * that every copy of Phone TV does the same way, so two people on the same channel at
 * the same moment are watching the same thing.
 *
 * These are Phone TV's own line-ups, built from what each network or studio has made.
 * They are not the networks' real feeds, and the guide says so.
 */
final class Channels {
    static final int NETWORKS = 0, STUDIOS = 1, GENRES = 2, MINE = 3;

    /** One channel in the guide. */
    static final class Channel {
        final int number;
        final String name;
        final int group;
        final Library.Company company; // for network and studio channels
        final String genre;            // for genre channels (a movie genre in the catalog)
        final String[] guideNames;     // how TVmaze spells this network, for tonight's real premieres
        List<TvGuide.Airing> aired = new ArrayList<>(); // today's real showings, in time order
        List<MediaItem> titles;        // null until its line-up has loaded
        String error;                  // why it didn't load, when it didn't
        private List<MediaItem> order; // titles, shuffled once for the loop
        private long loopMs;

        Channel(int number, String name, int group, Library.Company company, String genre, String... guideNames) {
            this.guideNames = guideNames;
            this.number = number;
            this.name = name;
            this.group = group;
            this.company = company;
            this.genre = genre;
        }

        /** A stable name for the shuffle and for remembering where the remote was. */
        String key() { return company != null ? company.id : "genre:" + genre; }

        void setTitles(List<MediaItem> list) {
            titles = list;
            error = null;
            order = new ArrayList<>(list);
            // Seeded by the channel alone: the same order on every device, every day.
            Collections.shuffle(order, new Random(key().hashCode() * 31L + 7));
            loopMs = 0;
            for (MediaItem m : order) loopMs += slotMs(m);
        }

        boolean ready() { return order != null && !order.isEmpty() && loopMs > 0; }
    }

    /** One showing: a title, and when it starts and ends on its channel. */
    static final class Slot {
        MediaItem item;
        long start, end;
        long origin;      // where the title really began, when an earlier real showing cut into its slot
        String episodeId; // set for a real showing, which names its episode
        boolean real;     // an actual premiere at its true time, not Phone TV's own scheduling
        long turn; // which time round the loop this is; picks the episode for a show
        int index;

        long length() { return end - start; }
        /** How far through this showing the channel is at {@code now}, in milliseconds. */
        long into(long now) { return Math.max(0, Math.min(now, end) - start); }
        int percent(long now) { return (int) Math.min(100, into(now) * 100 / Math.max(1, length())); }
    }

    private static final long MINUTE = 60_000L;
    /** A fixed moment every schedule counts from (1 Jan 2024, midnight UTC). */
    private static final long EPOCH = 1_704_067_200_000L;

    private Channels() {}

    /**
     * How long a title holds its slot: its running time when known, otherwise a typical
     * one (an episode about 45 minutes, a film about 1 h 50), rounded up to five minutes
     * so the guide reads like a guide.
     */
    static long slotMs(MediaItem m) {
        int minutes = m.minutes > 0 ? m.minutes : (m.isShow() ? 45 : 110);
        minutes = Math.max(20, Math.min(240, minutes));
        return ((minutes + 4) / 5) * 5 * MINUTE;
    }

    /** What's on at {@code now} and what follows: {@code count} slots in all. Empty until the channel is ready. */
    static List<Slot> lineup(Channel c, long now, int count) {
        List<Slot> out = new ArrayList<>();
        if (!c.ready()) return out;
        long at = now;
        for (int n = 0; n < count; n++) {
            Slot s = onAt(c, at);
            out.add(s);
            at = s.end;
        }
        return out;
    }

    /**
     * What the channel shows at one moment. A real premiere wins its time; around it runs
     * the channel's own loop, cut short where a premiere begins and picked up where one ends,
     * as a broadcaster would schedule around a fixed event.
     */
    private static Slot onAt(Channel c, long t) {
        long lastRealEnd = Long.MIN_VALUE, nextRealStart = Long.MAX_VALUE;
        for (TvGuide.Airing a : c.aired) {
            if (a.start <= t && t < a.end) {
                Slot s = new Slot();
                s.item = MediaItem.catalog(a.imdb, "series", a.title, "");
                s.start = s.origin = a.start;
                s.end = a.end;
                s.episodeId = a.episodeId;
                s.real = true;
                return s;
            }
            if (a.end <= t) lastRealEnd = Math.max(lastRealEnd, a.end);
            else nextRealStart = Math.min(nextRealStart, a.start);
        }
        Slot s = looped(c, t);
        s.origin = s.start;
        s.start = Math.max(s.start, lastRealEnd);
        s.end = Math.min(s.end, nextRealStart);
        return s;
    }

    /** The slot of the channel's own never-ending loop that contains {@code t}. */
    private static Slot looped(Channel c, long t) {
        long sinceEpoch = Math.max(0, t - EPOCH);
        long turn = sinceEpoch / c.loopMs, at = EPOCH + turn * c.loopMs;
        int i = 0;
        while (at + slotMs(c.order.get(i)) <= t) {
            at += slotMs(c.order.get(i));
            i++;
            if (i == c.order.size()) { i = 0; turn++; }
        }
        Slot s = new Slot();
        s.item = c.order.get(i);
        s.start = at;
        s.end = at + slotMs(s.item);
        s.turn = turn;
        s.index = i;
        return s;
    }

    /**
     * Which episode a show's slot plays: a different one each time round the loop, the
     * same for everyone. Specials are skipped. Null when the show has no regular episodes.
     */
    static CatalogRepository.Episode episodeFor(Slot s, List<CatalogRepository.Episode> episodes) {
        List<CatalogRepository.Episode> regular = new ArrayList<>();
        for (CatalogRepository.Episode e : episodes) if (e.season > 0) regular.add(e);
        if (regular.isEmpty()) return null;
        long pick = Math.abs((s.turn * 7919L + s.index * 104_729L + s.item.id.hashCode()) % regular.size());
        return regular.get((int) pick);
    }

    // ------------------------------------------------------------------ line-up

    private static Library.Company company(String id, String name, String kind) {
        Library.Company c = new Library.Company();
        c.id = id;
        c.name = name;
        c.kind = kind;
        return c;
    }

    /**
     * The guide: movie and series channels only. No news, sport or local stations: the point
     * is to tune in and find something on. Ids are Wikidata's, checked against each network's
     * own best-known title. The user's saved networks and studios follow as "My channels".
     */
    static List<Channel> guide(List<Library.Company> saved) {
        List<Channel> out = new ArrayList<>();
        // Wikidata id, name, then the names TVmaze's schedule uses for the same network.
        String[][] networks = {
                {"Q23633", "HBO", "HBO"}, {"Q249892", "Cinemax", "Cinemax"}, {"Q23589", "Showtime", "Showtime", "Paramount+ with Showtime"},
                {"Q2422698", "Starz", "Starz", "STARZ"}, {"Q294660", "AMC", "AMC", "AMC+"}, {"Q651228", "FX", "FX", "FXX"},
                {"Q907311", "Netflix", "Netflix"}, {"Q4740856", "Prime Video", "Prime Video"},
                {"Q62446736", "Apple TV", "Apple TV", "Apple TV+"}, {"Q1630304", "Hulu", "Hulu"}, {"Q54958752", "Disney+", "Disney+"},
                {"Q276542", "Syfy", "Syfy", "SYFY"}, {"Q131439", "Comedy Central", "Comedy Central"},
                {"Q379052", "Adult Swim", "Adult Swim"}, {"Q191472", "BBC One"}};
        String[][] studios = {
                {"Q16248298", "A24"}, {"Q127552", "Pixar"}, {"Q367466", "Marvel Studios"}, {"Q182950", "Studio Ghibli"},
                {"Q126399", "Warner Bros."}, {"Q159846", "Paramount"}, {"Q242446", "Lucasfilm"}};
        String[] genres = {"Action", "Comedy", "Horror", "Sci-Fi", "Thriller", "Romance", "Animation", "Western"};
        java.util.Set<String> listed = new java.util.HashSet<>();
        int n = 101;
        for (String[] row : networks) {
            out.add(new Channel(n++, row[1], NETWORKS, company(row[0], row[1], Studios.NETWORK), null, java.util.Arrays.copyOfRange(row, 2, row.length)));
            listed.add(row[0]);
        }
        n = 201;
        for (String[] row : studios) { out.add(new Channel(n++, row[1], STUDIOS, company(row[0], row[1], Studios.STUDIO), null)); listed.add(row[0]); }
        n = 301;
        for (String g : genres) out.add(new Channel(n++, g, GENRES, null, g));
        n = 401;
        for (Library.Company c : saved) if (listed.add(c.id)) out.add(new Channel(n++, c.name, MINE, c, null));
        return out;
    }
}
