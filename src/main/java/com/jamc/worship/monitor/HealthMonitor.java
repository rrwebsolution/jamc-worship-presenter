package com.jamc.worship.monitor;

import com.jamc.worship.camera.CameraStatus;
import com.jamc.worship.stream.StreamManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Production health from real readings: CPU and memory ({@link SystemSampler}), the livestream and the recording
 * ({@link StreamManager}), the cameras and the database. Works out Healthy / Warning / Critical against the
 * {@link HealthThresholds} and keeps a short list of recent alerts. Nothing here is measured by touching an
 * encoder, a camera or a player: it only reads what those already report. Used on the Swing thread.
 */
public final class HealthMonitor {

    public enum Level {
        HEALTHY("Healthy"), WARNING("Warning"), CRITICAL("Critical"), OFFLINE("Offline"), UNKNOWN("Unknown");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        int severity() {
            return switch (this) {
                case CRITICAL -> 2;
                case WARNING -> 1;
                default -> 0;
            };
        }
    }

    /** Something wrong right now; {@code key} stays the same while it lasts. */
    public record Issue(String key, Level level, String text) {
    }

    /** An entry in the recent alerts: a problem that started (WARNING / CRITICAL) or ended (HEALTHY). */
    public record Alert(long at, Level level, String text) {
    }

    /** A camera as the camera manager reports it. */
    public record Camera(String name, CameraStatus status, String size, int fps, String problem) {
        public boolean connected() {
            return status != null && !status.isOffline();
        }
    }

    /** Everything read at one moment. */
    public record Inputs(SystemSampler.Reading system, StreamManager.Session live, StreamManager.Session recording,
                         List<Camera> cameras, boolean databaseOk) {
    }

