package com.jamc.worship.model;

/**
 * How content treats the operator's global video background. Never changed automatically: the
 * default lets the global background run behind everything until the operator turns it off.
 */
public enum BackgroundMode {
    /** Show the global background (when one is on); otherwise the content's own background. */
    USE_GLOBAL("Use global background"),
    /** Always show this content's own background; the global one keeps running underneath. */
    OVERRIDE("Override global background"),
    /** No background at all (text on black); the global one keeps running underneath. */
    HIDE("Hide background");

    private final String label;

    BackgroundMode(String label) {
        this.label = label;
    }

    public static BackgroundMode parse(String v) {
        try {
            return v == null ? USE_GLOBAL : valueOf(v);
        } catch (IllegalArgumentException e) {
            return USE_GLOBAL;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
