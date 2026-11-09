package com.jamc.worship.monitor;

import com.jamc.worship.config.AppConfig;

/**
 * When the footer's health turns Warning or Critical (Settings → Health Monitor). Percentages are 0–100.
 *
 * @param cpuWarning      whole-computer CPU, averaged over {@code sustainSeconds}, that is a Warning
 * @param cpuCritical     … that is Critical
 * @param sustainSeconds  how long the CPU must stay high before it counts (short spikes are normal)
 * @param droppedWarning  dropped frames in the stream / recording over the last 30 s that is a Warning
 * @param droppedCritical … that is Critical
 * @param ramWarning      memory in use on the computer that is a Warning
 */
public record HealthThresholds(int cpuWarning, int cpuCritical, int sustainSeconds,
                               double droppedWarning, double droppedCritical, int ramWarning) {

    public static final HealthThresholds DEFAULTS = new HealthThresholds(85, 95, 20, 1.0, 5.0, 90);

    private static final String P = "health.";

    public static HealthThresholds load(AppConfig cfg) {
        HealthThresholds d = DEFAULTS;
        int warn = clamp(i(cfg, "cpuWarning", d.cpuWarning), 10, 100);
        double dWarn = clamp(dbl(cfg, "droppedWarning", d.droppedWarning), 0.1, 100);
        return new HealthThresholds(warn,
                clamp(i(cfg, "cpuCritical", d.cpuCritical), warn, 100),
                clamp(i(cfg, "sustainSeconds", d.sustainSeconds), 2, 600),
                dWarn,
                clamp(dbl(cfg, "droppedCritical", d.droppedCritical), dWarn, 100),
                clamp(i(cfg, "ramWarning", d.ramWarning), 10, 100));
    }

    public void save(AppConfig cfg) {
        cfg.set(P + "cpuWarning", Integer.toString(cpuWarning));
        cfg.set(P + "cpuCritical", Integer.toString(cpuCritical));
        cfg.set(P + "sustainSeconds", Integer.toString(sustainSeconds));
        cfg.set(P + "droppedWarning", Double.toString(droppedWarning));
        cfg.set(P + "droppedCritical", Double.toString(droppedCritical));
        cfg.set(P + "ramWarning", Integer.toString(ramWarning));
        cfg.save();
    }

    private static int i(AppConfig cfg, String key, int def) {
        try {
            return Integer.parseInt(cfg.get(P + key, Integer.toString(def)).strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double dbl(AppConfig cfg, String key, double def) {
        try {
            return Double.parseDouble(cfg.get(P + key, Double.toString(def)).strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
