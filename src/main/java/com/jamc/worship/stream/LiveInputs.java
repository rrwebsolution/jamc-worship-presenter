package com.jamc.worship.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Program scene's live sound inputs (Audio Input sources, camera audio), mixed straight into this app's sound on its
 * way to the recording and the stream. They are never played on the speakers, where a microphone would be picked up
 * again and echo. Each input is read by its own small FFmpeg, only while something is being recorded or streamed.
 */
final class LiveInputs {

    private static final Logger log = LoggerFactory.getLogger(LiveInputs.class);
    private static final int RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int RING_FRAMES = RATE * 2;
    /** Kept in hand: with a 20 ms buffer the longest wait for a device measured 43 ms. Every ms here makes the sound later. */
    static final int LEAD_FRAMES = RATE * 60 / 1000;
    /** More behind than this (the clocks of two devices drift): jump back to {@link #LEAD_FRAMES}. */
    static final int MAX_BEHIND_FRAMES = RATE * 140 / 1000;

    private final Map<String, Feed> feeds = new ConcurrentHashMap<>();
    private Map<String, Double> gains = Map.of();
    private Path ffmpeg;
    private int mixers;

    /** The inputs to hear and their gain (0 = muted, 1 = 100 %). */
    synchronized void set(Map<String, Double> wanted) {
        gains = Map.copyOf(wanted);
        update();
    }

    /** One destination (recording or stream) starts mixing: inputs are read while there is at least one. */
    synchronized Mixer attach(Path ffmpegExe) {
        ffmpeg = ffmpegExe;
        mixers++;
        update();
        return new Mixer();
    }

    private synchronized void detach() {
        mixers--;
        update();
    }

    private void update() {
        for (Iterator<Map.Entry<String, Feed>> it = feeds.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Feed> e = it.next();
            if (mixers <= 0 || !gains.containsKey(e.getKey())) {
                e.getValue().close();
                it.remove();
            }
        }
        if (mixers <= 0) {
            return;
        }
        gains.forEach((device, gain) -> {
            Feed f = feeds.get(device);
            if (f == null) {
                try {
                    f = new Feed(ffmpeg, device);
                    feeds.put(device, f);
                } catch (IOException e) {
                    log.warn("Sound input \"{}\" could not be opened: {}", device, e.getMessage());
                    return;
                }
            }
            f.gain = gain.floatValue();
        });
    }

    /** Adds the inputs to one destination's sound. Used by one thread. */
    final class Mixer implements AutoCloseable {
        private final Map<Feed, long[]> positions = new HashMap<>();
        private boolean closed;

        /** Adds to {@code frames} stereo frames of little-endian 32-bit float sound in {@code buf}. */
        void mix(byte[] buf, int frames) {
            ByteBuffer out = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            positions.keySet().retainAll(feeds.values());
            for (Feed feed : feeds.values()) {
                long[] pos = positions.computeIfAbsent(feed, f -> new long[]{-1});
                pos[0] = next(pos[0], feed.written);
                if (pos[0] < 0) {
                    continue; // collecting the lead
                }
                float gain = feed.gain;
                int n = (int) Math.min(frames, feed.written - pos[0]);
                for (int i = 0; i < n; i++) {
                    int at = (int) ((pos[0] + i) % RING_FRAMES) * CHANNELS;
                    for (int c = 0; c < CHANNELS; c++) {
                        int index = (i * CHANNELS + c) * Float.BYTES;
                        out.putFloat(index, out.getFloat(index) + feed.ring[at + c] * gain);
                    }
                }
                pos[0] = n < frames ? -1 : pos[0] + n; // ran dry: collect the lead again
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                detach();
            }
        }
    }

    /** Where to read next: -1 until {@link #LEAD_FRAMES} are in hand, back to that lead when too far behind. */
    static long next(long position, long written) {
        if (position < 0) {
            return written >= LEAD_FRAMES ? written - LEAD_FRAMES : -1;
        }
        return written - position > MAX_BEHIND_FRAMES ? written - LEAD_FRAMES : position;
    }

    private static final class Feed {
        final float[] ring = new float[RING_FRAMES * CHANNELS];
        final Process process;
        volatile long written;
        volatile float gain;

        Feed(Path ffmpeg, String device) throws IOException {
            List<String> cmd = new java.util.ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error",
                    "-f", "dshow", "-audio_buffer_size", "20", "-i", "audio=" + device));
            String chain = MixerChannels.get().chain(MixerChannels.input(device)); // its Audio Mixer filters
            if (!chain.isEmpty()) {
                cmd.addAll(List.of("-af", chain));
            }
            cmd.addAll(List.of("-f", "s16le", "-ar", String.valueOf(RATE), "-ac", String.valueOf(CHANNELS),
                    "-flush_packets", "1", "pipe:1"));
            process = new ProcessBuilder(cmd).start();
            Thread.ofPlatform().daemon().name("input-read").start(this::read);
            Thread.ofPlatform().daemon().name("input-log").start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.contains("non monotonic")) { // harmless with a small buffer: this output has no timestamps
                            log.warn("Sound input \"{}\": {}", device, line.strip());
                        }
                    }
                } catch (IOException e) {
                    // ended
                }
            });
        }

        private void read() {
            byte[] buf = new byte[4800];
            int kept = 0;
            long frames = 0;
            try (InputStream in = process.getInputStream()) {
                int n;
                while ((n = in.read(buf, kept, buf.length - kept)) > 0) {
                    int total = kept + n;
                    int whole = total - total % (CHANNELS * 2);
                    for (int i = 0; i < whole; i += 2) {
                        short v = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                        ring[(int) ((frames * CHANNELS + i / 2) % ring.length)] = v / 32768f;
                    }
                    frames += whole / (CHANNELS * 2);
                    written = frames;
                    kept = total - whole;
                    System.arraycopy(buf, whole, buf, 0, kept);
                }
            } catch (IOException e) {
                // closed
            }
        }

        void close() {
            process.destroy();
        }
    }
}
