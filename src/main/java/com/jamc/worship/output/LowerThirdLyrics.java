package com.jamc.worship.output;

import com.jamc.worship.config.LyricsBox;
import com.jamc.worship.config.ProgramLyricsStyle;
import com.jamc.worship.model.FontWeight;
import com.jamc.worship.render.Fonts;
import com.jamc.worship.util.Colors;

import java.awt.BasicStroke;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws lyrics as a broadcast lower third ({@link ProgramLyricsStyle}): each line on its own translucent bar, over
 * whatever is behind (camera, video, scene). Nothing else is painted, so the picture underneath stays as it is.
 *
 * <p>Sizes in the style are for a 1080-pixel-high picture and are scaled to {@code ph}. A card with more lines than
 * the style allows is first re-flowed into fewer, longer lines, then shrunk (down to 60 %); words are never cut off or
 * dropped: if it still does not fit, every line is shown.
 */
public final class LowerThirdLyrics {

    private static final FontRenderContext FRC = new FontRenderContext(new AffineTransform(), true, true);
    /** Bars never touch the picture's edges (title safe area). */
    private static final float SAFE = 0.03f;

    private LowerThirdLyrics() {
    }

    /** The lines and font size used for {@code text} in a {@code pw}×{@code ph} picture. */
    record Layout(List<String> lines, Font font) {
    }

