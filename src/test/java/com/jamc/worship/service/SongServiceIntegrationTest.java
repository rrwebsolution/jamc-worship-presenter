package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.database.MigrationManager;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongQuery;
import com.jamc.worship.repository.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real migrations against a throwaway SQLite file and exercises song use cases end to end. */
class SongServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private AppPaths paths;
    private DatabaseManager db;
    private SongService service;

    @BeforeEach
    void setUp() {
        paths = new AppPaths(tempDir.resolve("JAMCWorshipPresenter"));
        paths.ensureDirectories();
        db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        service = new SongService(new SongRepository(db));
    }

    @Test
    void createsDirectoriesDatabaseAndAllTables() {
        for (Path dir : paths.allDirectories()) {
            assertTrue(Files.isDirectory(dir), "missing " + dir);
        }
        assertTrue(Files.exists(paths.databaseFile()));
        List<String> tables = db.query(conn -> {
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
                List<String> names = new java.util.ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                return names;
            }
        });
        for (String t : List.of("songs", "song_sections", "services", "service_items", "slides", "themes",
                "media", "scenes", "sources", "lower_thirds", "favorites", "settings", "schema_version")) {
            assertTrue(tables.contains(t), "missing table " + t);
        }
        assertEquals(MigrationManager.latestVersion(), new MigrationManager(db).currentVersion());
    }

    @Test
    void migrationIsIdempotentAndSeedsOnce() {
        int before = service.count();
        new DatabaseManager(DatabaseConfig.forPaths(paths)).initialize();
        assertEquals(before, service.count());
        assertEquals(2, before, "two public-domain hymns are seeded");
        int themes = db.query(conn -> {
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM themes")) {
                rs.next();
                return rs.getInt(1);
            }
        });
        assertEquals(6, themes);
    }

    @Test
    void addEditDeleteSong() {
        Song song = new Song();
        song.setTitle("  Build My Life  ");
        song.setAuthor("Test Author");
        song.setTags("worship, ");
        song.setSections(LyricsParser.parse("Verse 1\nWorthy of every song\n\nChorus\nHoly there is no one like You"));

        Song saved = service.save(song);
        assertTrue(saved.getId() != null && saved.getId() > 0);
        assertEquals("Build My Life", saved.getTitle(), "title is trimmed");
        assertEquals(2, saved.getSections().size());
        assertEquals("Chorus", saved.getSections().get(1).getLabel());

        saved.setTitle("Build My Life (Live)");
        saved.setSections(LyricsParser.parse("Verse 1\nA\n\nVerse 2\nB\n\nBridge\nC"));
        Song updated = service.save(saved);
        assertEquals(saved.getId(), updated.getId());
        assertEquals("Build My Life (Live)", updated.getTitle());
        assertEquals(List.of("Verse 1", "Verse 2", "Bridge"),
                updated.getSections().stream().map(s -> s.getLabel()).toList());

        assertTrue(service.delete(updated.getId()));
        assertTrue(service.load(updated.getId()).isEmpty());
        int orphanSections = db.query(conn -> {
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM song_sections WHERE song_id = " + updated.getId())) {
                rs.next();
                return rs.getInt(1);
            }
        });
        assertEquals(0, orphanSections, "sections are removed with the song");
        assertFalse(service.delete(updated.getId()), "second delete reports nothing deleted");
    }

    @Test
    void editorRoundTripKeepsLyrics() {
        String lyrics = "Verse 1\nHello from phase one\nEvery line is a test\n\nChorus\nSing it loud";
        Song song = new Song();
        song.setTitle("Round Trip");
        song.setSections(LyricsParser.parse(lyrics));
        long id = service.save(song).getId();

        // What the editor does: load by id, format to text, parse back, save unchanged.
        Song loaded = service.load(id).orElseThrow();
        String editorText = LyricsParser.format(loaded.getSections());
        assertEquals(lyrics, editorText);
        loaded.setSections(LyricsParser.parse(editorText));
        Song resaved = service.save(loaded);
        assertEquals(2, resaved.getSections().size());
        assertEquals(lyrics, LyricsParser.format(service.load(id).orElseThrow().getSections()));
    }

    @Test
    void searchMatchesTitleAuthorTagsAndLyrics() {
        Song song = new Song();
        song.setTitle("Way Maker");
        song.setAuthor("Sinach");
        song.setTags("faith");
        song.setSections(LyricsParser.parse("Chorus\nMiracle worker, promise keeper"));
        service.save(song);

        assertEquals(List.of("Way Maker"), titles(new SongQuery("way", false, SongQuery.Sort.TITLE_ASC)));
        assertEquals(List.of("Way Maker"), titles(new SongQuery("sinach", false, SongQuery.Sort.TITLE_ASC)));
        assertEquals(List.of("Way Maker"), titles(new SongQuery("FAITH", false, SongQuery.Sort.TITLE_ASC)));
        assertEquals(List.of("Way Maker"), titles(new SongQuery("promise keeper", false, SongQuery.Sort.TITLE_ASC)));
        assertEquals(List.of("Amazing Grace"), titles(new SongQuery("wretch", false, SongQuery.Sort.TITLE_ASC)));
        assertTrue(titles(new SongQuery("zzz-not-there", false, SongQuery.Sort.TITLE_ASC)).isEmpty());
        assertTrue(titles(new SongQuery("100%", false, SongQuery.Sort.TITLE_ASC)).isEmpty(),
                "LIKE wildcards in the search text are treated literally");

        assertEquals(List.of("Amazing Grace", "Doxology", "Way Maker"), titles(SongQuery.all()));
        assertEquals(List.of("Way Maker", "Doxology", "Amazing Grace"),
                titles(new SongQuery("", false, SongQuery.Sort.TITLE_DESC)));
        assertEquals("Way Maker", titles(new SongQuery("", false, SongQuery.Sort.RECENTLY_ADDED)).getFirst());
    }

    @Test
    void favoritesAndDuplicate() {
        Song grace = service.search(new SongQuery("Amazing", false, SongQuery.Sort.TITLE_ASC)).getFirst();
        assertFalse(grace.isFavorite());
        service.setFavorite(grace.getId(), true);
        service.setFavorite(grace.getId(), true); // idempotent
        assertEquals(List.of("Amazing Grace"), titles(new SongQuery("", true, SongQuery.Sort.TITLE_ASC)));
        assertTrue(service.load(grace.getId()).orElseThrow().isFavorite());

        Song copy = service.duplicate(grace.getId());
        assertNotEquals(grace.getId(), copy.getId());
        assertEquals("Amazing Grace (Copy)", copy.getTitle());
        assertFalse(copy.isFavorite(), "copies start unfavorited");
        assertEquals(4, copy.getSections().size());
        assertEquals("Amazing Grace (Copy 2)", service.duplicate(grace.getId()).getTitle());

        service.delete(grace.getId());
        assertTrue(titles(new SongQuery("", true, SongQuery.Sort.TITLE_ASC)).isEmpty(),
                "favorite entry is removed with the song");
    }

    @Test
    void validationRejectsBadInput() {
        Song empty = new Song();
        empty.setTitle("   ");
        assertThrows(ValidationException.class, () -> service.save(empty));

        Song bigFont = new Song();
        bigFont.setTitle("x");
        bigFont.setFontSize(1000);
        assertThrows(ValidationException.class, () -> service.save(bigFont));

        assertTrue(service.isDuplicateTitle("amazing grace", null));
        assertFalse(service.isDuplicateTitle("Brand New Song", null));
    }

    private List<String> titles(SongQuery query) {
        return service.search(query).stream().map(Song::getTitle).toList();
    }
}
