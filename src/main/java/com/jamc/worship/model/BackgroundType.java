package com.jamc.worship.model;

/** What fills the slide behind the text. */
public enum BackgroundType {
    COLOR("Solid color"), GRADIENT("Gradient"), IMAGE("Image"), VIDEO("Looping video");

    /** True for backgrounds that come from a file (image or video). */
    public boolean usesFile() {
        return this == IMAGE || this == VIDEO;
    }

    private final String label;

    BackgroundType(String label) {
        this.label = label;
    }

    public static BackgroundType parse(String value, BackgroundType fallback) {
        try {
            return value == null ? fallback : valueOf(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
