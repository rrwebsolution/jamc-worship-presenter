package com.jamc.worship.camera;

import com.jamc.worship.camera.CameraManager.Detection;
import com.jamc.worship.camera.CameraManager.Feed;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Camera sessions: shared per device, kept open for Preview / Program, hot plug and failure isolation. */
class CameraManagerTest {

    /** A camera that sends a frame every time it is asked (or never, when broken). */
    static final class FakeFeed implements Feed {
        final String name;
        final boolean broken;
        long frames;
        boolean released;
        private final BufferedImage image = new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB);

        FakeFeed(String name, boolean broken) {
            this.name = name;
            this.broken = broken;
        }

        @Override
        public BufferedImage frame() {
            if (broken || released) {
                return null;
            }
            frames++;
            return image;
        }

        @Override public long frames() { return frames; }
        @Override public boolean failed() { return broken; }
        @Override public void release() { released = true; }
    }

    private final List<FakeFeed> opened = new ArrayList<>();
    private final Map<String, Boolean> broken = new HashMap<>();
    private volatile List<String> connected = new ArrayList<>(List.of("CAM A", "CAM B", "CAM C"));

    private CameraManager manager() {
        return onEdt(() -> new CameraManager((name, format) -> {
            FakeFeed f = new FakeFeed(name, broken.getOrDefault(name, false));
            opened.add(f);
            return f;
        }, () -> new Detection(List.copyOf(connected), List.of("Microphone (Realtek Audio)", "Digital Audio Interface (CAM B)")),
                () -> true, false));
    }

    private static <T> T onEdt(Callable<T> c) {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    out.set(c.call());
                } catch (Exception e) {
                    err.set(e);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (err.get() != null) {
            throw new IllegalStateException(err.get());
        }
        return out.get();
    }

    private long opens(String name) {
        return opened.stream().filter(f -> f.name.equals(name)).count();
    }

    private FakeFeed last(String name) {
        return opened.stream().filter(f -> f.name.equals(name)).reduce((a, b) -> b).orElse(null);
    }

    @Test
    void oneSessionPerCameraIsSharedByEveryScene() {
        CameraManager m = manager();
        m.detect();
        onEdt(() -> {
            for (int i = 0; i < 5; i++) {
                m.frame("CAM A"); // Program, Preview, the editor and the multiview all ask for the same camera
            }
            m.frame("CAM B");
            return null;
        });
        assertEquals(1, opens("CAM A"), "the camera is opened once, not once per scene or monitor");
        assertEquals(1, opens("CAM B"));
        assertEquals(CameraStatus.READY, onEdt(() -> m.status("CAM A")));
    }

    @Test
    void previewAndProgramCamerasAreOpenedBeforeTheyAreShown() {
        CameraManager m = manager();
        m.detect();
        onEdt(() -> {
            m.keepOpen("program", List.of("CAM A"));
            m.keepOpen("preview", List.of("CAM B"));
            return null;
        });
        assertEquals(1, opens("CAM A"));
        assertEquals(1, opens("CAM B"), "TAKE never waits for Preview's camera to start");
        assertEquals(0, opens("CAM C"));
        onEdt(() -> {
            m.sweep();
            return null;
        });
        assertFalse(last("CAM A").released, "a kept camera stays open even while nothing paints it");
    }

    @Test
    void anUnpluggedCameraIsDisconnectedWithoutTouchingTheOthersAndComesBack() throws Exception {
        CameraManager m = manager();
        m.detect();
        onEdt(() -> {
            m.keepOpen("program", List.of("CAM A"));
            m.keepOpen("preview", List.of("CAM B"));
            m.frame("CAM A");
            m.frame("CAM B");
            return null;
        });
        FakeFeed a = last("CAM A");
        FakeFeed b = last("CAM B");

        connected = new ArrayList<>(List.of("CAM A", "CAM C")); // CAM B's cable pulled
        m.detect();
        SwingUtilities.invokeAndWait(() -> { }); // the detection result is applied on the EDT
        assertEquals(CameraStatus.DISCONNECTED, onEdt(() -> m.status("CAM B")));
        assertTrue(b.released, "the missing camera's session is closed");
        assertNull(onEdt(() -> m.frame("CAM B")), "no frozen picture from an unplugged camera");
        assertFalse(a.released, "Program's camera keeps running");
        assertNotNull(onEdt(() -> m.frame("CAM A")));
        assertEquals(CameraStatus.READY, onEdt(() -> m.status("CAM A")));

        connected = new ArrayList<>(List.of("CAM A", "CAM B", "CAM C")); // plugged back in
        m.detect();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(2, opens("CAM B"), "the kept camera is reopened by itself");
        assertNotNull(onEdt(() -> m.frame("CAM B")));
        assertEquals(CameraStatus.READY, onEdt(() -> m.status("CAM B")));
        assertEquals(1, opens("CAM A"), "CAM A was never restarted");
    }

    @Test
    void aFailingCameraShowsErrorAndABandwidthWarningWhileOthersRun() throws Exception {
        broken.put("CAM C", true);
        CameraManager m = manager();
        m.detect();
        onEdt(() -> {
            m.frame("CAM A");
            m.frame("CAM A");
            m.frame("CAM C");
            return null;
        });
        assertEquals(CameraStatus.INITIALIZING, onEdt(() -> m.status("CAM C")));
        Thread.sleep(CameraManager.FALLBACK_MS + 200);
        onEdt(() -> {
            m.frame("CAM A");
            return m.frame("CAM C");
        });
        assertEquals(CameraStatus.ERROR, onEdt(() -> m.status("CAM C")));
        assertTrue(onEdt(() -> m.bandwidthWarning("CAM C")), "other cameras run, so it is likely the USB controller");
        assertTrue(onEdt(() -> m.problem("CAM C")).startsWith(CameraManager.BANDWIDTH));
        assertEquals(CameraStatus.READY, onEdt(() -> m.status("CAM A")), "one failing camera never affects another");
        assertNotNull(onEdt(() -> m.frame("CAM A")));
    }

    @Test
    void cameraAudioIsTheInputNamedAfterTheCameraOrChosenByTheOperator() {
        CameraManager m = manager();
        m.detect();
        assertEquals("Digital Audio Interface (CAM B)", m.audioDevice("CAM B"));
        assertNull(m.audioDevice("CAM A"), "a camera without its own input has no audio");
        m.setAudioOverride(name -> name.equals("CAM A") ? "Microphone (Realtek Audio)" : name.equals("CAM B") ? "" : null);
        assertEquals("Microphone (Realtek Audio)", m.audioDevice("CAM A"));
        assertNull(m.audioDevice("CAM B"), "\"\" = the operator chose no audio");
        assertEquals("Digital Audio Interface (USB Capture HDMI)",
                CameraManager.matchAudio("USB Capture HDMI", List.of("Microphone (Realtek)", "Digital Audio Interface (USB Capture HDMI)")));
        assertNull(CameraManager.matchAudio("Integrated Camera", List.of("Microphone Array (Realtek(R) Audio)")));
    }

    @Test
    void refreshReopensOnlyThatCameraAfterItHasClosed() throws Exception {
        CameraManager m = manager();
        m.detect();
        onEdt(() -> {
            m.frame("CAM A");
            m.frame("CAM B");
            return null;
        });
        FakeFeed first = last("CAM A");
        FakeFeed other = last("CAM B");
        onEdt(() -> {
            m.refresh("CAM A");
            assertEquals(CameraStatus.INITIALIZING, m.status("CAM A"), "shown as starting while it closes");
            return null;
        });
        for (int i = 0; i < 100 && !first.released; i++) {
            Thread.sleep(20);
        }
        assertTrue(first.released, "the old session is closed");
        assertFalse(other.released, "the other camera keeps running");
        for (int i = 0; i < 100 && opens("CAM A") < 2; i++) {
            onEdt(() -> m.frame("CAM A"));
            Thread.sleep(20);
        }
        assertEquals(2, opens("CAM A"), "opened again after closing");
        assertEquals(1, opens("CAM B"));
    }

    @Test
    void formatsComeFromWhatTheCameraReports() {
        String ffmpeg = """
                [dshow @ 0000] DirectShow video device options (from video devices)
                [dshow @ 0000]  Pin "Capture" (alternative pin name "0")
                [dshow @ 0000]   vcodec=mjpeg  min s=1920x1080 fps=5 max s=1920x1080 fps=30
                [dshow @ 0000]   vcodec=mjpeg  min s=1280x720 fps=5 max s=1280x720 fps=60
                [dshow @ 0000]   pixel_format=yuyv422  min s=640x480 fps=5 max s=640x480 fps=30
                """;
        CameraManager.Capabilities caps = CameraManager.parseOptions(ffmpeg);
        assertEquals(List.of("1920x1080", "1280x720", "640x480"), caps.sizes(), "largest first");
        assertEquals(List.of(60, 50, 30, 25, 24, 15), caps.rates());
        assertNull(CameraManager.parseOptions("Could not enumerate video device options"));
    }
}
