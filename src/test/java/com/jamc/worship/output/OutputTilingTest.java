package com.jamc.worship.output;

import org.junit.jupiter.api.Test;

import java.awt.Insets;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputTilingTest {

    @Test
    void twoWindowedOutputsSitSideBySideWithoutOverlapping() {
        Rectangle screen = new Rectangle(0, 0, 1920, 1040); // 1080p without the taskbar
        List<Rectangle> t = OutputService.tiles(screen, 2, new Insets(31, 8, 8, 8));
        assertEquals(2, t.size());
        assertFalse(t.get(0).intersects(t.get(1)), "they never cover each other");
        assertTrue(t.get(0).x < t.get(1).x, "left and right");
        assertEquals(t.get(0).width, t.get(1).width);
        assertTrue(screen.contains(t.get(0)) && screen.contains(t.get(1)), "both on the screen");
        double picture = (t.get(0).width - 16) / (double) (t.get(0).height - 39);
        assertEquals(16 / 9.0, picture, 0.02, "the picture inside stays 16:9");
    }

    @Test
    void windowsPlacedApartKeepTheirPlace() {
        assertFalse(OutputService.overlap(List.of(new Rectangle(0, 0, 900, 520), new Rectangle(1000, 0, 900, 520))));
        assertFalse(OutputService.overlap(List.of(new Rectangle(0, 0, 900, 520), new Rectangle(850, 0, 900, 520))),
                "a small touch is fine");
        assertTrue(OutputService.overlap(List.of(new Rectangle(500, 260, 960, 540), new Rectangle(500, 260, 960, 540))),
                "opened on top of each other: tiled");
    }

    @Test
    void rememberedPlacesSurviveASaveAndLoad() {
        var all = new java.util.LinkedHashMap<String, Rectangle>();
        all.put("window:PROGRAM", new Rectangle(-1900, 40, 960, 579)); // a screen left of the main one
        all.put("window:PREVIEW", new Rectangle(100, 80, 640, 400));
        assertEquals(all, OutputService.parseBounds(OutputService.formatBounds(all)));
        assertTrue(OutputService.parseBounds("window:PROGRAM=1,2,x,4;junk").isEmpty(), "damaged entries are skipped");
    }

    @Test
    void threeFitAcrossToo() {
        List<Rectangle> t = OutputService.tiles(new Rectangle(0, 0, 1920, 1040), 3, new Insets(0, 0, 0, 0));
        assertFalse(t.get(0).intersects(t.get(1)) || t.get(1).intersects(t.get(2)));
        assertTrue(t.get(2).x + t.get(2).width <= 1920);
    }
}
