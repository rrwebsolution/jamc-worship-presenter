package com.jamc.worship.ui;

import com.jamc.worship.config.AppConfig;

import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keyboard shortcuts. Defaults follow common presentation software; each one can be changed in
 * Settings (stored as {@code shortcut.<ACTION>=<key>} in settings.properties, e.g. {@code ctrl F}).
 */
public final class Shortcuts {

    public enum Action {
        NEXT_SLIDE("Next slide", KeyEvent.VK_RIGHT, 0),
        /**
         * While another program (WPS, PowerPoint…) is in front: a Windows hot key, → by default as the user chose. That
         * program then does not get the key; while LiveCat is in front the key is LiveCat's own as usual.
         */
        NEXT_SLIDE_ANYWHERE("Next slide, from any app", KeyEvent.VK_RIGHT, 0),
        PREVIOUS_SLIDE("Previous slide", KeyEvent.VK_LEFT, 0),
        PREVIOUS_SLIDE_ANYWHERE("Previous slide, from any app", KeyEvent.VK_LEFT, 0),
        SLIDESHOW_NEXT("Next slideshow image (Preview)", KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK),
        SLIDESHOW_PREVIOUS("Previous slideshow image (Preview)", KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK),
        TAKE("TAKE (Preview → Program)", KeyEvent.VK_SPACE, 0),
        BLACK("Black screen", KeyEvent.VK_B, 0),
        BLANK("Blank (hide text until next TAKE)", KeyEvent.VK_W, 0),
        LYRICS("Lyrics on/off", KeyEvent.VK_L, 0),
        HIDE_LOWER_THIRD("Hide lower third", KeyEvent.VK_H, 0),
        NEXT_ITEM("Next service item", KeyEvent.VK_PAGE_DOWN, 0),
        PREVIOUS_ITEM("Previous service item", KeyEvent.VK_PAGE_UP, 0),
        TOGGLE_OUTPUT("Open / close projector", KeyEvent.VK_F5, 0),
        CLOSE_OUTPUT("Close fullscreen projector", KeyEvent.VK_ESCAPE, 0),
        SEARCH("Search songs", KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK),
        SAVE("Save", KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK),
        NEW_SONG("New song", KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK);

        private final String label;
        private final KeyStroke defaultKey;

        Action(String label, int key, int modifiers) {
            this.label = label;
            this.defaultKey = KeyStroke.getKeyStroke(key, modifiers);
        }

        public String label() {
            return label;
        }

        public KeyStroke defaultKey() {
            return defaultKey;
        }

        /** Live actions fire from anywhere except text fields; the rest are menu accelerators. */
        public boolean live() {
            return this != SEARCH && this != SAVE && this != NEW_SONG;
        }
    }

    /** Whether {@link Action#NEXT_SLIDE_ANYWHERE} works outside the app (Settings → Keyboard Shortcuts). */
    public static final String KEY_ANYWHERE_ENABLED = "shortcut.anywhere.enabled";

    private final AppConfig config;
    private final Map<Action, KeyStroke> keys = new EnumMap<>(Action.class);
    private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Told when a key changes (the system hot key follows). */
    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public boolean anywhereEnabled() {
        return config.getBoolean(KEY_ANYWHERE_ENABLED, true);
    }

    public void setAnywhereEnabled(boolean on) {
        config.set(KEY_ANYWHERE_ENABLED, String.valueOf(on));
        config.save();
        listeners.forEach(Runnable::run);
    }

    /**
     * The programs (exe names) in which the "from any app" keys work. In every other program (a browser, a chat) the
     * key is never taken, so it types as usual: a plain key such as "-" or → is not lost there.
     */
    public static final String KEY_ANYWHERE_APPS = "shortcut.anywhere.apps";
    /** WPS Office (one window for everything, and the older presentation exe) and PowerPoint. */
    public static final String DEFAULT_ANYWHERE_APPS = "wps.exe, wpp.exe, POWERPNT.EXE";

