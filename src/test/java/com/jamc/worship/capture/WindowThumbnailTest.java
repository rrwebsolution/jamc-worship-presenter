package com.jamc.worship.capture;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Select Window list pictures: small copies of each window, same shape. */
class WindowThumbnailTest {

    @Test
    void bigWindowsAreShrunkKeepingTheirShape() {
        BufferedImage full = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        BufferedImage small = WindowCapture.scaled(full, 184);
        assertEquals(184, small.getWidth());
        assertEquals(104, small.getHeight());
    }

    @Test
    void smallWindowsStayAsTheyAre() {
        BufferedImage tiny = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        assertSame(tiny, WindowCapture.scaled(tiny, 184));
    }

    @Test
    void noWindowNoPicture() {
        assertNull(WindowCapture.snapshot(null, 184));
        assertNull(WindowCapture.snapshot(new WindowCapture.WindowInfo("No window has this title 7f3a", "none.exe"), 184));
    }
}
