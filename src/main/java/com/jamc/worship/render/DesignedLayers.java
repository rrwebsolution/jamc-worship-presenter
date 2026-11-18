package com.jamc.worship.render;

import com.jamc.worship.model.FontWeight;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.util.Colors;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scene layers for designed layouts (user 2026-11-17, a "Preaching" picture): shapes with rounded corners, gradients
 * and borders, text with a filled tag behind it and coloured words, and the Bible card (the reference in a yellow tag,
 * the verse on the left, marked words coloured). Sizes are in pixels of a 1920x1080 frame and scale with the output.
 */
public final class DesignedLayers {

    public static final String GOLD = "#F5C542";
    private static final Pattern STARS = Pattern.compile("\\*([^*]+)\\*");

    private DesignedLayers() {
    }

    /** One piece of text, coloured or not. */
    public record Run(String text, boolean highlighted) {
    }

    /** COLOR / Shape: plain or top-to-bottom gradient, rounded corners, an optional border. */
    public static void paintShape(Graphics2D g0, Rectangle r, SourceConfig c, int ph, float opacity) {
        Graphics2D g = prepared(g0, opacity);
        try {
            double k = ph / 1080.0;
            Shape shape = shape(r, c.cornerRadius, k);
            Color top = Colors.parse(c.color, Color.BLACK);
            g.setPaint(c.color2 == null || c.color2.isBlank() ? top
                    : new GradientPaint(r.x, r.y, top, r.x, r.y + r.height, Colors.parse(c.color2, top)));
            g.fill(shape);
            border(g, r, c, k);
        } finally {
            g.dispose();
        }
    }

