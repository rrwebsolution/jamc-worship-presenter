package com.jamc.worship.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads background images off the event dispatch thread and keeps recently used ones in memory.
 *
 * <p>{@link #get} never blocks: it returns null until the image is ready, then notifies change
 * listeners on the EDT so canvases can repaint. Missing or unreadable files are remembered and
 * logged once, so a deleted background never stalls or crashes a live service.
 */
public final class ImageCache {

    private static final Logger log = LoggerFactory.getLogger(ImageCache.class);
    private static final int DEFAULT_MAX_ENTRIES = 24;
    /** Larger images are downscaled on load; output never needs more than this. */
    private static final int MAX_WIDTH = 2560;
    private static final int MAX_HEIGHT = 1440;

    private final Path root;
    private final int maxEntries;
    private final Map<String, BufferedImage> images = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > maxEntries;
        }
    };
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-loader");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** @param root directory that relative paths are resolved against (the app data directory), or null */
    public ImageCache(Path root) {
        this(root, DEFAULT_MAX_ENTRIES);
    }

    /** @param maxEntries how many decoded images to keep (small thumbnails can afford hundreds) */
    public ImageCache(Path root, int maxEntries) {
        this.root = root;
        this.maxEntries = maxEntries;
    }

    public Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() || root == null ? p : root.resolve(p);
    }

    /** The image if it is loaded; otherwise starts loading it and returns null. */
    public BufferedImage get(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String key = key(path);
        synchronized (images) {
            BufferedImage img = images.get(key);
            if (img != null) {
                return img;
            }
        }
        if (!failed.contains(key) && pending.add(key)) {
            loader.submit(() -> load(path, key));
        }
        return null;
    }

    /** True if the file could not be found or decoded. */
    public boolean isMissing(String path) {
        return path != null && !path.isBlank() && failed.contains(key(path));
    }

    /** Loads synchronously (for exports and tests). Returns null if the image cannot be read. */
    public BufferedImage getBlocking(String path) {
        BufferedImage img = get(path);
        if (img == null && path != null && !path.isBlank()) {
            String key = key(path);
            load(path, key);
            synchronized (images) {
                img = images.get(key);
            }
        }
        return img;
    }

    /** Forgets a path so it is reloaded on next use (e.g. after a missing file was restored). */
    public void invalidate(String path) {
        String key = key(path);
        failed.remove(key);
        synchronized (images) {
            images.remove(key);
        }
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void load(String path, String key) {
        try {
            Path file = resolve(path);
            if (!Files.isRegularFile(file)) {
                failed.add(key);
                log.warn("Background image not found: {}", file);
                return;
            }
            BufferedImage raw = ImageIO.read(file.toFile());
            if (raw == null) {
                failed.add(key);
                log.warn("Unsupported image format: {}", file);
                return;
            }
            BufferedImage img = normalize(raw);
            synchronized (images) {
                images.put(key, img);
            }
        } catch (Exception | OutOfMemoryError e) {
            failed.add(key);
            log.warn("Could not load image {}", path, e);
        } finally {
            pending.remove(key);
            SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
        }
    }

    /** Converts to an RGB(A) image capped at {@link #MAX_WIDTH} x {@link #MAX_HEIGHT} for fast painting. */
    private static BufferedImage normalize(BufferedImage raw) {
        double scale = Math.min(1.0, Math.min((double) MAX_WIDTH / raw.getWidth(), (double) MAX_HEIGHT / raw.getHeight()));
        int w = Math.max(1, (int) Math.round(raw.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(raw.getHeight() * scale));
        int type = raw.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (scale == 1.0 && raw.getType() == type) {
            return raw;
        }
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(raw, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private String key(String path) {
        return resolve(path).toAbsolutePath().normalize().toString().toLowerCase(java.util.Locale.ROOT);
    }
}
