package com.jamc.worship.model;

/** Where a library item's media comes from. */
public enum MediaSourceType {
    /** A file on this computer (works offline). */
    LOCAL,
    /** A YouTube video referenced by id, played by the embedded YouTube player (needs internet). */
    YOUTUBE;

    public static MediaSourceType parse(String v) {
        try {
            return v == null ? LOCAL : valueOf(v);
        } catch (IllegalArgumentException e) {
            return LOCAL;
        }
    }
}
