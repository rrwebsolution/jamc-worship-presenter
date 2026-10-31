package com.jamc.worship.bible;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Bible ID pasted where the API.Bible key belongs is recognised (it always fails as a key). */
class ApiKeyCheckTest {

    @Test
    void bibleIdsAreNotApiKeys() {
        assertTrue(OnlineBibleProviders.looksLikeBibleId("78a9f6124f344018-01"));
        assertTrue(OnlineBibleProviders.looksLikeBibleId(" 967dd4c018fe7970-01 "));
        assertFalse(OnlineBibleProviders.looksLikeBibleId("0123456789abcdef0123456789abcdef"), "a real key");
        assertFalse(OnlineBibleProviders.looksLikeBibleId(""));
        assertFalse(OnlineBibleProviders.looksLikeBibleId(null));
    }
}
