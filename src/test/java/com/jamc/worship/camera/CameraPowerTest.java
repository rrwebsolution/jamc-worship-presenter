package com.jamc.worship.camera;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraPowerTest {

    @Test
    void camerasStartOffAndTheSwitchIsSavedAndShared(@TempDir Path dir) {
        CameraPower power = new CameraPower(new AppConfig(new AppPaths(dir)));
        AtomicInteger changes = new AtomicInteger();
        power.addListener(changes::incrementAndGet);
        assertFalse(power.isOn("Iriun Webcam"), "every camera starts off");

        power.set("Iriun Webcam", true);
        power.set("Iriun Webcam", true); // already on: no second change
        assertTrue(power.isOn("Iriun Webcam"));
        assertEquals(1, changes.get());
        assertTrue(reopened(dir).isOn("Iriun Webcam"), "kept after a restart");

        power.set("Iriun Webcam", false);
        assertFalse(power.isOn("Iriun Webcam"));
        assertFalse(reopened(dir).isOn("Iriun Webcam"));
        assertEquals(2, changes.get());
    }

    private static CameraPower reopened(Path dir) {
        AppConfig config = new AppConfig(new AppPaths(dir));
        config.load();
        return new CameraPower(config);
    }
}
