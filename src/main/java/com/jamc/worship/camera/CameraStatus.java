package com.jamc.worship.camera;

import java.awt.Color;

/**
 * The state shown on a camera's badge (colors match the app: LIVE green, PREVIEW blue). {@link CameraManager}
 * reports the device states (INITIALIZING, READY, DISCONNECTED, ERROR); PREVIEW and LIVE are added by the UI
 * when a Preview or Program scene uses the camera.
 */
public enum CameraStatus {
    /** Dark green badge with bright green text: ready stands out, and is still told apart from LIVE (solid green). */
    READY("READY", new Color(0x14532D)),
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

    /** Badge text: READY in bright green, the others white. */
    public Color textColor() {
        return this == READY ? READY_TEXT : Color.WHITE;
    }

    /** The green of the READY text. */
    public static final Color READY_TEXT = new Color(0x4ADE80);

    /** True when the camera cannot give a picture (the scene shows its offline image instead). */
    public boolean isOffline() {
        return this == DISCONNECTED || this == ERROR;
    }

    @Override
    public String toString() {
        return label;
    }
}
