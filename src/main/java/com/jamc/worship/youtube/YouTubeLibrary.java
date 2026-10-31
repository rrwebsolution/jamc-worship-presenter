package com.jamc.worship.youtube;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared YouTube services for the UI: the metadata client, thumbnails (downloaded once and kept in
 * {@code <data>/youtube}, so the library still shows them offline) and ONLINE / OFFLINE status checks.
 * Listeners are told on the event thread when a thumbnail or a status arrives.
 */
public final class YouTubeLibrary {

    private static final Logger log = LoggerFactory.getLogger(YouTubeLibrary.class);
    private static final BufferedImage NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private static volatile YouTubeClient client = new YouTubeClient(() -> null);
    private static volatile Path thumbDir;
    private static final Map<String, BufferedImage> thumbs = new ConcurrentHashMap<>();
    private static final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> checking = new ConcurrentHashMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "youtube-library");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private YouTubeLibrary() {
    }

    /** Call once at startup. */
    public static void configure(YouTubeClient c, Path thumbnailDir) {
        client = c;
        thumbDir = thumbnailDir;
    }

    public static YouTubeClient client() {
        return client;
    }

    public static void addListener(Runnable l) {
        listeners.add(l);
    }

    public static void removeListener(Runnable l) {
        listeners.remove(l);
    }

    /**
     * The video's thumbnail, or null while it loads (listeners are told when it is ready). Kept on disk,
     * so it also shows offline once it was seen.
     */
    public static BufferedImage thumbnail(String videoId, String url) {
        if (videoId == null) {
            return null;
        }
        BufferedImage img = thumbs.get(videoId);
        Long retry = retryAt.get(videoId);
        if (img == null || (img == NONE && retry != null && System.currentTimeMillis() > retry)) {
            thumbs.put(videoId, NONE); // queued once
            retryAt.remove(videoId);
            worker.submit(() -> {
                BufferedImage t = loadThumbnail(videoId, url == null ? YouTubeVideo.defaultThumbnail(videoId) : url);
                if (t != null) {
                    thumbs.put(videoId, t);
                    fire();
                } else {
                    retryAt.put(videoId, System.currentTimeMillis() + 60_000); // e.g. once the internet is back
                }
            });
            return null;
        }
        return img == NONE ? null : img;
    }

    private static BufferedImage loadThumbnail(String id, String url) {
        Path dir = thumbDir;
        Path file = dir == null ? null : dir.resolve(id + ".jpg");
        try {
            if (file != null && Files.isRegularFile(file)) {
                BufferedImage cached = ImageIO.read(file.toFile());
                if (cached != null) {
                    return cached;
                }
            }
            HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                r.body().close();
                return null;
            }
            byte[] bytes;
            try (InputStream in = r.body()) {
                bytes = in.readAllBytes();
            }
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img != null && file != null) {
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, id, ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return img;
        } catch (IOException e) {
            log.debug("No thumbnail for {}: {}", id, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** ONLINE / OFFLINE / UNAVAILABLE as last checked; starts a background check when due. */
    public static YouTubeClient.Availability availability(String videoId) {
        YouTubeClient c = client;
        if (videoId != null && c.needsCheck(videoId) && checking.putIfAbsent(videoId, Boolean.TRUE) == null) {
            worker.submit(() -> {
                try {
                    c.checkAvailability(videoId);
                } finally {
                    checking.remove(videoId);
                    fire();
                }
            });
        }
        return videoId == null ? YouTubeClient.Availability.UNKNOWN : c.cachedAvailability(videoId);
    }

    private static void fire() {
        SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
    }
}
