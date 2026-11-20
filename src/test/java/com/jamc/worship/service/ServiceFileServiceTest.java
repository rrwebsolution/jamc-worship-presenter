package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.LowerThird;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.MediaSourceType;
import com.jamc.worship.model.Presentation;
import com.jamc.worship.model.SectionType;
import com.jamc.worship.model.Service;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideDeck;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongSection;
import com.jamc.worship.presentation.ImageSlideRenderer;
import com.jamc.worship.presentation.PoiPresentationRenderer;
import com.jamc.worship.presentation.PresentationRenderers;
import com.jamc.worship.repository.LowerThirdRepository;
import com.jamc.worship.repository.MediaRepository;
import com.jamc.worship.repository.PresentationRepository;
import com.jamc.worship.repository.SceneRepository;
import com.jamc.worship.repository.ServiceRepository;
import com.jamc.worship.repository.SettingsRepository;
import com.jamc.worship.repository.SlideDeckRepository;
import com.jamc.worship.repository.SongRepository;
import com.jamc.worship.repository.ThemeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A service saved to a file on one laptop opens on another with its songs, slides, media and presentations. */
class ServiceFileServiceTest {

    @TempDir
    Path tempDir;

    /** One laptop: its own data folder and database. */
    private record Laptop(AppPaths paths, ServicePlanService plans, SongService songs, SlideDeckService decks,
                          MediaService media, PresentationService presentations, SceneService scenes,
                          ServiceFileService files) {
    }

    private Laptop laptop(String name) {
        AppPaths paths = new AppPaths(tempDir.resolve(name));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        ServicePlanService plans = new ServicePlanService(new ServiceRepository(db));
        SongService songs = new SongService(new SongRepository(db));
        SlideDeckService decks = new SlideDeckService(new SlideDeckRepository(db));
        MediaService media = new MediaService(new MediaRepository(db), paths);
        PresentationService presentations = new PresentationService(new PresentationRepository(db), paths.presentationsDir(),
                new PresentationRenderers(List.of(new PoiPresentationRenderer(), new ImageSlideRenderer())));
        SceneService scenes = new SceneService(new SceneRepository(db), new LowerThirdRepository(db));
        ThemeService themes = new ThemeService(new ThemeRepository(db), new SettingsRepository(db));
        return new Laptop(paths, plans, songs, decks, media, presentations, scenes,
                new ServiceFileService(paths, plans, songs, decks, media, presentations, scenes, themes));
    }

