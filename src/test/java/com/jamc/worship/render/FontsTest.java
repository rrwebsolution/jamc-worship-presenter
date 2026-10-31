package com.jamc.worship.render;

import com.jamc.worship.model.FontWeight;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FontsTest {

    @Test
    void addedFontsAreCopiedAndUsable(@TempDir Path dir) throws Exception {
        Path source = Path.of("C:\\Windows\\Fonts\\georgia.ttf");
        assumeTrue(Files.isRegularFile(source), "needs a Windows font to import");
        Path fonts = dir.resolve("fonts");
        Fonts.loadUserFonts(fonts);
        List<String> added = Fonts.importFonts(List.of(source));
        assertTrue(added.contains("Georgia"));
        assertTrue(Files.isRegularFile(fonts.resolve("georgia.ttf")));
        assertTrue(Fonts.families().contains("Georgia"));

        Path notAFont = Files.writeString(dir.resolve("lyrics.txt"), "Amazing grace");
        assertThrows(java.io.IOException.class, () -> Fonts.importFonts(List.of(notAFont)));
    }

    @Test
    void thicknessChoicesFollowTheInstalledWeights() {
        assumeTrue(Fonts.isInstalled("Segoe UI Semibold"), "Windows ships Segoe UI in several weights");
        var weights = Fonts.realWeights("Segoe UI");
        assertTrue(weights.contains(FontWeight.SEMIBOLD));
        assertTrue(weights.contains(FontWeight.BLACK));
        assertTrue(weights.contains(FontWeight.BOLD));
        assertFalse(Fonts.realWeights("Georgia").contains(FontWeight.BLACK) && !Fonts.isInstalled("Georgia Black"));
    }
}
