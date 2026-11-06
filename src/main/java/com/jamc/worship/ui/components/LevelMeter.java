package com.jamc.worship.ui.components;

import com.jamc.worship.ui.theme.Palette;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A moving sound wave for one mixer channel: the last few seconds of level, newest on the right.
 * Green is a good level, yellow loud, red too loud (it may distort). A flat line means silence; a
 * message (e.g. "not connected") replaces the wave when the input cannot be heard.
 */
public class LevelMeter extends JComponent implements MixerMeters.Meter {

    private final float[] history = new float[72];
    private int head;
    private String message;

    public LevelMeter() {
        setPreferredSize(new Dimension(180, 26));
        setMinimumSize(new Dimension(120, 22));
        setToolTipText("Sound level: green is good, yellow loud, red too loud. A flat line is silence.");
    }

    /** Adds the newest level (0..1 peak) and moves the wave along. */
    @Override
    public void push(float level) {
        history[head] = level;
        head = (head + 1) % history.length;
        repaint();
    }

    /** Shows {@code text} instead of the wave (null = the wave). */
    @Override
    public void setMessage(String text) {
        if (!java.util.Objects.equals(message, text)) {
            message = text;
            if (text != null) {
                java.util.Arrays.fill(history, 0);
            }
            repaint();
        }
    }

    /** 0..1 on a decibel scale (-54 dB .. 0 dB), so quiet speech still shows. */
    static float scale(float peak) {
        if (peak <= 0.002f) {
            return 0;
        }
        double db = 20 * Math.log10(peak);
        return (float) Math.clamp((db + 54) / 54, 0, 1);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        g.setColor(Palette.windowBackground());
        g.fillRoundRect(0, 0, w, h, 8, 8);
        int mid = h / 2;
        if (message != null) {
            g.setColor(Palette.mutedText());
            g.setFont(getFont().deriveFont(Font.PLAIN, Math.max(10f, getFont().getSize2D() - 2)));
            var fm = g.getFontMetrics();
            g.drawString(message, Math.max(6, (w - fm.stringWidth(message)) / 2), mid + fm.getAscent() / 2 - 1);
            g.dispose();
            return;
        }
        g.setColor(Palette.withAlpha(Palette.mutedText(), 90));
        g.drawLine(4, mid, w - 4, mid);
        float step = (w - 8f) / history.length;
        int bar = Math.max(1, Math.round(step) - 1);
        for (int i = 0; i < history.length; i++) {
            float v = scale(history[(head + i) % history.length]);
            if (v <= 0) {
                continue;
            }
            int half = Math.max(1, Math.round(v * (mid - 3)));
            g.setColor(color(v));
            g.fillRect(4 + Math.round(i * step), mid - half, bar, half * 2);
        }
        g.dispose();
    }

    private static Color color(float v) {
        if (v > 0.94f) {
            return Palette.danger();
        }
        if (v > 0.8f) {
            return Palette.warning();
        }
        return Palette.live();
    }
}
