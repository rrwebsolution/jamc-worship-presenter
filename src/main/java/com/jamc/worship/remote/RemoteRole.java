package com.jamc.worship.remote;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static com.jamc.worship.remote.RemotePermission.BIBLE;
import static com.jamc.worship.remote.RemotePermission.BLACK_PROJECTOR;
import static com.jamc.worship.remote.RemotePermission.CAMERAS;
import static com.jamc.worship.remote.RemotePermission.LYRICS;
import static com.jamc.worship.remote.RemotePermission.MEDIA;
import static com.jamc.worship.remote.RemotePermission.PRESENTATION;
import static com.jamc.worship.remote.RemotePermission.PREVIEW;
import static com.jamc.worship.remote.RemotePermission.RECORDING_MONITOR;
import static com.jamc.worship.remote.RemotePermission.SCENES;
import static com.jamc.worship.remote.RemotePermission.SERVICE;
import static com.jamc.worship.remote.RemotePermission.STREAM_MONITOR;
import static com.jamc.worship.remote.RemotePermission.TAKE;

/**
 * Permission presets. A role is not stored: it is whichever preset matches a device's permissions, so the two can
 * never disagree. No role grants settings, API keys, stream keys or the Scene Editor: those have no remote command.
 */
public enum RemoteRole {
    LYRICS_ONLY("Lyrics Only", EnumSet.of(LYRICS)),
    PASTOR("Pastor", EnumSet.of(PRESENTATION)),
    WORSHIP_LEADER("Worship Leader", EnumSet.of(LYRICS, BIBLE, SERVICE, PREVIEW)),
    CAMERA_OPERATOR("Camera Operator", EnumSet.of(SCENES, CAMERAS, PREVIEW, TAKE)),
    MEDIA_TEAM("Media Team", EnumSet.of(LYRICS, BIBLE, PRESENTATION, SERVICE, SCENES, CAMERAS, MEDIA, PREVIEW, TAKE,
            BLACK_PROJECTOR, STREAM_MONITOR, RECORDING_MONITOR)),
    FULL_CONTROL("Full Control", EnumSet.allOf(RemotePermission.class)),
    CUSTOM("Custom", EnumSet.noneOf(RemotePermission.class));

    private final String label;
    private final Set<RemotePermission> permissions;

    RemoteRole(String label, Set<RemotePermission> permissions) {
        this.label = label;
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public String label() {
        return label;
    }

    public Set<RemotePermission> permissions() {
        return permissions;
    }

    /** The preset with exactly these permissions, else Custom. */
    public static RemoteRole of(Set<RemotePermission> permissions) {
        for (RemoteRole r : values()) {
            if (r != CUSTOM && r.permissions.equals(permissions)) {
                return r;
            }
        }
        return CUSTOM;
    }

    @Override
    public String toString() {
        return label;
    }
}
