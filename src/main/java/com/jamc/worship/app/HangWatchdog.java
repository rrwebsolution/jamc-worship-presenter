package com.jamc.worship.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Writes down why the app froze. A background thread asks the Swing thread for a heartbeat every half second; when no
 * heartbeat comes back for {@link #HANG_MS}, every thread's stack (with locks and any deadlock) goes into the log file,
 * once per freeze, and a second line says how long the freeze lasted when the Swing thread is back. A freeze that ends
 * with Windows closing the app, or with the operator closing it, then still leaves the cause in the log.
 */
public final class HangWatchdog {

    private static final Logger log = LoggerFactory.getLogger(HangWatchdog.class);
    private static final long CHECK_MS = 500;
    private static final long HANG_MS = 3_000;
    /** A second dump during the same long freeze, to tell a stuck thread from a slow one. */
    private static final long SECOND_DUMP_MS = 15_000;
    private static final long HEALTH_MS = 60_000;

    private static volatile long lastBeat = System.nanoTime();
    private static volatile boolean beatQueued;
    /**
     * While the main window is being built (splash on screen) the Swing thread is busy on purpose: 2 s normally, about
     * 4 s when started from VS Code. That was logged as "App is frozen" with a thread dump at every start. Until
     * {@link #startupDone()} only a really stuck start is reported.
     */
    private static final long STARTUP_HANG_MS = 20_000;
    private static volatile boolean starting = true;

    private HangWatchdog() {
    }

    /** The main window is shown: from now on a Swing thread silent for {@link #HANG_MS} is a freeze. */
    public static void startupDone() {
        lastBeat = System.nanoTime();
        starting = false;
    }

    public static void start() {
        Thread t = new Thread(HangWatchdog::run, "swing-watchdog");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private static void run() {
        long frozenSince = 0;
        int dumps = 0;
        long lastHealth = System.nanoTime();
        logHealth();
        while (true) {
            try {
                Thread.sleep(CHECK_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (System.nanoTime() - lastHealth >= HEALTH_MS * 1_000_000) {
                lastHealth = System.nanoTime();
                logHealth();
            }
            if (!beatQueued) {
                beatQueued = true;
                SwingUtilities.invokeLater(() -> {
                    lastBeat = System.nanoTime();
                    beatQueued = false;
                });
            }
            long silentMs = (System.nanoTime() - lastBeat) / 1_000_000;
            boolean opening = starting;
            if (silentMs >= (opening ? STARTUP_HANG_MS : HANG_MS)) {
                if (frozenSince == 0) {
                    frozenSince = lastBeat;
                }
                if (dumps == 0 || (dumps == 1 && silentMs >= SECOND_DUMP_MS + (opening ? STARTUP_HANG_MS : 0))) {
                    dumps++;
                    log.warn(opening ? "LiveCat is stuck while opening: the Swing thread has not answered for {} ms. Thread dump {}:\n{}"
                                    : "App is frozen: the Swing thread has not answered for {} ms. Thread dump {}:\n{}",
                            silentMs, dumps, threadDump());
                }
            } else if (frozenSince != 0) {
                log.warn("App answers again after a freeze of {} ms", (lastBeat - frozenSince) / 1_000_000);
                frozenSince = 0;
                dumps = 0;
            }
        }
    }

    /** Windows calls for the counters that end a process when they run out (10,000 GDI / USER objects each). */
    private interface Win extends com.sun.jna.Library {
        Win USER32 = load("user32");
        Win KERNEL32 = load("kernel32");

        private static Win load(String dll) {
            try {
                return com.sun.jna.Native.load(dll, Win.class);
            } catch (Throwable e) {
                return null;
            }
        }

        int GetGuiResources(com.sun.jna.Pointer process, int flags); // user32: 0 = GDI, 1 = USER

        boolean GetProcessHandleCount(com.sun.jna.Pointer process, com.sun.jna.ptr.IntByReference count); // kernel32
    }

    private static final com.sun.jna.Pointer THIS_PROCESS = new com.sun.jna.Pointer(-1); // GetCurrentProcess()
    private static final int GDI_WARN = 8_000;

    /**
     * One line a minute with what grows when the app is used for a long time. Comparing the lines before a freeze
     * shows which one ran away: memory, threads, Windows handles, or the GDI / USER objects behind windows and images.
     */
    private static void logHealth() {
        try {
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
            long maxMb = rt.maxMemory() >> 20;
            int threads = ManagementFactory.getThreadMXBean().getThreadCount();
            int gdi = -1;
            int user = -1;
            int handles = -1;
            if (Win.USER32 != null) {
                gdi = Win.USER32.GetGuiResources(THIS_PROCESS, 0);
                user = Win.USER32.GetGuiResources(THIS_PROCESS, 1);
            }
            if (Win.KERNEL32 != null) {
                com.sun.jna.ptr.IntByReference n = new com.sun.jna.ptr.IntByReference();
                if (Win.KERNEL32.GetProcessHandleCount(THIS_PROCESS, n)) {
                    handles = n.getValue();
                }
            }
            int windows = java.awt.Window.getWindows().length;
            String line = "Health: memory " + usedMb + "/" + maxMb + " MB, threads " + threads + ", windows " + windows
                    + ", GDI " + gdi + ", USER " + user + ", handles " + handles;
            if (gdi >= GDI_WARN || user >= GDI_WARN || usedMb > maxMb * 9 / 10) {
                log.warn("{} - close to the Windows / memory limit, the app may freeze or close", line);
            } else {
                log.info(line);
            }
        } catch (Throwable e) {
            log.debug("Health line failed", e);
        }
    }

    static String threadDump() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StringBuilder out = new StringBuilder();
        try {
            long[] deadlocked = mx.findDeadlockedThreads();
            if (deadlocked != null) {
                out.append("DEADLOCK between threads:");
                for (ThreadInfo d : mx.getThreadInfo(deadlocked)) {
                    if (d != null) {
                        out.append(" \"").append(d.getThreadName()).append('"');
                    }
                }
                out.append('\n');
            }
            ThreadInfo[] all = mx.dumpAllThreads(mx.isObjectMonitorUsageSupported(), mx.isSynchronizerUsageSupported());
            // the Swing thread first: that is the one that froze
            for (ThreadInfo info : all) {
                if (info.getThreadName().startsWith("AWT-EventQueue")) {
                    append(out, info);
                }
            }
            for (ThreadInfo info : all) {
                if (!info.getThreadName().startsWith("AWT-EventQueue")) {
                    append(out, info);
                }
            }
        } catch (RuntimeException | Error e) {
            out.append("(thread dump failed: ").append(e).append(')');
        }
        return out.toString();
    }

    /** Like ThreadInfo.toString but with the whole stack, not just the first 8 frames. */
    private static void append(StringBuilder out, ThreadInfo info) {
        out.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
        if (info.getLockName() != null) {
            out.append(" on ").append(info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            out.append(" owned by \"").append(info.getLockOwnerName()).append('"');
        }
        if (info.isInNative()) {
            out.append(" (in native)");
        }
        out.append('\n');
        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();
        for (int i = 0; i < stack.length; i++) {
            out.append("\tat ").append(stack[i]).append('\n');
            for (MonitorInfo m : monitors) {
                if (m.getLockedStackDepth() == i) {
                    out.append("\t- locked ").append(m).append('\n');
                }
            }
        }
        for (LockInfo l : info.getLockedSynchronizers()) {
            out.append("\t- holds ").append(l).append('\n');
        }
        out.append('\n');
    }
}
