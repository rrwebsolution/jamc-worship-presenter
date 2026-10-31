package com.jamc.worship.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * When each lyrics card starts on a reference timeline (stored as JSON in {@code songs.sync_json}).
 * Optional: a song without sync is presented normally, card by card. YouTube is only one possible
 * reference; local audio, local video, a timing file or a manual timeline work the same way.
 *
 * <p>Cards are keyed {@code <section key>/<card index>} ({@link Slide#getCardKey()}), e.g. "Verse 1/0",
 * "Verse 1/1", "Chorus/0". Older data keyed whole sections ({@link #sections}); those count as the
 * section's first card.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LyricsSync {

    /** What the times were taken from. */
    public enum Type {
        YOUTUBE("YouTube"), LOCAL_AUDIO("Local audio"), LOCAL_VIDEO("Local video"), FILE("Timing file"),
        PROVIDER("Lyrics provider"), MANUAL("Manual timeline");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Overall state shown in the library ("✓ Synced"). */
    public enum Status {
        NOT_SYNCED("Not Synced"), PARTIAL("Partially Synced"), SYNCED("✓ Synced");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public Type type;
    /** YOUTUBE: the video id; LOCAL_AUDIO / LOCAL_VIDEO: the file; FILE / PROVIDER: where the times came from. */
    public String reference;
    /** YOUTUBE (older data): the video the times belong to. */
    public String videoId;
    /** Card key → start time in ms. */
    public Map<String, Long> cards = new LinkedHashMap<>();
    /** Older data: section label → start time of its first card. */
    public Map<String, Long> sections = new LinkedHashMap<>();
    /** How many cards the song had when the times were saved (the library list shows status without loading lyrics). */
    public Integer total;

    /** Status from the saved counts (for list rows, where the lyrics are not loaded). */
    @JsonIgnore
    public Status summaryStatus() {
        int timed = (cards == null ? 0 : cards.size()) + (cards == null || cards.isEmpty() ? (sections == null ? 0 : sections.size()) : 0);
        if (timed == 0) {
            return Status.NOT_SYNCED;
        }
        return total != null && timed < total ? Status.PARTIAL : Status.SYNCED;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return (cards == null || cards.isEmpty()) && (sections == null || sections.isEmpty());
    }

    /** The card's start time, or null. */
    public Long startOf(String cardKey) {
        if (cardKey == null) {
            return null;
        }
        Long t = cards == null ? null : cards.get(cardKey);
        if (t == null && sections != null && cardKey.endsWith("/0")) {
            t = sections.get(cardKey.substring(0, cardKey.length() - 2));
        }
        return t;
    }

    /** The YouTube video for YOUTUBE syncs (new or older data). */
    @JsonIgnore
    public String youtubeVideoId() {
        return type == Type.YOUTUBE && reference != null ? reference : videoId;
    }

    /** NOT_SYNCED, PARTIAL or SYNCED for these card keys (each distinct card once). */
    public static Status status(LyricsSync sync, List<String> cardKeys) {
        if (sync == null || sync.isEmpty() || cardKeys.isEmpty()) {
            return Status.NOT_SYNCED;
        }
        long timed = cardKeys.stream().distinct().filter(k -> sync.startOf(k) != null).count();
        if (timed == 0) {
            return Status.NOT_SYNCED;
        }
        return timed >= cardKeys.stream().distinct().count() ? Status.SYNCED : Status.PARTIAL;
    }

    public LyricsSync copy() {
        LyricsSync s = new LyricsSync();
        s.type = type;
        s.reference = reference;
        s.videoId = videoId;
        s.cards = cards == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cards);
        s.sections = sections == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sections);
        s.total = total;
        return s;
    }
}
