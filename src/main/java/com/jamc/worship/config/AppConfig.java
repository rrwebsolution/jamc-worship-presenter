package com.jamc.worship.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Application settings persisted as a properties file in {@code config/settings.properties}.
 *
 * <p>Writes go to a temporary file first and are then moved into place, so a crash
 * during save never leaves a half-written settings file behind.
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    public static final String KEY_UI_THEME = "ui.theme";
    public static final String KEY_WINDOW_BOUNDS = "ui.window.bounds";
    public static final String KEY_WINDOW_MAXIMIZED = "ui.window.maximized";

    public static final String KEY_OUTPUT_DISPLAY = "output.display";
    public static final String KEY_OUTPUT_MODE = "output.mode";
    public static final String KEY_TRANSITION = "live.transition";
    public static final String KEY_TRANSITION_MS = "live.transitionMs";
    public static final String KEY_AUTO_TAKE = "live.autoTake";
    /** Mouse pointer over the projector picture: ALWAYS_HIDDEN, AUTO_HIDE (default, 3 s) or ALWAYS_VISIBLE. */
    public static final String KEY_PROJECTOR_CURSOR = "output.projectorCursor";

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    private final AppPaths paths;
    private final Properties props = new Properties();

    public AppConfig(AppPaths paths) {
        this.paths = paths;
    }

    public AppPaths paths() {
        return paths;
    }

    public synchronized void load() {
        Path file = paths.settingsFile();
        if (!Files.exists(file)) {
            log.info("No settings file yet, using defaults ({})", file);
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            log.info("Loaded settings from {}", file);
        } catch (IOException e) {
            log.error("Could not read settings file {}, using defaults", file, e);
        }
    }

    public synchronized void save() {
        Path file = paths.settingsFile();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(out, "LiveCat Multimedia settings");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Could not save settings to {}", file, e);
        }
    }

    public synchronized String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public synchronized void set(String key, String value) {
        if (value == null) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
    }

    public synchronized boolean getBoolean(String key, boolean defaultValue) {
        String v = props.getProperty(key);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }

    public String uiTheme() {
        return get(KEY_UI_THEME, THEME_DARK);
    }
}
