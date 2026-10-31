package com.jamc.worship.media;

import com.jamc.worship.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.player.base.AudioDevice;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where Program and Preview media sound goes, and how loud.
 *
 * <ul>
 *   <li><b>Program output</b>: the congregation's speakers (Windows default unless chosen). Program
 *       videos, scene video sources and the audio player play here.</li>
 *   <li><b>Preview monitor</b>: off by default, so a video in Preview is never heard twice in the
 *       church. When the operator turns MONITOR on, Preview's scene videos play on the monitor device
 *       (e.g. headphones).</li>
 *   <li><b>Mixer</b>: a MEDIA channel and a MASTER, each with volume and mute, multiplied into every
 *       Program media player's own volume.</li>
 * </ul>
 * Settings live in settings.properties ({@code audio.*}); the monitor switch is per session.
 */
public final class AudioRouting {

    private static final Logger log = LoggerFactory.getLogger(AudioRouting.class);

    /** An audio output; an empty id is the Windows default device. */
    public record Device(String id, String name) {
        public static final Device DEFAULT = new Device("", "Windows default output");

        @Override
        public String toString() {
            return name;
        }
    }

    /** What happens to a scene video's position when its scene goes from Preview to Program. */
    public enum TakeBehavior {
        RESTART("Start Program video from the beginning"), CONTINUE("Continue from the Preview position");

        private final String label;

        TakeBehavior(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final String K_PROGRAM_DEVICE = "audio.program.device";
    static final String K_MONITOR_DEVICE = "audio.monitor.device";
    static final String K_MEDIA_VOLUME = "audio.media.volume";
    static final String K_MEDIA_MUTED = "audio.media.muted";
    static final String K_MASTER_VOLUME = "audio.master.volume";
    static final String K_MASTER_MUTED = "audio.master.muted";
    static final String K_TAKE = "video.take.behavior";

    private final AppConfig config;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile List<Device> devices = List.of(Device.DEFAULT);
    private boolean previewMonitor;

    public AudioRouting(AppConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------------ devices

    /** Re-reads the audio outputs (call after plugging in headphones, HDMI or a USB interface). */
    public List<Device> refreshDevices() {
        List<Device> out = new ArrayList<>();
        out.add(Device.DEFAULT);
        MediaEngine engine = MediaEngine.get();
        if (engine.isAvailable()) {
            MediaPlayer probe = null;
            try {
                probe = engine.newAudioPlayer();
                for (AudioDevice d : probe.audio().outputDevices()) {
                    if (d.getDeviceId() != null && !d.getDeviceId().isBlank()) {
                        out.add(new Device(d.getDeviceId(), d.getLongName() == null ? d.getDeviceId() : d.getLongName()));
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                log.warn("Could not list audio outputs", e);
            } finally {
                if (probe != null) {
                    probe.release();
                }
            }
        }
        List<Device> now = List.copyOf(out);
        if (!now.equals(devices)) { // plugged in or removed: players move to the right device (or the default)
            devices = now;
            log.info("Audio outputs: {}", devices.stream().map(Device::name).toList());
            fire();
        }
        return devices;
    }

    public List<Device> devices() {
        return devices;
    }

    /** The chosen Program output id ("" = Windows default). */
    public String programDevice() {
        return config.get(K_PROGRAM_DEVICE, "");
    }

    /** The chosen Preview monitor id ("" = Windows default). */
    public String monitorDevice() {
        return config.get(K_MONITOR_DEVICE, "");
    }

    /** The Program device to actually use: the chosen one if it is connected, else the Windows default. */
    public String programDeviceInUse() {
        return available(programDevice()) ? programDevice() : "";
    }

    public String monitorDeviceInUse() {
        return available(monitorDevice()) ? monitorDevice() : "";
    }

    /** False when a chosen device is not connected now (the Windows default is used meanwhile). */
    public boolean available(String id) {
        return id == null || id.isEmpty() || devices.size() <= 1 || devices.stream().anyMatch(d -> d.id().equals(id));
    }

    public String nameOf(String id) {
        return devices.stream().filter(d -> d.id().equals(id)).map(Device::name).findFirst()
                .orElse(id == null || id.isEmpty() ? Device.DEFAULT.name() : "Unavailable device");
    }

    public void setProgramDevice(String id) {
        save(K_PROGRAM_DEVICE, id == null ? "" : id);
    }

    public void setMonitorDevice(String id) {
        save(K_MONITOR_DEVICE, id == null ? "" : id);
    }

    // ------------------------------------------------------------------ mixer

    public int mediaVolume() { return parse(config.get(K_MEDIA_VOLUME, "100"), 100); }
    public boolean mediaMuted() { return config.getBoolean(K_MEDIA_MUTED, false); }
    public int masterVolume() { return parse(config.get(K_MASTER_VOLUME, "100"), 100); }
    public boolean masterMuted() { return config.getBoolean(K_MASTER_MUTED, false); }

    public void setMediaVolume(int v) { save(K_MEDIA_VOLUME, String.valueOf(Math.clamp(v, 0, 150))); }
    public void setMediaMuted(boolean m) { save(K_MEDIA_MUTED, String.valueOf(m)); }
    public void setMasterVolume(int v) { save(K_MASTER_VOLUME, String.valueOf(Math.clamp(v, 0, 150))); }
    public void setMasterMuted(boolean m) { save(K_MASTER_MUTED, String.valueOf(m)); }

    /** Media × master as a factor (1.0 = unchanged; 0 when either is muted). */
    public double mediaGain() {
        if (mediaMuted() || masterMuted()) {
            return 0;
        }
        return mediaVolume() / 100.0 * masterVolume() / 100.0;
    }

    /**
     * VLC volume (0..200, 100 = as recorded) for a Program media source at {@code sourceVolume}% through
     * the media channel and master.
     */
    public int programVolume(int sourceVolume) {
        return volume(sourceVolume, mediaGain());
    }

    static int volume(int sourceVolume, double gain) {
        return (int) Math.round(Math.clamp(sourceVolume * gain, 0, 200));
    }

    // ------------------------------------------------------------------ preview monitor and take

    /** True while the operator listens to Preview's videos on the monitor device. Off at every start. */
    public boolean previewMonitor() {
        return previewMonitor;
    }

    public void setPreviewMonitor(boolean on) {
        if (on != previewMonitor) {
            previewMonitor = on;
            fire();
        }
    }

    public TakeBehavior takeBehavior() {
        return "CONTINUE".equals(config.get(K_TAKE, "RESTART")) ? TakeBehavior.CONTINUE : TakeBehavior.RESTART;
    }

    public void setTakeBehavior(TakeBehavior b) {
        save(K_TAKE, (b == null ? TakeBehavior.RESTART : b).name());
    }

    // ------------------------------------------------------------------ listeners

    /** Called on the Swing event thread after any routing or mixer change, so players re-apply it. */
    public void addListener(Runnable l) {
        listeners.add(l);
    }

    private void save(String key, String value) {
        if (Objects.equals(config.get(key, null), value)) {
            return;
        }
        config.set(key, value);
        config.save();
        fire();
    }

    private void fire() {
        // players are confined to the Swing event thread; device lists may be refreshed in the background
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            listeners.forEach(Runnable::run);
        } else {
            javax.swing.SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
        }
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.strip());
        } catch (RuntimeException e) {
            return def;
        }
    }
}
