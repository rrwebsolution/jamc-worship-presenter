package com.jamc.worship.output;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.background.GlobalBackgroundManager;
import com.jamc.worship.model.BackgroundMode;
import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.render.GlobalBackgroundView;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The global video background is a layer of its own: content sits on it, covers it, or replaces it by choice. */
class GlobalBackgroundLayerTest {

    private static final Color GLOBAL = new Color(0xFF00FF);
    private static final Color THEME = new Color(0x203040);

    @TempDir
    Path tempDir;

    private final FakeGlobal global = new FakeGlobal();
    private final Compositor compositor = new Compositor(new SlideRenderer(new ImageCache(null)));

    @BeforeEach
    void install() {
        Rendering.installGlobalBackground(global);
    }

    @AfterEach
    void uninstall() {
        Rendering.installGlobalBackground(null);
    }

    private static SlideStyle theme(BackgroundMode mode) {
        StyleOverrides o = new StyleOverrides();
        o.backgroundType = BackgroundType.COLOR;
        o.backgroundValue = "#203040";
        o.backgroundMode = mode;
        return o.applyTo(SlideStyle.fallback());
    }

    private static FrameSpec live(Slide slide, SlideStyle style) {
        return new FrameSpec(LiveContent.snapshot(slide, style, "t"), true, true, false, null);
    }

    private Color corner(FrameSpec spec) {
        return new Color(compositor.render(spec, 160, 90, false).getRGB(2, 2));
    }

    @Test
    void songsVersesAndSlidesSitOnTheGlobalBackground() {
        global.active = true;
        FrameSpec song = live(new Slide("Verse 1", "Amazing grace", null), theme(BackgroundMode.USE_GLOBAL));
        assertEquals(GLOBAL, corner(song));
        assertTrue(Compositor.sitsOnGlobal(song));
        // nothing taken yet: the background alone
        assertEquals(GLOBAL, corner(FrameSpec.EMPTY));
        // background off: the theme's own background again
        global.active = false;
        assertEquals(THEME, corner(song));
        assertFalse(Compositor.sitsOnGlobal(song));
    }

    @Test
    void fullScreenPicturesCoverItAndOverrideOrHideAreExplicit() {
        global.active = true;
        Slide powerPoint = new Slide("Slide 1", "", null);
        powerPoint.setCoversBackground(true);
        FrameSpec ppt = live(powerPoint, theme(BackgroundMode.USE_GLOBAL));
        assertEquals(THEME, corner(ppt));
        assertFalse(Compositor.sitsOnGlobal(ppt));

        assertEquals(THEME, corner(live(new Slide("a", "x", null), theme(BackgroundMode.OVERRIDE))));
        assertEquals(Color.BLACK, corner(live(new Slide("a", "x", null), theme(BackgroundMode.HIDE))));
        assertEquals(0, countPaintsAfter(ppt), "the global layer is not drawn while a PowerPoint covers it");
    }

    private int countPaintsAfter(FrameSpec spec) {
        int before = global.paints;
        corner(spec);
        return global.paints - before;
    }

