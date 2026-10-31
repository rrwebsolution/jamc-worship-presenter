package com.jamc.worship.bible;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Response parsing for the licensed APIs, using placeholder text shaped like each API's output
 * (no real Bible text). Live calls need the church's own API keys and are not part of the build.
 */
class OnlineBibleProvidersTest {

    @Test
    void parsesEsvVerseMarkers() throws Exception {
        String json = "{\"query\":\"John 3\",\"passages\":[\"  [1] Placeholder one.  [2] Placeholder two\\n  continues. [3] Three.\"]}";
        List<BibleProvider.Verse> v = OnlineBibleProviders.parseEsv(json, 43, 3);
        assertEquals(3, v.size());
        assertEquals(2, v.get(1).verse());
        assertEquals("Placeholder two continues.", v.get(1).text());
        assertEquals(43, v.get(0).book());
    }

    @Test
    void parsesNltVerseExportsAndDropsNotesAndNumbers() {
        String html = "<h2 class=\"subhead\">Heading</h2>"
                + "<verse_export orig=\"john_3_16\" bk_id=\"43\" ch=\"3\" vn=\"16\"><span class=\"vn\">16</span>"
                + "Placeholder &ldquo;quoted&rdquo; text<a class=\"a-tn\">*</a><span class=\"tn\">a translator note</span>.</verse_export>"
                + "<verse_export orig=\"john_3_17\" vn=\"17\"><span class=\"vn\">17</span>Next  placeholder.</verse_export>";
        List<BibleProvider.Verse> v = OnlineBibleProviders.parseNlt(html, 43, 3);
        assertEquals(2, v.size());
        assertEquals(16, v.getFirst().verse());
        assertEquals("Placeholder “quoted” text.", v.getFirst().text());
        assertEquals("Next placeholder.", v.get(1).text());
    }

    @Test
    void parsesApiBibleChapterContent() throws Exception {
        String json = "{\"data\":{\"id\":\"JHN.3\",\"content\":\"     [1] Placeholder a [2] Placeholder b \"}}";
        List<BibleProvider.Verse> v = OnlineBibleProviders.parseApiBible(json, 43, 3);
        assertEquals(List.of("Placeholder a", "Placeholder b"), v.stream().map(BibleProvider.Verse::text).toList());
    }

    @Test
    void keepsCombinedVersesAsTheirOwnVerse() throws Exception {
        String json = "{\"data\":{\"content\":\" [31] Placeholder a [32-33] Placeholder b [34] Placeholder c\"}}";
        List<BibleProvider.Verse> v = OnlineBibleProviders.parseApiBible(json, 43, 3);
        assertEquals(List.of(31, 32, 34), v.stream().map(BibleProvider.Verse::verse).toList());
        assertEquals("Placeholder a", v.getFirst().text());
    }

    @Test
    void providersExplainMissingKeysAndIds() {
        BibleVersion niv = new BibleVersion(1L, "NIV", "New International Version", "English",
                BibleVersion.Provider.API_BIBLE, null, true, null, 0);
        BibleProvider noKey = OnlineBibleProviders.apiBible(p -> null, "https://example.invalid");
        assertNotNull(noKey.problem(niv));
        BibleProvider withKey = OnlineBibleProviders.apiBible(p -> "key", "https://example.invalid");
        assertEquals("Enter the API.Bible Bible ID for NIV in Bible Versions.", withKey.problem(niv));
        assertNull(withKey.problem(niv.withProvider(BibleVersion.Provider.API_BIBLE, null, "abc123", true)));
        assertNotNull(OnlineBibleProviders.esv(p -> " ").problem(niv));
        assertNull(OnlineBibleProviders.nlt(p -> "k").problem(niv));
    }
}
