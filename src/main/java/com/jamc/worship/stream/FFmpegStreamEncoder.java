package com.jamc.worship.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * One FFmpeg process that turns Program frames plus the mixed audio inputs into H.264 / AAC and writes
 * a recording file or an RTMP(S) stream.
 *
 * <p>Frames arrive from {@link ProgramFrameTap} as the picture changes; one writer thread sends each to FFmpeg at
 * once, which stamps it with the time it arrived, and a frame not sent in time is replaced and counted, so the
 * presentation is never slowed. FFmpeg's progress is parsed for the health display. Stream keys never reach
 * the log: the destination is redacted in every logged command line and error message. Nothing here runs on
 * the Swing thread.
 */
public final class FFmpegStreamEncoder implements ProgramFrameTap.Sink, StreamEncoder {

    private static final Logger log = LoggerFactory.getLogger(FFmpegStreamEncoder.class);
    private static final int POOL = 3;

    public enum Target { RECORDING, STREAM }

    private final Path ffmpeg;
    private final StreamSettings settings;
    private final String encoder;
    private final Target target;
    private final String destination;
    /** This app's own sound (process loopback), when the MEDIA input is {@link StreamSettings#APP_AUDIO}. */
    private volatile LoopbackAudio.Input appAudio;
    /** Where FFmpeg reads the Program frames from (a local port). */
    private java.net.ServerSocket videoServer;

    /** Set before {@link #start()}: where FFmpeg reads this app's sound. */
    public void setAppAudio(LoopbackAudio.Input input) {
        this.appAudio = input;
    }
    private final Listener listener;
    private final BlockingQueue<int[]> free = new ArrayBlockingQueue<>(POOL);
    /** The newest frame not sent yet. */
    private final AtomicReference<int[]> newest = new AtomicReference<>();
    private volatile Thread writer;
    private final List<MicAudio> micInputs = new ArrayList<>();
    private final AtomicLong dropped = new AtomicLong();
    private final Deque<String> lastErrors = new ArrayDeque<>();
    private volatile Process process;
    private volatile boolean stopping;
    private volatile long startNanos;

    /**
     * @param destination a file path for {@link Target#RECORDING}, the full ingest URL for {@link Target#STREAM}
     */
    public FFmpegStreamEncoder(Path ffmpeg, StreamSettings settings, String encoder, Target target, String destination,
                               Listener listener) {
        this.ffmpeg = ffmpeg;
        this.settings = settings;
        this.encoder = encoder;
        this.target = target;
        this.destination = destination;
        this.listener = listener;
        int size = settings.width() * settings.height();
        for (int i = 0; i < POOL; i++) {
            free.add(new int[size]);
        }
    }

    /** Starts FFmpeg and its reader / writer threads. Blocking for a moment: call off the Swing thread. */
    @Override
    public void start() throws IOException {
        // Program frames go to FFmpeg over a local port; its stdin stays free for "q" (STOP: finish the file now)
        videoServer = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        videoServer.setSoTimeout(20_000);
        java.util.Map<String, String> soundInputs = new java.util.HashMap<>();
        Object[][] devices = {{settings.micDevice(), settings.micMuted()}, {settings.auxDevice(), settings.auxMuted()},
                {settings.mediaDevice(), settings.mediaMuted()}};
        for (Object[] d : devices) {
            String device = (String) d[0];
            if (device != null && !(Boolean) d[1] && !StreamSettings.APP_AUDIO.equals(device) && !soundInputs.containsKey(device)) {
                MicAudio mic = MicAudio.start(ffmpeg, device);
                micInputs.add(mic);
                soundInputs.put(device, mic.url());
            }
        }
        List<String> cmd = command(ffmpeg.toString(), settings, encoder, target, destination, appAudio,
                "tcp://127.0.0.1:" + videoServer.getLocalPort(), soundInputs);
        log.info("Starting {} encoder: {}", target, redact(String.join(" ", cmd)));
        process = new ProcessBuilder(cmd).start();
        startNanos = System.nanoTime();
        Thread.ofPlatform().daemon().name("ffmpeg-writer-" + target).start(this::writeLoop);
        Thread.ofPlatform().daemon().name("ffmpeg-progress-" + target).start(this::progressLoop);
        Thread.ofPlatform().daemon().name("ffmpeg-log-" + target).start(this::errorLoop);
    }

