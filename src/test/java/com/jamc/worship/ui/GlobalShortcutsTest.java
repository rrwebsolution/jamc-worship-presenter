package com.jamc.worship.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.jamc.worship.ui.GlobalShortcuts.KeyboardContext.PRESENTATION;
import static com.jamc.worship.ui.GlobalShortcuts.KeyboardContext.SEARCH;
import static com.jamc.worship.ui.GlobalShortcuts.KeyboardContext.TEXT_EDITING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TAKE on ` works from a search box without typing the `; real text editing is never interrupted. */
class GlobalShortcutsTest {

    @TempDir
    Path dir;

    private AppConfig config;

    private Shortcuts shortcuts() {
        if (config == null) {
            AppPaths paths = new AppPaths(dir.resolve("home"));
            paths.ensureDirectories();
            config = new AppConfig(paths);
        }
        return new Shortcuts(config);
    }

    private static final KeyStroke BACKTICK = KeyStroke.getKeyStroke(KeyEvent.VK_BACK_QUOTE, 0);

    @Test
    void searchBoxesKeepOperatorKeysButNotTypingKeys() {
        JTextField search = new JTextField();
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search lyrics…");
        assertEquals(SEARCH, GlobalShortcuts.context(search));
        assertEquals(TEXT_EDITING, GlobalShortcuts.context(new JTextField()));
        assertEquals(TEXT_EDITING, GlobalShortcuts.context(new JTextArea()));
        assertEquals(PRESENTATION, GlobalShortcuts.context(new JButton()));
        assertEquals(PRESENTATION, GlobalShortcuts.context(null));

        assertTrue(GlobalShortcuts.allowed(BACKTICK, SEARCH), "` = TAKE works in a search box");
        assertFalse(GlobalShortcuts.allowed(KeyStroke.getKeyStroke(KeyEvent.VK_B, 0), SEARCH), "B is typed");
        assertFalse(GlobalShortcuts.allowed(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), SEARCH), "space is typed");
        assertFalse(GlobalShortcuts.allowed(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), SEARCH), "arrows move the caret");
        assertTrue(GlobalShortcuts.allowed(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), SEARCH));

        assertFalse(GlobalShortcuts.allowed(BACKTICK, TEXT_EDITING), "single keys never interrupt editing");
        assertFalse(GlobalShortcuts.allowed(KeyStroke.getKeyStroke(KeyEvent.VK_L, 0), TEXT_EDITING));
        assertTrue(GlobalShortcuts.allowed(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), TEXT_EDITING));
        assertTrue(GlobalShortcuts.allowed(BACKTICK, PRESENTATION));
    }

    @Test
    void aMatchedKeyRunsOnceAndItsCharacterIsSwallowed() {
        Shortcuts keys = shortcuts();
        keys.set(Shortcuts.Action.TAKE, BACKTICK);
        List<Shortcuts.Action> ran = new ArrayList<>();
        GlobalShortcuts g = new GlobalShortcuts(() -> true, keys, e -> false, a -> ran.add(a));
        JTextField source = new JTextField();

        KeyEvent press = new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_BACK_QUOTE, '`');
        assertTrue(g.dispatchKeyEvent(press));
        assertTrue(press.isConsumed());
        assertEquals(List.of(Shortcuts.Action.TAKE), ran);
        KeyEvent typed = new KeyEvent(source, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, '`');
        assertTrue(g.dispatchKeyEvent(typed), "the ` never reaches the focused field");

        KeyEvent letter = new KeyEvent(source, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'a');
        assertFalse(g.dispatchKeyEvent(letter), "ordinary typing goes on as normal");

        GlobalShortcuts inactive = new GlobalShortcuts(() -> false, keys, e -> false, a -> ran.add(a));
        assertFalse(inactive.dispatchKeyEvent(new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_BACK_QUOTE, '`')),
                "dialogs (another active window) keep their keys");
        assertEquals(1, ran.size());
    }

    @Test
    void oneKeyCannotHaveTwoActions() {
        Shortcuts keys = shortcuts();
        assertEquals(Shortcuts.Action.BLACK,
                keys.conflict(Shortcuts.Action.TAKE, KeyStroke.getKeyStroke(KeyEvent.VK_B, 0)).orElseThrow());
        assertTrue(keys.conflict(Shortcuts.Action.TAKE, BACKTICK).isEmpty());
        // REASSIGN: the other action loses the key (and stays without one after a restart)
        keys.set(Shortcuts.Action.BLACK, null);
        keys.set(Shortcuts.Action.TAKE, KeyStroke.getKeyStroke(KeyEvent.VK_B, 0));
        Shortcuts reloaded = shortcuts();
        assertEquals(null, reloaded.get(Shortcuts.Action.BLACK));
        assertEquals(KeyEvent.VK_B, reloaded.get(Shortcuts.Action.TAKE).getKeyCode());
    }
}
