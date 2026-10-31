package com.jamc.worship.media;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Video A muted at 35 % with loop: after Lyrics, Bible, another video and a restart it is still muted,
 * 35 % and looping; video B keeps its own settings.
 */
class MediaStatesTest {

    @TempDir
    Path dir;

    /** A player stand-in that only keeps what it was told. */
    private static final class FakePlayer implements Transport {
        boolean muted;
        int volume = 100;
        boolean loop;

        @Override public void playOrResume() { }
        @Override public void pause() { }
        @Override public void stop() { }
        @Override public void seek(float position) { }
        @Override public void setVolume(int v) { volume = v; }
        @Override public int volume() { return volume; }
        @Override public void setMuted(boolean m) { muted = m; }
        @Override public boolean isMuted() { return muted; }
        @Override public void setLoop(boolean l) { loop = l; }
        @Override public boolean isLoop() { return loop; }
        @Override public boolean isPlaying() { return false; }
        @Override public long time() { return 0; }
        @Override public long length() { return 0; }
        @Override public boolean hasMedia() { return true; }
        @Override public void addStateListener(Runnable listener) { }
    }

    @Test
    void eachMediaKeepsItsMuteVolumeAndLoop() {
        AppPaths paths = new AppPaths(dir.resolve("home"));
        paths.ensureDirectories();
        AppConfig config = new AppConfig(paths);
        MediaStates states = new MediaStates(config);
        MediaStates.State defaults = new MediaStates.State(false, 100, false);
        String a = MediaStates.mediaKey("videos/a.mp4");
        String b = MediaStates.mediaKey("videos/b.mp4");

        // the operator mutes video A, sets 35 % and turns loop on (through the transport controls)
        FakePlayer player = new FakePlayer();
        MediaStates.apply(player, states.get(a, defaults));
        Transport controls = states.remember(player, a);
        controls.setMuted(true);
        controls.setVolume(35);
        controls.setLoop(true);

        // Lyrics, Bible …, then video B on the same player: B has its own (default) state
        FakePlayer reused = player;
        MediaStates.apply(reused, states.get(b, defaults));
        assertFalse(reused.muted);
        assertEquals(100, reused.volume);
        states.remember(reused, b).setVolume(80);

        // back to A: muted, 35 %, loop on — not reset to the defaults
        MediaStates.apply(reused, states.get(a, defaults));
        assertTrue(reused.muted);
        assertEquals(35, reused.volume);
        assertTrue(reused.loop);

        // and after a restart (the settings are kept on disk)
        config.save();
        MediaStates reopened = new MediaStates(config);
        assertEquals(new MediaStates.State(true, 35, true), reopened.get(a, defaults));
        assertEquals(new MediaStates.State(false, 80, false), reopened.get(b, defaults));
        assertEquals(defaults, reopened.get(MediaStates.mediaKey("never-touched.mp4"), defaults));
        assertTrue(states.remember(player, a) instanceof MediaStates.Remembered r && r.delegate() == player,
                "the transport UI can still reach the real player");
    }
}
