package com.jamc.worship.stream;

import java.util.function.LongSupplier;

/**
 * Moves one sound input's delay toward a target (from {@link AvSync}) in small steps, at quiet moments where possible
 * so the change is not heard: a step adds silence before a chunk (later) or leaves out part of a chunk (earlier).
 * Not thread-safe: used by the one thread that sends that input's sound.
 */
final class SyncShift {

    static final long DEADBAND_MS = 25;
    static final long STEP_MS = 20;
    static final long FORCE_AFTER_MS = 2000;

    private final int rate;
    private final LongSupplier targetMs;
    private long applied;
    private long waitingSince;

    SyncShift(int rate, LongSupplier targetMs) {
        this.rate = rate;
        this.targetMs = targetMs;
    }

    /** Frames the delay has now (add to the clock's due frames). */
    long frames() {
        return applied;
    }

    /**
     * The change for the next chunk: &gt; 0 = write that many frames of silence before it, &lt; 0 = leave out up to that
     * many of its frames. Call {@link #applied} with what was really done.
     */
    long change(boolean quiet, long nowNanos) {
        long diff = Math.clamp(targetMs.getAsLong(), 0, 2 * AvSync.MAX_LAG_MS) * rate / 1000 - applied;
        if (Math.abs(diff) < DEADBAND_MS * rate / 1000) {
            waitingSince = 0;
            return 0;
        }
        if (!quiet) {
            if (waitingSince == 0) {
                waitingSince = nowNanos;
            }
            if (nowNanos - waitingSince < FORCE_AFTER_MS * 1_000_000) {
                return 0;
            }
        }
        waitingSince = 0;
        return Math.min(Math.abs(diff), STEP_MS * rate / 1000) * Long.signum(diff);
    }

    void applied(long frames) {
        applied += frames;
    }
}
