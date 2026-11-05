package com.jamc.worship.bible;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.TypographySettings;
import com.jamc.worship.model.ContentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BibleDesignTest {

    @TempDir
    Path tempDir;

    @Test
    void everyDesignIsRecognisedAfterSavingAndReloading() {
        AppPaths paths = new AppPaths(tempDir);
        paths.ensureDirectories();
        TypographySettings typo = new TypographySettings(new AppConfig(paths));
        for (BibleDesign d : BibleDesign.values()) {
            typo.set(ContentKind.BIBLE, d.typography());
            AppConfig fresh = new AppConfig(paths);
            fresh.load();
            TypographySettings reloaded = new TypographySettings(fresh);
            assertEquals(d, BibleDesign.matching(reloaded.get(ContentKind.BIBLE), d.referencePosition(),
                    d.uppercaseReference(), d.showVersion()), d.label());
        }
    }

    @Test
    void referenceAndVersionCanBeJoinedAboveOrBelow() {
        var top = BibleDisplay.ReferencePosition.TOP;
        assertEquals(java.util.Arrays.asList("JOHN 3:16", "NIV"),
                java.util.Arrays.asList(BibleDisplay.headingAndCaption(top, "JOHN 3:16", "NIV")));
        assertEquals(java.util.Arrays.asList("JOHN 3:16   ·   NIV", null), java.util.Arrays.asList(
                BibleDisplay.headingAndCaption(BibleDisplay.ReferencePosition.TOP_JOINED, "JOHN 3:16", "NIV")));
        assertEquals(java.util.Arrays.asList(null, "JOHN 3:16   ·   NIV"), java.util.Arrays.asList(
                BibleDisplay.headingAndCaption(BibleDisplay.ReferencePosition.BOTTOM, "JOHN 3:16", "NIV")));
        assertEquals(java.util.Arrays.asList("JOHN 3:16", null), java.util.Arrays.asList(
                BibleDisplay.headingAndCaption(BibleDisplay.ReferencePosition.TOP_JOINED, "JOHN 3:16", null)),
                "version switched off: the reference alone");
    }

    @Test
    void designsLookDifferentAndAHandChangeIsCustom() {
        Set<Object> looks = new HashSet<>();
        for (BibleDesign d : BibleDesign.values()) {
            looks.add(d.typography());
        }
        assertEquals(BibleDesign.values().length, looks.size(), "no two designs are the same");
        BibleDesign d = BibleDesign.CLASSIC;
        assertNull(BibleDesign.matching(d.typography().withFontSize(70), d.referencePosition(), d.uppercaseReference(),
                d.showVersion()));
        assertTrue(BibleDesign.LOWER_THIRD.typography().position() == com.jamc.worship.model.TextPosition.BOTTOM);
    }
}
