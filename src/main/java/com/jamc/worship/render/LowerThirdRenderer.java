package com.jamc.worship.render;

import com.jamc.worship.model.LowerThird;
import com.jamc.worship.util.Colors;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Draws a lower third in the 1920x1080 reference frame: accent bar, panel, name and subtitle,
 * with an optional logo. {@code progress} 0..1 drives the show/hide animation.
 */
public final class LowerThirdRenderer {

    private static final int MARGIN_X = 96;
    private static final int MARGIN_Y = 90;

    private LowerThirdRenderer() {
    }

    public static void paint(Graphics2D g0, LowerThird lt, float progress, ImageCache images) {
        if (lt == null || progress <= 0f) {
            return;
        }
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            float eased = ease(progress);
            if (lt.getStyle() == LowerThird.Style.BADGE) {
                paintBadge(g, lt, eased);
                return;
            }

            boolean hasSub = lt.getSubtitle() != null && !lt.getSubtitle().isBlank();
            BufferedImage logo = lt.getLogoPath() == null || images == null ? null : images.get(lt.getLogoPath());
            int fixedW = lt.getWidth() <= 0 ? 0 : Math.clamp(lt.getWidth(), 120, 1920 - 2 * MARGIN_X);
            // a set width: the box is exactly that wide and the words shrink when they would not fit (user 2026-11-17)
            float fs = lt.getFontSize();
            for (int i = 0; i < 2; i++) {
                FontMetrics tm = g.getFontMetrics(new Font(lt.getFontFamily(), Font.BOLD, Math.round(fs)));
                FontMetrics sm = g.getFontMetrics(new Font(lt.getFontFamily(), Font.PLAIN, Math.round(fs * 0.6f)));
                int tw = Math.max(tm.stringWidth(lt.getTitle()), hasSub ? sm.stringWidth(lt.getSubtitle()) : 0);
                int hh = tm.getHeight() + (hasSub ? sm.getHeight() + 4 : 0) + Math.round(fs * 0.7f);
                int room = fixedW - Math.max(8, Math.round(fs / 6)) - (logo == null ? 0 : hh) - 2 * Math.round(fs * 0.55f);
                if (fixedW == 0 || tw <= room || tw == 0) {
                    break;
                }
                fs = Math.max(12f, fs * Math.max(0.2f, room / (float) tw));
            }
            Font titleFont = new Font(lt.getFontFamily(), Font.BOLD, Math.round(fs));
            Font subFont = new Font(lt.getFontFamily(), Font.PLAIN, Math.round(fs * 0.6f));
            FontMetrics tfm = g.getFontMetrics(titleFont);
            FontMetrics sfm = g.getFontMetrics(subFont);

            int padX = Math.round(fs * 0.55f);
            int padY = Math.round(fs * 0.35f);
            int textH = tfm.getAscent() + tfm.getDescent() + (hasSub ? sfm.getAscent() + sfm.getDescent() + 4 : 0);
            int h = textH + padY * 2;
            int logoW = logo == null ? 0 : h;
            int textW = Math.max(tfm.stringWidth(lt.getTitle()), hasSub ? sfm.stringWidth(lt.getSubtitle()) : 0);
            int accent = Math.max(8, Math.round(fs / 6));
            int w = fixedW > 0 ? fixedW : Math.min(1920 - 2 * MARGIN_X, accent + logoW + padX * 2 + textW);

            LowerThird.Position pos = lt.getPosition();
            int x = switch (pos) {
                case BOTTOM_LEFT, TOP_LEFT -> MARGIN_X;
                case BOTTOM_CENTER -> (1920 - w) / 2;
                case BOTTOM_RIGHT, TOP_RIGHT -> 1920 - MARGIN_X - w;
            };
            int my = lt.getMarginY();
            int y = pos == LowerThird.Position.TOP_LEFT || pos == LowerThird.Position.TOP_RIGHT ? my : 1080 - my - h;

            float alpha = 1f;
            switch (lt.getAnimation()) {
                case SLIDE -> {
                    if (pos == LowerThird.Position.BOTTOM_CENTER) {
                        y += Math.round((1 - eased) * (h + MARGIN_Y));
                    } else {
                        boolean left = pos == LowerThird.Position.BOTTOM_LEFT || pos == LowerThird.Position.TOP_LEFT;
                        int travel = w + MARGIN_X + 40;
                        x += Math.round((1 - eased) * (left ? -travel : travel));
                    }
                    alpha = Math.min(1f, eased * 2f);
                }
                case FADE -> alpha = eased;
                case NONE -> alpha = 1f;
            }
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.clamp(alpha, 0f, 1f)));
            g.clip(new Rectangle(0, 0, 1920, 1080));

            Color bg = Colors.parse(lt.getBackgroundColor(), new Color(0x0B1220));
            g.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 235));
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(Colors.parse(lt.getAccentColor(), new Color(0x1677FF)));
            g.fillRoundRect(x, y, accent, h, 6, 6);

            int cx = x + accent;
            if (logo != null) {
                int s = h - padY;
                double scale = Math.min(s / (double) logo.getWidth(), s / (double) logo.getHeight());
                int lw = (int) (logo.getWidth() * scale);
                int lh = (int) (logo.getHeight() * scale);
                g.drawImage(logo, cx + padX / 2 + (s - lw) / 2, y + (h - lh) / 2, lw, lh, null);
                cx += logoW;
            }
            int ty = y + padY + tfm.getAscent();
            g.setFont(titleFont);
            g.setColor(Colors.parse(lt.getTitleColor(), Color.WHITE));
            int textRight = x + w - padX;
            g.drawString(lt.getTitle(), alignX(lt, false, cx + padX, textRight, tfm.stringWidth(lt.getTitle())), ty);
            if (hasSub) {
                g.setFont(subFont);
                g.setColor(Colors.parse(lt.getSubtitleColor(), new Color(0xCBD5E1)));
                g.drawString(lt.getSubtitle(), alignX(lt, false, cx + padX, textRight, sfm.stringWidth(lt.getSubtitle())),
                        ty + tfm.getDescent() + 4 + sfm.getAscent());
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * The name tag (user 2026-11-17): three slanted bars, right edges lined up for a right position (left edges for a
     * left one): the small label (accent colour, e.g. "Preaching Today"), the name (background colour, title colour)
     * and the role (gold, subtitle colour). Slides or fades like the classic box.
     */
    private static void paintBadge(Graphics2D g, LowerThird lt, float eased) {
        int fs = lt.getFontSize();
        Font nameFont = Fonts.create(lt.getFontFamily(), com.jamc.worship.model.FontWeight.BOLD, true, fs);
        Font smallFont = Fonts.create(lt.getFontFamily(), com.jamc.worship.model.FontWeight.BOLD, true, Math.round(fs * 0.62f));
        FontMetrics nm = g.getFontMetrics(nameFont);
        FontMetrics sm = g.getFontMetrics(smallFont);
        String label = lt.getLabel() == null || lt.getLabel().isBlank() ? null : lt.getLabel();
        String role = lt.getSubtitle() == null || lt.getSubtitle().isBlank() ? null : lt.getSubtitle();
        int nameH = Math.round(nm.getHeight() * 1.3f);
        int smallH = Math.round(sm.getHeight() * 1.25f);
        int slant = Math.round(nameH * 0.32f);
        int pad = Math.round(fs * 0.55f);
        int nameW = nm.stringWidth(lt.getTitle()) + 2 * pad + slant;
        int labelW = label == null ? 0 : sm.stringWidth(label) + 2 * pad + slant;
        int roleW = role == null ? 0 : sm.stringWidth(role) + 2 * pad + slant;
        if (lt.getWidth() > 0) {
            // a set width: the name bar is exactly that wide, the small bars never wider; a long name shrinks to fit
            int fixedW = Math.clamp(lt.getWidth(), 160, 1920 - 2 * MARGIN_X);
            int room = fixedW - 2 * pad - slant;
            int need = nm.stringWidth(lt.getTitle());
            if (need > room && need > 0) {
                nameFont = nameFont.deriveFont(Math.max(12f, nameFont.getSize2D() * room / need));
                nm = g.getFontMetrics(nameFont);
            }
            nameW = fixedW;
            labelW = Math.min(labelW, fixedW);
            roleW = Math.min(roleW, fixedW);
        }
        int overlap = Math.round(smallH * 0.18f);
        int totalH = (label == null ? 0 : smallH - overlap) + nameH + (role == null ? 0 : smallH - overlap);

        LowerThird.Position pos = lt.getPosition();
        boolean right = pos == LowerThird.Position.BOTTOM_RIGHT || pos == LowerThird.Position.TOP_RIGHT;
        boolean top = pos == LowerThird.Position.TOP_LEFT || pos == LowerThird.Position.TOP_RIGHT;
        int widest = Math.max(nameW, Math.max(labelW, roleW));
        int edge = switch (pos) {
            case BOTTOM_CENTER -> (1920 + widest) / 2;
            case BOTTOM_RIGHT, TOP_RIGHT -> 1920 - MARGIN_X;
            default -> MARGIN_X + widest;
        };
        int y = top ? lt.getMarginY() : 1080 - lt.getMarginY() - totalH;
        float alpha = 1f;
        switch (lt.getAnimation()) {
            case SLIDE -> {
                int travel = widest + MARGIN_X + 40;
                edge += Math.round((1 - eased) * (right ? travel : -travel));
                alpha = Math.min(1f, eased * 2f);
            }
            case FADE -> alpha = eased;
            case NONE -> alpha = 1f;
        }
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.clamp(alpha, 0f, 1f)));
        g.clip(new Rectangle(0, 0, 1920, 1080));
        // bars end at the same edge, the name bar sticking out a little further than the small ones
        int indent = Math.round(slant * 1.2f);
        int smallEdge = right ? edge - indent : edge + indent - widest + Math.max(labelW, roleW);
        if (label != null) {
            int x = right ? smallEdge - labelW : edge - widest + indent;
            bar(g, x, y, labelW, smallH, Math.round(smallH * 0.32f), Colors.parse(lt.getAccentColor(), new Color(0x1E4FD8)));
            text(g, smallFont, sm, label, Color.WHITE, x, y, labelW, smallH, Math.round(smallH * 0.32f));
            y += smallH - overlap;
        }
        int nx = right ? edge - nameW : edge - widest;
        bar(g, nx, y, nameW, nameH, slant, Colors.parse(lt.getBackgroundColor(), Color.WHITE));
        text(g, nameFont, nm, lt.getTitle(), Colors.parse(lt.getTitleColor(), new Color(0x0B1A3A)), nx, y, nameW, nameH, slant, lt);
        y += nameH - overlap;
        if (role != null) {
            int x = right ? smallEdge - roleW : edge - widest + indent;
            bar(g, x, y, roleW, smallH, Math.round(smallH * 0.32f), Colors.parse(com.jamc.worship.render.DesignedLayers.GOLD, Color.YELLOW));
            text(g, smallFont, sm, role, Colors.parse(lt.getSubtitleColor(), new Color(0x111827)), x, y, roleW, smallH, Math.round(smallH * 0.32f), lt);
        }
    }

    /** A bar slanted like a parallelogram (leaning right), with a soft shadow. */
    private static void bar(Graphics2D g, int x, int y, int w, int h, int slant, Color fill) {
        java.awt.Polygon p = new java.awt.Polygon(new int[]{x + slant, x + w, x + w - slant, x}, new int[]{y, y, y + h, y + h}, 4);
        g.setColor(new Color(0, 0, 0, 70));
        g.translate(4, 6);
        g.fillPolygon(p);
        g.translate(-4, -6);
        g.setColor(fill);
        g.fillPolygon(p);
    }

    private static void text(Graphics2D g, Font f, FontMetrics fm, String s, Color c, int x, int y, int w, int h, int slant) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, x + (w - fm.stringWidth(s)) / 2 + slant / 4, y + (h - fm.getHeight()) / 2 + fm.getAscent());
    }

    /** Name / role on a slanted bar, placed by the lower third's Left / Center / Right (the bar's slant kept clear). */
    private static void text(Graphics2D g, Font f, FontMetrics fm, String s, Color c, int x, int y, int w, int h, int slant,
                             LowerThird lt) {
        g.setFont(f);
        g.setColor(c);
        int inset = Math.round(h * 0.28f);
        int left = x + slant + inset;
        int right = x + w - slant / 2 - inset;
        g.drawString(s, alignX(lt, true, left, right, fm.stringWidth(s)), y + (h - fm.getHeight()) / 2 + fm.getAscent());
    }

    /**
     * Where a line of words starts between {@code left} and {@code right}: the lower third's own alignment (user
     * 2026-11-17), else the design's usual one (classic box: left, name tag: centered).
     */
    private static int alignX(LowerThird lt, boolean badge, int left, int right, int textW) {
        LowerThird.TextAlign a = lt.getTextAlign() != null ? lt.getTextAlign()
                : badge ? LowerThird.TextAlign.CENTER : LowerThird.TextAlign.LEFT;
        return switch (a) {
            case LEFT -> left;
            case RIGHT -> Math.max(left, right - textW);
            case CENTER -> left + Math.max(0, (right - left - textW) / 2);
        };
    }

    private static float ease(float p) {
        float t = Math.clamp(p, 0f, 1f);
        return 1 - (1 - t) * (1 - t) * (1 - t); // ease-out cubic
    }
}
