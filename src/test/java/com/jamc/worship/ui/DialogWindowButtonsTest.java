package com.jamc.worship.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Dialogs get Minimize and Maximize next to Close; the dialogs are made displayable but never shown. */
class DialogWindowButtonsTest {

    private static List<JButton> buttons(Container c, List<JButton> out) {
        for (Component k : c.getComponents()) {
            if (k instanceof JButton b && b.getToolTipText() != null) {
                out.add(b);
            }
            if (k instanceof Container cc) {
                buttons(cc, out);
            }
        }
        return out;
    }

    private static JButton named(JDialog d, String tip) {
        return buttons(d.getRootPane(), new ArrayList<>()).stream().filter(b -> tip.equals(b.getToolTipText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + tip + " button"));
    }

    @Test
    void maximizeMinimizeAndRestore() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel before = UIManager.getLookAndFeel();
            FlatDarkLaf.setup();
            JDialog d = new JDialog((Frame) null, "Edit Song");
            JDialog box = new JOptionPane("OK?").createDialog("Question");
            try {
                d.setLocation(-3000, -3000);
                d.pack(); // displayable, so FlatLaf adds its title bar; never shown on screen
                d.setBounds(-3000, -3000, 500, 300);
                assertTrue(DialogWindowButtons.decorate(d));
                assertFalse(DialogWindowButtons.decorate(d), "added once");

                Rectangle normal = d.getBounds();
                Rectangle area = DialogWindowButtons.workArea(d);
                named(d, "Maximize").doClick();
                assertEquals(area, d.getBounds(), "maximized: the whole screen above the taskbar");
                named(d, "Restore").doClick();
                assertEquals(normal, d.getBounds(), "restored to its size and place");

                named(d, "Minimize").doClick();
                Rectangle strip = d.getBounds();
                assertTrue(strip.height < 80 && strip.width <= 380, "minimized to its title bar: " + strip);
                assertTrue(strip.y + strip.height <= area.y + area.height, "at the bottom of the screen, above the taskbar");
                named(d, "Restore").doClick();
                assertEquals(normal, d.getBounds());

                box.pack();
                assertFalse(DialogWindowButtons.decorate(box), "OK / Cancel message boxes stay simple");
            } finally {
                d.dispose();
                box.dispose();
                try {
                    UIManager.setLookAndFeel(before);
                } catch (Exception ignored) {
                    // the next test sets its own look
                }
            }
        });
    }
}
