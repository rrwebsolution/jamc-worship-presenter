package com.jamc.worship.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The Windows file / folder dialog can be built on this PC (it is never shown by the test). */
class NativeFileDialogsTest {

    @TempDir
    Path dir;

    @Test
    void theWindowsDialogIsAvailable() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        assertTrue(NativeFileDialogs.selfTest(dir.toFile()), "open / folder / save dialog set up, start folder read back");
    }
}
