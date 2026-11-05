package com.jamc.worship.service;

import com.jamc.worship.bible.BiblePassage;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.LowerThird;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Service;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.repository.BibleRepository;
import com.jamc.worship.repository.LowerThirdRepository;
import com.jamc.worship.repository.MediaRepository;
import com.jamc.worship.repository.SceneRepository;
import com.jamc.worship.repository.ServiceRepository;
import com.jamc.worship.repository.SongRepository;
import com.jamc.worship.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end checks for services, media, Bible, scenes, lower thirds and backup/restore on a real database. */
class Phase4to8IntegrationTest {

    @TempDir
    Path tempDir;

    private AppPaths paths;
    private DatabaseManager db;
    private ServicePlanService plans;
    private MediaService media;
    private BibleService bible;
    private SceneService scenes;
    private SongService songs;

    @BeforeEach
    void setUp() {
        paths = new AppPaths(tempDir.resolve("root"));
        paths.ensureDirectories();
        db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        plans = new ServicePlanService(new ServiceRepository(db));
        media = new MediaService(new MediaRepository(db), paths);
        bible = new BibleService(new BibleRepository(db), new com.jamc.worship.repository.BibleVersionRepository(db));
        scenes = new SceneService(new SceneRepository(db), new LowerThirdRepository(db));
        songs = new SongService(new SongRepository(db));
    }

    @Test
    void servicesSaveReopenTemplatesAndDuplicate() {
        Song grace = songs.search(com.jamc.worship.model.SongQuery.all()).getFirst();
        Service s = plans.create("Sunday Service");
        assertTrue(s.getName().startsWith("Sunday Service — "));
        s.getItems().add(ServiceItem.ofSong(grace));
        s.getItems().add(ServicePlanService.textItem(ServiceItem.Type.ANNOUNCEMENT, "News", "Line one\n\nLine two"));
        ServiceItem bibleItem = new ServiceItem(ServiceItem.Type.BIBLE, "John 3:16 (KJV)", null);
        bibleItem.setPayload(Json.write(new BiblePassage("KJV", 43, 3, 16, 16)));
        s.getItems().add(bibleItem);
        s.getItems().add(new ServiceItem(ServiceItem.Type.BLANK, "Blank", null));
        Service saved = plans.save(s);

        Service reopened = plans.load(saved.getId()).orElseThrow();
        assertEquals(List.of(ServiceItem.Type.SONG, ServiceItem.Type.ANNOUNCEMENT, ServiceItem.Type.BIBLE, ServiceItem.Type.BLANK),
                reopened.getItems().stream().map(ServiceItem::getType).toList());
        assertEquals(grace.getId(), reopened.getItems().getFirst().getRefId());
        assertEquals("Line one\n\nLine two", reopened.getItems().get(1).getPayload());
        BiblePassage p = Json.readOrNull(reopened.getItems().get(2).getPayload(), BiblePassage.class);
        assertEquals(16, p.fromVerse());

        // reorder + remove round-trips
        reopened.setItems(List.of(reopened.getItems().get(3), reopened.getItems().get(0)));
        assertEquals(2, plans.save(reopened).getItems().size());

        Service template = plans.saveAsTemplate(reopened, "Standard Sunday");
        assertTrue(template.isTemplate());
        assertEquals(1, plans.templates().size());
        assertTrue(plans.services(null).stream().noneMatch(Service::isTemplate));
        Service fromTemplate = plans.createFromTemplate(template.getId());
        assertFalse(fromTemplate.isTemplate());
        assertEquals(2, fromTemplate.getItems().size());

        assertEquals(fromTemplate.getId(), plans.latest("Sunday Service").orElseThrow().getId());
        Service dup = plans.duplicate(saved.getId());
        assertTrue(dup.getName().endsWith("(Copy)"));
        assertTrue(plans.delete(dup.getId()));
        assertTrue(plans.load(dup.getId()).isEmpty());
    }

