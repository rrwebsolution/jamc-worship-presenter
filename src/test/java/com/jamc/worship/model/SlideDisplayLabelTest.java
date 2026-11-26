package com.jamc.worship.model;

import com.jamc.worship.service.LyricsParser;
import com.jamc.worship.service.SlideGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlideDisplayLabelTest {

    @Test
    void songCardsShowTheirSectionWithoutAPart() {
        Song s = new Song();
        s.setSections(LyricsParser.parse("Verse 1\na\n\nb\n\nChorus\nc\n\nd"));
        List<Slide> slides = SlideGenerator.fromSong(s);
        assertEquals(List.of("Verse 1 (1/2)", "Verse 1 (2/2)", "Chorus (1/2)", "Chorus (2/2)"),
                slides.stream().map(Slide::getLabel).toList(), "the stored label keeps the part (it groups the cards)");
        assertEquals(List.of("Verse 1", "Verse 1", "Chorus", "Chorus"),
                slides.stream().map(Slide::displayLabel).toList());
        assertEquals(List.of("Verse 1", "", "Chorus", ""), slides.stream().map(Slide::cardLabel).toList(),
                "card lists name the section once, on its first card");
    }

    @Test
    void aSectionOfOneCardAndARepeatedChorusKeepTheirName() {
        Song s = new Song();
        s.setSections(LyricsParser.parse("Verse 1\na\n\nChorus\nc"));
        s.setArrangement("Verse 1|Chorus|Chorus");
        assertEquals(List.of("Verse 1", "Chorus", "Chorus"),
                SlideGenerator.fromSong(s).stream().map(Slide::cardLabel).toList());
    }

    @Test
    void otherSlidesAreShownAsTheyAre() {
        assertEquals("John 3:16 (1/2)", new Slide("John 3:16 (1/2)", "", null).displayLabel());
        assertEquals(null, new Slide(null, "", null).displayLabel());
    }
}
