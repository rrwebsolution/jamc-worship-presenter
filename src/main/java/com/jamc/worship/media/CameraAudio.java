package com.jamc.worship.media;

import com.jamc.worship.camera.CameraManager;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Live sound for the Program scene: its AUDIO INPUT sources (any connected input, e.g. the audio tech's mixer) and
 * its camera sources (a camera's microphone, a capture card's HDMI audio) that have AUDIO switched on. Off by default
 * for cameras: several camera microphones at once give echo, delay and feedback. They go straight into the recording
 * and the stream (never the speakers, where a microphone would echo); a source set to the monitor device is played there.
 *
 * <p>Each camera's sound is its own audio-only VLC player, separate from the camera's picture session, so
 * switching it on or off never restarts the picture. Players follow the Program scene only (a lyrics or slide
 * TAKE leaves them alone); Preview is never heard. Confined to the EDT.
 */
public final class CameraAudio {

    private static final Logger log = LoggerFactory.getLogger(CameraAudio.class);

    private record Want(String input, int volume, boolean muted, boolean monitorOnly) {
    }

    /**
     * A live sound input of a scene, for its level wave; {@code device} is null when the camera has no input.
     * {@code monitorOnly}: heard on the monitor device only, never in the recording or the stream.
     */
    public record SceneInput(String name, String device, int volume, boolean muted, boolean monitorOnly) {
    }

    private final CameraManager cameras;
    private final AudioRouting routing;
    private final Map<String, VideoPlayer> players = new HashMap<>();
    private final Map<String, Want> playing = new HashMap<>();
    /** Mute and volume from the mixer: kept while that input stays on Program; the saved scene is not changed. */
    private final Map<String, Boolean> liveMuted = new HashMap<>();
    private final Map<String, Integer> liveVolume = new HashMap<>();
    private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private java.util.List<SceneInput> lastInputs = java.util.List.of();
    private java.util.function.Consumer<Map<String, Double>> sink = g -> { };
    private Map<String, Want> onProgram = Map.of();
    private Scene scene;

    public CameraAudio(CameraManager cameras, AudioRouting routing) {
        this.cameras = cameras;
        this.routing = routing;
        routing.addListener(this::apply);
        cameras.addListener(() -> syncProgram(scene)); // an input found or lost (hot plug)
    }

    /** True when this camera source plays its camera's sound on Program. */
    public static boolean wantsAudio(Source s) {
        return s.isVisible() && (s.getType() == SourceType.AUDIO_INPUT
                || s.getType() == SourceType.CAMERA && Boolean.TRUE.equals(s.getConfig().audioEnabled));
    }

    private String deviceOf(Source s) {
        return s.getType() == SourceType.AUDIO_INPUT ? s.getConfig().audioInput : cameras.audioDevice(s.getConfig().camera);
    }

    /** The live sound inputs of a scene, for the mixer. */
    public java.util.List<SceneInput> inputs(Scene s) {
        if (s == null) {
            return java.util.List.of();
        }
        return s.getSources().stream().filter(CameraAudio::wantsAudio)
                .map(x -> {
                    String device = deviceOf(x);
                    SourceConfig c = x.getConfig();
                    return new SceneInput(x.getName(), device, liveVolume.getOrDefault(device, c.volume == null ? 100 : c.volume),
                            liveMuted.getOrDefault(device, Boolean.TRUE.equals(c.muted)), Boolean.TRUE.equals(c.monitorOnly));
                }).toList();
    }

    /** The live sound inputs on Program now. */
    public java.util.List<SceneInput> programInputs() {
        return inputs(scene);
    }

    /** Called when the inputs on Program change. */
    public void addListener(Runnable l) {
        listeners.add(l);
    }

    /** Receives the inputs for the recording and the stream, with their gains. */
    public void setSink(java.util.function.Consumer<Map<String, Double>> sink) {
        this.sink = sink;
    }

    /** The mixer's volume for an input on Program now. */
    public void setInputVolume(String device, int volume) {
        liveVolume.put(device, volume);
        sendToSink();
        apply();
    }

    /** The mixer's mute for an input on Program now. */
    public void setInputMuted(String device, boolean muted) {
        liveMuted.put(device, muted);
        sendToSink();
        apply();
    }

    private boolean isMuted(Want w) {
        return liveMuted.getOrDefault(w.input(), w.muted());
    }

    private int volumeOf(Want w) {
        return liveVolume.getOrDefault(w.input(), w.volume());
    }

    private void sendToSink() {
        Map<String, Double> gains = new HashMap<>();
        onProgram.values().stream().filter(w -> !w.monitorOnly())
                .forEach(w -> gains.put(w.input(), isMuted(w) ? 0 : volumeOf(w) / 100.0));
        sink.accept(gains);
    }

    /** Program's scene changed: start the camera sound it asks for, stop the rest. */
    public void syncProgram(Scene programScene) {
        scene = programScene;
        Map<String, Want> want = new LinkedHashMap<>();
        if (programScene != null) {
            for (Source s : programScene.getSources()) {
                if (!wantsAudio(s)) {
                    continue;
                }
                SourceConfig c = s.getConfig();
                String input = deviceOf(s);
                if (input != null) {
                    // the same camera twice in one scene is heard once, at the first source's settings
                    want.putIfAbsent(input, new Want(input, c.volume == null ? 100 : c.volume,
                            Boolean.TRUE.equals(c.muted), Boolean.TRUE.equals(c.monitorOnly)));
                }
            }
        }
        onProgram = Map.copyOf(want);
        liveMuted.keySet().retainAll(want.keySet());
        liveVolume.keySet().retainAll(want.keySet());
        sendToSink();
        // only the monitor device is played: on the Program output a microphone would be picked up again
        want.values().removeIf(w -> !w.monitorOnly());
        players.entrySet().removeIf(e -> {
            if (!want.containsKey(e.getKey())) {
                e.getValue().release();
                playing.remove(e.getKey());
                log.info("Camera audio off: {}", e.getKey());
                return true;
            }
            return false;
        });
        java.util.List<SceneInput> now = programInputs();
        if (!now.equals(lastInputs)) {
            lastInputs = now;
            listeners.forEach(Runnable::run);
        }
        if (!MediaEngine.get().isAvailable()) {
            return;
        }
        for (Want w : want.values()) {
            VideoPlayer p = players.get(w.input());
            if (p == null) {
                p = new VideoPlayer();
                players.put(w.input(), p);
                playing.put(w.input(), w);
                applyTo(p, w);
                p.play("dshow://", false, mutedNow(w), ":dshow-vdev=none", ":dshow-adev=" + w.input(), ":live-caching=150");
                log.info("Camera audio on: {}", w.input());
            } else if (!Objects.equals(playing.get(w.input()), w)) {
                playing.put(w.input(), w);
                applyTo(p, w); // volume / mute / output changed: no restart
            }
        }
    }

    /** Re-applies volumes and devices (the mixer or audio routing changed). */
    public void apply() {
        playing.forEach((input, w) -> {
            VideoPlayer p = players.get(input);
            if (p != null) {
                applyTo(p, w);
            }
        });
    }

    private void applyTo(VideoPlayer p, Want w) {
        p.setOutputDevice(w.monitorOnly() ? routing.monitorDeviceInUse() : routing.programDeviceInUse());
        double gain = routing.masterMuted() ? 0 : routing.masterVolume() / 100.0;
        p.setVolume(AudioRouting.volume(volumeOf(w), gain));
        p.setMuted(mutedNow(w));
    }

    private boolean mutedNow(Want w) {
        return isMuted(w) || routing.masterMuted() || (w.monitorOnly() && routing.programOnly());
    }

    /** The inputs heard on Program now (for the mixer / status). */
    public java.util.Set<String> active() {
        return java.util.Set.copyOf(players.keySet());
    }

    public void shutdown() {
        players.values().forEach(VideoPlayer::release);
        players.clear();
        playing.clear();
    }
}
