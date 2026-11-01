package com.jamc.worship.camera;

import com.jamc.worship.config.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The operator's names for the physical cameras: a stable number ("CAM 2") given the first time a device is
 * seen, and an optional nickname ("PREACHER CAMERA") in place of the driver's name ("USB Video Device").
 * Kept in settings.properties ({@code camera.device.<n>} = device, {@code camera.nickname.<n>} = nickname),
 * so the same camera keeps its number and name after a restart.
 */
public final class CameraNames {

    private static final int MAX = 32;

    private final AppConfig config;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public CameraNames(AppConfig config) {
        this.config = config;
    }

    /** "CAM 2": the camera's number, given now if the device is new. Null for a blank name. */
    public String tag(String device) {
        int n = number(device);
        return n <= 0 ? null : "CAM " + n;
    }

    /** The camera's number (1, 2, 3...), given now if the device is new; 0 for a blank name. */
    public synchronized int number(String device) {
        if (device == null || device.isBlank()) {
            return 0;
        }
        int free = 0;
        for (int n = 1; n <= MAX; n++) {
            String d = config.get(deviceKey(n), null);
            if (device.equals(d)) {
                return n;
            }
            if (d == null && free == 0) {
                free = n;
            }
        }
        if (free == 0) {
            return 0;
        }
        config.set(deviceKey(free), device);
        config.save();
        return free;
    }

    /** The operator's nickname, or null. */
    public String nickname(String device) {
        int n = number(device);
        return n <= 0 ? null : config.get(nicknameKey(n), null);
    }

    /** The nickname, or the device name when it has none. */
    public String label(String device) {
        if (device == null || device.isBlank()) {
            return "First available camera";
        }
        String nick = nickname(device);
        return nick == null ? device : nick;
    }

    /** "CAM 2 · PREACHER CAMERA" (or "CAM 2 · USB Video Device" without a nickname). */
    public String full(String device) {
        String tag = tag(device);
        return tag == null ? label(device) : tag + " · " + label(device);
    }

    /** Renames a camera; a blank name goes back to the device name. */
    public void setNickname(String device, String nickname) {
        int n = number(device);
        if (n <= 0) {
            return;
        }
        String clean = nickname == null || nickname.isBlank() ? null : nickname.strip();
        if (Objects.equals(clean, config.get(nicknameKey(n), null))) {
            return;
        }
        config.set(nicknameKey(n), clean);
        config.save();
        listeners.forEach(Runnable::run);
    }

    /** The audio input chosen for this camera: a device name, "" for none, or null for automatic. */
    public String audioInput(String device) {
        int n = number(device);
        return n <= 0 ? null : config.get("camera.audio." + n, null);
    }

    /** Chooses the camera's audio input: a device name, "" for none, null for automatic. */
    public void setAudioInput(String device, String input) {
        int n = number(device);
        if (n > 0) {
            config.set("camera.audio." + n, input);
            config.save();
            listeners.forEach(Runnable::run);
        }
    }

    /** Every camera seen before, by number (some may be unplugged now). */
    public synchronized List<String> remembered() {
        List<String> out = new ArrayList<>();
        for (int n = 1; n <= MAX; n++) {
            String d = config.get(deviceKey(n), null);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    /** Drops a camera that is gone for good: its number and nickname become free. */
    public synchronized void forget(String device) {
        for (int n = 1; n <= MAX; n++) {
            if (Objects.equals(device, config.get(deviceKey(n), null))) {
                config.set(deviceKey(n), null);
                config.set(nicknameKey(n), null);
                config.set("camera.audio." + n, null);
                config.save();
                listeners.forEach(Runnable::run);
                return;
            }
        }
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    private static String deviceKey(int n) {
        return "camera.device." + n;
    }

    private static String nicknameKey(int n) {
        return "camera.nickname." + n;
    }
}
