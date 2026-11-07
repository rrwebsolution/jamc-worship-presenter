package com.jamc.worship.ui.components;

import com.jamc.worship.ui.theme.Palette;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * Settings → Studio Mode: a small picture of the Control page's monitors. On, Preview and Program side by side with
 * TAKE between them; off, only the live Program (TAKE by key).
 */
final class StudioModePreview extends JComponent {

    private boolean on;

    StudioModePreview() {
        setPreferredSize(new Dimension(260, 112));
        setMinimumSize(getPreferredSize());
        setToolTipText("How the Control Room page looks with this setting");
    }

    void setOn(boolean on) {
        this.on = on;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        g.setColor(Palette.secondaryBackground());
        g.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
        g.setColor(Palette.border());
        g.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);

        int pad = 10;
        int captionH = 16;
        int areaH = h - pad * 2 - captionH;
        Font base = getFont() == null ? new Font("Segoe UI", Font.PLAIN, 12) : getFont();
        if (on) {
            int takeW = 38;
            int mw = (w - pad * 2 - takeW - 8) / 2;
            int mh = Math.min(areaH, mw * 9 / 16);
            int y = pad + (areaH - mh) / 2;
            monitor(g, new Rectangle(pad, y, mw, mh), "PREVIEW", Palette.accent(), base);
            int tx = pad + mw + 4;
            g.setColor(new Color(0x16A34A));
            g.fillRoundRect(tx, y + mh / 2 - 9, takeW, 18, 8, 8);
            g.setColor(Color.WHITE);
            g.setFont(base.deriveFont(Font.BOLD, 9f));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("TAKE", tx + (takeW - fm.stringWidth("TAKE")) / 2, y + mh / 2 + fm.getAscent() / 2 - 1);
            monitor(g, new Rectangle(tx + takeW + 4, y, mw, mh), "PROGRAM", Palette.live(), base);
            caption(g, "ON · Preview and Program, with the TAKE button", base, w, h);
        } else {
            int mh = areaH;
            int mw = Math.min(w - pad * 2, mh * 16 / 9);
            monitor(g, new Rectangle((w - mw) / 2, pad, mw, mh), "PROGRAM", Palette.live(), base);
            caption(g, "OFF · Program only · Space = TAKE", base, w, h);
        }
        g.dispose();
    }

    private static void monitor(Graphics2D g, Rectangle r, String label, Color color, Font base) {
        g.setColor(Color.BLACK);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
        g.setColor(color);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
        g.setFont(base.deriveFont(Font.BOLD, 8.5f));
        FontMetrics fm = g.getFontMetrics();
        int bw = fm.stringWidth(label) + 8;
        g.fillRoundRect(r.x + 4, r.y + 4, bw, fm.getHeight(), 6, 6);
        g.setColor(Color.WHITE);
        g.drawString(label, r.x + 8, r.y + 4 + fm.getAscent());
        // a hint of a lyric line
        g.setColor(new Color(255, 255, 255, 120));
        int lw = r.width * 2 / 3;
        g.fillRoundRect(r.x + (r.width - lw) / 2, r.y + r.height * 3 / 5, lw, 3, 3, 3);
        g.fillRoundRect(r.x + (r.width - lw / 2) / 2, r.y + r.height * 3 / 5 + 6, lw / 2, 3, 3, 3);
    }

    private static void caption(Graphics2D g, String text, Font base, int w, int h) {
        g.setFont(base.deriveFont(Font.PLAIN, 10.5f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Palette.secondaryText());
        g.drawString(text, (w - fm.stringWidth(text)) / 2, h - 8);
    }
}
