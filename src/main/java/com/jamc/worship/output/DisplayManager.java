package com.jamc.worship.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects connected monitors and notices when one is plugged in or removed (a projector cable
 * coming loose mid-service is a real scenario).
 */
public final class DisplayManager {

    private static final Logger log = LoggerFactory.getLogger(DisplayManager.class);

    /**
     * One connected monitor.
     *
     * @param id     stable device id (e.g. {@code \Display2}), stored in settings
     * @param number 1-based number shown to the operator
     * @param bounds desktop area in Java (scaled) coordinates
     * @param name   the connected screen's own name ("EPSON PJ", "SAMSUNG"), null if Windows has none
     */
    public record Display(String id, int number, Rectangle bounds, int pixelWidth, int pixelHeight, boolean primary,
                          GraphicsDevice device, String name) {

        public String label() {
            return title() + "  ·  " + pixelWidth + "×" + pixelHeight + (primary ? "  ·  main screen" : "");
        }

        /** "EPSON PJ (Display 2)", or "Display 2" when the screen has no name. */
        public String title() {
            return name == null ? "Display " + number : name + " (Display " + number + ")";
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Timer poll = new Timer(2000, e -> checkForChanges());
    private List<Display> displays;
    private String signature;

    public DisplayManager() {
        displays = scan(true);
        signature = signature(displays);
    }

    public List<Display> displays() {
        return displays;
    }

    public Optional<Display> find(String id) {
        return id == null ? Optional.empty() : displays.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    /** The display that contains {@code device} (e.g. the operator window's screen). */
    public Optional<Display> forDevice(GraphicsDevice device) {
        return displays.stream().filter(d -> d.device() == device || d.id().equals(device.getIDstring())).findFirst();
    }

    /** Best default for the projector: the first secondary display, otherwise the main screen. */
    public Display defaultOutput() {
        return displays.stream().filter(d -> !d.primary()).findFirst().orElse(displays.getFirst());
    }

    public boolean hasSecondaryDisplay() {
        return displays.stream().anyMatch(d -> !d.primary());
    }

    /** Starts watching for monitors being connected or disconnected. Listeners run on the EDT. */
    public void startMonitoring() {
        poll.start();
    }

    public void stopMonitoring() {
        poll.stop();
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    private void checkForChanges() {
        List<Display> now;
        try {
            now = scan(false);
            String sig = signature(now);
            if (sig.equals(signature)) {
                return;
            }
            now = scan(true); // the names are read only when something changed
            log.info("Displays changed: {}", sig);
            displays = now;
            signature = sig;
        } catch (RuntimeException e) {
            log.warn("Could not enumerate displays", e);
            return;
        }
        listeners.forEach(Runnable::run);
    }

    private static List<Display> scan(boolean withNames) {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice primary = env.getDefaultScreenDevice();
        GraphicsDevice[] windowsOrder = env.getScreenDevices();
        Map<GraphicsDevice, String> names = withNames ? names(windowsOrder) : Map.of();
        List<GraphicsDevice> devices = new ArrayList<>(List.of(windowsOrder));
        // Number displays left to right, which matches how people describe their setup.
        devices.sort((a, b) -> Integer.compare(a.getDefaultConfiguration().getBounds().x,
                b.getDefaultConfiguration().getBounds().x));
        List<Display> result = new ArrayList<>();
        for (int i = 0; i < devices.size(); i++) {
            GraphicsDevice d = devices.get(i);
            Rectangle bounds = d.getDefaultConfiguration().getBounds();
            DisplayMode mode = d.getDisplayMode();
            int pw = mode != null ? mode.getWidth() : bounds.width;
            int ph = mode != null ? mode.getHeight() : bounds.height;
            result.add(new Display(d.getIDstring(), i + 1, bounds, pw, ph, d == primary, d, names.get(d)));
        }
        return List.copyOf(result);
    }

    /**
     * Matches Java's screen devices to Windows' monitors. Both list them in the same order; the size is
     * checked as well, and on a mismatch the first unused monitor of that size is taken.
     */
    private static Map<GraphicsDevice, String> names(GraphicsDevice[] devices) {
        List<MonitorNames.Monitor> monitors = new ArrayList<>(MonitorNames.monitors());
        Map<GraphicsDevice, String> out = new HashMap<>();
        boolean[] used = new boolean[monitors.size()];
        for (int i = 0; i < devices.length; i++) {
            DisplayMode mode = devices[i].getDisplayMode();
            if (mode == null) {
                continue;
            }
            int match = -1;
            if (i < monitors.size() && !used[i] && sameSize(monitors.get(i), mode)) {
                match = i;
            } else {
                for (int m = 0; m < monitors.size() && match < 0; m++) {
                    if (!used[m] && sameSize(monitors.get(m), mode)) {
                        match = m;
                    }
                }
            }
            if (match >= 0) {
                used[match] = true;
                if (monitors.get(match).name() != null) {
                    out.put(devices[i], monitors.get(match).name());
                }
            }
        }
        return out;
    }

    private static boolean sameSize(MonitorNames.Monitor m, DisplayMode mode) {
        return m.bounds().width == mode.getWidth() && m.bounds().height == mode.getHeight();
    }

    private static String signature(List<Display> displays) {
        StringBuilder sb = new StringBuilder();
        for (Display d : displays) {
            sb.append(d.id()).append('@').append(d.bounds()).append(';');
        }
        return sb.toString();
    }
}
