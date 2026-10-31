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
        PREVIOUS_SLIDE("Previous slide", KeyEvent.VK_LEFT, 0),
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

    private final AppConfig config;
    private final Map<Action, KeyStroke> keys = new EnumMap<>(Action.class);

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
    }

    /** Another action that already uses this key, if any (keys must not be shared). */
    public Optional<Action> conflict(Action a, KeyStroke key) {
        return keys.entrySet().stream()
                .filter(en -> en.getKey() != a && key.equals(en.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public void resetAll() {
        for (Action a : Action.values()) {
            keys.put(a, a.defaultKey());
            config.set("shortcut." + a.name(), null);
        }
        config.save();
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
