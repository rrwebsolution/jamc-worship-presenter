package com.jamc.worship.ui;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Predicate;

/**
 * The operator's presentation shortcuts (TAKE, next / previous, black, lyrics…), handled once for the
 * whole main window before any focused component sees the key.
 *
 * <p>What a key does depends on the {@link KeyboardContext}:
 * <ul>
 *   <li>PRESENTATION: every shortcut works.</li>
 *   <li>SEARCH (a search box has focus): shortcuts still work, except keys a search needs for typing
 *       and moving the caret (letters, digits, space, arrows, Backspace…). A TAKE key like {@code `}
 *       therefore takes and is never typed into the search.</li>
 *   <li>TEXT_EDITING (any other text field, e.g. a scene's text source): only shortcuts with Ctrl / Alt
 *       or a function key, so typing is never interrupted. Editing dialogs are separate windows, where
 *       these shortcuts are off altogether.</li>
 * </ul>
 * A matched key is consumed, including the character it would have typed.
 */
public final class GlobalShortcuts implements KeyEventDispatcher {

    public enum KeyboardContext { PRESENTATION, SEARCH, TEXT_EDITING }

    /** Runs an action; returns false when it did not apply (the key then goes on as normal). */
    public interface Handler {
        boolean run(Shortcuts.Action action);
    }

    private final java.util.function.BooleanSupplier windowActive;
    private final Shortcuts shortcuts;
    private final Handler handler;
    /** Extra keys handled before the shortcuts (e.g. Enter on the slide list); true = handled. */
    private final Predicate<KeyEvent> before;
    /** The character of a matched key press, swallowed so it never reaches the focused field. */
    private boolean swallowTyped;

    public GlobalShortcuts(java.util.function.BooleanSupplier windowActive, Shortcuts shortcuts, Predicate<KeyEvent> before,
                           Handler handler) {
        this.windowActive = windowActive;
        this.shortcuts = shortcuts;
        this.before = before;
        this.handler = handler;
    }

    public void install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_TYPED) {
            if (swallowTyped) {
                swallowTyped = false;
                e.consume();
                return true;
            }
            return false;
        }
        if (e.getID() != KeyEvent.KEY_PRESSED || !windowActive.getAsBoolean()) {
            return false;
        }
        swallowTyped = false;
        if (before.test(e)) {
            return consume(e);
        }
        java.util.List<Shortcuts.Action> actions = shortcuts.matchAll(e);
        if (actions.isEmpty()) {
            return false;
        }
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        KeyStroke key = KeyStroke.getKeyStroke(e.getKeyCode(), e.getModifiersEx());
        if (!allowed(key, context(focus))) {
            return false;
        }
        // several actions may share a key (Esc: Pause AI, else Close projector): the first one that acts takes it
        for (Shortcuts.Action action : actions) {
            if (action.director() && focus instanceof JTextComponent) {
                continue; // camera keys never fire while typing, not even with Ctrl
            }
            if (handler.run(action)) {
                return consume(e);
            }
        }
        return false;
    }

    private boolean consume(KeyEvent e) {
        e.consume();
        swallowTyped = true;
        return true;
    }

    /** Which keyboard context the focused component puts the window in. */
    public static KeyboardContext context(Component focus) {
        if (focus instanceof JTextField field && isSearch(field)) {
            return KeyboardContext.SEARCH;
        }
        JComboBox<?> combo = focus instanceof JComboBox<?> cb ? cb
                : focus == null ? null : (JComboBox<?>) SwingUtilities.getAncestorOfClass(JComboBox.class, focus);
        if (combo != null) {
            // a drop-down that was just used (e.g. the Program transition) keeps the focus: while it is closed and
            // not typed into, the shortcuts work again (next slide, TAKE…); open, or editable, its keys are its own
            return combo.isEditable() || combo.isPopupVisible() ? KeyboardContext.TEXT_EDITING : KeyboardContext.PRESENTATION;
        }
        if (focus instanceof JTextComponent) {
            return KeyboardContext.TEXT_EDITING;
        }
        return KeyboardContext.PRESENTATION;
    }

    /** A search box: the app's search fields all show a "Search…" placeholder. */
    static boolean isSearch(JTextField field) {
        Object hint = field.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT);
        return hint instanceof String s && s.startsWith("Search")
                || "search".equals(field.getClientProperty("JTextField.variant"));
    }

    /** Whether a shortcut on {@code key} fires in this context. */
    public static boolean allowed(KeyStroke key, KeyboardContext context) {
        boolean command = (key.getModifiers() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
                | InputEvent.META_DOWN_MASK)) != 0;
        int code = key.getKeyCode();
        boolean function = (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) || (code >= KeyEvent.VK_F13 && code <= KeyEvent.VK_F24);
        return switch (context) {
            case PRESENTATION -> true;
            case TEXT_EDITING -> command || function;
            case SEARCH -> command || function || !typingKey(code);
        };
    }

    /** Keys a search box needs for typing and moving the caret. */
    private static boolean typingKey(int code) {
        return Character.isLetterOrDigit(code) && code < 128
                || code == KeyEvent.VK_SPACE || code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE
                || code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT || code == KeyEvent.VK_UP
                || code == KeyEvent.VK_DOWN || code == KeyEvent.VK_HOME || code == KeyEvent.VK_END
                || code == KeyEvent.VK_ENTER || code == KeyEvent.VK_TAB || code == KeyEvent.VK_ESCAPE
                || (code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_DIVIDE);
    }
}
