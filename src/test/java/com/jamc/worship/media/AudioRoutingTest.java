package com.jamc.worship.media;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Video sources are VIDEO + AUDIO by default; Preview never doubles Program's sound; the mixer scales media. */
class AudioRoutingTest {

    @TempDir
    Path dir;

    private AudioRouting routing() {
        AppPaths paths = new AppPaths(dir.resolve("home"));
        paths.ensureDirectories();
        return new AudioRouting(new AppConfig(paths));
    }

    @Test
    void aSceneVideoHasSoundUnlessAudioIsUnticked() {
        SourceConfig c = new SourceConfig();
        assertFalse(SceneVideos.silent(c), "a video on Program is heard by default");
        assertTrue(SceneVideos.loops(c), "older looping scene videos keep looping");
        c.muted = true;
        assertTrue(SceneVideos.silent(c));
        c.muted = null;
        c.audioEnabled = false;
        assertTrue(SceneVideos.silent(c), "Audio unticked in the Scene editor");
        c.loop = false;
        assertFalse(SceneVideos.loops(c), "library videos added to a scene play once");
    }

    @Test
    void vlcPaddingBelowThePictureIsNotPartOfTheFrame() {
        java.awt.Dimension hd = new java.awt.Dimension(1280, 720);
        assertEquals(hd, VideoPlayer.visibleSize(hd, 1280, 738), "measured: a 720p video arrives as 1280x738");
        assertEquals(new java.awt.Dimension(1920, 1080), VideoPlayer.visibleSize(new java.awt.Dimension(1920, 1080), 1920, 1090));
        assertEquals(hd, VideoPlayer.visibleSize(hd, 1280, 720), "no padding: nothing changes");
        assertEquals(null, VideoPlayer.visibleSize(null, 1280, 738), "not known yet");
        assertEquals(new java.awt.Dimension(720, 1280), VideoPlayer.visibleSize(new java.awt.Dimension(1280, 720), 720, 1280),
                "not a smaller picture in the buffer (rotated): the whole buffer is kept");
    }

    @Test
    void whileRecordingThePreviewMonitorIsSilentAndComesBackAfter() throws Exception {
        AudioRouting routing = routing();
        routing.setPreviewMonitor(true);
        assertTrue(routing.previewMonitor());

        routing.setProgramOnly(true);
        assertFalse(routing.previewMonitor(), "Windows captures the whole app, so Preview's sound would be recorded");

        routing.setProgramOnly(false);
        assertTrue(routing.previewMonitor(), "the operator's switch is kept");
    }

    @Test
    void theSameSourceIsOneVideoAcrossPreviewProgramAndTheEditor() {
        Source a = new Source("Announcement", SourceType.VIDEO);
        a.setId(7L);
        a.getConfig().path = "media/announcement.mp4";
        Source copy = a.copy();
        assertEquals(SceneVideos.key(a), SceneVideos.key(copy), "a scene copy reuses the running player");
        Source resaved = a.copy();
        resaved.setId(99L); // saving a scene gives its sources new ids: the live video must not restart
        assertEquals(SceneVideos.key(a), SceneVideos.key(resaved));
        Source other = new Source("Announcement (copy)", SourceType.VIDEO);
        other.setId(8L);
        other.getConfig().path = "media/announcement.mp4";
        assertNotEquals(SceneVideos.key(a), SceneVideos.key(other), "a second layer of the same file is its own video");
    }

    @Test
    void mixerAndDefaults() {
        AudioRouting r = routing();
        assertEquals("", r.programDevice(), "no choice = Windows default output, never silent");
        assertEquals("", r.programDeviceInUse());
        assertFalse(r.previewMonitor(), "Preview audio monitoring is off by default");
        assertEquals(AudioRouting.TakeBehavior.RESTART, r.takeBehavior(), "a taken video starts from the beginning");
        assertEquals(100, r.programVolume(100));

        r.setMediaVolume(50);
        r.setMasterVolume(80);
        assertEquals(40, r.programVolume(100), "source × media × master");
        assertEquals(60, r.programVolume(150));
        r.setMasterMuted(true);
        assertEquals(0, r.mediaGain(), 0.0001);
        r.setMasterMuted(false);
        r.setMediaMuted(true);
        assertEquals(0, r.programVolume(100));

        // a chosen device that is not connected falls back to the Windows default (no silent failure)
        r.setProgramDevice("{gone-headphones}");
        assertEquals("{gone-headphones}", r.programDevice());
        assertTrue(r.available("{gone-headphones}"), "unknown until devices are listed");
        r.setTakeBehavior(AudioRouting.TakeBehavior.CONTINUE);
        assertEquals(AudioRouting.TakeBehavior.CONTINUE, r.takeBehavior(), "remembered in settings");
    }
}
