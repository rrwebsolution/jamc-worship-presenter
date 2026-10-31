package com.jamc.worship.model;

/** Where a media item is filed in the library. Backgrounds may be images or videos. */
public enum MediaCategory {
    IMAGE("Images"), VIDEO("Videos"), AUDIO("Audio"), BACKGROUND("Backgrounds");

    private final String label;

    MediaCategory(String label) {
        this.label = label;
    }

    public static MediaCategory forKind(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> IMAGE;
            case VIDEO -> VIDEO;
            case AUDIO -> AUDIO;
        };
    }

    public static MediaCategory parse(String v, MediaCategory fallback) {
        try {
            return v == null ? fallback : valueOf(v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
