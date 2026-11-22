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
    /** Pictures skipped because newer ones were already waiting (logged at most once a minute). */
    private long stale;
    private long lastStaleLog;

    /** Uses the Direct engine from now on (cameras opened later). */
    static void setEnabled(boolean on) {
        enabled = on;
    }

    static boolean enabled() {
        return (enabled || com.jamc.worship.app.Os.MAC) && ffmpeg != null; // a Mac always uses Direct
    }

    /** The FFmpeg the recordings use; without it cameras stay on VLC. */
    public static void setFFmpeg(Path exe) {
        ffmpeg = exe;
    }

    /** FFmpeg sends the pictures here (local TCP): a Windows pipe carried only ~28 MB/s, 3 pictures a second at 1080p. */
    private final java.net.ServerSocket server;
    private volatile java.net.Socket socket;

    private DirectCamera(String name, Process process, boolean lastResort, java.net.ServerSocket server) {
        this.name = name;
        this.process = process;
        this.lastResort = lastResort;
        this.server = server;
    }

    /** True when this camera should be opened directly (Direct engine on, FFmpeg found, the camera not refused). */
    static boolean usesDirect(String name) {
        if (com.jamc.worship.app.Os.MAC) {
            return ffmpeg != null; // a Mac has no DirectShow for VLC: cameras are always read by FFmpeg there
        }
        return enabled() && !refused.contains(name);
    }

    static Path ffmpeg() {
        return ffmpeg;
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
        List<String> cmd = new ArrayList<>(command(ffmpeg.toString(), name, format));
        boolean lastResort = format.size() == null && format.fps() == 0;
        try {
            // measured with 1080p test pictures: a pipe gave 3.3 pictures a second, local TCP the full 30 (250 MB/s)
            java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            server.setSoTimeout(15_000);
            cmd.set(cmd.size() - 1, "tcp://127.0.0.1:" + server.getLocalPort());
            Process p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            DirectCamera c = new DirectCamera(name, p, lastResort, server);
            Thread.ofPlatform().daemon().name("camera-log").start(c::logLoop);
            Thread.ofPlatform().daemon().name("camera-read").start(c::readLoop);
            log.info("Camera {} opened directly ({})", name, format.size() == null ? "driver default" : format.size()
                    + (format.fps() > 0 ? " @ " + format.fps() : ""));
            return c;
        } catch (IOException e) {
            log.warn("Camera {} could not be opened directly: {}", name, e.getMessage());
            DirectCamera c = new DirectCamera(name, null, lastResort, null);
            c.noPicture();
            return c;
        }
    }

    /** The FFmpeg command: the camera as it comes, low delay, out as BGRA pictures (read as 0xAARRGGBB ints). */
    static List<String> command(String exe, String name, CameraManager.Format format) {
        if (com.jamc.worship.app.Os.MAC) {
            return macCommand(exe, name, format);
        }
        List<String> c = new ArrayList<>(List.of(exe, "-hide_banner", "-loglevel", "info", "-nostats",
                "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0",
                "-f", "dshow", "-rtbufsize", String.valueOf(realTimeBuffer(format.size(), compressed.contains(name)))));
        if (format.size() != null) {
            c.addAll(List.of("-video_size", format.size()));
        }
        if (format.fps() > 0) {
            c.addAll(List.of("-framerate", String.valueOf(format.fps())));
        }
        if ("YUY2".equals(CameraManager.chroma(name, format))) {
            c.addAll(List.of("-pixel_format", "yuyv422")); // Iriun: its other format came out with red and blue swapped
        }
        // vfr: never invent copies. After a hiccup the default (cfr) sent ~100 copies of the last picture to "fill"
        // the gap, and the app had to show them all before the live picture: the camera was then seconds late
        c.addAll(List.of("-i", "video=" + name, "-an", "-vf", UP_TO_1080, "-fps_mode", "vfr", "-f", "rawvideo",
                "-pix_fmt", "bgra", "pipe:1"));
        return c;
    }

    /**
     * A camera smaller than 1080p is enlarged to 1080p by FFmpeg, in its own process, before the app sees it.
     * Recordings and the livestream are 1080p: enlarging a 720p camera there took 23 ms a picture on the Swing
     * thread (1080p to 1080p: 0.6 ms), which with the Preview / Program monitors filled the Swing thread and left
     * the recording only ~10 new pictures a second ("pikit") although the camera sent 21-30. FFmpeg's
     * fast_bilinear costs about a quarter of one core. Bigger cameras are left as they are.
     */
    static final String UP_TO_1080 = "scale=w=if(lt(ih\\,1080)\\,trunc(iw*1080/ih/2)*2\\,iw)"
            + ":h=if(lt(ih\\,1080)\\,1080\\,ih):flags=fast_bilinear";

    /** Cameras seen sending MJPEG/H.264 (small pictures): their waiting room is sized for those from the next open. */
    private static final java.util.Set<String> compressed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * DirectShow's waiting room for pictures FFmpeg has not taken yet, in bytes: one picture and a little.
     * It was 64 MB: after one hiccup (a busy moment) it filled up and STAYED full, so every picture after it was
     * old while the voice stayed on time. Measured on a 720p camera after a 3 s pause: 64 MB left the picture
     * 1.17 s late for good, 8 MB 0.1 s extra, 2 pictures sometimes 0.07 s extra, 1.1 pictures nothing extra
     * (3-27 ms from FFmpeg to the reader, the same as before the hiccup, in 4 of 5 runs; one picture in the fifth)
     * and never "buffer too full" in 30 s. A 64-minute Sunday recording on Facebook drifted exactly this way.
     */
    static long realTimeBuffer(String size, boolean compressedPictures) {
        long pixels = 1920L * 1080;
        if (size != null) {
            String[] wh = size.split("x");
            try {
                pixels = Long.parseLong(wh[0].trim()) * Long.parseLong(wh[1].trim());
            } catch (RuntimeException ignored) {
                // keep the 1080p guess
            }
        }
        if (compressedPictures) {
            return Math.max(256L << 10, pixels / 2); // an MJPEG picture is about a tenth of a raw one: ~3-5 pictures
        }
        return Math.max(1L << 20, Math.min(64L << 20, pixels * 2 * 11 / 10)); // 1.1 raw YUY2 pictures
    }

    private static final Pattern INPUT_CODEC = Pattern.compile("Stream #0:0.*Video: (mjpeg|h264|hevc)\\b");

    /** True when FFmpeg's description of the camera's input stream shows compressed pictures. */
    static boolean compressedInput(String line) {
        return INPUT_CODEC.matcher(line).find();
    }

    /**
     * macOS (AVFoundation): a Mac camera must be asked for a frame rate it has (it refuses FFmpeg's 29.97 default),
     * so 30 is asked when none is chosen; the camera's own sound is not read here.
     */
    static List<String> macCommand(String exe, String name, CameraManager.Format format) {
        List<String> c = new ArrayList<>(List.of(exe, "-hide_banner", "-loglevel", "info", "-nostats",
                "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0",
                "-f", "avfoundation", "-framerate", String.valueOf(format.fps() > 0 ? format.fps() : 30)));
        if (format.size() != null) {
            c.addAll(List.of("-video_size", format.size()));
        }
        c.addAll(List.of("-i", name + ":none", "-an", "-vf", UP_TO_1080, "-fps_mode", "vfr", "-f", "rawvideo",
                "-pix_fmt", "bgra", "pipe:1"));
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
                if (!output && compressedInput(line) && compressed.add(name)) {
                    log.info("Camera {} sends compressed pictures: smaller waiting room from its next open", name);
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
            java.net.Socket s = server.accept(); // FFmpeg connects once it has the camera's first picture
            s.setReceiveBufferSize(8 << 20);
            socket = s;
            try (InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 1 << 20)) {
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
                    // pictures already waiting behind this one mean we fell behind (a busy moment): show only the
                    // newest, or the camera stays late behind the voice for the rest of the service
                    if (in.available() >= bytes.length) {
                        stale++;
                        continue;
                    }
                    publish(bytes, w, h);
                    long now = System.nanoTime();
                    if (stale > 0 && now - lastStaleLog > 60_000_000_000L) {
                        log.info("Camera {} (direct): caught up, skipped {} old pictures", name, stale);
                        stale = 0;
                        lastStaleLog = now;
                    }
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

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    @Override
    public void release() {
        if (released) {
            return;
        }
        released = true;
        closeQuietly(socket);
        closeQuietly(server);
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