    @Test
    void serviceItemTagsSaveAndCarryIntoTemplates() {
        Song fast = new Song();
        fast.setTitle("Shout");
        fast.setTags("praise, fast");
        fast.setId(99L);
        assertEquals("Fast", ServiceItem.ofSong(fast).getTag());

        Service s = plans.create("Tagged");
        ServiceItem slow = new ServiceItem(ServiceItem.Type.BLANK, "Blank", null);
        slow.setTag("  Slow ");
        s.getItems().add(slow);
        s.getItems().add(new ServiceItem(ServiceItem.Type.BLANK, "Untagged", null));
        Service reopened = plans.load(plans.save(s).getId()).orElseThrow();
        assertEquals("Slow", reopened.getItems().getFirst().getTag());
        assertNull(reopened.getItems().get(1).getTag());
        Service template = plans.saveAsTemplate(reopened, "Tagged Template");
        assertEquals("Slow", plans.createFromTemplate(template.getId()).getItems().getFirst().getTag());
    }

    @Test
    void mediaImportCopyLinkRenameFavoriteDelete() throws Exception {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        Path original = tempDir.resolve("outside").resolve("Cross.png");
        Files.createDirectories(original.getParent());
        ImageIO.write(img, "png", original.toFile());

        MediaItem copied = media.importFile(original, true, null);
        assertEquals(MediaKind.IMAGE, copied.getKind());
        assertEquals(MediaCategory.IMAGE, copied.getCategory());
        assertEquals("media/images/Cross.png", copied.getPath());
        assertEquals(64, copied.getWidth());
        assertTrue(media.exists(copied));

        MediaItem linked = media.importFile(original, false, MediaCategory.BACKGROUND);
        assertFalse(linked.isCopied());
        assertEquals(original.toAbsolutePath().toString(), linked.getPath());

        assertEquals(1, media.find(List.of(MediaCategory.BACKGROUND), "", false).size());
        assertEquals(2, media.find(List.of(), "cross", false).size());

        media.rename(copied.getId(), "Old Rugged Cross");
        media.setFavorite(copied.getId(), true);
        List<MediaItem> favs = media.find(List.of(), "", true);
        assertEquals(List.of("Old Rugged Cross"), favs.stream().map(MediaItem::getName).toList());

        media.delete(media.load(copied.getId()).orElseThrow(), true);
        assertFalse(Files.exists(paths.root().resolve("media/images/Cross.png")), "copied file deleted on request");
        media.delete(linked, true);
        assertTrue(Files.exists(original), "linked originals are never deleted");

        Path text = Files.writeString(tempDir.resolve("notes.txt"), "x");
        assertThrows(ValidationException.class, () -> media.importFile(text, true, null));
    }

    private static final com.jamc.worship.bible.BibleSlideBuilder.Measurer MEASURE =
            (h, b, c, s) -> new com.jamc.worship.render.SlideRenderer(new com.jamc.worship.render.ImageCache(null))
                    .measure(h, b, c, s).fontSize();

    @Test
    void versionsSeededWithCopyrightedOnesNotSetUp() throws Exception {
        List<com.jamc.worship.bible.BibleVersion> versions = bible.ensureInstalled();
        assertEquals(List.of("KJV", "NLT", "ESV", "NIV", "APSD", "NASB"),
                versions.stream().map(com.jamc.worship.bible.BibleVersion::abbreviation).toList());
        com.jamc.worship.bible.BibleVersion kjv = versions.getFirst();
        assertTrue(bible.isReady(kjv));
        for (com.jamc.worship.bible.BibleVersion v : versions.subList(1, 6)) {
            assertFalse(bible.isReady(v), v.abbreviation() + " ships without text");
            assertTrue(bible.problem(v).contains("copyrighted"));
            assertThrows(ValidationException.class, () -> bible.verses(v, 43, 3));
        }
        assertEquals("Ang Pulong Sa Dios", versions.get(4).name());
        assertEquals("Cebuano", versions.get(4).language());
        assertEquals("New American Standard Bible", versions.get(5).name());

        assertEquals(36, bible.verses(kjv, 43, 3).size(), "John 3 has 36 verses");
        BiblePassage whole = bible.resolve(kjv, "Psalm 23").orElseThrow();
        assertEquals(6, whole.toVerse());
        assertTrue(bible.resolve(kjv, "John 99:1").isEmpty());
        assertFalse(bible.search(kjv, "shepherd want").isEmpty());
        assertEquals(6, bible.ensureInstalled().size(), "installing twice does not duplicate");

        // An online version without a key explains what to set up instead of failing.
        com.jamc.worship.bible.BibleVersion esv = versions.get(2)
                .withProvider(com.jamc.worship.bible.BibleVersion.Provider.ESV_API, null, null, true);
        assertTrue(bible.problem(bible.saveVersion(esv)).contains("ESV API key"));
    }