    /**
     * STOP: "q" tells FFmpeg to finish the file / stream at once (the audio inputs never end by themselves);
     * forced after 10 seconds.
     */
    @Override
    public void stop() {
        stopping = true;
        Thread.ofPlatform().daemon().name("ffmpeg-stop-" + target).start(() -> {
            Process p = process;
            if (p == null) {
                return;
            }
            try {
                try { // the writer sees "stopping" within one frame and closes the picture input
                    OutputStream keys = p.getOutputStream();
                    keys.write('q');
                    keys.flush();
                } catch (IOException e) {
                    log.debug("{} encoder already ended", target);
                }
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    log.warn("{} encoder did not stop in time; ending it", target);
                    p.destroy();
                    if (!p.waitFor(3, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Mutes (or brings back) the microphone inputs MIC and AUX during the recording / stream, e.g. while a video
     * plays on Program, so people talking in the room are not mixed into the video's sound. FFmpeg gets the new
     * level through its command line on stdin ("c" + filter command), so nothing restarts. The MEDIA input is
     * never touched.
     */
    public void setMicsMuted(boolean muted) {
        setMicsMuted(muted, muted);
    }

    /** The same per input: a microphone that is also a live input of the Program scene stays out of the mix. */
    void setMicsMuted(boolean micMuted, boolean auxMuted) {
        Process p = process;
        if (p == null || !p.isAlive() || stopping) {
            return;
        }
        double master = settings.masterMuted() ? 0 : settings.masterVolume() / 100.0;
        StringBuilder keys = new StringBuilder();
        if (settings.micDevice() != null && !settings.micMuted() && !StreamSettings.APP_AUDIO.equals(settings.micDevice())) {
            keys.append(micCommand("mic", micMuted ? 0 : settings.micVolume() / 100.0 * master));
        }
        if (settings.auxDevice() != null && !settings.auxMuted() && !StreamSettings.APP_AUDIO.equals(settings.auxDevice())) {
            keys.append(micCommand("aux", auxMuted ? 0 : settings.auxVolume() / 100.0 * master));
        }
        if (keys.isEmpty()) {
            return;
        }
        try {
            OutputStream out = p.getOutputStream();
            out.write(keys.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException e) {
            log.debug("{} encoder: could not change the microphone level", target, e);
        }
    }

    /** "c" (send a command to a filter), then "volume@mic -1 volume 0.8": the filter, now, new level. */
    static String micCommand(String input, double gain) {
        return "cvolume@" + input + " -1 volume " + fmt(gain) + "\n";
    }

    @Override
    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Keeps a copy of a Program frame as the newest one to send; one that was not sent yet is replaced. */
    @Override
    public void offer(int[] rgb) {
        if (stopping) {
            return;
        }
        int[] buf = free.poll();
        if (buf == null || buf.length != rgb.length) {
            dropped.incrementAndGet();
            if (buf != null) {
                free.offer(buf);
            }
            return;
        }
        System.arraycopy(rgb, 0, buf, 0, rgb.length);
        int[] replaced = newest.getAndSet(buf);
        if (replaced != null) {
            dropped.incrementAndGet();
            free.offer(replaced);
        }
        LockSupport.unpark(writer);
    }

    /**
     * Gives FFmpeg one frame per time slot, evenly. Each slot takes a new frame as soon as one arrives within half a
     * slot of its time, which absorbs the normal unevenness of the Swing thread without repeating or skipping
     * anything; only a frame that really is missing repeats the last one. Slots missed in a stall are skipped, not
     * sent in a burst: FFmpeg stamps frames as they arrive, so bursts would be thrown away and the sound stays right.
     */
    /**
     * 0xRRGGBB pixels to NV12: a brightness plane, then color for each 2x2 block. Under load FFmpeg cannot take 8 MB
     * RGB frames 30 times a second and convert them itself, which is what repeated frames in recordings; this is
     * the format the encoders want, under half the size. Same colors as FFmpeg's own conversion (BT.601, limited range).
     */
    static void toNv12(int[] rgb, int width, int height, byte[] out) {
        int uv = width * height;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int p = rgb[row + x];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                out[row + x] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
        }
        for (int y = 0; y + 1 < height; y += 2) {
            int top = y * width;
            int bottom = top + width;
            for (int x = 0; x + 1 < width; x += 2) {
                int a = rgb[top + x];
                int c = rgb[top + x + 1];
                int d = rgb[bottom + x];
                int e = rgb[bottom + x + 1];
                int r = (((a >> 16) & 0xFF) + ((c >> 16) & 0xFF) + ((d >> 16) & 0xFF) + ((e >> 16) & 0xFF)) >> 2;
                int g = (((a >> 8) & 0xFF) + ((c >> 8) & 0xFF) + ((d >> 8) & 0xFF) + ((e >> 8) & 0xFF)) >> 2;
                int b = ((a & 0xFF) + (c & 0xFF) + (d & 0xFF) + (e & 0xFF)) >> 2;
                out[uv++] = (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                out[uv++] = (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
            }
        }
    }

    private void writeLoop() {
        writer = Thread.currentThread();
        long period = 1_000_000_000L / settings.fps();
        int width = settings.width();
        int height = settings.height();
        byte[] bytes = new byte[width * height * 3 / 2];
        try (java.net.ServerSocket server = videoServer; java.net.Socket socket = server.accept();
             OutputStream out = socket.getOutputStream()) {
            socket.setTcpNoDelay(true);
            int[] current = null;
            long first = 0;
            long slot = 0;
            while (!stopping) {
                if (current == null) { // the clock starts with the first frame
                    current = newest.getAndSet(null);
                    if (current == null) {
                        LockSupport.parkNanos(5_000_000);
                        continue;
                    }
                    first = System.nanoTime();
                    toNv12(current, width, height, bytes);
                    out.write(bytes);
                    slot = 1;
                    continue;
                }
                long due = first + slot * period;
                int[] latest = null;
                while (!stopping) {
                    long now = System.nanoTime();
                    if (now - (due - period / 2) >= 0) {
                        latest = newest.getAndSet(null);
                        if (latest != null || now - (due + period / 2) >= 0) {
                            break;
                        }
                    }
                    LockSupport.parkNanos(1_000_000); // woken early by a new frame
                }
                if (latest != null) {
                    free.offer(current);
                    current = latest;
                    toNv12(current, width, height, bytes);
                }
                out.write(bytes);
                slot = Math.max(slot + 1, (System.nanoTime() - first + period / 2) / period);
            }
        } catch (IOException e) {
            if (!stopping) {
                log.warn("{} encoder input closed: {}", target, e.getMessage());
            }
        }
    }

    private void progressLoop() {
        Process p = process;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            Progress progress = new Progress();
            String line;
            while ((line = r.readLine()) != null) {
                if (progress.accept(line)) {
                    logHealth(progress);
                    listener.stats(new Stats(progress.frame, progress.fps, progress.bitrateKbps,
                            dropped.get() + progress.dropFrames, (System.nanoTime() - startNanos) / 1_000_000));
                }
            }
        } catch (IOException e) {
            log.debug("{} progress stream closed", target);
        }
        int code = waitExit(p);
        micInputs.forEach(MicAudio::close);
        String error = null;
        if (!stopping) { // it ended by itself: a failure the operator must see
            synchronized (lastErrors) {
                error = lastErrors.isEmpty() ? "FFmpeg stopped (exit code " + code + ")." : String.join("\n", lastErrors);
            }
        }
        log.info("{} encoder ended (exit {}){}", target, code, error == null ? "" : ": " + error);
        listener.ended(error);
    }

    private static final long HEALTH_EVERY_NANOS = 30_000_000_000L;
    private long lastHealth;
    private long lastHealthFrame;

    /** A line every 30 s of a livestream: when a stream is reported as choppy, this says what left the app at that moment. */
    private void logHealth(Progress p) {
        long now = System.nanoTime();
        if (target != Target.STREAM || now - lastHealth < HEALTH_EVERY_NANOS) {
            return;
        }
        if (lastHealth != 0) {
            log.info("Livestream health: {} frames in {} s, fps {}, frames dropped {} (ffmpeg {}, replaced {})",
                    p.frame - lastHealthFrame, (now - lastHealth) / 1_000_000_000L, p.fps,
                    dropped.get() + p.dropFrames, p.dropFrames, dropped.get());
        }
        lastHealth = now;
        lastHealthFrame = p.frame;
    }

    private void errorLoop() {
        Process p = process;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String clean = redact(line.strip());
                if (clean.isEmpty()) {
                    continue;
                }
                synchronized (lastErrors) {
                    lastErrors.addLast(clean);
                    while (lastErrors.size() > 12) { // the real reason comes first, then several lines of "could not finish"
                        lastErrors.removeFirst();
                    }
                }
                log.warn("ffmpeg ({}): {}", target, clean);
            }
        } catch (IOException e) {
            log.debug("{} error stream closed", target);
        }
    }

    private static int waitExit(Process p) {
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Hides the stream destination (server + key) in anything that is logged or shown. */
    String redact(String text) {
        if (target == Target.STREAM && destination != null && !destination.isEmpty()) {
            return text.replace(destination, redactedUrl(destination));
        }
        return text;
    }

    /** "rtmps://live-api-s.facebook.com:443/rtmp/•••" */
    static String redactedUrl(String url) {
        int slash = url.lastIndexOf('/');
        return (slash > 0 ? url.substring(0, slash + 1) : "") + "•••";
    }

    /** The FFmpeg command line (public for tests). */
    public static List<String> command(String exe, StreamSettings s, String encoder, Target target, String destination) {
        return command(exe, s, encoder, target, destination, null);
    }

    /** @param appAudio this app's own sound, read from a local port (null = not available) */
    public static List<String> command(String exe, StreamSettings s, String encoder, Target target, String destination,
                                       LoopbackAudio.Input appAudio) {
        return command(exe, s, encoder, target, destination, appAudio, "pipe:0");
    }

    /** @param videoInput where the Program frames come from ("pipe:0", or a local "tcp://127.0.0.1:port") */
    public static List<String> command(String exe, StreamSettings s, String encoder, Target target, String destination,
                                       LoopbackAudio.Input appAudio, String videoInput) {
        return command(exe, s, encoder, target, destination, appAudio, videoInput, java.util.Map.of());
    }

    /** @param soundInputs the local port of each sound device that has its own {@link MicAudio} */
    static List<String> command(String exe, StreamSettings s, String encoder, Target target, String destination,
                                LoopbackAudio.Input appAudio, String videoInput, java.util.Map<String, String> soundInputs) {
        List<String> c = new ArrayList<>(List.of(exe, "-hide_banner", "-loglevel", "warning", "-nostats",
                "-progress", "pipe:1"));
        // input 0: Program frames, stamped as they arrive: what keeps the picture in time with the sound, also when
        // the encoder falls behind for a moment. writeLoop sends them evenly, so the stamps are even too.
        c.addAll(List.of("-thread_queue_size", "64", "-use_wallclock_as_timestamps", "1", "-probesize", "32",
                "-analyzeduration", "0", "-f", "rawvideo",
                "-pix_fmt", "nv12", "-video_size", s.width() + "x" + s.height(), "-framerate", String.valueOf(s.fps()),
                "-i", videoInput));
        // audio inputs: microphone, camera / capture audio, and the loopback carrying Program media (videos, music)
        List<double[]> gains = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String[] inputNames = {"mic", "aux", "media"};
        Object[][] inputs = {{s.micDevice(), s.micMuted(), s.micVolume()},
                {s.auxDevice(), s.auxMuted(), s.auxVolume()}, {s.mediaDevice(), s.mediaMuted(), s.mediaVolume()}};
        for (int n = 0; n < inputs.length; n++) {
            Object[] in = inputs[n];
            String device = (String) in[0];
            if (StreamSettings.APP_AUDIO.equals(device)) {
                if (appAudio != null && !(Boolean) in[1]) { // this app's sound: raw PCM from the loopback capture
                    c.addAll(List.of("-thread_queue_size", "1024", "-probesize", "32",
                            "-analyzeduration", "0",
                            "-f", appAudio.sample(), "-ar", String.valueOf(appAudio.rate()),
                            "-ac", String.valueOf(appAudio.channels()), "-i", appAudio.url()));
                    gains.add(new double[]{(Integer) in[2] / 100.0});
                    names.add(inputNames[n]);
                }
            } else if (device != null && !(Boolean) in[1] && soundInputs.containsKey(device)) {
                // read by its own FFmpeg (MicAudio): a device read here would hold up the picture
                c.addAll(List.of("-thread_queue_size", "1024", "-probesize", "32", "-analyzeduration", "0",
                        "-f", MicAudio.SAMPLE, "-ar", String.valueOf(MicAudio.RATE), "-ac", String.valueOf(MicAudio.CHANNELS),
                        "-i", soundInputs.get(device)));
                gains.add(new double[]{(Integer) in[2] / 100.0});
                names.add(inputNames[n]);
            } else if (device != null && !(Boolean) in[1]) {
                // stamped with the real time it arrives: a device that delivers late or unevenly (some laptop
                // microphones) then gets silence for the missing moments instead of holding up the whole mix
                c.addAll(List.of("-thread_queue_size", "1024", "-use_wallclock_as_timestamps", "1", "-f", "dshow", "-audio_buffer_size", "50",
                        "-i", "audio=" + device));
                gains.add(new double[]{(Integer) in[2] / 100.0});
                names.add(inputNames[n]);
            }
        }
        double master = s.masterMuted() ? 0 : s.masterVolume() / 100.0;
        if (gains.isEmpty()) {
            // platforms expect an audio track: a silent one
            c.addAll(List.of("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000"));
            c.addAll(List.of("-map", "0:v", "-map", "1:a"));
        } else {
            StringBuilder f = new StringBuilder();
            for (int i = 0; i < gains.size(); i++) {
                // the channel's own Audio Mixer filters, mono / balance and sync delay come first (Filters…, Properties…)
                String chain = MixerChannels.get().chain(names.get(i));
                f.append('[').append(i + 1).append(":a]");
                if (!chain.isEmpty()) {
                    f.append(chain).append(',');
                }
                // named (volume@mic / volume@aux / volume@media) so a level can change during the recording
                f.append("volume@").append(names.get(i)).append('=')
                        .append(fmt(gains.get(i)[0] * master))
                        .append(",aresample=48000:async=1000[a").append(i + 1).append("];"); // gaps filled, drift corrected
            }
            String masterChain = MixerChannels.get().chain(MixerChannels.MASTER);
            String mixed = masterChain.isEmpty() ? "aout" : "mixed";
            if (gains.size() == 1) {
                f.append("[a1]anull[").append(mixed).append(']');
            } else {
                for (int i = 0; i < gains.size(); i++) {
                    f.append("[a").append(i + 1).append(']');
                }
                f.append("amix=inputs=").append(gains.size()).append(":normalize=0:dropout_transition=0[").append(mixed).append(']');
            }
            if (!masterChain.isEmpty()) {
                f.append(";[mixed]").append(masterChain).append("[aout]"); // MASTER's filters: on everything together
            }
            c.addAll(List.of("-filter_complex", f.toString(), "-map", "0:v", "-map", "[aout]"));
        }
        // video encoding
        c.addAll(List.of("-fps_mode", "cfr", "-r", String.valueOf(s.fps())));
        c.addAll(videoCodec(encoder, s));
        c.addAll(List.of("-b:v", s.videoKbps() + "k", "-maxrate", s.videoKbps() + "k",
                "-bufsize", (s.videoKbps() * 2) + "k", "-g", String.valueOf(s.fps() * 2)));
        c.addAll(List.of("-c:a", "aac", "-b:a", s.audioKbps() + "k", "-ar", "48000", "-ac", "2"));
        if (target == Target.RECORDING) {
            // fragmented MP4: a power cut or crash still leaves a playable file
            c.addAll(List.of("-f", "mp4", "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-y", destination));
        } else {
            // live: frames go out in the order they are shown, since hardware encoders otherwise send reordered frames
            // that a platform has to put back in time; no duration / size rewrite at the end (a stream cannot seek
            // back), RTMP(S) to the platform. A big send buffer: the Windows default keeps about 64 KB on the way, which at
            // the distance to the platform caps a stream near 3 Mbit/s however fast the internet is (a 6000 kbps
            // stream to Facebook came out at 2-3 Mbit/s with most frames lost, the choppy stream)
            // The fifo keeps the encoder away from the network: a slow moment no longer holds up the picture and the sound
            // (which is what made the stream choppy and out of sync); what cannot be sent in time is dropped.
            c.addAll(List.of("-bf", "0", "-flags", "+global_header", "-f", "fifo", "-fifo_format", "flv",
                    "-format_opts", "flvflags=no_duration_filesize:send_buffer_size=1048576",
                    "-queue_size", "300", "-drop_pkts_on_overflow", "1", "-attempt_recovery", "0", destination));
        }
        return c;
    }

    private static List<String> videoCodec(String encoder, StreamSettings s) {
        return switch (encoder) {
            case "h264_nvenc" -> List.of("-c:v", "h264_nvenc", "-preset", "p4", "-pix_fmt", "yuv420p");
            case "h264_qsv" -> List.of("-c:v", "h264_qsv", "-preset", "veryfast", "-pix_fmt", "nv12");
            case "h264_amf" -> List.of("-c:v", "h264_amf", "-quality", "speed", "-pix_fmt", "yuv420p");
            case "h264_mf" -> List.of("-c:v", "h264_mf", "-pix_fmt", "nv12");
            default -> List.of("-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-pix_fmt", "yuv420p");
        };
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Parses FFmpeg's {@code -progress} key=value blocks. */
    static final class Progress {
        long frame;
        double fps;
        double bitrateKbps;
        long dropFrames;

        /** True at the end of a block ("progress=continue/end"). */
        boolean accept(String line) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                return false;
            }
            String k = line.substring(0, eq).strip();
            String v = line.substring(eq + 1).strip();
            try {
                switch (k) {
                    case "frame" -> frame = Long.parseLong(v);
                    case "fps" -> fps = Double.parseDouble(v);
                    case "bitrate" -> bitrateKbps = v.endsWith("kbits/s") ? Double.parseDouble(v.replace("kbits/s", "")) : 0;
                    case "drop_frames" -> dropFrames = Long.parseLong(v);
                    case "progress" -> {
                        return true;
                    }
                    default -> { }
                }
            } catch (NumberFormatException ignored) {
                // "N/A" while starting
            }
            return false;
        }
    }
}
