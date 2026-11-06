package com.jamc.worship.camera;

import com.jamc.worship.media.MediaFrames;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NDI® network video sources as cameras: phone camera apps, other PCs, PTZ cameras and NDI encoders on the
 * same network each show up as their own camera ("NDI: PHONE (Camera)"), so any number of phones can be used
 * at once without a per-app camera limit.
 *
 * <p>Uses the NDI runtime installed on the computer (it comes with the free NDI Tools from ndi.video; it is not
 * shipped with the app). Without it NDI simply finds nothing. NDI® is a registered trademark of Vizrt NDI AB.
 *
 * <p>Bound with JNA to the plain C API of {@code Processing.NDI.Lib.x64.dll}; struct layouts are those of the
 * NDI SDK v5/v6 headers on 64-bit Windows.
 */
public final class Ndi {

    private static final Logger log = LoggerFactory.getLogger(Ndi.class);

    /** Camera names of NDI sources start with this; the rest is the NDI source name. */
    public static final String PREFIX = "NDI: ";

    private static final String DLL = "Processing.NDI.Lib.x64.dll";
    // NDIlib_frame_type_e
    private static final int FRAME_VIDEO = 1;
    private static final int FRAME_ERROR = 4;
    // NDIlib_recv_color_format_BGRX_BGRA, NDIlib_recv_bandwidth_highest
    private static final int COLOR_BGRX_BGRA = 0;
    private static final int BANDWIDTH_HIGHEST = 100;
    private static final int FOURCC_BGRA = fourCc('B', 'G', 'R', 'A');
    private static final int FOURCC_BGRX = fourCc('B', 'G', 'R', 'X');
    /** sizeof(NDIlib_video_frame_v2_t) and sizeof(NDIlib_recv_create_v3_t) on x64. */
    private static final int VIDEO_FRAME_SIZE = 72;
    private static final int RECV_CREATE_SIZE = 40;

    private static volatile Lib lib;
    private static volatile String problem;
    private static boolean loaded;
    private static Pointer finder;

    private Ndi() {
    }

    /** True for a camera name that is an NDI source. */
    public static boolean isNdi(String camera) {
        return camera != null && camera.startsWith(PREFIX);
    }

    /** The camera name for an NDI source name. */
    static String cameraName(String source) {
        return PREFIX + source;
    }

    /** True when the NDI runtime is on this computer. Quick and never blocks: safe on the Swing thread. */
    public static boolean isInstalled() {
        return locate() != null;
    }

    /** True when the NDI runtime is installed and started. */
    public static synchronized boolean isAvailable() {
        return load() != null;
    }

    /** Why NDI is not available, in words for the operator; null when it is. */
    public static synchronized String problem() {
        load();
        return problem;
    }

    /**
     * The NDI sources on the network now, as camera names. Blocking for up to a second or two the first time
     * (sources announce themselves); call from a background thread. Empty when NDI is not installed.
     */
    public static synchronized List<String> sources() {
        Lib l = load();
        if (l == null) {
            return List.of();
        }
        try {
            if (finder == null) {
                finder = (Pointer) l.findCreate.invoke(Pointer.class, new Object[]{null});
                if (finder == null) {
                    log.warn("NDI: could not start looking for sources");
                    return List.of();
                }
                l.findWait.invokeInt(new Object[]{finder, 2_000}); // first look: give sources time to answer
            } else {
                l.findWait.invokeInt(new Object[]{finder, 0});
            }
            IntByReference count = new IntByReference();
            Pointer array = (Pointer) l.findSources.invoke(Pointer.class, new Object[]{finder, count});
            List<String> out = new ArrayList<>();
            for (int i = 0; array != null && i < count.getValue(); i++) {
                Pointer name = array.getPointer(i * 16L); // NDIlib_source_t: p_ndi_name, p_url_address
                if (name != null) {
                    String n = name.getString(0, "UTF-8").strip();
                    if (!n.isEmpty()) {
                        out.add(cameraName(n));
                    }
                }
            }
            return out;
        } catch (RuntimeException | Error e) {
            log.warn("NDI source search failed", e);
            return List.of();
        }
    }

