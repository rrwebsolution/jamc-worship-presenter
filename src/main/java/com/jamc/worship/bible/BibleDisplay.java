package com.jamc.worship.bible;

/**
 * How Bible slides show the reference and version. Example with the defaults:
 * <pre>
 *            JOHN 3:16
 *     [verse text]
 *               NIV
 * </pre>
 *
 * @param autoFit pack several short verses onto one slide and split a long verse across slides;
 *                when false, exactly one verse per slide
 */
public record BibleDisplay(ReferencePosition referencePosition, boolean showVersion, boolean uppercaseReference,
                           boolean autoFit) {

    /** Between the reference and the version when they are joined on one line. */
    public static final String JOIN = "   ·   ";

    /**
     * [heading above the verse, caption below it] for a reference and an optional version (null = not shown).
     * Example: TOP_JOINED gives ["JOHN 3:16 · NIV", null]; BOTTOM gives [null, "JOHN 3:16 · NIV"].
     */
    public static String[] headingAndCaption(ReferencePosition position, String reference, String version) {
        String joined = version == null ? reference : reference + JOIN + version;
        return switch (position) {
            case TOP -> new String[]{reference, version};
            case TOP_JOINED -> new String[]{joined, null};
            case BOTTOM -> new String[]{null, joined};
            case HIDDEN -> new String[]{null, version};
        };
    }

    public enum ReferencePosition {
        TOP("Reference above, version below"),
        TOP_JOINED("Reference + version together above"),
        BOTTOM("Reference + version together below"),
        HIDDEN("Hidden (version only, below)");

        private final String label;

        ReferencePosition(String label) {
            this.label = label;
        }

        public static ReferencePosition parse(String v) {
            try {
                return v == null ? TOP : valueOf(v);
            } catch (IllegalArgumentException e) {
                return TOP;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static BibleDisplay defaults() {
        return new BibleDisplay(ReferencePosition.TOP, true, true, true);
    }
}
