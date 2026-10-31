package com.jamc.worship.model;

/** Vertical placement of the text block on a slide. */
public enum TextPosition {
    TOP("Top"), MIDDLE("Middle"), BOTTOM("Bottom");

    private final String label;

    TextPosition(String label) {
        this.label = label;
    }

    public static TextPosition parse(String value, TextPosition fallback) {
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
