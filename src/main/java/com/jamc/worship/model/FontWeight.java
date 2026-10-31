package com.jamc.worship.model;

/**
 * How heavy slide text is drawn. Fonts installed with separate weight families (for example
 * "Montserrat SemiBold" or "Segoe UI Semibold") are used when present; otherwise the nearest
 * of plain and bold is used (see {@code render.Fonts}).
 */
public enum FontWeight {
    LIGHT("Light", false, "Light"),
    REGULAR("Regular", false),
    MEDIUM("Medium", false, "Medium"),
    SEMIBOLD("SemiBold", true, "SemiBold", "Semibold", "Semi Bold", "DemiBold", "Demibold"),
    BOLD("Bold", true),
    BLACK("Black", true, "Black", "Heavy", "ExtraBold");

    private final String label;
    private final boolean bold;
    private final String[] faceSuffixes;

    FontWeight(String label, boolean bold, String... faceSuffixes) {
        this.label = label;
        this.bold = bold;
        this.faceSuffixes = faceSuffixes;
    }

    /** True when the plain/bold fallback for this weight is bold. */
    public boolean isBold() {
        return bold;
    }

    /** Family-name suffixes under which Windows registers this weight ("Montserrat SemiBold"). */
    public String[] faceSuffixes() {
        return faceSuffixes.clone();
    }

    public static FontWeight of(boolean bold) {
        return bold ? BOLD : REGULAR;
    }

    /** Parses a stored weight; the old NORMAL / BOLD values map to Regular / Bold. */
    public static FontWeight parse(String value, FontWeight fallback) {
        if (value == null) {
            return fallback;
        }
        if ("NORMAL".equalsIgnoreCase(value)) {
            return REGULAR;
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