    @Test
    void canonicalChapterCountsMatchTheBundledText() throws Exception {
        com.jamc.worship.bible.BibleVersion kjv = bible.ensureInstalled().getFirst();
        int total = 0;
        for (int b = 1; b <= 66; b++) {
            total += com.jamc.worship.bible.BibleBooks.chapterCount(b);
        }
        assertEquals(1189, total);
        assertFalse(bible.verses(kjv, 66, 22).isEmpty(), "Revelation 22 exists");
        assertEquals("JHN", com.jamc.worship.bible.BibleBooks.usfm(43));
    }

    @Test
    void importedLicensedFileBecomesAVersionAndSameReferenceResolvesInBoth() throws Exception {
        bible.ensureInstalled();
        StringBuilder tsv = new StringBuilder();
        for (int v = 1; v <= 36; v++) {
            tsv.append("John\t3\t").append(v).append("\tPlaceholder text for verse ").append(v).append('\n');
        }
        Path file = Files.writeString(tempDir.resolve("licensed-niv.tsv"), tsv.toString());
        com.jamc.worship.bible.BibleVersion niv = bible.importFile(file, "NIV", null, null);
        assertEquals(com.jamc.worship.bible.BibleVersion.Provider.LOCAL_FILE, niv.provider());
        assertTrue(niv.enabled());
        assertEquals("New International Version", bible.version("NIV").orElseThrow().name(), "keeps the version's name");
        assertTrue(bible.isReady(niv));

        com.jamc.worship.bible.BibleVersion kjv = bible.version("KJV").orElseThrow();
        BiblePassage inKjv = bible.resolve(kjv, "John 3:16").orElseThrow();
        BiblePassage inNiv = bible.resolve(niv, "John 3:16").orElseThrow();
        assertEquals(inKjv.book(), inNiv.book());
        assertEquals(inKjv.fromVerse(), inNiv.fromVerse());
        assertEquals("NIV", inNiv.translation());

        com.jamc.worship.bible.BibleDisplay oneEach = new com.jamc.worship.bible.BibleDisplay(
                com.jamc.worship.bible.BibleDisplay.ReferencePosition.TOP, true, true, false);
        List<Slide> nivSlides = bible.slides(new BiblePassage("NIV", 43, 3, 16, 16), oneEach,
                com.jamc.worship.model.SlideStyle.fallback(), MEASURE);
        assertEquals("Placeholder text for verse 16", nivSlides.getFirst().getText());
        assertEquals("JOHN 3:16", nivSlides.getFirst().getHeading());
        assertEquals("NIV", nivSlides.getFirst().getCaption());
    }

