package com.jamc.worship.director;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/** The bundled model loads offline and runs on the CPU. */
class YoloxPersonDetectorTest {

    @Test
    void bundledModelLoadsAndFindsNobodyInAnEmptyRoom() throws Exception {
        assertNotNull(YoloxPersonDetector.class.getResource(YoloxPersonDetector.RESOURCE), "model must be bundled");
        try (YoloxPersonDetector d = YoloxPersonDetector.load(1)) {
            BufferedImage frame = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = frame.createGraphics();
            g.setColor(new Color(40, 40, 60));
            g.fillRect(0, 0, 1920, 1080);
            g.dispose();
            PersonDetector.Prepared p = d.prepare(frame, null);
            assertEquals(1920, p.frameWidth());
            assertTrue(d.detect(p).isEmpty());
            // the analysis copy is reused, not allocated per frame
            assertSame(p.image(), d.prepare(frame, p).image());
        }
    }
}