    /**
     * Opens a receiver for the NDI source behind a camera name; it connects in the background. Never waits for a
     * source search (the Swing thread calls this): NDI cameras only exist once detection has started the runtime.
     */
    static Receiver open(String camera) {
        return new Receiver(lib, camera.substring(PREFIX.length()));
    }

    // ------------------------------------------------------------------ runtime

    private static Lib load() {
        if (loaded) {
            return lib;
        }
        loaded = true;
        Path dll = locate();
        if (dll == null) {
            problem = "NDI is not installed. Install the free NDI Tools (ndi.video), then restart the app.";
            log.info("NDI runtime not found; NDI sources are off");
            return null;
        }
        try {
            NativeLibrary nl = NativeLibrary.getInstance(dll.toString());
            Lib l = new Lib(nl);
            if ((l.initialize.invokeInt(new Object[0]) & 0xFF) == 0) {
                problem = "NDI could not start: this computer's processor is not supported by NDI.";
                log.warn("NDIlib_initialize failed");
                return null;
            }
            lib = l;
            log.info("NDI runtime loaded from {}", dll);
            return lib;
        } catch (RuntimeException | Error e) {
            problem = "NDI could not start: " + e.getMessage();
            log.warn("NDI runtime could not be loaded from {}", dll, e);
            return null;
        }
    }

