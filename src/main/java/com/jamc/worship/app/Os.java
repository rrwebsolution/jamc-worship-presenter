package com.jamc.worship.app;

import java.util.Locale;

/** Which operating system LiveCat runs on: Windows (the main platform) or macOS. */
public final class Os {

    private static final String NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    public static final boolean WINDOWS = NAME.startsWith("windows");
    public static final boolean MAC = NAME.startsWith("mac") || NAME.startsWith("darwin");

    private Os() {
    }
}
