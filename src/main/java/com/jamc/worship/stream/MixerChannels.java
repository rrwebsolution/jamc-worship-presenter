package com.jamc.worship.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jamc.worship.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the operator set per Audio Mixer channel, like OBS's mixer: a name of their own, hidden / pinned / volume locked,
 * the audio filters, mono, balance and sync offset, plus the mixer's own options (inactive / hidden channels,
 * horizontal or vertical layout). Channels are keyed "mic", "aux", "media", "master", or "input:" + the device of a
 * Program scene's sound input. Stored as JSON in the settings ({@value #KEY}).
 *
 * <p>The sound settings become the FFmpeg chain of each channel ({@link #chain}) when a recording or livestream starts.
 */
public final class MixerChannels {

    private static final Logger log = LoggerFactory.getLogger(MixerChannels.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = "audio.mixer";

    public static final String MIC = "mic";
    public static final String AUX = "aux";
    public static final String MEDIA = "media";
    public static final String MASTER = "master";

    /** The channel key of a Program scene's sound input. */
    public static String input(String device) {
        return "input:" + device;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Channel {
        public String name;
        public boolean hidden;
        public boolean pinned;
        public boolean locked;
        public boolean mono;
        /** -100 (left only) .. 100 (right only). */
        public int balance;
        /** Delay in ms, so the sound matches a late picture. */
        public int syncMs;
        public List<AudioFilter> filters = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Options {
        public boolean showInactive;
        public boolean showHidden;
        public boolean keepInactiveRight;
        public boolean horizontal;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class State {
        public Map<String, Channel> channels = new LinkedHashMap<>();
        public Options options = new Options();
        /** True once the microphone got its default sync offset (so an offset set back to 0 stays 0). */
        public boolean micSyncDefault;
        /** True once the AUX channel got its default sync offset. */
        public boolean auxSyncDefault;
    }

    private static volatile MixerChannels instance = new MixerChannels(null);

    private final AppConfig config;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = new State();

    MixerChannels(AppConfig config) {
        this.config = config;
        if (config != null) {
            String text = config.get(KEY, null);
            if (text != null && !text.isBlank()) {
                try {
                    state = JSON.readValue(text, State.class);
                    if (state.options == null) {
                        state.options = new Options();
                    }
                } catch (Exception e) {
                    log.warn("Audio Mixer settings could not be read; starting fresh", e);
                }
            }
            giveMicDefaultSync();
        }
    }

    /**
     * The voice waits for the camera by default: the camera picture (an Iriun phone over Wi-Fi, a USB camera) reaches
     * the recording about 600 ms after the sound; the app already delays the microphone about 120 ms ({@link AvSync}),
     * so the MICROPHONE channel starts with this sync offset (Properties). Measured on the church setup 2026-11-15.
     */
    static final int DEFAULT_MIC_SYNC_MS = 450;

    /**
     * The AUX channel is usually the camera's own microphone (the Iriun phone): its sound still reaches the app
     * before its picture, because the phone needs longer to send video. Measured 2026-11-23 from 11 claps in a
     * recording with the Iriun phone mic on AUX: the hands met a median 83 ms after the clap was heard.
     */
    static final int DEFAULT_AUX_SYNC_MS = 80;

    private void giveMicDefaultSync() {
        if (state.micSyncDefault && state.auxSyncDefault) {
            return;
        }
        if (!state.micSyncDefault) {
            Channel mic = state.channels.computeIfAbsent(MIC, k -> new Channel());
            if (mic.syncMs == 0) {
                mic.syncMs = DEFAULT_MIC_SYNC_MS;
            }
            state.micSyncDefault = true;
        }
        if (!state.auxSyncDefault) {
            Channel aux = state.channels.computeIfAbsent(AUX, k -> new Channel());
            if (aux.syncMs == 0) {
                aux.syncMs = DEFAULT_AUX_SYNC_MS;
            }
            state.auxSyncDefault = true;
        }
        try {
            config.set(KEY, JSON.writeValueAsString(state));
            config.save();
        } catch (Exception e) {
            log.warn("Audio Mixer settings could not be saved", e);
        }
    }

    /** Loads the stored settings; call once at start. */
    public static void install(AppConfig config) {
        instance = new MixerChannels(config);
    }

    public static MixerChannels get() {
        return instance;
    }

    public Options options() {
        return state.options;
    }

    /** The settings of a channel (created on first use). */
    public synchronized Channel channel(String key) {
        return state.channels.computeIfAbsent(key, k -> new Channel());
    }

    /** The settings of a channel, or null when nothing was ever set for it (read only, for the encoder). */
    synchronized Channel find(String key) {
        return state.channels.get(key);
    }

    /** The operator's name for the channel, or {@code fallback}. */
    public String name(String key, String fallback) {
        Channel c = find(key);
        return c == null || c.name == null || c.name.isBlank() ? fallback : c.name;
    }

    public synchronized void unhideAll() {
        state.channels.values().forEach(c -> c.hidden = false);
        changed();
    }

    /** Stores the settings and tells the mixers. */
    public void changed() {
        if (config != null) {
            try {
                config.set(KEY, JSON.writeValueAsString(state));
                config.save();
            } catch (Exception e) {
                log.warn("Audio Mixer settings could not be saved", e);
            }
        }
        listeners.forEach(Runnable::run);
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public void removeListener(Runnable l) {
        listeners.remove(l);
    }

    /**
     * The FFmpeg filters for a channel's sound, in order: its filters, then mono / balance, then the sync delay; ""
     * when nothing is set. Safe from any thread.
     */
    public String chain(String key) {
        Channel c = find(key);
        return c == null ? "" : chain(c);
    }

    static String chain(Channel c) {
        List<String> parts = new ArrayList<>();
        if (c.filters != null) {
            for (AudioFilter f : c.filters) {
                String s = f == null ? null : f.ffmpeg();
                if (s != null) {
                    parts.add(s);
                }
            }
        }
        int balance = Math.clamp(c.balance, -100, 100);
        if (c.mono || balance != 0) {
            double b = balance / 100.0;
            double left = b > 0 ? 1 - b : 1;
            double right = b < 0 ? 1 + b : 1;
            parts.add("aformat=channel_layouts=stereo"); // a mono microphone becomes two channels first
            if (c.mono) {
                parts.add("pan=stereo|c0=" + AudioFilter.num(left / 2) + "*c0+" + AudioFilter.num(left / 2) + "*c1|c1="
                        + AudioFilter.num(right / 2) + "*c0+" + AudioFilter.num(right / 2) + "*c1");
            } else {
                parts.add("pan=stereo|c0=" + AudioFilter.num(left) + "*c0|c1=" + AudioFilter.num(right) + "*c1");
            }
        }
        if (c.syncMs > 0) {
            parts.add("adelay=delays=" + Math.min(c.syncMs, 5000) + ":all=1");
        }
        return String.join(",", parts);
    }
}
