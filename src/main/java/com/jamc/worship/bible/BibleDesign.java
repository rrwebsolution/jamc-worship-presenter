package com.jamc.worship.bible;

import com.jamc.worship.model.FontWeight;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.model.Typography;
import com.jamc.worship.render.Fonts;

/**
 * Ready-made looks for Bible verses (Settings → Bible Design). Picking one writes the Bible typography profile and
 * the reference options; everything stays editable afterwards in Bible Typography and Bible Presentation.
 * The background still comes from the Bible theme.
 */
public enum BibleDesign {

    MODERN("Modern", "Clean sans-serif, centered, reference on top"),
    CLASSIC("Classic", "Serif book style, calm and readable"),
    ELEGANT("Elegant", "Warm italic serif, reference below"),
    BOLD("Bold", "Heavy capitals with a strong outline"),
    MINIMAL("Minimal", "Light text, no shadow, small reference below"),
    LARGE_PRINT("Large Print", "Big, thick yellow text for the back rows"),
    LOWER_THIRD("Lower Third", "Left-aligned at the bottom, for livestream over a camera");

    private final String label;
    private final String description;

    BibleDesign(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** The verse text look. Fonts fall back to ones installed on Windows. */
    public Typography typography() {
        String sans = Fonts.preferred("Montserrat", "Segoe UI");
        String serif = Fonts.preferred("Georgia", "Cambria", "Times New Roman", "Segoe UI");
        return switch (this) {
            case MODERN -> new Typography(sans, FontWeight.SEMIBOLD, false, 48, TextTransform.ORIGINAL, "#FFFFFF",
                    TextAlign.CENTER, TextPosition.MIDDLE, 1.2, true, 0.4, true, "#000000", 2);
            case CLASSIC -> new Typography(serif, FontWeight.REGULAR, false, 50, TextTransform.ORIGINAL, "#FFFFFF",
                    TextAlign.CENTER, TextPosition.MIDDLE, 1.3, true, 0.5, false, "#000000", 2);
            case ELEGANT -> new Typography(serif, FontWeight.REGULAR, true, 48, TextTransform.ORIGINAL, "#F5E6C8",
                    TextAlign.CENTER, TextPosition.MIDDLE, 1.3, true, 0.6, false, "#000000", 2);
            case BOLD -> new Typography(Fonts.preferred("Montserrat", "Segoe UI Black", "Arial Black", "Segoe UI"),
                    FontWeight.BLACK, false, 54, TextTransform.UPPERCASE, "#FFFFFF",
                    TextAlign.CENTER, TextPosition.MIDDLE, 1.15, true, 0.5, true, "#000000", 3);
            case MINIMAL -> new Typography(sans, FontWeight.LIGHT, false, 46, TextTransform.ORIGINAL, "#FFFFFF",
                    TextAlign.CENTER, TextPosition.MIDDLE, 1.35, false, 0.4, false, "#000000", 2);
            case LARGE_PRINT -> new Typography(sans, FontWeight.BOLD, false, 64, TextTransform.ORIGINAL, "#FFE45C",
                    TextAlign.CENTER, TextPosition.MIDDLE, 1.15, true, 0.6, true, "#000000", 3);
            case LOWER_THIRD -> new Typography(sans, FontWeight.SEMIBOLD, false, 40, TextTransform.ORIGINAL, "#FFFFFF",
                    TextAlign.LEFT, TextPosition.BOTTOM, 1.15, true, 0.8, true, "#000000", 2);
        };
    }

    public BibleDisplay.ReferencePosition referencePosition() {
        return switch (this) {
            case ELEGANT, MINIMAL -> BibleDisplay.ReferencePosition.BOTTOM;
            case LOWER_THIRD -> BibleDisplay.ReferencePosition.TOP_JOINED;
            default -> BibleDisplay.ReferencePosition.TOP;
        };
    }

    public boolean uppercaseReference() {
        return this != MINIMAL && this != ELEGANT;
    }

    public boolean showVersion() {
        return this != MINIMAL;
    }

    /** The design these settings match, or null when they were changed by hand ("Custom"). */
    public static BibleDesign matching(Typography t, BibleDisplay.ReferencePosition ref, boolean upper, boolean version) {
        for (BibleDesign d : values()) {
            if (d.typography().equals(t) && d.referencePosition() == ref && d.uppercaseReference() == upper
                    && d.showVersion() == version) {
                return d;
            }
        }
        return null;
    }
}
