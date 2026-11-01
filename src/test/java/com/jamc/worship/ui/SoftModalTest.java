package com.jamc.worship.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A dialog blocks the app like a modal one, MINIMIZE lets the app be used, and the opener's code waits until the
 * dialog closes. The windows are placed off screen and never take the focus.
 */
class SoftModalTest {

    @Test
    void minimizingLiftsTheBlockAndClosingReturnsToTheCaller() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean returned = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("main");
            frame.setType(Window.Type.UTILITY); // no taskbar button
            frame.setAutoRequestFocus(false);
            frame.setBounds(-4000, -4000, 300, 200);
            frame.setVisible(true);
            Component original = frame.getGlassPane();
            JDialog dialog = new JDialog(frame, "YouTube", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
            dialog.setAutoRequestFocus(false);
            dialog.setBounds(-4000, -3600, 300, 200);
            SwingUtilities.invokeLater(() -> {
                try {
                    // open: the main window is blocked (a glass pane swallows its mouse)
                    assertNotSame(original, frame.getGlassPane(), "blocked while the dialog is open");
                    assertTrue(frame.getGlassPane().isVisible());
                    SoftModal.setBlocking(dialog, false); // MINIMIZE
                    assertSame(original, frame.getGlassPane(), "minimized: the main window can be used");
                    SoftModal.setBlocking(dialog, true); // RESTORE
                    assertNotSame(original, frame.getGlassPane(), "restored: blocked again");
                    assertFalse(returned.get(), "the caller still waits while the dialog is open");
                } catch (Throwable t) {
                    failure.set(t.getMessage());
                } finally {
                    dialog.dispose();
                }
            });
            SoftModal.showAndWait(dialog); // returns when the dialog closes
            returned.set(true);
            if (failure.get() == null && frame.getGlassPane() != original) {
                failure.set("the main window is unblocked after the dialog closes");
            }
            frame.dispose();
        });
        assertTrue(returned.get());
        assertTrue(failure.get() == null, failure.get());
    }
}
