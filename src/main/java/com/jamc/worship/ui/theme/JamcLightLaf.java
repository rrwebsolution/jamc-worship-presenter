package com.jamc.worship.ui.theme;

import com.formdev.flatlaf.FlatLightLaf;

/** Light theme for bright rooms. Colors live in {@code JamcLightLaf.properties} next to this class. */
public class JamcLightLaf extends FlatLightLaf {

    public static final String NAME = "JAMC Light";

    @Override
    public String getName() {
        return NAME;
    }

    public static boolean setup() {
        return setup(new JamcLightLaf());
    }
}
