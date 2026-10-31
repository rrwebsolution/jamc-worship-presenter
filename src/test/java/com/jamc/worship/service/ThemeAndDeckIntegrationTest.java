package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideDeck;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.model.Theme;
import com.jamc.worship.repository.SettingsRepository;
import com.jamc.worship.repository.SlideDeckRepository;
import com.jamc.worship.repository.SongRepository;
import com.jamc.worship.repository.ThemeRepository;
import com.jamc.worship.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeAndDeckIntegrationTest {

    @TempDir
    Path tempDir;

    private AppPaths paths;
    private ThemeService themes;
    private SlideDeckService decks;
    private SongService songs;

    @BeforeEach
    void setUp() {
        paths = new AppPaths(tempDir.resolve("data-root"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        themes = new ThemeService(new ThemeRepository(db), new SettingsRepository(db));
        decks = new SlideDeckService(new SlideDeckRepository(db));
        songs = new SongService(new SongRepository(db));
    }

    @Test
    void seededThemesAndDefault() {
        List<Theme> all = themes.list();
        assertEquals(6, all.size());
        assertTrue(all.stream().allMatch(Theme::isBuiltin));
        assertEquals("Worship Dark", themes.defaultTheme().getName());
        Theme bible = all.stream().filter(t -> t.getName().equals("Bible")).findFirst().orElseThrow();
        assertTrue(bible.getStyle().italic(), "V3 migration tuned built-in themes");
    }

    @Test
    void themeCrudAndRules() {
        Theme dark = themes.defaultTheme();
        assertThrows(ValidationException.class, () -> themes.delete(dark.getId()), "built-in/default cannot be deleted");

        Theme copy = themes.duplicate(dark.getId());
        assertEquals("Worship Dark (Copy)", copy.getName());
        assertFalse(copy.isBuiltin());
        assertEquals(dark.getStyle(), copy.getStyle());

        assertThrows(ValidationException.class, () -> themes.save(new Theme(null, "worship dark", false, SlideStyle.fallback())),
                "names are unique, case-insensitively");

        SlideStyle s = copy.getStyle();
        SlideStyle changed = new SlideStyle(BackgroundType.IMAGE, "media/backgrounds/a.jpg", "#101010", 0.3, "Georgia", 80,
                false, true, "#EEEEEE", s.align(), TextPosition.BOTTOM, 1.4, true, true, "#000000", 6);
        Theme updated = themes.save(new Theme(copy.getId(), "Evening", false, changed));
        assertEquals(changed, themes.load(updated.getId()).orElseThrow().getStyle(), "every style field round-trips");

        themes.setDefault(updated.getId());
        assertEquals("Evening", themes.defaultTheme().getName());
        assertThrows(ValidationException.class, () -> themes.delete(updated.getId()), "default cannot be deleted");
        themes.setDefault(dark.getId());
        themes.delete(updated.getId());
        assertTrue(themes.load(updated.getId()).isEmpty());
    }

    @Test
    void songStyleUsesThemeThenSongOverridesAndSurvivesThemeDeletion() {
        Theme custom = themes.duplicate(themes.defaultTheme().getId());
        Song song = new Song();
        song.setTitle("Styled");
        song.setThemeId(custom.getId());
        song.setFontSize(50);
        song.setBackground("media/backgrounds/song.jpg");
        song = songs.save(song);

        SlideStyle style = themes.styleForSong(song);
        assertEquals(50, style.fontSize());
        assertEquals(BackgroundType.IMAGE, style.backgroundType());
        assertEquals("media/backgrounds/song.jpg", style.backgroundValue());
        assertEquals(custom.getStyle().fontFamily(), style.fontFamily());

        assertEquals(1, themes.usageCount(custom.getId()));
        themes.delete(custom.getId());
        Song reloaded = songs.load(song.getId()).orElseThrow();
        assertNull(reloaded.getThemeId(), "deleting a theme detaches songs (ON DELETE SET NULL)");
        assertEquals(themes.defaultTheme().getStyle().fontFamily(), themes.styleForSong(reloaded).fontFamily());
    }

    @Test
    void deckLifecycleWithOverridesAndReordering() {
        List<SlideDeck> seeded = decks.search("");
        assertEquals(1, seeded.size());
        assertEquals(3, seeded.getFirst().getSlideCount());
        assertEquals(1, decks.search("prayer meeting").size(), "search matches slide text");

        SlideDeck deck = decks.create("Sermon Notes", null);
        assertEquals(1, deck.getSlides().size());

        Slide a = new Slide("Point 1", "Grace is a gift", null);
        Slide b = new Slide("Point 2", "Faith responds", "Ephesians 2:8");
        StyleOverrides o = new StyleOverrides();
        o.position = TextPosition.TOP;
        o.fontSize = 90;
        b.setOverrides(o);
        deck.setSlides(List.of(b, a)); // reordered
        SlideDeck saved = decks.save(deck);

        SlideDeck loaded = decks.load(saved.getId()).orElseThrow();
        assertEquals(List.of("Point 2", "Point 1"), loaded.getSlides().stream().map(Slide::getLabel).toList());
        assertEquals("Ephesians 2:8", loaded.getSlides().getFirst().getCaption());
        assertEquals(90, loaded.getSlides().getFirst().getOverrides().fontSize);
        assertNull(loaded.getSlides().get(1).getOverrides(), "slides without overrides store no JSON");

        assertThrows(ValidationException.class, () -> decks.create("sermon notes", null));
        SlideDeck copy = decks.duplicate(saved.getId());
        assertEquals("Sermon Notes (Copy)", copy.getName());
        assertEquals(2, copy.getSlides().size());
        assertNotEquals(saved.getId(), copy.getId());

        assertTrue(decks.delete(saved.getId()));
        assertTrue(decks.load(saved.getId()).isEmpty());
        assertEquals(2, decks.load(copy.getId()).orElseThrow().getSlides().size(), "copy is independent");
    }

    @Test
    void backgroundImportCopiesIntoLibraryOnceAndStoresRelativePath() throws Exception {
        Path outside = Files.writeString(tempDir.resolve("photo.jpg"), "not really a jpeg");
        String stored = FileUtils.importInto(paths, outside, paths.backgroundsDir());
        assertEquals("media/backgrounds/photo.jpg", stored);
        String second = FileUtils.importInto(paths, outside, paths.backgroundsDir());
        assertEquals("media/backgrounds/photo-2.jpg", second, "never overwrites an existing file");
        String inside = FileUtils.importInto(paths, paths.root().resolve(stored), paths.backgroundsDir());
        assertEquals(stored, inside, "files already in the library are not copied again");
    }
}
