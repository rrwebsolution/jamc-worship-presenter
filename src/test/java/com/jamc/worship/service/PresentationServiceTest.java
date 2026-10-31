package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.Presentation;
import com.jamc.worship.presentation.ImageSlideRenderer;
import com.jamc.worship.presentation.PoiPresentationRenderer;
import com.jamc.worship.presentation.PresentationRenderers;
import com.jamc.worship.repository.PresentationRepository;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Imports real .pptx / .ppt files (generated with POI) through the built-in renderer. */
class PresentationServiceTest {

    @TempDir
    Path tempDir;

    private PresentationService service;
    private AppPaths paths;

    @BeforeEach
    void setUp() {
        paths = new AppPaths(tempDir.resolve("home"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        service = new PresentationService(new PresentationRepository(db), paths.presentationsDir(),
                new PresentationRenderers(List.of(new PoiPresentationRenderer(), new ImageSlideRenderer())));
    }

    @Test
    void importsEverySlideWithImagesThumbnailsTitlesAndProgress() throws Exception {
        Path file = pptx(tempDir.resolve("Sunday Sermon.pptx"), 3, Color.RED);
        List<Integer> ready = new ArrayList<>();
        int[] total = {0};
        Presentation p = service.importFile(file, new PresentationService.Progress() {
            @Override
            public void started(String name, int slideCount, String renderer) {
                total[0] = slideCount;
            }

            @Override
            public void slideReady(PresentationService.PresentationSlide slide) {
                ready.add(slide.number());
                assertTrue(Files.isRegularFile(slide.thumbnail()), "thumbnail exists when reported");
            }
        });

        assertEquals("Sunday Sermon.pptx", p.name());
        assertEquals(3, p.slideCount());
        assertEquals(3, total[0]);
        assertEquals(List.of(1, 2, 3), ready);
        List<PresentationService.PresentationSlide> slides = service.slides(p);
        assertEquals(3, slides.size());
        assertEquals("Point 2", slides.get(1).title());
        BufferedImage full = ImageIO.read(slides.getFirst().image().toFile());
        BufferedImage thumb = ImageIO.read(slides.getFirst().thumbnail().toFile());
        assertEquals(new Dimension(1920, 1080), new Dimension(full.getWidth(), full.getHeight()));
        assertEquals(new Dimension(320, 180), new Dimension(thumb.getWidth(), thumb.getHeight()));
        // the red rectangle at the slide center is really drawn
        Color center = new Color(full.getRGB(960, 540));
        assertTrue(center.getRed() > 200 && center.getGreen() < 60, "red shape rendered, got " + center);
        assertTrue(Files.isRegularFile(paths.presentationsDir().resolve(p.folder()).resolve("presentation.json")));
        assertEquals(PresentationService.SourceState.CURRENT, service.sourceState(p));
        assertEquals(1, service.list("sermon").size());
    }

    @Test
    void oldFourByThreePptIsPillarboxedOnBlack() throws Exception {
        Path file = tempDir.resolve("Old.ppt");
        try (HSLFSlideShow show = new HSLFSlideShow()) {
            show.setPageSize(new Dimension(720, 540));
            HSLFSlide slide = show.createSlide();
            slide.getBackground().getFill().setForegroundColor(Color.WHITE);
            HSLFTextBox box = slide.createTextBox();
            box.setText("Hello from 1997");
            box.setAnchor(new Rectangle(100, 200, 500, 80));
            try (OutputStream out = Files.newOutputStream(file)) {
                show.write(out);
            }
        }
        Presentation p = service.importFile(file, quiet());
        assertEquals(1, p.slideCount());
        BufferedImage full = ImageIO.read(service.slides(p).getFirst().image().toFile());
        assertEquals(Color.BLACK.getRGB(), full.getRGB(20, 540), "left bar is black");
        assertNotEquals(Color.BLACK.getRGB(), full.getRGB(960, 100), "slide area is not black");
    }

    @Test
    void detectsChangedFileAndRefreshesIntoNewCacheFolder() throws Exception {
        Path file = pptx(tempDir.resolve("Youth.pptx"), 2, Color.BLUE);
        Presentation p = service.importFile(file, quiet());
        String oldFolder = p.folder();

        pptx(file, 4, Color.GREEN);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals(PresentationService.SourceState.CHANGED, service.sourceState(p));

        Presentation refreshed = service.refresh(p.id(), null, quiet());
        assertEquals(4, refreshed.slideCount());
        assertNotEquals(oldFolder, refreshed.folder());
        assertEquals(PresentationService.SourceState.CURRENT, service.sourceState(refreshed));
        assertTrue(Files.isDirectory(paths.presentationsDir().resolve(oldFolder)), "old cache kept while it may be live");
        assertEquals(1, service.cleanUp());
        assertFalse(Files.exists(paths.presentationsDir().resolve(oldFolder)));
    }

    @Test
    void renameAndRemoveNeverTouchTheOriginalFile() throws Exception {
        Path file = pptx(tempDir.resolve("Anniversary.pptx"), 1, Color.ORANGE);
        Presentation p = service.importFile(file, quiet());
        assertEquals("Anniversary 2026", service.rename(p.id(), "Anniversary 2026").name());
        service.remove(p.id());
        assertTrue(service.list(null).isEmpty());
        assertFalse(Files.exists(paths.presentationsDir().resolve(p.folder())));
        assertTrue(Files.isRegularFile(file), "the user's file is kept");
    }

    @Test
    void missingFileAndBadFilesAreReportedNotCrashing() throws Exception {
        Path file = pptx(tempDir.resolve("Moved.pptx"), 1, Color.RED);
        Presentation p = service.importFile(file, quiet());
        Files.delete(file);
        assertEquals(PresentationService.SourceState.MISSING, service.sourceState(p));
        assertThrows(IOException.class, () -> service.refresh(p.id(), null, quiet()));
        assertEquals(1, service.slides(p).size(), "cached slides still usable");

        Path junk = Files.writeString(tempDir.resolve("broken.pptx"), "not a presentation");
        assertThrows(IOException.class, () -> service.importFile(junk, quiet()));
        assertTrue(service.list(null).stream().noneMatch(x -> x.name().equals("broken.pptx")));
        assertFalse(service.canImport(Files.writeString(tempDir.resolve("notes.txt"), "x")));
    }

    @Test
    void importsAFolderOfExportedSlideImagesInNumericOrder() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("Exported"));
        for (int n : new int[]{1, 2, 10}) {
            BufferedImage img = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, n);
            ImageIO.write(img, "png", dir.resolve("Slide" + n + ".PNG").toFile());
        }
        Presentation p = service.importFile(dir, quiet());
        assertEquals(3, p.slideCount());
        assertEquals("Slide images", p.renderer());
    }

    private static PresentationService.Progress quiet() {
        return new PresentationService.Progress() {
            @Override
            public void started(String name, int slideCount, String renderer) {
            }

            @Override
            public void slideReady(PresentationService.PresentationSlide slide) {
            }
        };
    }

    /** A 16:9 deck whose slides have a title text box and a filled rectangle in the middle. */
    private static Path pptx(Path file, int slides, Color shapeColor) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow()) {
            show.setPageSize(new Dimension(960, 540));
            for (int i = 1; i <= slides; i++) {
                XSLFSlide slide = show.createSlide(show.getSlideMasters().getFirst()
                        .getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_ONLY));
                slide.getPlaceholder(0).setText("Point " + i);
                XSLFAutoShape box = slide.createAutoShape();
                box.setShapeType(ShapeType.RECT);
                box.setAnchor(new Rectangle(380, 220, 200, 100));
                box.setFillColor(shapeColor);
                XSLFTextBox note = slide.createTextBox();
                note.setAnchor(new Rectangle(40, 460, 600, 40));
                note.setText("Footer text " + i);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                show.write(out);
            }
        }
        return file;
    }
}
