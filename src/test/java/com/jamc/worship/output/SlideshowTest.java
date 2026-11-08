package com.jamc.worship.output;

import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IMAGE SLIDESHOW: Preview and Program play separately; only the slideshow layer changes. */
class SlideshowTest {

    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(0);
    /** library id → path; id 3 is missing (deleted from the library). */
    private final Map<Long, String> library = new HashMap<>(Map.of(1L, "one.png", 2L, "two.png", 4L, "four.png", 5L, "five.png"));

    private Slideshows shows() {
        return new Slideshows(library::get, now::get, false);
    }

    private static Scene announcements(long sceneId) {
        Scene scene = new Scene(sceneId, "Sunday Announcements");
        Source show = new Source("Image Slideshow", SourceType.SLIDESHOW);
        show.setId(11L);
        show.getConfig().uid = "sunday-announcements";
        show.getConfig().imageIds = List.of(1L, 2L, 3L, 4L, 5L);
        scene.getSources().add(show);
        return scene;
    }

    @AfterEach
    void uninstall() {
        Compositor.installSlideshows(null);
    }

    /** SCENE EDITOR: PREV / NEXT move the editor's images even when the scene is in neither Preview nor Program. */
    @Test
    void editorNextWorksWithoutPreview() {
        Slideshows shows = shows();
        Source s = announcements(1L).getSources().getFirst();
        assertEquals("one.png", shows.editorView(s).current(), "the first image before NEXT");
        shows.controlEditor(s, Slideshows.Control.NEXT);
        assertEquals("two.png", shows.editorView(s).current());
        shows.controlEditor(s, Slideshows.Control.NEXT);
        assertEquals("four.png", shows.editorView(s).current(), "the missing image is skipped");
        assertEquals(3, shows.editorPosition(s)[0]);
        assertEquals(4, shows.editorPosition(s)[1]);
        shows.controlEditor(s, Slideshows.Control.PREVIOUS);
        assertEquals("two.png", shows.editorView(s).current());
        assertNull(shows.status(s), "Preview and Program were not touched");
        shows.controlEditor(s, Slideshows.Control.PLAY);
        assertTrue(shows.editorPlaying(s));
        shows.pauseEditor(); // the editor left the screen
        assertFalse(shows.editorPlaying(s));
        assertEquals("two.png", shows.editorView(s).current(), "paused where it was");
    }

    /** Manual by default: NEXT / PREVIOUS move Preview only; TAKE puts Preview's image on Program. */
    @Test
    void nextChangesPreviewAndTakeChangesProgram() {
        Slideshows shows = shows();
        Scene scene = announcements(1L);
        Source s = scene.getSources().getFirst();
        assertEquals(1, shows.missing(s.getConfig()), "one image is missing");
        shows.syncPreview(scene);
        shows.syncProgram(scene.copy());
        assertEquals("one.png", shows.view(s, true).current());
        assertEquals("one.png", shows.view(s, false).current());

        now.addAndGet(60 * SECOND);
        shows.tick();
        assertEquals("one.png", shows.view(s, true).current(), "Auto Play is off by default: nothing moves");
        assertFalse(shows.takePending());

        shows.control(s, Slideshows.Control.NEXT);
        assertEquals("two.png", shows.view(s, true).current(), "NEXT: Preview 2");
        assertEquals("one.png", shows.view(s, false).current(), "Program still 1");
        assertTrue(shows.takePending());
        shows.control(s, Slideshows.Control.NEXT);
        assertEquals("four.png", shows.view(s, true).current(), "the missing image is skipped");
        assertEquals("one.png", shows.view(s, false).current());
        var st = shows.status(s);
        assertEquals(3, st.previewPosition());
        assertEquals(1, st.programPosition());

        shows.take();
        assertEquals("four.png", shows.view(s, false).current(), "TAKE: Program shows Preview's image");
        assertEquals("four.png", shows.view(s, true).current());
        assertFalse(shows.takePending());

        shows.control(s, Slideshows.Control.PREVIOUS);
        assertEquals("two.png", shows.view(s, true).current());
        assertEquals("four.png", shows.view(s, false).current(), "PREVIOUS never touches Program either");

        // the same scene synced again (a lyrics TAKE, an edit, a save that gives every source a new id)
        // keeps Program's image
        Scene resaved = scene.copy();
        resaved.getSources().getFirst().setId(99L); // the repository reassigns source ids on save
        shows.syncProgram(resaved);
        assertEquals("four.png", shows.view(s, false).current());
    }

