package com.jamc.worship.ui.components;

import com.jamc.worship.ui.theme.Palette;

import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * An on / off switch (a pill with a sliding knob, accent blue when on) with its label to the right, e.g. Studio Mode.
 * It is a {@link JToggleButton}, so {@code isSelected}, action listeners and the keyboard (Space / Enter) work as usual.
 */
public class ToggleSwitch extends JToggleButton {

    private static final int TRACK_W = 34;
    private static final int TRACK_H = 18;
    private static final int GAP = 8;

    public ToggleSwitch(String text, boolean on) {
        super(text, on);
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(text);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(labelFont());
        String t = getText();
        int textW = t == null || t.isEmpty() ? 0 : GAP + fm.stringWidth(t);
        return new Dimension(TRACK_W + textW + 4, Math.max(TRACK_H + 4, fm.getHeight() + 4));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    private Font labelFont() {
        Font f = getFont();
        return f == null ? new Font("Segoe UI", Font.BOLD, 12) : f.deriveFont(Font.BOLD);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        boolean on = isSelected();
        int y = (getHeight() - TRACK_H) / 2;
        Color track = !isEnabled() ? Palette.border() : on ? Palette.accent() : Palette.withAlpha(Palette.mutedText(), 110);
        g.setColor(track);
        g.fillRoundRect(2, y, TRACK_W, TRACK_H, TRACK_H, TRACK_H);
        if (hasFocus()) {
            g.setColor(Palette.withAlpha(Palette.accent(), 140));
            g.drawRoundRect(0, y - 2, TRACK_W + 3, TRACK_H + 3, TRACK_H + 4, TRACK_H + 4);
        }
        int knob = TRACK_H - 4;
        int kx = on ? 2 + TRACK_W - knob - 2 : 4;
        g.setColor(Color.WHITE);
        g.fillOval(kx, y + 2, knob, knob);
        String t = getText();
        if (t != null && !t.isEmpty()) {
            g.setFont(labelFont());
            FontMetrics fm = g.getFontMetrics();
            g.setColor(isEnabled() ? Palette.text() : Palette.mutedText());
            g.drawString(t, 2 + TRACK_W + GAP, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
        }
        g.dispose();
    }
}
