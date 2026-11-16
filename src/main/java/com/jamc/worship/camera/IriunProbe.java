package com.jamc.worship.camera;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Iriun installs four cameras (Iriun Webcam, #2, #3, #4) whether or not a phone is connected to them. One without a
 * phone sends Iriun's "Looking for the phone" picture: almost all black and not moving. Those are left out
 * of the camera list, so LiveCat shows only the Iriun cameras that have a phone; when a phone connects later, the
 * next detection finds it.
 */
final class IriunProbe {

    private static final Logger log = LoggerFactory.getLogger(IriunProbe.class);
    static final double PROBE_SECONDS = 2.0;
    /** Brightness (of 255) counted as pure black. */
    static final int PURE_BLACK = 3;
    /** Share of pure black pixels in Iriun's waiting picture (measured 99%). */
    static final double WAITING_MIN_BLACK = 0.9;
    /** Mean change between the first and last picture of the probe (measured 0.05 waiting, 1.3 with a phone). */
    static final double WAITING_MAX_CHANGE = 0.5;
    private static final int W = 64;
    private static final int H = 36;

    private IriunProbe() {
    }

    static boolean isIriun(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("iriun");
    }

    /** The cameras without the Iriun ones that are waiting for a phone (checked together, about 2 seconds). */
    static List<String> withoutWaiting(Path ffmpeg, List<String> cameras) {
        if (ffmpeg == null || !com.jamc.worship.app.Os.WINDOWS) {
            return cameras;
        }
        List<CompletableFuture<Boolean>> checks = new ArrayList<>();
        for (String cam : cameras) {
            checks.add(isIriun(cam)
                    ? CompletableFuture.supplyAsync(() -> waiting(ffmpeg, cam))
                    : CompletableFuture.completedFuture(false));
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < cameras.size(); i++) {
            if (!checks.get(i).join()) {
                out.add(cameras.get(i));
            }
        }
        if (out.size() != cameras.size()) {
            List<String> hidden = new ArrayList<>(cameras);
            hidden.removeAll(out);
            log.info("Iriun cameras without a phone, not listed: {}", hidden);
        }
        return out;
    }

    /** True when this Iriun camera shows Iriun's "Looking for the phone" picture. A camera in use stays listed. */
    static boolean waiting(Path ffmpeg, String cam) {
        try {
            Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-f", "dshow",
                    "-i", "video=" + cam, "-t", String.valueOf(PROBE_SECONDS), "-fps_mode", "passthrough", "-vf", "scale=" + W + ":" + H + ",format=gray",
                    "-f", "rawvideo", "pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] all;
            try (InputStream in = p.getInputStream()) {
                all = in.readAllBytes();
            }
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return looksWaiting(all, PROBE_SECONDS);
        } catch (Exception e) {
            return false; // when in doubt, list it
        }
    }

    /**
     * Iriun's waiting picture: almost all pure black (a small cat and "Looking for the phone" in the middle) and not
     * moving. Measured: 99% pure black and 0.03-0.05 change in 2 s without a phone; 1% black and 1.3 change with one.
     * Fewer than two pictures (busy: in use in LiveCat) is never "waiting".
     */
    static boolean looksWaiting(byte[] gray, double seconds) {
        int size = W * H;
        int frames = gray.length / size;
        if (frames < 2) {
            return false;
        }
        int black = 0;
        double change = 0;
        int last = (frames - 1) * size;
        for (int i = 0; i < size; i++) {
            int v = gray[i] & 0xFF;
            if (v <= PURE_BLACK) {
                black++;
            }
            change += Math.abs(v - (gray[last + i] & 0xFF));
        }
        return black >= size * WAITING_MIN_BLACK && change / size < WAITING_MAX_CHANGE;
    }
}
