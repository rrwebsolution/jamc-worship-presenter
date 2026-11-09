package com.jamc.worship.media;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoBufferSizeTest {

    @Test
    void picturesBiggerThan1080pAreScaledDownKeepingTheirShape() {
        assertEquals(new Dimension(1920, 1012), VideoPlayer.bufferSize(2736, 1442, false));
        assertEquals(new Dimension(1920, 1080), VideoPlayer.bufferSize(3840, 2160, false));
        assertEquals(new Dimension(1918, 1080), VideoPlayer.bufferSize(2048, 1153, false));
    }

    @Test
    void hdAndSmallerAndFullResolutionChecksAreUntouched() {
        assertEquals(new Dimension(1920, 1088), VideoPlayer.bufferSize(1920, 1088, false));
        assertEquals(new Dimension(1280, 720), VideoPlayer.bufferSize(1280, 720, false));
        assertEquals(new Dimension(2736, 1442), VideoPlayer.bufferSize(2736, 1442, true));
    }
}
