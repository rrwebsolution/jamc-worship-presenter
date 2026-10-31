package com.jamc.worship.output;

import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.LowerThird;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositorTest {

    @TempDir
    Path dir;

    private static final SlideStyle BLUE = new SlideStyle(BackgroundType.COLOR, "#0000FF", "#0000FF", 0, "Dialog", 200,
            true, false, "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);

    private LiveContent content() {
        return LiveContent.snapshot(new Slide("x", "WWWWWW", null), BLUE, "t");
    }

    /** Camera + Lyrics: the same lyrics, smaller and at the bottom, over a readability overlay. */
    @Test
    void aSceneCanPlaceTheLyricsLowerAndSmaller() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        LiveContent words = content();
        Scene full = new Scene(1L, "Worship");
        full.getSources().add(new Source("Lyrics", SourceType.LYRICS));
        Scene camLyrics = new Scene(2L, "Camera + Lyrics");
        Source lyrics = new Source("Lyrics", SourceType.LYRICS);
        lyrics.getConfig().textPosition = TextPosition.BOTTOM;
        lyrics.getConfig().textScale = 0.5;
        camLyrics.getSources().add(lyrics);

        int[] fullRows = textRows(c.render(new FrameSpec(words, true, true, false, full), 192, 108, false));
        int[] lowRows = textRows(c.render(new FrameSpec(words, true, true, false, camLyrics), 192, 108, false));
        assertTrue(lowRows[0] > 108 * 0.6, "text sits in the lower part: first row " + lowRows[0]);
        assertTrue(lowRows[1] - lowRows[0] < fullRows[1] - fullRows[0], "and is smaller");

        Scene overlay = new Scene(3L, "Overlay");
        Source o = new Source("Overlay", SourceType.OVERLAY);
        o.getConfig().y = 0.5;
        o.getConfig().h = 0.5;
        o.getConfig().opacity = 1.0;
        overlay.getSources().add(o);
        BufferedImage img = c.render(new FrameSpec(null, true, true, false, overlay), 192, 108, false);
        assertEquals(0, img.getRGB(96, 104) & 0xFFFFFF, "the overlay darkens the bottom");
    }

    /** Edit Layout: a smaller lyrics box keeps the text inside it, and the text gets smaller. */
    @Test
    void lyricsFitTheirLayerBox() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        Scene boxed = new Scene(4L, "Boxed");
        Source lyrics = new Source("Lyrics", SourceType.LYRICS);
        lyrics.getConfig().x = 0.5;
        lyrics.getConfig().y = 0.5;
        lyrics.getConfig().w = 0.5;
        lyrics.getConfig().h = 0.5;
        boxed.getSources().add(lyrics);
        Scene full = new Scene(5L, "Full");
        full.getSources().add(new Source("Lyrics", SourceType.LYRICS));
        BufferedImage img = c.render(new FrameSpec(content(), true, true, false, boxed), 384, 216, false);
        int[] rows = textRows(img);
        assertTrue(rows[0] >= 108, "text starts in the lower half (box top), row " + rows[0]);
        for (int y = 0; y < 216; y++) {
            for (int x = 0; x < 192; x++) {
                Color px = new Color(img.getRGB(x, y));
                assertFalse(px.getRed() > 200 && px.getGreen() > 200, "no text left of the box at " + x + "," + y);
            }
        }
        int[] fullRows = textRows(c.render(new FrameSpec(content(), true, true, false, full), 384, 216, false));
        assertTrue(rows[1] - rows[0] < fullRows[1] - fullRows[0], "smaller box, smaller text");
    }

    /** First and last row (y) containing white text pixels. */
    private static int[] textRows(BufferedImage img) {
        int first = -1;
        int last = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color px = new Color(img.getRGB(x, y));
                if (px.getRed() > 200 && px.getGreen() > 200) {
                    if (first < 0) {
                        first = y;
                    }
                    last = y;
                    break;
                }
            }
        }
        return new int[]{first, last};
    }

    @Test
    void contentTakeKeepsTheProgramScene() {
        ProgramOutput program = new ProgramOutput();
        program.setTransition(Transition.CUT, 0);
        Scene camLyrics = new Scene(2L, "Camera + Lyrics");
        program.setScene(camLyrics, false);
        program.take(content(), null); // content TAKE: next lyrics only
        assertEquals(camLyrics.getId(), program.scene().getId(), "the camera scene stays live");
        Scene worship = new Scene(1L, "Worship");
        program.take(content(), worship); // scene TAKE
        assertEquals(worship.getId(), program.scene().getId());
    }

    @Test
    void sceneLayersAreComposedBottomToTop() throws Exception {
        BufferedImage red = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = red.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 10, 10);
        g.dispose();
        ImageIO.write(red, "png", dir.resolve("logo.png").toFile());
        ImageCache cache = new ImageCache(dir);
        cache.getBlocking("logo.png");
        Compositor c = new Compositor(new SlideRenderer(cache));

        Scene scene = new Scene(1L, "Test");
        Source logo = new Source("Logo", SourceType.CHURCH_LOGO);
        logo.getConfig().path = "logo.png";
        logo.getConfig().x = 0;
        logo.getConfig().y = 0;
        logo.getConfig().w = 0.25;
        logo.getConfig().h = 0.25;
        logo.getConfig().fit = FitMode.STRETCH;
        scene.getSources().add(logo);
        scene.getSources().add(new Source("Lyrics", SourceType.LYRICS));
        scene.getSources().add(new Source("Background", SourceType.BACKGROUND));

        BufferedImage out = c.render(new FrameSpec(content(), true, true, false, scene), 320, 180, false);
        assertEquals(Color.RED.getRGB(), out.getRGB(10, 10), "logo on top of the background");
        assertEquals(Color.BLUE.getRGB(), out.getRGB(300, 170), "background elsewhere");

        logo.setVisible(false);
        BufferedImage hidden = c.render(new FrameSpec(content(), true, true, false, scene.copy()), 320, 180, false);
        assertEquals(Color.BLUE.getRGB(), hidden.getRGB(10, 10), "hidden source is not drawn");
    }

    @Test
    void sceneWithoutTextSourceHidesLyricsAndEmptySceneIsBlack() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(dir)));
        Scene bgOnly = new Scene(2L, "Background only");
        bgOnly.getSources().add(new Source("Background", SourceType.BACKGROUND));
        BufferedImage out = c.render(new FrameSpec(content(), true, true, false, bgOnly), 320, 180, false);
        for (int x = 0; x < 320; x++) {
            assertEquals(Color.BLUE.getRGB(), out.getRGB(x, 90), "no text layer in this scene");
        }
        BufferedImage blank = c.render(new FrameSpec(content(), true, true, false, new Scene(3L, "Blank")), 32, 18, false);
        assertEquals(Color.BLACK.getRGB(), blank.getRGB(5, 5));
    }

    @Test
    void keyModeReplacesBackgroundWithGreenButKeepsText() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(dir)));
        BufferedImage out = c.render(new FrameSpec(content(), true, true, false, null), 320, 180, true);
        assertEquals(Compositor.KEY_GREEN.getRGB(), out.getRGB(2, 2));
        boolean white = false;
        for (int x = 0; x < 320 && !white; x++) {
            white = out.getRGB(x, 90) == Color.WHITE.getRGB();
        }
        assertTrue(white, "text stays visible for keying");
    }

    @Test
    void lowerThirdAnimatesAndIsDrawnInScenesWithALowerThirdSource() {
        ProgramOutput program = new ProgramOutput();
        LowerThird lt = new LowerThird();
        lt.setTitle("Ptr. Carlo");
        lt.setAnimation(LowerThird.Animation.NONE);
        assertEquals(0f, program.lowerThirdProgress());
        program.showLowerThird(lt);
        assertTrue(program.isLowerThirdOn());
        assertEquals(1f, program.lowerThirdProgress(), "no animation shows immediately");

        Compositor c = new Compositor(new SlideRenderer(new ImageCache(dir)));
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        c.paint(g, 320, 180, new FrameSpec(null, true, true, false, null), program, false);
        g.dispose();
        // Bottom-left panel area is no longer pure black
        assertFalse(img.getRGB(30, 150) == Color.BLACK.getRGB(), "lower third panel drawn bottom left");

        program.hideLowerThird();
        assertFalse(program.isLowerThirdOn());
    }

    @Test
    void scenesSwitchWithTransitionsAndEditsApplyInstantly() {
        ProgramOutput program = new ProgramOutput();
        program.setTransition(Transition.FADE, 1000);
        Scene a = new Scene(1L, "A");
        program.setScene(a, false);
        assertEquals(null, program.previous(), "edits apply without animation");
        program.setScene(new Scene(2L, "B"), true);
        assertTrue(program.previous() != null && program.progress() < 1f, "scene switch fades");
        assertEquals("B", program.scene().getName());
    }
}
