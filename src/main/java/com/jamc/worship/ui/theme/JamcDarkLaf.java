package com.jamc.worship.ui.theme;

import com.formdev.flatlaf.FlatDarkLaf;

/** Dark broadcast theme. Colors live in {@code JamcDarkLaf.properties} next to this class. */
public class JamcDarkLaf extends FlatDarkLaf {

    public static final String NAME = "LiveCat Dark";

    @Override
    public String getName() {
        return NAME;
    }

    public static boolean setup() {
        return setup(new JamcDarkLaf());
    }
}
