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
        NEW_SONG("New song", KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK),
        // ---- AI Smart Auto Director (virtual camera shots). They only act while the Auto Director is on, never
        // while typing, and give the key back otherwise (so 1–5, + and − keep working elsewhere).
        DIRECTOR_SHOT_1("Auto Director: shot 1 to Preview", KeyEvent.VK_1, 0),
        DIRECTOR_SHOT_2("Auto Director: shot 2 to Preview", KeyEvent.VK_2, 0),
        DIRECTOR_SHOT_3("Auto Director: shot 3 to Preview", KeyEvent.VK_3, 0),
        DIRECTOR_SHOT_4("Auto Director: shot 4 to Preview", KeyEvent.VK_4, 0),
        DIRECTOR_SHOT_5("Auto Director: shot 5 to Preview", KeyEvent.VK_5, 0),
        DIRECTOR_TAKE_1("Auto Director: TAKE shot 1 directly", KeyEvent.VK_1, KeyEvent.CTRL_DOWN_MASK),
        DIRECTOR_TAKE_2("Auto Director: TAKE shot 2 directly", KeyEvent.VK_2, KeyEvent.CTRL_DOWN_MASK),
        DIRECTOR_TAKE_3("Auto Director: TAKE shot 3 directly", KeyEvent.VK_3, KeyEvent.CTRL_DOWN_MASK),
        DIRECTOR_TAKE_4("Auto Director: TAKE shot 4 directly", KeyEvent.VK_4, KeyEvent.CTRL_DOWN_MASK),
        DIRECTOR_TAKE_5("Auto Director: TAKE shot 5 directly", KeyEvent.VK_5, KeyEvent.CTRL_DOWN_MASK),
        DIRECTOR_ASSISTED("Auto Director: AI Assisted on/off", KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
        DIRECTOR_AUTO("Auto Director: Full Auto on/off", KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
        /** Shares Esc with "Close fullscreen projector": while the AI is active, Esc pauses the AI first. */
        DIRECTOR_PAUSE("Auto Director: Pause AI (manual control)", KeyEvent.VK_ESCAPE, 0),
        DIRECTOR_RESUME("Auto Director: Resume AI", KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
        DIRECTOR_ZOOM_IN("Auto Director: zoom in (Preview)", KeyEvent.VK_EQUALS, 0),
        DIRECTOR_ZOOM_OUT("Auto Director: zoom out (Preview)", KeyEvent.VK_MINUS, 0),
        // arrows alone are next / previous slide, so the digital pan uses Alt + arrows
        DIRECTOR_PAN_LEFT("Auto Director: pan left (Preview)", KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK),
        DIRECTOR_PAN_RIGHT("Auto Director: pan right (Preview)", KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK),
        DIRECTOR_PAN_UP("Auto Director: pan up (Preview)", KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK),
        DIRECTOR_PAN_DOWN("Auto Director: pan down (Preview)", KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK),
        DIRECTOR_RESET_WIDE("Auto Director: reset Preview to the wide shot", KeyEvent.VK_HOME, 0);

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

        /** An Auto Director key (camera shots, AI modes, digital pan / zoom). */
        public boolean director() {
            return name().startsWith("DIRECTOR_");
        }

        /** 0..4 for the shot keys (DIRECTOR_SHOT_n / DIRECTOR_TAKE_n), else -1. */
        public int shotIndex() {
            String n = name();
            return n.startsWith("DIRECTOR_SHOT_") || n.startsWith("DIRECTOR_TAKE_") ? n.charAt(n.length() - 1) - '1' : -1;
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
                .filter(en -> !escapePair(a, en.getKey()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Pause AI and Close projector share Esc on purpose: Pause AI acts only while the AI is active. */
    private static boolean escapePair(Action a, Action b) {
        return a == Action.DIRECTOR_PAUSE && b == Action.CLOSE_OUTPUT || a == Action.CLOSE_OUTPUT && b == Action.DIRECTOR_PAUSE;
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
        java.util.List<Action> all = matchAll(e);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Every live action on this key press, in the order they are offered: the first one that acts takes the key
     * (e.g. Esc: Pause AI while the AI is active, else Close projector). Auto Director keys come first; they give
     * the key back when the director is off. The numeric keypad's + and − count as the + and − keys.
     */
    public java.util.List<Action> matchAll(KeyEvent e) {
        int code = e.getKeyCode() == KeyEvent.VK_ADD ? KeyEvent.VK_EQUALS
                : e.getKeyCode() == KeyEvent.VK_SUBTRACT ? KeyEvent.VK_MINUS : e.getKeyCode();
        boolean keypad = code != e.getKeyCode();
        KeyStroke pressed = KeyStroke.getKeyStroke(code, e.getModifiersEx());
        java.util.List<Action> director = new java.util.ArrayList<>();
        java.util.List<Action> other = new java.util.ArrayList<>();
        for (Map.Entry<Action, KeyStroke> en : keys.entrySet()) {
            KeyStroke k = en.getValue();
            if (k == null || !en.getKey().live() || k.getKeyCode() != pressed.getKeyCode()
                    || k.getModifiers() != pressed.getModifiers() || keypad && !en.getKey().director()) {
                continue;
            }
            (en.getKey().director() ? director : other).add(en.getKey());
        }
        director.addAll(other);
        return director;
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
