package com.jamc.worship.output;

import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An output window on a 16:10 screen (1440x900): Fill stretches the picture to the edges, Fit keeps black bars. */
class OutputFillScreenTest {

    private static final SlideStyle RED_BG = new SlideStyle(BackgroundType.COLOR, "#FF0000", "#FF0000", 0, "Dialog", 200,
            true, false, "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);

    @BeforeAll
    static void renderer() {
        Rendering.install(new SlideRenderer(new ImageCache(null)));
    }

    private static BufferedImage paint(boolean fill) {
        ProgramOutput program = new ProgramOutput();
        program.setTransition(Transition.CUT, 0);
        program.take(LiveContent.snapshot(new Slide("Verse 1", "", null), RED_BG, "Song"));
        OutputCanvas canvas = new OutputCanvas(program);
        canvas.setFillScreen(() -> fill);
        canvas.setSize(1440, 900);
        BufferedImage img = new BufferedImage(1440, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        canvas.paint(g);
        g.dispose();
        return img;
    }

    @Test
    void fillCoversTheWholeScreen() {
        BufferedImage img = paint(true);
        assertEquals(0xFF0000, img.getRGB(720, 2) & 0xFFFFFF, "top edge");
        assertEquals(0xFF0000, img.getRGB(720, 897) & 0xFFFFFF, "bottom edge");
    }

    @Test
    void fitKeepsBlackBars() {
        BufferedImage img = paint(false);
        assertEquals(0x000000, img.getRGB(720, 2) & 0xFFFFFF, "bar above");
        assertEquals(0xFF0000, img.getRGB(720, 450) & 0xFFFFFF, "picture");
    }

    @Test
    void boundsFollowTheSetting() {
        OutputCanvas canvas = new OutputCanvas(new ProgramOutput());
        canvas.setSize(1440, 900);
        assertEquals(new Rectangle(0, 45, 1440, 810), canvas.screenBounds());
        canvas.setFillScreen(() -> true);
        assertEquals(new Rectangle(0, 0, 1440, 900), canvas.screenBounds());
    }
}
