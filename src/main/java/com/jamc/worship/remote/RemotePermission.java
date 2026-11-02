package com.jamc.worship.remote;

import java.util.EnumSet;
import java.util.Set;

/** What a remote device may do. The desktop decides per device. Saved by name, so new values can be added later. */
public enum RemotePermission {
    LYRICS("Lyrics", Group.CONTENT),
    BIBLE("Bible", Group.CONTENT),
    PRESENTATION("Presentation", Group.CONTENT),
    SERVICE("Service", Group.CONTENT),
    SCENES("Scenes", Group.PRODUCTION),
    CAMERAS("Cameras", Group.PRODUCTION),
    MEDIA("Media", Group.PRODUCTION),
    PREVIEW("Preview", Group.OUTPUT),
    TAKE("TAKE", Group.OUTPUT),
    BLACK_PROJECTOR("Black Projector", Group.OUTPUT),
    STREAM_MONITOR("Stream Status", Group.MONITORING),
    RECORDING_MONITOR("Recording Status", Group.MONITORING);

    /** How the permissions are arranged on the desktop. */
    public enum Group { CONTENT, PRODUCTION, OUTPUT, MONITORING }

    private final String label;
    private final Group group;

    RemotePermission(String label, Group group) {
        this.label = label;
        this.group = group;
    }

    /** A new device starts with Lyrics only; the operator gives it more per device. */
    public static Set<RemotePermission> defaults() {
        return EnumSet.of(LYRICS);
    }

    /** The permission with this name, or null. Devices saved before the names changed keep what they had. */
    public static RemotePermission parse(String name) {
        if ("PRESENTATIONS".equals(name)) {
            return PRESENTATION;
        }
        if ("PROGRAM".equals(name)) {
            return TAKE;
        }
        for (RemotePermission p : values()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public String label() {
        return label;
    }

    public Group group() {
        return group;
    }

    @Override
    public String toString() {
        return label;
    }
}
