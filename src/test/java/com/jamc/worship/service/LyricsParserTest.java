package com.jamc.worship.service;

import com.jamc.worship.model.SectionType;
import com.jamc.worship.model.SongSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsParserTest {

    @Test
    void parsesStandardHeadings() {
        List<SongSection> sections = LyricsParser.parse("""
                Verse 1
                Amazing grace
                How sweet the sound

                Chorus
                My chains are gone

                Verse 2
                'Twas grace that taught
                """);
        assertEquals(3, sections.size());
        assertEquals(SectionType.VERSE, sections.get(0).getType());
        assertEquals("Verse 1", sections.get(0).getLabel());
        assertEquals("Amazing grace\nHow sweet the sound", sections.get(0).getContent());
        assertEquals(SectionType.CHORUS, sections.get(1).getType());
        assertEquals("Chorus", sections.get(1).getLabel());
        assertEquals("Verse 2", sections.get(2).getLabel());
    }

    @Test
    void acceptsBracketsColonsAndVariants() {
        List<SongSection> sections = LyricsParser.parse("""
                [Intro]
                Oh
                PRE-CHORUS:
                Rising
                [Bridge 2]
                Higher
                [Tag]
                Again
                [Final Chorus]
                Last
                """);
        assertEquals(List.of(SectionType.INTRO, SectionType.PRE_CHORUS, SectionType.BRIDGE,
                SectionType.CUSTOM, SectionType.CUSTOM), sections.stream().map(SongSection::getType).toList());
        assertEquals("Bridge 2", sections.get(2).getLabel());
        assertEquals("Tag", sections.get(3).getLabel());
        assertEquals("Final Chorus", sections.get(4).getLabel());
    }

    @Test
    void autoNumbersUnnumberedVerses() {
        List<SongSection> sections = LyricsParser.parse("Verse\na\n\nVerse\nb\n\nChorus\nc");
        assertEquals("Verse 1", sections.get(0).getLabel());
        assertEquals("Verse 2", sections.get(1).getLabel());
    }

    @Test
    void lyricsWithoutHeadingsSplitIntoVersesByBlankLine() {
        List<SongSection> sections = LyricsParser.parse("line a\nline b\n\n\nline c\n");
        assertEquals(2, sections.size());
        assertEquals("Verse 1", sections.get(0).getLabel());
        assertEquals("line a\nline b", sections.get(0).getContent());
        assertEquals("line c", sections.get(1).getContent());
    }

    @Test
    void keepsSingleBlankLineInsideSectionAsSlideBreak() {
        List<SongSection> sections = LyricsParser.parse("Chorus\none\n\n\n\ntwo\n");
        assertEquals(1, sections.size());
        assertEquals("one\n\ntwo", sections.get(0).getContent());
    }

    @Test
    void ordinaryLyricLinesAreNotHeadings() {
        assertTrue(LyricsParser.parseHeading("Amazing grace how sweet the sound").isEmpty());
        assertTrue(LyricsParser.parseHeading("C").isEmpty(), "bare single letter is ambiguous");
        assertTrue(LyricsParser.parseHeading("V1").isPresent());
        assertTrue(LyricsParser.parseHeading("Amen").isEmpty());
    }

    @Test
    void handlesWindowsLineEndingsAndEmptyInput() {
        assertEquals(0, LyricsParser.parse("").size());
        assertEquals(0, LyricsParser.parse(null).size());
        List<SongSection> sections = LyricsParser.parse("Verse 1\r\nHello\r\n\r\nChorus\r\nWorld");
        assertEquals(2, sections.size());
        assertEquals("Hello", sections.get(0).getContent());
    }

    @Test
    void formatRoundTrips() {
        List<SongSection> original = List.of(
                new SongSection(SectionType.VERSE, "Verse 1", "a\nb"),
                new SongSection(SectionType.PRE_CHORUS, "Pre-Chorus", "c"),
                new SongSection(SectionType.CHORUS, "Chorus", "d\n\ne"),
                new SongSection(SectionType.CUSTOM, "Tag", "f"),
                new SongSection(SectionType.CUSTOM, "Final Chorus", "g"),
                new SongSection(SectionType.ENDING, "Ending", "Amen."));
        List<SongSection> reparsed = LyricsParser.parse(LyricsParser.format(original));
        assertEquals(original.size(), reparsed.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).getType(), reparsed.get(i).getType(), "type of #" + i);
            assertEquals(original.get(i).getLabel(), reparsed.get(i).getLabel(), "label of #" + i);
            assertEquals(original.get(i).getContent(), reparsed.get(i).getContent(), "content of #" + i);
        }
    }
}
