package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.MediaSourceType;
import com.jamc.worship.repository.MediaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** A downloaded Pixabay video goes straight to Library → Videos → Local (user, 2026-11-13). */
class DownloadedPixabayTest {

    @TempDir
    Path home;

    @Test
    void aDownloadIsALocalVideoInTheVideosListOnceOnly() throws Exception {
        AppPaths paths = new AppPaths(home);
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        MediaService media = new MediaService(new MediaRepository(db), paths);
        AtomicInteger told = new AtomicInteger();
        media.addChangeListener(told::incrementAndGet);

        Path file = paths.dataDir().resolve("pixabay").resolve("videos").resolve("2025-09-13_303680_small.mp4");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[2048]);

        MediaItem m = media.addDownloaded(file, "Worship Lights", "Pixabay user", 1920, 1080, 8_000L);
        assertEquals(MediaSourceType.LOCAL, m.getSourceType(), "a file on this computer: the Local tab");
        assertEquals(MediaCategory.VIDEO, m.getCategory());
        assertEquals(MediaKind.VIDEO, m.getKind());
        assertEquals("Worship Lights", m.getName());
        assertEquals("Pixabay user", m.getChannelName(), "the creator's credit is kept");
        assertTrue(media.exists(m), "plays from the downloaded file, offline");
        assertEquals(file.toAbsolutePath().normalize(), media.resolve(m).toAbsolutePath().normalize(),
                "the same file: not stored twice");
        assertEquals(1, told.get(), "the library lists are told to show it");

        List<MediaItem> videos = media.find(List.of(MediaCategory.VIDEO), null, false);
        assertEquals(1, videos.size());
        assertFalse(videos.getFirst().isPixabay());

        MediaItem again = media.addDownloaded(file, "Worship Lights", "Pixabay user", 1920, 1080, 8_000L);
        assertEquals(m.getId(), again.getId(), "Download pressed again: the same item, not a second one");
        assertEquals(1, media.find(List.of(MediaCategory.VIDEO), null, false).size());
    }
}
