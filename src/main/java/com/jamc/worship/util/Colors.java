package com.jamc.worship.util;

import java.awt.Color;
import java.util.Locale;

/** Conversions between {@code #RRGGBB} strings (as stored) and AWT colors. */
public final class Colors {

    private Colors() {
    }

    /** Parses {@code #RGB}, {@code #RRGGBB} or {@code #AARRGGBB}; returns {@code fallback} for anything else. */
    public static Color parse(String hex, Color fallback) {
        if (hex == null) {
            return fallback;
        }
        String s = hex.strip();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        try {
            return switch (s.length()) {
                case 3 -> new Color(Integer.parseInt("" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1)
                        + s.charAt(2) + s.charAt(2), 16));
                case 6 -> new Color(Integer.parseInt(s, 16));
                case 8 -> new Color((int) Long.parseLong(s, 16), true);
                default -> fallback;
            };
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String toHex(Color c) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static boolean isValid(String hex) {
        return parse(hex, null) != null;
    }
}
