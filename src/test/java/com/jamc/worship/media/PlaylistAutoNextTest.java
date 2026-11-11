package com.jamc.worship.media;

import com.jamc.worship.model.SourceConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Video playlists: Autoplay (play the next video when one ends) is on unless turned off; it survives a copy (undo). */
class PlaylistAutoNextTest {

    @Test
    void autoplayIsOnByDefaultAndCanBeTurnedOff() {
        SourceConfig c = new SourceConfig();
        assertTrue(SceneVideos.autoNext(c));
        c.autoNext = Boolean.FALSE;
        assertFalse(SceneVideos.autoNext(c));
        assertFalse(SceneVideos.autoNext(c.copy()), "kept by copy (scene editor undo / save)");
    }
}
