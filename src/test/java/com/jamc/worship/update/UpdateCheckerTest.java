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
                  {"name":"LiveCat-Multimedia-0.1.2.exe","browser_download_url":"https://x/setup.exe","size":107443200}]}
                """;
        UpdateChecker.Release r = UpdateChecker.parse(json).orElseThrow();
        assertEquals("0.1.2", r.version());
        assertEquals("https://x/setup.exe", r.installerUrl());
        assertEquals(107443200, r.size());
        assertTrue(UpdateChecker.parse("{\"tag_name\":\"v1\",\"assets\":[]}").isEmpty(), "no installer: nothing to offer");
        assertEquals("", r.notes(), "an older release without the What's new part");
    }

    @Test
    void whatsNewComesFromTheReleaseText() throws Exception {
        String json = """
                {"tag_name":"v0.1.20","body":"## What's new\\r\\n<!-- whats-new:start -->\\r\\n## Audio\\r\\n- **Mixer** always on screen\\r\\n<!-- whats-new:end -->\\r\\n\\r\\n**Install:** run it",
                 "assets":[{"name":"LiveCat-Multimedia-0.1.20.exe","browser_download_url":"https://x/setup.exe","size":1}]}
                """;
        UpdateChecker.Release r = UpdateChecker.parse(json).orElseThrow();
        assertEquals("## Audio\n- **Mixer** always on screen", r.notes());
        assertEquals("<h3>Audio</h3><ul><li><b>Mixer</b> always on screen</li></ul>", WhatsNew.toHtml(r.notes()));
    }

    @Test
    void whatsNewIsShownOnlyAfterAnUpdate() {
        assertTrue(WhatsNew.justUpdated("0.1.19", "0.1.20"));
        assertFalse(WhatsNew.justUpdated(null, "0.1.20"), "first install: nothing changed for this operator");
        assertFalse(WhatsNew.justUpdated("0.1.20", "0.1.20"), "only once");
        assertFalse(WhatsNew.justUpdated("0.1.19", null), "development copy");
    }

    @Test
    void theBundledNotesHaveNoTitleOrComments() {
        String notes = WhatsNew.bundled();
        assertFalse(notes.isBlank(), "src/main/resources/whats-new.md is inside the app");
        assertFalse(notes.contains("<!--"));
        assertFalse(notes.startsWith("# "));
        assertEquals("<p>a &lt;b&gt; &amp; <code>x</code></p>", WhatsNew.toHtml("a <b> & `x`"));
    }
}
