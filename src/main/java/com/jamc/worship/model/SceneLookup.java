package com.jamc.worship.model;

import java.util.function.Function;

/**
 * The saved scenes by id, for a SCENE source (a scene shown inside another one, like OBS's Scene source). The main
 * window installs it; until then nothing is found.
 */
public final class SceneLookup {

    /** A scene inside a scene inside a scene… stops here (and a scene never shows itself). */
    public static final int MAX_DEPTH = 4;

    private static volatile Function<Long, Scene> lookup = id -> null;

    private SceneLookup() {
    }

    public static void install(Function<Long, Scene> f) {
        lookup = f == null ? id -> null : f;
    }

    /** The saved scene with this id, or null. */
    public static Scene find(Long id) {
        return id == null ? null : lookup.apply(id);
    }
}
