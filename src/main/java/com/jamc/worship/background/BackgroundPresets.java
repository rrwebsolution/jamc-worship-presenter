package com.jamc.worship.background;

import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in solid color and gradient backgrounds listed in the Backgrounds library after the
 * imported files. They are not stored in the database (negative ids, no file); their path is the
 * {@code color:} / {@code gradient:} value the global background understands.
 */
public final class BackgroundPresets {

    private static final String[][] PRESETS = {
            {"Black", "color:#000000"},
            {"Deep Navy", "color:#0B1220"},
            {"Royal Blue", "color:#1E3A8A"},
            {"Deep Purple", "color:#3B0764"},
            {"Charcoal", "color:#1F2937"},
            {"Blue Night", "gradient:#1E3A8A,#0B1220,90"},
            {"Purple Glow", "gradient:#6D28D9,#111827,120"},
            {"Gold Dawn", "gradient:#B45309,#1C1917,90"},
            {"Teal Calm", "gradient:#0F766E,#082F49,60"},
            {"Crimson", "gradient:#991B1B,#0C0A09,90"},
    };

    private BackgroundPresets() {
    }

    public static List<MediaItem> all() {
        List<MediaItem> out = new ArrayList<>();
        for (int i = 0; i < PRESETS.length; i++) {
            MediaItem m = new MediaItem();
            m.setId(-1L - i);
            m.setName(PRESETS[i][0]);
            m.setPath(PRESETS[i][1]);
            m.setKind(MediaKind.IMAGE);
            m.setCategory(MediaCategory.BACKGROUND);
            m.setCopied(true);
            out.add(m);
        }
        return out;
    }

    /** True for a built-in color or gradient (nothing to rename, delete, favorite or schedule). */
    public static boolean isPreset(MediaItem m) {
        return m != null && GlobalBackgroundManager.isSolid(m.getPath());
    }

    /** The preset's name ("Blue Night") for a stored color / gradient value, or null if it is not a preset. */
    public static String nameOf(String path) {
        for (String[] p : PRESETS) {
            if (p[1].equalsIgnoreCase(path)) {
                return p[0];
            }
        }
        return null;
    }

    /** "Solid color" or "Gradient". */
    public static String describe(MediaItem m) {
        return m.getPath().startsWith(GlobalBackgroundManager.GRADIENT_PREFIX) ? "Gradient" : "Solid color";
    }
}
