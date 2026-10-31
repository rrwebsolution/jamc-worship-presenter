package com.jamc.worship.output;

import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramOutputTest {

    private static final SlideStyle RED_BG = new SlideStyle(BackgroundType.COLOR, "#FF0000", "#FF0000", 0, "Dialog", 200,
            true, false, "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);

    private ProgramOutput program;

    @BeforeEach
    void setUp() {
        program = new ProgramOutput();
        program.setTransition(Transition.CUT, 0);
    }

    private LiveContent content(String text) {
        return LiveContent.snapshot(new Slide("Verse 1", text, null), RED_BG, "Song");
    }

    @Test
    void takePutsContentOnProgram() {
        assertFalse(program.isShowingContent());
        program.take(content("Hello"));
        assertTrue(program.isShowingContent());
        assertEquals("Hello", program.shown().content().slide().getText());
        assertTrue(program.shown().text());
    }

    @Test
    void takenSlideIsASnapshot() {
        Slide editable = new Slide("x", "before", null);
        program.take(LiveContent.snapshot(editable, RED_BG, "Deck"));
        editable.setText("after");
        assertEquals("before", program.shown().content().slide().getText(), "editing after TAKE must not change Program");
    }

    @Test
    void blackOverridesAndToggles() {
        program.take(content("Hello"));
        program.toggleBlack();
        assertTrue(program.isBlack());
        assertFalse(program.isShowingContent());
        program.take(content("Next"));
        assertTrue(program.isBlack(), "black stays on across takes until the operator turns it off");
        program.toggleBlack();
        assertTrue(program.isShowingContent());
        assertEquals("Next", program.shown().content().slide().getText());
    }

    @Test
    void blankHidesTextUntilNextTake() {
        program.take(content("Hello"));
        program.toggleBlank();
        assertFalse(program.shown().text());
        assertTrue(program.shown().background());
        program.take(content("Next"));
        assertFalse(program.isBlank(), "blank is one-shot");
        assertTrue(program.shown().text());
    }

    @Test
    void lyricsAndBackgroundSwitchesPersistAcrossTakes() {
        program.toggleLyrics();
        program.toggleBackground();
        program.take(content("Hello"));
        assertFalse(program.shown().text());
        assertFalse(program.shown().background());
        program.take(content("Next"));
        assertFalse(program.shown().text(), "lyrics stay off until switched back");
        program.toggleLyrics();
        assertTrue(program.shown().text());
    }

    @Test
    void transitionsTrackPreviousFrameAndCutIsImmediate() {
        program.take(content("A"));
        assertNull(program.previous(), "cut has no transition");
        assertEquals(1f, program.progress());

        program.setTransition(Transition.CROSSFADE, 2000);
        FrameSpec before = program.shown();
        program.take(content("B"));
        assertEquals(before, program.previous());
        assertEquals(Transition.CROSSFADE, program.activeTransition());
        assertTrue(program.progress() < 1f, "a 2 s crossfade has just started");
    }

    @Test
    void listenersAreNotified() {
        AtomicInteger calls = new AtomicInteger();
        program.addListener(calls::incrementAndGet);
        program.take(content("A"));
        program.toggleBlack();
        assertTrue(calls.get() >= 2);
    }

    @Test
    void frameRendererAppliesSwitches(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        SlideRenderer renderer = new SlideRenderer(new ImageCache(dir));
        LiveContent c = content("WWWWWW");

        BufferedImage normal = FrameRenderer.render(renderer, new FrameSpec(c, true, true, false, null), 320, 180);
        assertEquals(Color.RED.getRGB(), normal.getRGB(2, 2));
        assertTrue(hasWhite(normal), "text visible");

        BufferedImage black = FrameRenderer.render(renderer, new FrameSpec(c, true, true, true, null), 320, 180);
        assertEquals(Color.BLACK.getRGB(), black.getRGB(160, 90));
        assertFalse(hasWhite(black));

        BufferedImage noText = FrameRenderer.render(renderer, new FrameSpec(c, false, true, false, null), 320, 180);
        assertEquals(Color.RED.getRGB(), noText.getRGB(160, 90));
        assertFalse(hasWhite(noText), "text hidden");

        BufferedImage noBg = FrameRenderer.render(renderer, new FrameSpec(c, true, false, false, null), 320, 180);
        assertEquals(Color.BLACK.getRGB(), noBg.getRGB(2, 2), "background hidden");
        assertTrue(hasWhite(noBg));

        assertNotNull(FrameRenderer.render(renderer, FrameSpec.EMPTY, 10, 10));
    }

    private static boolean hasWhite(BufferedImage img) {
        int y = img.getHeight() / 2;
        for (int x = 0; x < img.getWidth(); x++) {
            if (img.getRGB(x, y) == Color.WHITE.getRGB()) {
                return true;
            }
        }
        return false;
    }
}
