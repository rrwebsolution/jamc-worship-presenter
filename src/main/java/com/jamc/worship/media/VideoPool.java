package com.jamc.worship.media;

import com.jamc.worship.youtube.YouTubeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

/**
 * Looping, muted videos used as backgrounds and scene video sources.
 *
 * <p>Players start on first request and keep running while they are being painted, so moving
 * from Verse 1 to Chorus to Verse 2 with the same background never restarts the video. A player
 * nobody has asked for during {@link #IDLE_MS} is stopped to save CPU. Confined to the EDT.
 */
public final class VideoPool {

    private static final Logger log = LoggerFactory.getLogger(VideoPool.class);
    private static final long IDLE_MS = 8_000;
    private static final int MAX_PLAYERS = 3;

    private final Function<String, Path> resolver;
    private final Map<String, Entry> players = new HashMap<>();
    private final Timer sweeper = new Timer(2_000, e -> sweep());

    private static final class Entry {
        final VideoPlayback player;
        long lastUsed;

        Entry(VideoPlayback player) {
            this.player = player;
        }
    }

    public VideoPool(Function<String, Path> resolver) {
        this.resolver = resolver;
        sweeper.start();
    }

    /** Current frame of the looping video at {@code path}, starting it if needed. Null until the first frame. */
    public BufferedImage frame(String path) {
        if (path == null || !VideoPlayback.available(path)) {
            return null;
        }
        Entry e = players.get(path);
        if (e == null) {
            String mrl;
            if (YouTubeRef.is(path)) {
                mrl = path; // played by the embedded YouTube player, never by VLC
            } else {
                Path file = resolver.apply(path);
                if (!Files.isRegularFile(file)) {
                    return null;
                }
                mrl = file.toString();
            }
            if (players.size() >= MAX_PLAYERS) {
                evictOldest();
            }
            try {
                e = new Entry(VideoPlayback.create(path));
                e.player.play(mrl, true, true);
                players.put(path, e);
                log.info("Started looping video {}", path);
            } catch (RuntimeException ex) {
                log.warn("Could not start video {}", path, ex);
                return null;
            }
        }
        e.lastUsed = System.currentTimeMillis();
        return e.player.currentFrame();
    }

    /** The running player for this path, or null (never starts one). */
    VideoPlayback player(String path) {
        Entry e = path == null ? null : players.get(path);
        return e == null ? null : e.player;
    }

    /** Hands over the running player for this path, which keeps playing: the caller owns it now. Null when none runs. */
    VideoPlayback take(String path) {
        Entry e = path == null ? null : players.remove(path);
        return e == null ? null : e.player;
    }

    /** Latest frame if the video is already running; never starts a player (used by thumbnails). */
    public BufferedImage poster(String path) {
        Entry e = path == null ? null : players.get(path);
        return e == null ? null : e.player.currentFrame();
    }

    public void stopAll() {
        players.values().forEach(e -> e.player.release());
        players.clear();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = players.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            if (now - e.getValue().lastUsed > IDLE_MS) {
                e.getValue().player.release();
                it.remove();
                log.info("Stopped idle video {}", e.getKey());
            }
        }
    }

    private void evictOldest() {
        players.entrySet().stream().min((a, b) -> Long.compare(a.getValue().lastUsed, b.getValue().lastUsed))
                .ifPresent(e -> {
                    e.getValue().player.release();
                    players.remove(e.getKey());
                });
    }
}
