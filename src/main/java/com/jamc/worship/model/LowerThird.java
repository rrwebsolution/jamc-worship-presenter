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

    @Override
    public String toString() {
        return name;
    }
}
