package com.jamc.worship.service;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.config.TypographySettings;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.ContentKind;
import com.jamc.worship.model.FontWeight;
import com.jamc.worship.model.SectionType;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongSection;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.model.Theme;
import com.jamc.worship.model.Typography;
import com.jamc.worship.output.LiveContent;
import com.jamc.worship.output.ProgramOutput;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.repository.SettingsRepository;
import com.jamc.worship.repository.SongRepository;
import com.jamc.worship.repository.ThemeRepository;
import com.jamc.worship.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Global lyrics typography: capitals on screen only, separate profiles, and the override order. */
class TypographyTest {

    private static final String LYRICS = "Amazing grace\nHow sweet the sound\nThat saved a wretch like me";

    @TempDir
    Path tempDir;

    private AppPaths paths;
    private AppConfig config;
    private TypographySettings typography;
    private ThemeService themes;
    private SongService songs;

    @BeforeEach
    void setUp() {
        paths = new AppPaths(tempDir.resolve("home"));
        paths.ensureDirectories();
        config = new AppConfig(paths);
        typography = new TypographySettings(config);
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        themes = new ThemeService(new ThemeRepository(db), new SettingsRepository(db), typography);
        songs = new SongService(new SongRepository(db));
    }

    private Song amazingGrace() {
        Song s = new Song();
        s.setTitle("Amazing Grace");
        s.setSections(List.of(new SongSection(SectionType.VERSE, "Verse 1", LYRICS)));
        return songs.save(s);
    }

    @Test
    void textCaseIsAppliedOnlyWhenDrawn() {
        assertEquals("AMAZING GRACE\nHOW SWEET THE SOUND", TextTransform.UPPERCASE.apply("Amazing grace\nHow sweet the sound"));
        assertEquals("amazing grace", TextTransform.LOWERCASE.apply("Amazing GRACE"));
        assertEquals("Don't Stop (Believing) 2nd Time", TextTransform.TITLE_CASE.apply("don't STOP (believing) 2nd time"));
        assertEquals("Amazing grace", TextTransform.ORIGINAL.apply("Amazing grace"));
        assertEquals("IKAW LAMANG", TextTransform.UPPERCASE.apply("ikaw lamang"), "locale-neutral: i becomes I");
    }

    @Test
    void firstRunDefaultsAreCapitalLyricsAndOriginalBible() {
        assertTrue(typography.isGlobal(ContentKind.LYRICS), "lyrics use the global typography out of the box");
        assertFalse(typography.isGlobal(ContentKind.BIBLE), "Bible keeps its theme until switched on");
        Typography lyrics = typography.get(ContentKind.LYRICS);
        assertEquals(TextTransform.UPPERCASE, lyrics.textTransform());
        assertEquals(TextAlign.CENTER, lyrics.align());
        assertEquals(TextPosition.MIDDLE, lyrics.position());
        assertEquals("#FFFFFF", lyrics.fontColor());
        assertTrue(lyrics.shadow());
        assertTrue(lyrics.outline());
        assertEquals(TextTransform.ORIGINAL, typography.get(ContentKind.BIBLE).textTransform());
    }

    @Test
    void settingsSurviveARestart() throws Exception {
        Typography changed = new Typography("Georgia", FontWeight.BOLD, true, 66, TextTransform.TITLE_CASE, "#FFEE00",
                TextAlign.LEFT, TextPosition.BOTTOM, 1.4, false, 0.3, true, "#101010", 5);
        typography.set(ContentKind.LYRICS, changed);
        typography.setActionCardsUseCase(true);

        AppConfig reopened = new AppConfig(paths);
        reopened.load();
        TypographySettings again = new TypographySettings(reopened);
        assertEquals(changed, again.get(ContentKind.LYRICS));
        assertTrue(again.actionCardsUseCase());
        String file = Files.readString(paths.settingsFile());
        assertTrue(file.contains("lyrics.textTransform=TITLE_CASE"));
        assertTrue(file.contains("lyrics.fontFamily=Georgia"));

        again.restoreDefaults(ContentKind.LYRICS);
        assertEquals(TextTransform.UPPERCASE, again.get(ContentKind.LYRICS).textTransform());
    }

    @Test
    void everySongShowsCapitalsWhileTheStoredLyricsKeepTheirCase() {
        Song song = amazingGrace();
        SlideStyle style = themes.styleForSong(song);
        assertEquals(TextTransform.UPPERCASE, style.textTransform());
        assertEquals(typography.get(ContentKind.LYRICS).fontSize(), style.fontSize());
        assertEquals(themes.defaultTheme().getStyle().backgroundValue(), style.backgroundValue(), "background still from the theme");

        // drawn exactly like text typed in capitals
        SlideRenderer renderer = new SlideRenderer(new ImageCache(null));
        BufferedImage shown = renderer.render(new Slide("Verse 1", LYRICS, null), style, 320, 180);
        BufferedImage typedCaps = renderer.render(new Slide("Verse 1", LYRICS.toUpperCase(), null),
                style.withTextTransform(TextTransform.ORIGINAL), 320, 180);
        assertArrayEquals(pixels(typedCaps), pixels(shown));

        Song stored = songs.load(song.getId()).orElseThrow();
        assertEquals(LYRICS, stored.getSections().getFirst().getContent(), "the database keeps the lyrics as typed");

        // back to Original: the capitals of the stored lyrics return
        typography.set(ContentKind.LYRICS, typography.get(ContentKind.LYRICS).withTextTransform(TextTransform.ORIGINAL));
        assertEquals(TextTransform.ORIGINAL, themes.styleForSong(stored).textTransform());
    }

