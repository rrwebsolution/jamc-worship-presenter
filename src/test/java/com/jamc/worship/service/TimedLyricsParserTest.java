package com.jamc.worship.service;

import com.jamc.worship.model.SongSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedLyricsParserTest {

    private static final String LRC = """
            [ti:Goodness of God]
            [ar:Bethel Music]
            [00:12.00]I love You, Lord
            [00:15.50]Oh Your mercy never fails me
            [00:19.20]All my days I've been held in Your hands
            [00:30.00]
            [00:31.00][01:31.00]All my life You have been faithful
            [00:35.00][01:35.00]All my life You have been so, so good
            [00:45.00]
            [00:46.00]I love Your voice
            [00:49.00]You have led me through the fire
            [01:20.00]
            """;

    @Test
    void lrcBecomesVersesAndOneChorusInSungOrder() {
        TimedLyricsParser.Result r = TimedLyricsParser.parse("goodness.lrc", LRC);
        assertEquals("Goodness of God", r.title());
        assertEquals("Bethel Music", r.artist());
        assertEquals(List.of("Verse 1", "Chorus", "Verse 2", "Chorus"), r.arrangement());
        assertEquals(12_000L, r.sync().get("Verse 1"));
        assertEquals(31_000L, r.sync().get("Chorus"));
        assertEquals(46_000L, r.sync().get("Verse 2"));

        // the text reads back through the normal lyrics parser into the same sections, chorus kept once
        List<SongSection> sections = LyricsParser.parse(r.text());
        assertEquals(List.of("Verse 1", "Chorus", "Verse 2"), sections.stream().map(SongSection::getLabel).toList());
        assertTrue(sections.get(1).getContent().startsWith("All my life You have been faithful"));
    }

    @Test
    void srtCaptionsWithTagsAndRepeats() {
        String srt = """
                1
                00:00:05,000 --> 00:00:08,000
                <i>Amazing grace</i>

                2
                00:00:08,100 --> 00:00:11,000
                How sweet the sound

                3
                00:00:20,000 --> 00:00:23,000
                I once was lost
                but now am found
                """;
        TimedLyricsParser.Result r = TimedLyricsParser.parse("song.srt", srt);
        assertEquals(List.of("Verse 1", "Verse 2"), r.arrangement());
        assertTrue(r.text().contains("Amazing grace\nHow sweet the sound"));
        assertFalse(r.text().contains("<i>"));
        assertEquals(20_000L, r.sync().get("Verse 2"));
    }

    @Test
    void webVttWithShortTimesAndSettings() {
        String vtt = """
                WEBVTT

                NOTE written by hand

                intro
                00:01.000 --> 00:04.000 align:center
                ♪ Way maker ♪

                00:04.200 --> 00:07.000
                Miracle worker
                """;
        assertEquals(TimedLyricsParser.Format.VTT, TimedLyricsParser.detect("captions.txt", vtt));
        TimedLyricsParser.Result r = TimedLyricsParser.parse("captions.vtt", vtt);
        assertEquals("Verse 1\nWay maker\nMiracle worker", r.text());
        assertEquals(1_000L, r.sync().get("Verse 1"));
    }
}
