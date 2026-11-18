package com.jamc.worship.model;

/** A name/title graphic, e.g. "Ptr. Carlo" over "Sunday Service". */
public class LowerThird {

    public enum Position {
        BOTTOM_LEFT("Bottom left"), BOTTOM_CENTER("Bottom center"), BOTTOM_RIGHT("Bottom right"),
        TOP_LEFT("Top left"), TOP_RIGHT("Top right");

        private final String label;

        Position(String label) {
            this.label = label;
        }

        public static Position parse(String v) {
            try {
                return v == null ? BOTTOM_LEFT : valueOf(v);
            } catch (IllegalArgumentException e) {
                return BOTTOM_LEFT;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Style {
        CLASSIC("Classic box"), BADGE("Name tag (3 lines)");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        public static Style parse(String v) {
            try {
                return v == null ? CLASSIC : valueOf(v);
            } catch (IllegalArgumentException e) {
                return CLASSIC;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** How the name and subtitle sit in the lower third. */
    public enum TextAlign {
        LEFT("Left"), CENTER("Center"), RIGHT("Right");

        private final String label;

        TextAlign(String label) {
            this.label = label;
        }

        /** null when not set (the design's usual alignment). */
        public static TextAlign parse(String v) {
            try {
                return v == null ? null : valueOf(v);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Animation {
        SLIDE("Slide in"), FADE("Fade"), NONE("None");

        private final String label;

        Animation(String label) {
            this.label = label;
        }

        public static Animation parse(String v) {
            try {
                return v == null ? SLIDE : valueOf(v);
            } catch (IllegalArgumentException e) {
                return SLIDE;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private Long id;
    private String name = "";
    private String title = "";
    private String subtitle;
    private String fontFamily = "Segoe UI";
    private int fontSize = 52;
    private String titleColor = "#FFFFFF";
    private String subtitleColor = "#CBD5E1";
    private String backgroundColor = "#0B1220";
    private String accentColor = "#1677FF";
    private String logoPath;
    private Position position = Position.BOTTOM_LEFT;
    private Animation animation = Animation.SLIDE;
    /** CLASSIC box, or BADGE: a 3-line name tag (label / name / role) on slanted bars (user 2026-11-17). */
    private Style style = Style.CLASSIC;
    /** BADGE: the small line above the name, e.g. "Preaching Today". */
    private String label;
    /** Distance from the top or bottom edge, in 1080p pixels (raise it above a title banner). */
    private int marginY = 90;
    /** Box width in 1080p pixels; 0 = as wide as the words (user 2026-11-17: the width must be adjustable). */
    private int width;
    /** Name and subtitle: LEFT, CENTER or RIGHT; null = the design's usual (classic left, name tag centered). */
    private TextAlign textAlign;

    public LowerThird copy() {
        LowerThird l = new LowerThird();
        l.id = id;
        l.name = name;
        l.title = title;
        l.subtitle = subtitle;
        l.fontFamily = fontFamily;
        l.fontSize = fontSize;
        l.titleColor = titleColor;
        l.subtitleColor = subtitleColor;
        l.backgroundColor = backgroundColor;
        l.accentColor = accentColor;
        l.logoPath = logoPath;
        l.position = position;
        l.animation = animation;
        l.style = style;
        l.label = label;
        l.marginY = marginY;
        l.width = width;
        l.textAlign = textAlign;
        return l;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title == null ? "" : title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public int getFontSize() { return fontSize; }
    public void setFontSize(int fontSize) { this.fontSize = Math.clamp(fontSize, 16, 160); }
    public String getTitleColor() { return titleColor; }
    public void setTitleColor(String titleColor) { this.titleColor = titleColor; }
    public String getSubtitleColor() { return subtitleColor; }
    public void setSubtitleColor(String subtitleColor) { this.subtitleColor = subtitleColor; }
    public String getBackgroundColor() { return backgroundColor; }
    public void setBackgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; }
    public String getAccentColor() { return accentColor; }
    public void setAccentColor(String accentColor) { this.accentColor = accentColor; }
    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }
    public Position getPosition() { return position; }
    public void setPosition(Position position) { this.position = position == null ? Position.BOTTOM_LEFT : position; }
    public Animation getAnimation() { return animation; }
    public void setAnimation(Animation animation) { this.animation = animation == null ? Animation.SLIDE : animation; }

    public Style getStyle() { return style; }
    public void setStyle(Style style) { this.style = style == null ? Style.CLASSIC : style; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public int getMarginY() { return marginY; }
    public void setMarginY(int marginY) { this.marginY = Math.clamp(marginY, 0, 900); }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = Math.clamp(width, 0, 1920); }
    public TextAlign getTextAlign() { return textAlign; }
    public void setTextAlign(TextAlign textAlign) { this.textAlign = textAlign; }

    @Override
    public String toString() {
        return name;
    }
}
