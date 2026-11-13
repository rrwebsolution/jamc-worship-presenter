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

    /**
     * What VLC opens for this path: the saved copy on this computer when there is one (see {@link #keepCopy}), else the
     * lighter address when one is known, else the path itself.
     */
    public static String playable(String path) {
        if (!isUrl(path)) {
            return path;
        }
        String address = PLAY_INSTEAD.getOrDefault(path, path);
        Path copy = copyOf(address);
        if (copy != null && Files.isRegularFile(copy)) {
            try {
                Files.setLastModifiedTime(copy, java.nio.file.attribute.FileTime.from(java.time.Instant.now())); // recently used
            } catch (IOException ignored) {
                // only the order in which old copies are removed
            }
            return copy.toString();
        }
        return address;
    }

    // ------------------------------------------------------------------ saved copies

    /**
     * Saved copies of streamed Pixabay videos. Streamed, a background downloaded the whole file again at every loop
     * (VLC keeps only what it is playing): one 8-second, 22 MB video used about 9.5 GB an hour, and on a connection that
     * could not keep up it stopped for about 4 s near the end of every loop. A copy loops seamlessly and offline.
     * Pixabay's licence allows downloading and using its videos; the library item still stores the address.
     */
    private static volatile Path copiesDir;
    /** Old copies are removed (least recently used first) above this. */
    static long copiesLimitBytes = 3L * 1024 * 1024 * 1024; // not final: tests use a small limit
    private static final java.util.Set<String> DOWNLOADING = ConcurrentHashMap.newKeySet();

    /** The folder for saved copies (data/pixabay/videos). */
    public static void installCopies(Path dir) {
        copiesDir = dir;
    }

    /** True when this path plays from a saved copy on this computer. */
    public static boolean hasCopy(String path) {
        if (!isUrl(path)) {
            return false;
        }
        Path copy = copyOf(PLAY_INSTEAD.getOrDefault(path, path));
        return copy != null && Files.isRegularFile(copy);
    }

    /** The saved copy of this address on this computer, or null when there is none (yet). */
    public static Path copyFile(String path) {
        if (!isUrl(path)) {
            return null;
        }
        Path copy = copyOf(PLAY_INSTEAD.getOrDefault(path, path));
        return copy != null && Files.isRegularFile(copy) ? copy : null;
    }

    /** True while this path's copy is being downloaded. */
    public static boolean isDownloading(String path) {
        return isUrl(path) && DOWNLOADING.contains(PLAY_INSTEAD.getOrDefault(path, path));
    }

    /**
     * Downloads the video this path plays into the saved copies, once and in the background; {@code ready} then runs on
     * the Swing thread (not when the download fails or a copy already exists). Only Pixabay videos are copied.
     * A copy saved this way may be removed again when the copies pass {@link #copiesLimitBytes}.
     */
    public static void keepCopy(String path, Runnable ready) {
        if (hasCopy(path)) {
            return;
        }
        keepCopy(path, false, ok -> {
            if (ok && ready != null) {
                ready.run();
            }
        });
    }

    /**
     * The operator's Download: like {@link #keepCopy(String, Runnable)}, but the copy is kept for good (never removed to
     * make room). {@code done} runs on the Swing thread with true when the copy is there (also when it already was).
     */
    public static void download(String path, java.util.function.Consumer<Boolean> done) {
        keepCopy(path, true, done);
    }

    private static final Map<String, java.util.List<java.util.function.Consumer<Boolean>>> WAITING = new ConcurrentHashMap<>();

    private static void keepCopy(String path, boolean pinned, java.util.function.Consumer<Boolean> done) {
        java.util.function.Consumer<Boolean> callback = done == null ? ok -> { } : done;
        if (!isUrl(path)) {
            javax.swing.SwingUtilities.invokeLater(() -> callback.accept(false));
            return;
        }
        String address = PLAY_INSTEAD.getOrDefault(path, path);
        Path copy = copyOf(address);
        if (copy == null) {
            javax.swing.SwingUtilities.invokeLater(() -> callback.accept(false));
            return;
        }
        if (pinned) {
            pin(copy);
        }
        if (Files.isRegularFile(copy)) {
            javax.swing.SwingUtilities.invokeLater(() -> callback.accept(true));
            return;
        }
        WAITING.computeIfAbsent(address, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(callback);
        if (!DOWNLOADING.add(address)) {
            return; // already downloading: told when it is done
        }
        Thread.ofVirtual().name("pixabay-copy").start(() -> {
            boolean ok = false;
            Path part = copy.resolveSibling(copy.getFileName() + ".part");
            try {
                Files.createDirectories(copy.getParent());
                long started = System.nanoTime();
                var client = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .connectTimeout(java.time.Duration.ofSeconds(15)).build();
                var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(address))
                                .timeout(java.time.Duration.ofMinutes(10)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofFile(part));
                if (response.statusCode() != 200 || Files.size(part) == 0) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                Files.move(part, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                log.info("Saved a copy of {} ({} MB in {} s): it now plays from this computer", copy.getFileName(),
                        Files.size(copy) / (1024 * 1024), (System.nanoTime() - started) / 1_000_000_000);
                trimCopies(copy);
                ok = true;
            } catch (Exception e) {
                log.warn("Could not save a copy of {}: it keeps streaming", address, e);
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                    // removed with the next copy of this video
                }
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                DOWNLOADING.remove(address);
                var waiting = WAITING.remove(address);
                boolean result = ok;
                if (waiting != null) {
                    javax.swing.SwingUtilities.invokeLater(() -> waiting.forEach(w -> w.accept(result)));
                }
            }
        });
    }

    /** Copies the operator downloaded; listed in {@code downloaded.txt} next to them and never removed to make room. */
    private static synchronized void pin(Path copy) {
        java.util.Set<String> pinned = pinned();
        if (pinned.add(copy.getFileName().toString())) {
            try {
                Files.createDirectories(copy.getParent());
                Files.write(copy.resolveSibling("downloaded.txt"), pinned);
            } catch (IOException e) {
                log.warn("Could not record the download of {}", copy.getFileName(), e);
            }
        }
    }

    private static synchronized java.util.Set<String> pinned() {
        Path dir = copiesDir;
        java.util.Set<String> names = new java.util.TreeSet<>();
        if (dir != null && Files.isRegularFile(dir.resolve("downloaded.txt"))) {
            try {
                for (String line : Files.readAllLines(dir.resolve("downloaded.txt"))) {
                    if (!line.isBlank()) {
                        names.add(line.strip());
                    }
                }
            } catch (IOException e) {
                log.warn("Could not read the list of downloaded videos", e);
            }
        }
        return names;
    }

    /** Where the copy of a Pixabay address is kept, e.g. {@code 2025-09-13_303680_small.mp4}; null for other addresses. */
    static Path copyOf(String address) {
        Path dir = copiesDir;
        if (dir == null || address == null || !address.contains("pixabay.com/")) {
            return null;
        }
        String p = java.net.URI.create(address).getPath();
        String[] parts = p.split("/");
        int from = Math.max(0, parts.length - 4); // .../video/2025/09/13/303680_small.mp4 → 2025-09-13_303680_small.mp4
        StringBuilder name = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].equals("video")) {
                continue;
            }
            name.append(name.isEmpty() ? "" : i == parts.length - 1 ? "_" : "-").append(parts[i].replaceAll("[^A-Za-z0-9._-]", ""));
        }
        return name.isEmpty() ? null : dir.resolve(name.toString());
    }

    /** Keeps the saved copies under {@link #copiesLimitBytes}: the least recently used go first, never {@code keep}. */
    static void trimCopies(Path keep) {
        Path dir = copiesDir;
        if (dir == null) {
            return;
        }
        try (var files = Files.list(dir)) {
            var copies = files.filter(f -> f.getFileName().toString().endsWith(".mp4"))
                    .sorted(java.util.Comparator.comparing(f -> {
                        try {
                            return Files.getLastModifiedTime(f);
                        } catch (IOException e) {
                            return java.nio.file.attribute.FileTime.fromMillis(0);
                        }
                    })).toList();
            long total = 0;
            for (Path f : copies) {
                total += Files.size(f);
            }
            java.util.Set<String> pinned = pinned();
            for (Path f : copies) {
                if (total <= copiesLimitBytes) {
                    break;
                }
                if (f.equals(keep) || pinned.contains(f.getFileName().toString())) {
                    continue; // the operator downloaded it: kept for good
                }
                long size = Files.size(f);
                try {
                    Files.delete(f);
                    total -= size;
                } catch (IOException inUse) {
                    // playing right now: kept
                }
            }
        } catch (IOException e) {
            log.debug("Could not tidy {}", dir, e);
        }
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
