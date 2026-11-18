package com.jamc.worship.output;

import java.util.function.BooleanSupplier;

/**
 * Whether the livestream is on, for scene layers set to "only while live" (the LIVE badge). The main window installs
 * the stream manager's answer; until then nothing is live.
 */
public final class LiveStatus {

    private static volatile BooleanSupplier streaming = () -> false;

    private LiveStatus() {
    }

    public static void install(BooleanSupplier s) {
        streaming = s == null ? () -> false : s;
    }

    public static boolean streaming() {
        try {
            return streaming.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
