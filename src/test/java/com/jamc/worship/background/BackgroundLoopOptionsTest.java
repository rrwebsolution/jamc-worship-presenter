package com.jamc.worship.background;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class BackgroundLoopOptionsTest {

    @Test
    void aLoopingBackgroundRepeatsInsideTheInputSoTheEndRunsStraightIntoTheStart() {
        assertArrayEquals(new String[]{":network-caching=3000", ":avcodec-hw=d3d11va", ":input-repeat=65535"},
                GlobalBackgroundManager.videoOptions(true, ":network-caching=3000", ":avcodec-hw=d3d11va"));
    }

    @Test
    void withoutLoopNothingIsAdded() {
        assertArrayEquals(new String[]{":avcodec-hw=d3d11va"}, GlobalBackgroundManager.videoOptions(false, ":avcodec-hw=d3d11va"));
    }
}
