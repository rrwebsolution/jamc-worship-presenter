package com.jamc.worship.pixabay;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.media.OnlineMedia;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.MediaSourceType;
import com.jamc.worship.repository.MediaRepository;
import com.jamc.worship.service.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Pixabay Videos API client against a fake network, and Pixabay videos in the library (streamed, never files). */
class PixabayClientTest {

    private static final String KEY = "12345-secretkey";

    /** The shape of the example answer in the Pixabay API documentation. */
    static String answer(long id, int totalHits) {
        return """
                {"total": 4692, "totalHits": %d, "hits": [ {
                  "id": %d, "pageURL": "https://pixabay.com/videos/id-%d/", "type": "film",
                  "tags": "clouds, sky, heaven", "duration": 12,
                  "videos": {
                    "large":  {"url": "https://cdn.pixabay.com/video/2015/08/08/%d-135736646_large.mp4", "width": 3840, "height": 2160, "size": 6615235, "thumbnail": "https://cdn.pixabay.com/video/2015/08/08/%d-135736646_large.jpg"},
                    "medium": {"url": "https://cdn.pixabay.com/video/2015/08/08/%d-135736646_medium.mp4", "width": 1920, "height": 1080, "size": 3562083, "thumbnail": "https://cdn.pixabay.com/video/2015/08/08/%d-135736646_medium.jpg"},
                    "small":  {"url": "https://cdn.pixabay.com/video/2015/08/08/%d-135736646_small.mp4", "width": 1280, "height": 720, "size": 1827315, "thumbnail": "https://cdn.pixabay.com/video/2015/08/08/%d-135736646_small.jpg"},
                    "tiny":   {"url": "https://cdn.pixabay.com/video/2015/08/08/%d-135736646_tiny.mp4", "width": 960, "height": 540, "size": 1023835, "thumbnail": "https://cdn.pixabay.com/video/2015/08/08/%d-135736646_tiny.jpg"}
                  },
                  "views": 4462, "downloads": 1464, "likes": 18, "comments": 0,
                  "user_id": 1281706, "user": "Coverr-Free-Footage", "userImageURL": ""
                } ] }""".formatted(totalHits, id, id, id, id, id, id, id, id, id, id);
    }

    private record Fake(List<String> urls, PixabayClient.Fetcher fetcher) {
    }

    private static Fake fake(PixabayClient.Response... responses) {
        List<String> urls = new ArrayList<>();
        int[] i = {0};
        return new Fake(urls, url -> {
            urls.add(url);
            return responses[Math.min(i[0]++, responses.length - 1)];
        });
    }

    private static PixabayClient.Response ok(String body) {
        return new PixabayClient.Response(200, body, Map.of("X-RateLimit-Remaining", List.of("99"),
                "X-RateLimit-Reset", List.of("60")));
    }

    @Test
    void searchParsesVideosAndPicksTheRightVersions(@TempDir Path dir) throws IOException {
        Fake f = fake(ok(answer(125, 60)));
        PixabayClient c = new PixabayClient(() -> KEY, dir, f.fetcher(), System::currentTimeMillis);
        PixabayClient.Page page = c.search("peaceful clouds", 1);

        assertEquals(1, page.videos().size());
        assertTrue(page.hasMore(), "60 hits, 24 a page: more to load");
        PixabayVideo v = page.videos().getFirst();
        assertEquals(125, v.id());
        assertEquals("Coverr-Free-Footage", v.user());
        assertEquals("by Coverr-Free-Footage via Pixabay", v.credit());
        assertEquals("Clouds, Sky, Heaven", v.title());
        assertEquals("0:12", v.durationText());
        assertTrue(v.playback().url().endsWith("_medium.mp4"), "Program plays 1080p, not the 4K file");
        assertEquals("1920×1080", v.resolutionText());
        assertTrue(v.hover().url().endsWith("_tiny.mp4"), "the hover preview uses the smallest version");
        assertTrue(v.poster().endsWith("_tiny.jpg"));

        String url = f.urls().getFirst();
        assertTrue(url.startsWith("https://pixabay.com/api/videos/?"), "the official API endpoint only");
        assertTrue(url.contains("q=peaceful+clouds") && url.contains("safesearch=true") && url.contains("per_page=24"));
    }

