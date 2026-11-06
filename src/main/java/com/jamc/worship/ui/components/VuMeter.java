package com.jamc.worship.ui.components;

import com.jamc.worship.ui.theme.Palette;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A vertical mixer level meter (like a desk or OBS): -60 dB at the bottom, 0 dB at the top, green up to
 * -20 dB, yellow up to -9 dB, red above. The bar falls back smoothly and a thin peak line holds for a moment.
 * A dB scale is drawn beside the bar. A message (e.g. "not connected") dims the bar and is written along it.
 */
public class VuMeter extends JComponent implements MixerMeters.Meter {

    static final float MIN_DB = -60f;
    private static final float YELLOW_DB = -20f;
    private static final float RED_DB = -9f;
    private static final int BAR = 9;

    private float level;   // 0..1 on the dB scale
    private float peak;    // 0..1
    private long peakAt;
    private String message;

    public VuMeter() {
        setPreferredSize(new Dimension(BAR + 24, 150));
        setMinimumSize(new Dimension(BAR + 24, 60));
        setToolTipText("Sound level: green is good, yellow loud, red too loud (it may distort).");
    }

    /** 0..1 on the meter's dB scale. */
    static float scale(float amplitude) {
        if (amplitude <= 0.001f) {
            return 0;
        }
        double db = 20 * Math.log10(amplitude);
        return (float) Math.clamp((db - MIN_DB) / -MIN_DB, 0, 1);
    }

    @Override
    public void push(float amplitude) {
        float v = scale(amplitude);
        level = v >= level ? v : Math.max(v, level - 0.035f); // fast up, slow fall
        long now = System.currentTimeMillis();
        if (v >= peak || now - peakAt > 1500) {
            peak = v >= peak ? v : Math.max(v, peak - 0.02f);
            if (v >= peak) {
                peakAt = now;
            }
        }
        repaint();
    }

    @Override
    public void setMessage(String text) {
        if (!java.util.Objects.equals(message, text)) {
            message = text;
            if (text != null) {
                level = 0;
                peak = 0;
            }
            setToolTipText(text == null ? "Sound level: green is good, yellow loud, red too loud (it may distort)."
                    : "No level: " + text);
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int top = 6;
        int h = Math.max(10, getHeight() - top * 2);
        int x = 0;
        // dark track in three zones, lit up to the level
        paintZones(g, x, top, h, true);
        if (message == null && level > 0) {
            int litTop = top + Math.round(h * (1 - level));
            g.setClip(x, litTop, BAR, top + h - litTop);
            paintZones(g, x, top, h, false);
            g.setClip(null);
        }
        if (message == null && peak > 0) {
            int py = top + Math.round(h * (1 - peak));
            g.setColor(zoneColor(peak, false));
            g.fillRect(x, py, BAR, 2);
        }
        // scale: 0, -6 … -60
        Font f = getFont().deriveFont(Font.PLAIN, 9f);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int step = h < 110 ? 12 : 6;
        for (int db = 0; db >= MIN_DB; db -= step) {
            int y = top + Math.round(h * (db / MIN_DB));
            g.setColor(Palette.mutedText());
            g.fillRect(x + BAR + 1, y, 3, 1);
            String s = String.valueOf(db);
            g.drawString(s, x + BAR + 5, y + fm.getAscent() / 2 - 1);
        }
        if (message != null) {
            g.setColor(Palette.mutedText());
            g.setFont(getFont().deriveFont(Font.PLAIN, 9f));
            FontMetrics mf = g.getFontMetrics();
            String text = message;
            g.rotate(-Math.PI / 2);
            int tw = mf.stringWidth(text);
            g.drawString(text, -(top + (h + tw) / 2), x + BAR - 1);
        }
        g.dispose();
    }

    private void paintZones(Graphics2D g, int x, int top, int h, boolean dark) {
        int yYellow = top + Math.round(h * (YELLOW_DB / MIN_DB));
        int yRed = top + Math.round(h * (RED_DB / MIN_DB));
        g.setColor(zone(Palette.danger(), dark));
        g.fillRect(x, top, BAR, yRed - top);
        g.setColor(zone(Palette.warning(), dark));
        g.fillRect(x, yRed, BAR, yYellow - yRed);
        g.setColor(zone(Palette.live(), dark));
        g.fillRect(x, yYellow, BAR, top + h - yYellow);
    }

    private static Color zoneColor(float v, boolean dark) {
        float db = MIN_DB * (1 - v);
        return zone(db > RED_DB ? Palette.danger() : db > YELLOW_DB ? Palette.warning() : Palette.live(), dark);
    }

    private static Color zone(Color c, boolean dark) {
        return dark ? new Color(c.getRed() / 3, c.getGreen() / 3, c.getBlue() / 3) : c;
    }
}