    @Test
    void bibleSlidesFollowDisplayOptions() throws Exception {
        com.jamc.worship.bible.BibleVersion kjv = bible.ensureInstalled().getFirst();
        com.jamc.worship.model.SlideStyle style = com.jamc.worship.model.SlideStyle.fallback();
        BiblePassage p = bible.resolve(kjv, "jn 3:16-17").orElseThrow();

        var top = new com.jamc.worship.bible.BibleDisplay(com.jamc.worship.bible.BibleDisplay.ReferencePosition.TOP, true, true, false);
        List<Slide> one = bible.slides(p, top, style, MEASURE);
        assertEquals(2, one.size(), "one verse per slide");
        assertEquals("JOHN 3:16", one.getFirst().getHeading());
        assertEquals("KJV", one.getFirst().getCaption());
        assertEquals("John 3:17", one.get(1).getLabel());

        var bottom = new com.jamc.worship.bible.BibleDisplay(com.jamc.worship.bible.BibleDisplay.ReferencePosition.BOTTOM, true, false, false);
        Slide b = bible.slides(p, bottom, style, MEASURE).getFirst();
        assertEquals(null, b.getHeading());
        assertEquals("John 3:16   ·   KJV", b.getCaption());

        var hidden = new com.jamc.worship.bible.BibleDisplay(com.jamc.worship.bible.BibleDisplay.ReferencePosition.HIDDEN, false, true, false);
        Slide h = bible.slides(p, hidden, style, MEASURE).getFirst();
        assertEquals(null, h.getHeading());
        assertEquals(null, h.getCaption());

        var auto = com.jamc.worship.bible.BibleDisplay.defaults();
        List<Slide> packed = bible.slides(p, auto, style, MEASURE);
        assertEquals(1, packed.size(), "two average verses fit on one slide");
        assertEquals("JOHN 3:16-17", packed.getFirst().getHeading());
        assertTrue(packed.getFirst().getText().startsWith("16  "), "verse numbers shown when a slide holds several verses");
    }

    @Test
    void longVerseIsSplitAcrossSlidesWhenTooSmall() {
        // A measurer that pretends anything longer than 60 characters must shrink below the minimum.
        com.jamc.worship.bible.BibleSlideBuilder.Measurer strict = (h, body, c, s) -> body.length() > 60 ? 10f : s.fontSize();
        com.jamc.worship.bible.BibleProvider.Verse longVerse = new com.jamc.worship.bible.BibleProvider.Verse(43, 3, 16,
                "First clause of placeholder text, second clause here. Another sentence follows it; and a final part ends.");
        List<Slide> slides = new com.jamc.worship.bible.BibleSlideBuilder(com.jamc.worship.bible.BibleDisplay.defaults(),
                com.jamc.worship.model.SlideStyle.fallback(), "NIV", strict).build(List.of(longVerse));
        assertTrue(slides.size() >= 2, "split into several slides");
        assertTrue(slides.stream().allMatch(s -> s.getText().length() <= 60));
        assertEquals("John 3:16 (1/" + slides.size() + ")", slides.getFirst().getLabel());
        assertEquals(longVerse.text().replaceAll("\\s+", " "),
                String.join(" ", slides.stream().map(Slide::getText).toList()), "no words lost");
    }

    @Test
    void obsStyleScenesAreInstalledAndProtected() {
        List<Scene> all = scenes.scenes();
        assertTrue(all.stream().allMatch(Scene::isBuiltin), "the standard scenes are protected");
        Scene camLyrics = all.stream().filter(s -> s.getName().equals("Camera + Lyrics")).findFirst().orElseThrow();
        assertEquals(List.of(SourceType.LOWER_THIRD, SourceType.CHURCH_LOGO, SourceType.LYRICS, SourceType.OVERLAY,
                SourceType.CAMERA), camLyrics.getSources().stream().map(s -> s.getType()).toList());
        var lyrics = camLyrics.getSources().get(2).getConfig();
        assertEquals(com.jamc.worship.model.TextPosition.BOTTOM, lyrics.textPosition);
        assertEquals(0.62, lyrics.textScale, 1e-9);
        assertTrue(camLyrics.getSources().get(4).isVisible(), "the camera is on in Camera + Lyrics");
        Scene bible = all.stream().filter(s -> s.getName().equals("Bible")).findFirst().orElseThrow();
        assertTrue(bible.has(SourceType.CHURCH_LOGO));
        Scene welcome = all.stream().filter(s -> s.getName().equals("Logo / Welcome")).findFirst().orElseThrow();
        assertTrue(welcome.has(SourceType.BACKGROUND));
        assertTrue(welcome.getSources().stream().anyMatch(s -> s.getType() == SourceType.TEXT && !s.isVisible()),
                "optional welcome text, hidden until wanted");

        Scene copy = scenes.duplicate(camLyrics.getId());
        assertFalse(copy.isBuiltin());
        scenes.delete(copy.getId());
        // the standard scenes can be deleted too
        scenes.delete(camLyrics.getId());
        assertTrue(scenes.scenes().stream().noneMatch(s -> s.getName().equals("Camera + Lyrics")));
    }

