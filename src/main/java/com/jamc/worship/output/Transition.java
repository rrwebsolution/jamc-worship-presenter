package com.jamc.worship.output;

/** How Program changes from one frame to the next. */
public enum Transition {
    /** Instant switch. */
    CUT("Cut"),
    /** Old frame fades to black, then the new frame fades in. */
    FADE("Fade"),
    /** Old frame dissolves directly into the new one. */
    CROSSFADE("Crossfade");

    private final String label;

    Transition(String label) {
        this.label = label;
    }

    public static Transition parse(String value, Transition fallback) {
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
