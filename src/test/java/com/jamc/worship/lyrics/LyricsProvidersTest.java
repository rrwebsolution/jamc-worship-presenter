package com.jamc.worship.lyrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsProvidersTest {

    /** A provider that answers what it is given (or fails). */
    private record Fake(String id, LyricsProvider.Trust trust, List<LyricsResult> answer, boolean fail, Status status)
            implements LyricsProvider {
        @Override public String name() { return "Provider " + id; }
        @Override public String description() { return ""; }
        @Override public Set<Capability> capabilities() { return EnumSet.of(Capability.SEARCH, Capability.PLAIN_LYRICS); }
        @Override public List<LyricsResult> search(String query) throws IOException {
            if (fail) {
                throw new IOException("busy");
            }
            return answer;
        }
        @Override public LyricsResult fetch(LyricsResult r) { return r; }
    }

    private static LyricsResult result(String provider, LyricsProvider.Trust trust, String title, String artist, boolean timed) {
        return new LyricsResult(provider, "Provider " + provider, trust, null, title, artist, null,
                "Verse 1\nwords", timed ? "[00:01.00]words" : null, null,
                LyricsProviders.score("Goodness of God Bethel Music", title, artist));
    }

    @Test
    void fallsBackWhenAProviderHasNothingOrFails() {
        Fake empty = new Fake("a", LyricsProvider.Trust.COMMUNITY, List.of(), false, LyricsProvider.Status.CONNECTED);
        Fake broken = new Fake("b", LyricsProvider.Trust.COMMUNITY, List.of(), true, LyricsProvider.Status.CONNECTED);
        Fake found = new Fake("c", LyricsProvider.Trust.CHURCH,
                List.of(result("c", LyricsProvider.Trust.CHURCH, "Goodness of God", "Bethel Music", false)), false,
                LyricsProvider.Status.CONNECTED);
        Fake off = new Fake("d", LyricsProvider.Trust.COMMUNITY, List.of(), true, LyricsProvider.Status.DISABLED);
        LyricsProviders all = new LyricsProviders(List.of(empty, broken, found, off));

        LyricsProviders.Outcome o = all.search("Goodness of God Bethel Music", null);
        assertEquals(1, o.results().size());
        assertEquals(List.of("Provider a", "Provider b", "Provider c"), o.searched()); // the disabled one is never asked
        assertTrue(o.notes().stream().anyMatch(n -> n.contains("busy")));
        assertEquals(3, all.configured().size());
    }

    @Test
    void rankingPrefersLicensedThenConfidentThenTimed() {
        List<LyricsResult> found = new ArrayList<>();
        found.add(result("plain", LyricsProvider.Trust.COMMUNITY, "Goodness of God", "Bethel Music", false));
        found.add(result("timed", LyricsProvider.Trust.COMMUNITY, "Goodness of God", "Bethel Music", true));
        found.add(result("other", LyricsProvider.Trust.COMMUNITY, "Goodness", "Someone Else", true));
        found.add(result("lic", LyricsProvider.Trust.LICENSED, "Goodness of God (Live)", "Bethel Music", false));
        found.add(result("x", LyricsProvider.Trust.COMMUNITY, "Unrelated Song", "Nobody", true));
        List<LyricsResult> ranked = LyricsProviders.rank(found);
        assertEquals("lic", ranked.getFirst().providerId());
        // the same song from two providers is kept once: the timed copy
        assertTrue(ranked.stream().noneMatch(r -> r.providerId().equals("plain")));
        assertTrue(ranked.stream().noneMatch(r -> r.title().equals("Unrelated Song")));
        assertEquals("other", ranked.getLast().providerId());
    }

    @Test
    void lyricsFolderFindsTheChurchsFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bethel Music - Goodness of God.txt"), "Verse 1\nI love You Lord");
        Files.writeString(dir.resolve("track07.lrc"), "[ti:Way Maker]\n[ar:Sinach]\n[00:01.00]Way maker");
        LyricsFolderProvider p = new LyricsFolderProvider(dir::toString);
        assertEquals(LyricsProvider.Status.CONNECTED, p.status());

        List<LyricsResult> r = p.search("goodness of god");
        assertEquals(1, r.size());
        assertEquals("Goodness of God", r.getFirst().title());
        assertEquals("Bethel Music", r.getFirst().artist());
        assertEquals("Verse 1\nI love You Lord", p.fetch(r.getFirst()).plainLyrics());

        LyricsResult wm = p.search("Way Maker Sinach").getFirst();
        assertEquals("Way Maker", wm.title());
        assertTrue(p.fetch(wm).hasTimed());

        assertEquals(LyricsProvider.Status.NOT_CONFIGURED, new LyricsFolderProvider(() -> null).status());
    }

    @Test
    void lrclibResultsCarryPlainAndSyncedLyrics() throws IOException {
        LrcLibProvider p = new LrcLibProvider(() -> true, "test");
        String json = """
                [{"id":1,"trackName":"Goodness of God","artistName":"Bethel Music","duration":356.0,"instrumental":false,
                  "plainLyrics":"I love You Lord","syncedLyrics":"[00:15.20] I love You Lord"},
                 {"id":2,"trackName":"Goodness of God (Instrumental)","artistName":"X","instrumental":true,
                  "plainLyrics":null,"syncedLyrics":null}]
                """;
        List<LyricsResult> r = p.parse("Goodness of God", json);
        assertEquals(1, r.size());
        assertTrue(r.getFirst().hasTimed());
        assertEquals(356_000L, r.getFirst().durationMs());
        assertNotNull(r.getFirst().plainLyrics());
        assertFalse(new LrcLibProvider(() -> false, "test").status() == LyricsProvider.Status.CONNECTED);
    }
}
