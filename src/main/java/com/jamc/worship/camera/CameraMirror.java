package com.jamc.worship.camera;

import com.jamc.worship.config.AppConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which cameras are flipped left to right. One setting per camera, shared by the Camera page's Mirror and the Auto
 * Director's MIRROR, and used by the Auto Director's shots on Preview / LIVE and in RECORD THIS SHOT files.
 */
public final class CameraMirror {

    static final String KEY = "camera.mirrored";

    private CameraMirror() {
    }

    public static boolean mirrored(AppConfig config, String cam) {
        return cam != null && Arrays.asList(config.get(KEY, "").split("\\|")).contains(cam);
    }

    public static void set(AppConfig config, String cam, boolean on) {
        if (cam == null) {
            return;
        }
        List<String> list = new ArrayList<>(Arrays.asList(config.get(KEY, "").split("\\|")));
        list.removeIf(s -> s.isBlank() || s.equals(cam));
        if (on) {
            list.add(cam);
        }
        config.set(KEY, String.join("|", list));
        config.save();
    }
}