    @Test
    void blackCoversEverythingAndContentOnlyLeavesTheBackgroundOut() {
        global.active = true;
        FrameSpec song = live(new Slide("Chorus", "My chains are gone", null), theme(BackgroundMode.USE_GLOBAL));
        FrameSpec black = new FrameSpec(song.content(), true, true, true, null);
        assertEquals(Color.BLACK, corner(black));
        assertFalse(Compositor.sitsOnGlobal(black));

        BufferedImage layer = new BufferedImage(160, 90, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = layer.createGraphics();
        compositor.paint(g, 160, 90, song, null, false, true);
        g.dispose();
        assertEquals(0, layer.getRGB(2, 2) >>> 24, "content-only layer is transparent where the background is");
    }

    @Test
    void managerStartsOffRemembersSettingsAndNeverTurnsItselfOn() {
        AppPaths paths = new AppPaths(tempDir.resolve("home"));
        paths.ensureDirectories();
        AppConfig config = new AppConfig(paths);
        GlobalBackgroundManager m = new GlobalBackgroundManager(config, Path::of);
        assertEquals(GlobalBackgroundManager.Status.NONE, m.status());
        m.setOn(true); // no video chosen: stays off
        assertFalse(m.isActive());
        m.setOverlay(0.4);
        m.setFit(com.jamc.worship.model.FitMode.FIT);
        m.setChange(GlobalBackgroundManager.Change.FADE, 1500);
        m.setKeepPlayingInBlack(false);
        m.take(); // nothing in preview: no change
        assertNull(m.path());

        GlobalBackgroundManager again = new GlobalBackgroundManager(config, Path::of);
        assertEquals(0.4, again.overlay(), 1e-9);
        assertEquals(com.jamc.worship.model.FitMode.FIT, again.fit());
        assertEquals(GlobalBackgroundManager.Change.FADE, again.change());
        assertEquals(1500, again.changeMs());
        assertFalse(again.keepPlayingInBlack());
        assertTrue(again.muted(), "muted by default");
        assertTrue(again.loop(), "loops by default");
        assertFalse(again.isOn());
    }

    @Test
    void anImageCanBeTheGlobalBackgroundAndWaitsInPreviewUntilTake() throws Exception {
        AppPaths paths = new AppPaths(tempDir.resolve("img-home"));
        paths.ensureDirectories();
        Path blue = tempDir.resolve("blue.png");
        Path red = tempDir.resolve("red.png");
        javax.imageio.ImageIO.write(solid(Color.BLUE), "png", blue.toFile());
        javax.imageio.ImageIO.write(solid(Color.RED), "png", red.toFile());
        GlobalBackgroundManager m = new GlobalBackgroundManager(new AppConfig(paths), Path::of);
        assertTrue(GlobalBackgroundManager.isImage(blue.toString()));
        assertTrue(m.canShow(blue.toString()), "a picture needs no video engine");

        m.preview(blue.toString());
        assertFalse(m.isActive(), "Preview never changes the live background");
        m.take();
        assertTrue(m.isActive());
        assertEquals(GlobalBackgroundManager.Status.ACTIVE, m.status());
        assertEquals(Color.BLUE, waitForColor(m, Color.BLUE));

        m.preview(red.toString()); // another background in Preview: blue stays live
        assertEquals(blue.toString(), m.path());
        assertEquals(Color.BLUE, waitForColor(m, Color.BLUE));
        m.take();
        assertEquals(red.toString(), m.path());
        m.setChange(GlobalBackgroundManager.Change.CUT, 0);
        m.shutdown();
    }

    @Test
    void takeAsBackgroundChangesOnlyTheSharedBackgroundNeverTheScenes() throws Exception {
        AppPaths paths = new AppPaths(tempDir.resolve("take-home"));
        paths.ensureDirectories();
        GlobalBackgroundManager m = new GlobalBackgroundManager(new AppConfig(paths), Path::of);
        ProgramOutput preview = new ProgramOutput();
        ProgramOutput program = new ProgramOutput();
        com.jamc.worship.model.Scene worship = new com.jamc.worship.model.Scene(1L, "Worship");
        com.jamc.worship.model.Scene camera = new com.jamc.worship.model.Scene(2L, "Camera + Lyrics");
        LiveContent lyrics = LiveContent.snapshot(new Slide("Verse 1", "AMAZING GRACE", null), theme(BackgroundMode.USE_GLOBAL), "Amazing Grace");
        preview.take(lyrics, worship);
        program.take(lyrics, camera);
        var previewScene = preview.scene();
        var programScene = program.scene();
        var previewContent = preview.content();
        var programContent = program.content();

        assertFalse(GlobalBackgroundManager.isImage("color:#112233"), "a color value is not a file path");
        assertFalse(GlobalBackgroundManager.isVideo("gradient:#000000,#FFFFFF,90"));
        assertTrue(m.canShow("color:#112233"), "colors need no video engine");

        m.takeAsBackground("color:#0000FF");
        assertTrue(m.isActive(), "TAKE AS BACKGROUND puts it live at once, with no separate background TAKE");
        assertEquals(com.jamc.worship.background.GlobalBackgroundState.Type.COLOR, m.state().type());
        assertEquals(Color.BLUE, waitForColor(m, Color.BLUE));

        m.takeAsBackground("gradient:#FF0000,#FF0000,0");
        assertEquals("gradient:#FF0000,#FF0000,0", m.state().mediaPath());
        m.setChange(GlobalBackgroundManager.Change.CUT, 0);
        assertEquals(Color.RED, waitForColor(m, Color.RED));

        // the scenes and the content on both outputs are exactly as they were
        org.junit.jupiter.api.Assertions.assertSame(previewScene, preview.scene());
        org.junit.jupiter.api.Assertions.assertSame(programScene, program.scene());
        assertEquals("Worship", preview.scene().getName());
        assertEquals("Camera + Lyrics", program.scene().getName());
        org.junit.jupiter.api.Assertions.assertSame(previewContent, preview.content());
        org.junit.jupiter.api.Assertions.assertSame(programContent, program.content());

        // and off is explicit; the scenes still do not change
        m.setOn(false);
        assertFalse(m.isActive());
        org.junit.jupiter.api.Assertions.assertSame(programScene, program.scene());
        m.shutdown();
    }

    private static BufferedImage solid(Color c) {
        BufferedImage img = new BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 32, 18);
        g.dispose();
        return img;
    }

    /** The image is read off the event thread: wait briefly until it is drawn. */
    private static Color waitForColor(GlobalBackgroundManager m, Color expected) throws InterruptedException {
        Color seen = null;
        for (int i = 0; i < 100; i++) {
            BufferedImage out = new BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            boolean drawn = m.paint(g, new Rectangle(0, 0, 32, 18));
            g.dispose();
            seen = drawn ? new Color(out.getRGB(16, 9)) : null;
            if (expected.equals(seen)) {
                return seen;
            }
            Thread.sleep(20);
        }
        return seen;
    }

    @Test
    void projectorCursorDefaultsToAutoHide() {
        assertEquals(PresentationWindow.CursorMode.AUTO_HIDE, PresentationWindow.CursorMode.parse(null));
        assertEquals(PresentationWindow.CursorMode.ALWAYS_HIDDEN, PresentationWindow.CursorMode.parse("ALWAYS_HIDDEN"));
        assertEquals(PresentationWindow.CursorMode.AUTO_HIDE, PresentationWindow.CursorMode.parse("nonsense"));
    }

    /** Paints solid magenta, standing in for the running video. */
    private static final class FakeGlobal implements GlobalBackgroundView {
        boolean active;
        int paints;

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public boolean paint(Graphics2D g, Rectangle r) {
            paints++;
            g.setColor(GLOBAL);
            g.fillRect(r.x, r.y, r.width, r.height);
            return true;
        }

        @Override
        public boolean hasPreview() {
            return false;
        }

        @Override
        public boolean paintPreview(Graphics2D g, Rectangle r) {
            return paint(g, r);
        }

        @Override
        public void addListener(Runnable listener) {
        }

        @Override
        public void removeListener(Runnable listener) {
        }
    }
}
