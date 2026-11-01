package com.jamc.worship.camera;

import com.jamc.worship.config.AppConfig;

/** Settings → Camera: multiview performance, the camera switch transition and Direct Camera Switching. */
public final class CameraSettings {

    public static final String K_QUALITY = "camera.multiview.quality";
    public static final String K_PREVIEW_FPS = "camera.multiview.fps";
    public static final String K_SWITCH_FADE_MS = "camera.switch.fadeMs";
    public static final String K_DIRECT_SWITCH = "camera.directSwitch";

    /** Multiview picture quality: how smoothly the camera tiles are scaled. */
    public enum Quality {
        LOW("Low"), MEDIUM("Medium"), HIGH("High");

        private final String label;

        Quality(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final int[] PREVIEW_FPS = {15, 30, 60};

    private final AppConfig config;

    public CameraSettings(AppConfig config) {
        this.config = config;
    }

    public Quality quality() {
        try {
            return Quality.valueOf(config.get(K_QUALITY, Quality.MEDIUM.name()));
        } catch (IllegalArgumentException e) {
            return Quality.MEDIUM;
        }
    }

    public void setQuality(Quality q) {
        save(K_QUALITY, q.name());
    }

    /** Frames per second of the multiview tiles (cameras not in Preview or Program refresh at half this). */
    public int previewFps() {
        int fps = parse(config.get(K_PREVIEW_FPS, "30"), 30);
        return fps <= 15 ? 15 : fps >= 60 ? 60 : 30;
    }

    public void setPreviewFps(int fps) {
        save(K_PREVIEW_FPS, String.valueOf(fps));
    }

    /**
     * Length of the fade when TAKE switches from one camera scene to another; 0 = CUT (the default, so
     * camera switching feels immediate). Only the camera layers fade; lyrics, logo and lower third stay.
     */
    public int switchFadeMs() {
        return Math.clamp(parse(config.get(K_SWITCH_FADE_MS, "0"), 0), 0, 3000);
    }

    public void setSwitchFadeMs(int ms) {
        save(K_SWITCH_FADE_MS, String.valueOf(Math.clamp(ms, 0, 3000)));
    }

    /**
     * Advanced, off by default: the multiview's SWITCH NOW control puts a camera on Program without Preview /
     * TAKE. Scene library clicks never go live directly, whatever this says.
     */
    public boolean directSwitch() {
        return config.getBoolean(K_DIRECT_SWITCH, false);
    }

    public void setDirectSwitch(boolean on) {
        save(K_DIRECT_SWITCH, String.valueOf(on));
    }

    private void save(String key, String value) {
        config.set(key, value);
        config.save();
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.strip());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
