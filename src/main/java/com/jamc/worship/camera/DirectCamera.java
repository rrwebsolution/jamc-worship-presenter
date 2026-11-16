package com.jamc.worship.camera;

import com.jamc.worship.media.MediaFrames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Camera engine "Direct": the camera is read straight from DirectShow by FFmpeg (the way OBS reads it) and each
 * picture is handed to the app the moment it arrives. VLC holds camera pictures back 150 ms ({@code live-caching})
 * to play them evenly, which made the picture later than the voice. Videos and music stay on VLC.
 */
final class DirectCamera implements CameraManager.Feed {

    private static final Logger log = LoggerFactory.getLogger(DirectCamera.class);
    /** Settings key: "DIRECT" (default) or "VLC". */
    static final String KEY_ENGINE = "camera.engine";

    private static volatile boolean enabled;
    private static volatile Path ffmpeg;
    /** Cameras that gave no picture through FFmpeg even at the driver's default: they use VLC this run. */
    private static final java.util.Set<String> refused = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final String name;
    private final Process process;
    private final boolean lastResort;
    private volatile BufferedImage front;
    private BufferedImage back;
    private BufferedImage spare;
    private volatile long frames;
    private volatile boolean failed;
    private volatile boolean released;
    private volatile int width;
    private volatile int height;
    private volatile long lastFrameNanos;
    private volatile long maxGapNanos;

    /** Uses the Direct engine from now on (cameras opened later). */
    static void setEnabled(boolean on) {
        enabled = on;
    }

    static boolean enabled() {
        return enabled && ffmpeg != null;
    }

    /** The FFmpeg the recordings use; without it cameras stay on VLC. */
    public static void setFFmpeg(Path exe) {
        ffmpeg = exe;
    }

    private DirectCamera(String name, Process process, boolean lastResort) {
        this.name = name;
        this.process = process;
        this.lastResort = lastResort;
    }

    /** True when this camera should be opened directly (Direct engine on, FFmpeg found, the camera not refused). */
    static boolean usesDirect(String name) {
        return enabled() && !refused.contains(name);
    }

    /** Gave no picture at all: noted so the next try of this camera goes through VLC. */
    private void noPicture() {
        failed = true;
        if (lastResort && refused.add(name)) {
            log.warn("Camera {} gave no picture through FFmpeg; it uses VLC from now on (this run)", name);
        }
    }

    /** Starts FFmpeg on the camera; pictures arrive on its own threads. */
    static DirectCamera open(String name, CameraManager.Format format) {
        List<String> cmd = command(ffmpeg.toString(), name, format);
        try {
            Process p = new ProcessBuilder(cmd).start();
            DirectCamera c = new DirectCamera(name, p, format.size() == null && format.fps() == 0);
            Thread.ofPlatform().daemon().name("camera-log").start(c::logLoop);
            Thread.ofPlatform().daemon().name("camera-read").start(c::readLoop);
            log.info("Camera {} opened directly ({})", name, format.size() == null ? "driver default" : format.size()
                    + (format.fps() > 0 ? " @ " + format.fps() : ""));
            return c;
        } catch (IOException e) {
            log.warn("Camera {} could not be opened directly: {}", name, e.getMessage());
            DirectCamera c = new DirectCamera(name, null, format.size() == null && format.fps() == 0);
            c.noPicture();
            return c;
        }
    }

    /** The FFmpeg command: the camera as it comes, low delay, out as BGRA pictures (read as 0xAARRGGBB ints). */
    static List<String> command(String exe, String name, CameraManager.Format format) {
        List<String> c = new ArrayList<>(List.of(exe, "-hide_banner", "-loglevel", "info", "-nostats",
                "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0",
                "-f", "dshow", "-rtbufsize", "64M"));
        if (format.size() != null) {
            c.addAll(List.of("-video_size", format.size()));
        }
        if (format.fps() > 0) {
            c.addAll(List.of("-framerate", String.valueOf(format.fps())));
        }
        if ("YUY2".equals(CameraManager.chroma(name, format))) {
            c.addAll(List.of("-pixel_format", "yuyv422")); // Iriun: its other format came out with red and blue swapped
        }
        c.addAll(List.of("-i", "video=" + name, "-an", "-f", "rawvideo", "-pix_fmt", "bgra", "pipe:1"));
        return c;
    }

    private static final Pattern OUTPUT_SIZE = Pattern.compile("Video: rawvideo.*?, (\\d{2,5})x(\\d{2,5})");

    /** The picture size from FFmpeg's description of its output, or null. */
    static int[] outputSize(String line) {
        Matcher m = OUTPUT_SIZE.matcher(line);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    private final Object sizeLock = new Object();

    private void logLoop() {
        boolean output = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Output #0")) {
                    output = true;
                }
                if (output && width == 0) {
                    int[] size = outputSize(line);
                    if (size != null) {
                        synchronized (sizeLock) {
                            width = size[0];
                            height = size[1];
                            sizeLock.notifyAll();
                        }
                    }
                }
                String l = line.toLowerCase(java.util.Locale.ROOT);
                if (l.contains("error") || l.contains("could not") || l.contains("failed")) {
                    log.warn("Camera {} (direct): {}", name, line.strip());
                }
            }
        } catch (IOException e) {
            // ended
        } finally {
            synchronized (sizeLock) {
                sizeLock.notifyAll();
            }
        }
    }

    private void readLoop() {
        try {
            synchronized (sizeLock) {
                long until = System.currentTimeMillis() + 15_000;
                while (width == 0 && process.isAlive() && !released && System.currentTimeMillis() < until) {
                    sizeLock.wait(250);
                }
            }
            int w = width;
            int h = height;
            if (w == 0 || h == 0) {
                if (!released) {
                    log.warn("Camera {} gave no picture when opened directly", name);
                    noPicture();
                }
                return;
            }
            byte[] bytes = new byte[w * h * 4];
            try (InputStream in = process.getInputStream()) {
                while (!released) {
                    int got = 0;
                    while (got < bytes.length) {
                        int n = in.read(bytes, got, bytes.length - got);
                        if (n < 0) {
                            if (!released) {
                                if (frames == 0) {
                                    noPicture();
                                }
                                log.info("Camera {} (direct) stopped sending", name);
                            }
                            return;
                        }
                        got += n;
                    }
                    publish(bytes, w, h);
                }
            }
        } catch (IOException e) {
            if (!released && frames == 0) {
                noPicture();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void publish(byte[] bytes, int w, int h) {
        BufferedImage img = back != null && back.getWidth() == w && back.getHeight() == h
                ? back : new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        // BGRA in memory read as little-endian ints is 0xAARRGGBB, which TYPE_INT_RGB expects (alpha ignored)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        BufferedImage previous = front;
        front = img;
        // the next picture goes into the image that left the screen one picture ago, never the one just shown
        back = spare;
        spare = previous;
        long now = System.nanoTime();
        if (lastFrameNanos != 0) {
            maxGapNanos = Math.max(maxGapNanos, now - lastFrameNanos);
        }
        lastFrameNanos = now;
        frames++;
        MediaFrames.frameArrived();
    }

    @Override
    public BufferedImage frame() {
        com.jamc.worship.stream.AvSync.noteFrame(lastFrameNanos); // how late this picture is in a recording
        return front;
    }

    @Override
    public long frames() {
        return frames;
    }

    @Override
    public boolean failed() {
        return failed;
    }

    @Override
    public long takeMaxGapMillis() {
        long g = maxGapNanos;
        maxGapNanos = 0;
        return g / 1_000_000;
    }

    @Override
    public void release() {
        if (released) {
            return;
        }
        released = true;
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        front = null;
    }
}
