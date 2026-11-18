package com.jamc.worship.model;

import java.util.List;
import java.util.Objects;

/**
 * The saved lower thirds, for a LOWER_THIRD scene source set to one of them (a scene can hold several different
 * lower thirds at once). The Lower Thirds list keeps it up to date; until then nothing is found.
 */
public final class LowerThirdLookup {

    private static volatile List<LowerThird> all = List.of();

    private LowerThirdLookup() {
    }

    /** The newest saved lower thirds (copies, so the drawing never sees one half edited). */
    public static void set(List<LowerThird> items) {
        all = items == null ? List.of() : items.stream().map(LowerThird::copy).toList();
    }

    public static List<LowerThird> all() {
        return all;
    }

    /** The saved lower third with this id, or null. */
    public static LowerThird find(Long id) {
        if (id == null) {
            return null;
        }
        for (LowerThird lt : all) {
            if (Objects.equals(lt.getId(), id)) {
                return lt;
            }
        }
        return null;
    }
}
