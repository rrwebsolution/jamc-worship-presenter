package com.jamc.worship.media;

import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraAudioTest {

    @Test
    void anAudioInputIsHeardWhileVisibleACameraOnlyWithAudioOn() {
        Source input = new Source("Mixer", SourceType.AUDIO_INPUT);
        assertTrue(CameraAudio.wantsAudio(input));
        input.setVisible(false);
        assertFalse(CameraAudio.wantsAudio(input));

        Source camera = new Source("CAM 1", SourceType.CAMERA);
        assertFalse(CameraAudio.wantsAudio(camera));
        camera.getConfig().audioEnabled = true;
        assertTrue(CameraAudio.wantsAudio(camera));
    }

    @Test
    void aCopiedSourceKeepsItsAudioInput() {
        SourceConfig c = new SourceConfig();
        c.audioInput = "Line In (USB Mixer)";
        assertEquals("Line In (USB Mixer)", c.copy().audioInput);
    }
}
