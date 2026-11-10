package com.jamc.worship.output;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The audience-facing output window. It contains nothing but the Program picture.
 *
 * <p>Fullscreen mode is a borderless window covering the chosen monitor. It never takes keyboard
 * focus, so the operator window keeps working while it is open. The mouse pointer is hidden,
 * auto-hidden after 3 seconds, or always shown, as chosen in Settings.
 * Windowed mode is a normal resizable window, useful for rehearsing on a single screen.
 */
public class PresentationWindow extends JFrame {

    /** What the mouse pointer does over the projector picture (Settings, Output). */
    public enum CursorMode {
        ALWAYS_HIDDEN("Always hidden"), AUTO_HIDE("Auto hide after 3 seconds"), ALWAYS_VISIBLE("Always visible");

        private final String label;

        CursorMode(String label) {
            this.label = label;
        }

        public static CursorMode parse(String v) {
            try {
                return v == null ? AUTO_HIDE : valueOf(v);
            } catch (IllegalArgumentException e) {
                return AUTO_HIDE;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final int CURSOR_HIDE_MS = 3000;

    /** Blacks out this window only (its background is black): Program, the stream and recordings carry on. */
    public void setBlackedOut(boolean black) {
        canvas.setVisible(!black);
    }

    private final boolean fullscreen;
    private final OutputCanvas canvas;
    private final java.util.function.Supplier<CursorMode> cursorMode;
    private final javax.swing.Timer hideCursor = new javax.swing.Timer(CURSOR_HIDE_MS, e -> applyCursor(false));

    public PresentationWindow(ProgramOutput program, DisplayManager.Display display, boolean fullscreen,
                              List<Image> icons, Runnable onUserClose) {
        this(program, display, fullscreen, icons, onUserClose, () -> CursorMode.ALWAYS_HIDDEN);
    }

    public PresentationWindow(ProgramOutput program, DisplayManager.Display display, boolean fullscreen,
                              List<Image> icons, Runnable onUserClose, java.util.function.Supplier<CursorMode> cursorMode) {
        this(program, false, display, fullscreen, icons, onUserClose, cursorMode);
    }

    /**
     * @param state      Program, or the Preview state when {@code preview} (a confidence screen or an in-room
     *                   display while Program goes to the livestream)
     * @param cursorMode read on every mouse move, so a Settings change applies at once
     */
    public PresentationWindow(ProgramOutput state, boolean preview, DisplayManager.Display display, boolean fullscreen,
                              List<Image> icons, Runnable onUserClose, java.util.function.Supplier<CursorMode> cursorMode) {
        super(com.jamc.worship.ui.BrandingManager.shortNameOrDefault() + (preview ? " - Output (Preview)" : " - Output"));
        ProgramOutput program = state;
        this.fullscreen = fullscreen;
        this.cursorMode = cursorMode;
        hideCursor.setRepeats(false);
        setIconImages(icons);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onUserClose.run();
            }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.BLACK);
        canvas = preview ? new OutputCanvas(program, false, true) : new OutputCanvas(program);
        // the pointer still moves freely between the screens; it only shows over the picture as configured
        canvas.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                applyCursor(true);
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                applyCursor(true);
            }
        });
        root.add(canvas, BorderLayout.CENTER);
        setContentPane(root);
        getContentPane().setBackground(Color.BLACK);
        setBackground(Color.BLACK);

        Rectangle screen = display.bounds();
        applyCursor(false);
        if (fullscreen) {
            setUndecorated(true);
            setResizable(false);
            setFocusableWindowState(false); // never steal the operator's keyboard
            setAutoRequestFocus(false);
            setAlwaysOnTop(true);
            setBounds(screen);
        } else {
            setAutoRequestFocus(false);
            setMinimumSize(new Dimension(320, 200));
            int w = Math.min(960, screen.width - 80);
            int h = (int) Math.round(w * 9 / 16.0);
            setSize(w, h);
            pack(); // account for decorations
            setSize(Math.max(getWidth(), w), Math.max(getHeight(), h));
            setLocation(screen.x + (screen.width - getWidth()) / 2, screen.y + (screen.height - getHeight()) / 2);
        }
    }

    /** Shows or hides the pointer for the configured mode; {@code moved} = the mouse just moved over the picture. */
    private void applyCursor(boolean moved) {
        CursorMode mode = cursorMode.get();
        boolean visible = mode == CursorMode.ALWAYS_VISIBLE || (mode == CursorMode.AUTO_HIDE && moved);
        getContentPane().setCursor(visible ? Cursor.getDefaultCursor() : blankCursor());
        if (mode == CursorMode.AUTO_HIDE && moved) {
            hideCursor.restart();
        } else {
            hideCursor.stop();
        }
    }

    @Override
    public void dispose() {
        hideCursor.stop();
        super.dispose();
    }

    /**
     * Fullscreen on a screen that is not 16:9: stretch the picture over all of it instead of black bars. A window
     * (rehearsal) always keeps the true shape, however it is resized.
     */
    public void setFillScreen(java.util.function.BooleanSupplier fill) {
        canvas.setFillScreen(() -> fullscreen && fill.getAsBoolean());
        canvas.repaint();
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    private static Cursor blankCursor() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        return Toolkit.getDefaultToolkit().createCustomCursor(img, new Point(0, 0), "blank");
    }
}