    @Test
    void answersAreKeptFor24Hours(@TempDir Path dir) throws IOException {
        AtomicLong now = new AtomicLong(1_000_000);
        Fake f = fake(ok(answer(1, 3)), ok(answer(2, 3)));
        PixabayClient c = new PixabayClient(() -> KEY, dir, f.fetcher(), now::get);
        c.search("gold particles", 1);
        c.search("gold particles", 1);
        assertEquals(1, f.urls().size(), "the same search within 24 hours is not sent again");

        // a restart within the day: read from the disk cache
        PixabayClient restarted = new PixabayClient(() -> KEY, dir, f.fetcher(), now::get);
        assertEquals(1, restarted.search("gold particles", 1).videos().getFirst().id());
        assertEquals(1, f.urls().size());

        now.addAndGet(PixabayClient.CACHE_TTL.toMillis() + 1);
        assertEquals(2, c.search("gold particles", 1).videos().getFirst().id(), "after 24 hours it is asked again");
        assertEquals(2, f.urls().size());
    }

    @Test
    void rateLimitStopsRequestsUntilTheWindowResets(@TempDir Path dir) {
        AtomicLong now = new AtomicLong(5_000);
        Fake f = fake(new PixabayClient.Response(429, "API rate limit exceeded",
                Map.of("X-RateLimit-Reset", List.of("30"))), ok(answer(3, 3)));
        PixabayClient c = new PixabayClient(() -> KEY, dir, f.fetcher(), now::get);
        var e = assertThrows(PixabayClient.PixabayException.class, () -> c.search("waterfalls", 1));
        assertEquals(PixabayClient.Problem.RATE_LIMIT, e.problem());
        assertThrows(PixabayClient.PixabayException.class, () -> c.search("ocean", 1));
        assertEquals(1, f.urls().size(), "no request is sent while the limit is used up");
        now.addAndGet(31_000);
        assertEquals(1, assertDoesNotThrow(() -> c.search("ocean", 1)).videos().size());
    }

    @Test
    void badKeyOfflineAndNoKeyGiveClearReasonsWithoutTheKey(@TempDir Path dir) {
        Fake bad = fake(new PixabayClient.Response(400, "[ERROR 400] Invalid API key " + KEY, Map.of()));
        var e = assertThrows(PixabayClient.PixabayException.class,
                () -> new PixabayClient(() -> KEY, dir, bad.fetcher(), System::currentTimeMillis).search("sky", 1));
        assertEquals(PixabayClient.Problem.INVALID_KEY, e.problem());
        assertFalse(e.getMessage().contains(KEY), "the key never reaches a message");

        PixabayClient.Fetcher offline = url -> {
            throw new ConnectException("no route");
        };
        var o = assertThrows(PixabayClient.PixabayException.class,
                () -> new PixabayClient(() -> KEY, dir, offline, System::currentTimeMillis).search("sea", 1));
        assertEquals(PixabayClient.Problem.OFFLINE, o.problem());

        var n = assertThrows(PixabayClient.PixabayException.class,
                () -> new PixabayClient(() -> null, dir, offline, System::currentTimeMillis).search("sea", 1));
        assertEquals(PixabayClient.Problem.NO_KEY, n.problem());
    }

    @Test
    void aPixabayVideoInTheLibraryIsAnAddressNotAFile(@TempDir Path dir) throws IOException {
        AppPaths paths = new AppPaths(dir.resolve("LiveCat"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        MediaService media = new MediaService(new MediaRepository(db), paths);
        PixabayVideo v = PixabayClient.parse(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(answer(125, 1)).path("hits").get(0));

        MediaItem item = media.addPixabay(v, false);
        assertEquals(MediaSourceType.PIXABAY, item.getSourceType());
        assertEquals(MediaKind.VIDEO, item.getKind());
        assertEquals(MediaCategory.VIDEO, item.getCategory());
        assertTrue(OnlineMedia.isUrl(item.getPath()) && item.getPath().endsWith("_medium.mp4"));
        assertEquals(125, item.pixabayId());
        assertEquals("Coverr-Free-Footage", item.getChannelName());
        assertFalse(item.isCopied(), "nothing is copied or downloaded");
        assertTrue(media.exists(item), "an online video is never a missing file");
        assertFalse(Files.exists(media.resolve(item)), "resolving the address never throws and never points at a file");
        try (var files = Files.walk(paths.root())) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".mp4")), "no video file was written");
        }

        MediaItem again = media.addPixabay(v, true);
        assertEquals(item.getId(), again.getId(), "the same video is not added twice");
        assertEquals(MediaCategory.BACKGROUND, again.getCategory(), "USE AS BACKGROUND moves it to Backgrounds");
    }

    private static <T> T assertDoesNotThrow(org.junit.jupiter.api.function.ThrowingSupplier<T> s) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(s);
    }
}