    @Test
    void serviceOpensOnAnotherLaptopWithItsContent() throws IOException {
        Laptop a = laptop("laptop-a");

        Song song = new Song();
        song.setTitle("Our Own Song");
        song.setAuthor("JAMC Worship");
        song.setSections(List.of(new SongSection(SectionType.VERSE, "Verse 1", "Line one\nLine two"),
                new SongSection(SectionType.CHORUS, "Chorus", "Sing it out")));
        song = a.songs.save(song);
        a.songs.setArrangement(song.getId(), List.of("Verse 1", "Chorus", "Chorus"));

        SlideDeck deck = a.decks.create("Welcome", null);
        deck.setSlides(List.of(new Slide("Slide 1", "Welcome to church", null), new Slide("Slide 2", "Offering", "Give")));
        deck = a.decks.save(deck);

        Path picture = picture(tempDir.resolve("outside").resolve("Poster.png"), Color.RED);
        MediaItem image = a.media.importFile(picture, true, MediaCategory.IMAGE);

        MediaItem yt = new MediaItem();
        yt.setName("Worship Video");
        yt.setKind(MediaKind.VIDEO);
        yt.setCategory(MediaCategory.VIDEO);
        yt.setPath("youtube:abcdefghijk");
        yt.setSourceType(MediaSourceType.YOUTUBE);
        yt.setYoutubeVideoId("abcdefghijk");
        yt = a.media.addOnlineReference(yt);

        Path slidesFolder = tempDir.resolve("outside").resolve("Sermon Slides");
        picture(slidesFolder.resolve("01.png"), Color.BLUE);
        picture(slidesFolder.resolve("02.png"), Color.GREEN);
        Presentation pres = a.presentations.importFile(slidesFolder, quiet());

        LowerThird lt = new LowerThird();
        lt.setName("Pastor");
        lt.setTitle("Ptr. Carlo");
        lt.setSubtitle("Sunday Service");
        lt = a.scenes.saveLowerThird(lt);

        Service s = a.plans.create("Sunday Service");
        s.getItems().add(ServiceItem.ofSong(song));
        s.getItems().add(new ServiceItem(ServiceItem.Type.SLIDE, "Welcome", deck.getId()));
        s.getItems().add(new ServiceItem(ServiceItem.Type.IMAGE, "Poster", image.getId()));
        s.getItems().add(new ServiceItem(ServiceItem.Type.VIDEO, "Worship Video", yt.getId()));
        s.getItems().add(new ServiceItem(ServiceItem.Type.PRESENTATION, "Sermon", pres.id()));
        s.getItems().add(new ServiceItem(ServiceItem.Type.LOWER_THIRD, "Pastor", lt.getId()));
        s.getItems().add(ServicePlanService.textItem(ServiceItem.Type.ANNOUNCEMENT, "News", "One\n\nTwo"));
        s.getItems().getFirst().setTag("Fast");
        s = a.plans.save(s);

        Path file = tempDir.resolve("usb").resolve(ServiceFileService.fileNameFor(s));
        ServiceFileService.ExportResult saved = a.files.export(s, file, m -> { });
        assertTrue(Files.isRegularFile(file));
        assertTrue(saved.warnings().isEmpty(), saved.warnings().toString());

        // ---- the other laptop: nothing of this in its library
        Laptop b = laptop("laptop-b");
        ServiceFileService.ImportResult opened = b.files.importFile(file, m -> { });
        assertTrue(opened.warnings().isEmpty(), opened.warnings().toString());
        Service there = b.plans.load(opened.service().getId()).orElseThrow();
        assertEquals(s.getName(), there.getName());
        assertEquals(s.getItems().stream().map(ServiceItem::getType).toList(),
                there.getItems().stream().map(ServiceItem::getType).toList());
        assertEquals("Fast", there.getItems().getFirst().getTag());
        assertEquals("One\n\nTwo", there.getItems().get(6).getPayload());

        Song songB = b.songs.load(there.getItems().get(0).getRefId()).orElseThrow();
        assertEquals("Our Own Song", songB.getTitle());
        assertEquals("Sing it out", songB.getSections().get(1).getContent());
        assertEquals("Verse 1|Chorus|Chorus", songB.getArrangement());

        SlideDeck deckB = b.decks.load(there.getItems().get(1).getRefId()).orElseThrow();
        assertEquals(List.of("Welcome to church", "Offering"), deckB.getSlides().stream().map(Slide::getText).toList());

        MediaItem imageB = b.media.load(there.getItems().get(2).getRefId()).orElseThrow();
        assertEquals("Poster", imageB.getName());
        assertTrue(b.media.exists(imageB));
        assertTrue(b.media.resolve(imageB).startsWith(b.paths.root()), "the picture is copied into laptop B's data");

        MediaItem ytB = b.media.load(there.getItems().get(3).getRefId()).orElseThrow();
        assertEquals("abcdefghijk", ytB.getYoutubeVideoId());

        Presentation presB = b.presentations.find(there.getItems().get(4).getRefId()).orElseThrow();
        assertEquals(2, presB.slideCount());
        assertEquals(pres.name(), presB.name());
        assertTrue(b.presentations.isCached(presB));

        LowerThird ltB = b.scenes.lowerThird(there.getItems().get(5).getRefId()).orElseThrow();
        assertEquals("Ptr. Carlo", ltB.getTitle());

        // ---- opening the same file again reuses everything (no duplicate songs, decks, media...)
        int songsBefore = b.songs.count();
        ServiceFileService.ImportResult again = b.files.importFile(file, m -> { });
        assertEquals(0, again.added());
        assertEquals(6, again.reused());
        assertEquals(songsBefore, b.songs.count());
        Service second = b.plans.load(again.service().getId()).orElseThrow();
        assertNotEquals(there.getId(), second.getId());
        assertEquals(there.getItems().stream().map(ServiceItem::getRefId).toList(),
                second.getItems().stream().map(ServiceItem::getRefId).toList());
    }

    @Test
    void songWithSameTitleButOtherLyricsIsAddedNotOverwritten() throws IOException {
        Laptop a = laptop("a2");
        Laptop b = laptop("b2");
        for (Laptop l : List.of(a, b)) {
            Song x = new Song();
            x.setTitle("Same Title");
            x.setSections(List.of(new SongSection(SectionType.VERSE, "Verse 1", l == a ? "Words from A" : "Words from B")));
            l.songs.save(x);
        }
        Song fromA = a.songs.search(new com.jamc.worship.model.SongQuery("Same Title", false,
                com.jamc.worship.model.SongQuery.Sort.TITLE_ASC)).getFirst();
        Service s = a.plans.create("Test");
        s.getItems().add(ServiceItem.ofSong(fromA));
        s = a.plans.save(s);
        Path file = tempDir.resolve("t." + ServiceFileService.EXTENSION);
        a.files.export(s, file, m -> { });

        ServiceFileService.ImportResult r = b.files.importFile(file, m -> { });
        Song opened = b.songs.load(r.service().getItems().getFirst().getRefId()).orElseThrow();
        assertEquals("Words from A", opened.getSections().getFirst().getContent());
        assertEquals(1, r.added());
    }

    @Test
    void notAServiceFileIsRefused() throws IOException {
        Laptop b = laptop("b3");
        Path junk = Files.writeString(tempDir.resolve("junk." + ServiceFileService.EXTENSION), "hello");
        assertThrows(ValidationException.class, () -> b.files.importFile(junk, m -> { }));
        assertNotNull(b.plans.services(null));
    }

    private static Path picture(Path file, Color color) throws IOException {
        Files.createDirectories(file.getParent());
        BufferedImage img = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 64, 36);
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    private static PresentationService.Progress quiet() {
        return new PresentationService.Progress() {
            @Override
            public void started(String name, int slideCount, String rendererName) {
            }

            @Override
            public void slideReady(PresentationService.PresentationSlide slide) {
            }
        };
    }
}