    /** Paints {@code text} in the {@code pw}×{@code ph} picture at the origin of {@code g}. */
    public static void paint(Graphics2D g0, int pw, int ph, String text, ProgramLyricsStyle s) {
        if (text == null || text.isBlank() || pw <= 0 || ph <= 0) {
            return;
        }
        Layout layout = layout(text, s, pw, ph);
        if (layout.lines().isEmpty()) {
            return;
        }
        if (s.box() != null) {
            paintBox(g0, pw, ph, layout, s, s.box());
            return;
        }
        float k = ph / 1080f;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Font font = layout.font();
            var lm = font.getLineMetrics("Ag", FRC);
            float textH = lm.getAscent() + lm.getDescent();
            float padV = s.padV() * k;
            float padH = s.padH() * k;
            float barH = textH + padV * 2;
            float gap = s.lineSpacing() * k;
            int n = layout.lines().size();
            float total = n * barH + (n - 1) * gap;
            float top = switch (s.mode()) {
                case LOWER_THIRD -> ph - s.bottomMargin() * ph - total;
                default -> (ph - total) / 2f;
            };
            top = Math.max(ph * SAFE, Math.min(top, ph - ph * SAFE - total));
            Color bar = withAlpha(Colors.parse(s.barColor(), Color.BLACK), s.barOpacity());
            if (s.mode() == ProgramLyricsStyle.Mode.FULL_SCREEN && s.barOpacity() > 0) {
                g.setColor(bar); // full screen: one dim layer over the whole picture instead of bars
                g.fillRect(0, 0, pw, ph);
            }
            float cx = pw / 2f + s.offsetX() * pw;
            float y = top;
            for (String line : layout.lines()) {
                TextLayout tl = new TextLayout(line, font, FRC);
                float tw = (float) tl.getAdvance();
                float w = Math.clamp(tw + padH * 2, s.minBarWidth() * pw, Math.max(s.minBarWidth(), s.maxBarWidth()) * pw);
                w = Math.max(w, Math.min(tw + padH * 2, pw * (1 - SAFE * 2)));
                float x = Math.clamp(cx - w / 2f, pw * SAFE, Math.max(pw * SAFE, pw * (1 - SAFE) - w));
                if (s.mode() != ProgramLyricsStyle.Mode.FULL_SCREEN && s.barOpacity() > 0) {
                    g.setColor(bar);
                    float r = s.cornerRadius() * k;
                    g.fill(new RoundRectangle2D.Float(x, y, w, barH, r * 2, r * 2));
                }
                float tx = switch (s.align()) {
                    case LEFT -> x + padH;
                    case RIGHT -> x + w - padH - tw;
                    case CENTER -> x + (w - tw) / 2f;
                };
                float baseline = y + padV + lm.getAscent();
                Shape outline = tl.getOutline(AffineTransform.getTranslateInstance(tx, baseline));
                if (s.shadow()) {
                    g.setColor(new Color(0, 0, 0, 150));
                    g.fill(AffineTransform.getTranslateInstance(2.5 * k, 2.5 * k).createTransformedShape(outline));
                }
                if (s.outline()) {
                    g.setColor(Color.BLACK);
                    g.setStroke(new BasicStroke(Math.max(1f, 3f * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(outline);
                }
                g.setColor(Colors.parse(s.textColor(), Color.WHITE));
                g.fill(outline);
                y += barH + gap;
            }
        } finally {
            g.dispose();
        }
    }

    /** The picture area of a Custom layout's box. */
    static java.awt.geom.Rectangle2D.Float boxArea(LyricsBox b, int pw, int ph) {
        return new java.awt.geom.Rectangle2D.Float(b.x() * pw, b.y() * ph, b.w() * pw, b.h() * ph);
    }

    /** Custom layout: the lines inside the operator's box, with a bar per line, one box, or nothing behind them. */
    private static void paintBox(Graphics2D g0, int pw, int ph, Layout layout, ProgramLyricsStyle s, LyricsBox b) {
        float k = ph / 1080f;
        var area = boxArea(b, pw, ph);
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Font font = layout.font();
            var lm = font.getLineMetrics("Ag", FRC);
            float textH = lm.getAscent() + lm.getDescent();
            boolean bars = b.background() == LyricsBox.Background.BARS;
            float padV = s.padV() * k;
            float padH = s.padH() * k;
            float lineH = bars ? textH + padV * 2 : textH;
            float gap = s.lineSpacing() * k;
            int n = layout.lines().size();
            float total = n * lineH + (n - 1) * gap;
            float inner = bars ? 0 : padV; // one box / none: the padding is inside the box edge
            float top = switch (b.valign()) {
                case TOP -> area.y + inner;
                case MIDDLE -> area.y + (area.height - total) / 2f;
                case BOTTOM -> area.y + area.height - inner - total;
            };
            Color fill = withAlpha(Colors.parse(s.barColor(), Color.BLACK), s.barOpacity());
            float r = s.cornerRadius() * k;
            if (b.background() == LyricsBox.Background.BOX && s.barOpacity() > 0) {
                g.setColor(fill);
                g.fill(new RoundRectangle2D.Float(area.x, area.y, area.width, area.height, r * 2, r * 2));
            }
            float y = top;
            for (String line : layout.lines()) {
                TextLayout tl = new TextLayout(line, font, FRC);
                float tw = (float) tl.getAdvance();
                float tx;
                if (bars) {
                    float w = Math.min(area.width, tw + padH * 2);
                    float x = switch (s.align()) {
                        case LEFT -> area.x;
                        case RIGHT -> area.x + area.width - w;
                        case CENTER -> area.x + (area.width - w) / 2f;
                    };
                    if (s.barOpacity() > 0) {
                        g.setColor(fill);
                        g.fill(new RoundRectangle2D.Float(x, y, w, lineH, r * 2, r * 2));
                    }
                    tx = x + (w - tw) / 2f;
                } else {
                    tx = switch (s.align()) {
                        case LEFT -> area.x + padH;
                        case RIGHT -> area.x + area.width - padH - tw;
                        case CENTER -> area.x + (area.width - tw) / 2f;
                    };
                }
                float baseline = y + (bars ? padV : 0) + lm.getAscent();
                Shape outline = tl.getOutline(AffineTransform.getTranslateInstance(tx, baseline));
                if (s.shadow()) {
                    g.setColor(new Color(0, 0, 0, 150));
                    g.fill(AffineTransform.getTranslateInstance(2.5 * k, 2.5 * k).createTransformedShape(outline));
                }
                if (s.outline()) {
                    g.setColor(Color.BLACK);
                    g.setStroke(new BasicStroke(Math.max(1f, 3f * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(outline);
                }
                g.setColor(Colors.parse(s.textColor(), Color.WHITE));
                g.fill(outline);
                y += lineH + gap;
            }
        } finally {
            g.dispose();
        }
    }

    /** Spotify-style lower third: dim previous/current/next cards remain visible as a vertical lyrics reel. */
    public static void paint(Graphics2D g, int pw, int ph, String text, ProgramLyricsStyle s, LyricsContext context) {
        if (s.fade() != ProgramLyricsStyle.Fade.SPOTIFY || context == null) {
            paint(g, pw, ph, text, s);
            return;
        }
        paintSpotifyFrame(g, pw, ph, text, s, context, 0);
    }

    /** Paints a Spotify reel with steady guide cards and an independently moving current/incoming card. */
    public static void paintSpotifyFrame(Graphics2D g, int pw, int ph, String text, ProgramLyricsStyle s,
                                         LyricsContext context, int currentOffsetY) {
        var spotify = context.settings();
        int gap = Math.max(12, Math.round(spotify.spacing() * ph / 1080f));
        int currentY = spotify.showNext() ? -gap : 0;
        if (spotify.showPrevious()) {
            paintCard(g, pw, ph, context.card(context.index() - 1), s, currentY - gap, spotify.dimOpacity());
        }
        paintCard(g, pw, ph, text, s, currentY + currentOffsetY, 1f);
        if (spotify.showNext()) {
            paintCard(g, pw, ph, context.card(context.index() + 1), s, currentY + gap, spotify.dimOpacity());
        }
    }

    private static void paintCard(Graphics2D g0, int pw, int ph, String text, ProgramLyricsStyle s, int dy, float alpha) {
        if (text == null || text.isBlank()) {
            return;
        }
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.clipRect(0, 0, pw, ph);
            g.translate(0, dy);
            g.setComposite(AlphaComposite.SrcOver.derive(alpha));
            paint(g, pw, ph, text, s);
        } finally {
            g.dispose();
        }
    }

    /** Fits the card into at most {@code maxLines} lines (re-flowed, then smaller), never dropping words. */
    static Layout layout(String text, ProgramLyricsStyle s, int pw, int ph) {
        float k = ph / 1080f;
        List<String> raw = new ArrayList<>();
        for (String l : text.split("\\R")) {
            String t = l.strip();
            if (!t.isEmpty()) {
                raw.add(s.uppercase() ? t.toUpperCase(Locale.ROOT) : t);
            }
        }
        if (raw.isEmpty()) {
            return new Layout(List.of(), null);
        }
        LyricsBox box = s.box();
        float maxText = box != null ? Math.max(40, box.w() * pw - s.padH() * k * 2)
                : Math.max(40, s.maxBarWidth() * pw - s.padH() * k * 2);
        float size = s.fontSize() * k;
        float smallest = size * (box != null ? 0.4f : 0.6f);
        List<String> lines;
        while (true) {
            Font font = font(s, size);
            lines = wrap(raw, font, maxText); // the card's own line breaks first
            if (lines.size() > s.maxLines()) {
                List<String> flowed = wrap(List.of(String.join(" ", raw)), font, maxText); // fewer, longer lines
                if (flowed.size() < lines.size()) {
                    lines = flowed;
                }
            }
            boolean fits = lines.size() <= s.maxLines() && (box == null || fitsBox(lines.size(), font, s, box, ph));
            if (fits || size <= smallest) {
                return new Layout(lines, font);
            }
            size = Math.max(smallest, size * 0.92f);
        }
    }

    /** A Custom layout's lines (with their bars or padding) are no taller than its box. */
    private static boolean fitsBox(int n, Font font, ProgramLyricsStyle s, LyricsBox box, int ph) {
        float k = ph / 1080f;
        var lm = font.getLineMetrics("Ag", FRC);
        float textH = lm.getAscent() + lm.getDescent();
        boolean bars = box.background() == LyricsBox.Background.BARS;
        float lineH = bars ? textH + s.padV() * k * 2 : textH;
        float total = n * lineH + (n - 1) * s.lineSpacing() * k + (bars ? 0 : s.padV() * k * 2);
        return total <= box.h() * ph + 0.5f;
    }

    private static Font font(ProgramLyricsStyle s, float size) {
        return Fonts.create(s.fontFamily(), s.bold() ? FontWeight.BOLD : FontWeight.REGULAR, false, size);
    }

    private static List<String> wrap(List<String> lines, Font font, float max) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            StringBuilder cur = new StringBuilder();
            for (String word : line.split("\\s+")) {
                String cand = cur.isEmpty() ? word : cur + " " + word;
                if (cur.isEmpty() || width(cand, font) <= max) {
                    cur.setLength(0);
                    cur.append(cand);
                } else {
                    out.add(cur.toString());
                    cur.setLength(0);
                    cur.append(word);
                }
            }
            if (!cur.isEmpty()) {
                out.add(cur.toString());
            }
        }
        return out;
    }

    private static float width(String s, Font f) {
        return (float) f.getStringBounds(s, FRC).getWidth();
    }

    private static Color withAlpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(Math.clamp(a, 0f, 1f) * 255));
    }
}
