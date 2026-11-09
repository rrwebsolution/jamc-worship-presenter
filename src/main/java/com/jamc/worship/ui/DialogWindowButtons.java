package com.jamc.worship.ui;

import com.formdev.flatlaf.ui.FlatTitlePane;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;

/**
 * Minimize and Maximize buttons on every dialog's title bar, next to Close (FlatLaf gives dialogs only Close).
 *
 * <p>MAXIMIZE fills the screen the dialog is on (above the taskbar); RESTORE puts it back. MINIMIZE shrinks the
 * dialog to the Windows taskbar with its own button (see {@link TaskbarMinimize}); LiveCat and the projector keep
 * running and can be used, and a click on that button brings the dialog back. Where that is not possible it shrinks
 * to its title bar at the bottom-left of the screen instead, and the same button restores it. Small message boxes (OK / Cancel questions) are left as they are.
 */
public final class DialogWindowButtons {

    private static final String INSTALLED = "jamc.dialogButtons";
    /** Width of a minimized dialog's title strip. */
    private static final int STRIP_W = 380;

    private DialogWindowButtons() {
    }

    /** Adds the buttons to every dialog that opens from now on. Call once at startup. */
    public static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e.getID() == WindowEvent.WINDOW_OPENED && e.getSource() instanceof JDialog d) {
                SwingUtilities.invokeLater(() -> decorate(d));
            }
        }, AWTEvent.WINDOW_EVENT_MASK);
    }

    /** Adds Minimize and Maximize to this dialog's title bar (once); false when it has no FlatLaf title bar. */
    static boolean decorate(JDialog d) {
        if (d.getRootPane().getClientProperty(INSTALLED) != null || isMessageBox(d)) {
            return false;
        }
        FlatTitlePane title = find(d.getRootPane(), FlatTitlePane.class);
        JButton close = title == null ? null : find(title, JButton.class);
        if (close == null) {
            return false;
        }
        d.getRootPane().putClientProperty(INSTALLED, Boolean.TRUE);
        d.setResizable(true);
        State state = new State(d, title);
        Container row = close.getParent();
        int at = indexOf(row, close);
        state.minimize = button("TitlePane.iconifyIcon", "Minimize", close.getPreferredSize());
        state.maximize = button("TitlePane.maximizeIcon", "Maximize", close.getPreferredSize());
        state.minimize.addActionListener(e -> state.toggleMinimized());
        state.maximize.addActionListener(e -> state.toggleMaximized());
        row.add(state.minimize, at);
        row.add(state.maximize, at + 1);
        row.revalidate();
        row.repaint();
        // moved or resized by hand: no longer maximized / minimized
        d.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                state.boundsChanged();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                state.boundsChanged();
            }
        });
        return true;
    }

    /** OK / Cancel / Yes / No message boxes stay small and simple. */
    private static boolean isMessageBox(JDialog d) {
        return find(d.getContentPane(), JOptionPane.class) != null;
    }

    private static JButton button(String iconKey, String name, Dimension size) {
        JButton b = new JButton(UIManager.getIcon(iconKey));
        b.setFocusable(false);
        b.setContentAreaFilled(false);
        b.setBorder(BorderFactory.createEmptyBorder());
        b.setToolTipText(name);
        b.getAccessibleContext().setAccessibleName(name);
        b.setPreferredSize(size);
        b.setMinimumSize(size);
        b.setMaximumSize(size);
        return b;
    }

    /** The screen area without the taskbar, for the screen the dialog is on. */
    static Rectangle workArea(Component c) {
        GraphicsConfiguration gc = c.getGraphicsConfiguration();
        if (gc == null) {
            Dimension s = Toolkit.getDefaultToolkit().getScreenSize();
            return new Rectangle(0, 0, s.width, s.height);
        }
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
    }

    private static final class State {
        final JDialog dialog;
        final FlatTitlePane title;
        JButton minimize;
        JButton maximize;
        /** Size and place before maximizing / minimizing (null = normal now). */
        Rectangle normal;
        Dimension minimumSize;
        boolean maximized;
        boolean minimized;
        /** The bounds this class set last, so its own changes are not taken for the operator's. */
        Rectangle applied;

        State(JDialog dialog, FlatTitlePane title) {
            this.dialog = dialog;
            this.title = title;
        }

        void toggleMaximized() {
            if (maximized) {
                restore();
                return;
            }
            remember();
            minimized = false;
            maximized = true;
            apply(workArea(dialog));
        }

        /** Minimized to the Windows taskbar (its own button there); watched until the operator brings it back. */
        boolean onTaskbar;
        private final javax.swing.Timer taskbarWatch = new javax.swing.Timer(250, e -> watchTaskbar());

        void toggleMinimized() {
            if (minimized) {
                restore();
                return;
            }
            // Windows: to the taskbar with its own button; LiveCat and the projector keep running
            if (!onTaskbar && TaskbarMinimize.supported() && TaskbarMinimize.minimize(dialog)) {
                onTaskbar = true;
                SoftModal.setBlocking(dialog, false); // minimized: the rest of the app can be used meanwhile
                taskbarWatch.start();
                return;
            }
            remember();
            maximized = false;
            minimized = true;
            Rectangle area = workArea(dialog);
            Insets in = dialog.getInsets();
            int h = title.getHeight() + in.top + in.bottom;
            dialog.setMinimumSize(new Dimension(1, 1)); // a dialog's minimum size would stop it shrinking
            apply(new Rectangle(area.x + 8, area.y + area.height - h - 8, Math.min(STRIP_W, area.width - 16), h));
            SoftModal.setBlocking(dialog, false); // minimized: the rest of the app can be used meanwhile
        }

        /** The operator clicked the dialog's taskbar button (Windows restored it): attach it to LiveCat and block again. */
        private void watchTaskbar() {
            if (!dialog.isDisplayable()) {
                taskbarWatch.stop(); // closed while minimized (e.g. Close on its taskbar button)
                onTaskbar = false;
                return;
            }
            if (onTaskbar && !TaskbarMinimize.isMinimized(dialog)) {
                taskbarWatch.stop();
                onTaskbar = false;
                TaskbarMinimize.reattach(dialog, true);
                SoftModal.setBlocking(dialog, true); // full size again: it has the operator's attention
                dialog.toFront();
                dialog.requestFocus();
            }
        }

        private void remember() {
            if (normal == null) {
                normal = dialog.getBounds();
                minimumSize = dialog.isMinimumSizeSet() ? dialog.getMinimumSize() : null;
            }
        }

        void restore() {
            Rectangle back = normal;
            boolean wasMinimized = minimized;
            maximized = false;
            minimized = false;
            normal = null;
            dialog.setMinimumSize(minimumSize);
            if (back != null) {
                apply(back);
            }
            update();
            if (wasMinimized) {
                SoftModal.setBlocking(dialog, true); // full size again: it has the operator's attention
                dialog.toFront();
            }
        }

        private void apply(Rectangle r) {
            applied = new Rectangle(r);
            dialog.setBounds(r);
            dialog.validate();
            update();
        }

        void boundsChanged() {
            if ((maximized || minimized) && applied != null && !near(applied, dialog.getBounds())) {
                Rectangle now = dialog.getBounds();
                if (minimized && Math.abs(now.width - applied.width) <= 12 && Math.abs(now.height - applied.height) <= 16) {
                    applied = now; // the minimized strip was only dragged elsewhere: still minimized
                    return;
                }
                // resized by hand: that is the normal size now
                boolean wasMinimized = minimized;
                maximized = false;
                minimized = false;
                normal = null;
                if (minimumSize != null) {
                    dialog.setMinimumSize(minimumSize);
                }
                update();
                if (wasMinimized) {
                    SoftModal.setBlocking(dialog, true);
                }
            }
        }

        /** Icons and tooltips follow the state: RESTORE replaces the button that was used. */
        private void update() {
            maximize.setIcon(UIManager.getIcon(maximized ? "TitlePane.restoreIcon" : "TitlePane.maximizeIcon"));
            maximize.setToolTipText(maximized ? "Restore" : "Maximize");
            minimize.setIcon(UIManager.getIcon(minimized ? "TitlePane.restoreIcon" : "TitlePane.iconifyIcon"));
            minimize.setToolTipText(minimized ? "Restore" : "Minimize");
        }
    }

    /** Windows may nudge a window a few pixels (e.g. its smallest height); that is not a move by the operator. */
    static boolean near(Rectangle a, Rectangle b) {
        return Math.abs(a.x - b.x) <= 4 && Math.abs(a.y - b.y) <= 12 && Math.abs(a.width - b.width) <= 12
                && Math.abs(a.height - b.height) <= 16;
    }

    private static int indexOf(Container parent, Component child) {
        Component[] all = parent.getComponents();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == child) {
                return i;
            }
        }
        return all.length;
    }

    private static <T extends Component> T find(Container c, Class<T> type) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) {
                return type.cast(k);
            }
            if (k instanceof Container cc) {
                T r = find(cc, type);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
