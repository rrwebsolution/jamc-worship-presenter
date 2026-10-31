package com.jamc.worship.ui;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.service.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings → Branding: custom name and logo are saved, kept after a restart, and reset to the default. */
class BrandingManagerTest {

    @TempDir
    Path dir;

    @Test
    void customNameAndLogoAreSavedAndReset() throws Exception {
        AppPaths paths = new AppPaths(dir.resolve("home"));
        paths.ensureDirectories();
        AppConfig config = new AppConfig(paths);
        BrandingManager b = new BrandingManager(config, paths);
        assertEquals("JAMC Worship Presenter", b.applicationName());
        assertNull(b.customLogo());
        assertTrue(b.isDefault());

        // a wide red logo picked from somewhere else on the computer
        Path picked = dir.resolve("jamc-logo.png");
        BufferedImage wide = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = wide.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 400, 200);
        g.dispose();
        ImageIO.write(wide, "png", picked.toFile());

        AtomicInteger changes = new AtomicInteger();
        b.addListener(changes::incrementAndGet);
        b.save("  JAMC Tagoloan Worship Presenter ", "JAMC Tagoloan", picked, false);
        assertEquals(1, changes.get(), "the UI is told once, so the header and title update at once");
        assertEquals("JAMC Tagoloan Worship Presenter", b.applicationName());
        assertEquals("JAMC Tagoloan", b.shortName());
        Path stored = paths.root().resolve("branding").resolve("app-logo.png");
        assertTrue(Files.isRegularFile(stored), "the logo is copied into the app's own data");
        Files.delete(picked);

        // "restart": a new manager on the saved settings still has the name and the copied logo
        BrandingManager reopened = new BrandingManager(config, paths);
        assertEquals("JAMC Tagoloan Worship Presenter", reopened.applicationName());
        assertNotNull(reopened.customLogo());

        // icons keep the 2:1 shape: transparent above and below, red in the middle
        BufferedImage icon = (BufferedImage) reopened.icon(64);
        assertEquals(64, icon.getWidth());
        assertEquals(0, icon.getRGB(32, 4) >>> 24, "padding, not a stretched logo");
        assertEquals(0xFF0000, icon.getRGB(32, 32) & 0xFFFFFF);
        assertEquals(BrandingManager.ICON_SIZES.length, reopened.windowIcons().size());

        assertThrows(ValidationException.class, () -> reopened.save("   ", null, null, false));
        assertThrows(ValidationException.class, () -> reopened.save("x".repeat(61), null, null, false));

        reopened.resetToDefault();
        assertEquals("JAMC Worship Presenter", reopened.applicationName());
        assertEquals("JAMC Worship", reopened.shortName());
        assertNull(reopened.customLogo());
        assertFalse(Files.exists(stored));
        assertTrue(new BrandingManager(config, paths).isDefault());
    }
}
