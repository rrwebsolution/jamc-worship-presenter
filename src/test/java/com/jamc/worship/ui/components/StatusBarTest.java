package com.jamc.worship.ui.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusBarTest {

    @Test
    void longBackgroundNamesAreShortenedAndKeepTheirState() {
        assertEquals("BG: Loop.mp4", StatusBar.shorten("BG: Loop.mp4", 26));
        assertEquals("BG: Very Long Worship Loo… (paused)",
                StatusBar.shorten("BG: Very Long Worship Loop Background 4K Ultra HD.mp4 (paused)", 26));
        assertEquals(26, StatusBar.shorten("BG: " + "x".repeat(80), 26).length());
    }
}
