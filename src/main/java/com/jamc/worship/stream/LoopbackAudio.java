package com.jamc.worship.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * This app's own sound for recordings and the livestream: only what this process and its children play
 * (VLC runs inside it, YouTube's Chromium runs as its children), captured with Windows process loopback
 * (Windows 10 version 2004 or newer). Other programs on the PC are never included, on any output device, and
 * the capture never falls back to the whole device. No "Stereo Mix" or VB-Cable needed.
 *
 * <p>A small helper ({@code jamc-loopback.exe}, compiled once from {@code LoopbackCapture.cs} with the C#
 * compiler every Windows has) writes the sound as raw PCM; FFmpeg reads it from a local TCP port as one
 * more audio input. Until FFmpeg connects the sound is dropped, so it starts in time with the picture.
 */
public final class LoopbackAudio implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LoopbackAudio.class);
    private static final String SOURCE = "/stream/LoopbackCapture.cs";
    private static final int FRAME_BYTES = 8; // the helper's format: stereo 32-bit float
    private static final int RATE = 48000;
    /** Sound ahead of the real time by more than this is dropped: FFmpeg times this sound by counting it. */
    private static final long AHEAD_MS = 40;

    /** How FFmpeg reads the sound: {@code -f <sample> -ar <rate> -ac <channels> -i <url>}. */
    public record Input(String url, String sample, int rate, int channels) {
    }

    private final Process process;
    private final ServerSocket server;
    private final Input input;
    private volatile OutputStream target;
    private volatile long connectedNanos;
    private volatile boolean closed;
    /** The scene's live sound inputs, added to this sound; set before FFmpeg connects. */
    private volatile LiveInputs.Mixer inputs;

    private LoopbackAudio(Process process, ServerSocket server, Input input) {
        this.process = process;
        this.server = server;
        this.input = input;
    }

    public Input input() {
        return input;
    }

    /**
     * Starts capturing this app's sound. Blocking for a moment (compiles the helper the first time): call off
     * the Swing thread.
     */
    public static LoopbackAudio start(Path toolsDir) throws IOException {
        Path exe = helper(toolsDir);
        Process p = new ProcessBuilder(exe.toString(), String.valueOf(ProcessHandle.current().pid())).start();
        BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            try {
                return err.readLine();
            } catch (IOException e) {
                return null;
            }
        });
        String line;
        try {
            line = first.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            p.destroyForcibly();
            throw new IOException("The app sound capture did not start.");
        }
        if (line == null || !line.startsWith("FORMAT ")) {
            p.destroyForcibly();
            throw new IOException("The app sound could not be captured: " + (line == null ? "no answer"
                    : line.startsWith("ERROR ") ? line.substring(6) : line));
        }
        String[] f = line.split(" "); // FORMAT f32le 48000 2
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Input input = new Input("tcp://127.0.0.1:" + server.getLocalPort(), f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]));
        LoopbackAudio a = new LoopbackAudio(p, server, input);
        Thread.ofPlatform().daemon().name("loopback-accept").start(a::acceptLoop);
        Thread.ofPlatform().daemon().name("loopback-pump").start(a::pumpLoop);
        Thread.ofPlatform().daemon().name("loopback-log").start(() -> {
            try {
                String l;
                while ((l = err.readLine()) != null) {
                    log.warn("App sound capture: {}", l);
                }
            } catch (IOException ignored) {
                // ended
            }
        });
        log.info("Capturing only this app's sound ({} {} Hz, {} ch)", input.sample(), input.rate(), input.channels());
        return a;
    }

    void mixWith(LiveInputs.Mixer mixer) {
        inputs = mixer;
    }

    /** FFmpeg connects once; the sound flows to it from then on. */
    private void acceptLoop() {
        try {
            Socket s = server.accept();
            s.setTcpNoDelay(true);
            connectedNanos = System.nanoTime();
            target = s.getOutputStream();
        } catch (IOException e) {
            if (!closed) {
                log.warn("App sound: FFmpeg did not connect: {}", e.getMessage());
            }
        }
    }

    /** Reads the helper all the time (so it never blocks); sends to FFmpeg once it is connected. */
    private void pumpLoop() {
        byte[] buf = new byte[16384];
        int kept = 0;
        long sent = 0;
        long droppedFrames = 0;
        try (InputStream in = process.getInputStream()) {
            int n;
            while ((n = in.read(buf, kept, buf.length - kept)) > 0) {
                int total = kept + n;
                int whole = total - total % FRAME_BYTES; // only whole sample frames can be mixed
                OutputStream out = target;
                if (out != null) {
                    int frames = whole / FRAME_BYTES;
                    long due = (System.nanoTime() - connectedNanos) / 1_000 * RATE / 1_000_000;
                    if (keepsTime(sent, frames, due)) {
                        silenceInvalid(buf, whole);
                        LiveInputs.Mixer mixer = inputs;
                        if (mixer != null) {
                            mixer.mix(buf, frames);
                        }
                        out.write(buf, 0, whole);
                        sent += frames;
                    } else if (droppedFrames == 0) {
                        droppedFrames = frames;
                        log.info("App sound ran ahead of the clock; dropping the excess so it stays in time with the picture");
                    }
                }
                kept = total - whole;
                System.arraycopy(buf, whole, buf, 0, kept);
            }
        } catch (IOException e) {
            if (!closed) {
                log.info("App sound stream ended: {}", e.getMessage());
            }
        } finally {
            close();
        }
    }

    /**
     * False when these frames would put the sound too far ahead of the real time. The helper fills quiet moments with
     * silence and then also delivers what Windows had kept back, so without this the sound stays late for the whole
     * recording or stream (measured: 142 ms in one run, 11 ms in the next).
     */
    static boolean keepsTime(long sent, long frames, long due) {
        return sent + frames - due <= RATE * AHEAD_MS / 1000;
    }

    /**
     * Makes invalid samples (NaN, infinity) silent. One of them from a player makes FFmpeg's sound encoder fail, and that
     * ended a live stream.
     */
    static void silenceInvalid(byte[] buf, int length) {
        java.nio.ByteBuffer samples = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + Float.BYTES <= length; i += Float.BYTES) {
            if (!Float.isFinite(samples.getFloat(i))) {
                samples.putFloat(i, 0f);
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        LiveInputs.Mixer mixer = inputs;
        if (mixer != null) {
            mixer.close();
        }
        process.destroy();
        try {
            server.close();
        } catch (IOException ignored) {
            // closing
        }
        OutputStream out = target;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    /** The helper exe in {@code toolsDir}, compiled from the bundled source when missing or outdated. */
    static synchronized Path helper(Path toolsDir) throws IOException {
        byte[] source;
        try (InputStream in = LoopbackAudio.class.getResourceAsStream(SOURCE)) {
            if (in == null) {
                throw new IOException("The app sound capture is missing from this build.");
            }
            source = in.readAllBytes();
        }
        Files.createDirectories(toolsDir);
        Path exe = toolsDir.resolve("jamc-loopback.exe");
        Path stamp = toolsDir.resolve("jamc-loopback.version");
        String version = Integer.toHexString(java.util.Arrays.hashCode(source));
        if (Files.isRegularFile(exe) && Files.isRegularFile(stamp) && Files.readString(stamp).strip().equals(version)) {
            return exe;
        }
        Path cs = toolsDir.resolve("LoopbackCapture.cs");
        Files.write(cs, source);
        String windir = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
        Path csc = Path.of(windir, "Microsoft.NET", "Framework64", "v4.0.30319", "csc.exe");
        if (!Files.isRegularFile(csc)) {
            csc = Path.of(windir, "Microsoft.NET", "Framework", "v4.0.30319", "csc.exe");
        }
        if (!Files.isRegularFile(csc)) {
            throw new IOException("The .NET C# compiler was not found, so this app's sound cannot be captured.");
        }
        Process p = new ProcessBuilder(csc.toString(), "-nologo", "-optimize", "-out:" + exe, cs.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0 || !Files.isRegularFile(exe)) {
                throw new IOException("The app sound capture could not be built: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        Files.writeString(stamp, version);
        log.info("Built the app sound capture: {}", exe);
        return exe;
    }
}
