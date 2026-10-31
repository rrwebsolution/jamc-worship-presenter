package com.jamc.worship.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Partial style changes layered over a theme (per song or per slide). A {@code null} field
 * means "use the theme's value". Serialized to JSON in {@code slides.style_json}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StyleOverrides {

    public BackgroundType backgroundType;
    public String backgroundValue;
    public Double overlayOpacity;
    public FitMode backgroundFit;
    public BackgroundMode backgroundMode;
    public String fontFamily;
    public Integer fontSize;
    /** Legacy (before font weights): true = Bold, false = Regular. Used only when {@link #fontWeight} is null. */
    public Boolean bold;
    public FontWeight fontWeight;
    public TextTransform textTransform;
    public Double shadowStrength;
    public Boolean italic;
    public String fontColor;
    public TextAlign align;
    public TextPosition position;
    public Double lineSpacing;
    public Boolean shadow;
    public Boolean outline;
    public String outlineColor;
    public Integer outlineWidth;

    public SlideStyle applyTo(SlideStyle base) {
        return new SlideStyle(
                or(backgroundType, base.backgroundType()),
                or(backgroundValue, base.backgroundValue()),
                base.backgroundColor(),
                or(overlayOpacity, base.overlayOpacity()),
                or(fontFamily, base.fontFamily()),
                or(fontSize, base.fontSize()),
                fontWeight != null ? fontWeight : bold != null ? FontWeight.of(bold) : base.fontWeight(),
                or(italic, base.italic()),
                or(fontColor, base.fontColor()),
                or(align, base.align()),
                or(position, base.position()),
                or(lineSpacing, base.lineSpacing()),
                or(shadow, base.shadow()),
                or(outline, base.outline()),
                or(outlineColor, base.outlineColor()),
                or(outlineWidth, base.outlineWidth()),
                or(backgroundFit, base.backgroundFit()),
                or(backgroundMode, base.backgroundMode()),
                or(textTransform, base.textTransform()),
                or(shadowStrength, base.shadowStrength()));
    }

    @JsonIgnore
    public boolean isEmpty() {
        return backgroundType == null && backgroundValue == null && overlayOpacity == null && backgroundFit == null && backgroundMode == null && fontFamily == null
                && fontSize == null && bold == null && fontWeight == null && textTransform == null && shadowStrength == null && italic == null && fontColor == null && align == null
                && position == null && lineSpacing == null && shadow == null && outline == null
                && outlineColor == null && outlineWidth == null;
    }

    public StyleOverrides copy() {
        StyleOverrides o = new StyleOverrides();
        o.backgroundType = backgroundType;
        o.backgroundValue = backgroundValue;
        o.overlayOpacity = overlayOpacity;
        o.backgroundFit = backgroundFit;
        o.backgroundMode = backgroundMode;
        o.fontFamily = fontFamily;
        o.fontSize = fontSize;
        o.bold = bold;
        o.fontWeight = fontWeight;
        o.textTransform = textTransform;
        o.shadowStrength = shadowStrength;
        o.italic = italic;
        o.fontColor = fontColor;
        o.align = align;
        o.position = position;
        o.lineSpacing = lineSpacing;
        o.shadow = shadow;
        o.outline = outline;
        o.outlineColor = outlineColor;
        o.outlineWidth = outlineWidth;
        return o;
    }

    /**
     * The overrides that turn {@code base} into {@code edited}: only fields that differ are set,
     * so later changes to the theme still flow through to everything the user did not change.
     */
    public static StyleOverrides diff(SlideStyle base, SlideStyle edited) {
        StyleOverrides o = new StyleOverrides();
        if (base.backgroundType() != edited.backgroundType()
                || !java.util.Objects.equals(base.backgroundValue(), edited.backgroundValue())) {
            o.backgroundType = edited.backgroundType();
            o.backgroundValue = edited.backgroundValue();
        }
        if (base.overlayOpacity() != edited.overlayOpacity()) o.overlayOpacity = edited.overlayOpacity();
        if (base.backgroundFit() != edited.backgroundFit()) o.backgroundFit = edited.backgroundFit();
        if (base.backgroundMode() != edited.backgroundMode()) o.backgroundMode = edited.backgroundMode();
        if (!base.fontFamily().equals(edited.fontFamily())) o.fontFamily = edited.fontFamily();
        if (base.fontSize() != edited.fontSize()) o.fontSize = edited.fontSize();
        if (base.fontWeight() != edited.fontWeight()) o.fontWeight = edited.fontWeight();
        if (base.textTransform() != edited.textTransform()) o.textTransform = edited.textTransform();
        if (base.shadowStrength() != edited.shadowStrength()) o.shadowStrength = edited.shadowStrength();
        if (base.italic() != edited.italic()) o.italic = edited.italic();
        if (!base.fontColor().equalsIgnoreCase(edited.fontColor())) o.fontColor = edited.fontColor();
        if (base.align() != edited.align()) o.align = edited.align();
        if (base.position() != edited.position()) o.position = edited.position();
        if (base.lineSpacing() != edited.lineSpacing()) o.lineSpacing = edited.lineSpacing();
        if (base.shadow() != edited.shadow()) o.shadow = edited.shadow();
        if (base.outline() != edited.outline()) o.outline = edited.outline();
        if (!base.outlineColor().equalsIgnoreCase(edited.outlineColor())) o.outlineColor = edited.outlineColor();
        if (base.outlineWidth() != edited.outlineWidth()) o.outlineWidth = edited.outlineWidth();
        return o;
    }

    /**
     * Overrides that a song's own settings contribute over its theme and the global lyrics
     * typography: its quick typography (weight, case, alignment...), font, size and background.
     */
    public static StyleOverrides forSong(Song song) {
        StyleOverrides o = song.getTypography() == null ? new StyleOverrides() : song.getTypography().copy();
        o.fontFamily = song.getFontFamily() != null ? song.getFontFamily() : o.fontFamily;
        o.fontSize = song.getFontSize() != null ? song.getFontSize() : o.fontSize;
        if (song.getBackground() != null && !song.getBackground().isBlank()) {
            boolean video = MediaKind.of(java.nio.file.Path.of(song.getBackground())).orElse(MediaKind.VIDEO) == MediaKind.VIDEO;
            o.backgroundType = video ? BackgroundType.VIDEO : BackgroundType.IMAGE;
            o.backgroundValue = song.getBackground();
        }
        return o;
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
