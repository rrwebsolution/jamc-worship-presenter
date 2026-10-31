package com.jamc.worship.model;

/**
 * Fully resolved look of a slide. Sizes are in pixels of the 1920x1080 reference frame and
 * are scaled to whatever surface the slide is drawn on.
 *
 * <p>Immutable: themes produce one; the global typography profiles, songs and per-slide
 * overrides derive new ones (see {@link Typography#applyTo(SlideStyle)} and
 * {@link StyleOverrides#applyTo(SlideStyle)}).
 *
 * @param backgroundValue a {@code #RRGGBB} color for {@link BackgroundType#COLOR}, or an image path
 *                        (absolute, or relative to the application data directory) for {@link BackgroundType#IMAGE}
 * @param backgroundColor color shown behind images and whenever an image is missing
 * @param backgroundValue for {@link BackgroundType#GRADIENT}: {@code "#RRGGBB,#RRGGBB,angle"} (see {@link Gradient})
 * @param overlayOpacity  0..1 black overlay drawn over the background to keep text readable
 * @param backgroundFit   how an image or video background fills the slide: FILL crops, FIT letterboxes on the background color
 * @param backgroundMode  whether this content shows the operator's global video background or its own
 * @param textTransform   letter case applied while drawing; the stored text keeps its own capitalization
 * @param shadowStrength  0..1 darkness of the text shadow
 */
