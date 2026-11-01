package com.jamc.worship.output;

import com.jamc.worship.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Image;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Output router: which screens show Preview and which show Program.
 *
 * <p>Nothing is hard-wired. Any connected display (or a window) can show either Preview or Program,
 * several displays can show the same source, and Program keeps feeding OBS, recording and the
 * livestream whatever the displays show. A physical display shows one source at a time: routing the
 * other source to it asks first and then replaces it, so two fullscreen windows never fight over
 * one monitor.
 *
 * <p>The "projector" is the display chosen on the Output page; its source (Program by default) can be
 * flipped with one click. The routing is remembered. If a display disconnects its window closes but
 * the route stays, and the window comes back by itself when the display returns, so a loose cable
 * does not leave the operator hunting for buttons. Confined to the event dispatch thread.
 */
public final class OutputService {

    private static final Logger log = LoggerFactory.getLogger(OutputService.class);

    /** Saved routes: {@code target|SOURCE|MODE;...} (target = display id, or "window:SOURCE"). */
    public static final String KEY_ROUTES = "output.routes";
    /** What the projector display shows: PROGRAM (default) or PREVIEW. */
    public static final String KEY_PROJECTOR_SOURCE = "output.source";
    private static final String WINDOW_PREFIX = "window:";

    public enum Mode {
        FULLSCREEN("Fullscreen"), WINDOWED("Windowed");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What an output window shows. */
    public enum Source {
        PREVIEW("Preview"), PROGRAM("Program");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public static Source parse(String v, Source fallback) {
            try {
                return v == null ? fallback : valueOf(v);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One active route as the operator sees it ("Display 2 · Program"). */
    public record RouteInfo(String target, int displayNumber, String displayName, Source source, boolean showing) {
        public boolean windowed() {
            return displayNumber <= 0;
        }

        public String place() {
            return windowed() ? "Window" : displayName != null ? displayName : "Display " + displayNumber;
        }
    }

    /** A display (or a window) and what it shows; the window is null while its display is disconnected. */
    private static final class Route {
        final String target;
        Source source;
        Mode mode;
        PresentationWindow window;

        Route(String target, Source source, Mode mode) {
            this.target = target;
            this.source = source;
            this.mode = mode;
        }

        boolean windowTarget() {
            return target.startsWith(WINDOW_PREFIX);
        }
    }

    private final ProgramOutput program;
    private final ProgramOutput preview;
    private final DisplayManager displays;
    private final AppConfig config;
    private final List<Image> icons;
    private final Supplier<GraphicsDevice> operatorDevice;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private Consumer<String> notifier = s -> { };

    private String displayId;
    private Mode mode;

    public OutputService(ProgramOutput program, DisplayManager displays, AppConfig config, List<Image> icons,
                         Supplier<GraphicsDevice> operatorDevice) {
        this(program, null, displays, config, icons, operatorDevice);
    }

    /** @param preview the Preview state, so Preview can be routed to a display too (null = Program only) */
    public OutputService(ProgramOutput program, ProgramOutput preview, DisplayManager displays, AppConfig config,
                         List<Image> icons, Supplier<GraphicsDevice> operatorDevice) {
        this.program = program;
        this.preview = preview;
        this.displays = displays;
        this.config = config;
        this.icons = icons;
        this.operatorDevice = operatorDevice;
        this.displayId = config.get(AppConfig.KEY_OUTPUT_DISPLAY, null);
        this.mode = "WINDOWED".equals(config.get(AppConfig.KEY_OUTPUT_MODE, "FULLSCREEN")) ? Mode.WINDOWED : Mode.FULLSCREEN;
        displays.addChangeListener(this::onDisplaysChanged);
    }

    public void setNotifier(Consumer<String> notifier) {
        this.notifier = notifier;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ state

    /** True if any display or window shows output. */
    public boolean isOpen() {
        return routes.values().stream().anyMatch(r -> r.window != null);
    }

    /** True if some route is waiting for its display to be connected. */
    public boolean isWaiting() {
        return routes.values().stream().anyMatch(r -> r.window == null);
    }

    public Mode mode() {
        return mode;
    }

    /** The chosen projector display, falling back to the best default if it is not connected. */
    public DisplayManager.Display targetDisplay() {
        return displays.find(displayId).orElseGet(displays::defaultOutput);
    }

    /** What the projector display shows when it is opened (Program unless the operator switched it). */
    public Source projectorSource() {
        return Source.parse(config.get(KEY_PROJECTOR_SOURCE, null), Source.PROGRAM);
    }

    public List<RouteInfo> routes() {
        List<RouteInfo> out = new ArrayList<>();
        for (Route r : routes.values()) {
            DisplayManager.Display d = r.windowTarget() ? null : displays.find(r.target).orElse(null);
            int number = r.windowTarget() ? 0 : d == null ? -1 : d.number();
            out.add(new RouteInfo(r.target, number, d == null ? null : d.title(), r.source, r.window != null));
        }
        return out;
    }

    /** True if {@code source} is shown on some display or window. */
    public boolean isRouted(Source source) {
        return routes.values().stream().anyMatch(r -> r.source == source && r.window != null);
    }

    /** "Display 2 · Program  ·  Window · Preview", or Off / Waiting for display. */
    public String describe() {
        if (routes.isEmpty()) {
            return "Off";
        }
        List<String> parts = new ArrayList<>();
        for (RouteInfo r : routes()) {
            parts.add(r.showing() ? r.place() + " · " + r.source() : (r.windowed() ? "Window" : "Display") + " waiting");
        }
        return String.join("  ·  ", parts);
    }

    // ------------------------------------------------------------------ routing

    /**
     * Shows {@code source} on a display ({@code displayId}) or, when null, in a window. Asks before
     * replacing the other source on that display. Returns false if the operator cancelled.
     */
    public boolean route(Source source, String displayId, Component parent) {
        if (source == Source.PREVIEW && preview == null) {
            return false;
        }
        String target = displayId == null ? WINDOW_PREFIX + source.name() : displayId;
        Route existing = routes.get(target);
        if (existing != null && existing.source == source && existing.window != null) {
            return true;
        }
        Mode useMode = displayId == null ? Mode.WINDOWED : Mode.FULLSCREEN;
        DisplayManager.Display display = displayId == null ? displays.defaultOutput() : displays.find(displayId).orElse(null);
        if (display == null) {
            notifier.accept("That display is not connected.");
            return false;
        }
        if (existing != null && existing.source != source && displayId != null && parent != null) {
            Object[] options = {"Cancel", "Switch to " + source};
            int choice = JOptionPane.showOptionDialog(parent,
                    display.title() + " is currently showing " + existing.source + ".\n\n"
                            + "Switch " + display.title() + " to " + source + "?",
                    display.title(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    options, options[1]);
            if (choice != 1) {
                return false;
            }
        }
        if (useMode == Mode.FULLSCREEN && isOperatorScreen(display) && parent != null) {
            Object[] options = {"Use a Window", "Cover This Screen", "Cancel"};
            int choice = JOptionPane.showOptionDialog(parent,
                    display.title() + " is the screen you are working on.\n"
                            + "Fullscreen output would cover these controls.\n\n"
                            + "Connect the projector, or show " + source + " in a window.",
                    "Display output", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (choice == 0) {
                return route(source, null, parent);
            } else if (choice != 1) {
                return false;
            }
        }
        if (existing != null) {
            dispose(existing);
        }
        Route r = new Route(target, source, useMode);
        routes.put(target, r);
        show(r, display);
        saveRoutes();
        fire();
        return true;
    }

    /** Stops every display and window that shows {@code source} ("Stop Display Output"). */
    public void stop(Source source) {
        routes.values().removeIf(r -> {
            if (r.source == source) {
                dispose(r);
                return true;
            }
            return false;
        });
        saveRoutes();
        log.info("{} display output stopped", source);
        fire();
    }

    /**
     * Quick switch: the projector display shows Preview or Program from now on. When it is showing
     * something it switches at once (no confirmation: this is the operator's explicit choice).
     */
    public void setProjectorSource(Source source, Component parent) {
        config.set(KEY_PROJECTOR_SOURCE, source.name());
        config.save();
        Route r = projectorRoute();
        if (r != null && r.source != source) {
            r.source = source;
            DisplayManager.Display d = r.windowTarget() ? targetDisplay() : displays.find(r.target).orElse(null);
            dispose(r);
            if (d != null) {
                show(r, d);
            }
            saveRoutes();
        }
        fire();
    }

    // ------------------------------------------------------------------ projector (Output page, F5, Projector button)

    /** Opens the projector display with its source. Returns false if the operator cancelled. */
    public boolean open(Component parent) {
        DisplayManager.Display target = targetDisplay();
        if (mode == Mode.WINDOWED) {
            return route(projectorSource(), null, parent);
        }
        return route(projectorSource(), target.id(), parent);
    }

    /**
     * Opens the remembered routing (or the projector when nothing was remembered). A display that is
     * not connected waits for it and the operator is told.
     */
    public void restore(Component parent) {
        List<String[]> saved = savedRoutes();
        if (saved.isEmpty()) {
            open(parent);
            return;
        }
        boolean missing = false;
        for (String[] s : saved) {
            Source source = Source.parse(s[1], Source.PROGRAM);
            if (source == Source.PREVIEW && preview == null) {
                continue;
            }
            Route r = new Route(s[0], source, s[0].startsWith(WINDOW_PREFIX) ? Mode.WINDOWED : Mode.FULLSCREEN);
            DisplayManager.Display d = r.windowTarget() ? displays.defaultOutput() : displays.find(r.target).orElse(null);
            routes.put(r.target, r);
            if (d == null) {
                missing = true;
            } else {
                if (r.mode == Mode.FULLSCREEN && isOperatorScreen(d)) {
                    r.mode = Mode.WINDOWED; // never cover the controls without asking
                }
                show(r, d);
            }
        }
        if (missing) {
            notifier.accept("Configured projector display is currently unavailable. Choose another display, "
                    + "or the output returns when it is connected.");
        }
        fire();
    }

    /** Closes every output window (the remembered routing is kept for next time). */
    public void close() {
        routes.values().forEach(this::dispose);
        routes.clear();
        log.info("Display outputs closed");
        fire();
    }

    public void toggle(Component parent) {
        if (!routes.isEmpty()) {
            close();
        } else {
            List<String[]> saved = savedRoutes();
            if (saved.isEmpty()) {
                open(parent);
            } else {
                restore(parent);
            }
        }
    }

    /** Chooses the projector display; an open projector moves there with the same source. */
    public void setDisplay(String id, Component parent) {
        Route old = projectorRoute();
        displayId = id;
        config.set(AppConfig.KEY_OUTPUT_DISPLAY, id);
        config.save();
        if (old != null && !old.windowTarget() && !old.target.equals(id)) {
            dispose(old);
            routes.remove(old.target);
            route(old.source, id, parent);
        }
        fire();
    }

    public void setMode(Mode newMode, Component parent) {
        mode = newMode;
        config.set(AppConfig.KEY_OUTPUT_MODE, newMode.name());
        config.save();
        Route old = projectorRoute();
        if (old != null) {
            dispose(old);
            routes.remove(old.target);
            saveRoutes();
            open(parent);
        }
        fire();
    }

    /** Shows a large number on every monitor for a few seconds so the operator can tell them apart. */
    public void identifyDisplays() {
        List<JWindow> windows = new ArrayList<>();
        for (DisplayManager.Display d : displays.displays()) {
            JWindow w = new JWindow();
            w.setFocusableWindowState(false);
            w.setAlwaysOnTop(true);
            int size = Math.min(d.bounds().width, d.bounds().height) / 3;
            w.setBounds(d.bounds().x + (d.bounds().width - size) / 2, d.bounds().y + (d.bounds().height - size) / 2, size, size);
            w.setContentPane(new javax.swing.JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(new Color(0x1677FF));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 40, 40);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, getHeight() / 2));
                    String s = String.valueOf(d.number());
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, Math.max(12, getHeight() / 16)));
                    fm = g2.getFontMetrics();
                    String sub = (d.name() == null ? "" : d.name() + " · ") + d.pixelWidth() + "×" + d.pixelHeight()
                            + (d.primary() ? " · main" : "");
                    g2.drawString(sub, (getWidth() - fm.stringWidth(sub)) / 2, getHeight() - fm.getHeight());
                    g2.dispose();
                }
            });
            w.setVisible(true);
            windows.add(w);
        }
        Timer t = new Timer(2500, e -> windows.forEach(JWindow::dispose));
        t.setRepeats(false);
        t.start();
    }

