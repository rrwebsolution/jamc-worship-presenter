package com.jamc.worship.youtube;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YouTubeRefTest {

    private static final Optional<String> ID = Optional.of("M7lc1UVf-VE");

    @Test
    void readsEveryCommonLinkForm() {
        assertEquals(ID, YouTubeRef.parse("https://www.youtube.com/watch?v=M7lc1UVf-VE"));
        assertEquals(ID, YouTubeRef.parse("https://www.youtube.com/watch?list=PL1&v=M7lc1UVf-VE&t=42s"));
        assertEquals(ID, YouTubeRef.parse("youtube.com/watch?v=M7lc1UVf-VE"));
        assertEquals(ID, YouTubeRef.parse("https://youtu.be/M7lc1UVf-VE?si=abc"));
        assertEquals(ID, YouTubeRef.parse("https://m.youtube.com/watch?v=M7lc1UVf-VE"));
        assertEquals(ID, YouTubeRef.parse("https://music.youtube.com/watch?v=M7lc1UVf-VE"));
        assertEquals(ID, YouTubeRef.parse("https://www.youtube.com/embed/M7lc1UVf-VE"));
        assertEquals(ID, YouTubeRef.parse("https://www.youtube.com/shorts/M7lc1UVf-VE"));
        assertEquals(ID, YouTubeRef.parse("https://www.youtube.com/live/M7lc1UVf-VE?feature=share"));
        assertEquals(ID, YouTubeRef.parse("https://www.youtube-nocookie.com/embed/M7lc1UVf-VE"));
        assertEquals(ID, YouTubeRef.parse("  M7lc1UVf-VE  "));
    }

    @Test
    void rejectsWordsAndOtherSites() {
        assertTrue(YouTubeRef.parse("Goodness of God Bethel Music").isEmpty());
        assertTrue(YouTubeRef.parse("https://vimeo.com/M7lc1UVf-VE").isEmpty());
        assertTrue(YouTubeRef.parse("https://www.youtube.com/@BethelMusic").isEmpty());
        assertTrue(YouTubeRef.parse(null).isEmpty());
    }

    @Test
    void storedPathsAreNeverFiles() {
        String path = YouTubeRef.path("M7lc1UVf-VE");
        assertEquals("youtube:M7lc1UVf-VE", path);
        assertTrue(YouTubeRef.is(path));
        assertEquals("M7lc1UVf-VE", YouTubeRef.id(path));
        assertFalse(YouTubeRef.is("C:\\Videos\\Intro.mp4"));
        assertNull(YouTubeRef.id("media/videos/intro.mp4"));
    }

    @Test
    void durationsFromTheDataApi() {
        assertEquals(253_000L, YouTubeClient.parseDuration("PT4M13S"));
        assertEquals(3_723_000L, YouTubeClient.parseDuration("PT1H2M3S"));
        assertNull(YouTubeClient.parseDuration("P0D")); // live stream
        assertNull(YouTubeClient.parseDuration("nonsense"));
    }

    @Test
    void guessesTitleAndArtistFromTheVideo() {
        SongTitleGuess.Guess g = SongTitleGuess.from("Goodness of God - Bethel Music (Official Lyric Video)", "Bethel Music");
        assertEquals("Goodness of God", g.title());
        assertEquals("Bethel Music", g.artist());

        g = SongTitleGuess.from("CeCe Winans - Goodness of God (Live)", "CeCeWinansVEVO");
        assertEquals("Goodness of God", g.title());
        assertEquals("CeCe Winans", g.artist());

        g = SongTitleGuess.from("Way Maker [Official Audio]", "Sinach - Topic");
        assertEquals("Way Maker", g.title());
        assertEquals("Sinach", g.artist());
    }

    @Test
    void playerErrorsAreExplained() {
        assertTrue(YouTubePlayer.errorText(150).contains("does not allow"));
        assertTrue(YouTubePlayer.errorText(100).contains("removed"));
    }
}
