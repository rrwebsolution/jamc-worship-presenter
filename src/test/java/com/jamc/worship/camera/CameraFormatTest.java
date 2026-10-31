package com.jamc.worship.camera;

import com.jamc.worship.camera.CameraManager.Format;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A camera opens at the best size it supports instead of DirectShow's blurry smallest default. */
class CameraFormatTest {

    private static final Format DEFAULT = new Format(null, 0);

    @Test
    void autoTriesFullHdThenHdThenTheDriverDefault() {
        assertEquals(List.of(new Format("1920x1080", 0), new Format("1280x720", 0), DEFAULT),
                CameraManager.candidates(null, null));
        assertEquals(List.of(new Format("1920x1080", 0), new Format("1280x720", 0), DEFAULT),
                CameraManager.candidates(new Format(null, 0), null));
    }

    @Test
    void whatWorkedLastTimeIsTriedFirst() {
        Format hd = new Format("1280x720", 0);
        assertEquals(List.of(hd, new Format("1920x1080", 0), DEFAULT), CameraManager.candidates(null, hd));
    }

    @Test
    void aSizeTheCameraLacksFallsBackToTheBestSizesNotTheSmallest() {
        Format uhd = new Format("3840x2160", 30);
        assertEquals(List.of(uhd, new Format("1920x1080", 30), new Format("1280x720", 30), new Format(null, 30), DEFAULT),
                CameraManager.candidates(uhd, null));
    }

    /** Iriun Webcam is asked for YUY2 (its default comes out blue); other cameras keep VLC's choice. */
    @Test
    void iriunGetsTrueColorsOtherCamerasAreUnchanged() {
        Format fullHd = new Format("1920x1080", 0);
        assertEquals("YUY2", CameraManager.chroma("Iriun Webcam", fullHd));
        assertEquals(null, CameraManager.chroma("Integrated Camera", fullHd));
        assertEquals(null, CameraManager.chroma("Iriun Webcam", DEFAULT), "the last try is the driver's own default");
    }
}
