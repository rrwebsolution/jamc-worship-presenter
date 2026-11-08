package com.jamc.worship.ui;

import com.jamc.worship.model.SectionType;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongSection;
import com.jamc.worship.model.Slide;
import com.jamc.worship.service.SlideGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Edits of a live item follow on Program: the live slide is found again in the rebuilt strip. */
class LiveEditFollowTest {

    private static Song song(String verse, String chorus) {
        Song s = new Song();
        s.setId(7L);
        s.setTitle("Test");
        s.setSections(List.of(new SongSection(SectionType.VERSE, "Verse 1", verse), new SongSection(SectionType.CHORUS, "Chorus", chorus)));
        return s;
    }

    @Test
    void editedLyricsFindTheLiveCardAndSeeTheChange() {
        List<Slide> before = SlideGenerator.fromSong(song("Old words", "Chorus line"));
        Slide live = before.get(1); // the chorus is live
        List<Slide> after = SlideGenerator.fromSong(song("Old words", "Chorus line, edited"));
        int at = MainFrame.liveMatch(after, live, 1);
        assertEquals(1, at);
        assertFalse(MainFrame.sameContent(after.get(at), live.copy()), "the edited words must reach Program");
        assertTrue(MainFrame.sameContent(before.get(1), live.copy()), "unchanged: no update");
    }

    @Test
    void anotherSongInTheStripLeavesProgramAlone() {
        Slide live = SlideGenerator.fromSong(song("A", "B")).get(0);
        Song other = song("A", "B");
        other.setId(8L);
        assertEquals(-1, MainFrame.liveMatch(SlideGenerator.fromSong(other), live, 0));
    }

    @Test
    void customSlideEditedInPlaceIsFound() {
        Slide s = new Slide("1", "Welcome", null);
        List<Slide> strip = List.of(new Slide("0", "x", null), s);
        Slide copy = s.copy();
        s.setText("Welcome everyone");
        assertEquals(1, MainFrame.liveMatch(strip, s, 1));
        assertFalse(MainFrame.sameContent(s, copy));
    }
}
