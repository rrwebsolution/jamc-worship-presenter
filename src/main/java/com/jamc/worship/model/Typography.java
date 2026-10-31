package com.jamc.worship.model;

/**
 * The text part of a look (font, case, color, placement, shadow and outline) without the
 * background. Global typography profiles ({@link ContentKind}) are stored as one of these and
 * laid over a theme with {@link #applyTo(SlideStyle)}, which keeps the theme's background.
 */
public record Typography(
        String fontFamily,
        FontWeight fontWeight,
        boolean italic,
        int fontSize,
        TextTransform textTransform,
        String fontColor,
        TextAlign align,
        TextPosition position,
        double lineSpacing,
        boolean shadow,
        double shadowStrength,
        boolean outline,
        String outlineColor,
        int outlineWidth) {

    public Typography {
        fontFamily = fontFamily == null || fontFamily.isBlank() ? "Segoe UI" : fontFamily.strip();
        fontWeight = fontWeight == null ? FontWeight.REGULAR : fontWeight;
        fontSize = Math.clamp(fontSize, SlideStyle.MIN_FONT_SIZE, SlideStyle.MAX_FONT_SIZE);
        textTransform = textTransform == null ? TextTransform.ORIGINAL : textTransform;
        fontColor = fontColor == null || fontColor.isBlank() ? "#FFFFFF" : fontColor;
        align = align == null ? TextAlign.CENTER : align;
        position = position == null ? TextPosition.MIDDLE : position;
        lineSpacing = Math.clamp(lineSpacing <= 0 ? 1.0 : lineSpacing, 0.7, 2.5);
        shadowStrength = Double.isNaN(shadowStrength) ? SlideStyle.DEFAULT_SHADOW_STRENGTH : Math.clamp(shadowStrength, 0.0, 1.0);
        outlineColor = outlineColor == null || outlineColor.isBlank() ? "#000000" : outlineColor;
        outlineWidth = Math.clamp(outlineWidth, 1, 20);
    }

    public Typography withFontWeight(FontWeight weight) {
        return new Typography(fontFamily, weight, italic, fontSize, textTransform, fontColor, align, position, lineSpacing,
                shadow, shadowStrength, outline, outlineColor, outlineWidth);
    }

    public Typography withFontSize(int size) {
        return new Typography(fontFamily, fontWeight, italic, size, textTransform, fontColor, align, position, lineSpacing,
                shadow, shadowStrength, outline, outlineColor, outlineWidth);
    }

    public Typography withAlign(TextAlign textAlign) {
        return new Typography(fontFamily, fontWeight, italic, fontSize, textTransform, fontColor, textAlign, position, lineSpacing,
                shadow, shadowStrength, outline, outlineColor, outlineWidth);
    }

    /** The theme's background with this typography. */
    public SlideStyle applyTo(SlideStyle base) {
        return new SlideStyle(base.backgroundType(), base.backgroundValue(), base.backgroundColor(), base.overlayOpacity(),
                fontFamily, fontSize, fontWeight, italic, fontColor, align, position, lineSpacing, shadow, outline,
                outlineColor, outlineWidth, base.backgroundFit(), base.backgroundMode(), textTransform, shadowStrength);
    }

    /** The typography part of a full style. */
    public static Typography of(SlideStyle s) {
        return new Typography(s.fontFamily(), s.fontWeight(), s.italic(), s.fontSize(), s.textTransform(), s.fontColor(),
                s.align(), s.position(), s.lineSpacing(), s.shadow(), s.shadowStrength(), s.outline(), s.outlineColor(),
                s.outlineWidth());
    }

    /**
     * First-run profile for a kind of content. Song lyrics: capitals, centered, white, shadow and
     * outline. Everything else keeps its original capitalization.
     *
     * @param family the preferred font when it is installed, else the caller's fallback
     */
    public static Typography defaults(ContentKind kind, String family) {
        return switch (kind) {
            case LYRICS -> new Typography(family, FontWeight.SEMIBOLD, false, 54, TextTransform.UPPERCASE, "#FFFFFF",
                    TextAlign.CENTER, TextPosition.MIDDLE, 1.2, true, 0.4, true, "#000000", 2);
            case BIBLE -> new Typography(family, FontWeight.SEMIBOLD, false, 48, TextTransform.ORIGINAL, "#FFFFFF",
                    TextAlign.CENTER, TextPosition.MIDDLE, 1.2, true, 0.4, true, "#000000", 2);
            case ANNOUNCEMENTS, CUSTOM_SLIDES -> new Typography(family, FontWeight.SEMIBOLD, false, 60, TextTransform.ORIGINAL,
                    "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.15, true, 0.4, false, "#000000", 2);
        };
    }

    public Typography withFontFamily(String v) {
        return new Typography(v, fontWeight, italic, fontSize, textTransform, fontColor, align, position, lineSpacing, shadow,
                shadowStrength, outline, outlineColor, outlineWidth);
    }

    public Typography withTextTransform(TextTransform v) {
        return new Typography(fontFamily, fontWeight, italic, fontSize, v, fontColor, align, position, lineSpacing, shadow,
                shadowStrength, outline, outlineColor, outlineWidth);
    }
}
