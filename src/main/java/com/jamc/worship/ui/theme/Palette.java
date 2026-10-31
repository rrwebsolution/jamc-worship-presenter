package com.jamc.worship.ui.theme;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Application colors, looked up from the active look and feel on every call so custom-painted
 * components follow a dark/light theme switch without being rebuilt.
 */
public final class Palette {

    private Palette() {
    }

    public static Color windowBackground() { return color("Jamc.windowBackground", 0x0B1220); }
    public static Color panelBackground() { return color("Jamc.panelBackground", 0x111827); }
    public static Color secondaryBackground() { return color("Jamc.secondaryBackground", 0x172033); }
    public static Color hoverBackground() { return color("Jamc.hoverBackground", 0x1C2740); }
    public static Color border() { return color("Jamc.border", 0x223047); }
    public static Color accent() { return color("Jamc.accent", 0x1677FF); }
    public static Color live() { return color("Jamc.live", 0x16A34A); }
    public static Color danger() { return color("Jamc.danger", 0xEF4444); }
    public static Color warning() { return color("Jamc.warning", 0xF59E0B); }
    public static Color text() { return color("Jamc.text", 0xF8FAFC); }
    public static Color secondaryText() { return color("Jamc.secondaryText", 0x94A3B8); }
    public static Color mutedText() { return color("Jamc.mutedText", 0x64748B); }
    public static Color monitorBackground() { return color("Jamc.monitorBackground", 0x000000); }
    public static Color monitorText() { return color("Jamc.monitorText", 0xFFFFFF); }

    public static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static Color color(String key, int fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : new Color(fallback);
    }
}
