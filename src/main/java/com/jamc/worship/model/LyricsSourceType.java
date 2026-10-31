package com.jamc.worship.model;

/**
 * Where a song's lyrics came from. Only provenance: every source is saved and presented the same way,
 * as text lyrics through the Lyrics / Bible scene source.
 */
public enum LyricsSourceType {
    MANUAL("Created manually"),
    PASTED("Pasted"),
    FILE_IMPORT("Imported file"),
    LICENSED_API("Licensed lyrics provider"),
    AUTHORIZED_PROVIDER("Lyrics provider"),
    YOUTUBE_REFERENCE("Identified on YouTube");

    private final String label;

    LyricsSourceType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static LyricsSourceType parse(String v) {
        try {
            return v == null ? null : valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
