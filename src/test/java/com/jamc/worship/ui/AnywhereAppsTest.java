package com.jamc.worship.ui;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Next slide from any app" keys are taken only in the listed programs, so a browser still types "-" or →. */
class AnywhereAppsTest {

    @TempDir
    Path dir;

    private Shortcuts shortcuts() {
        AppPaths paths = new AppPaths(dir.resolve("home"));
        paths.ensureDirectories();
        return new Shortcuts(new AppConfig(paths));
    }

    @Test
    void byDefaultOnlyWpsAndPowerPointNeverABrowser() {
        Shortcuts s = shortcuts();
        assertTrue(s.anywhereApp("wps.exe"));
        assertTrue(s.anywhereApp("WPP.EXE"));
        assertTrue(s.anywhereApp("powerpnt.exe"));
        assertFalse(s.anywhereApp("chrome.exe"));
        assertFalse(s.anywhereApp("msedge.exe"));
        assertFalse(s.anywhereApp(null));
    }

    @Test
    void theListCanBeChangedAndIsTidied() {
        Shortcuts s = shortcuts();
        s.setAnywhereAppsText(" C:\\Program Files\\Foxit\\FoxitPDFReader.exe ; wps , wps.exe");
        assertEquals("FoxitPDFReader.exe, wps.exe", s.anywhereAppsText());
        assertTrue(s.anywhereApp("foxitpdfreader.exe"));
        assertFalse(s.anywhereApp("POWERPNT.EXE"));
        assertEquals(List.of(), Shortcuts.parseApps(" , "));
    }
}
