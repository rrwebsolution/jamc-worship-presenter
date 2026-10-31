package com.jamc.worship.output;

import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.PresentationLibraryView;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A library presentation placed in a scene: referenced by id, shown in its own box, never copied. */
class PresentationSourceTest {

    @TempDir
    Path dir;

    private final ImageCache images = new ImageCache(null);
    private final Compositor compositor = new Compositor(new SlideRenderer(images));

    @AfterEach
    void uninstall() {
        Rendering.installPresentations(null);
    }

    private String picture(String name, Color c) throws Exception {
        BufferedImage img = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 160, 90);
        g.dispose();
        Path p = dir.resolve(name);
        ImageIO.write(img, "png", p.toFile());
        images.getBlocking(p.toString()); // decoded now, so the frame below is complete
        return p.toString();
    }

    /** A library slide as MainFrame builds it: full-screen picture, tagged with its presentation. */
    private static LiveContent slide(String image, long presentationId, int index) {
        StyleOverrides o = new StyleOverrides();
        o.backgroundType = BackgroundType.IMAGE;
        o.backgroundValue = image;
        o.overlayOpacity = 0.0;
        Slide s = new Slide("Slide " + (index + 1), "", null);
        s.setOverrides(o);
        s.setCoversBackground(true);
        s.setPresentation(presentationId, index);
        return LiveContent.snapshot(s, SlideStyle.fallback(), "Sermon");
    }

    private static Scene scene(Source presentation) {
        Scene s = new Scene(1L, "Sermon");
        if (presentation != null) {
            s.getSources().add(presentation);
        }
        s.getSources().add(new Source("Background", SourceType.BACKGROUND));
        return s;
    }

    private static Source box(SourceConfig.PresentationMode mode, Long id) {
        Source p = new Source("Presentation", SourceType.PRESENTATION);
        SourceConfig c = p.getConfig();
        c.x = 0.5;
        c.y = 0.5;
        c.w = 0.5;
        c.h = 0.5;
        c.fit = FitMode.FILL;
        c.presentationMode = mode;
        c.presentationId = id;
        c.slideIndex = 0;
        return p;
    }

    private Color at(FrameSpec spec, int x, int y) {
        return new Color(compositor.render(spec, 160, 90, false).getRGB(x, y));
    }

    @Test
    void aSlideSentToPreviewShowsInsideTheScenesPresentationBox() throws Exception {
        String red = picture("red.png", Color.RED);
        LiveContent content = slide(red, 7L, 4);
        Scene sermon = scene(box(SourceConfig.PresentationMode.DYNAMIC, null));
        FrameSpec spec = new FrameSpec(content, true, true, false, sermon);

        assertSame(sermon.getSources().getFirst(), Compositor.presentationSlot(sermon, content));
        assertEquals(Color.RED, at(spec, 120, 70), "the slide fills the Presentation box");
        assertNotEquals(Color.RED, at(spec, 20, 20), "outside the box the slide no longer covers the frame");

        // a scene without a Presentation source does not show presentation pages (only its Presentation source does)
        FrameSpec plain = new FrameSpec(content, true, true, false, scene(null));
        assertNull(Compositor.presentationSlot(scene(null), content));
        assertEquals(Color.BLACK, at(plain, 20, 20));
        assertEquals(Color.BLACK, at(plain, 120, 70));
    }

    @Test
    void aFixedSourceShowsItsOwnLibraryPresentationAndSurvivesItsRemoval() throws Exception {
        String green = picture("green.png", Color.GREEN);
        String red = picture("red2.png", Color.RED);
        boolean[] deleted = {false};
        Rendering.installPresentations(new PresentationLibraryView() {
            @Override
            public String slideImage(long id, int index) {
                return !deleted[0] && id == 9 && index == 0 ? green : null;
            }

            @Override
            public int slideCount(long id) {
                return !deleted[0] && id == 9 ? 1 : 0;
            }

            @Override
            public String name(long id) {
                return !deleted[0] && id == 9 ? "Anniversary 2026" : null;
            }
        });
        Scene anniversary = scene(box(SourceConfig.PresentationMode.FIXED, 9L));
        LiveContent otherDeck = slide(red, 7L, 0);
        assertNull(Compositor.presentationSlot(anniversary, otherDeck), "a fixed source ignores other presentations");

        FrameSpec nothingSent = new FrameSpec(null, true, true, false, anniversary);
        assertEquals(Color.GREEN, at(nothingSent, 120, 70), "its own slide, looked up by library id");

        // the presentation is removed from the library: no crash, the rest of the scene still renders
        deleted[0] = true;
        assertNotNull(compositor.render(nothingSent, 160, 90, false));
        assertNotEquals(Color.GREEN, at(nothingSent, 120, 70));
        // and the editor shows a placeholder for it instead of nothing
        Compositor editor = new Compositor(new SlideRenderer(images));
        editor.setEditorMode(true);
        Color placeholder = new Color(editor.render(nothingSent, 160, 90, false).getRGB(100, 50));
        assertNotEquals(Color.BLACK, placeholder);
    }
}
