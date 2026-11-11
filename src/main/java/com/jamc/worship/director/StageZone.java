package com.jamc.worship.director;

/** A part of the stage where a performer of a role usually stands or sits (normalized to the camera picture). */
public record StageZone(Role role, Box box) {

    /** Where a fresh install starts: a common layout for a worship team seen from the back of the hall. */
    public static java.util.List<StageZone> defaults() {
        return java.util.List.of(
                new StageZone(Role.KEYBOARD, new Box(0.02, 0.25, 0.28, 0.70)),
                new StageZone(Role.LEAD_SINGER, new Box(0.36, 0.15, 0.28, 0.80)),
                new StageZone(Role.BACKUP_SINGER, new Box(0.20, 0.15, 0.16, 0.80)),
                new StageZone(Role.DRUMS, new Box(0.70, 0.20, 0.28, 0.70)),
                new StageZone(Role.OTHER, new Box(0.62, 0.15, 0.10, 0.80)));
    }
}