    /**
     * The result of one update. Drop percentages are 0–100, or NaN while nothing is encoded. {@code cpuAverage}
     * is NaN until the CPU has been watched for the sustain time.
     */
    public record Report(long at, Level level, List<Issue> issues, Inputs in, double cpuAverage,
                         double liveDropTotal, double liveDropRecent, double recDropTotal, double recDropRecent) {

        public long camerasConnected() {
            return in.cameras().stream().filter(Camera::connected).count();
        }

        public int camerasTotal() {
            return in.cameras().size();
        }

        public static Report unknown() {
            Inputs none = new Inputs(SystemSampler.Reading.UNKNOWN, null, null, List.of(), true);
            return new Report(0, Level.UNKNOWN, List.of(), none, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    /** Recent dropped frames are counted over this window. */
    static final long DROP_WINDOW_MS = 30_000;
    /** A livestream still connecting (or reconnecting) after this long is a Warning. */
    static final long CONNECTING_MS = 20_000;
    /** The same problem is announced again only after this long (no repeated notifications). */
    static final long REPEAT_MS = 5 * 60_000;
    static final int MAX_ALERTS = 40;

    private final Supplier<HealthThresholds> thresholds;
    private final Deque<double[]> cpu = new ArrayDeque<>();
    private final DropWindow liveDrops = new DropWindow();
    private final DropWindow recDrops = new DropWindow();
    private long connectingSince;
    private SystemSampler.Reading lastSystem;
    private final Map<String, Issue> active = new LinkedHashMap<>();
    private final Map<String, Long> announced = new HashMap<>();
    private final Map<String, Level> announcedLevel = new HashMap<>();
    private final Deque<Alert> alerts = new ArrayDeque<>();
    private Report last = Report.unknown();
    private Consumer<Alert> onAlert = a -> { };

    public HealthMonitor(Supplier<HealthThresholds> thresholds) {
        this.thresholds = thresholds;
    }

    /** Told about each new problem once (not again for {@link #REPEAT_MS} while it comes and goes). */
    public void setAlertListener(Consumer<Alert> listener) {
        onAlert = listener == null ? a -> { } : listener;
    }

    public Report last() {
        return last;
    }

    /** Recent alerts, newest first. */
    public List<Alert> alerts() {
        return List.copyOf(alerts);
    }

    public Report update(Inputs in, long now) {
        HealthThresholds t = thresholds.get();
        List<Issue> issues = new ArrayList<>();

        // CPU, sustained: short spikes (opening a video, a scene change) are normal
        double cpuAvg = Double.NaN;
        long sustainMs = t.sustainSeconds() * 1000L;
        if (!Double.isNaN(in.system().cpu()) && in.system() != lastSystem) { // one entry per reading
            cpu.addLast(new double[]{now, in.system().cpu()});
        }
        lastSystem = in.system();
        while (!cpu.isEmpty() && cpu.peekFirst()[0] < now - sustainMs) {
            cpu.removeFirst();
        }
        if (!cpu.isEmpty() && now - cpu.peekFirst()[0] >= sustainMs * 3 / 4) {
            cpuAvg = cpu.stream().mapToDouble(s -> s[1]).average().orElse(Double.NaN) * 100;
            if (cpuAvg >= t.cpuCritical()) {
                issues.add(new Issue("cpu", Level.CRITICAL, String.format("CPU very high: %.0f%% for %d s", cpuAvg, t.sustainSeconds())));
            } else if (cpuAvg >= t.cpuWarning()) {
                issues.add(new Issue("cpu", Level.WARNING, String.format("CPU high: %.0f%% for %d s", cpuAvg, t.sustainSeconds())));
            }
        }

        // memory
        SystemSampler.Reading s = in.system();
        if (s.ramTotal() > 0 && s.ramUsed() >= 0) {
            double pct = 100.0 * s.ramUsed() / s.ramTotal();
            if (pct >= t.ramWarning()) {
                issues.add(new Issue("ram", Level.WARNING, String.format("Memory almost full: %.0f%% in use", pct)));
            }
        }

        // livestream: stopping on purpose (IDLE) is not a problem
        StreamManager.Session live = in.live();
        double[] liveDrop = liveDrops.update(live, now);
        if (live != null) {
            if (live.state() == StreamManager.State.ERROR) {
                issues.add(new Issue("stream", Level.CRITICAL, "Livestream stopped: " + orElse(live.detail(), "the connection failed")));
            } else if (live.state() == StreamManager.State.STARTING && !live.finishing()) {
                connectingSince = connectingSince == 0 ? now : connectingSince;
                String d = live.detail() == null ? "" : live.detail();
                if (d.startsWith("Reconnecting") || now - connectingSince >= CONNECTING_MS) {
                    issues.add(new Issue("stream-connect", Level.WARNING, "Livestream " + (d.isEmpty() ? "still connecting" : d.toLowerCase())));
                }
            }
            if (live.state() != StreamManager.State.STARTING) {
                connectingSince = 0;
            }
        }
        dropIssue(issues, "stream-drops", "Livestream", liveDrop[1], t);

        // recording
        StreamManager.Session rec = in.recording();
        double[] recDrop = recDrops.update(rec, now);
        if (rec != null && rec.state() == StreamManager.State.ERROR) {
            issues.add(new Issue("recording", Level.CRITICAL, "Recording stopped: " + orElse(rec.detail(), "an error")));
        }
        dropIssue(issues, "recording-drops", "Recording", recDrop[1], t);

        // cameras
        for (Camera c : in.cameras()) {
            if (c.status() == CameraStatus.ERROR) {
                issues.add(new Issue("camera:" + c.name(), Level.CRITICAL, "Camera \"" + c.name() + "\" has an error"
                        + (c.problem() == null ? "" : ": " + c.problem())));
            } else if (c.status() == CameraStatus.DISCONNECTED) {
                issues.add(new Issue("camera:" + c.name(), Level.WARNING, "Camera \"" + c.name() + "\" disconnected"));
            }
        }

        if (!in.databaseOk()) {
            issues.add(new Issue("database", Level.CRITICAL, "The database has a problem"));
        }

        Level level = Level.HEALTHY;
        for (Issue i : issues) {
            if (i.level().severity() > level.severity()) {
                level = i.level();
            }
        }
        track(issues, now);
        last = new Report(now, level, List.copyOf(issues), in, cpuAvg, liveDrop[0], liveDrop[1], recDrop[0], recDrop[1]);
        return last;
    }

    private static void dropIssue(List<Issue> issues, String key, String what, double recent, HealthThresholds t) {
        if (Double.isNaN(recent)) {
            return;
        }
        if (recent >= t.droppedCritical()) {
            issues.add(new Issue(key, Level.CRITICAL, String.format("%s dropping frames: %.1f%% in the last 30 s", what, recent)));
        } else if (recent >= t.droppedWarning()) {
            issues.add(new Issue(key, Level.WARNING, String.format("%s dropping frames: %.1f%% in the last 30 s", what, recent)));
        }
    }

    /** New or worse problems become alerts (each announced once per {@link #REPEAT_MS}); ended ones are noted. */
    private void track(List<Issue> issues, long now) {
        Map<String, Issue> current = new LinkedHashMap<>();
        issues.forEach(i -> current.put(i.key(), i));
        for (Issue i : current.values()) {
            Issue before = active.get(i.key());
            boolean worse = before != null && i.level().severity() > before.level().severity();
            if (before == null || worse) {
                Long when = announced.get(i.key());
                Level told = announcedLevel.get(i.key());
                boolean worseThanTold = told != null && i.level().severity() > told.severity();
                if (worse || worseThanTold || when == null || now - when >= REPEAT_MS) {
                    announced.put(i.key(), now);
                    announcedLevel.put(i.key(), i.level());
                    Alert a = new Alert(now, i.level(), i.text());
                    add(a);
                    onAlert.accept(a);
                }
            }
        }
        for (Issue gone : active.values()) {
            if (!current.containsKey(gone.key()) && announced.containsKey(gone.key())
                    && now - announced.get(gone.key()) < REPEAT_MS * 2) {
                add(new Alert(now, Level.HEALTHY, "Resolved: " + gone.text()));
            }
        }
        active.clear();
        active.putAll(current);
    }

    private void add(Alert a) {
        alerts.addFirst(a);
        while (alerts.size() > MAX_ALERTS) {
            alerts.removeLast();
        }
    }

    private static String orElse(String s, String other) {
        return s == null || s.isBlank() ? other : s;
    }

    /** Dropped frames of one encoder: in total and over the last {@link #DROP_WINDOW_MS}. */
    static final class DropWindow {
        private final Deque<long[]> points = new ArrayDeque<>();
        private long session;

        /** {total %, recent %}; NaN while nothing is being encoded or too little to tell. */
        double[] update(StreamManager.Session s, long now) {
            if (s == null || s.state() != StreamManager.State.ACTIVE || s.stats() == null) {
                points.clear();
                session = 0;
                return new double[]{Double.NaN, Double.NaN};
            }
            if (s.startedMillis() != session) {
                points.clear(); // a new stream / recording
                session = s.startedMillis();
            }
            long frames = s.stats().frames();
            long dropped = s.stats().droppedFrames();
            points.addLast(new long[]{now, frames, dropped});
            while (points.size() > 1 && points.peekFirst()[0] < now - DROP_WINDOW_MS) {
                points.removeFirst();
            }
            double total = frames + dropped > 0 ? 100.0 * dropped / (frames + dropped) : Double.NaN;
            long[] first = points.peekFirst();
            long df = frames - first[1];
            long dd = dropped - first[2];
            double recent = df + dd >= 30 && dd >= 0 ? 100.0 * dd / (df + dd) : Double.NaN;
            return new double[]{total, recent};
        }
    }
}
