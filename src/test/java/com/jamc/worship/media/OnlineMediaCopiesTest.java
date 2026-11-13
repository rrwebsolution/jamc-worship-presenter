package com.jamc.worship.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Saved copies of Pixabay videos: names, playing from the copy, Download keeps a copy for good. */
class OnlineMediaCopiesTest {

    @TempDir
    Path dir;

    private final long limit = OnlineMedia.copiesLimitBytes;

    @AfterEach
    void restore() {
        OnlineMedia.copiesLimitBytes = limit;
        OnlineMedia.installCopies(null);
    }

    @Test
    void aCopyIsNamedAfterThePixabayAddressAndOtherAddressesAreNeverCopied() {
        OnlineMedia.installCopies(dir);
        assertEquals(dir.resolve("2025-09-13_303680_small.mp4"),
                OnlineMedia.copyOf("https://cdn.pixabay.com/video/2025/09/13/303680_small.mp4"));
        assertEquals(dir.resolve("2015-08-07_5-135665794_large.mp4"),
                OnlineMedia.copyOf("https://cdn.pixabay.com/video/2015/08/07/5-135665794_large.mp4"));
        assertNull(OnlineMedia.copyOf("https://example.com/video.mp4"));
    }

    @Test
    void anAddressWithACopyPlaysFromThisComputer() throws Exception {
        OnlineMedia.installCopies(dir);
        String url = "https://cdn.pixabay.com/video/2025/09/13/303680_small.mp4";
        assertFalse(OnlineMedia.hasCopy(url));
        assertEquals(url, OnlineMedia.playable(url));
        Files.write(dir.resolve("2025-09-13_303680_small.mp4"), new byte[]{1, 2, 3});
        assertTrue(OnlineMedia.hasCopy(url));
        assertEquals(dir.resolve("2025-09-13_303680_small.mp4").toString(), OnlineMedia.playable(url));
        assertEquals("C:\\videos\\a.mp4", OnlineMedia.playable("C:\\videos\\a.mp4"), "files are untouched");
    }

    @Test
    void downloadKeepsACopyForGoodWhileTheOldestAutomaticCopiesMakeRoom() throws Exception {
        OnlineMedia.installCopies(dir);
        String downloaded = "https://cdn.pixabay.com/video/2025/01/01/1_small.mp4";
        Path pinned = dir.resolve("2025-01-01_1_small.mp4");
        Path oldAuto = dir.resolve("2025-01-02_2_small.mp4");
        Path newAuto = dir.resolve("2025-01-03_3_small.mp4");
        for (Path p : new Path[]{pinned, oldAuto, newAuto}) {
            Files.write(p, new byte[1000]);
        }
        Files.setLastModifiedTime(pinned, FileTime.fromMillis(1_000)); // the oldest of all
        Files.setLastModifiedTime(oldAuto, FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(newAuto, FileTime.fromMillis(3_000));

        // Download of a video already copied: kept for good, reported done at once (no internet needed)
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        OnlineMedia.download(downloaded, done::complete);
        assertTrue(done.get(5, TimeUnit.SECONDS));
        assertTrue(Files.readAllLines(dir.resolve("downloaded.txt")).contains("2025-01-01_1_small.mp4"));

        // over the limit: the oldest automatic copy goes, the downloaded one stays although it is older still
        OnlineMedia.copiesLimitBytes = 2_000;
        OnlineMedia.trimCopies(newAuto);
        assertTrue(Files.exists(pinned), "downloaded by the operator: never removed to make room");
        assertFalse(Files.exists(oldAuto), "the least recently used automatic copy makes room");
        assertTrue(Files.exists(newAuto));
        SwingUtilities.invokeAndWait(() -> { }); // let the callback's Swing task finish
    }
}
