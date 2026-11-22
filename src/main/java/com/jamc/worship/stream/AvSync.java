package com.jamc.worship.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the sound of recordings and the livestream in time with the picture when the picture is late.
 *
 * <p>A camera or video picture reaches the recording later than the sound: VLC holds camera pictures back
 * ({@code live-caching}), and a busy PC paints Program late (the "lagging camera"). Each Program frame taken for the
 * recording notes how old the newest camera / video picture in it is; the sound inputs are then delayed by that much
 * (changed in small steps at quiet moments, see {@link SyncShift}), so lips and voice stay together.
 */
public final class AvSync {

    private static final Logger log = LoggerFactory.getLogger(AvSync.class);
    /** Ages above this are a paused or frozen picture, not lag. */
    static final long MAX_LAG_MS = 1000;
    /**
     * Extra delay for a microphone read straight from Windows (MicAudio): the camera picture spends VLC's
     * live-caching (150 ms) plus about a frame in the driver before the app sees it; the microphone about 50 ms. Only with
     * the VLC camera engine.
     */
    static final long MIC_EXTRA_MS = 120;
    /**
     * The camera's own delay before FFmpeg even sees a picture (exposure + USB/driver: at least one picture, 33 ms
     * at 30 fps), which the app's clock cannot see. FFmpeg to the app measured 5-25 ms, and that part is in the lag
     * above. Without this the voice came a little before the lips; the CLAP sync test measures the rest exactly.
     */
    static final long DIRECT_CAMERA_MS = 40;

    // EDT only (ProgramFrameTap paints there)
    private static boolean collecting;
    private static long newest;
    private static long lastContent;
    private static long windowStart;
    private static long windowMin = Long.MAX_VALUE;

    private static volatile long lagMs;

    private AvSync() {
    }

    /** A video / camera picture painted now arrived in the app at {@code arrivedNanos}. */
    public static void noteFrame(long arrivedNanos) {
        if (collecting && arrivedNanos > newest) {
            newest = arrivedNanos;
        }
    }

    static void beginFrame() {
        collecting = true;
        newest = 0;
    }

    /** Ends a Program frame taken at {@code nowNanos}. */
    static void endFrame(long nowNanos) {
        collecting = false;
        if (newest != 0 && newest != lastContent) { // a repeated picture says nothing about the lag
            lastContent = newest;
            long age = (nowNanos - newest) / 1_000_000;
            if (age >= 0 && age <= MAX_LAG_MS) {
                windowMin = Math.min(windowMin, age);
            }
        }
        if (nowNanos - windowStart >= 1_000_000_000L) {
            windowStart = nowNanos;
            if (windowMin != Long.MAX_VALUE) { // no moving picture this second: keep the last value
                publish(windowMin);
            }
            windowMin = Long.MAX_VALUE;
        }
    }

    static void publish(long ms) {
        long before = lagMs;
        lagMs = ms;
        if (Math.abs(ms - before) >= 50) {
            log.info("Picture lag {} ms: sound delayed to match", ms);
        }
    }

    /** How late the picture reaches the recording, in ms (the freshest moment of the last second). */
    public static long pictureLagMs() {
        return lagMs;
    }

    /** Delay for a microphone read straight from Windows. */
    static long micDelayMs() {
        // the Direct camera engine (FFmpeg) has no VLC caching, only its own ~1 picture of delay
        return (com.jamc.worship.camera.CameraManager.directEngine() ? DIRECT_CAMERA_MS : MIC_EXTRA_MS) + lagMs;
    }
}
