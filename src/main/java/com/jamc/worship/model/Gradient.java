package com.jamc.worship.model;

import com.jamc.worship.util.Colors;

import java.awt.Color;
import java.util.Locale;

/**
 * A two-color linear gradient background, stored in a style's background value as
 * {@code "#RRGGBB,#RRGGBB,angle"}. The angle is in degrees: 0 runs left to right, 90 top to bottom.
 */
public record Gradient(String from, String to, int angle) {

    public static final Gradient DEFAULT = new Gradient("#1E3A8A", "#0B1220", 90);

    public Gradient {
        from = from == null ? DEFAULT.from : from;
        to = to == null ? DEFAULT.to : to;
        angle = Math.floorMod(angle, 360);
    }

    /** Parses a stored value; anything malformed gives {@link #DEFAULT}. */
    public static Gradient parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String[] p = value.split(",");
        if (p.length < 2 || !p[0].strip().startsWith("#") || !p[1].strip().startsWith("#")) {
            return DEFAULT;
        }
        int angle = 90;
        if (p.length > 2) {
            try {
                angle = Integer.parseInt(p[2].strip());
            } catch (NumberFormatException ignored) {
                // keep the default direction
            }
        }
        return new Gradient(p[0].strip(), p[1].strip(), angle);
    }

    public String format() {
        return from.toUpperCase(Locale.ROOT) + "," + to.toUpperCase(Locale.ROOT) + "," + angle;
    }

    public Color fromColor() {
        return Colors.parse(from, Color.BLACK);
    }

    public Color toColor() {
        return Colors.parse(to, Color.BLACK);
    }
}
