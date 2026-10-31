package com.jamc.worship.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Resolves and creates the local application data directory.
 *
 * <p>Resolution order for the root directory:
 * <ol>
 *   <li>System property {@code jamc.home}</li>
 *   <li>Environment variable {@code JAMC_HOME}</li>
 *   <li>{@code %LOCALAPPDATA%\JAMCWorshipPresenter} on Windows</li>
 *   <li>{@code ~/JAMCWorshipPresenter} elsewhere</li>
 * </ol>
 *
 * <p>This class must not log: it is used before logging is configured.
 */
public final class AppPaths {

    public static final String APP_DIR_NAME = "JAMCWorshipPresenter";

    private final Path root;

    public AppPaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static AppPaths resolveDefault() {
        String override = System.getProperty("jamc.home");
        if (isBlank(override)) {
            override = System.getenv("JAMC_HOME");
        }
        if (!isBlank(override)) {
            return new AppPaths(Paths.get(override));
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (!isBlank(localAppData)) {
            return new AppPaths(Paths.get(localAppData, APP_DIR_NAME));
        }
        return new AppPaths(Paths.get(System.getProperty("user.home"), APP_DIR_NAME));
    }

    /** Creates every application directory that does not exist yet. */
    public void ensureDirectories() {
        for (Path dir : allDirectories()) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create directory " + dir, e);
            }
        }
    }

    public List<Path> allDirectories() {
        return List.of(root, dataDir(), mediaDir(), audioDir(), videosDir(), imagesDir(),
                backgroundsDir(), presentationsDir(), bibleDir(), backupsDir(), logsDir(), configDir());
    }

    public Path root() { return root; }
    public Path dataDir() { return root.resolve("data"); }
    public Path databaseFile() { return dataDir().resolve("worship.db"); }
    /** Rendered slides of imported PowerPoint files, one folder per presentation. */
    public Path presentationsDir() { return dataDir().resolve("presentations"); }
    public Path mediaDir() { return root.resolve("media"); }
    public Path audioDir() { return mediaDir().resolve("audio"); }
    public Path videosDir() { return mediaDir().resolve("videos"); }
    public Path imagesDir() { return mediaDir().resolve("images"); }
    public Path backgroundsDir() { return mediaDir().resolve("backgrounds"); }
    public Path bibleDir() { return root.resolve("bible"); }
    public Path backupsDir() { return root.resolve("backups"); }
    public Path logsDir() { return root.resolve("logs"); }
    public Path configDir() { return root.resolve("config"); }
    public Path settingsFile() { return configDir().resolve("settings.properties"); }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
