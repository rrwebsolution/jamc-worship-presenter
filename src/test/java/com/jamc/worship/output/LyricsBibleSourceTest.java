package com.jamc.worship.output;

import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A scene's Lyrics source shows songs and Bible verses; its Bible source shows Bible verses only (user, 2026-11-13). */
class LyricsBibleSourceTest {

    private final Compositor compositor = new Compositor(new SlideRenderer(new ImageCache(null)));

    private static Scene scene(SourceType type) {
        Scene s = new Scene(1L, type.name());
        s.getSources().add(new Source(type.name(), type));
        return s;
    }

    private static LiveContent song() {
        Slide s = new Slide("Verse 1", "Amazing grace how sweet the sound", null);
        s.setSongId(7L);
        return LiveContent.snapshot(s, SlideStyle.fallback(), "Amazing Grace");
    }

    private static LiveContent verse() {
        Slide s = new Slide("John 3:16", "For God so loved the world", "JOHN 3:16");
        s.setBible(true);
        return LiveContent.snapshot(s, SlideStyle.fallback(), "John 3:16");
    }

    /** True when anything but black was drawn (the words). */
    private boolean showsWords(LiveContent content, Scene scene) {
        BufferedImage img = compositor.render(new FrameSpec(content, true, true, false, scene), 320, 180, false);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void aLyricsSourceShowsSongsAndBibleVerses() {
        assertTrue(showsWords(song(), scene(SourceType.LYRICS)), "song lyrics");
        assertTrue(showsWords(verse(), scene(SourceType.LYRICS)), "Bible verses too");
    }

    @Test
    void aBibleSourceShowsBibleVersesOnlyNeverLyrics() {
        assertTrue(showsWords(verse(), scene(SourceType.BIBLE)), "Bible verses");
        assertFalse(showsWords(song(), scene(SourceType.BIBLE)), "song lyrics never show in a Bible source");
    }

    @org.junit.jupiter.api.AfterEach
    void defaults() {
        Compositor.setTextSources(true, false);
    }

    /** Settings → Live & Scenes: both choices, in all four combinations. */
    @Test
    void whatEachSourceShowsIsASetting() {
        Compositor.setTextSources(false, false);
        assertTrue(showsWords(song(), scene(SourceType.LYRICS)));
        assertFalse(showsWords(verse(), scene(SourceType.LYRICS)), "Lyrics source: songs only");
        assertTrue(showsWords(verse(), scene(SourceType.BIBLE)));
        assertFalse(showsWords(song(), scene(SourceType.BIBLE)));

        Compositor.setTextSources(true, true);
        assertTrue(showsWords(verse(), scene(SourceType.LYRICS)));
        assertTrue(showsWords(song(), scene(SourceType.BIBLE)), "Bible source shows songs too");

        Compositor.setTextSources(false, true);
        assertFalse(showsWords(verse(), scene(SourceType.LYRICS)));
        assertTrue(showsWords(song(), scene(SourceType.BIBLE)));
        assertTrue(showsWords(verse(), scene(SourceType.BIBLE)), "its own kind always");
    }

    @Test
    void theBibleMarkSurvivesACopy() {
        assertTrue(verse().slide().copy().isBible());
        assertFalse(song().slide().copy().isBible());
    }
}
