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

/**
 * One FFmpeg process that turns Program frames plus the mixed audio inputs into H.264 / AAC and writes
 * a recording file or an RTMP(S) stream.
 *
 * <p>Frames arrive from {@link ProgramFrameTap} and are queued in a small pool; when FFmpeg falls
 * behind, frames are dropped (and counted) instead of slowing the presentation. FFmpeg's progress is
 * parsed for the health display. Stream keys never reach the log: the destination is redacted in
 * every logged command line and error message. Nothing here runs on the Swing thread.
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
    /** This app's own sound (Windows loopback), when the MEDIA input is {@link StreamSettings#APP_AUDIO}. */
    private volatile LoopbackAudio.Input appAudio;
    /** Where FFmpeg reads the Program frames from (a local port). */
    private java.net.ServerSocket videoServer;

    /** Set before {@link #start()}: where FFmpeg reads this app's sound. */
    public void setAppAudio(LoopbackAudio.Input input) {
        this.appAudio = input;
    }
    private final Listener listener;
    private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(POOL);
    private final BlockingQueue<byte[]> ready = new ArrayBlockingQueue<>(POOL);
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
        int size = settings.width() * settings.height() * 3;
        for (int i = 0; i < POOL; i++) {
            free.add(new byte[size]);
        }
    }

    /** Starts FFmpeg and its reader / writer threads. Blocking for a moment: call off the Swing thread. */
    @Override
    public void start() throws IOException {
        // Program frames go to FFmpeg over a local port; its stdin stays free for "q" (STOP: finish the file now)
        videoServer = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        videoServer.setSoTimeout(20_000);
        List<String> cmd = command(ffmpeg.toString(), settings, encoder, target, destination, appAudio,
                "tcp://127.0.0.1:" + videoServer.getLocalPort());
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
                ready.offer(new byte[0]); // wakes the writer, which closes the picture input
                try {
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
        Process p = process;
        if (p == null || !p.isAlive() || stopping) {
            return;
        }
        double master = settings.masterMuted() ? 0 : settings.masterVolume() / 100.0;
        StringBuilder keys = new StringBuilder();
        if (settings.micDevice() != null && !settings.micMuted() && !StreamSettings.APP_AUDIO.equals(settings.micDevice())) {
            keys.append(micCommand("mic", muted ? 0 : settings.micVolume() / 100.0 * master));
        }
        if (settings.auxDevice() != null && !settings.auxMuted() && !StreamSettings.APP_AUDIO.equals(settings.auxDevice())) {
            keys.append(micCommand("aux", muted ? 0 : settings.auxVolume() / 100.0 * master));
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

    /** Copies a Program frame into a free buffer; drops it when FFmpeg is behind. */
    @Override
    public void offer(byte[] bgr) {
        if (stopping) {
            return;
        }
        byte[] buf = free.poll();
        if (buf == null || buf.length != bgr.length) {
            dropped.incrementAndGet();
            if (buf != null) {
                free.offer(buf);
            }
            return;
        }
        System.arraycopy(bgr, 0, buf, 0, bgr.length);
        ready.offer(buf);
    }

    private void writeLoop() {
        try (java.net.ServerSocket server = videoServer; java.net.Socket socket = server.accept();
             OutputStream out = new java.io.BufferedOutputStream(socket.getOutputStream(), 1 << 20)) {
            socket.setTcpNoDelay(true);
            while (true) {
                byte[] buf = ready.take();
                if (buf.length == 0 || stopping) {
                    break;
                }
                out.write(buf);
                free.offer(buf);
            }
        } catch (IOException e) {
            if (!stopping) {
                log.warn("{} encoder input closed: {}", target, e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void progressLoop() {
        Process p = process;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            Progress progress = new Progress();
            String line;
            while ((line = r.readLine()) != null) {
                if (progress.accept(line)) {
                    listener.stats(new Stats(progress.frame, progress.fps, progress.bitrateKbps,
                            dropped.get() + progress.dropFrames, (System.nanoTime() - startNanos) / 1_000_000));
                }
            }
        } catch (IOException e) {
            log.debug("{} progress stream closed", target);
        }
        int code = waitExit(p);
        String error = null;
        if (!stopping) { // it ended by itself: a failure the operator must see
            synchronized (lastErrors) {
                error = lastErrors.isEmpty() ? "FFmpeg stopped (exit code " + code + ")." : String.join("\n", lastErrors);
            }
        }
        log.info("{} encoder ended (exit {}){}", target, code, error == null ? "" : ": " + error);
        listener.ended(error);
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
                    while (lastErrors.size() > 6) {
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
        List<String> c = new ArrayList<>(List.of(exe, "-hide_banner", "-loglevel", "warning", "-nostats",
                "-progress", "pipe:1"));
        // input 0: Program frames, timestamped as they arrive so dropped frames never desync audio
        c.addAll(List.of("-thread_queue_size", "64", "-use_wallclock_as_timestamps", "1", "-probesize", "32",
                "-analyzeduration", "0", "-f", "rawvideo",
                "-pix_fmt", "bgr24", "-video_size", s.width() + "x" + s.height(), "-framerate", String.valueOf(s.fps()),
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
                // named (volume@mic / volume@aux / volume@media) so a level can change during the recording
                f.append('[').append(i + 1).append(":a]volume@").append(names.get(i)).append('=')
                        .append(fmt(gains.get(i)[0] * master))
                        .append(",aresample=48000:async=1000[a").append(i + 1).append("];"); // gaps filled, drift corrected
            }
            if (gains.size() == 1) {
                f.append("[a1]anull[aout]");
            } else {
                for (int i = 0; i < gains.size(); i++) {
                    f.append("[a").append(i + 1).append(']');
                }
                f.append("amix=inputs=").append(gains.size()).append(":normalize=0:dropout_transition=0[aout]");
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
            // live: no duration / size rewrite at the end (a stream cannot seek back), RTMP(S) to the platform
            c.addAll(List.of("-flvflags", "no_duration_filesize", "-f", "flv", destination));
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
