package com.jamc.worship.ui.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopBarTest {

    @Test
    void longBackgroundNamesAreShortenedAndKeepTheirState() {
        assertEquals("BG: Loop.mp4", TopBar.shorten("BG: Loop.mp4", 26));
        assertEquals("BG: Very Long Worship Loo… (paused)",
                TopBar.shorten("BG: Very Long Worship Loop Background 4K Ultra HD.mp4 (paused)", 26));
        assertEquals(26, TopBar.shorten("BG: " + "x".repeat(80), 26).length());
    }
}
