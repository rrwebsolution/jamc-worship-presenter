package com.jamc.worship.output;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.ProgramLyricsStyle;
import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Program lyrics style: a lower third over the camera on the stream, while Preview and the projector stay full screen. */
class ProgramLyricsStyleTest {

    private static final Color CAMERA = new Color(0xC03020);

    @TempDir
    Path dir;
    private AppConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new AppConfig(new AppPaths(dir));
        ProgramLyricsStyle.apply(cfg, ProgramLyricsStyle.defaults());
    }

    @AfterEach
    void tearDown() {
        ProgramLyricsStyle.apply(cfg, ProgramLyricsStyle.defaults());
    }

    private static LiveContent song(String text) {
        Slide s = new Slide("Verse", text, null);
        s.setSongId(7L);
        StyleOverrides o = new StyleOverrides();
        o.backgroundType = BackgroundType.COLOR;
        o.backgroundValue = "#2040C0"; // the song theme's own blue background
        o.backgroundMode = com.jamc.worship.model.BackgroundMode.OVERRIDE;
        return LiveContent.snapshot(s, o.applyTo(SlideStyle.fallback()), "Worthy");
    }

    /** A camera filling the picture (a red layer standing in for it) with a lower-third lyrics layer above. */
    private static Scene cameraAndLyrics() {
        Scene scene = new Scene(3L, "Camera + Lyrics");
        Source lyrics = new Source("Lyrics", SourceType.LYRICS);
        lyrics.getConfig().lyricsLayout = "LOWER_THIRD"; // chosen in Edit Scene
        scene.getSources().add(lyrics);
        Source cam = new Source("Camera", SourceType.COLOR);
        cam.getConfig().color = "#C03020";
        scene.getSources().add(cam);
        return scene;
    }

    private static Compositor program() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        c.setProgramLyrics(true);
        return c;
    }

    @Test
    void defaultsAreTheBroadcastLowerThird() {
        ProgramLyricsStyle d = ProgramLyricsStyle.defaults();
        assertEquals(ProgramLyricsStyle.Mode.LOWER_THIRD, d.mode());
        assertEquals(42, d.fontSize());
        assertTrue(d.bold() && d.uppercase());
        assertEquals("#FFFFFF", d.textColor());
        assertEquals("#000000", d.barColor());
        assertEquals(0.75f, d.barOpacity(), 1e-6);
        assertEquals(2, d.maxLines());
        assertEquals(0.08f, d.bottomMargin(), 1e-6);
        assertEquals(12, d.padV());
        assertEquals(24, d.padH());
        assertEquals(8, d.lineSpacing());
        assertEquals(400, d.changeMs());
    }

    @Test
    void programShowsSeparateTranslucentBarsAtTheBottomOverTheCamera() {
        FrameSpec spec = new FrameSpec(song("Worthy of ev'ry breath\nWe could ever breathe"), true, true, false, cameraAndLyrics());
        BufferedImage img = program().render(spec, 1920, 1080, false);
        assertEquals(CAMERA, new Color(img.getRGB(960, 300)), "the camera stays visible above the lyrics");
        assertEquals(CAMERA, new Color(img.getRGB(30, 1000)), "and beside the bars");
        // walk up the middle from the bottom: camera, bar, gap (camera), bar, camera
        List<String> runs = runs(img, 400);
        assertEquals(List.of("camera", "bar", "camera", "bar", "camera"), runs, "one bar per line, with a gap between them");
        // a bar = 75 % black over the red camera
        Color bar = new Color(img.getRGB(400, barRow(img, 400)));
        assertEquals(Math.round(0xC0 * 0.25), bar.getRed(), 3);
        assertTrue(brightestIn(img, 860, 1060, barRow(img, 400) - 30, barRow(img, 400) + 30) > 240, "white text in the bar");
        assertTrue(barRow(img, 400) > 1080 * 0.7, "near the bottom");
    }

    @Test
    void previewAndTheProjectorKeepTheirFullScreenLyrics() {
        FrameSpec spec = new FrameSpec(song("Worthy of ev'ry breath\nWe could ever breathe"), true, true, false, cameraAndLyrics());
        Compositor projector = new Compositor(new SlideRenderer(new ImageCache(null))); // Program, but not a stream surface
        BufferedImage img = projector.render(spec, 1920, 1080, false);
        assertEquals(List.of("camera"), runs(img, 400).stream().distinct().toList(), "no bars on the projector");
        assertTrue(brightestIn(img, 200, 1700, 400, 680) > 240, "big words in the middle as before");
    }

    /** Each scene picks its lyrics layout in Edit Scene; Preview shows the same as Program; the projector never changes. */
    @Test
    void eachSceneChoosesItsOwnLyricsLayout() {
        String words = "Worthy of ev'ry breath\nWe could ever breathe";
        Scene full = cameraAndLyrics();
        full.getSources().getFirst().getConfig().lyricsLayout = null; // nothing chosen in Edit Scene
        assertEquals(List.of("camera"), runs(program().render(new FrameSpec(song(words), true, true, false, full), 1920, 1080, false), 400)
                .stream().distinct().toList(), "a Lyrics layer is Full lyrics by default: no bars on Program");
        full.getSources().getFirst().getConfig().lyricsLayout = "OFF"; // older saved value: also Full lyrics
        assertEquals(List.of("camera"), runs(program().render(new FrameSpec(song(words), true, true, false, full), 1920, 1080, false), 400)
                .stream().distinct().toList());

        Scene lower = cameraAndLyrics(); // this scene chose Lower third
        FrameSpec spec = new FrameSpec(song(words), true, true, false, lower);
        assertEquals(List.of("camera", "bar", "camera", "bar", "camera"), runs(program().render(spec, 1920, 1080, false), 400));

        // Preview: exactly what TAKE puts on Program
        Compositor preview = new Compositor(new SlideRenderer(new ImageCache(null)), true);
        preview.setProgramLyrics(true);
        assertEquals(List.of("camera", "bar", "camera", "bar", "camera"), runs(preview.render(spec, 1920, 1080, false), 400));
        // the projector keeps the full lyrics
        assertEquals(List.of("camera"), runs(new Compositor(new SlideRenderer(new ImageCache(null))).render(spec, 1920, 1080, false), 400)
                .stream().distinct().toList());
        // the scene editor's sample words (no song) show the layout too
        Compositor editor = new Compositor(new SlideRenderer(new ImageCache(null)));
        editor.setEditorMode(true);
        editor.setProgramLyrics(true);
        FrameSpec sample = new FrameSpec(LiveContent.snapshot(new Slide("Sample", words, null), SlideStyle.fallback(), "Sample"),
                true, true, false, lower);
        assertEquals(List.of("camera", "bar", "camera", "bar", "camera"), runs(editor.render(sample, 1920, 1080, false), 400));
    }

    /**
     * Separate Layouts: Preview keeps a full-lyrics scene, Program a camera scene with a lower third. TAKE sends only
     * the lyrics; Program keeps its scene and draws them as bars over the camera, Preview as full lyrics.
     */
    @Test
    void separateLayoutsGivePreviewAndProgramTheirOwnLyricsSetup() {
        String words = "Worthy of ev'ry breath\nWe could ever breathe";
        Scene fullLyrics = cameraAndLyrics();
        fullLyrics.setId(2L);
        fullLyrics.getSources().getFirst().getConfig().lyricsLayout = "OFF";
        Scene cameraLowerThird = cameraAndLyrics();
        cameraLowerThird.setId(9L);
        cameraLowerThird.getSources().getFirst().getConfig().lyricsLayout = "LOWER_THIRD";

        ProgramOutput preview = new ProgramOutput();
        ProgramOutput live = new ProgramOutput();
        preview.setScene(fullLyrics, false);
        live.setScene(cameraLowerThird, false);
        LiveContent card = song(words);
        preview.take(card);
        live.take(card, null); // a content TAKE: Program keeps its own scene
        assertEquals(9L, live.scene().getId());

        Compositor previewMonitor = new Compositor(new SlideRenderer(new ImageCache(null)), true);
        previewMonitor.setProgramLyrics(true);
        BufferedImage p = previewMonitor.render(preview.shown(), 1920, 1080, false);
        assertEquals(List.of("camera"), runs(p, 400).stream().distinct().toList(), "Preview: full lyrics, no bars");
        assertTrue(brightestIn(p, 200, 1700, 400, 680) > 240, "big words in the middle of Preview");
        BufferedImage l = program().render(live.shown(), 1920, 1080, false);
        assertEquals(List.of("camera", "bar", "camera", "bar", "camera"), runs(l, 400), "Program: the same lyrics as a lower third");
    }

    @Test
    void transparentOverlayNeverPaintsTheSongBackgroundOverTheScene() {
        Scene scene = new Scene(2L, "Lyrics and Bible");
        Source lyrics = new Source("Lyrics", SourceType.LYRICS);
        lyrics.getConfig().lyricsLayout = "LOWER_THIRD";
        scene.getSources().add(lyrics);
        scene.getSources().add(new Source("Media", SourceType.BACKGROUND));
        FrameSpec spec = new FrameSpec(song("Worthy"), true, true, false, scene);
        assertEquals(new Color(0x2040C0), new Color(new Compositor(new SlideRenderer(new ImageCache(null)))
                .render(spec, 192, 108, false).getRGB(5, 5)), "the projector shows the song's background");
        assertNotEquals(new Color(0x2040C0), new Color(program().render(spec, 192, 108, false).getRGB(5, 5)),
                "the stream does not: the lyrics are an overlay");
        var opaque = ProgramLyricsStyle.defaults();
        ProgramLyricsStyle.apply(cfg, new ProgramLyricsStyle(opaque.mode(), opaque.fontFamily(), opaque.fontSize(), true, true,
                "#FFFFFF", opaque.align(), "#000000", 0.75f, 0, 0.08f, 8, 12, 24, 2, 0.66f, 0.9f, 0, false, false,
                opaque.fade(), 400, false));
        assertEquals(new Color(0x2040C0), new Color(program().render(spec, 192, 108, false).getRGB(5, 5)),
                "transparent overlay off: the song background is drawn again");
    }

    @Test
    void editingADraftNeverChangesTheLiveProgramUntilApplied(@TempDir Path other) {
        ProgramLyricsStyle draft = new ProgramLyricsStyle(ProgramLyricsStyle.Mode.CENTERED, "Segoe UI", 60, true, false,
                "#FFFF00", null, "#000000", 0.5f, 0, 0.1f, 8, 12, 24, 3, 0.5f, 0.9f, 8, true, true,
                ProgramLyricsStyle.Fade.CUT, 0, true);
        ProgramLyricsStyle.saveDraft(cfg, draft);
        assertEquals(ProgramLyricsStyle.defaults(), ProgramLyricsStyle.applied(), "Save Settings does not touch Program");
        assertEquals(draft, ProgramLyricsStyle.draft(cfg));
        ProgramLyricsStyle.apply(cfg, draft);
        assertEquals(draft, ProgramLyricsStyle.applied());
        // restored after a restart
        AppConfig reread = new AppConfig(cfg.paths());
        reread.load();
        ProgramLyricsStyle.install(reread);
        assertEquals(draft, ProgramLyricsStyle.applied());
    }

    @Test
    void longCardsAreReflowedNeverCut() {
        ProgramLyricsStyle s = ProgramLyricsStyle.defaults();
        String card = "I'm in awe of how You love me\nNow I'm standing here in Your presence\nWorthy of ev'ry song\nWe could ever sing";
        LowerThirdLyrics.Layout l = LowerThirdLyrics.layout(card, s, 1920, 1080);
        assertTrue(l.lines().size() <= 4);
        String shown = String.join(" ", l.lines()).replaceAll("\\s+", " ");
        assertEquals(card.toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", " "), shown, "every word is shown, in order");
        assertTrue(l.lines().size() <= 2 || l.font().getSize2D() < 42, "fewer lines, or smaller, to approach 2 lines");
        // 720p scales with the picture
        assertEquals(28f, LowerThirdLyrics.layout("Worthy", s, 1280, 720).font().getSize2D(), 0.5f);
    }

    @Test
    void programFadesItsLyricsOnTheirOwnClockEvenUnderACut() {
        ProgramOutput p = new ProgramOutput();
        p.setTransition(Transition.CUT, 0);
        p.setLyricsOverlayFade(400);
        p.take(song("A"), cameraAndLyrics());
        p.take(song("B"), null);
        assertEquals(1f, p.progress(), "the projector cuts at once");
        assertTrue(p.overlayProgress() < 1f, "the stream's lower third fades over 400 ms");
        assertTrue(p.previous() != null);
        ProgramOutput preview = new ProgramOutput();
        preview.setTransition(Transition.CUT, 0);
        preview.take(song("A"), cameraAndLyrics());
        preview.take(song("B"), null);
        assertEquals(1f, preview.overlayProgress(), "Preview has no Program lyrics fade");
        assertFalse(preview.previous() != null);
    }

    // ------------------------------------------------------------------ helpers

    /** What the column x shows from the bottom up, as runs of "camera" / "bar". */
    private static List<String> runs(BufferedImage img, int x) {
        String[] last = {null};
        return Arrays.stream(new int[img.getHeight()]).boxed().toList().isEmpty() ? List.of() : java.util.stream.IntStream
                .iterate(img.getHeight() - 1, y -> y >= 0, y -> y - 1)
                .mapToObj(y -> new Color(img.getRGB(x, y)).equals(CAMERA) ? "camera" : "bar")
                .filter(k -> {
                    boolean change = !k.equals(last[0]);
                    last[0] = k;
                    return change;
                }).toList();
    }

    private static int barRow(BufferedImage img, int x) {
        for (int y = img.getHeight() - 1; y >= 0; y--) {
            if (!new Color(img.getRGB(x, y)).equals(CAMERA)) {
                return y - 5;
            }
        }
        return -1;
    }

    private static int brightestIn(BufferedImage img, int x0, int x1, int y0, int y1) {
        int best = 0;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
            for (int x = x0; x < x1; x++) {
                Color c = new Color(img.getRGB(x, y));
                best = Math.max(best, Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue())));
            }
        }
        return best;
    }
}
