package com.jamc.worship.output;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.LyricsBox;
import com.jamc.worship.config.LyricsLayouts;
import com.jamc.worship.config.ProgramLyricsStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** My layouts: the operator's own lyrics box, saved, chosen by a scene, and drawn only inside that box. */
class CustomLyricsLayoutTest {

    @TempDir
    Path dir;

    @Test
    void aBoxSurvivesSavingAndDamagedOnesAreIgnored() {
        LyricsBox b = new LyricsBox(0.05f, 0.2f, 0.4f, 0.6f, LyricsBox.VAlign.MIDDLE, LyricsBox.Background.BOX);
        assertEquals(b, LyricsBox.parse(b.format()));
        assertNull(LyricsBox.parse("1,2,3"));
        assertNull(LyricsBox.parse("1,2,3,4,SIDEWAYS,BOX"));
        LyricsBox off = new LyricsBox(0.9f, 0.9f, 0.5f, 0.5f, null, null);
        assertTrue(off.x() + off.w() <= 1f && off.y() + off.h() <= 1f, "kept on the picture");
    }

    @Test
    void layoutsAreSavedFoundAndDeleted() throws Exception {
        AppConfig cfg = new AppConfig(new AppPaths(dir));
        LyricsLayouts.install(cfg);
        LyricsLayouts.Layout side = LyricsLayouts.create("Side box", LyricsBox.starter().at(0.04f, 0.2f, 0.4f, 0.6f));
        LyricsLayouts.save(cfg, side);
        AppConfig again = new AppConfig(cfg.paths());
        again.load();
        LyricsLayouts.install(again);
        assertEquals(1, LyricsLayouts.all().size());
        assertEquals("Side box", LyricsLayouts.find(side.key()).name());
        assertEquals(side.box(), LyricsLayouts.find(side.key()).box());
        LyricsLayouts.delete(again, side.id());
        assertNull(LyricsLayouts.find(side.key()));
        assertTrue(LyricsLayouts.all().isEmpty());
    }

    @Test
    void theStyleKeepsItsBoxOnlyForCustom() {
        ProgramLyricsStyle s = ProgramLyricsStyle.defaults().withBox(LyricsBox.starter());
        assertEquals(ProgramLyricsStyle.Mode.CUSTOM, s.mode());
        assertNotNull(s.box());
        assertNull(s.withMode(ProgramLyricsStyle.Mode.LOWER_THIRD).box());
        assertEquals(ProgramLyricsStyle.Mode.LOWER_THIRD, ProgramLyricsStyle.defaults().withBox(null).mode(),
                "a custom layout without a box is the usual lower third");
    }

    @Test
    void wordsAreDrawnInsideTheBoxOnly() {
        LyricsBox left = new LyricsBox(0.04f, 0.2f, 0.35f, 0.6f, LyricsBox.VAlign.MIDDLE, LyricsBox.Background.BOX);
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        LowerThirdLyrics.paint(g, 1920, 1080, "Worthy of ev'ry breath we could ever breathe\nWe live for You",
                ProgramLyricsStyle.defaults().withBox(left));
        g.dispose();
        int boxRight = Math.round((left.x() + left.w()) * 1920);
        int inside = 0;
        for (int y = 0; y < 1080; y += 4) {
            for (int x = 0; x < 1920; x += 4) {
                boolean painted = (img.getRGB(x, y) >>> 24) != 0;
                if (painted && x > boxRight + 2) {
                    throw new AssertionError("painted outside the box at " + x + "," + y);
                }
                if (painted) {
                    inside++;
                }
            }
        }
        assertTrue(inside > 0, "the box and words are drawn");
    }

    @Test
    void longLyricsGetSmallerToFitTheBox() {
        LyricsBox small = new LyricsBox(0.6f, 0.75f, 0.35f, 0.18f, LyricsBox.VAlign.BOTTOM, LyricsBox.Background.NONE);
        ProgramLyricsStyle s = ProgramLyricsStyle.defaults().withBox(small);
        var l = LowerThirdLyrics.layout("Worthy of ev'ry breath we could ever breathe, we live for You alone", s, 1920, 1080);
        assertFalse(l.lines().isEmpty());
        assertTrue(l.font().getSize2D() < 42f, "shrunk from 42 px to fit: " + l.font().getSize2D());
    }
}
