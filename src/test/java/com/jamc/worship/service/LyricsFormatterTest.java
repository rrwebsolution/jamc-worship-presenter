package com.jamc.worship.service;

import com.jamc.worship.model.LyricsSync;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongSection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsFormatterTest {

    @Test
    void autoFormatNormalisesLabelsWithoutChangingWords() {
        String pasted = """
                [VERSE 1]
                Amazing grace   \s
                How sweet the sound



                (Chorus)
                My chains are gone
                Bridge: The earth shall soon dissolve like snow
                Verse Two
                Twas grace that taught
                """;
        String f = LyricsFormatter.autoFormat(pasted);
        List<SongSection> s = LyricsParser.parse(f);
        assertEquals(List.of("Verse 1", "Chorus", "Bridge", "Verse 2"), s.stream().map(SongSection::getLabel).toList());
        assertEquals("Amazing grace\nHow sweet the sound", s.get(0).getContent());
        assertEquals("The earth shall soon dissolve like snow", s.get(2).getContent());
        assertTrue(f.contains("Twas grace that taught"));
    }

    @Test
    void lyricsWithoutLabelsBecomeVerses() {
        List<SongSection> s = LyricsParser.parse(LyricsFormatter.autoFormat("Line one\nLine two\n\nLine three"));
        assertEquals(List.of("Verse 1", "Verse 2"), s.stream().map(SongSection::getLabel).toList());
    }

    @Test
    void splitIntoCardsAndEditCards() {
        String text = "Verse 1\nAmazing grace\nHow sweet the sound\nThat saved a wretch like me\n\nChorus\nMy chains are gone";
        String split = LyricsFormatter.splitIntoCards(text, 2);
        List<LyricsFormatter.Card> cards = LyricsFormatter.cards(split);
        assertEquals(3, cards.size());
        assertEquals("Amazing grace\nHow sweet the sound", cards.get(0).text());
        assertEquals("That saved a wretch like me", cards.get(1).text());
        assertEquals("Verse 1A", cards.get(0).name(2));

        String merged = LyricsFormatter.mergeWithNext(split, 0);
        assertEquals(2, LyricsFormatter.cards(merged).size());
        String reSplit = LyricsFormatter.split(merged, 0, 1);
        assertEquals("Amazing grace", LyricsFormatter.cards(reSplit).get(0).text());
        String moved = LyricsFormatter.move(split, 1, -1);
        assertEquals("That saved a wretch like me", LyricsFormatter.cards(moved).get(0).text());
        String edited = LyricsFormatter.replace(split, 2, "My chains are gone\nI've been set free");
        assertEquals("My chains are gone\nI've been set free", LyricsFormatter.cards(edited).get(2).text());
    }

    @Test
    void timestampsFollowTheCards() {
        Song song = new Song();
        song.setTitle("Amazing Grace");
        song.setSections(LyricsParser.parse("Verse 1\nAmazing grace\nHow sweet the sound\n\nThat saved a wretch\n\nChorus\nMy chains are gone"));
        TimedLyricsParser.Result timed = TimedLyricsParser.parse("a.lrc", """
                [00:15.20]Amazing grace
                [00:18.00]How sweet the sound
                [00:22.50]That saved a wretch
                [00:40.00]My chains are gone
                """);
        Map<String, String> cards = new LinkedHashMap<>();
        SlideGenerator.distinctCards(song).forEach(c -> cards.put(c.getCardKey(), c.getText()));
        Map<String, Long> times = LyricsFormatter.cardTimes(cards, timed.cues());
        assertEquals(15_200L, times.get("Verse 1/0"));
        assertEquals(22_500L, times.get("Verse 1/1"));
        assertEquals(40_000L, times.get("Chorus/0"));

        LyricsSync sync = new LyricsSync();
        sync.cards.putAll(times);
        assertEquals(LyricsSync.Status.SYNCED, LyricsSync.status(sync, SlideGenerator.cardKeys(song)));
        sync.cards.remove("Chorus/0");
        assertEquals(LyricsSync.Status.PARTIAL, LyricsSync.status(sync, SlideGenerator.cardKeys(song)));
        assertEquals(LyricsSync.Status.NOT_SYNCED, LyricsSync.status(null, SlideGenerator.cardKeys(song)));
    }

    @Test
    void olderSectionTimesCountAsFirstCards() {
        LyricsSync old = new LyricsSync();
        old.videoId = "M7lc1UVf-VE";
        old.sections.put("Chorus", 31_000L);
        assertEquals(31_000L, old.startOf("Chorus/0"));
        assertNull(old.startOf("Chorus/1"));
        assertEquals("M7lc1UVf-VE", old.youtubeVideoId());
    }

    @Test
    void slidesCarryTheirCardKeys() {
        Song song = new Song();
        song.setSections(LyricsParser.parse("Chorus\nA\n\nB"));
        song.setArrangement("Chorus|Chorus");
        assertEquals(List.of("Chorus/0", "Chorus/1", "Chorus/0", "Chorus/1"),
                SlideGenerator.fromSong(song).stream().map(com.jamc.worship.model.Slide::getCardKey).toList());
        assertEquals(List.of("Chorus/0", "Chorus/1"), SlideGenerator.cardKeys(song));
    }
}
