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
import java.nio.file.Path;

/**
 * One DirectShow sound input (a microphone, a capture card) read by its own small FFmpeg and handed to the encoder as
 * raw sound on a local port. Read inside the encoder's FFmpeg, a microphone holds up the picture: measured with
 * the operator's microphones, 30 new pictures a second became about 6, so saved recordings were choppy.
 *
 * <p>The sound follows the real clock: silence fills the moments a device delivers nothing (also when it stops or was
 * never available, so a recording never hangs on a microphone), and sound arriving too far ahead is dropped.
 */
final class MicAudio implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MicAudio.class);
    static final String SAMPLE = "s16le";
    static final int RATE = 48000;
    static final int CHANNELS = 2;
    private static final int FRAME_BYTES = 4;
    private static final long BEHIND_MS = 50;
    private static final long AHEAD_MS = 200;

    private final String device;
    private final Process process;
    private final ServerSocket server;
    private final Object lock = new Object();
    private OutputStream target;
    private long startNanos;
    private long written;
    private volatile boolean closed;

    private MicAudio(String device, Process process, ServerSocket server) {
        this.device = device;
        this.process = process;
        this.server = server;
    }

    String url() {
        return "tcp://127.0.0.1:" + server.getLocalPort();
    }

    static MicAudio start(Path ffmpeg, String device) throws IOException {
        Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-f", "dshow",
                "-audio_buffer_size", "50", "-i", "audio=" + device, "-f", SAMPLE, "-ar", String.valueOf(RATE),
                "-ac", String.valueOf(CHANNELS), "-flush_packets", "1", "pipe:1").start();
        MicAudio a = new MicAudio(device, p, new ServerSocket(0, 1, InetAddress.getLoopbackAddress()));
        Thread.ofPlatform().daemon().name("mic-accept").start(a::acceptLoop);
        Thread.ofPlatform().daemon().name("mic-read").start(a::readLoop);
        Thread.ofPlatform().daemon().name("mic-clock").start(a::clockLoop);
        Thread.ofPlatform().daemon().name("mic-log").start(a::logLoop);
        return a;
    }

    private void acceptLoop() {
        try {
            Socket s = server.accept();
            s.setTcpNoDelay(true);
            synchronized (lock) {
                target = s.getOutputStream();
                startNanos = System.nanoTime();
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("Sound input \"{}\": the encoder did not connect: {}", device, e.getMessage());
            }
        }
    }

    /** Reads the helper all the time, so it never blocks; only whole sample frames are passed on. */
    private void readLoop() {
        byte[] buf = new byte[4800];
        int kept = 0;
        try (InputStream in = process.getInputStream()) {
            int n;
            while ((n = in.read(buf, kept, buf.length - kept)) > 0) {
                int total = kept + n;
                int whole = total - total % FRAME_BYTES;
                send(buf, whole);
                kept = total - whole;
                System.arraycopy(buf, whole, buf, 0, kept);
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("Sound input \"{}\" stopped: {}", device, e.getMessage());
            }
        }
    }

    private void send(byte[] buf, int length) throws IOException {
        synchronized (lock) {
            if (target == null || dropBecauseAhead(written, length / FRAME_BYTES, dueFrames())) {
                return;
            }
            target.write(buf, 0, length);
            written += length / FRAME_BYTES;
        }
    }

    /** Keeps the sound on time with silence whenever the device has fallen behind. */
    private void clockLoop() {
        byte[] silence = new byte[RATE / 10 * FRAME_BYTES];
        try {
            while (!closed) {
                synchronized (lock) {
                    if (target != null) {
                        long missing = silenceNeeded(written, dueFrames());
                        while (missing > 0) {
                            int frames = (int) Math.min(missing, silence.length / FRAME_BYTES);
                            target.write(silence, 0, frames * FRAME_BYTES);
                            written += frames;
                            missing -= frames;
                        }
                    }
                }
                Thread.sleep(20);
            }
        } catch (IOException e) {
            if (!closed) {
                log.debug("Sound input \"{}\": the encoder closed", device);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void logLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.warn("Sound input \"{}\": {}", device, line.strip());
            }
        } catch (IOException e) {
            // ended
        }
    }

    private long dueFrames() {
        return (System.nanoTime() - startNanos) / 1_000 * RATE / 1_000_000;
    }

    /** Frames of silence to write now: none until the device is clearly behind the clock. */
    static long silenceNeeded(long written, long due) {
        long behind = due - written;
        return behind > RATE * BEHIND_MS / 1000 ? behind : 0;
    }

    /** True when sound arrived so far ahead of the clock that keeping it would delay this input for good. */
    static boolean dropBecauseAhead(long written, long frames, long due) {
        return written + frames - due > RATE * AHEAD_MS / 1000;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        process.destroy();
        try {
            server.close();
        } catch (IOException ignored) {
            // closing
        }
        synchronized (lock) {
            if (target != null) {
                try {
                    target.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
        }
    }
}
