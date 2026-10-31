package com.jamc.worship.output;

import com.jamc.worship.model.BackgroundType;
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

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Next lyrics in a scene with a video (here a solid layer standing in for it): only the lyrics fade;
 * the other layers stay at full opacity through the whole transition.
 */
class LayerTransitionTest {

    private static final SlideStyle ON_BLACK = new SlideStyle(BackgroundType.COLOR, "#000000", "#000000", 0, "Dialog", 200,
            true, false, "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);

    private static LiveContent words(String text) {
        return LiveContent.snapshot(new Slide("Verse", text, null), ON_BLACK, "Song");
    }

    /** Lyrics on top of a red layer on the left half (the "video"). */
    private static Scene videoAndLyrics(long id) {
        Scene s = new Scene(id, "Video + Lyrics");
        s.getSources().add(new Source("Lyrics", SourceType.LYRICS));
        Source video = new Source("Video", SourceType.COLOR);
        video.getConfig().color = "#FF0000";
        video.getConfig().w = 0.5;
        s.getSources().add(video);
        return s;
    }

    @Test
    void onlyTheLyricsTransitionTheVideoLayerStaysFull() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        Scene scene = videoAndLyrics(7L);
        FrameSpec from = new FrameSpec(words("AMAZING GRACE"), true, true, false, scene);
        FrameSpec to = new FrameSpec(words("THAT SAVED A WRETCH"), true, true, false, scene);
        assertTrue(Compositor.contentChangeOnly(from, to));

        // the middle of a FADE (old lyrics gone, new not yet in): a whole-frame fade would be black here
        for (float[] alphas : new float[][]{{0f, 0f}, {0.5f, 0f}, {0f, 0.5f}, {1f, 0f}, {0f, 1f}}) {
            BufferedImage img = new BufferedImage(192, 108, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            c.paintLayered(g, 192, 108, from, to, null, false, alphas[0], alphas[1]);
            g.dispose();
            assertEquals(0xFF0000, img.getRGB(4, 4) & 0xFFFFFF, "the video layer is never faded");
            assertEquals(0xFF0000, img.getRGB(4, 104) & 0xFFFFFF);
        }
    }

    @Test
    void blackOrAnotherSceneIsAWholeFrameTransition() {
        Scene scene = videoAndLyrics(7L);
        FrameSpec verse1 = new FrameSpec(words("A"), true, true, false, scene);
        assertTrue(Compositor.contentChangeOnly(verse1, new FrameSpec(words("B"), true, true, false, scene.copy())),
                "the same scene taken again (a copy) still keeps its layers");
        assertTrue(Compositor.contentChangeOnly(verse1, new FrameSpec(words("A"), false, true, false, scene)),
                "lyrics off: only the lyrics layer fades");
        assertFalse(Compositor.contentChangeOnly(verse1, new FrameSpec(words("A"), true, true, true, scene)));
        assertFalse(Compositor.contentChangeOnly(verse1, new FrameSpec(words("B"), true, true, false, videoAndLyrics(8L))));
    }
}
