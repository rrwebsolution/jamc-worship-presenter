package com.jamc.worship.camera;

import java.awt.Color;

/**
 * The state shown on a camera's badge (colors match the app: LIVE green, PREVIEW blue). {@link CameraManager}
 * reports the device states (INITIALIZING, READY, DISCONNECTED, ERROR); PREVIEW and LIVE are added by the UI
 * when a Preview or Program scene uses the camera.
 */
public enum CameraStatus {
    READY("READY", new Color(0x64748B)),
    PREVIEW("PREVIEW", new Color(0x1677FF)),
    LIVE("LIVE", new Color(0x16A34A)),
    INITIALIZING("INITIALIZING", new Color(0xD97706)),
    DISCONNECTED("DISCONNECTED", new Color(0x475569)),
    ERROR("ERROR", new Color(0xB91C1C));

    private final String label;
    private final Color color;

    CameraStatus(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    /** Badge background. */
    public Color color() {
        return color;
    }

    /** True when the camera cannot give a picture (the scene shows its offline image instead). */
    public boolean isOffline() {
        return this == DISCONNECTED || this == ERROR;
    }

    @Override
    public String toString() {
        return label;
    }
}