    /** Closes the windows when the application exits. */
    public void shutdown() {
        routes.values().forEach(this::dispose);
    }

    // ------------------------------------------------------------------ internals

    /** The route on the projector display, else the first route. */
    private Route projectorRoute() {
        if (displayId != null && routes.containsKey(displayId)) {
            return routes.get(displayId);
        }
        return routes.values().stream().findFirst().orElse(null);
    }

    private void show(Route r, DisplayManager.Display display) {
        dispose(r);
        ProgramOutput state = r.source == Source.PREVIEW ? preview : program;
        String target = r.target;
        r.window = new PresentationWindow(state, r.source == Source.PREVIEW, display, r.mode == Mode.FULLSCREEN, icons,
                () -> closeRoute(target),
                () -> PresentationWindow.CursorMode.parse(config.get(AppConfig.KEY_PROJECTOR_CURSOR, null)));
        r.window.setVisible(true);
        log.info("{} output opened: {} on {}", r.source, r.mode, display.label());
    }

    /** The operator closed one output window with its close button. */
    private void closeRoute(String target) {
        Route r = routes.remove(target);
        if (r != null) {
            dispose(r);
            saveRoutes();
            fire();
        }
    }

    private void dispose(Route r) {
        if (r.window != null) {
            r.window.dispose();
            r.window = null;
        }
    }

