package com.jamc.worship.lyrics;

/**
 * A song found by a {@link LyricsProvider}. Search results may come without lyrics; {@link LyricsProvider#fetch}
 * fills them in.
 *
 * @param reference     the provider's own id or file for this song (saved as the lyrics' source reference)
 * @param plainLyrics   plain text (may contain section headings), or null
 * @param timedLyrics   LRC / SRT / VTT text with timestamps, or null
 * @param existingSongId set when the song is already in the local library
 * @param score         how well title + artist match the search (0..1), for ranking
 */
public record LyricsResult(String providerId, String providerName, LyricsProvider.Trust trust, String reference,
                           String title, String artist, Long durationMs, String plainLyrics, String timedLyrics,
                           Long existingSongId, double score) {

    public boolean hasTimed() {
        return timedLyrics != null && !timedLyrics.isBlank();
    }

    public boolean hasLyrics() {
        return hasTimed() || (plainLyrics != null && !plainLyrics.isBlank());
    }

    public LyricsResult withLyrics(String plain, String timed) {
        return new LyricsResult(providerId, providerName, trust, reference, title, artist, durationMs, plain, timed,
                existingSongId, score);
    }

    public LyricsResult withScore(double s) {
        return new LyricsResult(providerId, providerName, trust, reference, title, artist, durationMs, plainLyrics,
                timedLyrics, existingSongId, s);
    }

    /** Same song from another provider (for merging results). */
    public String matchKey() {
        return norm(title) + "|" + norm(artist);
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("\\(.*?\\)|\\[.*?]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
