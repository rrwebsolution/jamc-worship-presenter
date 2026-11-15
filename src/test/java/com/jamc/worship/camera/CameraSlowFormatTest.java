package com.jamc.worship.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraSlowFormatTest {

    @Test
    void autoCameraTooSlowAt1080GoesTo720At30() {
        CameraManager.Format hd = new CameraManager.Format("1920x1080", 0);
        assertTrue(CameraManager.slowAt1080(true, hd, 15), "about 15 choppy pictures a second at 1080p");
        assertFalse(CameraManager.slowAt1080(true, hd, 29), "smooth: kept");
        assertFalse(CameraManager.slowAt1080(true, hd, 0), "no measurement yet");
        assertFalse(CameraManager.slowAt1080(false, hd, 15), "a size the operator chose is never changed");
        assertFalse(CameraManager.slowAt1080(true, new CameraManager.Format("1280x720", 30), 15), "already smaller");
        assertEquals(new CameraManager.Format("1280x720", 30), CameraManager.REDUCED.getFirst());
        assertEquals(new CameraManager.Format(null, 0), CameraManager.REDUCED.getLast(), "the driver default stays the last try");
    }
}