    private void saveRoutes() {
        List<String> parts = new ArrayList<>();
        for (Route r : routes.values()) {
            parts.add(r.target + "|" + r.source.name() + "|" + r.mode.name());
        }
        config.set(KEY_ROUTES, parts.isEmpty() ? null : String.join(";", parts));
        config.save();
    }

    private List<String[]> savedRoutes() {
        String v = config.get(KEY_ROUTES, null);
        List<String[]> out = new ArrayList<>();
        if (v != null) {
            for (String part : v.split(";")) {
                String[] f = part.split("\\|");
                if (f.length >= 2 && !f[0].isBlank()) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private boolean isOperatorScreen(DisplayManager.Display d) {
        GraphicsDevice operator = operatorDevice.get();
        return operator != null && (operator == d.device() || operator.getIDstring().equals(d.id()));
    }

    private void onDisplaysChanged() {
        for (Route r : routes.values()) {
            if (r.windowTarget()) {
                continue;
            }
            DisplayManager.Display d = displays.find(r.target).orElse(null);
            if (d == null && r.window != null) {
                dispose(r);
                notifier.accept("A display showing " + r.source + " was disconnected. Output returns when it is reconnected.");
                log.warn("Output display {} disconnected", r.target);
            } else if (d != null && r.window == null) {
                if (r.mode == Mode.FULLSCREEN && isOperatorScreen(d)) {
                    r.mode = Mode.WINDOWED;
                }
                show(r, d);
                notifier.accept(d.title() + " reconnected. " + r.source + " is showing again.");
            } else if (d != null && r.window.isFullscreen() && !r.window.getBounds().equals(d.bounds())) {
                r.window.setBounds(d.bounds()); // resolution or arrangement changed: refit
            }
        }
        fire();
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }
}
