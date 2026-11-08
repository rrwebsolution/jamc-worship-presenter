package com.jamc.worship.service;

import com.jamc.worship.model.Song;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lyrics editor numbers, like EasyWorship: one number per slide, on the line where the slide starts. */
class SlideNumbersTest {

    private static final String AMAZING_GRACE = """
            Verse 1
            Amazing grace! How sweet the sound
            That saved a wretch like me!

            Verse 5
            I once was lost, but now am found;
            Was blind, but now I see.

            Verse 2
            'Twas grace that taught my heart to fear,
            And grace my fears relieved;

            How precious did that grace appear
            The hour I first believed.
            """;

    @Test
    void oneNumberPerSlideOnItsFirstLine() {
        int[] n = LyricsParser.slideNumbers(AMAZING_GRACE);
        // Verse 1 = 1, Verse 5 = 2, Verse 2 = 3, the part after the empty line inside Verse 2 = 4
        assertEquals(1, n[0], "on the heading line");
        assertEquals(0, n[1]);
        assertEquals(0, n[2]);
        assertEquals(2, n[4]);
        assertEquals(3, n[8]);
        assertEquals(4, n[12], "Enter on an empty line inside a section: the next slide");
        assertEquals(4, Arrays.stream(n).max().orElse(0));
    }

    @Test
    void theNumbersMatchTheSlidesTheAppMakes() {
        for (String text : new String[]{AMAZING_GRACE, "One\nline\n\nTwo\n\n\nThree", "Chorus\n\nWords after a gap\n\nMore"}) {
            Song song = new Song();
            song.setSections(LyricsParser.parse(text));
            int slides = SlideGenerator.fromSong(song).size();
            assertEquals(slides, Arrays.stream(LyricsParser.slideNumbers(text)).max().orElse(0), text);
        }
    }

    @Test
    void aHeadingWithNoWordsGetsNoNumber() {
        // Verse 1 has no words: no number; the Chorus slide is number 1, shown on its heading line
        assertArrayEquals(new int[]{0, 1, 0, 0}, LyricsParser.slideNumbers("Verse 1\nChorus\nWords\n"));
    }
}
