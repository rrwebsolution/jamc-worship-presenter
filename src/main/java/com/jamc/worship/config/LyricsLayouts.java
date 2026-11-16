package com.jamc.worship.config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The operator's own lyrics layouts (Settings → Lyrics Appearance → My layouts): a name and a {@link LyricsBox}. A
 * scene's Lyrics layer uses one by storing {@code CUSTOM:<id>} as its lyrics layout, so renaming or moving the box
 * changes every scene that uses it.
 */
public final class LyricsLayouts {

    public static final String PREFIX = "CUSTOM:";
    static final String KEY = "lyrics.layouts";

    /** One saved layout. */
    public record Layout(String id, String name, LyricsBox box) {
        public Layout {
            name = name == null || name.isBlank() ? "My layout" : name.strip();
            box = box == null ? LyricsBox.starter() : box;
        }

        /** The value a scene's Lyrics layer stores. */
        public String key() {
            return PREFIX + id;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static volatile List<Layout> layouts = List.of();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private LyricsLayouts() {
    }

    /** Loads the saved layouts at startup. */
    public static void install(AppConfig cfg) {
        layouts = List.copyOf(read(cfg));
        LISTENERS.forEach(Runnable::run);
    }

    public static List<Layout> all() {
        return layouts;
    }

    /** The layout a scene asks for ({@code CUSTOM:<id>}), or null when it was deleted or is not a custom one. */
    public static Layout find(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        String id = key.substring(PREFIX.length());
        return layouts.stream().filter(l -> l.id().equals(id)).findFirst().orElse(null);
    }

    /** Adds or replaces (same id) a layout and saves; the outputs use it at once. */
    public static void save(AppConfig cfg, Layout layout) {
        List<Layout> next = new ArrayList<>(layouts);
        int at = indexOf(next, layout.id());
        if (at >= 0) {
            next.set(at, layout);
        } else {
            next.add(layout);
        }
        store(cfg, next);
    }

    public static void delete(AppConfig cfg, String id) {
        List<Layout> next = new ArrayList<>(layouts);
        next.removeIf(l -> l.id().equals(id));
        store(cfg, next);
    }

    public static Layout create(String name, LyricsBox box) {
        return new Layout(UUID.randomUUID().toString().substring(0, 8), name, box);
    }

    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static int indexOf(List<Layout> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static void store(AppConfig cfg, List<Layout> next) {
        List<String> ids = new ArrayList<>();
        for (Layout l : next) {
            ids.add(l.id());
            cfg.set(KEY + "." + l.id() + ".name", l.name());
            cfg.set(KEY + "." + l.id() + ".box", l.box().format());
        }
        for (Layout old : layouts) {
            if (!ids.contains(old.id())) {
                cfg.set(KEY + "." + old.id() + ".name", null);
                cfg.set(KEY + "." + old.id() + ".box", null);
            }
        }
        cfg.set(KEY, ids.isEmpty() ? null : String.join(",", ids));
        cfg.save();
        layouts = List.copyOf(next);
        LISTENERS.forEach(Runnable::run);
    }

    static List<Layout> read(AppConfig cfg) {
        List<Layout> out = new ArrayList<>();
        String ids = cfg.get(KEY, null);
        if (ids == null || ids.isBlank()) {
            return out;
        }
        for (String id : ids.split(",")) {
            String k = id.strip();
            LyricsBox box = LyricsBox.parse(cfg.get(KEY + "." + k + ".box", null));
            if (!k.isEmpty() && box != null) {
                out.add(new Layout(k, cfg.get(KEY + "." + k + ".name", null), box));
            }
        }
        return out;
    }
}
