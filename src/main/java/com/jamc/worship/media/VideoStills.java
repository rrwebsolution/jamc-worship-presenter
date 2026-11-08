package com.jamc.worship.media;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One still picture per video file for scene thumbnails (Scenes dock, Scenes page, the scene view in the
 * workspace). The still is grabbed once in the background and kept, so a thumbnail never starts a player:
 * nothing moves there and no decoder competes with Preview / Program.
 */
public final class VideoStills {

    private static final int WIDTH = 640;
    /** Where in the video a still is tried, in turn, until one is not (nearly) black, e.g. after a fade-in. */
    private static final float[] POSITIONS = {0.1f, 0.3f, 0.5f, 0.7f};
    private static final BufferedImage NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    private static final int MAX = 100;
    private static final Map<Path, BufferedImage> cache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, BufferedImage> eldest) {
                    return size() > MAX;
                }
            });
    /** Stills taken from a player that was already running, until the grabbed one is ready. */
    private static final Map<Path, BufferedImage> interim = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-stills");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private VideoStills() {
    }

    /** The still, or null while it is being made (listeners are told when it is ready) or if there is none. */
    public static BufferedImage get(Path file) {
        if (file == null) {
            return null;
        }
        BufferedImage img = cache.get(file);
        if (img == null) {
            cache.put(file, NONE); // queued once
            worker.submit(() -> {
                BufferedImage t = grab(file);
                if (t != null) {
                    cache.put(file, t);
                    interim.remove(file);
                    SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
                }
            });
            return interim.get(file);
        }
        return img == NONE ? interim.get(file) : img;
    }

    /** True when there is no still yet (the caller may {@link #offer} one). */
    public static boolean missing(Path file) {
        BufferedImage img = cache.get(file);
        return (img == null || img == NONE) && !interim.containsKey(file);
    }

    /** A picture from a player that already runs, shown until the grabbed still is ready (copied once). */
    public static void offer(Path file, BufferedImage frame) {
        if (file != null && frame != null && !isDark(frame)) {
            interim.putIfAbsent(file, copy(frame));
        }
    }

    private static BufferedImage grab(Path file) {
        BufferedImage best = null;
        for (float p : POSITIONS) {
            BufferedImage t = MediaProbe.thumbnail(file, WIDTH, p);
            if (t == null) {
                return best; // VLC cannot open it: trying further does not help
            }
            if (!isDark(t)) {
                return t;
            }
            if (best == null) {
                best = t;
            }
        }
        return best; // a dark video: its darkest-but-real picture
    }

    /** True if the picture is (nearly) black, sampled on a grid. */
    static boolean isDark(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        long sum = 0;
        int n = 0;
        for (int y = h / 20; y < h; y += Math.max(1, h / 10)) {
            for (int x = w / 20; x < w; x += Math.max(1, w / 10)) {
                int rgb = img.getRGB(x, y);
                sum += ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                n++;
            }
        }
        return n == 0 || sum / (3.0 * n) < 12;
    }

    static BufferedImage copy(BufferedImage src) {
        int w = Math.min(WIDTH, src.getWidth());
        int h = Math.max(1, w * src.getHeight() / Math.max(1, src.getWidth()));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Called on the event thread when a new still is ready (repaint the thumbnails). */
    public static void addListener(Runnable l) {
        listeners.add(l);
    }

    public static void removeListener(Runnable l) {
        listeners.remove(l);
    }
}
