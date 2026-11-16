package com.jamc.worship.config;

import java.util.Locale;

/**
 * Where the lyrics sit in a Custom layout: one box on the 16:9 picture (fractions of its width and height), how the
 * words sit inside it and what is drawn behind them. The font, colors, padding and maximum lines still come from
 * Settings → Lyrics Appearance, like the other bar layouts.
 */
public record LyricsBox(float x, float y, float w, float h, VAlign valign, Background background) {

    /** Where the lines sit in the box from top to bottom. */
    public enum VAlign {
        TOP("Top"), MIDDLE("Middle"), BOTTOM("Bottom");

        private final String label;

        VAlign(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What is drawn behind the words (color, opacity and corners from Lyrics Appearance). */
    public enum Background {
        BARS("A bar per line"), BOX("One box"), NONE("No background");

        private final String label;

        Background(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Smallest box side: 5 % of the picture. */
    public static final float MIN = 0.05f;

    public LyricsBox {
        w = Math.clamp(w, MIN, 1f);
        h = Math.clamp(h, MIN, 1f);
        x = Math.clamp(x, 0f, 1f - w);
        y = Math.clamp(y, 0f, 1f - h);
        valign = valign == null ? VAlign.BOTTOM : valign;
        background = background == null ? Background.BARS : background;
    }

    /** A new layout starts as a box across the lower part of the picture. */
    public static LyricsBox starter() {
        return new LyricsBox(0.1f, 0.62f, 0.8f, 0.3f, VAlign.BOTTOM, Background.BARS);
    }

    public LyricsBox at(float nx, float ny, float nw, float nh) {
        return new LyricsBox(nx, ny, nw, nh, valign, background);
    }

    public LyricsBox with(VAlign v, Background b) {
        return new LyricsBox(x, y, w, h, v, b);
    }

    /** "x,y,w,h,VALIGN,BACKGROUND" in ten-thousandths, for settings.properties. */
    public String format() {
        return String.format(Locale.ROOT, "%d,%d,%d,%d,%s,%s", Math.round(x * 10000), Math.round(y * 10000),
                Math.round(w * 10000), Math.round(h * 10000), valign.name(), background.name());
    }

    /** The box written by {@link #format()}, or null when it is missing or damaged. */
    public static LyricsBox parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] p = s.split(",");
        if (p.length != 6) {
            return null;
        }
        try {
            return new LyricsBox(Integer.parseInt(p[0].strip()) / 10000f, Integer.parseInt(p[1].strip()) / 10000f,
                    Integer.parseInt(p[2].strip()) / 10000f, Integer.parseInt(p[3].strip()) / 10000f,
                    VAlign.valueOf(p[4].strip()), Background.valueOf(p[5].strip()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
