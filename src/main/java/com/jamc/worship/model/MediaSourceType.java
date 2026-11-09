package com.jamc.worship.model;

/** Where a library item's media comes from. */
public enum MediaSourceType {
    /** A file on this computer (works offline). */
    LOCAL,
    /** A YouTube video referenced by id, played by the embedded YouTube player (needs internet). */
    YOUTUBE,
    /**
     * A Pixabay video: the path is its Pixabay CDN address, streamed by VLC (never downloaded; needs internet). The
     * Pixabay id, creator and poster are kept in the YouTube id / channel / thumbnail columns.
     */
    PIXABAY;

    public static MediaSourceType parse(String v) {
        try {
            return v == null ? LOCAL : valueOf(v);
        } catch (IllegalArgumentException e) {
            return LOCAL;
        }
    }
}
