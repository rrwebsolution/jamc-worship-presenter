package com.jamc.worship.output;

import com.jamc.worship.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The "LiveCat - OBS Output" window for OBS Window Capture.
 *
 * <p>Its picture area is exactly the chosen resolution in real screen pixels (also on scaled
 * displays), so OBS captures it 1:1. Green mode paints pure #00FF00 instead of backgrounds,
 * camera and video, for a Chroma Key filter in OBS. Streaming, recording and audio mixing stay
 * in OBS. Confined to the event dispatch thread.
 */
public final class ObsOutput {

    public static final String WINDOW_TITLE = "LiveCat - OBS Output";
    private static final Logger log = LoggerFactory.getLogger(ObsOutput.class);

    public static final String KEY_WIDTH = "obs.width";
    public static final String KEY_HEIGHT = "obs.height";
    public static final String KEY_BORDERLESS = "obs.borderless";
    public static final String KEY_GREEN = "obs.green";

    private final ProgramOutput program;
    private final AppConfig config;
    private final List<Image> icons;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private JFrame window;

    public ObsOutput(ProgramOutput program, AppConfig config, List<Image> icons) {
        this.program = program;
        this.config = config;
        this.icons = icons;
    }

    public boolean isOpen() {
        return window != null;
    }

    public int width() {
        return parse(config.get(KEY_WIDTH, "1920"), 1920);
    }

    public int height() {
        return parse(config.get(KEY_HEIGHT, "1080"), 1080);
    }

    public boolean borderless() {
        return config.getBoolean(KEY_BORDERLESS, false);
    }

    public boolean green() {
        return config.getBoolean(KEY_GREEN, false);
    }

    public void configure(int width, int height, boolean borderless, boolean green) {
        config.set(KEY_WIDTH, String.valueOf(width));
        config.set(KEY_HEIGHT, String.valueOf(height));
        config.set(KEY_BORDERLESS, String.valueOf(borderless));
        config.set(KEY_GREEN, String.valueOf(green));
        config.save();
        if (isOpen()) {
            Point location = window.getLocation();
            close();
            open();
            window.setLocation(location);
        }
        fire();
    }

    public void open() {
        if (window != null) {
            window.toFront();
            return;
        }
        JFrame f = new JFrame(WINDOW_TITLE);
        f.setIconImages(icons);
        f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        f.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
        f.setUndecorated(borderless());
        f.setResizable(false);
        f.setAutoRequestFocus(false);
        OutputCanvas canvas = new OutputCanvas(program, green()).useProgramLyricsStyle(); // OBS takes the Program lyrics style
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.BLACK);
        root.add(canvas);
        f.setContentPane(root);

        GraphicsConfiguration gc = f.getGraphicsConfiguration();
        double scale = gc == null ? 1.0 : gc.getDefaultTransform().getScaleX();
        Dimension logical = new Dimension((int) Math.round(width() / scale), (int) Math.round(height() / scale));
        canvas.setPreferredSize(logical);
        f.pack();

        if (borderless()) {
            // A borderless window has no title bar, so let the operator drag the picture itself.
            MouseAdapter drag = new MouseAdapter() {
                private Point start;

                @Override
                public void mousePressed(MouseEvent e) {
                    start = e.getPoint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (start != null) {
                        Point loc = f.getLocation();
                        f.setLocation(loc.x + e.getX() - start.x, loc.y + e.getY() - start.y);
                    }
                }
            };
            canvas.addMouseListener(drag);
            canvas.addMouseMotionListener(drag);
        }
        f.setLocationByPlatform(true);
        f.setVisible(true);
        window = f;
        log.info("OBS output opened ({}x{}, borderless={}, green={})", width(), height(), borderless(), green());
        fire();
    }

    public void close() {
        if (window != null) {
            window.dispose();
            window = null;
            fire();
        }
    }

    public void toggle() {
        if (isOpen()) {
            close();
        } else {
            open();
        }
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }

    private static int parse(String s, int fallback) {
        try {
            return Math.clamp(Integer.parseInt(s.strip()), 320, 3840);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
