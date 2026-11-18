package com.jamc.worship.output;

import com.jamc.worship.model.LowerThird;
import com.jamc.worship.model.LowerThirdLookup;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A scene can hold several different saved lower thirds at once (user 2026-11-17), each on its own. */
class SceneLowerThirdsTest {

    @AfterEach
    void forget() {
        LowerThirdLookup.set(null);
    }

    private static LowerThird lt(long id, String title, LowerThird.Position where) {
        LowerThird l = new LowerThird();
        l.setId(id);
        l.setTitle(title);
        l.setSubtitle("Role");
        l.setPosition(where);
        return l;
    }

    private static Source source(Long id) {
        Source s = new Source("Lower Third", SourceType.LOWER_THIRD);
        s.getConfig().lowerThirdId = id;
        return s;
    }

    /** Pixels that are not black in the left or right half of the bottom of the frame. */
    private static int[] litBottomHalves(BufferedImage img) {
        int left = 0;
        int right = 0;
        for (int y = img.getHeight() * 2 / 3; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) {
                    if (x < img.getWidth() / 2) left++; else right++;
                }
            }
        }
        return new int[]{left, right};
    }

    private static BufferedImage render(Source... sources) {
        Scene scene = new Scene(1L, "Two names");
        scene.setSources(List.of(sources));
        return new Compositor(new SlideRenderer(new ImageCache(null)))
                .render(new FrameSpec(null, true, true, false, scene), 640, 360, false);
    }

    @Test
    void twoChosenLowerThirdsShowTogether() {
        LowerThirdLookup.set(List.of(lt(1, "Ptr. Carlo", LowerThird.Position.BOTTOM_LEFT),
                lt(2, "Worship Leader", LowerThird.Position.BOTTOM_RIGHT)));
        int[] lit = litBottomHalves(render(source(1L), source(2L)));
        assertTrue(lit[0] > 200, "the left name is drawn: " + lit[0]);
        assertTrue(lit[1] > 200, "the right name is drawn: " + lit[1]);
    }

    @Test
    void aLiveSourceWithNothingShownDrawsNothing() {
        LowerThirdLookup.set(List.of(lt(1, "Ptr. Carlo", LowerThird.Position.BOTTOM_LEFT)));
        int[] lit = litBottomHalves(render(source(null)));
        assertEquals(0, lit[0] + lit[1]);
    }

    @Test
    void hidingOneShownLowerThirdKeepsTheOthers() {
        ProgramOutput program = new ProgramOutput();
        LowerThird a = lt(1, "Ptr. Carlo", LowerThird.Position.BOTTOM_LEFT);
        LowerThird b = lt(2, "Worship Leader", LowerThird.Position.BOTTOM_RIGHT);
        a.setAnimation(LowerThird.Animation.NONE);
        b.setAnimation(LowerThird.Animation.NONE);
        program.showLowerThird(a);
        program.showLowerThird(b);
        assertTrue(program.isLowerThirdOn(1L) && program.isLowerThirdOn(2L), "both on together");
        program.hideLowerThird(1L);
        assertTrue(!program.isLowerThirdOn(1L), "the hidden one is off");
        assertTrue(program.isLowerThirdOn(2L), "the other stays on");
        program.hideLowerThird();
        assertTrue(!program.isLowerThirdOn(), "the emergency HIDE hides all");
    }

    @Test
    void aSceneLayerLowerThirdHidesAloneAndComesBackWithShow() {
        LowerThird a = lt(1, "Ptr. Carlo", LowerThird.Position.BOTTOM_LEFT);
        LowerThird b = lt(2, "Worship Leader", LowerThird.Position.BOTTOM_RIGHT);
        a.setAnimation(LowerThird.Animation.NONE);
        LowerThirdLookup.set(List.of(a, b));
        ProgramOutput program = new ProgramOutput();
        assertEquals(1f, program.fixedLowerThirdProgress(1L), "on by itself while the scene is up");
        program.hideLowerThird(1L);
        assertEquals(0f, program.fixedLowerThirdProgress(1L));
        assertEquals(1f, program.fixedLowerThirdProgress(2L), "the other layer stays");
        program.showLowerThird(a);
        assertEquals(1f, program.fixedLowerThirdProgress(1L), "SHOW brings it back");
    }

    /** How many columns of the bottom band have anything drawn (the lower third's width on a 1920 frame). */
    private static int drawnWidth(LowerThird l) {
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        com.jamc.worship.render.LowerThirdRenderer.paint(g, l, 1f, null);
        g.dispose();
        int cols = 0;
        for (int x = 0; x < 1920; x++) {
            for (int y = 700; y < 1080; y++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) {
                    cols++;
                    break;
                }
            }
        }
        return cols;
    }

    @Test
    void theWidthCanBeSetForBothDesigns() {
        for (LowerThird.Style style : LowerThird.Style.values()) {
            LowerThird l = lt(1, "Ptr. Carlo", LowerThird.Position.BOTTOM_LEFT);
            l.setStyle(style);
            l.setAnimation(LowerThird.Animation.NONE);
            int auto = drawnWidth(l);
            l.setWidth(1200);
            int wide = drawnWidth(l);
            assertTrue(wide > auto + 300, style + ": wider when set (" + auto + " -> " + wide + ")");
            l.setTitle("A very long name that would never fit in a narrow lower third box at all");
            l.setWidth(500);
            assertTrue(drawnWidth(l) < 620, style + ": a set width holds even for long words");
        }
        LowerThird copy = lt(1, "x", LowerThird.Position.BOTTOM_LEFT);
        copy.setWidth(800);
        assertEquals(800, copy.copy().getWidth());
    }

    /** The middle column of the name's letters (title colour pixels) on a 1920 frame. */
    private static int nameCenterX(LowerThird l) {
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        com.jamc.worship.render.LowerThirdRenderer.paint(g, l, 1f, null);
        g.dispose();
        int target = java.awt.Color.decode(l.getTitleColor()).getRGB() & 0xFFFFFF;
        long sum = 0;
        int n = 0;
        for (int x = 0; x < 1920; x++) {
            for (int y = 600; y < 1080; y++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == target) {
                    sum += x;
                    n++;
                }
            }
        }
        return n == 0 ? -1 : (int) (sum / n);
    }

    @Test
    void theNameCanBeAlignedLeftCenterOrRight() {
        for (LowerThird.Style style : LowerThird.Style.values()) {
            LowerThird l = lt(1, "Ptr. Carlo", LowerThird.Position.BOTTOM_LEFT);
            l.setStyle(style);
            l.setAnimation(LowerThird.Animation.NONE);
            l.setTitleColor("#FF00FF");
            l.setBackgroundColor("#202020");
            l.setWidth(1200);
            l.setTextAlign(LowerThird.TextAlign.LEFT);
            int left = nameCenterX(l);
            l.setTextAlign(LowerThird.TextAlign.CENTER);
            int center = nameCenterX(l);
            l.setTextAlign(LowerThird.TextAlign.RIGHT);
            int right = nameCenterX(l);
            assertTrue(left > 0 && left + 200 < center && center + 200 < right,
                    style + ": left " + left + " < center " + center + " < right " + right);
        }
    }

    @Test
    void theChoiceIsCopiedForUndo() {
        Source s = source(7L);
        assertEquals(7L, s.getConfig().copy().lowerThirdId);
    }
}
