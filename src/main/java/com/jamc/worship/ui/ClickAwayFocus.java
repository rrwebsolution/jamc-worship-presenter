package com.jamc.worship.ui;

import javax.swing.JComboBox;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseEvent;

/**
 * A click anywhere in the main window that is not a text box takes the cursor out of the text box, so
 * the live shortcuts (Space = TAKE, arrows, B, W, L, H) work again at once. Most of the app's buttons
 * never take the focus, so without this the cursor stayed in the last search box and Space typed a
 * space instead of taking.
 *
 * <p>It acts after the click was handled (on release, then later still), so a button that uses the
 * text box's cursor position has already done its work.
 */
public final class ClickAwayFocus {

    private ClickAwayFocus() {
    }

    public static void install(Window window, JRootPane root) {
        root.setFocusable(true); // the neutral place the focus goes to
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event.getID() != MouseEvent.MOUSE_RELEASED || !(event instanceof MouseEvent e)
                    || !(e.getSource() instanceof Component target) || SwingUtilities.getWindowAncestor(target) != window
                    && target != window) {
                return;
            }
            if (isInput(target)) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focus != null && isInput(focus) && SwingUtilities.getWindowAncestor(focus) == window) {
                    root.requestFocusInWindow();
                }
            });
        }, AWTEvent.MOUSE_EVENT_MASK);
    }

    /** A text box, a list box or a number field, including their own parts (arrow button, scroll bar). */
    static boolean isInput(Component c) {
        if (c instanceof JTextComponent || SwingUtilities.getAncestorOfClass(JComboBox.class, c) != null
                || c instanceof JComboBox || SwingUtilities.getAncestorOfClass(JSpinner.class, c) != null) {
            return true;
        }
        // the scroll bar of a text area
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, c);
        return scroll != null && scroll.getViewport().getView() instanceof JTextComponent
                && !SwingUtilities.isDescendingFrom(c, scroll.getViewport());
    }
}
