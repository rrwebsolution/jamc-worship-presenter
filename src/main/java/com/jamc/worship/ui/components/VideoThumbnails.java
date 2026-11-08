package com.jamc.worship.ui.components;

import com.jamc.worship.media.MediaProbe;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Still pictures for video tiles in the media library, grabbed one video at a time in the
 * background (each grab briefly opens the file in VLC) and kept for the session.
 */
final class VideoThumbnails {

    private static final int WIDTH = 256;
    private static final BufferedImage NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    /** Most recently shown thumbnails only, so a large library browsed for hours does not fill the memory. */
    private static final int MAX_THUMBS = 500;
    private static final Map<Path, BufferedImage> cache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, BufferedImage> eldest) {
                    return size() > MAX_THUMBS; // a dropped one is grabbed again when its tile shows
                }
            });
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-thumbnails");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private VideoThumbnails() {
    }

    /** The thumbnail, or null while it is being made (listeners are told when it is ready) or if there is none. */
    static BufferedImage get(Path file) {
        if (file == null) {
            return null;
        }
        BufferedImage img = cache.get(file);
        if (img == null) {
            cache.put(file, NONE); // queued once
            worker.submit(() -> {
                BufferedImage t = MediaProbe.thumbnail(file, WIDTH);
                cache.put(file, t == null ? NONE : t);
                if (t != null) {
                    SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
                }
            });
            return null;
        }
        return img == NONE ? null : img;
    }

    static void addListener(Runnable l) {
        listeners.add(l);
    }
}
