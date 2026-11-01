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
 * Sound from cameras (a camera's microphone, a capture card's HDMI audio) for the Program scene's camera
 * sources that have AUDIO switched on. Off by default: several camera microphones at once give echo, delay
 * and feedback, so the church mixer (an audio input of the stream) stays the main Program sound.
 *
 * <p>Each camera's sound is its own audio-only VLC player, separate from the camera's picture session, so
 * switching it on or off never restarts the picture. Players follow the Program scene only (a lyrics or slide
 * TAKE leaves them alone); Preview is never heard. Confined to the EDT.
 */
public final class CameraAudio {

    private static final Logger log = LoggerFactory.getLogger(CameraAudio.class);

    private record Want(String input, int volume, boolean muted, boolean monitorOnly) {
    }

    private final CameraManager cameras;
    private final AudioRouting routing;
    private final Map<String, VideoPlayer> players = new HashMap<>();
    private final Map<String, Want> playing = new HashMap<>();
    private Scene scene;

    public CameraAudio(CameraManager cameras, AudioRouting routing) {
        this.cameras = cameras;
        this.routing = routing;
        routing.addListener(this::apply);
        cameras.addListener(() -> syncProgram(scene)); // an input found or lost (hot plug)
    }

    /** True when this camera source plays its camera's sound on Program. */
    public static boolean wantsAudio(Source s) {
        return s.isVisible() && s.getType() == SourceType.CAMERA && Boolean.TRUE.equals(s.getConfig().audioEnabled);
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
                String input = cameras.audioDevice(c.camera);
                if (input != null) {
                    // the same camera twice in one scene is heard once, at the first source's settings
                    want.putIfAbsent(input, new Want(input, c.volume == null ? 100 : c.volume,
                            Boolean.TRUE.equals(c.muted), Boolean.TRUE.equals(c.monitorOnly)));
                }
            }
        }
        players.entrySet().removeIf(e -> {
            if (!want.containsKey(e.getKey())) {
                e.getValue().release();
                playing.remove(e.getKey());
                log.info("Camera audio off: {}", e.getKey());
                return true;
            }
            return false;
        });
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
        p.setVolume(AudioRouting.volume(w.volume(), gain));
        p.setMuted(mutedNow(w));
    }

    private boolean mutedNow(Want w) {
        return w.muted() || routing.masterMuted();
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
