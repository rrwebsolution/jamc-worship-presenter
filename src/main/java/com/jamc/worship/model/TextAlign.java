package com.jamc.worship.model;

/** Horizontal alignment of slide text. */
public enum TextAlign {
    LEFT("Left"), CENTER("Center"), RIGHT("Right");

    private final String label;

    TextAlign(String label) {
        this.label = label;
    }

    public static TextAlign parse(String value, TextAlign fallback) {
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
