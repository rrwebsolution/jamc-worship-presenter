package com.jamc.worship.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Live sound levels for the mixer meters, so the operator can see that a microphone or the app's own
 * sound is really coming in. Only listens (nothing is recorded or sent anywhere) and only while a mixer
 * is on screen. Opening is blocking: call off the Swing thread.
 */
public final class AudioLevels {

    private static final Logger log = LoggerFactory.getLogger(AudioLevels.class);

    private AudioLevels() {
    }

    /** One listened input. {@link #level()} is the loudest moment since the last call, 0..1. */
    public abstract static class Source implements AutoCloseable {
        private volatile float peak;
        protected volatile boolean closed;

        public float level() {
            float p = peak;
            peak = 0;
            return p;
        }

        protected void offer(float value) {
            if (value > peak) {
                peak = value;
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A microphone / capture input, by the name FFmpeg lists for it (e.g. "Microphone (Iriun Webcam)"). */
    public static Source device(String name) throws IOException {
        Mixer.Info found = find(name);
        if (found == null) {
            throw new IOException("\"" + name + "\" is not connected.");
        }
        Mixer mixer = AudioSystem.getMixer(found);
        for (AudioFormat f : new AudioFormat[]{new AudioFormat(48000, 16, 1, true, false),
                new AudioFormat(44100, 16, 1, true, false), new AudioFormat(48000, 16, 2, true, false),
                new AudioFormat(44100, 16, 2, true, false)}) {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, f);
            if (!mixer.isLineSupported(info)) {
                continue;
            }
            try {
                TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                line.open(f, (int) (f.getFrameRate() / 10) * f.getFrameSize());
                line.start();
                return new LineSource(line, f);
            } catch (LineUnavailableException e) {
                log.debug("Level meter: {} not available as {}", name, f);
            }
        }
        throw new IOException("\"" + name + "\" cannot be listened to right now.");
    }

    /** This app's own sound on the Program output device (the same capture recordings use). */
    public static Source appSound(Path toolsDir, String programDevice) throws IOException {
        LoopbackAudio loopback = LoopbackAudio.start(toolsDir, programDevice);
        LoopbackAudio.Input in = loopback.input();
        URI uri = URI.create(in.url());
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 3000);
        } catch (IOException e) {
            loopback.close();
            socket.close();
            throw e;
        }
        return new StreamSource(loopback, socket, in.sample().startsWith("f32") ? 4 : 2, in.sample().startsWith("f32"));
    }

    /** The Java Sound input whose name matches FFmpeg's (Java Sound may garble "®" or cut long names). */
    static Mixer.Info find(String name) {
        String want = key(name);
        Mixer.Info prefix = null;
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer m = AudioSystem.getMixer(mi);
            if (m.getTargetLineInfo().length == 0) {
                continue;
            }
            String have = key(mi.getName());
            if (have.equals(want)) {
                return mi;
            }
            if (have.length() >= 12 && (want.startsWith(have) || have.startsWith(want))) {
                prefix = mi;
            }
        }
        return prefix;
    }

    /** Letters and digits only, lower case: "Microphone Array (Intel® Smart" → "microphonearrayintelsmart". */
    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Peak of 16-bit little-endian samples, 0..1. */
    static float peak16(byte[] buf, int len) {
        int max = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int v = Math.abs((short) ((buf[i] & 0xFF) | (buf[i + 1] << 8)));
            if (v > max) {
                max = v;
            }
        }
        return Math.min(1f, max / 32768f);
    }

    private static final class LineSource extends Source {
        private final TargetDataLine line;

        LineSource(TargetDataLine line, AudioFormat f) {
            this.line = line;
            Thread.ofPlatform().daemon().name("level-" + line.getLineInfo()).start(() -> {
                byte[] buf = new byte[(int) (f.getFrameRate() / 50) * f.getFrameSize()]; // 20 ms
                while (!closed) {
                    int n = line.read(buf, 0, buf.length);
                    if (n <= 0) {
                        break;
                    }
                    offer(peak16(buf, n));
                }
            });
        }

        @Override
        public void close() {
            super.close();
            line.stop();
            line.close();
        }
    }

    private static final class StreamSource extends Source {
        private final LoopbackAudio loopback;
        private final Socket socket;

        StreamSource(LoopbackAudio loopback, Socket socket, int bytes, boolean floats) {
            this.loopback = loopback;
            this.socket = socket;
            Thread.ofPlatform().daemon().name("level-app-sound").start(() -> {
                byte[] buf = new byte[4096 * bytes];
                try (InputStream in = socket.getInputStream()) {
                    int n;
                    while (!closed && (n = in.readNBytes(buf, 0, buf.length)) > 0) {
                        if (floats) {
                            ByteBuffer b = ByteBuffer.wrap(buf, 0, n - n % 4).order(ByteOrder.LITTLE_ENDIAN);
                            float max = 0;
                            while (b.remaining() >= 4) {
                                max = Math.max(max, Math.abs(b.getFloat()));
                            }
                            offer(Math.min(1f, max));
                        } else {
                            offer(peak16(buf, n));
                        }
                    }
                } catch (IOException e) {
                    // closed
                }
            });
        }

        @Override
        public void close() {
            super.close();
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
            loopback.close();
        }
    }
}
