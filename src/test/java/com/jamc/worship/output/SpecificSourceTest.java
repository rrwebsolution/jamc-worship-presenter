package com.jamc.worship.output;

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

import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADD SOURCE → SPECIFIC FILE: a lyrics source fixed to one song never shows another song. */
class SpecificSourceTest {

    private static final SlideStyle STYLE = new SlideStyle(BackgroundType.COLOR, "#000000", "#000000", 0, "Dialog", 200,
            true, false, "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);

    private static LiveContent song(long songId, String text) {
        Slide slide = new Slide(text, text, null);
        slide.setSongId(songId);
        return LiveContent.snapshot(slide, STYLE, "Song " + songId);
    }

    private static Scene lyricsScene(Long fixedSong) {
        Scene s = new Scene(1L, "Lyrics");
        Source lyrics = new Source("Lyrics", SourceType.LYRICS);
        lyrics.getConfig().songId = fixedSong;
        lyrics.getConfig().fixedSong = fixedSong == null ? null : Boolean.TRUE;
        s.getSources().add(lyrics);
        return s;
    }

    private static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private static boolean blank(BufferedImage img) {
        return Arrays.stream(pixels(img)).allMatch(p -> (p & 0xFFFFFF) == 0);
    }

    @Test
    void aSpecificSongKeepsItsOwnLyricsWhenAnotherSongIsSent() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        Scene fixed = lyricsScene(1L);
        LiveContent amazing = song(1L, "AMAZING");
        LiveContent other = song(2L, "XXXXXXXXXX");

        BufferedImage before = c.render(new FrameSpec(other, true, true, false, fixed), 192, 108, false);
        assertTrue(blank(before), "another song never shows in a source fixed to song 1");

        BufferedImage own = c.render(new FrameSpec(amazing, true, true, false, fixed), 192, 108, false);
        assertFalse(blank(own), "its own song shows");
        BufferedImage after = c.render(new FrameSpec(other, true, true, false, fixed), 192, 108, false);
        assertTrue(Arrays.equals(pixels(own), pixels(after)), "clicking another song keeps this song's last slide");
    }

    private static LiveContent presentationSlide(long presentationId) {
        Slide slide = new Slide("Slide", "", null);
        slide.setPresentation(presentationId, 0);
        return LiveContent.snapshot(slide, STYLE, "Deck " + presentationId);
    }

    private static Source presentation(Long id, com.jamc.worship.model.SourceConfig.PresentationMode mode) {
        Source s = new Source("Presentation", SourceType.PRESENTATION);
        s.getConfig().presentationId = id;
        s.getConfig().presentationMode = mode;
        return s;
    }

    @Test
    void aSpecificPresentationOnlyTakesItsOwnFileAndAlwaysWinsOverFromLibrary() {
        var fixed = com.jamc.worship.model.SourceConfig.PresentationMode.FIXED;
        var dynamic = com.jamc.worship.model.SourceConfig.PresentationMode.DYNAMIC;
        Scene onlySermon = new Scene(1L, "Sermon only");
        Source sermon = presentation(7L, fixed);
        onlySermon.getSources().add(sermon);
        assertTrue(Compositor.presentationSlot(onlySermon, presentationSlide(7L)) == sermon);
        assertTrue(Compositor.presentationSlot(onlySermon, presentationSlide(9L)) == null, "another file is not taken");
        assertTrue(Compositor.hidesPresentation(new FrameSpec(presentationSlide(9L), true, true, false, onlySermon), null),
                "and is not shown full screen either");

        Scene both = new Scene(2L, "Both");
        Source any = presentation(null, dynamic);
        Source specific = presentation(7L, fixed);
        both.getSources().add(any); // the dynamic one is on top
        both.getSources().add(specific);
        assertTrue(Compositor.presentationSlot(both, presentationSlide(7L)) == specific, "the specific source wins its file");
        assertTrue(Compositor.presentationSlot(both, presentationSlide(9L)) == any, "from library takes the others");
    }

    @Test
    void aFromLibrarySourceShowsWhateverSongIsSent() {
        Compositor c = new Compositor(new SlideRenderer(new ImageCache(null)));
        Scene dynamic = lyricsScene(null);
        BufferedImage a = c.render(new FrameSpec(song(1L, "AMAZING"), true, true, false, dynamic), 192, 108, false);
        BufferedImage b = c.render(new FrameSpec(song(2L, "XXXXXXXXXX"), true, true, false, dynamic), 192, 108, false);
        assertFalse(Arrays.equals(pixels(a), pixels(b)), "dynamic lyrics follow the song sent");
    }
}
