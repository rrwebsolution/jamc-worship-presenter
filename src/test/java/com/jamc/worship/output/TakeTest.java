package com.jamc.worship.output;

import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Preview → TAKE → Program: Preview has its own state; TAKE applies all of it in one transition. */
class TakeTest {

    private static LiveContent slide(String text) {
        return LiveContent.snapshot(new Slide(text, text, null), SlideStyle.fallback(), "Song");
    }

    @Test
    void previewNeverChangesProgramUntilTake() {
        ProgramOutput program = new ProgramOutput();
        ProgramOutput preview = new ProgramOutput();
        LiveContent chorus = slide("Chorus");
        program.take(chorus);
        preview.take(slide("Verse 2"));
        assertSame(chorus, program.content(), "selecting Verse 2 only changes Preview");

        LiveContent verse2 = preview.content();
        program.take(verse2, null);
        assertSame(verse2, program.content());
    }

    @Test
    void contentAndSceneGoLiveTogetherInOneTransition() {
        ProgramOutput program = new ProgramOutput();
        program.setTransition(Transition.FADE, 500);
        Scene worship = new Scene(1L, "Worship");
        Scene camera = new Scene(2L, "Camera");
        program.setScene(worship, false);
        program.take(slide("Verse 1"));
        FrameSpec before = program.shown();

        LiveContent next = slide("Verse 2");
        program.take(next, camera);
        assertSame(next, program.content());
        assertEquals("Camera", program.scene().getName());
        assertSame(before, program.previous(), "one transition from the old frame, not two");

        program.take(null, worship); // a scene alone keeps the content
        assertSame(next, program.content());
        assertEquals("Worship", program.scene().getName());
    }

    @Test
    void blackIsAnOverrideThatReturnsWithoutAnotherTake() {
        ProgramOutput program = new ProgramOutput();
        LiveContent chorus = slide("Chorus");
        program.take(chorus);
        program.setBlack(true);
        assertTrue(program.isBlack());
        assertSame(chorus, program.content(), "Program is still the chorus underneath");
        program.setBlack(false);
        assertSame(chorus, program.shown().content());
    }

    @Test
    void previewComposesAServiceVideoAsAStillCardNotTheProgramVideo() {
        Compositor previewCompositor = new Compositor(new SlideRenderer(new ImageCache(null)), true);
        FrameSpec spec = new FrameSpec(LiveContent.video("C:/none/announcement.mp4", "Announcement"), true, true, false, null);
        var img = previewCompositor.render(spec, 160, 90, false);
        assertNotNull(img);
        assertNotEquals(Color.BLACK.getRGB(), img.getRGB(2, 2), "the preview card is drawn");
        assertNull(new ProgramOutput().content());
    }
}
