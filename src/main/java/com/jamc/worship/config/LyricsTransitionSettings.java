package com.jamc.worship.config;

import com.jamc.worship.model.TextAlign;

/**
 * How lyrics change from one card to the next (Settings → Lyrics Transition), saved in settings.properties.
 *
 * <ul>
 *   <li>{@link Mode#NONE}: lyrics use Program's own transition (the one next to TAKE), as before.</li>
 *   <li>{@link Mode#SMOOTH_FADE}: lyrics always crossfade, with this duration and easing.</li>
 *   <li>{@link Mode#SPOTIFY}: the previous card sits dimmed above the current one and the next card dimmed below;
 *       NEXT scrolls the stack up, PREVIOUS scrolls it down.</li>
 * </ul>
 * A copy travels with each lyrics slide sent to Preview or Program, so changing these never changes what is live
 * until the next slide is shown.
 */
public record LyricsTransitionSettings(Mode mode, int durationMs, int fontSize, float dimOpacity, int spacing,
                                       String highlightColor, TextAlign align, boolean showPrevious, boolean showNext,
                                       Easing easing) {

    public static final String KEY_MODE = "lyrics.transition.mode";
    public static final String KEY_DURATION = "lyrics.transition.durationMs";
    public static final String KEY_FONT_SIZE = "lyrics.transition.fontSize";
    public static final String KEY_DIM_OPACITY = "lyrics.transition.dimOpacity";
    public static final String KEY_SPACING = "lyrics.transition.spacing";
    public static final String KEY_HIGHLIGHT = "lyrics.transition.highlightColor";
    public static final String KEY_ALIGN = "lyrics.transition.align";
    public static final String KEY_SHOW_PREVIOUS = "lyrics.transition.showPrevious";
    public static final String KEY_SHOW_NEXT = "lyrics.transition.showNext";
    public static final String KEY_EASING = "lyrics.transition.easing";

    public static final int MIN_DURATION = 200;
    public static final int MAX_DURATION = 1500;
    public static final int MIN_FONT = 36;
    public static final int MAX_FONT = 160;
    public static final int MAX_SPACING = 240;

    public enum Mode {
        NONE("None (use the Program transition)"),
        SMOOTH_FADE("Smooth Fade"),
        SPOTIFY("Spotify-Style Lyrics");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Easing {
        EASE_IN_OUT("Ease in-out"),
        EASE_OUT("Ease out"),
        EASE_IN("Ease in"),
        LINEAR("Linear");

        private final String label;

        Easing(String label) {
            this.label = label;
        }

        /** Eased progress for linear progress {@code t} (0..1). */
        public float apply(float t) {
            t = Math.clamp(t, 0f, 1f);
            return switch (this) {
                case LINEAR -> t;
                case EASE_IN -> t * t * t;
                case EASE_OUT -> 1f - (1f - t) * (1f - t) * (1f - t);
                case EASE_IN_OUT -> t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public LyricsTransitionSettings {
        mode = mode == null ? Mode.NONE : mode;
        durationMs = Math.clamp(durationMs, MIN_DURATION, MAX_DURATION);
        fontSize = Math.clamp(fontSize, MIN_FONT, MAX_FONT);
        dimOpacity = Math.clamp(dimOpacity, 0f, 1f);
        spacing = Math.clamp(spacing, 0, MAX_SPACING);
        highlightColor = highlightColor == null || !highlightColor.matches("#[0-9A-Fa-f]{6}") ? "#FFFFFF" : highlightColor;
        align = align == null ? TextAlign.CENTER : align;
        easing = easing == null ? Easing.EASE_IN_OUT : easing;
    }

    public static LyricsTransitionSettings defaults() {
        return new LyricsTransitionSettings(Mode.NONE, 600, 84, 0.30f, 56, "#FFFFFF", TextAlign.CENTER, true, true,
                Easing.EASE_IN_OUT);
    }

    public LyricsTransitionSettings withMode(Mode m) {
        return new LyricsTransitionSettings(m, durationMs, fontSize, dimOpacity, spacing, highlightColor, align,
                showPrevious, showNext, easing);
    }

    public static LyricsTransitionSettings load(AppConfig cfg) {
        LyricsTransitionSettings d = defaults();
        return new LyricsTransitionSettings(
                parseEnum(Mode.class, cfg.get(KEY_MODE, null), d.mode()),
                parseInt(cfg.get(KEY_DURATION, null), d.durationMs()),
                parseInt(cfg.get(KEY_FONT_SIZE, null), d.fontSize()),
                parseInt(cfg.get(KEY_DIM_OPACITY, null), Math.round(d.dimOpacity() * 100)) / 100f,
                parseInt(cfg.get(KEY_SPACING, null), d.spacing()),
                cfg.get(KEY_HIGHLIGHT, d.highlightColor()),
                TextAlign.parse(cfg.get(KEY_ALIGN, null), d.align()),
                cfg.getBoolean(KEY_SHOW_PREVIOUS, d.showPrevious()),
                cfg.getBoolean(KEY_SHOW_NEXT, d.showNext()),
                parseEnum(Easing.class, cfg.get(KEY_EASING, null), d.easing()));
    }

    public void save(AppConfig cfg) {
        cfg.set(KEY_MODE, mode.name());
        cfg.set(KEY_DURATION, String.valueOf(durationMs));
        cfg.set(KEY_FONT_SIZE, String.valueOf(fontSize));
        cfg.set(KEY_DIM_OPACITY, String.valueOf(Math.round(dimOpacity * 100)));
        cfg.set(KEY_SPACING, String.valueOf(spacing));
        cfg.set(KEY_HIGHLIGHT, highlightColor);
        cfg.set(KEY_ALIGN, align.name());
        cfg.set(KEY_SHOW_PREVIOUS, String.valueOf(showPrevious));
        cfg.set(KEY_SHOW_NEXT, String.valueOf(showNext));
        cfg.set(KEY_EASING, easing.name());
        cfg.save();
    }

    private static int parseInt(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String v, E fallback) {
        try {
            return v == null ? fallback : Enum.valueOf(type, v.strip());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
