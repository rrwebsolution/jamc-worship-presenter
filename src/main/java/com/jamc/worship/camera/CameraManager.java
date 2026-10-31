package com.jamc.worship.camera;

import com.jamc.worship.media.MediaEngine;
import com.jamc.worship.media.VideoPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Detects cameras and provides live camera frames to scenes.
 *
 * <p>Capture uses VLC's DirectShow input. The {@link CameraProvider} seam keeps the capture method
 * replaceable (NDI, capture cards, OBS virtual camera...) without touching scenes or the compositor.
 * Feeds start when a visible camera source is painted and stop when no scene has shown them for a
 * few seconds, so an unused camera never stays switched on.
 */
public final class CameraManager {

    private static final Logger log = LoggerFactory.getLogger(CameraManager.class);
    private static final long IDLE_MS = 5_000;

    /** A way of turning a camera name into a running video feed. */
    public interface CameraProvider {
        VideoPlayer open(String cameraName);
    }

    private final Map<String, Feed> feeds = new HashMap<>();
    private final Timer sweeper = new Timer(2_000, e -> sweep());
    private volatile List<String> cameras = List.of();

    /** One way of opening a camera: a capture size ("1280x720", null = the driver's default) and frame rate (0 = default). */
    record Format(String size, int fps) {
    }

    /**
     * Sizes tried, best first, when no size is chosen ("Auto"). Left to itself DirectShow often opens a
     * webcam at its smallest size (e.g. 320×180, which looks blurry); asking for a size the camera does
     * not have gives no picture at all, so each one is tried in turn.
     */
    static final List<String> AUTO_SIZES = List.of("1920x1080", "1280x720");
    /** How long a format may take to show its first picture before the next one is tried. */
    static final long FALLBACK_MS = 3_500;

    private static final class Feed {
        VideoPlayer player;
        long lastUsed;
        long started;
        final List<Format> formats;
        int attempt;

        Feed(VideoPlayer player, List<Format> formats) {
            this.player = player;
            this.formats = formats;
            this.started = System.currentTimeMillis();
        }
    }

    /** Requested capture format per camera (null size = Auto). */
    private final Map<String, Format> requested = new java.util.concurrent.ConcurrentHashMap<>();
    /** The format that actually gave a picture, per camera, so a restart opens it straight away. */
    private final Map<String, Format> working = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.function.BiFunction<String, Format, VideoPlayer> opener;
    private final boolean fallback;

    public CameraManager() {
        this.opener = CameraManager::openDirectShow;
        this.fallback = true;
        sweeper.start();
    }

    public CameraManager(CameraProvider provider) {
        this.opener = (name, format) -> provider.open(name);
        this.fallback = false;
        sweeper.start();
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
        boolean iriun = name != null && name.toLowerCase(java.util.Locale.ROOT).contains("iriun");
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

    /** The picture size the camera is delivering now, e.g. "1280×720", or null while it starts. */
    public String liveSize(String name) {
        Feed f = feeds.get(name);
        BufferedImage img = f == null ? null : f.player.currentFrame();
        return img == null ? null : img.getWidth() + "×" + img.getHeight();
    }

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
        Feed f = feeds.remove(name);
        if (f != null) {
            f.player.release(); // reopened with the new format on the next frame request
            log.info("Camera {} restarting at {} / {} fps", name, size == null ? "Auto" : size, fps == 0 ? "default" : fps);
        }
    }

    /** Last detected camera names (call {@link #detect()} to refresh). */
    public List<String> cameras() {
        return cameras;
    }

    /**
     * Lists connected cameras. Blocking (runs PowerShell on Windows): call from a background thread.
     * Printers and scanners that Windows files under "Image" devices are excluded.
     */
    public List<String> detect() {
        List<String> found = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "Get-CimInstance Win32_PnPEntity | Where-Object { $_.PNPClass -eq 'Camera' -or "
                                + "($_.PNPClass -eq 'Image' -and $_.Service -eq 'usbvideo') } | "
                                + "Where-Object { $_.Status -eq 'OK' } | ForEach-Object { $_.Name }")
                        .redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.isBlank() && !found.contains(line.strip())) {
                            found.add(line.strip());
                        }
                    }
                }
                if (!p.waitFor(15, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (Exception e) {
                log.warn("Camera detection failed", e);
            } catch (Error e) {
                log.warn("Camera detection failed", e);
            }
        }
        cameras = List.copyOf(found);
        log.info("Cameras detected: {}", cameras);
        return cameras;
    }

    /**
     * Latest frame from the named camera (or the first camera when the name is blank), starting the
     * feed if needed. Returns null until frames arrive or when no camera/VLC is available. EDT only.
     */
    public BufferedImage frame(String name) {
        String cam = name == null || name.isBlank() ? (cameras.isEmpty() ? null : cameras.getFirst()) : name;
        if (cam == null || !MediaEngine.get().isAvailable()) {
            return null;
        }
        Feed f = feeds.get(cam);
        long now = System.currentTimeMillis();
        if (f == null) {
            List<Format> tries = fallback ? candidates(requested.get(cam), working.get(cam)) : List.of(new Format(null, 0));
            try {
                f = new Feed(opener.apply(cam, tries.getFirst()), tries);
                feeds.put(cam, f);
                log.info("Camera started: {} ({})", cam, describe(tries.getFirst()));
            } catch (RuntimeException e) {
                log.warn("Could not open camera {}", cam, e);
                return null;
            }
        }
        f.lastUsed = now;
        BufferedImage img = f.player.currentFrame();
        if (img != null) {
            working.putIfAbsent(cam, f.formats.get(f.attempt));
        } else if (fallback && f.attempt < f.formats.size() - 1 && (f.player.hasError() || now - f.started > FALLBACK_MS)) {
            // this camera cannot deliver that size / rate: try the next one instead of staying black
            Format next = f.formats.get(f.attempt + 1);
            log.info("Camera {} gave no picture at {}; trying {}", cam, describe(f.formats.get(f.attempt)), describe(next));
            f.player.release();
            try {
                f.player = opener.apply(cam, next);
            } catch (RuntimeException e) {
                log.warn("Could not reopen camera {}", cam, e);
                feeds.remove(cam);
                return null;
            }
            f.attempt++;
            f.started = now;
        }
        return img;
    }

    private static String describe(Format f) {
        return (f.size() == null ? "driver default size" : f.size()) + (f.fps() > 0 ? " @ " + f.fps() + " fps" : "");
    }

    public void stopAll() {
        feeds.values().forEach(f -> f.player.release());
        feeds.clear();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Feed>> it = feeds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Feed> e = it.next();
            if (now - e.getValue().lastUsed > IDLE_MS) {
                e.getValue().player.release();
                it.remove();
                log.info("Camera stopped (not in use): {}", e.getKey());
            }
        }
    }
}
