package com.jamc.worship.util;

import com.jamc.worship.config.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

/** File helpers for media handled by the application. */
public final class FileUtils {

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    private FileUtils() {
    }

    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImage(Path file) {
        return IMAGE_EXTENSIONS.contains(extension(file));
    }

    /**
     * Copies an image into {@code targetDir} (inside the data directory) unless it is already
     * stored there, and returns the path to save: relative to the data directory when possible,
     * so a library moved to another computer or restored from backup keeps working.
     */
    public static String importInto(AppPaths paths, Path source, Path targetDir) throws IOException {
        Path src = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(src)) {
            throw new IOException("File not found: " + src);
        }
        Path root = paths.root().toAbsolutePath().normalize();
        Path stored;
        if (src.startsWith(root)) {
            stored = src;
        } else {
            Files.createDirectories(targetDir);
            stored = uniqueTarget(targetDir, src.getFileName().toString());
            Files.copy(src, stored, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return toStoredPath(paths, stored);
    }

    /** Relative ("media/backgrounds/x.jpg") inside the data directory, absolute otherwise. */
    public static String toStoredPath(AppPaths paths, Path file) {
        Path root = paths.root().toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        return abs.startsWith(root) ? root.relativize(abs).toString().replace('\\', '/') : abs.toString();
    }

    static Path uniqueTarget(Path dir, String fileName) {
        String safe = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        int dot = safe.lastIndexOf('.');
        String base = dot < 0 ? safe : safe.substring(0, dot);
        String ext = dot < 0 ? "" : safe.substring(dot);
        Path candidate = dir.resolve(safe);
        for (int n = 2; Files.exists(candidate); n++) {
            candidate = dir.resolve(base + "-" + n + ext);
        }
        return candidate;
    }
}
