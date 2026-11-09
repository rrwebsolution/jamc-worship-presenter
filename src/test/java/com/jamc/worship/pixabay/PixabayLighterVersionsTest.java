package com.jamc.worship.pixabay;

import com.jamc.worship.media.OnlineMedia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PixabayLighterVersionsTest {

    @Test
    void readsThePixabayIdFromSavedAddresses() {
        assertEquals(303686, PixabayLighterVersions.idOf("https://cdn.pixabay.com/video/2025/09/13/303686_medium.mp4"));
        assertEquals(5, PixabayLighterVersions.idOf("https://cdn.pixabay.com/video/2015/08/07/5-135665794_medium.mp4"));
        assertEquals(0, PixabayLighterVersions.idOf("https://example.com/video/1_medium.mp4"));
        assertEquals(0, PixabayLighterVersions.idOf(null));
    }

    @Test
    void aSavedOnePointFourKAddressPlaysTheLighterVersionAndRemembersIt(@TempDir Path dir) throws Exception {
        String medium = "https://cdn.pixabay.com/video/2025/09/13/303686_medium.mp4";
        String small = "https://cdn.pixabay.com/video/2025/09/13/303686_small.mp4";
        String body = """
                {"total":1,"totalHits":1,"hits":[{"id":303686,"pageURL":"p","tags":"space","duration":10,"user":"u","user_id":1,
                 "videos":{"large":{"url":"https://cdn.pixabay.com/video/2025/09/13/303686_large.mp4","width":4096,"height":2160,"size":1,"thumbnail":""},
                           "medium":{"url":"%s","width":2730,"height":1440,"size":1,"thumbnail":""},
                           "small":{"url":"%s","width":2048,"height":1080,"size":1,"thumbnail":""},
                           "tiny":{"url":"https://cdn.pixabay.com/video/2025/09/13/303686_tiny.mp4","width":1364,"height":720,"size":1,"thumbnail":""}}}]}
                """.formatted(medium, small);
        Path pairs = dir.resolve("playback.properties");
        OnlineMedia.install(pairs);
        PixabayClient c = new PixabayClient(() -> "key", dir.resolve("cache"),
                url -> new PixabayClient.Response(200, body, Map.of("X-RateLimit-Remaining", List.of("99"))),
                System::currentTimeMillis);

        assertEquals(1, PixabayLighterVersions.updateAddresses(c, List.of(medium)));
        assertEquals(small, OnlineMedia.playable(medium), "VLC opens the about-1080p version");
        assertEquals("C:/media/x.mp4", OnlineMedia.playable("C:/media/x.mp4"), "files are untouched");

        OnlineMedia.playInstead(medium, null);
        assertEquals(medium, OnlineMedia.playable(medium));
        OnlineMedia.playInstead(medium, small);
        OnlineMedia.install(pairs); // read back from the file (offline next start)
        assertEquals(small, OnlineMedia.playable(medium));
        OnlineMedia.playInstead(medium, null);
    }

    @Test
    void withoutAKeyNothingChanges(@TempDir Path dir) {
        String medium = "https://cdn.pixabay.com/video/2015/08/07/5-135665794_medium.mp4";
        PixabayClient c = new PixabayClient(() -> null, dir);
        assertEquals(0, PixabayLighterVersions.updateAddresses(c, List.of(medium)));
        assertEquals(medium, OnlineMedia.playable(medium));
    }
}
