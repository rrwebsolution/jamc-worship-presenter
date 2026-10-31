package com.jamc.worship.render;

import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlideRendererTest {

    @TempDir
    Path tempDir;

    private static SlideStyle style(String bg, TextAlign align, TextPosition pos, int size) {
        return new SlideStyle(BackgroundType.COLOR, bg, bg, 0, "Dialog", size, true, false, "#FFFFFF",
                align, pos, 1.1, false, false, "#000000", 4);
    }

    @Test
    void longTextShrinksToStayInsideTheSafeArea() {
        TextRenderer tr = new TextRenderer();
        String longText = "For God so loved the world, that he gave his only begotten Son, that whosoever believeth "
                + "in him should not perish, but have everlasting life. ".repeat(4);
        TextRenderer.TextBlock block = tr.layout(longText, "John 3:16", style("#000000", TextAlign.CENTER, TextPosition.MIDDLE, 120));
        Rectangle2D b = block.bounds();
        assertTrue(block.fontSize() < 120, "font was reduced to fit");
        assertTrue(b.getMinX() >= TextRenderer.MARGIN_X - 2 && b.getMaxX() <= TextRenderer.FRAME_WIDTH - TextRenderer.MARGIN_X + 2,
                "fits horizontally: " + b);
        assertTrue(b.getMinY() >= TextRenderer.MARGIN_Y - 10 && b.getMaxY() <= TextRenderer.FRAME_HEIGHT - TextRenderer.MARGIN_Y + 10,
                "fits vertically: " + b);
    }

    @Test
    void shortTextKeepsRequestedSizeAndHonoursPosition() {
        TextRenderer tr = new TextRenderer();
        var top = tr.layout("Hello", null, style("#000000", TextAlign.LEFT, TextPosition.TOP, 72));
        var bottom = tr.layout("Hello", null, style("#000000", TextAlign.RIGHT, TextPosition.BOTTOM, 72));
        assertEquals(72f, top.fontSize());
        assertTrue(top.bounds().getMinY() < TextRenderer.FRAME_HEIGHT / 3.0);
        assertTrue(bottom.bounds().getMaxY() > TextRenderer.FRAME_HEIGHT * 2 / 3.0);
        assertTrue(top.bounds().getMinX() < TextRenderer.FRAME_WIDTH / 3.0, "left aligned");
        assertTrue(bottom.bounds().getMaxX() > TextRenderer.FRAME_WIDTH * 2 / 3.0, "right aligned");
    }

    @Test
    void layoutIsCachedAndIndependentOfOutputSize() {
        TextRenderer tr = new TextRenderer();
        SlideStyle s = style("#000000", TextAlign.CENTER, TextPosition.MIDDLE, 72);
        assertTrue(tr.layout("Same", null, s) == tr.layout("Same", null, s));
    }

    @Test
    void rendersBackgroundColorAndWhiteText() {
        SlideRenderer r = new SlideRenderer(new ImageCache(tempDir));
        BufferedImage img = r.render(new Slide("x", "WWWWWWWW", null),
                style("#203040", TextAlign.CENTER, TextPosition.MIDDLE, 200), 640, 360);
        assertEquals(new Color(0x203040).getRGB(), img.getRGB(5, 5), "corner shows the background");
        boolean foundWhite = false;
        for (int x = 0; x < 640 && !foundWhite; x++) {
            foundWhite = img.getRGB(x, 180) == Color.WHITE.getRGB();
        }
        assertTrue(foundWhite, "text is drawn across the middle");
    }

    @Test
    void perSlideOverridesApply() {
        SlideRenderer r = new SlideRenderer(new ImageCache(tempDir));
        Slide slide = new Slide("x", "", null);
        var o = new com.jamc.worship.model.StyleOverrides();
        o.backgroundValue = "#FF0000";
        slide.setOverrides(o);
        BufferedImage img = r.render(slide, style("#000000", TextAlign.CENTER, TextPosition.MIDDLE, 72), 64, 36);
        assertEquals(Color.RED.getRGB(), img.getRGB(10, 10));
    }

    @Test
    void imageBackgroundCoversAndMissingImageFallsBackToColor() throws Exception {
        BufferedImage green = new BufferedImage(40, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = green.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, 40, 10);
        g.dispose();
        ImageIO.write(green, "png", tempDir.resolve("bg.png").toFile());

        ImageCache cache = new ImageCache(tempDir);
        assertTrue(cache.getBlocking("bg.png") != null, "relative paths resolve against the data directory");
        SlideRenderer r = new SlideRenderer(cache);
        SlideStyle withImage = new SlideStyle(BackgroundType.IMAGE, "bg.png", "#0000FF", 0, "Dialog", 72, true, false,
                "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);
        BufferedImage out = r.render(new Slide("x", "", null), withImage, 160, 90);
        assertEquals(Color.GREEN.getRGB(), out.getRGB(2, 2), "wide image covers the whole frame");
        assertEquals(Color.GREEN.getRGB(), out.getRGB(157, 87));

        SlideStyle missing = new SlideStyle(BackgroundType.IMAGE, "nope.png", "#0000FF", 0, "Dialog", 72, true, false,
                "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);
        assertTrue(cache.getBlocking("nope.png") == null);
        assertTrue(cache.isMissing("nope.png"));
        BufferedImage fallback = r.render(new Slide("x", "", null), missing, 32, 18);
        assertEquals(Color.BLUE.getRGB(), fallback.getRGB(1, 1), "missing image shows the fallback color");
    }

    @Test
    void overlayDarkensBackground() {
        SlideRenderer r = new SlideRenderer(new ImageCache(tempDir));
        SlideStyle dimmed = new SlideStyle(BackgroundType.COLOR, "#FFFFFF", "#FFFFFF", 0.5, "Dialog", 72, true, false,
                "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);
        int rgb = r.render(new Slide("x", "", null), dimmed, 20, 20).getRGB(1, 1);
        assertNotEquals(Color.WHITE.getRGB(), rgb);
        int red = (rgb >> 16) & 0xFF;
        assertTrue(red > 100 && red < 160, "about half brightness, got " + red);
    }
}
