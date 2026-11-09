package com.jamc.worship.output;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.LyricsTransitionSettings;
import com.jamc.worship.config.LyricsTransitionSettings.Easing;
import com.jamc.worship.config.LyricsTransitionSettings.Mode;
import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spotify-style lyrics: layout, scrolling poses, Program timing, rapid NEXT, and what reaches the picture. */
class LyricsReelTest {

    private static final SlideStyle STYLE = new SlideStyle(BackgroundType.COLOR, "#000000", "#000000", 0, "Dialog", 72,
            true, false, "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);
    private static final List<String> SONG = List.of("AMAZING GRACE\nHOW SWEET THE SOUND", "THAT SAVED A WRETCH\nLIKE ME",
            "I ONCE WAS LOST\nBUT NOW AM FOUND", "", "WAS BLIND\nBUT NOW I SEE");

    private static LyricsTransitionSettings spotify(String highlight) {
        return new LyricsTransitionSettings(Mode.SPOTIFY, 600, 84, 0.30f, 56, highlight, TextAlign.CENTER, true, true,
                Easing.EASE_IN_OUT);
    }

    private static LiveContent card(int index, LyricsTransitionSettings s) {
        Slide slide = new Slide("Verse", SONG.get(index), null);
        slide.setSongId(5L);
        return LiveContent.snapshot(slide, STYLE, "Amazing Grace", new LyricsContext(5L, SONG, index, s));
    }

    private static Scene lyricsOnly() {
        Scene s = new Scene(9L, "Lyrics");
        s.getSources().add(new Source("Lyrics", SourceType.LYRICS));
        Source black = new Source("Black", SourceType.COLOR);
        black.getConfig().color = "#000000";
        s.getSources().add(black);
        return s;
    }

    @Test
    void settingsAreSavedAndRestored(@TempDir Path dir) {
        AppConfig cfg = new AppConfig(new AppPaths(dir));
        assertEquals(Mode.NONE, LyricsTransitionSettings.load(cfg).mode(), "off by default: nothing changes for old setups");
        LyricsTransitionSettings s = new LyricsTransitionSettings(Mode.SPOTIFY, 900, 96, 0.25f, 40, "#FFD700",
                TextAlign.LEFT, false, true, Easing.EASE_OUT);
        s.save(cfg);
        AppConfig reread = new AppConfig(new AppPaths(dir));
        reread.load();
        assertEquals(s, LyricsTransitionSettings.load(reread));
        // out-of-range values are clamped
        assertEquals(LyricsTransitionSettings.MAX_DURATION, new LyricsTransitionSettings(Mode.SPOTIFY, 99_999, 84, 0.3f, 56,
                "#FFFFFF", TextAlign.CENTER, true, true, Easing.LINEAR).durationMs());
    }

    @Test
    void easingStartsAndEndsInPlace() {
        for (Easing e : Easing.values()) {
            assertEquals(0f, e.apply(0f), 1e-6);
            assertEquals(1f, e.apply(1f), 1e-6);
        }
        assertTrue(Easing.EASE_IN_OUT.apply(0.25f) < 0.25f && Easing.EASE_IN_OUT.apply(0.75f) > 0.75f);
    }

    @Test
    void restingStackHasPreviousAboveCurrentInTheMiddleAndNextBelow() {
        LiveContent c = card(1, spotify("#FFFFFF"));
        LyricsReel.Pose pose = LyricsReel.rest(c);
        LyricsReel.CardPose prev = pose.cards().get(0);
        LyricsReel.CardPose cur = pose.cards().get(1);
        LyricsReel.CardPose next = pose.cards().get(2);
        assertEquals(1f, cur.alpha());
        assertEquals(1f, cur.scale());
        assertTrue(cur.top() < 0 && cur.top() > -400, "current card centred on the middle");
        assertEquals(0.30f, prev.alpha(), 1e-6);
        assertEquals(0.30f, next.alpha(), 1e-6);
        assertEquals(LyricsReel.DIM_SCALE, prev.scale(), 1e-6);
        assertTrue(prev.top() < cur.top(), "previous above");
        assertTrue(next.top() > cur.top(), "next below");
        assertEquals(0f, pose.cards().get(3).alpha(), "two away: hidden");
    }

    @Test
    void showPreviousAndNextCanBeTurnedOff() {
        LyricsTransitionSettings s = new LyricsTransitionSettings(Mode.SPOTIFY, 600, 84, 0.3f, 56, "#FFFFFF",
                TextAlign.CENTER, false, false, Easing.EASE_IN_OUT);
        LyricsReel.Pose pose = LyricsReel.rest(card(1, s));
        assertEquals(0f, pose.cards().get(0).alpha());
        assertEquals(0f, pose.cards().get(2).alpha());
    }

    @Test
    void nextScrollsUpAndPreviousScrollsDown() {
        LyricsTransitionSettings s = spotify("#FFFFFF");
        LiveContent a = card(1, s);
        LiveContent b = card(2, s);
        assertTrue(LyricsReel.scrolls(a, b));
        LyricsReel.Pose start = LyricsReel.pose(a, b, null, 0f);
        LyricsReel.Pose mid = LyricsReel.pose(a, b, null, 0.5f);
        LyricsReel.Pose end = LyricsReel.pose(a, b, null, 1f);
        assertEquals(LyricsReel.rest(a).cards().get(1), start.cards().get(1));
        assertEquals(LyricsReel.rest(b).cards().get(2), end.cards().get(2));
        // NEXT: every card moves up, the old current dims, the new one brightens
        assertTrue(mid.cards().get(1).top() < start.cards().get(1).top());
        assertTrue(end.cards().get(2).top() < start.cards().get(2).top());
        assertTrue(mid.cards().get(1).alpha() < 1f && mid.cards().get(2).alpha() > 0.3f);
        // PREVIOUS: everything moves down
        LyricsReel.Pose back = LyricsReel.pose(b, a, null, 0.5f);
        assertTrue(back.cards().get(1).top() > LyricsReel.rest(b).cards().get(1).top());
    }

    @Test
    void anotherSongOrAFarJumpDoesNotScroll() {
        LyricsTransitionSettings s = spotify("#FFFFFF");
        Slide other = new Slide("Verse", "OTHER SONG", null);
        other.setSongId(6L);
        LiveContent otherSong = LiveContent.snapshot(other, STYLE, "Other", new LyricsContext(6L, List.of("OTHER SONG"), 0, s));
        assertFalse(LyricsReel.scrolls(card(0, s), otherSong));
        List<String> longSong = java.util.stream.IntStream.range(0, 12).mapToObj(i -> "LINE " + i).toList();
        Slide x = new Slide("V", "LINE 0", null);
        LiveContent first = LiveContent.snapshot(x, STYLE, "Long", new LyricsContext(5L, longSong, 0, s));
        LiveContent last = LiveContent.snapshot(x, STYLE, "Long", new LyricsContext(5L, longSong, 11, s));
        assertFalse(LyricsReel.scrolls(first, last), "a big jump crossfades");
        assertFalse(LyricsReel.scrolls(card(0, s.withMode(Mode.SMOOTH_FADE)), card(1, s.withMode(Mode.SMOOTH_FADE))));
    }

    @Test
    void programUsesTheLyricsDurationOnlyBetweenLyricsCards() {
        ProgramOutput p = new ProgramOutput();
        p.setTransition(Transition.FADE, 1000);
        Scene scene = lyricsOnly();
        p.take(card(0, spotify("#FFFFFF")), scene);
        p.take(card(1, spotify("#FFFFFF")), null);
        assertEquals(Transition.CROSSFADE, p.activeTransition());
        assertEquals(600, p.activeDurationMs());

        // a slide without a lyrics transition (Bible, announcement, NONE): Program's own transition
        p.take(LiveContent.snapshot(new Slide("John 3:16", "FOR GOD SO LOVED", null), STYLE, "Bible"), null);
        assertEquals(Transition.FADE, p.activeTransition());
        assertEquals(1000, p.activeDurationMs());

        // Blank keeps the Program transition too
        p.take(card(2, spotify("#FFFFFF")), null);
        p.toggleBlank();
        assertEquals(Transition.FADE, p.activeTransition());
    }

    @Test
    void rapidNextContinuesFromWhereTheCardsAre() {
        ProgramOutput p = new ProgramOutput();
        Scene scene = lyricsOnly();
        LyricsTransitionSettings s = spotify("#FFFFFF");
        p.take(card(0, s), scene);
        p.take(card(1, s), null);
        assertNull(p.reelStart(), "a scroll from rest starts at rest");
        p.take(card(2, s), null); // pressed again while the first scroll is still running
        assertNotNull(p.reelStart(), "the second scroll starts where the first one was");
    }

    @Test
    void theCurrentCardIsBrightAndHighlightedTheNeighboursDim() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        FrameSpec spec = new FrameSpec(card(1, spotify("#FF0000")), true, true, false, lyricsOnly());
        BufferedImage img = c.render(spec, 960, 540, false);
        int[] middle = brightest(img, 200, 300);
        int[] top = brightest(img, 0, 180);
        int[] bottom = brightest(img, 360, 540);
        assertTrue(middle[0] > 200 && middle[1] < 60, "current card in the highlight color (red)");
        assertTrue(top[1] > 30 && top[1] < 120, "previous card dimmed white above: " + top[1]);
        assertTrue(bottom[1] > 30 && bottom[1] < 120, "next card dimmed white below: " + bottom[1]);
    }