    /** The installed NDI runtime: the folders the NDI installers register, then the usual install folders. */
    private static Path locate() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        List<Path> dirs = new ArrayList<>();
        for (String v : List.of("NDI_RUNTIME_DIR_V6", "NDI_RUNTIME_DIR_V5", "NDI_RUNTIME_DIR_V4")) {
            String dir = System.getenv(v);
            if (dir != null && !dir.isBlank()) {
                dirs.add(Path.of(dir.strip()));
            }
        }
        String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
        dirs.add(Path.of(pf, "NDI", "NDI 6 Runtime", "v6"));
        dirs.add(Path.of(pf, "NDI", "NDI 5 Runtime", "v5"));
        dirs.add(Path.of(pf, "NDI", "NDI 6 Tools", "Runtime"));
        dirs.add(Path.of(pf, "NDI", "NDI 6 SDK", "Bin", "x64"));
        dirs.add(Path.of(pf, "NDI", "NDI 5 SDK", "Bin", "x64"));
        for (Path d : dirs) {
            try {
                Path f = d.resolve(DLL);
                if (Files.isRegularFile(f)) {
                    return f;
                }
            } catch (RuntimeException ignored) {
                // a malformed folder in an environment variable
            }
        }
        return null;
    }

    private static int fourCc(char a, char b, char c, char d) {
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    /** The NDI functions used. */
    private static final class Lib {
        final Function initialize;
        final Function findCreate;
        final Function findWait;
        final Function findSources;
        final Function recvCreate;
        final Function recvDestroy;
        final Function recvCapture;
        final Function recvFreeVideo;

        Lib(NativeLibrary nl) {
            initialize = nl.getFunction("NDIlib_initialize");
            findCreate = nl.getFunction("NDIlib_find_create_v2");
            findWait = nl.getFunction("NDIlib_find_wait_for_sources");
            findSources = nl.getFunction("NDIlib_find_get_current_sources");
            recvCreate = nl.getFunction("NDIlib_recv_create_v3");
            recvDestroy = nl.getFunction("NDIlib_recv_destroy");
            recvCapture = nl.getFunction("NDIlib_recv_capture_v2");
            recvFreeVideo = nl.getFunction("NDIlib_recv_free_video_v2");
        }
    }

    // ------------------------------------------------------------------ receiver

    /** One NDI source being received: its own thread copies each video frame into a picture. */
    static final class Receiver implements CameraManager.Feed {
        private final Lib lib;
        private final String source;
        private final Pointer recv;
        private final Thread thread;
        private volatile boolean running = true;
        private volatile boolean failed;
        private volatile BufferedImage front;
        private volatile long frames;
        private BufferedImage back;

        Receiver(Lib lib, String source) {
            this.lib = lib;
            this.source = source;
            Pointer r = null;
            if (lib != null) {
                try {
                    Memory name = new Memory(source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L);
                    name.setString(0, source, "UTF-8");
                    Memory create = new Memory(RECV_CREATE_SIZE);
                    create.clear();
                    create.setPointer(0, name);              // source_to_connect_to.p_ndi_name
                    create.setPointer(8, null);              // p_url_address: found by name
                    create.setInt(16, COLOR_BGRX_BGRA);      // color_format
                    create.setInt(20, BANDWIDTH_HIGHEST);    // bandwidth
                    create.setByte(24, (byte) 0);            // allow_video_fields: progressive frames only
                    create.setPointer(32, null);             // p_ndi_recv_name: default
                    r = (Pointer) lib.recvCreate.invoke(Pointer.class, new Object[]{create});
                } catch (RuntimeException | Error e) {
                    log.warn("NDI: could not open {}", source, e);
                }
            }
            recv = r;
            failed = recv == null;
            if (recv != null) {
                log.info("NDI: receiving {}", source);
                thread = Thread.ofPlatform().daemon().name("ndi-" + source).start(this::run);
            } else {
                thread = null;
            }
        }

        private void run() {
            Memory video = new Memory(VIDEO_FRAME_SIZE);
            int errors = 0;
            while (running) {
                int type;
                try {
                    video.clear();
                    type = lib.recvCapture.invokeInt(new Object[]{recv, video, null, null, 500});
                } catch (RuntimeException | Error e) {
                    log.warn("NDI: receiving {} failed", source, e);
                    failed = true;
                    return;
                }
                if (type == FRAME_VIDEO) {
                    errors = 0;
                    try {
                        copy(video);
                    } finally {
                        lib.recvFreeVideo.invoke(Void.class, new Object[]{recv, video});
                    }
                } else if (type == FRAME_ERROR && ++errors == 1) {
                    log.warn("NDI: lost the connection to {}; it reconnects by itself", source);
                }
            }
        }

        private void copy(Memory video) {
            int w = video.getInt(0);
            int h = video.getInt(4);
            int fourCc = video.getInt(8);
            Pointer data = video.getPointer(40);
            int stride = video.getInt(48);
            if (w <= 0 || h <= 0 || data == null || (fourCc != FOURCC_BGRA && fourCc != FOURCC_BGRX)) {
                return;
            }
            if (back == null || back.getWidth() != w || back.getHeight() != h) {
                back = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            int[] pixels = ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
            // BGRA in memory read as little-endian ints is 0xAARRGGBB, which TYPE_INT_RGB expects (alpha ignored)
            if (stride == w * 4) {
                data.read(0, pixels, 0, w * h);
            } else {
                for (int y = 0; y < h; y++) {
                    data.read((long) y * stride, pixels, y * w, w);
                }
            }
            BufferedImage done = back;
            BufferedImage previous = front;
            front = done;
            back = previous != null && previous.getWidth() == w && previous.getHeight() == h ? previous : null;
            frames++;
            MediaFrames.frameArrived();
        }

        @Override
        public BufferedImage frame() {
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
        public void release() {
            running = false;
            if (thread != null) {
                try {
                    thread.join(2_000); // capture waits at most 500 ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (recv != null && (thread == null || !thread.isAlive())) {
                lib.recvDestroy.invoke(Void.class, new Object[]{recv});
                log.info("NDI: stopped {}", source);
            }
        }
    }
}
