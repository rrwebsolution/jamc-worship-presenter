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

            Font titleFont = new Font(lt.getFontFamily(), Font.BOLD, lt.getFontSize());
            Font subFont = new Font(lt.getFontFamily(), Font.PLAIN, Math.round(lt.getFontSize() * 0.6f));
            FontMetrics tfm = g.getFontMetrics(titleFont);
            FontMetrics sfm = g.getFontMetrics(subFont);
            boolean hasSub = lt.getSubtitle() != null && !lt.getSubtitle().isBlank();
            BufferedImage logo = lt.getLogoPath() == null || images == null ? null : images.get(lt.getLogoPath());

            int padX = Math.round(lt.getFontSize() * 0.55f);
            int padY = Math.round(lt.getFontSize() * 0.35f);
            int textH = tfm.getAscent() + tfm.getDescent() + (hasSub ? sfm.getAscent() + sfm.getDescent() + 4 : 0);
            int h = textH + padY * 2;
            int logoW = logo == null ? 0 : h;
            int textW = Math.max(tfm.stringWidth(lt.getTitle()), hasSub ? sfm.stringWidth(lt.getSubtitle()) : 0);
            int accent = Math.max(8, lt.getFontSize() / 6);
            int w = Math.min(1920 - 2 * MARGIN_X, accent + logoW + padX * 2 + textW);

            LowerThird.Position pos = lt.getPosition();
            int x = switch (pos) {
                case BOTTOM_LEFT, TOP_LEFT -> MARGIN_X;
                case BOTTOM_CENTER -> (1920 - w) / 2;
                case BOTTOM_RIGHT, TOP_RIGHT -> 1920 - MARGIN_X - w;
            };
            int y = pos == LowerThird.Position.TOP_LEFT || pos == LowerThird.Position.TOP_RIGHT ? MARGIN_Y : 1080 - MARGIN_Y - h;

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
            g.drawString(lt.getTitle(), cx + padX, ty);
            if (hasSub) {
                g.setFont(subFont);
                g.setColor(Colors.parse(lt.getSubtitleColor(), new Color(0xCBD5E1)));
                g.drawString(lt.getSubtitle(), cx + padX, ty + tfm.getDescent() + 4 + sfm.getAscent());
            }
        } finally {
            g.dispose();
        }
    }

    private static float ease(float p) {
        float t = Math.clamp(p, 0f, 1f);
        return 1 - (1 - t) * (1 - t) * (1 - t); // ease-out cubic
    }
}