    /**
     * TEXT: one line, LEFT / CENTER / RIGHT in its box, with an optional filled tag behind the words, and *starred*
     * words in the highlight colour.
     */
    public static void paintText(Graphics2D g0, Rectangle r, String text, SourceConfig c, int ph, float opacity) {
        if (text == null || text.isBlank()) {
            return;
        }
        Graphics2D g = prepared(g0, opacity);
        try {
            double k = ph / 1080.0;
            float size = (float) ((c.fontSize == null ? 48 : c.fontSize) * k);
            String family = c.fontFamily == null ? "Segoe UI" : c.fontFamily;
            boolean italic = Boolean.TRUE.equals(c.italic);
            Font font = Fonts.create(family, c.fontWeight == null ? FontWeight.BOLD : c.fontWeight, italic, Math.max(1, Math.round(size)));
            List<Run> runs = runs(text.replace('\n', ' '), null);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int width = 0;
            for (Run run : runs) {
                width += fm.stringWidth(run.text());
            }
            boolean tag = c.background != null && !c.background.isBlank();
            int padX = tag ? (int) Math.round(size * 0.6) : 0;
            // the words shrink to fit the box rather than spill out of it
            int room = r.width - 2 * padX;
            if (width > room && width > 0) {
                font = font.deriveFont(Math.max(1f, font.getSize2D() * room / (float) width));
                g.setFont(font);
                fm = g.getFontMetrics();
                width = 0;
                for (Run run : runs) {
                    width += fm.stringWidth(run.text());
                }
            }
            String align = c.align == null ? "CENTER" : c.align.toUpperCase(Locale.ROOT);
            int boxW = width + 2 * padX;
            int bx = switch (align) {
                case "LEFT" -> r.x;
                case "RIGHT" -> r.x + r.width - boxW;
                default -> r.x + (r.width - boxW) / 2;
            };
            if (tag) {
                Rectangle t = new Rectangle(bx, r.y, boxW, r.height);
                Color top = Colors.parse(c.background, Color.BLACK);
                g.setPaint(c.color2 == null || c.color2.isBlank() ? top
                        : new GradientPaint(t.x, t.y, top, t.x, t.y + t.height, Colors.parse(c.color2, top)));
                g.fill(shape(t, c.cornerRadius == null ? (int) Math.round(r.height / k / 2) : c.cornerRadius, k));
                border(g, t, c, k);
            }
            int x = bx + padX;
            int y = r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent();
            Color normal = Colors.parse(c.color, Color.WHITE);
            Color marked = Colors.parse(c.highlightColor, Colors.parse(GOLD, Color.YELLOW));
            for (Run run : runs) {
                if (!tag) {
                    g.setColor(new Color(0, 0, 0, 150));
                    int d = Math.max(1, Math.round(size / 24));
                    g.drawString(run.text(), x + d, y + d);
                }
                g.setColor(run.highlighted() ? marked : normal);
                g.drawString(run.text(), x, y);
                x += fm.stringWidth(run.text());
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * The Bible card: the reference in a yellow tag at the top left, the verse below it wrapped on the left (shrunk to
     * fit), the marked words coloured, and a short gold line under the words.
     */
    public static void paintBibleCard(Graphics2D g0, Rectangle r, String reference, String verse, SourceConfig c, int ph) {
        if (verse == null || verse.isBlank()) {
            return;
        }
        Graphics2D g = prepared(g0, 1f);
        try {
            double k = ph / 1080.0;
            int pad = (int) Math.round(Math.min(r.width, r.height) * 0.08);
            int x0 = r.x + pad;
            int y = r.y + pad;
            int innerW = r.width - 2 * pad;
            Color gold = Colors.parse(c.highlightColor, Colors.parse(GOLD, Color.YELLOW));
            String family = c.fontFamily == null ? "Segoe UI" : c.fontFamily;

            if (reference != null && !reference.isBlank()) {
                Font refFont = Fonts.create(family, FontWeight.BOLD, false, Math.max(1, (int) Math.round(40 * k)));
                g.setFont(refFont);
                FontMetrics rm = g.getFontMetrics();
                int th = (int) Math.round(rm.getHeight() * 1.35);
                int tw = Math.min(innerW, rm.stringWidth(reference) + th);
                g.setColor(gold);
                g.fill(new RoundRectangle2D.Double(x0, y, tw, th, th * 0.45, th * 0.45));
                g.setColor(new Color(0x111827));
                g.drawString(reference, x0 + (tw - rm.stringWidth(reference)) / 2, y + (th - rm.getHeight()) / 2 + rm.getAscent());
                y += th + (int) Math.round(th * 0.45);
            }

            List<Run> runs = runs(verse.replaceAll("\\s+", " ").strip(), c.highlightWords);
            int bottom = r.y + r.height - pad - (int) Math.round(24 * k);
            float size = (float) ((c.fontSize == null ? 60 : c.fontSize) * k);
            List<List<Run>> lines;
            Font plain;
            Font bold;
            FontMetrics fm;
            while (true) {
                // the verse in the Bible Typography font: its thickness and italic; marked words at least bold
                FontWeight weight = c.fontWeight == null ? FontWeight.MEDIUM : c.fontWeight;
                boolean italic = Boolean.TRUE.equals(c.italic);
                plain = Fonts.create(family, weight, italic, Math.max(1, Math.round(size)));
                bold = Fonts.create(family, weight.ordinal() >= FontWeight.BOLD.ordinal() ? weight : FontWeight.BOLD, italic,
                        Math.max(1, Math.round(size)));
                g.setFont(plain);
                fm = g.getFontMetrics();
                lines = wrap(g, runs, plain, bold, innerW);
                int needed = lines.size() * fm.getHeight();
                if (y + needed <= bottom || size <= 12 * k) {
                    break;
                }
                size *= 0.92f;
            }
            Color white = Colors.parse(c.color, Color.WHITE);
            int lineY = y + fm.getAscent();
            int widest = 0;
            for (List<Run> line : lines) {
                int x = x0;
                for (Run run : line) {
                    Font f = run.highlighted() ? bold : plain;
                    g.setFont(f);
                    g.setColor(new Color(0, 0, 0, 120));
                    g.drawString(run.text(), x + 2, lineY + 2);
                    g.setColor(run.highlighted() ? gold : white);
                    g.drawString(run.text(), x, lineY);
                    x += g.getFontMetrics().stringWidth(run.text());
                }
                widest = Math.max(widest, x - x0);
                lineY += fm.getHeight();
            }
            int lineW = Math.max((int) Math.round(80 * k), widest * 2 / 5);
            int ly = lineY - fm.getAscent() + (int) Math.round(18 * k);
            g.setColor(gold);
            g.fill(new RoundRectangle2D.Double(x0 + (widest - lineW) / 2.0, ly, lineW, Math.max(2, 5 * k), 4 * k, 4 * k));
        } finally {
            g.dispose();
        }
    }

    /** The text cut into coloured / plain pieces: *starred* words, and the listed phrases (any case). */
    public static List<Run> runs(String text, String phrases) {
        List<Run> starred = new ArrayList<>();
        Matcher m = STARS.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) {
                starred.add(new Run(text.substring(at, m.start()), false));
            }
            starred.add(new Run(m.group(1), true));
            at = m.end();
        }
        if (at < text.length()) {
            starred.add(new Run(text.substring(at), false));
        }
        if (phrases == null || phrases.isBlank()) {
            return starred;
        }
        List<String> list = new ArrayList<>();
        for (String p : phrases.split(",")) {
            if (!p.isBlank()) {
                list.add(Pattern.quote(p.strip()));
            }
        }
        if (list.isEmpty()) {
            return starred;
        }
        Pattern any = Pattern.compile(String.join("|", list), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<Run> out = new ArrayList<>();
        for (Run run : starred) {
            if (run.highlighted()) {
                out.add(run);
                continue;
            }
            Matcher pm = any.matcher(run.text());
            int from = 0;
            while (pm.find()) {
                if (pm.start() > from) {
                    out.add(new Run(run.text().substring(from, pm.start()), false));
                }
                out.add(new Run(pm.group(), true));
                from = pm.end();
            }
            if (from < run.text().length()) {
                out.add(new Run(run.text().substring(from), false));
            }
        }
        return out;
    }

    /** Word wrap of coloured pieces: each line a list of pieces, never wider than {@code width} (a long word alone). */
    static List<List<Run>> wrap(Graphics2D g, List<Run> runs, Font plain, Font bold, int width) {
        FontMetrics pm = g.getFontMetrics(plain);
        FontMetrics bm = g.getFontMetrics(bold);
        List<List<Run>> lines = new ArrayList<>();
        List<Run> line = new ArrayList<>();
        int used = 0;
        for (Run run : runs) {
            FontMetrics fm = run.highlighted() ? bm : pm;
            for (String word : run.text().split("(?<= )")) {
                if (word.isEmpty()) {
                    continue;
                }
                int w = fm.stringWidth(word.stripTrailing());
                if (used > 0 && used + w > width) {
                    lines.add(line);
                    line = new ArrayList<>();
                    used = 0;
                    word = word.stripLeading();
                    if (word.isEmpty()) {
                        continue;
                    }
                }
                line.add(new Run(word, run.highlighted()));
                used += fm.stringWidth(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    private static Graphics2D prepared(Graphics2D g0, float opacity) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (opacity < 1f) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.clamp(opacity, 0f, 1f)));
        }
        return g;
    }

    private static Shape shape(Rectangle r, Integer radius, double k) {
        if (radius == null || radius <= 0) {
            return r;
        }
        double d = Math.min(Math.min(r.width, r.height), radius * 2 * k);
        return new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, d, d);
    }

    private static void border(Graphics2D g, Rectangle r, SourceConfig c, double k) {
        if (c.borderColor == null || c.borderColor.isBlank()) {
            return;
        }
        float bw = (float) Math.max(1, (c.borderWidth == null ? 3 : c.borderWidth) * k);
        g.setStroke(new BasicStroke(bw));
        g.setColor(Colors.parse(c.borderColor, Color.WHITE));
        Rectangle in = new Rectangle(r.x + Math.round(bw / 2), r.y + Math.round(bw / 2),
                Math.max(1, r.width - Math.round(bw)), Math.max(1, r.height - Math.round(bw)));
        g.draw(shape(in, c.cornerRadius, k));
    }
}
