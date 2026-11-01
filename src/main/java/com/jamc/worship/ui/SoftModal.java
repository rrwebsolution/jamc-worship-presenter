package com.jamc.worship.ui;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shows a dialog like a modal one, except that MINIMIZE (see {@link DialogWindowButtons}) lets the operator use
 * the rest of the app while the dialog stays open, e.g. a YouTube video keeps playing in its minimized dialog
 * while the next song goes live. RESTORE blocks the app again.
 *
 * <p>{@link #showAndWait} returns only when the dialog closes, like {@code setVisible(true)} on a modal dialog, so the
 * code that opened it reads its result as before. The other windows are blocked by a glass pane that swallows
 * the mouse (a click brings the dialog to the front) and by sending their focus back to the dialog, instead of by
 * Java's modality, which cannot be lifted while the dialog is on screen.
 */
public final class SoftModal {

    static final String KEY = "jamc.softModal";

    private SoftModal() {
    }

    /** Shows the dialog and waits until it is closed (or hidden); the event thread keeps running meanwhile. */
    public static void showAndWait(JDialog dialog) {
        if (!SwingUtilities.isEventDispatchThread()) {
            dialog.setVisible(true); // only the event thread can wait like this
            return;
        }
        dialog.setModalityType(Dialog.ModalityType.MODELESS);
        Session session = new Session(dialog);
        dialog.getRootPane().putClientProperty(KEY, session);
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        WindowAdapter closed = new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                loop.exit();
            }
        };
        ComponentAdapter hidden = new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                loop.exit();
            }
        };
        dialog.addWindowListener(closed);
        dialog.addComponentListener(hidden);
        session.setBlocking(true);
        try {
            dialog.setVisible(true);
            if (dialog.isDisplayable() && dialog.isVisible()) {
                loop.enter();
            }
        } finally {
            session.setBlocking(false);
            dialog.removeWindowListener(closed);
            dialog.removeComponentListener(hidden);
            dialog.getRootPane().putClientProperty(KEY, null);
        }
    }

    /** MINIMIZE / RESTORE: false lets the operator use the other windows while the dialog stays open. */
    static void setBlocking(JDialog dialog, boolean blocking) {
        if (dialog.getRootPane().getClientProperty(KEY) instanceof Session s) {
            s.setBlocking(blocking);
        }
    }

    /** One dialog shown by {@link #showAndWait}, and the windows it blocks. */
    private static final class Session {
        private final JDialog dialog;
        /** Blocked windows and the glass pane each had before. */
        private final Map<RootPaneContainer, Saved> blocked = new LinkedHashMap<>();
        private boolean blocking;

        private record Saved(Component glass, boolean glassVisible, WindowFocusListener focus) {
        }

        Session(JDialog dialog) {
            this.dialog = dialog;
        }

        void setBlocking(boolean on) {
            if (on == blocking) {
                return;
            }
            blocking = on;
            if (on) {
                for (Window w : Window.getWindows()) {
                    if (w != dialog && w.isShowing() && w.isFocusableWindow() && !ownedByDialog(w)
                            && w instanceof RootPaneContainer rpc && !blocked.containsKey(rpc)) {
                        block(w, rpc);
                    }
                }
            } else {
                // restore in reverse order, so a dialog opened from another dialog hands back the blocks it found
                var entries = new java.util.ArrayList<>(blocked.entrySet());
                java.util.Collections.reverse(entries);
                for (var e : entries) {
                    RootPaneContainer rpc = e.getKey();
                    Saved s = e.getValue();
                    rpc.setGlassPane(s.glass);
                    s.glass.setVisible(s.glassVisible);
                    ((Window) rpc).removeWindowFocusListener(s.focus);
                }
                blocked.clear();
            }
        }

        private void block(Window w, RootPaneContainer rpc) {
            Component old = rpc.getGlassPane();
            boolean oldVisible = old.isVisible();
            JComponent glass = new JComponent() {
            };
            MouseAdapter swallow = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    bringBack();
                }
            };
            glass.addMouseListener(swallow);
            glass.addMouseMotionListener(swallow);
            glass.addMouseWheelListener(swallow);
            glass.addKeyListener(new KeyAdapter() {
            });
            glass.setOpaque(false);
            glass.setToolTipText("Minimize or close the \"" + dialog.getTitle() + "\" window to use this");
            rpc.setGlassPane(glass);
            glass.setVisible(true);
            WindowFocusListener focus = new WindowFocusListener() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                    SwingUtilities.invokeLater(() -> {
                        if (blocking && dialog.isShowing()) {
                            bringBack(); // typing goes to the dialog, not to the blocked window
                        }
                    });
                }

                @Override
                public void windowLostFocus(WindowEvent e) {
                }
            };
            w.addWindowFocusListener(focus);
            blocked.put(rpc, new Saved(old, oldVisible, focus));
        }

        private void bringBack() {
            dialog.toFront();
            dialog.requestFocus();
        }

        private boolean ownedByDialog(Window w) {
            for (Window o = w.getOwner(); o != null; o = o.getOwner()) {
                if (o == dialog) {
                    return true;
                }
            }
            return false;
        }
    }
}
