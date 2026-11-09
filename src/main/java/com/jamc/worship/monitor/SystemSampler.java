package com.jamc.worship.monitor;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Measures CPU and memory on a background thread (never the Swing thread) and hands each reading on.
 * Only reads operating-system counters: cameras, encoders and players are never touched.
 */
public final class SystemSampler {

    /**
     * One reading. CPU values are 0–1, or NaN when the system does not report them; memory sizes are
     * bytes, or -1 when unknown.
     */
    public record Reading(double cpu, double appCpu, int cores, long ramUsed, long ramTotal,
                          long heapUsed, long heapMax) {
        public static final Reading UNKNOWN = new Reading(Double.NaN, Double.NaN, 0, -1, -1, -1, -1);
    }

    private ScheduledExecutorService timer;

    /** Starts reading every {@code periodMs}; {@code sink} is called on the sampler thread. */
    public synchronized void start(long periodMs, Consumer<Reading> sink) {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-sampler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                sink.accept(read());
            } catch (Throwable ignored) {
                // a failed reading shows N/A; the next one tries again
            }
        }, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    /** Reads the counters now (blocking for a moment on some systems). */
    public static Reading read() {
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory() == Long.MAX_VALUE ? -1 : rt.maxMemory();
        int cores = rt.availableProcessors();
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            return new Reading(load(os.getCpuLoad()), load(os.getProcessCpuLoad()), cores,
                    total > 0 && free >= 0 ? total - free : -1, total > 0 ? total : -1, heapUsed, heapMax);
        }
        return new Reading(Double.NaN, Double.NaN, cores, -1, -1, heapUsed, heapMax);
    }

    /** The MXBean reports a negative value when it has no reading (yet). */
    private static double load(double v) {
        return v < 0 || Double.isNaN(v) ? Double.NaN : Math.min(1, v);
    }
}
