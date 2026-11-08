package com.jamc.worship.app;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.media.MediaEngine;
import com.jamc.worship.repository.BibleRepository;
import com.jamc.worship.repository.BibleVersionRepository;
import com.jamc.worship.repository.LowerThirdRepository;
import com.jamc.worship.repository.MediaRepository;
import com.jamc.worship.repository.PresentationRepository;
import com.jamc.worship.repository.SceneRepository;
import com.jamc.worship.repository.ServiceRepository;
import com.jamc.worship.service.BibleService;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.service.PresentationService;
import com.jamc.worship.presentation.PresentationRenderers;
import com.jamc.worship.service.SceneService;
import com.jamc.worship.service.ServicePlanService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.repository.SettingsRepository;
import com.jamc.worship.repository.SlideDeckRepository;
import com.jamc.worship.repository.SongRepository;
import com.jamc.worship.repository.ThemeRepository;
import com.jamc.worship.service.SlideDeckService;
import com.jamc.worship.service.SongService;
import com.jamc.worship.service.ThemeService;
import com.jamc.worship.ui.MainFrame;
import com.jamc.worship.ui.theme.Themes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;

/**
 * Application entry point.
 *
 * <p>Startup order matters: directories first (so the log folder exists), then logging,
 * then settings and look and feel, then the database, and finally the UI.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        AppPaths paths;
        try {
            paths = AppPaths.resolveDefault();
            paths.ensureDirectories();
        } catch (RuntimeException e) {
            fatal("Could not create the application data folder.", e);
            return;
        }
        System.setProperty("jamc.logs.dir", paths.logsDir().toString());
        Logger log = LoggerFactory.getLogger(Main.class);
        log.info("Starting LiveCat Multimedia {} on Java {} ({})", version(),
                System.getProperty("java.version"), System.getProperty("os.name"));
        log.info("Data directory: {}", paths.root());

        installCrashHandler(log);
        HangWatchdog.start();
        // a normal exit says so; a log that just stops means the process was killed or crashed natively (hs_err_pid*.log)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> log.info("Process exiting"), "exit-log"));

        AppConfig config = new AppConfig(paths);
        config.load();
        com.jamc.worship.stream.MixerChannels.install(config); // Audio Mixer names, filters, layout

        try {
            SwingUtilities.invokeAndWait(() -> {
                Themes.install(config.uiTheme());
                com.jamc.worship.ui.SplashWindow.open(version());
            });
            // every dialog gets Minimize / Maximize next to Close
            com.jamc.worship.ui.DialogWindowButtons.install();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (InvocationTargetException e) {
            log.error("Could not install look and feel", e.getCause());
        }

        DatabaseManager database = new DatabaseManager(DatabaseConfig.forPaths(paths));
        try {
            database.initialize();
        } catch (RuntimeException e) {
            log.error("Database initialization failed", e);
            fatal("The song database could not be opened:\n" + paths.databaseFile()
                    + "\n\nCheck that the folder is writable and no other copy of the app is using it."
                    + "\nDetails are in the log folder:\n" + paths.logsDir(), e);
            return;
        }

        // fonts the church added in the app (Add Font…), before anything measures text
        com.jamc.worship.render.Fonts.loadUserFonts(paths.dataDir().resolve("fonts"));
        com.jamc.worship.render.Fonts.loadWindowsFonts(); // every Windows font, also per-user and app-installed ones
        Rendering.install(new SlideRenderer(new ImageCache(paths.root())));
        SongService songService = new SongService(new SongRepository(database));
        ThemeService themeService = new ThemeService(new ThemeRepository(database), new SettingsRepository(database),
                new com.jamc.worship.config.TypographySettings(config));
        SlideDeckService deckService = new SlideDeckService(new SlideDeckRepository(database));
        AppContext context = new AppContext(paths, config, database, songService, themeService, deckService,
                new ServicePlanService(new ServiceRepository(database)),
                new MediaService(new MediaRepository(database), paths),
                new BibleService(new BibleRepository(database), new BibleVersionRepository(database),
                        provider -> config.get("bible.key." + provider.name(), null),
                        config.get("bible.apiBibleUrl", "https://rest.api.bible/v1")),
                new SceneService(new SceneRepository(database), new LowerThirdRepository(database)),
                new PresentationService(new PresentationRepository(database), paths.presentationsDir(),
                        PresentationRenderers.defaults()));

        // YouTube: metadata through YouTube's APIs (the church's own key for search); the embedded player's
        // Chromium is installed into <root>/cef only when a YouTube video is first played
        com.jamc.worship.youtube.YouTubeEngine.get().configure(paths.root().resolve("cef"),
                paths.dataDir().resolve("youtube").resolve("browser"));
        com.jamc.worship.youtube.YouTubeLibrary.configure(
                new com.jamc.worship.youtube.YouTubeClient(() -> config.get(com.jamc.worship.youtube.YouTubeClient.KEY_API_KEY, null)),
                paths.dataDir().resolve("youtube").resolve("thumbnails"));

        // VLC can take a second to load: do it off the event thread while the window opens.
        Thread vlc = new Thread(() -> MediaEngine.initialize(vlcCandidates(paths, config)), "vlc-init");
        vlc.setDaemon(true);
        vlc.start();

        SwingUtilities.invokeLater(() -> {
            try {
                MainFrame frame = new MainFrame(context);
                frame.setVisible(true);
                log.info("Main window shown");
            } finally {
                com.jamc.worship.ui.SplashWindow.close();
            }
        });
    }

    /** Folders that may hold a VLC installation: configured, portable copies, then the standard locations. */
    static List<Path> vlcCandidates(AppPaths paths, AppConfig config) {
        List<Path> dirs = new ArrayList<>();
        String configured = config.get("media.vlcPath", null);
        if (configured != null && !configured.isBlank()) {
            dirs.add(Path.of(configured));
        }
        dirs.add(paths.root().resolve("vlc"));
        try {
            Path jarDir = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            if (jarDir != null) {
                dirs.add(jarDir.resolve("vlc"));
            }
        } catch (Exception ignored) {
            // running from an unusual class path: skip the portable folder next to the jar
        }
        dirs.add(Path.of("C:\\Program Files\\VideoLAN\\VLC"));
        return dirs;
    }

    /**
     * Logs uncaught exceptions instead of letting them kill the UI. During a live service an
     * unexpected error in one panel must never take the whole application down.
     */
    private static void installCrashHandler(Logger log) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            log.error("Uncaught exception on thread {}", thread.getName(), error);
            if (error instanceof OutOfMemoryError) {
                return;
            }
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Something went wrong, but the presenter is still running.\n\n" + error
                            + "\n\nDetails were written to the log file.",
                    com.jamc.worship.ui.BrandingManager.name(), JOptionPane.WARNING_MESSAGE));
        });
    }

    private static void fatal(String message, Throwable error) {
        System.err.println(message);
        if (error != null) {
            error.printStackTrace();
        }
        try {
            SwingUtilities.invokeAndWait(() -> {
                com.jamc.worship.ui.SplashWindow.close();
                JOptionPane.showMessageDialog(null, message,
                        com.jamc.worship.ui.BrandingManager.name(), JOptionPane.ERROR_MESSAGE);
            });
        } catch (Exception ignored) {
            // headless or interrupted: the message is already on stderr
        }
        System.exit(1);
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "(development)" : v;
    }
}
