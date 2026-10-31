package com.jamc.worship.service;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.Song;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlideGeneratorTest {

    @Test
    void oneSlidePerSectionAndBlankLinesSplit() {
        Song song = new Song();
        song.setSections(LyricsParser.parse("""
                Verse 1
                a
                b

                Chorus
                c

                d
                e

                Bridge
                """));
        List<Slide> slides = SlideGenerator.fromSong(song);
        assertEquals(List.of("Verse 1", "Chorus (1/2)", "Chorus (2/2)"), slides.stream().map(Slide::getLabel).toList());
        assertEquals("a\nb", slides.get(0).getText());
        assertEquals("d\ne", slides.get(2).getText());
    }

    @Test
    void emptyAndNullSongsProduceNoSlides() {
        assertTrue(SlideGenerator.fromSong(null).isEmpty());
        assertTrue(SlideGenerator.fromSong(new Song()).isEmpty());
    }

    @Test
    void splitIgnoresWhitespaceOnlyLinesAndTrims() {
        assertEquals(List.of("x", "y"), SlideGenerator.splitOnBlankLines("  x  \n   \n\ty\n\n"));
    }
}