    @Test
    void bibleAndOtherContentKeepTheirOwnProfiles() {
        Theme bible = themes.list().stream().filter(t -> t.getName().equals("Bible")).findFirst().orElseThrow();
        assertEquals(bible.getStyle(), themes.styleFor(ContentKind.BIBLE, bible), "off: the Bible theme as it is");
        typography.setGlobal(ContentKind.BIBLE, true);
        SlideStyle verses = themes.styleFor(ContentKind.BIBLE, bible);
        assertEquals(TextTransform.ORIGINAL, verses.textTransform(), "song capitals never reach Bible verses");
        assertEquals(typography.get(ContentKind.BIBLE).fontSize(), verses.fontSize());
        assertEquals(TextTransform.ORIGINAL, themes.styleFor(ContentKind.CUSTOM_SLIDES, bible).textTransform());
    }

    @Test
    void slideBeatsSongBeatsThemeOverrideBeatsGlobal() {
        Theme youth = themes.duplicate(themes.defaultTheme().getId());
        StyleOverrides youthText = new StyleOverrides();
        youthText.fontFamily = "Poppins";
        youthText.fontSize = 60;
        youthText.textTransform = TextTransform.UPPERCASE;
        youth.setStyle(youthText.applyTo(youth.getStyle()));
        youth.setTypographyOverride(true);
        youth = themes.save(youth);
        assertTrue(themes.load(youth.getId()).orElseThrow().isTypographyOverride(), "stored with the theme");

        Song song = amazingGrace();
        song.setThemeId(youth.getId());
        song = songs.save(song);
        SlideStyle themeWins = themes.styleForSong(song);
        assertEquals("Poppins", themeWins.fontFamily());
        assertEquals(60, themeWins.fontSize());

        StyleOverrides songOwn = new StyleOverrides();
        songOwn.textTransform = TextTransform.TITLE_CASE;
        songOwn.fontWeight = FontWeight.LIGHT;
        songs.setTypography(song.getId(), null, 48, songOwn);
        Song reloaded = songs.load(song.getId()).orElseThrow();
        SlideStyle songWins = themes.styleForSong(reloaded);
        assertEquals(TextTransform.TITLE_CASE, songWins.textTransform());
        assertEquals(FontWeight.LIGHT, songWins.fontWeight());
        assertEquals(48, songWins.fontSize());
        assertEquals("Poppins", songWins.fontFamily(), "what the song does not set still comes from the theme");
        assertEquals(LYRICS, reloaded.getSections().getFirst().getContent());

        Slide slide = new Slide("Verse 1", LYRICS, null);
        StyleOverrides slideOwn = new StyleOverrides();
        slideOwn.textTransform = TextTransform.ORIGINAL;
        slide.setOverrides(slideOwn);
        assertEquals(TextTransform.ORIGINAL, slide.styleOver(songWins).textTransform());

        // "Use Global" on the song: back to the theme's own text
        songs.setTypography(song.getId(), null, null, null);
        Song cleared = songs.load(song.getId()).orElseThrow();
        assertNull(cleared.getTypography());
        assertEquals(60, themes.styleForSong(cleared).fontSize());
    }

    @Test
    void oldSlideOverridesAndThemesStillRead() {
        StyleOverrides legacy = Json.readOrNull("{\"bold\":false}", StyleOverrides.class);
        assertEquals(FontWeight.REGULAR, legacy.applyTo(SlideStyle.fallback()).fontWeight());
        assertEquals(FontWeight.REGULAR, FontWeight.parse("NORMAL", FontWeight.BOLD));
        Theme dark = themes.defaultTheme();
        assertEquals(TextTransform.ORIGINAL, dark.getStyle().textTransform(), "themes keep their look after the upgrade");
        assertEquals(SlideStyle.DEFAULT_SHADOW_STRENGTH, dark.getStyle().shadowStrength(), 1e-9);
    }

    @Test
    void programKeepsItsLookUntilTake() {
        Song song = amazingGrace();
        SlideStyle before = themes.styleForSong(song);
        ProgramOutput program = new ProgramOutput();
        LiveContent live = LiveContent.snapshot(new Slide("Verse 1", LYRICS, null), before, "Amazing Grace");
        program.take(live);

        typography.set(ContentKind.LYRICS, typography.get(ContentKind.LYRICS).withTextTransform(TextTransform.LOWERCASE));
        SlideStyle preview = themes.styleForSong(song);
        assertEquals(TextTransform.LOWERCASE, preview.textTransform(), "Preview gets the new look");
        assertSame(live, program.content(), "Program is unchanged until TAKE");
        assertEquals(TextTransform.UPPERCASE, program.content().style().textTransform());
    }

    private static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }
}
