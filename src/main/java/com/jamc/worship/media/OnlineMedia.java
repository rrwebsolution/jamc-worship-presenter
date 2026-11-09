package com.jamc.worship.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Library media that streams from the internet instead of a file (Pixabay videos): the stored path is the {@code https}
 * address itself, which VLC plays directly. Such a path is never turned into a file path (on Windows it is not a valid
 * one) and is never "missing" on disk; when the network fails, the player reports an error instead.
 *
 * <p>A stored address can have a lighter one to play instead ({@link #playable}): Pixabay videos saved before the
 * ≤1080p choice point at a 1440p "medium" file. The stored address stays the item's identity everywhere (scenes, songs,
 * the background setting); only what VLC opens changes. The pairs are kept in a small file so they work offline.
 */
public final class OnlineMedia {

    private static final Logger log = LoggerFactory.getLogger(OnlineMedia.class);
    private static final Map<String, String> PLAY_INSTEAD = new ConcurrentHashMap<>();
    private static volatile Path file;

    private OnlineMedia() {
    }

    /** True for an {@code http(s)://} address. */
    public static boolean isUrl(String path) {
        return path != null && (path.startsWith("https://") || path.startsWith("http://"));
    }

    /** Loads the saved "play this instead" pairs. */
    public static void install(Path pairsFile) {
        file = pairsFile;
        if (pairsFile == null || !Files.isRegularFile(pairsFile)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(pairsFile)) {
            p.load(in);
            p.forEach((k, v) -> PLAY_INSTEAD.put(k.toString(), v.toString()));
        } catch (IOException e) {
            log.warn("Could not read {}", pairsFile, e);
        }
    }

    /** What VLC opens for this path: the lighter address when one is known, else the path itself. */
    public static String playable(String path) {
        if (!isUrl(path)) {
            return path;
        }
        return PLAY_INSTEAD.getOrDefault(path, path);
    }

    /** Plays {@code lighter} wherever {@code stored} is used (saved at once). Same or null addresses remove the pair. */
    public static void playInstead(String stored, String lighter) {
        if (!isUrl(stored)) {
            return;
        }
        String before = lighter == null || lighter.equals(stored) ? PLAY_INSTEAD.remove(stored) : PLAY_INSTEAD.put(stored, lighter);
        if (!java.util.Objects.equals(before, lighter == null || lighter.equals(stored) ? null : lighter)) {
            save();
        }
    }

    private static synchronized void save() {
        Path f = file;
        if (f == null) {
            return;
        }
        Properties p = new Properties();
        p.putAll(PLAY_INSTEAD);
        try {
            Files.createDirectories(f.getParent());
            try (OutputStream out = Files.newOutputStream(f)) {
                p.store(out, "Stored Pixabay address = lighter (at most 1080p) address that is played instead");
            }
        } catch (IOException e) {
            log.warn("Could not save {}", f, e);
        }
    }
}