    /** PREV / NEXT under the monitors: Preview's only move Preview; Program's move Program (and Preview with it). */
    @Test
    void monitorButtonsStepPreviewOrProgram() {
        Slideshows shows = shows();
        Scene scene = announcements(4L);
        Source s = scene.getSources().getFirst();
        assertNull(shows.summary(false), "no slideshow: no buttons");
        shows.syncPreview(scene);
        shows.syncProgram(scene.copy());
        assertEquals("IMAGE 1 / 4", shows.summary(false), "4 images (the missing one is not counted)");
        assertEquals("IMAGE 1 / 4", shows.summary(true));

        shows.controlPreview(Slideshows.Control.NEXT);
        assertEquals("IMAGE 2 / 4", shows.summary(false));
        assertEquals("IMAGE 1 / 4", shows.summary(true), "Preview NEXT leaves Program");

        shows.controlProgram(Slideshows.Control.NEXT);
        shows.controlProgram(Slideshows.Control.NEXT);
        assertEquals("four.png", shows.view(s, false).current(), "Program NEXT steps the live image");
        assertEquals("four.png", shows.view(s, true).current(), "and Preview follows, so TAKE does not undo it");
        assertFalse(shows.takePending());
        shows.controlProgram(Slideshows.Control.PREVIOUS);
        assertEquals("IMAGE 2 / 4", shows.summary(true));
        assertEquals("IMAGE 2 / 4", shows.summary(false));
    }

    @Test
    void autoPlayAdvancesPreviewOnlyAndSceneTakeShowsPreviewsImage() {
        Slideshows shows = shows();
        Scene scene = announcements(2L);
        Source s = scene.getSources().getFirst();
        s.getConfig().autoplay = Boolean.TRUE;
        s.getConfig().loop = Boolean.FALSE;
        shows.syncPreview(scene);
        now.addAndGet(5 * SECOND);
        shows.tick();
        assertEquals("two.png", shows.view(s, true).current(), "Auto Play moves Preview");
        // a scene TAKE: Program shows the image Preview shows
        shows.syncProgram(scene.copy());
        assertEquals("two.png", shows.view(s, false).current());
        now.addAndGet(5 * SECOND);
        shows.tick();
        assertEquals("four.png", shows.view(s, true).current());
        assertEquals("two.png", shows.view(s, false).current(), "Program never advances by itself");
        now.addAndGet(5 * SECOND);
        shows.tick();
        now.addAndGet(5 * SECOND);
        shows.tick();
        assertEquals("five.png", shows.view(s, true).current(), "no loop: stays on the last image");
        assertFalse(shows.status(s).playing());

        // "Program starts from the first image" for a scene that arrives on Program
        Scene other = announcements(3L);
        other.getSources().getFirst().getConfig().restartOnProgram = Boolean.TRUE;
        other.getSources().getFirst().getConfig().uid = "another-slideshow";
        shows.syncProgram(other);
        assertEquals("one.png", shows.view(other.getSources().getFirst(), false).current());
    }

    @Test
    void onlyTheSlideshowLayerChangesAndItFadesOnItsOwn() throws Exception {
        write("one.png", Color.RED);
        write("two.png", Color.BLUE);
        ImageCache cache = new ImageCache(dir);
        cache.getBlocking("one.png");
        cache.getBlocking("two.png");
        Compositor c = new Compositor(new SlideRenderer(cache));
        library.clear();
        library.put(1L, "one.png");
        library.put(2L, "two.png");
        Slideshows shows = shows();
        Compositor.installSlideshows(shows);

        Scene scene = new Scene(3L, "Announcements + Logo");
        Source show = new Source("Image Slideshow", SourceType.SLIDESHOW);
        show.setId(12L);
        show.getConfig().imageIds = List.of(1L, 2L);
        show.getConfig().fit = FitMode.STRETCH;
        show.getConfig().w = 0.5; // left half
        show.getConfig().slideTransition = "CROSSFADE";
        Source logo = new Source("Logo bar", SourceType.COLOR); // stands in for any other layer
        logo.getConfig().color = "#00FF00";
        logo.getConfig().x = 0.5;
        logo.getConfig().w = 0.5;
        scene.getSources().add(show);
        scene.getSources().add(logo);
        FrameSpec frame = new FrameSpec(null, true, true, false, scene);
        shows.syncPreview(scene);
        shows.syncProgram(scene);

        assertEquals(0xFF0000, c.render(frame, 192, 108, false).getRGB(40, 50) & 0xFFFFFF);
        shows.control(show, Slideshows.Control.NEXT); // Preview only
        assertEquals(0xFF0000, c.render(frame, 192, 108, false).getRGB(40, 50) & 0xFFFFFF, "Program waits for TAKE");
        shows.take();
        now.addAndGet(250_000_000L); // half way through the 500 ms crossfade
        BufferedImage mid = c.render(frame, 192, 108, false);
        Color px = new Color(mid.getRGB(40, 50));
        assertTrue(px.getRed() > 60 && px.getBlue() > 60, "crossfading red → blue: " + px);
        assertEquals(0x00FF00, mid.getRGB(150, 50) & 0xFFFFFF, "the other layer is untouched");
        now.addAndGet(SECOND);
        shows.tick();
        BufferedImage done = c.render(frame, 192, 108, false);
        assertEquals(0x0000FF, done.getRGB(40, 50) & 0xFFFFFF);
        assertEquals(0x00FF00, done.getRGB(150, 50) & 0xFFFFFF);
        assertNull(shows.view(show, false).previous(), "the transition is over");
        assertNotNull(shows.status(show));
    }

    private void write(String name, Color color) throws Exception {
        BufferedImage img = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 64, 36);
        g.dispose();
        ImageIO.write(img, "png", dir.resolve(name).toFile());
    }
}
