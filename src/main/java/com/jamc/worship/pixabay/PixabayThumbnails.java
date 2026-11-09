package com.jamc.worship.pixabay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Poster pictures of Pixabay videos. The API documentation asks that images be downloaded rather than hotlinked, so
 * each poster is fetched once into the data folder ({@code pixabay/posters}, a small JPG) and drawn from there.
 * Loading never blocks: {@link #get} returns null until the picture is ready, then the listeners are told (on the
 * Swing thread) so cards repaint. Pictures are kept in memory at most 480 px wide.
 */
public final class PixabayThumbnails {

    private static final Logger log = LoggerFactory.getLogger(PixabayThumbnails.class);
    private static final int MAX_WIDTH = 480;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "pixabay-poster");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Map<String, BufferedImage> MEMORY = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > 160;
                }
            });
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    /** Poster address of each video address seen (library items and search results), for scene stills. */
    private static final Map<String, String> POSTER_OF_VIDEO = new ConcurrentHashMap<>();
    private static volatile Path folder;

    private PixabayThumbnails() {
    }

    /** Where posters are kept (data/pixabay/posters). */
    public static void install(Path dir) {
        folder = dir;
    }

    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    /** Remembers which poster belongs to a video address (the address a library item plays). */
    public static void remember(String videoUrl, String posterUrl) {
        if (videoUrl != null && posterUrl != null) {
            POSTER_OF_VIDEO.put(videoUrl, posterUrl);
        }
    }

    /** The poster of a playing address, if it is known: remembered, or Pixabay's "same name .jpg" beside the mp4. */
    public static BufferedImage forVideo(String videoUrl) {
        if (videoUrl == null) {
            return null;
        }
        String poster = POSTER_OF_VIDEO.get(videoUrl);
        if (poster == null && videoUrl.contains("cdn.pixabay.com/") && videoUrl.endsWith(".mp4")) {
            poster = videoUrl.substring(0, videoUrl.length() - 4) + ".jpg";
        }
        return poster == null ? null : get(poster);
    }

    /** The picture, or null while it loads (or when it cannot be loaded). */
    public static BufferedImage get(String posterUrl) {
        if (posterUrl == null || posterUrl.isBlank() || !posterUrl.startsWith("https://")) {
            return null;
        }
        BufferedImage img = MEMORY.get(posterUrl);
        if (img != null || FAILED.contains(posterUrl) || !LOADING.add(posterUrl)) {
            return img;
        }
        LOADER.execute(() -> {
            try {
                BufferedImage loaded = load(posterUrl);
                if (loaded != null) {
                    MEMORY.put(posterUrl, loaded);
                } else {
                    FAILED.add(posterUrl);
                }
            } finally {
                LOADING.remove(posterUrl);
                SwingUtilities.invokeLater(() -> LISTENERS.forEach(Runnable::run));
            }
        });
        return null;
    }

    private static BufferedImage load(String url) {
        Path dir = folder;
        Path file = dir == null ? null : dir.resolve(name(url));
        try {
            if (file != null && Files.isRegularFile(file)) {
                BufferedImage img = ImageIO.read(file.toFile());
                if (img != null) {
                    return shrink(img);
                }
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) {
                    return null;
                }
                byte[] bytes = in.readAllBytes();
                if (file != null) {
                    Files.createDirectories(dir);
                    Path tmp = Files.createTempFile(dir, "poster", ".tmp");
                    Files.write(tmp, bytes);
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                return img == null ? null : shrink(img);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Pixabay poster not loaded: {}", e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static BufferedImage shrink(BufferedImage img) {
        if (img.getWidth() <= MAX_WIDTH) {
            return img;
        }
        int h = Math.max(1, img.getHeight() * MAX_WIDTH / img.getWidth());
        BufferedImage out = new BufferedImage(MAX_WIDTH, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, MAX_WIDTH, h, null);
        g.dispose();
        return out;
    }

    /** A safe file name for a poster address. */
    static String name(String url) {
        String hash = Integer.toHexString(url.hashCode()) + Integer.toHexString(url.length());
        String tail = url.substring(url.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (tail.length() > 60) {
            tail = tail.substring(tail.length() - 60);
        }
        return new String((hash + "-" + tail).getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }
}
