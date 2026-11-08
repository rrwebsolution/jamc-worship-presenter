package com.jamc.worship.output;

import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A lyrics box made bigger than the screen (past its edges) makes the words bigger too. */
class LyricsBoxSizeTest {

    private static final int W = 960;
    private static final int H = 540;

    /** Height in pixels of the words drawn (rows with bright pixels). */
    private static int textHeight(double x, double y, double w, double h) {
        Scene scene = new Scene(1L, "Lyrics");
        Source lyrics = new Source("Lyrics", SourceType.LYRICS);
        lyrics.getConfig().x = x;
        lyrics.getConfig().y = y;
        lyrics.getConfig().w = w;
        lyrics.getConfig().h = h;
        scene.getSources().add(lyrics);
        LiveContent content = LiveContent.snapshot(new Slide("Verse", "Amazing grace", null), SlideStyle.fallback(), "Song");
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        new Compositor(new SlideRenderer(new ImageCache(null))).paint(g, W, H, new FrameSpec(content, true, false, false, scene), null, false);
        g.dispose();
        int top = -1;
        int bottom = -1;
        for (int row = 0; row < H; row++) {
            for (int col = 0; col < W; col += 2) {
                int rgb = img.getRGB(col, row);
                if (((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) > 200 && (rgb & 0xFF) > 200) {
                    if (top < 0) {
                        top = row;
                    }
                    bottom = row;
                    break;
                }
            }
        }
        return top < 0 ? 0 : bottom - top + 1;
    }

    @Test
    void biggerThanTheScreenGivesBiggerWords() {
        int full = textHeight(0, 0, 1, 1);
        int bigger = textHeight(-0.25, -0.25, 1.5, 1.5);
        assertTrue(full > 0, "the words are drawn");
        assertTrue(bigger > full * 1.3, "a box 1.5x the screen makes the words about 1.5x taller: " + full + " → " + bigger);
    }

    /** The user's box (X -0.248, W 1.496): big words, but no word cut off at the screen's left or right edge. */
    @Test
    void aBoxPastTheEdgesNeverCutsTheWordsOff() {
        Scene scene = new Scene(1L, "Lyrics");
        Source lyrics = new Source("Lyrics", SourceType.LYRICS);
        lyrics.getConfig().x = -0.248;
        lyrics.getConfig().y = -0.340;
        lyrics.getConfig().w = 1.496;
        lyrics.getConfig().h = 1.680;
        scene.getSources().add(lyrics);
        LiveContent content = LiveContent.snapshot(new Slide("Verse", "Hello from Phase One, every line is a test of the words\nEvery line is a test", null),
                SlideStyle.fallback().withTextTransform(com.jamc.worship.model.TextTransform.UPPERCASE), "Song");
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        new Compositor(new SlideRenderer(new ImageCache(null))).paint(g, W, H, new FrameSpec(content, true, false, false, scene), null, false);
        g.dispose();
        for (int row = 0; row < H; row++) {
            for (int col : new int[]{0, 1, 2, W - 3, W - 2, W - 1}) {
                int rgb = img.getRGB(col, row);
                assertTrue(((rgb >> 16) & 0xFF) < 120, "no letter at the screen edge (x " + col + ", y " + row + ")");
            }
        }
    }

    @Test
    void theWholeScreenStillUsesTheThemeLayout() {
        assertEquals(textHeight(0, 0, 1, 1), textHeight(0.0, 0.0, 1.0, 1.0));
        assertTrue(textHeight(0.25, 0.25, 0.5, 0.5) < textHeight(0, 0, 1, 1), "a smaller box still gives smaller words");
    }
}
