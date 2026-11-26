package com.zgenmedia.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An add-on's own description of a source, read for the few facts a viewer chooses by.
 *
 * Add-ons write for people who know the shorthand: "Torrentio\n4k DV", then a file name
 * and a line of symbols. This picks out the picture quality, the picture and sound
 * extras and the size, and separates the file's name from the rest, so the sources page
 * can put the answer to "which one?" in plain view. Nothing is thrown away: whatever
 * isn't understood is still shown, just smaller.
 */
final class SourceLabel {
    private static final Pattern SIZE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(gb|gib|mb|mib)\\b");
    private static final Pattern SIZE_ONLY = Pattern.compile("\\d+(?:[.,]\\d+)?\\s*(gb|gib|mb|mib)");
    private static final Pattern DOLBY_VISION = Pattern.compile("\\b(dv|dovi)\\b|dolby\\s?vision");
    private static final Pattern SURROUND = Pattern.compile("\\b(?:dd[p+]?|eac3|ac3|aac|dts|truehd)?\\s?([57])[. ]1\\b");

    /** "4K", "1080p", "720p", "SD", or empty when the add-on doesn't say. */
    final String quality;
    /** "Dolby Vision", "HDR", "Atmos", "5.1 sound": at most one of each kind. */
    final List<String> extras = new ArrayList<>();
    /** The file or release name. */
    final String name;
    /** Whatever else the add-on wrote, on one line. */
    final String more;
    final long size;

    private SourceLabel(String quality, String name, String more, long size) {
        this.quality = quality;
        this.name = name;
        this.more = more;
        this.size = size;
    }

    static SourceLabel of(Addons.Stream s) {
        String file = s.filename == null ? "" : s.filename.trim();
        String all = (s.name + " " + s.title + " " + file).toLowerCase(Locale.ROOT);

        int lines = VideoSupport.quality(all);
        String quality = lines >= 2160 ? "4K" : lines == 1080 ? "1080p" : lines == 720 ? "720p" : lines == 480 ? "SD" : "";

        // The first line of the add-on's text is nearly always the file; the rest is its own notes.
        String[] rows = s.title.split("\\n");
        String name = file;
        StringBuilder more = new StringBuilder();
        for (String row : rows) {
            String r = row.trim().replaceAll("\\s+", " ");
            if (r.isEmpty()) continue;
            if (name.isEmpty()) { name = r; continue; }
            if (r.equals(name) || SIZE_ONLY.matcher(r.toLowerCase(Locale.ROOT)).matches()) continue; // the size has its own place
            if (more.length() > 0) more.append("  ·  ");
            more.append(r);
        }

        long size = s.size;
        if (size <= 0) {
            Matcher m = SIZE.matcher(all);
            if (m.find()) {
                double n = Double.parseDouble(m.group(1).replace(',', '.'));
                size = (long) (n * (m.group(2).startsWith("g") ? 1_073_741_824L : 1_048_576L));
            }
        }

        SourceLabel label = new SourceLabel(quality, name, more.toString(), size);
        if (DOLBY_VISION.matcher(all).find()) label.extras.add("Dolby Vision");
        if (all.contains("hdr")) label.extras.add("HDR");
        if (all.contains("atmos")) label.extras.add("Atmos");
        else {
            Matcher m = SURROUND.matcher(all);
            if (m.find()) label.extras.add(m.group(1) + ".1 sound");
        }
        return label;
    }
}
