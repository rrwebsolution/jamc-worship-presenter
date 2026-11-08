package com.jamc.worship.output;

import org.junit.jupiter.api.Test;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every Program transition draws the old (red) and new (blue) frame where it should. */
class TransitionFxTest {

    private static final int W = 160;
    private static final int H = 90;
    private static final int RED = 0xFF0000;
    private static final int BLUE = 0x0000FF;

    private static BufferedImage solid(int rgb) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, W, H);
        g.dispose();
        return img;
    }

    /** One frame of the transition, the way OutputCanvas draws whole frames (black behind). */
    static BufferedImage frame(Transition t, float p) {
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, H);
        for (boolean incoming : new boolean[]{false, true}) {
            Graphics2D side = (Graphics2D) g.create();
            float a = TransitionFx.prepare(t, side, W, H, p, incoming, true);
            if (a > 0) {
                side.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, a)));
                side.drawImage(solid(incoming ? BLUE : RED), 0, 0, null);
            }
            side.dispose();
        }
        g.dispose();
        return out;
    }

    private static int at(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    void everyTransitionEndsOnTheNewFrame() {
        for (Transition t : Transition.values()) {
            BufferedImage end = frame(t, 1f);
            for (int[] p : new int[][]{{2, 2}, {W / 2, H / 2}, {W - 3, H - 3}}) {
                assertEquals(BLUE, at(end, p[0], p[1]), t + " at the end");
            }
        }
    }

    @Test
    void wipesAndSlidesSplitTheFrame() {
        BufferedImage wipe = frame(Transition.WIPE_LEFT, 0.5f);
        assertEquals(RED, at(wipe, 10, H / 2), "the old frame stays on the left");
        assertEquals(BLUE, at(wipe, W - 10, H / 2), "the new frame comes in from the right");
        BufferedImage down = frame(Transition.WIPE_DOWN, 0.5f);
        assertEquals(BLUE, at(down, W / 2, 5));
        assertEquals(RED, at(down, W / 2, H - 5));
        BufferedImage slide = frame(Transition.SLIDE_LEFT, 0.5f);
        assertEquals(RED, at(slide, 10, H / 2), "the old frame is pushed out to the left");
        assertEquals(BLUE, at(slide, W - 10, H / 2));
        BufferedImage circle = frame(Transition.CIRCLE, 0.4f);
        assertEquals(BLUE, at(circle, W / 2, H / 2), "the circle opens from the middle");
        assertEquals(RED, at(circle, 1, 1), "the corners are still the old frame");
    }

    @Test
    void fadesStartOnTheOldFrame() {
        for (Transition t : EnumSet.of(Transition.FADE, Transition.CROSSFADE, Transition.WIPE_LEFT, Transition.SLIDE_UP,
                Transition.CIRCLE, Transition.ZOOM)) {
            assertEquals(RED, at(frame(t, 0f), W / 2, H / 2), t + " at the start");
        }
        assertEquals(0x000000, at(frame(Transition.FADE, 0.5f), W / 2, H / 2), "Fade goes through black");
    }

    @Test
    void shownListIsSavedAndReadBack() {
        Set<Transition> set = EnumSet.of(Transition.CUT, Transition.WIPE_LEFT, Transition.ZOOM);
        assertEquals(set, Transition.parseSet(Transition.format(set)));
        assertEquals(Transition.DEFAULT_SHOWN, Transition.parseSet(null), "nothing chosen yet: the classic three");
        assertEquals(Transition.DEFAULT_SHOWN, Transition.parseSet("NOT_A_TRANSITION"));
        assertTrue(Transition.parseSet("FADE, CIRCLE").contains(Transition.CIRCLE));
    }
}
