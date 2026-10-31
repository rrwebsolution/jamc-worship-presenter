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

    public enum ReferencePosition {
        TOP("Above the verse"), BOTTOM("Below the verse"), HIDDEN("Hidden");

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
