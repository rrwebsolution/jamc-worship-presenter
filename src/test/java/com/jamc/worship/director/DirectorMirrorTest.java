package com.jamc.worship.director;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectorMirrorTest {

    @Test
    void mirrorIsKeptPerCamera(@TempDir Path dir) {
        AppPaths paths = new AppPaths(dir);
        paths.ensureDirectories();
        DirectorSettings s = new DirectorSettings(new AppConfig(paths));
        s.setMirrored("Iriun Webcam", true);
        assertTrue(s.mirrored("Iriun Webcam"));
        assertFalse(s.mirrored("Iriun Webcam #2"), "another camera is not flipped");
        s.setMirrored("Iriun Webcam", false);
        assertFalse(s.mirrored("Iriun Webcam"));
    }

}
