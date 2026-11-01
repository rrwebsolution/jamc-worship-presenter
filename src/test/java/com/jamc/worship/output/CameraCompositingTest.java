package com.jamc.worship.output;

import com.jamc.worship.camera.CameraStatus;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.LiveFrames;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Camera sources: crop / rotate / mirror, the offline picture, and switching cameras without touching other layers. */
class CameraCompositingTest {

    private static final int RED = 0xFF0000;
    private static final int GREEN = 0x00FF00;
    private static final int BLUE = 0x0000FF;

    private final Map<String, BufferedImage> pictures = new HashMap<>();
    private final Map<String, CameraStatus> statuses = new HashMap<>();

    private void installCameras() {
        Rendering.installFrames(new LiveFrames() {
            @Override public BufferedImage video(String path) { return null; }
            @Override public BufferedImage videoPoster(String path) { return null; }
            @Override public BufferedImage programVideo() { return null; }
            @Override public BufferedImage camera(String name) { return pictures.get(name); }
            @Override public CameraStatus cameraStatus(String name) { return statuses.getOrDefault(name, CameraStatus.READY); }
        });
    }

    @AfterEach
    void restore() {
        Rendering.installFrames(LiveFrames.NONE);
    }

    private static BufferedImage solid(int rgb) {
        BufferedImage img = new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, 16, 9);
        g.dispose();
        return img;
    }

    /** Left half red, right half blue. */
    private static BufferedImage halves() {
        BufferedImage img = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                img.setRGB(x, y, x < 2 ? RED : BLUE);
            }
        }
        return img;
    }

    private static int at(SourceConfig c, int x, int y) {
        BufferedImage out = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        c.fit = FitMode.STRETCH;
        Compositor.drawCamera(g, halves(), new Rectangle(0, 0, 40, 20), c, 1f);
        g.dispose();
        return out.getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    void cropRotateAndMirrorChangeOnlyTheCameraPicture() {
        assertEquals(RED, at(new SourceConfig(), 5, 10));
        assertEquals(BLUE, at(new SourceConfig(), 35, 10));
        SourceConfig crop = new SourceConfig();
        crop.cropLeft = 0.5;
        assertEquals(BLUE, at(crop, 5, 10), "the left half is cropped away");
        SourceConfig mirror = new SourceConfig();
        mirror.mirror = true;
        assertEquals(BLUE, at(mirror, 5, 10));
        SourceConfig turned = new SourceConfig();
        turned.rotation = 180;
        assertEquals(BLUE, at(turned, 5, 10));
        assertEquals(RED, at(turned, 35, 10));
    }

    private static Scene cameraScene(long id, String camera) {
        Scene s = new Scene(id, camera);
        Source cam = new Source(camera, SourceType.CAMERA);
        cam.getConfig().camera = camera;
        cam.getConfig().fit = FitMode.STRETCH;
        s.getSources().add(cam);
        return s;
    }

    @Test
    void anOfflineCameraShowsItsOfflinePictureNeverAnError() {
        installCameras();
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        Scene scene = cameraScene(1L, "CAM 2");
        statuses.put("CAM 2", CameraStatus.DISCONNECTED);
        BufferedImage slate = c.render(new FrameSpec(null, true, true, false, scene), 192, 108, false);
        assertNotEquals(0, slate.getRGB(5, 5) & 0xFFFFFF, "the \"Camera Offline\" card, not black");

        scene.getSources().getFirst().getConfig().offline = "BLACK";
        BufferedImage black = c.render(new FrameSpec(null, true, true, false, scene), 192, 108, false);
        assertEquals(0, black.getRGB(5, 5) & 0xFFFFFF);

        statuses.put("CAM 2", CameraStatus.INITIALIZING);
        scene.getSources().getFirst().getConfig().offline = null;
        BufferedImage starting = c.render(new FrameSpec(null, true, true, false, scene), 192, 108, false);
        assertEquals(0, starting.getRGB(5, 5) & 0xFFFFFF, "a camera that is starting shows nothing yet (no card flash)");
    }

    /** CAM 1 → CAM 2 under the same logo: mid-transition, the logo is exactly as before; only the camera changes. */
    @Test
    void switchingCamerasFadesOnlyTheCameraLayer() {
        installCameras();
        pictures.put("CAM 1", solid(GREEN));
        pictures.put("CAM 2", solid(BLUE));
        Scene one = cameraScene(1L, "CAM 1");
        Scene two = cameraScene(2L, "CAM 2");
        for (Scene s : new Scene[]{one, two}) {
            Source logo = new Source("Logo", SourceType.COLOR);
            logo.getConfig().color = "#FF0000";
            logo.getConfig().x = 0;
            logo.getConfig().y = 0;
            logo.getConfig().w = 0.25;
            logo.getConfig().h = 0.25;
            s.getSources().addFirst(logo); // top layer
        }
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        BufferedImage img = new BufferedImage(192, 108, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        c.paintSceneChange(g, 192, 108, new FrameSpec(null, true, true, false, one), new FrameSpec(null, true, true, false, two),
                null, false, 0.5f, 0.5f);
        g.dispose();
        assertEquals(RED, img.getRGB(10, 10) & 0xFFFFFF, "the shared logo never fades");
        int mid = img.getRGB(150, 80);
        assertTrue(((mid >> 8) & 0xFF) > 40 && (mid & 0xFF) > 40, "the camera area mixes CAM 1 and CAM 2: " + Integer.toHexString(mid));
    }

    @Test
    void cameraSwitchingIsACutByDefaultAndCanFade() {
        ProgramOutput program = new ProgramOutput();
        program.setTransition(Transition.FADE, 500);
        Scene one = cameraScene(1L, "CAM 1");
        Scene two = cameraScene(2L, "CAM 2");
        program.take(null, one);
        program.take(null, two);
        assertEquals(Transition.CUT, program.activeTransition(), "camera switching feels immediate");
        assertNull(program.previous());

        program.setCameraSwitchFade(300);
        program.take(null, one);
        assertEquals(Transition.CROSSFADE, program.activeTransition());
        assertNotNull(program.previous());

        Scene words = new Scene(3L, "Worship");
        words.getSources().add(new Source("Lyrics", SourceType.LYRICS));
        program.take(null, words);
        assertEquals(Transition.FADE, program.activeTransition(), "other scene changes keep the normal transition");
    }
}
