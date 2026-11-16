package com.jamc.worship.camera;

import com.jamc.worship.config.AppConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which cameras the operator switched ON (Camera page switch or the remote's switch). Every camera starts OFF: it
 * is switched on once it is ready to be used, and only an ON camera can be sent to Preview from the remote. A camera
 * on Program or Preview always counts as on (the app keeps it running). Use on the UI thread.
 */
public final class CameraPower {
    /** Cameras switched on, separated by '|'. (The older "camera.workspace.off" list meant the opposite default.) */
    static final String K_ON = "camera.power.on";

    private final AppConfig config;
    private final Set<String> on = new LinkedHashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public CameraPower(AppConfig config) {
        this.config = config;
        for (String cam : config.get(K_ON, "").split("\\|")) {
            if (!cam.isBlank()) {
                on.add(cam);
            }
        }
    }

    /** True when the operator switched this camera on. */
    public boolean isOn(String cam) {
        return cam != null && on.contains(cam);
    }

    /** Switches a camera on or off and saves it; listeners (the Camera page, the remote) follow. */
    public void set(String cam, boolean turnOn) {
        if (cam == null || (turnOn ? !on.add(cam) : !on.remove(cam))) {
            return;
        }
        config.set(K_ON, String.join("|", on));
        config.save();
        listeners.forEach(Runnable::run);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }
}
