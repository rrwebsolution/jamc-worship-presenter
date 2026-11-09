package com.jamc.worship.config;

import com.jamc.worship.model.TextAlign;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How song lyrics look on the LIVE PROGRAM picture that goes out to the livestream, the recording, the OBS window and
 * the Program monitor: by default a broadcast lower third (white bold capitals, one translucent black bar per line,
 * over the camera or scene). Preview and the church projector keep the normal lyrics look (Lyrics Typography and
 * Lyrics Transition), so the room can read big full-screen words while the stream shows a clean lower third.
 *
 * <p>Sizes are in pixels of a 1920×1080 picture and scale with the output (720p is two thirds); widths and the bottom
 * margin are fractions of the picture. Two copies are kept in settings.properties: the DRAFT the operator edits and
 * saves, and the APPLIED one the outputs use. Only "Apply to Program" copies the draft to the outputs, so editing
 * never changes the live picture by surprise.
 */
public record ProgramLyricsStyle(Mode mode, String fontFamily, int fontSize, boolean bold, boolean uppercase,
                                 String textColor, TextAlign align, String barColor, float barOpacity,
                                 float offsetX, float bottomMargin, int lineSpacing, int padV, int padH, int maxLines,
                                 float minBarWidth, float maxBarWidth, int cornerRadius, boolean outline, boolean shadow,
                                 Fade fade, int fadeMs, boolean transparentOverlay) {

    public enum Mode {
        OFF("Full lyrics (normal)"),
        LOWER_THIRD("Lower Third"),
        CENTERED("Centered bars"),
        FULL_SCREEN("Full Screen (dimmed)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Fade {
        SMOOTH_FADE("Smooth Fade"),
        CUT("Cut");

        private final String label;

        Fade(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final String APPLIED = "program.lyrics.";
    static final String DRAFT = "program.lyrics.draft.";

    public ProgramLyricsStyle {
        mode = mode == null ? Mode.LOWER_THIRD : mode;
        fontFamily = fontFamily == null || fontFamily.isBlank() ? "Segoe UI" : fontFamily;
        fontSize = Math.clamp(fontSize, 16, 160);
        textColor = color(textColor, "#FFFFFF");
        align = align == null ? TextAlign.CENTER : align;
        barColor = color(barColor, "#000000");
        barOpacity = Math.clamp(barOpacity, 0f, 1f);
        offsetX = Math.clamp(offsetX, -0.4f, 0.4f);
        bottomMargin = Math.clamp(bottomMargin, 0f, 0.4f);
        lineSpacing = Math.clamp(lineSpacing, 0, 80);
        padV = Math.clamp(padV, 0, 60);
        padH = Math.clamp(padH, 0, 120);
        maxLines = Math.clamp(maxLines, 1, 6);
        minBarWidth = Math.clamp(minBarWidth, 0f, 0.94f);
        maxBarWidth = Math.clamp(maxBarWidth, Math.max(0.2f, minBarWidth), 0.94f);
        cornerRadius = Math.clamp(cornerRadius, 0, 40);
        fade = fade == null ? Fade.SMOOTH_FADE : fade;
        fadeMs = Math.clamp(fadeMs, 0, 2000);
    }

    /** The broadcast lower third of the reference picture: 42 px bold white capitals on 75 % black bars, 2 lines. */
    public static ProgramLyricsStyle defaults() {
        return new ProgramLyricsStyle(Mode.LOWER_THIRD, "Segoe UI", 42, true, true, "#FFFFFF", TextAlign.CENTER,
                "#000000", 0.75f, 0f, 0.08f, 8, 12, 24, 2, 0.66f, 0.9f, 0, false, false, Fade.SMOOTH_FADE, 400, true);
    }

    public boolean active() {
        return mode != Mode.OFF;
    }

    /** How long bar-style lyrics take to change (0 = cut). A scene may use bars even when the default is Off. */
    public int changeMs() {
        return fade == Fade.CUT ? 0 : fadeMs;
    }

    /** The same look with another layout (a scene's own Lyrics layout). */
    public ProgramLyricsStyle withMode(Mode m) {
        return new ProgramLyricsStyle(m, fontFamily, fontSize, bold, uppercase, textColor, align, barColor, barOpacity,
                offsetX, bottomMargin, lineSpacing, padV, padH, maxLines, minBarWidth, maxBarWidth, cornerRadius, outline,
                shadow, fade, fadeMs, transparentOverlay);
    }

    // ------------------------------------------------------------------ the applied style (what the outputs draw)

    private static volatile ProgramLyricsStyle applied = defaults();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** The style the Program outputs draw now. */
    public static ProgramLyricsStyle applied() {
        return applied;
    }

    /** Loads the applied style at startup (defaults when nothing was saved yet). */
    public static void install(AppConfig cfg) {
        applied = read(cfg, APPLIED, defaults());
        LISTENERS.forEach(Runnable::run);
    }

    /** Apply to Program: the outputs draw this from now on; it is saved as both the applied style and the draft. */
    public static void apply(AppConfig cfg, ProgramLyricsStyle s) {
        write(cfg, APPLIED, s);
        write(cfg, DRAFT, s);
        cfg.save();
        applied = s;
        LISTENERS.forEach(Runnable::run);
    }

    /** Told (on the caller's thread) when the applied style changes. */
    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    /** The draft being edited in Settings (the applied style when none was saved). */
    public static ProgramLyricsStyle draft(AppConfig cfg) {
        return read(cfg, DRAFT, read(cfg, APPLIED, defaults()));
    }

    /** Save Settings: keeps the draft; the outputs do not change until Apply to Program. */
    public static void saveDraft(AppConfig cfg, ProgramLyricsStyle s) {
        write(cfg, DRAFT, s);
        cfg.save();
    }

    /** Test hook. */
    static void setApplied(ProgramLyricsStyle s) {
        applied = s;
    }

    // ------------------------------------------------------------------ persistence

    static ProgramLyricsStyle read(AppConfig cfg, String p, ProgramLyricsStyle d) {
        if (cfg.get(p + "mode", null) == null) {
            return d;
        }
        return new ProgramLyricsStyle(
                parseEnum(Mode.class, cfg.get(p + "mode", null), d.mode()),
                cfg.get(p + "fontFamily", d.fontFamily()),
                i(cfg, p + "fontSize", d.fontSize()),
                cfg.getBoolean(p + "bold", d.bold()),
                cfg.getBoolean(p + "uppercase", d.uppercase()),
                cfg.get(p + "textColor", d.textColor()),
                TextAlign.parse(cfg.get(p + "align", null), d.align()),
                cfg.get(p + "barColor", d.barColor()),
                i(cfg, p + "barOpacity", Math.round(d.barOpacity() * 100)) / 100f,
                i(cfg, p + "offsetX", Math.round(d.offsetX() * 100)) / 100f,
                i(cfg, p + "bottomMargin", Math.round(d.bottomMargin() * 100)) / 100f,
                i(cfg, p + "lineSpacing", d.lineSpacing()),
                i(cfg, p + "padV", d.padV()),
                i(cfg, p + "padH", d.padH()),
                i(cfg, p + "maxLines", d.maxLines()),
                i(cfg, p + "minBarWidth", Math.round(d.minBarWidth() * 100)) / 100f,
                i(cfg, p + "maxBarWidth", Math.round(d.maxBarWidth() * 100)) / 100f,
                i(cfg, p + "cornerRadius", d.cornerRadius()),
                cfg.getBoolean(p + "outline", d.outline()),
                cfg.getBoolean(p + "shadow", d.shadow()),
                parseEnum(Fade.class, cfg.get(p + "fade", null), d.fade()),
                i(cfg, p + "fadeMs", d.fadeMs()),
                cfg.getBoolean(p + "transparentOverlay", d.transparentOverlay()));
    }

    static void write(AppConfig cfg, String p, ProgramLyricsStyle s) {
        cfg.set(p + "mode", s.mode().name());
        cfg.set(p + "fontFamily", s.fontFamily());
        cfg.set(p + "fontSize", String.valueOf(s.fontSize()));
        cfg.set(p + "bold", String.valueOf(s.bold()));
        cfg.set(p + "uppercase", String.valueOf(s.uppercase()));
        cfg.set(p + "textColor", s.textColor());
        cfg.set(p + "align", s.align().name());
        cfg.set(p + "barColor", s.barColor());
        cfg.set(p + "barOpacity", String.valueOf(Math.round(s.barOpacity() * 100)));
        cfg.set(p + "offsetX", String.valueOf(Math.round(s.offsetX() * 100)));
        cfg.set(p + "bottomMargin", String.valueOf(Math.round(s.bottomMargin() * 100)));
        cfg.set(p + "lineSpacing", String.valueOf(s.lineSpacing()));
        cfg.set(p + "padV", String.valueOf(s.padV()));
        cfg.set(p + "padH", String.valueOf(s.padH()));
        cfg.set(p + "maxLines", String.valueOf(s.maxLines()));
        cfg.set(p + "minBarWidth", String.valueOf(Math.round(s.minBarWidth() * 100)));
        cfg.set(p + "maxBarWidth", String.valueOf(Math.round(s.maxBarWidth() * 100)));
        cfg.set(p + "cornerRadius", String.valueOf(s.cornerRadius()));
        cfg.set(p + "outline", String.valueOf(s.outline()));
        cfg.set(p + "shadow", String.valueOf(s.shadow()));
        cfg.set(p + "fade", s.fade().name());
        cfg.set(p + "fadeMs", String.valueOf(s.fadeMs()));
        cfg.set(p + "transparentOverlay", String.valueOf(s.transparentOverlay()));
    }

    private static String color(String v, String d) {
        return v == null || !v.matches("#[0-9A-Fa-f]{6}") ? d : v.toUpperCase(java.util.Locale.ROOT);
    }

    private static int i(AppConfig cfg, String key, int d) {
        try {
            String v = cfg.get(key, null);
            return v == null ? d : Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String v, E d) {
        try {
            return v == null ? d : Enum.valueOf(type, v.strip());
        } catch (IllegalArgumentException e) {
            return d;
        }
    }
}