    /**
     * A 1080p scroll frame costs no more than the crossfade lyrics already used (measured side by side, so a slow or
     * throttled test machine slows both alike). On the developer PC: about 6 ms a frame, well inside 60 fps.
     */
    @Test
    void scrollFramesCostNoMoreThanTheOldCrossfade() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        Scene scene = lyricsOnly();
        LyricsTransitionSettings s = spotify("#FFD700");
        FrameSpec from = new FrameSpec(card(0, s), true, true, false, scene);
        FrameSpec to = new FrameSpec(card(1, s), true, true, false, scene);
        // the same words without a lyrics transition: the classic crossfade of two whole lyrics layers
        FrameSpec plainFrom = new FrameSpec(LiveContent.snapshot(new Slide("V", SONG.get(0), null), STYLE, "A"), true, true, false, scene);
        FrameSpec plainTo = new FrameSpec(LiveContent.snapshot(new Slide("V", SONG.get(1), null), STYLE, "A"), true, true, false, scene);
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        int frames = 36; // one 600 ms scroll at 60 fps
        double[] reel = new double[frames * 4];
        double[] plain = new double[frames * 4];
        for (int i = 0; i < frames * 6; i++) { // two scrolls of warm-up (pictures cached, code compiled), then four timed
            float p = (i % frames) / (float) frames;
            long t0 = System.nanoTime();
            Graphics2D g = img.createGraphics();
            c.paintLayered(g, 1920, 1080, from, to, null, false, Transition.CROSSFADE, p);
            g.dispose();
            long t1 = System.nanoTime();
            g = img.createGraphics();
            c.paintLayered(g, 1920, 1080, plainFrom, plainTo, null, false, Transition.CROSSFADE, p);
            g.dispose();
            long t2 = System.nanoTime();
            if (i >= frames * 2) {
                reel[i - frames * 2] = (t1 - t0) / 1e6;
                plain[i - frames * 2] = (t2 - t1) / 1e6;
            }
        }
        java.util.Arrays.sort(reel);
        java.util.Arrays.sort(plain);
        double reelMedian = reel[reel.length / 2];
        double plainMedian = plain[plain.length / 2];
        System.out.printf("1080p frame: Spotify-style scroll %.2f ms (90th %.2f), classic crossfade %.2f ms%n",
                reelMedian, reel[reel.length * 9 / 10], plainMedian);
        assertTrue(reelMedian <= plainMedian * 1.5, "the scroll must not be heavier than the crossfade: "
                + reelMedian + " vs " + plainMedian + " ms");
    }

    /** {max red, max green} in the rows y0..y1. */
    private static int[] brightest(BufferedImage img, int y0, int y1) {
        int r = 0;
        int g = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int rr = (rgb >> 16) & 0xFF;
                int gg = (rgb >> 8) & 0xFF;
                if (rr + gg > r + g) {
                    r = rr;
                    g = gg;
                }
            }
        }
        return new int[]{r, g};
    }
}
