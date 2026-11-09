package com.jamc.worship.ui.components;

import com.jamc.worship.camera.CameraStatus;
import com.jamc.worship.monitor.HealthMonitor;
import com.jamc.worship.monitor.HealthThresholds;
import com.jamc.worship.monitor.SystemSampler;
import com.jamc.worship.stream.StreamEncoder;
import com.jamc.worship.stream.StreamManager.Session;
import com.jamc.worship.stream.StreamManager.State;
import com.jamc.worship.ui.theme.JamcDarkLaf;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The footer health strip: real readings, N/A when there are none, narrow windows keep the essentials. */
class HealthStripTest {

    private static final long GB = 1L << 30;

    static HealthMonitor.Report sample(boolean streaming) {
        HealthMonitor m = new HealthMonitor(() -> HealthThresholds.DEFAULTS);
        var r = new SystemSampler.Reading(0.28, 0.12, 8, (long) (2.4 * GB), 16 * GB, 300L << 20, 4 * GB);
        Session live = streaming
                ? new Session(State.ACTIVE, null, new StreamEncoder.Stats(36000, 60, 5800, 0, 0), System.currentTimeMillis() - 754_000, null)
                : new Session(State.IDLE, null, null, 0, null);
        List<HealthMonitor.Camera> cams = List.of(
                new HealthMonitor.Camera("Logitech BRIO", CameraStatus.READY, "1920×1080", 30, null),
                new HealthMonitor.Camera("Elgato Cam Link 4K", CameraStatus.READY, "1920×1080", 60, null),
                new HealthMonitor.Camera("OBS Virtual Camera", CameraStatus.READY, "1280×720", 30, null),
                new HealthMonitor.Camera("USB Camera", CameraStatus.READY, "1280×720", 30, null));
        return m.update(new HealthMonitor.Inputs(r, live, new Session(State.IDLE, null, null, 0, null), cams, true), 0);
    }

    private static boolean shown(Component c) {
        return c.getWidth() > 0;
    }

    @Test
    void wideShowsEverythingNarrowKeepsTheEssentials() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JamcDarkLaf.setup();
            HealthStrip strip = new HealthStrip();
            strip.show(sample(true));
            JComponent[] parts = parts(strip);
            strip.setSize(strip.getPreferredSize().width, 24);
            strip.doLayout();
            for (JComponent p : parts) {
                if (!"+".equals(first(p))) {
                    assertTrue(shown(p), "wide: " + text(p) + " is shown");
                }
            }
            assertTrue(text(parts[0]).startsWith("CPU 28%"));
            assertEquals("RAM 2.4 GB", text(parts[1]));
            assertEquals("60 FPS", text(parts[2]));
            assertEquals("5.8 Mbps", text(parts[3]));
            assertEquals("Drop 0.0%", text(parts[4]));
            assertEquals("CAM 4/4", text(parts[5]));
            assertTrue(text(parts[6]).startsWith("LIVE 00:12:"));
            assertEquals("Healthy", text(parts[8]));

            strip.setSize(strip.getMinimumSize().width + 200, 24);
            strip.doLayout();
            assertTrue(shown(parts[8]), "Health always stays");
            assertTrue(shown(parts[7]) && text(parts[7]).startsWith("+"), "the rest is behind +N");
            assertFalse(shown(parts[4]), "dropped frames give way first");
            assertTrue(strip.getComponentCount() > 0);

            strip.show(sample(false));
            assertEquals("FPS N/A", text(parts[2]), "nothing fabricated while not streaming");
            assertEquals("N/A Mbps", text(parts[3]));
            assertEquals("Offline", text(parts[6]));
        });
    }

    @Test
    void rendersForAVisualCheck() throws Exception {
        String out = System.getProperty("health.render");
        if (out == null) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            JamcDarkLaf.setup();
            for (int w : new int[]{1400, 800, 480}) {
                StatusBar bar = new StatusBar();
                bar.setDataDirectory("C:\\Users\\me\\AppData\\Roaming\\JAMC Worship Presenter");
                bar.showMessage("Scene \"Worship\" is live");
                bar.health().show(sample(true));
                bar.setSize(w, bar.getPreferredSize().height);
                layoutAll(bar);
                save(bar, new File(out, "footer-" + w + ".png"));
                if (w == 1400) {
                    JComponent d = bar.health().detailsView();
                    d.setSize(440, d.getPreferredSize().height);
                    layoutAll(d);
                    save(d, new File(out, "details.png"));
                }
            }
        });
    }

    private static void layoutAll(Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container k) {
            for (Component x : k.getComponents()) {
                layoutAll(x);
            }
        }
    }

    private static void save(JComponent c, File f) {
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c.getBackground());
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        c.printAll(g);
        g.dispose();
        try {
            f.getParentFile().mkdirs();
            ImageIO.write(img, "png", f);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static JComponent[] parts(HealthStrip s) {
        JComponent[] out = new JComponent[s.getComponentCount()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (JComponent) s.getComponent(i);
        }
        return out;
    }

    private static String text(JComponent c) {
        return ((javax.swing.JLabel) c).getText();
    }

    private static String first(JComponent c) {
        String t = text(c);
        return t == null || t.isEmpty() ? "+" : t.substring(0, 1);
    }
}
