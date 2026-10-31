package com.jamc.worship.youtube;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Guesses a song's title and artist from a YouTube video title and channel, e.g.
 * "Goodness of God - Bethel Music (Official Lyric Video)" on "Bethel Music" → Goodness of God / Bethel Music.
 * Only a suggestion: the operator reviews both fields before saving.
 */
public final class SongTitleGuess {

    /** Bracketed extras that are not part of a song title. */
    private static final Pattern NOISE = Pattern.compile(
            "\\s*[(\\[][^)\\]]*\\b(official|lyric|lyrics|video|audio|live|visualizer|visualiser|hd|4k|performance|"
                    + "acoustic|version|remaster(ed)?|feat\\.?|ft\\.?|with|reprise|cover|karaoke|instrumental)\\b[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DASH = Pattern.compile("\\s+[-–—|]\\s+");

    public record Guess(String title, String artist) {
    }

    private SongTitleGuess() {
    }

    public static Guess from(String videoTitle, String channel) {
        String t = videoTitle == null ? "" : videoTitle.strip();
        String ch = cleanChannel(channel);
        t = NOISE.matcher(t).replaceAll("");
        t = t.replaceAll("(?i)\\s+(feat\\.?|ft\\.?)\\s+.*$", "").strip();
        String[] parts = DASH.split(t);
        String title;
        String artist;
        if (parts.length >= 2) {
            String a = parts[0].strip();
            String b = parts[1].strip();
            if (sameArtist(b, ch)) {
                title = a;
                artist = b;
            } else if (sameArtist(a, ch)) {
                title = b;
                artist = a;
            } else {
                // the usual music-video form is "Artist - Title"
                title = b;
                artist = a;
            }
        } else {
            title = t;
            artist = ch;
        }
        title = title.replaceAll("^[\"'“”]+|[\"'“”]+$", "").strip();
        return new Guess(title.isEmpty() ? (videoTitle == null ? "" : videoTitle.strip()) : title, artist == null ? "" : artist);
    }

    private static String cleanChannel(String channel) {
        if (channel == null) {
            return "";
        }
        return channel.replaceAll("(?i)\\s*-\\s*topic$", "").replaceAll("(?i)vevo$", "").replaceAll("(?i)\\s+official$", "").strip();
    }

    private static boolean sameArtist(String s, String channel) {
        if (channel.isEmpty()) {
            return false;
        }
        String a = norm(s);
        String b = norm(channel);
        return !a.isEmpty() && (a.equals(b) || a.contains(b) || b.contains(a));
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
