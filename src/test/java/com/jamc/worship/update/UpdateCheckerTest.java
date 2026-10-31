package com.jamc.worship.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    @Test
    void versionsCompareAsNumbers() {
        assertTrue(UpdateChecker.isNewer("0.1.2", "0.1.1"));
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.9"), "10 > 9, not text order");
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"));
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.1.1"));
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.1"));
        assertFalse(UpdateChecker.isNewer(null, "0.1.1"));
    }

    @Test
    void theReleaseInstallerIsFound() throws Exception {
        String json = """
                {"tag_name":"v0.1.2","assets":[
                  {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
                  {"name":"JAMC-Worship-Presenter-0.1.2.exe","browser_download_url":"https://x/setup.exe","size":107443200}]}
                """;
        UpdateChecker.Release r = UpdateChecker.parse(json).orElseThrow();
        assertEquals("0.1.2", r.version());
        assertEquals("https://x/setup.exe", r.installerUrl());
        assertEquals(107443200, r.size());
        assertTrue(UpdateChecker.parse("{\"tag_name\":\"v1\",\"assets\":[]}").isEmpty(), "no installer: nothing to offer");
    }
}
