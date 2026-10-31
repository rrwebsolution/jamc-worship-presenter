package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.Song;
import com.jamc.worship.repository.SongRepository;
import com.jamc.worship.ui.components.ActionOrderPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Song arrangements (section order with repeats) and how the Action / Order list groups slides. */
class ActionOrderTest {

    @TempDir
    Path tempDir;

    private static Song song() {
        Song s = new Song();
        s.setTitle("Placeholder Song");
        s.setSections(LyricsParser.parse("""
                Verse 1
                line a

                line b

                Chorus
                chorus line

                Verse 2
                line c

                Bridge
                bridge line
                """));
        return s;
    }

    private static List<String> labels(List<Slide> slides) {
        return slides.stream().map(Slide::getLabel).toList();
    }

    @Test
    void writtenOrderByDefaultAndArrangementRepeatsSections() {
        Song s = song();
        assertEquals(List.of("Verse 1 (1/2)", "Verse 1 (2/2)", "Chorus", "Verse 2", "Bridge"), labels(SlideGenerator.fromSong(s)));
        assertEquals(List.of("Verse 1", "Chorus", "Verse 2", "Bridge"), SlideGenerator.sectionKeys(s));

        s.setArrangement("Verse 1|Chorus|Verse 2|Chorus|Bridge|Chorus|Gone Section");
        List<Slide> slides = SlideGenerator.fromSong(s);
        assertEquals(List.of("Verse 1 (1/2)", "Verse 1 (2/2)", "Chorus", "Verse 2", "Chorus", "Bridge", "Chorus"), labels(slides));
        assertEquals("Chorus", slides.get(4).getSectionKey());
    }

    @Test
    void sectionsWithTheSameNameGetSeparateKeys() {
        Song s = new Song();
        s.setSections(LyricsParser.parse("Chorus\nfirst\n\nVerse 1\nv\n\nChorus\nsecond, different"));
        assertEquals(List.of("Chorus", "Verse 1", "Chorus#2"), SlideGenerator.sectionKeys(s));
        s.setArrangement("Chorus#2|Chorus");
        assertEquals(List.of("second, different", "first"), SlideGenerator.fromSong(s).stream().map(Slide::getText).toList());
    }

    @Test
    void groupsKeepRepeatsAndJoinMultiSlideSections() {
        List<Slide> slides = List.of(new Slide("Verse 1 (1/2)", "", null), new Slide("Verse 1 (2/2)", "", null),
                new Slide("Chorus", "", null), new Slide("Chorus", "", null),
                new Slide("Bridge (1/2)", "", null), new Slide("Bridge (2/2)", "", null),
                new Slide("Bridge (1/2)", "", null), new Slide("Bridge (2/2)", "", null),
                new Slide("John 3:16 (1/2)", "", null), new Slide("John 3:16 (2/2)", "", null), new Slide("John 3:17", "", null),
                new Slide("", "", null));
        List<ActionOrderPanel.Group> g = ActionOrderPanel.groups(slides);
        assertEquals(List.of("Verse 1", "Chorus", "Chorus", "Bridge", "Bridge", "John 3:16", "John 3:17", "Slide 12"),
                g.stream().map(ActionOrderPanel.Group::label).toList());
        assertEquals(List.of(2, 1, 1, 2, 2, 2, 1, 1), g.stream().map(ActionOrderPanel.Group::count).toList());
        assertEquals(4, g.get(3).start());
    }

    @Test
    void arrangementIsStoredAndSurvivesEditingTheLyrics() {
        AppPaths paths = new AppPaths(tempDir.resolve("home"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        SongService songs = new SongService(new SongRepository(db));
        Song saved = songs.save(song());
        songs.setArrangement(saved.getId(), List.of("Verse 1", "Chorus", "Bridge", "Chorus"));

        Song loaded = songs.load(saved.getId()).orElseThrow();
        assertEquals("Verse 1|Chorus|Bridge|Chorus", loaded.getArrangement());
        loaded.setTitle("Placeholder Song (edited)");
        Song edited = songs.save(loaded);
        assertEquals("Verse 1|Chorus|Bridge|Chorus", songs.load(edited.getId()).orElseThrow().getArrangement());
        assertEquals("Verse 1|Chorus|Bridge|Chorus", songs.duplicate(saved.getId()).getArrangement());

        songs.setArrangement(saved.getId(), null);
        assertNull(songs.load(saved.getId()).orElseThrow().getArrangement());
    }
}