    /** The programs as typed in Settings, e.g. "wps.exe, POWERPNT.EXE". */
    public String anywhereAppsText() {
        String v = config.get(KEY_ANYWHERE_APPS, DEFAULT_ANYWHERE_APPS);
        return v == null ? DEFAULT_ANYWHERE_APPS : v;
    }

    public void setAnywhereAppsText(String text) {
        String clean = String.join(", ", parseApps(text));
        config.set(KEY_ANYWHERE_APPS, clean.equalsIgnoreCase(DEFAULT_ANYWHERE_APPS) ? null : clean);
        config.save();
        listeners.forEach(Runnable::run);
    }

    /** True when the keys may be taken while this program is in front (exe name, any case; null = unknown → no). */
    public boolean anywhereApp(String exe) {
        if (exe == null) {
            return false;
        }
        return parseApps(anywhereAppsText()).stream().anyMatch(a -> a.equalsIgnoreCase(exe));
    }

    static java.util.List<String> parseApps(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : (text == null ? "" : text).split("[,;\\n]")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int slash = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
            p = p.substring(slash + 1); // a full path pasted: its file name
            if (!p.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                p = p + ".exe";
            }
            if (out.stream().noneMatch(p::equalsIgnoreCase)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Stored for an action whose key was given to another action (it then has no key). */
    private static final String NONE = "none";

    public Shortcuts(AppConfig config) {
        this.config = config;
        for (Action a : Action.values()) {
            String saved = config.get("shortcut." + a.name(), "");
            KeyStroke custom = KeyStroke.getKeyStroke(saved);
            keys.put(a, NONE.equals(saved) ? null : custom != null ? custom : a.defaultKey());
        }
    }

    /** The action's key, or null when it has none. */
    public KeyStroke get(Action a) {
        return keys.get(a);
    }

    public void set(Action a, KeyStroke key) {
        keys.put(a, key);
        config.set("shortcut." + a.name(), key == null ? NONE : key.equals(a.defaultKey()) ? null : toConfig(key));
        config.save();
        listeners.forEach(Runnable::run);
    }

    /** Another action that already uses this key, if any (keys must not be shared). */
    public Optional<Action> conflict(Action a, KeyStroke key) {
        return keys.entrySet().stream()
                .filter(en -> en.getKey() != a && key.equals(en.getValue()))
                // "next slide" in LiveCat and from another app (or "previous"): sharing a key is the point
                .filter(en -> !(nextSlide(a) && nextSlide(en.getKey())) && !(previousSlide(a) && previousSlide(en.getKey())))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static boolean nextSlide(Action a) {
        return a == Action.NEXT_SLIDE || a == Action.NEXT_SLIDE_ANYWHERE;
    }

    private static boolean previousSlide(Action a) {
        return a == Action.PREVIOUS_SLIDE || a == Action.PREVIOUS_SLIDE_ANYWHERE;
    }

    public void resetAll() {
        for (Action a : Action.values()) {
            keys.put(a, a.defaultKey());
            config.set("shortcut." + a.name(), null);
        }
        config.save();
        listeners.forEach(Runnable::run);
    }

    /** The live action bound to this key press, if any. */
    public Optional<Action> match(KeyEvent e) {
        KeyStroke pressed = KeyStroke.getKeyStroke(e.getKeyCode(), e.getModifiersEx());
        for (Map.Entry<Action, KeyStroke> en : keys.entrySet()) {
            KeyStroke k = en.getValue();
            if (k != null && en.getKey().live() && k.getKeyCode() == pressed.getKeyCode() && k.getModifiers() == pressed.getModifiers()) {
                return Optional.of(en.getKey());
            }
        }
        return Optional.empty();
    }

    /** Human-readable key, e.g. "Ctrl+F" or "Space". */
    public static String describe(KeyStroke k) {
        if (k == null) {
            return "—";
        }
        String mods = KeyEvent.getModifiersExText(k.getModifiers());
        String key = KeyEvent.getKeyText(k.getKeyCode());
        return mods.isEmpty() ? key : mods + "+" + key;
    }

    private static String toConfig(KeyStroke k) {
        // KeyStroke.toString() is "pressed X" / "ctrl pressed X"; getKeyStroke(String) accepts it back.
        return k.toString();
    }
}
