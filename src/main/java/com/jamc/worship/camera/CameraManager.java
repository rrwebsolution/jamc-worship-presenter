package com.jamc.worship.camera;

import com.jamc.worship.media.MediaEngine;
import com.jamc.worship.media.VideoPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every physical camera in one place: detection, one shared session per device, status, hot plug and audio.
 *
 * <p>A session is opened once per device and shared by every scene, Preview, Program, the multiview and the
 * scene editor that show that camera, so switching scenes never closes and reopens a camera (no black frames,
 * no re-initialising). Cameras used by the Preview or Program scene are kept open ({@link #keepOpen}) even while
 * their source is hidden; other sessions close after a few seconds unseen, so an unused camera never stays on.
 *
 * <p>One camera failing never affects another: each session has its own VLC player. A camera that stops
 * sending pictures is re-detected; if it was unplugged it shows as DISCONNECTED and is reopened by itself
 * when it comes back.
 *
 * <p>Capture uses VLC's DirectShow input. The {@link CameraProvider} seam keeps the capture method replaceable
 * (PTZ / network cameras, NDI...) without touching scenes or the compositor. EDT only, except {@link #detect()}.
 */
public final class CameraManager {

    private static final Logger log = LoggerFactory.getLogger(CameraManager.class);
    /** A session nobody has shown for this long closes (unless Preview / Program keeps it open). */
    static final long IDLE_MS = 10_000;
    /** A camera that sent pictures and then none for this long is checked for being unplugged. */
    static final long STALL_MS = 3_000;
    /** A failed camera is tried again after this long while something still wants it. */
    static final long RETRY_MS = 10_000;
    /** How often detection runs while a wanted camera is missing (hot plug). */
    static final long RECHECK_MS = 8_000;

    /** A way of turning a camera name into a running video feed. */
    public interface CameraProvider {
        VideoPlayer open(String cameraName);
    }

    /** A running picture from one device: a VLC player in the app, a fake in tests. */
    interface Feed {
        BufferedImage frame();

        /** Frames received so far. */
        long frames();

        boolean failed();

        void release();

        /** Stands in while the previous feed is still closing: no picture yet, not failed. */
        Feed NONE = new Feed() {
            @Override public BufferedImage frame() { return null; }
            @Override public long frames() { return 0; }
            @Override public boolean failed() { return false; }
            @Override public void release() { }
        };

        static Feed of(VideoPlayer p) {
            return new Feed() {
                @Override public BufferedImage frame() { return p.currentFrame(); }
                @Override public long frames() { return p.frameCount(); }
                @Override public boolean failed() { return p.hasError(); }
                @Override public void release() { p.release(); }
            };
        }
    }

    /** One way of opening a camera: a capture size ("1280x720", null = the driver's default) and frame rate (0 = default). */
    record Format(String size, int fps) {
    }

    /** What detection found: camera / capture devices and audio capture inputs (microphones, HDMI audio). */
    public record Detection(List<String> video, List<String> audio) {
    }

    /** The sizes ("1920x1080", largest first) and frame rates a camera reports. */
    public record Capabilities(List<String> sizes, List<Integer> rates) {
    }

    /**
     * Sizes tried, best first, when no size is chosen ("Auto"). Left to itself DirectShow often opens a
     * webcam at its smallest size (e.g. 320×180, which looks blurry); asking for a size the camera does
     * not have gives no picture at all, so each one is tried in turn.
     */
    static final List<String> AUTO_SIZES = List.of("1920x1080", "1280x720");
    /** How long a format may take to show its first picture before the next one is tried. */
    static final long FALLBACK_MS = 3_500;

    /** One open camera device. */
    private static final class Session {
        Feed feed;
        long lastUsed;
        long started;
        final List<Format> formats;
        int attempt;
        /** Frame count last seen, and when it last went up. */
        long seenFrames;
        long lastFrameAt;
        boolean gotFrame;
        /** Every format was tried without a picture: the camera shows ERROR until the next retry. */
        boolean failed;
        String problem;
        long fpsFrames;
        long fpsAt;
        int fps;
        /** The next format ({@link #attempt}) opens once the previous feed has closed (or after a time limit). */
        boolean reopenPending;
        long reopenSince;

        Session(Feed feed, List<Format> formats, long now) {
            this.feed = feed;
            this.formats = formats;
            this.started = now;
            this.fpsAt = now;
        }
    }

    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Set<String>> kept = new HashMap<>();
    /** Cameras something asked for while they were missing, and when (they are looked for again). */
    private final Map<String, Long> wanted = new HashMap<>();
    /** Wanted cameras the last detection did not find. */
    private final Set<String> missing = new HashSet<>();
    /** Requested capture format per camera (null size = Auto). */
    private final Map<String, Format> requested = new ConcurrentHashMap<>();
    /** The format that actually gave a picture, per camera, so a restart opens it straight away. */
    private final Map<String, Format> working = new ConcurrentHashMap<>();
    private final Map<String, Capabilities> capabilities = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Timer sweeper = new Timer(2_000, e -> sweep());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "camera-detect");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean detecting = new AtomicBoolean();
    /**
     * Closes camera feeds off the Swing thread. Stopping a VLC DirectShow player waits for the driver, and a camera
     * that is still starting (OBS Virtual Camera while OBS initializes, a phone camera connecting) can hold that for
     * many seconds: on the Swing thread the whole app froze. One thread per close, so a stuck one never holds others.
     */
    private final ExecutorService releaser = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "camera-release");
        t.setDaemon(true);
        return t;
    });
    /** Cameras with a feed still closing (count per camera), EDT only: not opened again until done. */
    private final Map<String, Integer> closing = new HashMap<>();
    /** False in tests (fake feeds close at once). */
    private final boolean asyncRelease;
    private final BiFunction<String, Format, Feed> opener;
    private final Supplier<Detection> detector;
    private final BooleanSupplier available;
    private final boolean fallback;
    private volatile List<String> cameras = List.of();
    private volatile List<String> audioInputs = List.of();
    /** Cameras being closed by a refresh: not opened again until that is done. */
    private final java.util.Set<String> restarting = new java.util.HashSet<>();
    private static final int REFRESH_WAIT_MS = 5000;
    private volatile boolean detected;
    private volatile long lastDetect;
    private volatile Function<String, String> audioOverride = name -> null;
    private boolean watching;

    public CameraManager() {
        this((name, format) -> Feed.of(openDirectShow(name, format)), CameraManager::detectWindows,
                () -> MediaEngine.get().isAvailable(), true, true);
    }

    public CameraManager(CameraProvider provider) {
        this((name, format) -> Feed.of(provider.open(name)), CameraManager::detectWindows,
                () -> MediaEngine.get().isAvailable(), false, true);
    }

    /** For tests: fake feeds and detection. */
    CameraManager(BiFunction<String, Format, Feed> opener, Supplier<Detection> detector, BooleanSupplier available,
                  boolean fallback) {
        this(opener, detector, available, fallback, false);
    }

    CameraManager(BiFunction<String, Format, Feed> opener, Supplier<Detection> detector, BooleanSupplier available,
                  boolean fallback, boolean asyncRelease) {
        this.opener = opener;
        this.detector = detector;
        this.available = available;
        this.fallback = fallback;
        this.asyncRelease = asyncRelease;
        sweeper.start();
    }

    /** Closes a feed without blocking the Swing thread; the camera is not reopened until it is closed. */
    private void release(String cam, Feed feed) {
        if (feed == null || feed == Feed.NONE) {
            return;
        }
        if (!asyncRelease) {
            feed.release();
            return;
        }
        if (cam != null) {
            closing.merge(cam, 1, Integer::sum);
        }
        releaser.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                feed.release();
            } catch (RuntimeException e) {
                log.warn("Camera {} did not close cleanly", cam, e);
            }
            long took = System.currentTimeMillis() - t0;
            if (took > 1_000) {
                log.info("Camera {} took {} ms to close", cam, took);
            }
            if (cam != null) {
                onEdt(() -> {
                    closing.computeIfPresent(cam, (k, n) -> n > 1 ? n - 1 : null);
                    fire();
                });
            }
        });
    }

    /** True while an earlier feed of this camera is closing (a device still closing refuses to open). */
    private boolean isClosing(String cam) {
        return closing.containsKey(cam);
    }

    private static VideoPlayer openDirectShow(String name, Format format) {
        List<String> options = new ArrayList<>(List.of(":dshow-vdev=" + name, ":dshow-adev=none", ":live-caching=150"));
        if (format.size() != null) {
            options.add(":dshow-size=" + format.size());
        }
        if (format.fps() > 0) {
            options.add(":dshow-fps=" + format.fps());
        }
        String chroma = chroma(name, format);
        if (chroma != null) {
            options.add(":dshow-chroma=" + chroma);
        }
        VideoPlayer p = new VideoPlayer();
        p.play("dshow://", false, true, options.toArray(String[]::new));
        return p;
    }

    /**
     * Pixel format to ask the camera for, or null for VLC's choice. Iriun Webcam's default format comes out
     * with red and blue swapped (a blue picture: white walls blue, a blue water jug orange); asking for
     * YUY2 gives the true colors. The driver's default (no size, no rate) stays as the last try, so the
     * camera always shows something.
     */
    static String chroma(String name, Format format) {
        boolean iriun = name != null && name.toLowerCase(Locale.ROOT).contains("iriun");
        boolean lastResort = format.size() == null && format.fps() == 0;
        return iriun && !lastResort ? "YUY2" : null;
    }

    /**
     * The formats to try for a camera, in order: the chosen one (or, for Auto, the best sizes first), then
     * the driver's default so there is always a picture if the camera works at all.
     */
    static List<Format> candidates(Format requested, Format working) {
        List<Format> out = new ArrayList<>();
        int fps = requested == null ? 0 : requested.fps();
        if (requested != null && requested.size() != null) {
            out.add(requested); // the operator's choice first; if the camera lacks it, the best sizes below
        } else if (working != null && working.fps() == fps) {
            out.add(working); // what worked last time
        }
        AUTO_SIZES.forEach(s -> out.add(new Format(s, fps)));
        if (fps > 0) {
            out.add(new Format(null, fps));
        }
        out.add(new Format(null, 0));
        return out.stream().distinct().toList();
    }

    // ------------------------------------------------------------------ devices

    /** Last detected camera names (call {@link #detect()} to refresh). */
    public List<String> cameras() {
        return cameras;
    }

    /** Last detected audio capture inputs (microphones, capture-card HDMI audio). */
    public List<String> audioInputs() {
        return audioInputs;
    }

    /**
     * Lists connected cameras and audio inputs. Blocking (runs PowerShell on Windows): call from a background
     * thread. Printers and scanners that Windows files under "Image" devices are excluded. Cameras that were
     * in use and are now missing show as DISCONNECTED; cameras that came back are reopened.
     */
    public List<String> detect() {
        Detection d;
        try {
            d = detector.get();
        } catch (RuntimeException e) {
            log.warn("Camera detection failed", e);
            d = new Detection(cameras, audioInputs);
        }
        List<String> before = cameras;
        cameras = List.copyOf(d.video());
        audioInputs = List.copyOf(d.audio());
        detected = true;
        lastDetect = System.currentTimeMillis();
        if (!before.equals(cameras)) {
            log.info("Cameras detected: {}", cameras);
        }
        onEdt(this::detectionChanged);
        return cameras;
    }

    /** Detection in the background (at most one at a time); listeners hear about any change. */
    public void detectAsync() {
        if (!detecting.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                detect();
            } finally {
                detecting.set(false);
            }
        });
    }

    /** EDT: works out which wanted cameras are gone or back. */
    private void detectionChanged() {
        Set<String> nowMissing = new HashSet<>();
        for (String cam : wantedNames()) {
            if (!cameras.contains(cam)) {
                nowMissing.add(cam);
            }
        }
        for (String cam : nowMissing) {
            Session s = sessions.remove(cam);
            if (s != null) {
                release(cam, s.feed);
            }
            if (!missing.contains(cam)) {
                log.warn("Camera disconnected: {}", cam);
            }
        }
        for (String cam : missing) {
            if (!nowMissing.contains(cam)) {
                log.info("Camera reconnected: {}", cam);
                Session s = sessions.remove(cam); // a stale session from before the unplug
                if (s != null) {
                    release(cam, s.feed);
                }
            }
        }
        missing.clear();
        missing.addAll(nowMissing);
        prewarm();
        fire();
    }

    private Set<String> wantedNames() {
        Set<String> out = new LinkedHashSet<>(sessions.keySet());
        kept.values().forEach(out::addAll);
        out.addAll(wanted.keySet());
        return out;
    }

    /** The microphone / HDMI audio input that belongs to this camera, or null when it has none. */
    public String audioDevice(String name) {
        String cam = resolve(name);
        if (cam == null) {
            return null;
        }
        String chosen = audioOverride.apply(cam);
        if (chosen != null) {
            return chosen.isBlank() ? null : chosen;
        }
        return matchAudio(cam, audioInputs);
    }

    /**
     * The operator's audio input per camera: returns a device name, "" for none, or null for automatic.
     */
    public void setAudioOverride(Function<String, String> override) {
        this.audioOverride = override == null ? name -> null : override;
    }

    /**
     * The audio input whose name carries the camera's name, e.g. "Digital Audio Interface (USB Capture HDMI)"
     * for "USB Capture HDMI". A laptop's built-in camera usually has no match (its microphone is separate).
     */
    static String matchAudio(String camera, List<String> inputs) {
        String c = camera.toLowerCase(Locale.ROOT);
        for (String a : inputs) {
            String l = a.toLowerCase(Locale.ROOT);
            int open = l.indexOf('(');
            int close = l.lastIndexOf(')');
            String inner = open >= 0 && close > open ? l.substring(open + 1, close).strip() : l;
            if (inner.equals(c) || inner.contains(c) || (inner.length() >= 6 && c.contains(inner))) {
                return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ status

    /** The device state: INITIALIZING, READY, DISCONNECTED or ERROR (the UI adds PREVIEW / LIVE). */
    public CameraStatus status(String name) {
        String cam = resolve(name);
        if (cam == null) {
            return detected ? CameraStatus.DISCONNECTED : CameraStatus.INITIALIZING;
        }
        if (missing.contains(cam) || (detected && !cameras.contains(cam))) {
            return CameraStatus.DISCONNECTED;
        }
        if (restarting.contains(cam)) {
            return CameraStatus.INITIALIZING;
        }
        Session s = sessions.get(cam);
        if (s == null) {
            return CameraStatus.READY;
        }
        if (s.failed) {
            return CameraStatus.ERROR;
        }
        if (!s.gotFrame || System.currentTimeMillis() - s.lastFrameAt > STALL_MS) {
            return CameraStatus.INITIALIZING;
        }
        return CameraStatus.READY;
    }

    /** Why a camera shows ERROR or DISCONNECTED, in words for the operator; null when it is fine. */
    public String problem(String name) {
        String cam = resolve(name);
        CameraStatus st = status(name);
        if (st == CameraStatus.DISCONNECTED) {
            return cam == null ? "No camera is connected."
                    : "\"" + cam + "\" is not connected. Check its cable; it is reopened by itself when it comes back.";
        }
        Session s = cam == null ? null : sessions.get(cam);
        return st == CameraStatus.ERROR && s != null ? s.problem : null;
    }

    /** True when a camera's failure looks like too many cameras on one USB controller. */
    public boolean bandwidthWarning(String name) {
        String cam = resolve(name);
        Session s = cam == null ? null : sessions.get(cam);
        return s != null && s.failed && s.problem != null && s.problem.startsWith(BANDWIDTH);
    }

    static final String BANDWIDTH = "CAMERA BANDWIDTH WARNING";

    /** The picture size the camera is delivering now, e.g. "1280×720", or null while it starts. */
    public String liveSize(String name) {
        String cam = resolve(name);
        Session s = cam == null ? null : sessions.get(cam);
        BufferedImage img = s == null ? null : s.feed.frame();
        return img == null ? null : img.getWidth() + "×" + img.getHeight();
    }

    /** Frames per second the camera is delivering now (measured), or 0 while it starts or is closed. */
    public int liveFps(String name) {
        String cam = resolve(name);
        Session s = cam == null ? null : sessions.get(cam);
        return s == null || !s.gotFrame ? 0 : s.fps;
    }

    /** True while the camera has an open session (its picture is flowing or starting). */
    public boolean isOpen(String name) {
        String cam = resolve(name);
        return cam != null && sessions.containsKey(cam);
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public void removeListener(Runnable l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------ format / capabilities

    /**
     * Asks the camera for a resolution ("1920x1080", null = Auto: the best size it supports) and frame
     * rate (0 = default). A running feed restarts with the new format; if the camera cannot deliver it,
     * the next best format is used instead of showing nothing.
     */
    public void setFormat(String name, String size, int fps) {
        if (name == null) {
            return;
        }
        Format now = new Format(size, fps);
        Format old = requested.put(name, now);
        if (now.equals(old) || (old == null && size == null && fps == 0)) {
            return;
        }
        working.remove(name);
        Session s = sessions.remove(name);
        if (s != null) {
            release(name, s.feed); // reopened with the new format on the next frame request
            log.info("Camera {} restarting at {} / {} fps", name, size == null ? "Auto" : size, fps == 0 ? "default" : fps);
        }
    }

    /**
     * REFRESH: closes this camera and opens it again, for when it shows nothing. Only this camera is touched. Closing runs
     * off the Swing thread (a stuck driver can hold it for half a minute), and the camera opens again once that is
     * done, or after a few seconds if it stays stuck, since a device still closing refuses to open. EDT only.
     */
    public void refresh(String name) {
        String cam = resolve(name);
        if (cam == null || !restarting.add(cam)) {
            return;
        }
        log.info("Camera {}: refresh", cam);
        working.remove(cam); // try every format again, the last good one may be the problem
        Session s = sessions.remove(cam);
        Runnable reopen = () -> {
            if (restarting.remove(cam)) {
                detectAsync();
                fire();
            }
        };
        javax.swing.Timer limit = new javax.swing.Timer(REFRESH_WAIT_MS, e -> reopen.run());
        limit.setRepeats(false);
        limit.start();
        if (s == null) {
            limit.stop();
            reopen.run();
            return;
        }
        fire();
        Thread.ofPlatform().daemon().name("camera-refresh").start(() -> {
            try {
                s.feed.release();
            } catch (RuntimeException e) {
                log.warn("Camera {} did not close cleanly", cam, e);
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                limit.stop();
                reopen.run();
            });
        });
    }

    /** What the camera reported it supports, or null when not known (yet). */
    public Capabilities capabilities(String name) {
        return name == null ? null : capabilities.get(name);
    }

    /**
     * Asks the camera which sizes and frame rates it supports (through FFmpeg, when installed) and hands the
     * answer to {@code done} on the EDT; null when it cannot be found out.
     */
    public void queryCapabilities(String name, Consumer<Capabilities> done) {
        if (name == null) {
            done.accept(null);
            return;
        }
        Capabilities known = capabilities.get(name);
        if (known != null) {
            done.accept(known);
            return;
        }
        worker.execute(() -> {
            Capabilities caps = null;
            try {
                Path ffmpeg = com.jamc.worship.stream.FFmpeg.locate(null);
                if (ffmpeg != null) {
                    caps = listOptions(ffmpeg, name);
                }
            } catch (Exception e) {
                log.debug("Could not read the formats of {}", name, e);
            }
            if (caps != null) {
                capabilities.put(name, caps);
            }
            Capabilities result = caps;
            onEdt(() -> done.accept(result));
        });
    }

    private static Capabilities listOptions(Path ffmpeg, String name) throws Exception {
        Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-f", "dshow", "-list_options", "true",
                "-i", "video=" + name).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return parseOptions(out.toString());
    }

    private static final Pattern MAX_OPTION = Pattern.compile("max s=(\\d+)x(\\d+) fps=([\\d.]+)");
    private static final int[] RATES = {60, 50, 30, 25, 24, 15};

    /** Reads FFmpeg's "-list_options" output ("... max s=1920x1080 fps=30") into sizes and rates. */
    static Capabilities parseOptions(String text) {
        Map<String, Double> best = new HashMap<>();
        Matcher m = MAX_OPTION.matcher(text);
        while (m.find()) {
            String size = m.group(1) + "x" + m.group(2);
            best.merge(size, Double.parseDouble(m.group(3)), Math::max);
        }
        if (best.isEmpty()) {
            return null;
        }
        List<String> sizes = best.keySet().stream().sorted((a, b) -> Long.compare(area(b), area(a))).toList();
        double top = best.values().stream().mapToDouble(Double::doubleValue).max().orElse(30);
        List<Integer> rates = new ArrayList<>();
        for (int r : RATES) {
            if (r <= top + 0.5) {
                rates.add(r);
            }
        }
        return new Capabilities(sizes, rates);
    }

    private static long area(String size) {
        String[] p = size.split("x");
        return Long.parseLong(p[0]) * Long.parseLong(p[1]);
    }

    // ------------------------------------------------------------------ sessions

    /**
     * Keeps these cameras open for {@code owner} ("program", "preview"...), even while their sources are
     * hidden, and opens them now so a TAKE never waits for a camera to start. Replaces the owner's last set.
     */
    public void keepOpen(String owner, Collection<String> names) {
        Set<String> set = new HashSet<>();
        for (String n : names) {
            String cam = resolve(n);
            if (cam != null) {
                set.add(cam);
            }
        }
        if (set.isEmpty()) {
            kept.remove(owner);
        } else {
            kept.put(owner, set);
        }
        prewarm();
    }

    private boolean isKept(String cam) {
        return kept.values().stream().anyMatch(s -> s.contains(cam));
    }

    private void prewarm() {
        if (kept.isEmpty() || !available.getAsBoolean()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Set<String> set : kept.values()) {
            for (String cam : set) {
                if (!sessions.containsKey(cam) && !missing.contains(cam) && (!detected || cameras.contains(cam))) {
                    open(cam, now);
                }
            }
        }
    }

    private Session open(String cam, long now) {
        if (restarting.contains(cam) || isClosing(cam)) {
            return null; // opened on a later frame request, once the old feed has let go of the device
        }
        List<Format> tries = fallback ? candidates(requested.get(cam), working.get(cam)) : List.of(new Format(null, 0));
        try {
            Session s = new Session(opener.apply(cam, tries.getFirst()), tries, now);
            s.lastUsed = now;
            sessions.put(cam, s);
            log.info("Camera started: {} ({})", cam, describe(tries.getFirst()));
            fire();
            return s;
        } catch (RuntimeException e) {
            log.warn("Could not open camera {}", cam, e);
            return null;
        }
    }

    /** The named camera, or the first camera for a blank name; null when there is none. */
    private String resolve(String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        List<String> list = cameras;
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * Latest frame from the named camera (or the first camera when the name is blank), starting the
     * session if needed. Returns null until frames arrive, or when the camera is missing / failed or VLC is
     * not available. EDT only.
     */
    public BufferedImage frame(String name) {
        String cam = resolve(name);
        if (cam == null || !available.getAsBoolean()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (missing.contains(cam) || (detected && !cameras.contains(cam))) {
            wanted.put(cam, now); // looked for again by the sweeper (hot plug)
            missing.add(cam);
            return null;
        }
        Session s = sessions.get(cam);
        if (s == null) {
            s = open(cam, now);
            if (s == null) {
                return null;
            }
        }
        s.lastUsed = now;
        if (s.failed) {
            return null; // retried by the sweeper
        }
        if (s.reopenPending) {
            // the next format opens once the previous feed let go of the device; a driver stuck closing for
            // longer than REFRESH_WAIT_MS is not waited for (the open may still work, or fails and is retried)
            if (isClosing(cam) && now - s.reopenSince < REFRESH_WAIT_MS) {
                return null;
            }
            s.reopenPending = false;
            try {
                s.feed = opener.apply(cam, s.formats.get(s.attempt));
            } catch (RuntimeException e) {
                log.warn("Could not reopen camera {}", cam, e);
                fail(cam, s, now);
                return null;
            }
            s.started = now;
            return null;
        }
        BufferedImage img = s.feed.frame();
        long count = s.feed.frames();
        if (count != s.seenFrames) {
            s.seenFrames = count;
            s.lastFrameAt = now;
            if (!s.gotFrame) {
                s.gotFrame = true;
                working.putIfAbsent(cam, s.formats.get(s.attempt));
                fire();
            }
        }
        if (!s.gotFrame) {
            boolean timedOut = s.feed.failed() || now - s.started > FALLBACK_MS;
            if (timedOut && fallback && s.attempt < s.formats.size() - 1) {
                // this camera cannot deliver that size / rate: try the next one instead of staying black
                Format next = s.formats.get(s.attempt + 1);
                log.info("Camera {} gave no picture at {}; trying {}", cam, describe(s.formats.get(s.attempt)), describe(next));
                Feed old = s.feed;
                s.feed = Feed.NONE;
                s.attempt++;
                s.started = now;
                if (asyncRelease) {
                    release(cam, old);
                    s.reopenPending = true; // opened by a later frame request, without blocking this one
                    s.reopenSince = now;
                    return null;
                }
                old.release();
                try {
                    s.feed = opener.apply(cam, next);
                } catch (RuntimeException e) {
                    log.warn("Could not reopen camera {}", cam, e);
                    fail(cam, s, now);
                    return null;
                }
            } else if (timedOut && now - s.started > FALLBACK_MS) {
                fail(cam, s, now);
            }
            return null;
        }
        if (s.feed.failed() || now - s.lastFrameAt > STALL_MS) {
            // it was sending and stopped: unplugged, or the driver hung. Close it and look for it again.
            log.warn("Camera {} stopped sending pictures; checking whether it is still connected", cam);
            release(cam, sessions.remove(cam).feed);
            wanted.put(cam, now);
            fire();
            detectAsync();
            return null;
        }
        return img;
    }

    /** Every format was tried without a picture: ERROR, with the likely reason, until the next retry. */
    private void fail(String cam, Session s, long now) {
        s.failed = true;
        s.started = now;
        boolean othersRunning = sessions.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(cam) && e.getValue().gotFrame && !e.getValue().failed);
        s.problem = othersRunning
                ? BANDWIDTH + ": \"" + cam + "\" gives no picture while other cameras run. Connect it to another USB "
                        + "port or controller (USB 3.x), lower its resolution or frame rate, or close unused camera previews."
                : "\"" + cam + "\" gives no picture. It may be in use by another program (Zoom, Teams, OBS), or need "
                        + "reconnecting. It is tried again by itself.";
        log.warn("Camera {} failed: {}", cam, s.problem);
        release(cam, s.feed);
        s.feed = Feed.NONE;
        s.reopenPending = false;
        fire();
    }

    private static String describe(Format f) {
        return (f.size() == null ? "driver default size" : f.size()) + (f.fps() > 0 ? " @ " + f.fps() + " fps" : "");
    }

    /**
     * While true (Library → Camera is open) detection also runs now and then, so a newly plugged camera
     * appears by itself.
     */
    public void setWatching(boolean on) {
        watching = on;
    }

    public void stopAll() {
        sessions.forEach((cam, s) -> release(cam, s.feed));
        sessions.clear();
    }

    /** Every 2 s: closes unused sessions, retries failed ones, measures frame rates, looks for missing cameras. */
    void sweep() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Session> e = it.next();
            Session s = e.getValue();
            String cam = e.getKey();
            if (now - s.lastUsed > IDLE_MS && !isKept(cam)) {
                release(cam, s.feed);
                it.remove();
                changed = true;
                log.info("Camera stopped (not in use): {}", cam);
                continue;
            }
            if (s.failed && now - s.started > RETRY_MS && (isKept(cam) || now - s.lastUsed < IDLE_MS)) {
                it.remove(); // the next frame request (or prewarm) opens it from the first format again
                changed = true;
                log.info("Retrying camera {}", cam);
                continue;
            }
            if (!s.failed && now - s.fpsAt >= 1_000) {
                long count = s.feed.frames();
                s.fps = (int) Math.round((count - s.fpsFrames) * 1000.0 / (now - s.fpsAt));
                s.fpsFrames = count;
                s.fpsAt = now;
                if (count != s.seenFrames) {
                    s.seenFrames = count;
                    s.lastFrameAt = now; // pictures are flowing even when nothing painted them
                    s.gotFrame = true;
                }
            }
        }
        wanted.entrySet().removeIf(e -> now - e.getValue() > 60_000 && !isKept(e.getKey()));
        boolean anyMissing = !missing.isEmpty() || wanted.keySet().stream().anyMatch(c -> !cameras.contains(c));
        if ((anyMissing && now - lastDetect > RECHECK_MS) || (watching && now - lastDetect > 3 * RECHECK_MS)) {
            detectAsync();
        }
        prewarm();
        if (changed) {
            fire();
        }
    }

    private void fire() {
        onEdt(() -> listeners.forEach(Runnable::run));
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    // ------------------------------------------------------------------ Windows detection

    private static Detection detectWindows() {
        List<String> video = new ArrayList<>();
        List<String> virtual = new ArrayList<>();
        List<String> audio = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return new Detection(video, audio);
        }
        try {
            // cameras: Camera class, or usbvideo Image devices (not printers / scanners). Audio capture inputs are
            // the AudioEndpoint devices whose id has {0.0.1.} (capture; {0.0.0.} is playback). UTF-8 output keeps a name
            // exact ("Intel®" came back "Intelr", and that input could then not be opened).
            // 'D|': software / virtual cameras (Iriun Webcam #2..#4, DroidCam Source 2/3, OBS Virtual Camera, Snap
            // Camera...) are DirectShow filters, not PnP devices: they are listed under the video input category.
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                            + "Get-CimInstance Win32_PnPEntity | Where-Object { $_.Status -eq 'OK' } | ForEach-Object { "
                            + "if ($_.PNPClass -eq 'Camera' -or ($_.PNPClass -eq 'Image' -and $_.Service -eq 'usbvideo')) "
                            + "{ 'V|' + $_.Name } elseif ($_.PNPClass -eq 'AudioEndpoint' -and $_.DeviceID -like '*{0.0.1.*') "
                            + "{ 'A|' + $_.Name } }; "
                            + "Get-ChildItem 'Registry::HKEY_CLASSES_ROOT\\CLSID\\" + VIDEO_INPUT_CATEGORY + "\\Instance' "
                            + "-ErrorAction SilentlyContinue | ForEach-Object { "
                            + "$n = (Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue).FriendlyName; "
                            + "if ($n) { 'D|' + $n } }")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String v = line.strip();
                    if (v.length() > 2 && v.charAt(1) == '|') {
                        List<String> into = switch (v.charAt(0)) {
                            case 'V' -> video;
                            case 'D' -> virtual;
                            default -> audio;
                        };
                        String name = v.substring(2).strip();
                        if (!name.isEmpty() && !into.contains(name)) {
                            into.add(name);
                        }
                    }
                }
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception | Error e) {
            log.warn("Camera detection failed", e);
        }
        return new Detection(mergeCameras(directShowCameras(), video, virtual), audio);
    }

    /** CLSID_VideoInputDeviceCategory: where DirectShow lists capture filters, virtual cameras included. */
    private static final String VIDEO_INPUT_CATEGORY = "{860BB310-5D01-11d0-BD3B-00A0C911CE86}";

    /**
     * The DirectShow video devices as FFmpeg sees them (the same list OBS shows, in the same order), or empty
     * when FFmpeg is not installed.
     */
    private static List<String> directShowCameras() {
        try {
            Path ffmpeg = com.jamc.worship.stream.FFmpeg.locate(null);
            if (ffmpeg == null) {
                return List.of();
            }
            Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-list_devices", "true", "-f", "dshow",
                    "-i", "dummy").redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return com.jamc.worship.stream.FFmpeg.parseDevices(out.toString(), "video");
        } catch (Exception e) {
            log.debug("FFmpeg camera list failed", e);
            return List.of();
        }
    }

    /**
     * Every camera from every list, once (names compared ignoring case): FFmpeg's DirectShow list first, then
     * hardware cameras, then registered virtual cameras FFmpeg did not report.
     */
    @SafeVarargs
    static List<String> mergeCameras(List<String>... lists) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (List<String> list : lists) {
            for (String name : list) {
                String n = name == null ? "" : name.strip();
                if (!n.isEmpty()) {
                    out.putIfAbsent(n.toLowerCase(Locale.ROOT), n);
                }
            }
        }
        return List.copyOf(out.values());
    }
}