public record SlideStyle(
        BackgroundType backgroundType,
        String backgroundValue,
        String backgroundColor,
        double overlayOpacity,
        String fontFamily,
        int fontSize,
        FontWeight fontWeight,
        boolean italic,
        String fontColor,
        TextAlign align,
        TextPosition position,
        double lineSpacing,
        boolean shadow,
        boolean outline,
        String outlineColor,
        int outlineWidth,
        FitMode backgroundFit,
        BackgroundMode backgroundMode,
        TextTransform textTransform,
        double shadowStrength) {

    public static final int MIN_FONT_SIZE = 12;
    public static final int MAX_FONT_SIZE = 300;
    /** Shadow strength of the classic look (themes made before shadow strength was adjustable). */
    public static final double DEFAULT_SHADOW_STRENGTH = 0.55;

    public SlideStyle {
        backgroundType = backgroundType == null ? BackgroundType.COLOR : backgroundType;
        backgroundColor = backgroundColor == null ? "#000000" : backgroundColor;
        overlayOpacity = Math.clamp(overlayOpacity, 0.0, 0.95);
        fontFamily = fontFamily == null || fontFamily.isBlank() ? "Segoe UI" : fontFamily;
        fontSize = Math.clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
        fontWeight = fontWeight == null ? FontWeight.BOLD : fontWeight;
        fontColor = fontColor == null ? "#FFFFFF" : fontColor;
        align = align == null ? TextAlign.CENTER : align;
        position = position == null ? TextPosition.MIDDLE : position;
        lineSpacing = Math.clamp(lineSpacing <= 0 ? 1.0 : lineSpacing, 0.7, 2.5);
        outlineColor = outlineColor == null ? "#000000" : outlineColor;
        outlineWidth = Math.clamp(outlineWidth, 1, 20);
        backgroundFit = backgroundFit == null ? FitMode.FILL : backgroundFit;
        backgroundMode = backgroundMode == null ? BackgroundMode.USE_GLOBAL : backgroundMode;
        textTransform = textTransform == null ? TextTransform.ORIGINAL : textTransform;
        shadowStrength = Double.isNaN(shadowStrength) ? DEFAULT_SHADOW_STRENGTH : Math.clamp(shadowStrength, 0.0, 1.0);
    }

    /** Classic form: bold or regular, original case, default shadow. */
    public SlideStyle(BackgroundType backgroundType, String backgroundValue, String backgroundColor, double overlayOpacity,
                      String fontFamily, int fontSize, boolean bold, boolean italic, String fontColor, TextAlign align,
                      TextPosition position, double lineSpacing, boolean shadow, boolean outline, String outlineColor,
                      int outlineWidth, FitMode backgroundFit, BackgroundMode backgroundMode) {
        this(backgroundType, backgroundValue, backgroundColor, overlayOpacity, fontFamily, fontSize, FontWeight.of(bold),
                italic, fontColor, align, position, lineSpacing, shadow, outline, outlineColor, outlineWidth, backgroundFit,
                backgroundMode, TextTransform.ORIGINAL, DEFAULT_SHADOW_STRENGTH);
    }

    /** Same as the classic form with the background filling the slide ({@link FitMode#FILL}). */
    public SlideStyle(BackgroundType backgroundType, String backgroundValue, String backgroundColor, double overlayOpacity,
                      String fontFamily, int fontSize, boolean bold, boolean italic, String fontColor, TextAlign align,
                      TextPosition position, double lineSpacing, boolean shadow, boolean outline, String outlineColor,
                      int outlineWidth) {
        this(backgroundType, backgroundValue, backgroundColor, overlayOpacity, fontFamily, fontSize, bold, italic, fontColor,
                align, position, lineSpacing, shadow, outline, outlineColor, outlineWidth, FitMode.FILL);
    }

    /** Same as the classic form with {@link BackgroundMode#USE_GLOBAL}. */
    public SlideStyle(BackgroundType backgroundType, String backgroundValue, String backgroundColor, double overlayOpacity,
                      String fontFamily, int fontSize, boolean bold, boolean italic, String fontColor, TextAlign align,
                      TextPosition position, double lineSpacing, boolean shadow, boolean outline, String outlineColor,
                      int outlineWidth, FitMode backgroundFit) {
        this(backgroundType, backgroundValue, backgroundColor, overlayOpacity, fontFamily, fontSize, bold, italic, fontColor,
                align, position, lineSpacing, shadow, outline, outlineColor, outlineWidth, backgroundFit, BackgroundMode.USE_GLOBAL);
    }

    /** True when the text is drawn bold or heavier (SemiBold falls back to bold where the font has no SemiBold face). */
    public boolean bold() {
        return fontWeight.isBold();
    }

    public SlideStyle withBackgroundFit(FitMode fit) {
        return new SlideStyle(backgroundType, backgroundValue, backgroundColor, overlayOpacity, fontFamily, fontSize, fontWeight,
                italic, fontColor, align, position, lineSpacing, shadow, outline, outlineColor, outlineWidth, fit, backgroundMode,
                textTransform, shadowStrength);
    }

    public SlideStyle withBackgroundMode(BackgroundMode mode) {
        return new SlideStyle(backgroundType, backgroundValue, backgroundColor, overlayOpacity, fontFamily, fontSize, fontWeight,
                italic, fontColor, align, position, lineSpacing, shadow, outline, outlineColor, outlineWidth, backgroundFit, mode,
                textTransform, shadowStrength);
    }

    public SlideStyle withTextTransform(TextTransform transform) {
        return new SlideStyle(backgroundType, backgroundValue, backgroundColor, overlayOpacity, fontFamily, fontSize, fontWeight,
                italic, fontColor, align, position, lineSpacing, shadow, outline, outlineColor, outlineWidth, backgroundFit,
                backgroundMode, transform, shadowStrength);
    }

    /** The look used when no theme can be found: white bold text on black. */
    public static SlideStyle fallback() {
        return new SlideStyle(BackgroundType.COLOR, "#000000", "#000000", 0, "Segoe UI", 72, true, false,
                "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, true, false, "#000000", 4);
    }

    /** Effective background color: the chosen color for color backgrounds, else the fallback color. */
    public String effectiveBackgroundColor() {
        return backgroundType == BackgroundType.COLOR && backgroundValue != null ? backgroundValue : backgroundColor;
    }
}
