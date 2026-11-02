package com.jamc.worship.remote;

import java.util.EnumSet;
import java.util.Set;

import static com.jamc.worship.remote.RemotePermission.BIBLE;
import static com.jamc.worship.remote.RemotePermission.BLACK_PROJECTOR;
import static com.jamc.worship.remote.RemotePermission.CAMERAS;
import static com.jamc.worship.remote.RemotePermission.LYRICS;
import static com.jamc.worship.remote.RemotePermission.MEDIA;
import static com.jamc.worship.remote.RemotePermission.PRESENTATION;
import static com.jamc.worship.remote.RemotePermission.PREVIEW;
import static com.jamc.worship.remote.RemotePermission.SCENES;
import static com.jamc.worship.remote.RemotePermission.SERVICE;
import static com.jamc.worship.remote.RemotePermission.TAKE;

/**
 * Everything a remote can ask for, with what the device must be allowed. Anything not listed here cannot be done
 * remotely: there is no command for settings, keys or the Scene Editor.
 */
public enum RemoteCommand {
    TAKE_PREVIEW("TAKE", EnumSet.of(TAKE)),
    SET_SCENE_PREVIEW(EnumSet.of(SCENES, PREVIEW)),
    SET_CAMERA_PREVIEW(EnumSet.of(CAMERAS, PREVIEW)),
    PRESENTATION_FIRST(EnumSet.of(PRESENTATION)),
    PRESENTATION_PREVIOUS(EnumSet.of(PRESENTATION)),
    PRESENTATION_NEXT(EnumSet.of(PRESENTATION)),
    PRESENTATION_LAST(EnumSet.of(PRESENTATION)),
    PRESENTATION_SELECT(EnumSet.of(PRESENTATION)),
    PRESENTATION_CLEAR(EnumSet.of(PRESENTATION)),
    LYRICS_PREVIOUS(EnumSet.of(LYRICS)),
    LYRICS_NEXT(EnumSet.of(LYRICS)),
    LYRICS_SELECT(EnumSet.of(LYRICS)),
    LYRICS_CLEAR(EnumSet.of(LYRICS)),
    BIBLE_CLEAR(EnumSet.of(BIBLE)),
    BIBLE_PREVIOUS(EnumSet.of(BIBLE)),
    BIBLE_NEXT(EnumSet.of(BIBLE)),
    BIBLE_SELECT(EnumSet.of(BIBLE)),
    SET_SERVICE_PREVIEW(EnumSet.of(SERVICE, PREVIEW)),
    OPEN_SONG(EnumSet.of(LYRICS)),
    OPEN_BIBLE(EnumSet.of(BIBLE)),
    OPEN_PRESENTATION(EnumSet.of(PRESENTATION)),
    OPEN_MEDIA(EnumSet.of(MEDIA)),
    OPEN_SERVICE(EnumSet.of(SERVICE)),
    BLACK_PROJECTOR_ON("BLACK_PROJECTOR", EnumSet.of(BLACK_PROJECTOR)),
    RESTORE_PROJECTOR(EnumSet.of(BLACK_PROJECTOR)),
    MEDIA_PLAY(EnumSet.of(MEDIA)),
    MEDIA_PAUSE(EnumSet.of(MEDIA)),
    MEDIA_STOP(EnumSet.of(MEDIA)),
    MEDIA_SEEK(EnumSet.of(MEDIA)),
    MEDIA_MUTE(EnumSet.of(MEDIA)),
    MEDIA_SET_VOLUME(EnumSet.of(MEDIA)),
    MEDIA_SET_LOOP(EnumSet.of(MEDIA)),
    MEDIA_CLEAR(EnumSet.of(MEDIA));

    private final String wireName;
    private final Set<RemotePermission> required;

    RemoteCommand(Set<RemotePermission> required) {
        this.wireName = name();
        this.required = required;
    }

    RemoteCommand(String wireName, Set<RemotePermission> required) {
        this.wireName = wireName;
        this.required = required;
    }

    /** A device needs all of these. */
    public Set<RemotePermission> required() {
        return required;
    }

    /** The command with this name in a message, or null. */
    public static RemoteCommand parse(String wire) {
        for (RemoteCommand c : values()) {
            if (c.wireName.equals(wire)) {
                return c;
            }
        }
        return null;
    }
}