    @Test
    void presentationSourcesReferenceTheLibraryById() {
        List<Scene> all = scenes.scenes();
        Scene sermon = all.stream().filter(s -> s.getName().equals("Sermon")).findFirst().orElseThrow();
        var types = sermon.getSources().stream().map(s -> s.getType()).toList();
        assertTrue(types.contains(SourceType.PRESENTATION), "the Sermon scene has a Presentation source");
        assertEquals(types.indexOf(SourceType.BACKGROUND) - 1, types.indexOf(SourceType.PRESENTATION),
                "just above the slide background");
        var slot = sermon.getSources().get(types.indexOf(SourceType.PRESENTATION));
        assertTrue(slot.getConfig().isDynamicPresentation(), "one Sermon scene serves every sermon");
        assertEquals(1.0, slot.getConfig().w, 1e-9);

        Scene camPres = all.stream().filter(s -> s.getName().equals("Camera + Presentation")).findFirst().orElseThrow();
        assertTrue(camPres.isBuiltin());
        assertEquals(List.of(SourceType.LOWER_THIRD, SourceType.CHURCH_LOGO, SourceType.CAMERA, SourceType.PRESENTATION,
                SourceType.BACKGROUND), camPres.getSources().stream().map(s -> s.getType()).toList());

        // a fixed source keeps only the library id and slide number, and they survive saving
        Scene copy = scenes.duplicate(camPres.getId());
        var c = copy.getSources().get(3).getConfig();
        c.presentationMode = com.jamc.worship.model.SourceConfig.PresentationMode.FIXED;
        c.presentationId = 42L;
        c.slideIndex = 4;
        c.keepAspect = true;
        scenes.save(copy);
        Scene reloaded = scenes.scenes().stream().filter(s -> s.getId().equals(copy.getId())).findFirst().orElseThrow();
        var r = reloaded.getSources().get(3).getConfig();
        assertEquals(42L, r.presentationId);
        assertEquals(4, r.slideIndex);
        assertFalse(r.isDynamicPresentation());
        assertNull(r.path, "no slide pictures or files are copied into the scene");

        // an IMAGE SLIDESHOW keeps its library ids, order and playback settings with the scene
        com.jamc.worship.model.Source show = new com.jamc.worship.model.Source("Sunday Announcements", SourceType.SLIDESHOW);
        var sc = show.getConfig();
        sc.imageIds = List.of(7L, 3L, 9L, 3L);
        sc.slideSeconds = 8;
        sc.slideTransition = "CROSSFADE";
        sc.slideTransitionMs = 800;
        sc.shuffle = true;
        sc.loop = false;
        sc.restartOnProgram = false;
        sc.slideBackground = "black";
        sc.x = 0.6;
        sc.w = 0.35;
        sc.opacity = 0.9;
        reloaded.getSources().addFirst(show);
        scenes.save(reloaded);
        var saved = scenes.scenes().stream().filter(s -> s.getId().equals(copy.getId())).findFirst().orElseThrow()
                .getSources().getFirst();
        assertEquals(SourceType.SLIDESHOW, saved.getType());
        assertEquals("Sunday Announcements", saved.getName());
        var sr = saved.getConfig();
        assertEquals(List.of(7L, 3L, 9L, 3L), sr.imageIds, "ids in show order (a duplicate stays)");
        assertEquals(8, sr.slideSeconds);
        assertEquals("CROSSFADE", sr.slideTransition);
        assertEquals(800, sr.slideTransitionMs);
        assertEquals(Boolean.TRUE, sr.shuffle);
        assertEquals(Boolean.FALSE, sr.loop);
        assertEquals(Boolean.FALSE, sr.restartOnProgram);
        assertEquals("black", sr.slideBackground);
        assertEquals(0.6, sr.x, 1e-9);
        assertEquals(0.9, sr.opacity, 1e-9);
        assertNull(sr.path, "the image files are referenced, never copied");
        scenes.delete(copy.getId());
    }

