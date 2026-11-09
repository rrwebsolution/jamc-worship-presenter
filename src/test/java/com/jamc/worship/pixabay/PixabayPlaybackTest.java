package com.jamc.worship.pixabay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PixabayPlaybackTest {

    private static PixabayVideo.Rendition r(String name, int w, int h) {
        return new PixabayVideo.Rendition("https://cdn.pixabay.com/video/x_" + name + ".mp4", w, h, 1, null);
    }

    @Test
    void aTwoPointSevenKUploadPlaysItsAbout1080pVersionNotThe1440pMedium() {
        // video 303686: medium is 2730x1440 at 60 fps and made the background stutter
        PixabayVideo v = new PixabayVideo(303686, null, "space", 10, "u", 1,
                r("large", 4096, 2160), r("medium", 2730, 1440), r("small", 2048, 1080), r("tiny", 1364, 720));
        assertEquals("2048×1080", v.resolutionText());
        assertEquals("https://cdn.pixabay.com/video/x_small.mp4", v.playback().url());
    }

    @Test
    void aFullHdUploadStillPlaysItsMedium() {
        PixabayVideo v = new PixabayVideo(125, null, "clouds", 12, "u", 1,
                r("large", 1920, 1080), r("medium", 1920, 1080), r("small", 1280, 720), r("tiny", 960, 540));
        assertEquals("https://cdn.pixabay.com/video/x_large.mp4", v.playback().url());
    }
}
