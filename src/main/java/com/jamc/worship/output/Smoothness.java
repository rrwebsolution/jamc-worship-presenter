package com.jamc.worship.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The smoothness log: every 10 s while video moves on an output, one line with how often each output (Preview monitor,
 * Program monitor, projector) was drawn, the longest wait between two of its drawings, and the longest time the Swing
 * thread was stuck. Together with the cameras' own line (CameraManager) it tells whether a stutter comes from the
 * camera, from the drawing, or from the app being busy. Swing thread only.
 */
public final class Smoothness {

    private static final Logger log = LoggerFactory.getLogger(Smoothness.class);
    private static final long PROBE_MS = 10;
    private static final int REPORT_MS = 10_000;

    /** name -> {drawings, last drawing (ns), longest gap (ns)} */
    private static final Map<String, long[]> DRAWS = new LinkedHashMap<>();
    private static Timer probe;
    private static long lastProbe;
    private static long longestStall;

    private Smoothness() {
    }

    /** An output was drawn. */
    public static void drawn(String name) {
        long now = System.nanoTime();
        long[] s = DRAWS.computeIfAbsent(name, k -> new long[3]);
        s[0]++;
        if (s[1] != 0) {
            s[2] = Math.max(s[2], now - s[1]);
        }
        s[1] = now;
        start();
    }

    private static void start() {
        if (probe != null) {
            return;
        }
        // a 10 ms tick on the Swing thread: when it comes late, the thread was busy for that long
        probe = new Timer((int) PROBE_MS, e -> {
            long now = System.nanoTime();
            if (lastProbe != 0) {
                longestStall = Math.max(longestStall, now - lastProbe - PROBE_MS * 1_000_000);
            }
            lastProbe = now;
        });
        probe.start();
        Timer report = new Timer(REPORT_MS, e -> report());
        report.start();
    }

    private static void report() {
        StringBuilder line = new StringBuilder();
        DRAWS.forEach((name, s) -> {
            if (s[0] >= REPORT_MS / 1000 * 5) { // something moved: at least 5 drawings a second
                line.append(line.isEmpty() ? "" : "; ").append(name).append(' ')
                        .append(Math.round(s[0] * 1000.0 / REPORT_MS)).append(" draws/s, longest gap ")
                        .append(s[2] / 1_000_000).append(" ms");
            }
            s[0] = 0;
            s[2] = 0;
        });
        if (!line.isEmpty()) {
            log.info("Smoothness: {}; Swing thread longest stall {} ms", line, Math.max(0, longestStall / 1_000_000));
        }
        longestStall = 0;
    }
}
