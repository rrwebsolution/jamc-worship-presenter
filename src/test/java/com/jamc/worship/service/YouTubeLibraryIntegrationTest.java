package com.jamc.worship.service;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.LyricsSync;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.MediaSourceType;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongQuery;
import com.jamc.worship.repository.MediaRepository;
import com.jamc.worship.repository.SongRepository;
import com.jamc.worship.youtube.YouTubeVideo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two YouTube workflows stay apart: Videos → Import from YouTube creates a VIDEO reference;
 * Lyrics → Import from YouTube creates a normal LYRICS item that only remembers the video.
 */
class YouTubeLibraryIntegrationTest {

    @TempDir
    Path tempDir;

    private MediaService media;
    private SongService songs;
    /** Sample songs the migrations install. */
    private int seededSongs;

    @BeforeEach
    void setUp() {
        AppPaths paths = new AppPaths(tempDir.resolve("JAMCWorshipPresenter"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        media = new MediaService(new MediaRepository(db), paths);
        songs = new SongService(new SongRepository(db));
        seededSongs = songs.count();
    }

    @Test
    void youTubeVideoIsAVideoReferenceNotADownload() {
        YouTubeVideo v = new YouTubeVideo("M7lc1UVf-VE", "Goodness of God – Live", "Bethel Music", 360_000L,
                "https://i.ytimg.com/vi/M7lc1UVf-VE/hqdefault.jpg");
        MediaItem item = media.addYouTube(v);

        assertEquals(MediaSourceType.YOUTUBE, item.getSourceType());
        assertEquals(MediaKind.VIDEO, item.getKind());
        assertEquals(MediaCategory.VIDEO, item.getCategory());
        assertEquals("youtube:M7lc1UVf-VE", item.getPath());
        assertEquals("M7lc1UVf-VE", item.getYoutubeVideoId());
        assertEquals("Bethel Music", item.getChannelName());
        assertEquals(360_000L, item.getDurationMs());
        assertFalse(item.isCopied());
        // no file: resolving the reference never throws and never points at a real file
        assertFalse(Files.exists(media.resolve(item)));

        // adding the same video again keeps one library item
        assertEquals(item.getId(), media.addYouTube(v).getId());
        List<MediaItem> videos = media.find(List.of(MediaCategory.VIDEO), "", false);
        assertEquals(1, videos.size());
        // nothing appears in Lyrics
        assertEquals(seededSongs, songs.count());
    }

    @Test
    void lyricsKeepTheirSourceAndCardSync() {
        Song s = new Song();
        s.setTitle("Amazing Grace");
        s.setSections(LyricsParser.parse("Verse 1\nAmazing grace\n\nHow sweet the sound"));
        s.setSourceType(com.jamc.worship.model.LyricsSourceType.AUTHORIZED_PROVIDER);
        s.setSourceProvider("LRCLIB (synced lyrics)");
        s.setSourceReference("lrclib:42");
        Song saved = songs.save(s);
        LyricsSync sync = new LyricsSync();
        sync.type = LyricsSync.Type.LOCAL_AUDIO;
        sync.reference = "C:\\Music\\amazing-grace.mp3";
        sync.cards.put("Verse 1/0", 15_200L);
        sync.total = 2;
        songs.setSync(saved.getId(), sync);

        Song loaded = songs.load(saved.getId()).orElseThrow();
        assertEquals(com.jamc.worship.model.LyricsSourceType.AUTHORIZED_PROVIDER, loaded.getSourceType());
        assertEquals("lrclib:42", loaded.getSourceReference());
        assertEquals(LyricsSync.Type.LOCAL_AUDIO, loaded.getSync().type);
        assertEquals(15_200L, loaded.getSync().startOf("Verse 1/0"));
        assertEquals(LyricsSync.Status.PARTIAL, loaded.getSync().summaryStatus());

        // editing the lyrics keeps where they came from
        loaded.setSourceType(null);
        songs.save(loaded);
        assertEquals(com.jamc.worship.model.LyricsSourceType.AUTHORIZED_PROVIDER, songs.load(saved.getId()).orElseThrow().getSourceType());
    }

    @Test
    void lyricsImportedFromYouTubeStayTextLyrics() {
        MediaItem video = media.addYouTube(new YouTubeVideo("M7lc1UVf-VE", "Goodness of God – Live", "Bethel Music", null, null));

        Song s = new Song();
        s.setTitle("Goodness of God");
        s.setAuthor("Bethel Music");
        s.setSections(LyricsParser.parse("Verse 1\nI love You, Lord\n\nChorus\nAll my life You have been faithful"));
        s.setYoutubeVideoId("M7lc1UVf-VE");
        s.setYoutubeUrl("https://www.youtube.com/watch?v=M7lc1UVf-VE");
        s.setYoutubeMediaId(video.getId());
        LyricsSync sync = new LyricsSync();
        sync.videoId = "M7lc1UVf-VE";
        sync.sections.put("Verse 1", 12_000L);
        sync.sections.put("Chorus", 31_000L);
        s.setSync(sync);
        Song saved = songs.save(s);

        Song loaded = songs.load(saved.getId()).orElseThrow();
        assertEquals(2, loaded.getSections().size());
        assertEquals("I love You, Lord", loaded.getSections().getFirst().getContent());
        assertEquals("M7lc1UVf-VE", loaded.getYoutubeVideoId());
        assertEquals(video.getId(), loaded.getYoutubeMediaId());
        assertEquals(31_000L, loaded.getSync().sections.get("Chorus"));
        // the list shows "YouTube Sync Available" from the summary rows
        assertTrue(songs.search(new SongQuery("Goodness", false, null)).getFirst().hasYouTubeReference());

        // editing the lyrics (the song editor's save) keeps the reference
        loaded.setSections(LyricsParser.parse("Verse 1\nI love You, Lord\nOh Your mercy never fails me"));
        loaded.setYoutubeVideoId(null); // the editor does not carry the reference
        songs.save(loaded);
        Song edited = songs.load(saved.getId()).orElseThrow();
        assertEquals("M7lc1UVf-VE", edited.getYoutubeVideoId());
        assertEquals(1, edited.getSections().size());

        // the two items are separate: the video is still only in the Video Library
        assertEquals(1, media.find(List.of(MediaCategory.VIDEO), "", false).size());
        assertEquals(seededSongs + 1, songs.count());

        // removing the reference leaves the lyrics alone
        songs.setYouTubeReference(saved.getId(), null, null, null);
        Song plain = songs.load(saved.getId()).orElseThrow();
        assertNull(plain.getYoutubeVideoId());
        assertNull(plain.getSync());
        assertEquals(1, plain.getSections().size());
    }
}