    @Test
    void defaultScenesSeededAndEditable() {
        List<Scene> all = scenes.scenes();
        assertEquals(List.of("Worship", "Camera", "Camera + Lyrics", "Bible", "Sermon", "Camera + Presentation", "Announcement", "Fullscreen Video",
                        "Logo / Welcome", "Blank"),
                all.stream().map(Scene::getName).toList());
        Scene worship = all.getFirst();
        assertEquals(List.of(SourceType.LOWER_THIRD, SourceType.CHURCH_LOGO, SourceType.LYRICS, SourceType.CAMERA,
                SourceType.BACKGROUND), worship.getSources().stream().map(s -> s.getType()).toList());
        assertFalse(worship.getSources().get(3).isVisible(), "camera starts hidden");

        worship.getSources().get(3).setVisible(true);
        worship.getSources().get(1).getConfig().x = 0.5;
        Scene saved = scenes.save(worship);
        assertTrue(saved.getSources().get(3).isVisible());
        assertEquals(0.5, saved.getSources().get(1).getConfig().x);

        Scene copy = scenes.duplicate(saved.getId());
        assertEquals("Worship (Copy)", copy.getName());
        assertEquals(5, copy.getSources().size());
        scenes.delete(copy.getId());
        Scene created = scenes.create("Prayer");
        assertEquals(2, created.getSources().size());

        List<LowerThird> lts = scenes.lowerThirds();
        assertEquals("Ptr. Carlo", lts.getFirst().getTitle());
        LowerThird lt = new LowerThird();
        lt.setTitle("Sis. Ana");
        lt.setSubtitle("Worship Leader");
        lt.setPosition(LowerThird.Position.BOTTOM_RIGHT);
        LowerThird savedLt = scenes.saveLowerThird(lt);
        assertEquals(LowerThird.Position.BOTTOM_RIGHT, scenes.lowerThird(savedLt.getId()).orElseThrow().getPosition());
        assertThrows(ValidationException.class, () -> scenes.saveLowerThird(new LowerThird()));
    }

    @Test
    void backupAndRestoreRoundTripWithSafetyBackup() throws Exception {
        BackupService backups = new BackupService(paths, db);
        Files.writeString(paths.settingsFile(), "ui.theme=light\n");
        Path backup = backups.backup(tempDir.resolve("b.zip"), false, m -> { });
        assertTrue(Files.size(backup) > 1000);

        Song extra = new Song();
        extra.setTitle("Added After Backup");
        songs.save(extra);
        assertEquals(3, songs.count());

        Path safety = backups.restore(backup, m -> { });
        assertTrue(Files.exists(safety), "safety backup written before restoring");
        DatabaseManager reopened = new DatabaseManager(DatabaseConfig.forPaths(paths));
        reopened.initialize();
        assertEquals(2, new SongService(new SongRepository(reopened)).count(), "library is back to the backed-up state");

        // a zip with a path-escaping entry must be refused before anything is replaced
        Path evil = tempDir.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(evil))) {
            zip.putNextEntry(new ZipEntry("../../escape.txt"));
            zip.write(1);
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> backups.restore(evil, m -> { }));

        Path notBackup = tempDir.resolve("other.zip");
        try (OutputStream os = Files.newOutputStream(notBackup); ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write(1);
            zip.closeEntry();
        }
        assertThrows(ValidationException.class, () -> backups.restore(notBackup, m -> { }));
    }
}
