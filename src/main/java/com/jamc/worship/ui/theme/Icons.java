package com.jamc.worship.ui.theme;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * Loads the line icons in {@code /icons}. Icons are recolored at paint time, so they follow
 * theme changes. The filled star keeps its own color.
 */
public final class Icons {

    public static final int SMALL = 14;
    public static final int MEDIUM = 16;
    public static final int LARGE = 20;

    private Icons() {
    }

    /** Icon tinted with the secondary text color. */
    public static FlatSVGIcon get(String name, int size) {
        return get(name, size, Palette::secondaryText);
    }

    /** Icon tinted with a color resolved on every paint. */
    public static FlatSVGIcon get(String name, int size, Supplier<Color> color) {
        FlatSVGIcon icon = new FlatSVGIcon("icons/" + name + ".svg", size, size);
        if (!name.endsWith("-filled")) {
            icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> color.get()));
        }
        return icon;
    }
}
