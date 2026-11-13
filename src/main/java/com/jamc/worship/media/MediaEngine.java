package com.jamc.worship.media;

import com.sun.jna.NativeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates VLC and creates media players.
 *
 * <p>VLC is looked for in, in order: a configured folder, a portable {@code vlc} folder inside the
 * data directory or next to the application, then the normal installation. If VLC cannot be
 * found, video, audio and camera features report themselves unavailable instead of failing, and
 * slides, Bible and output keep working.
 */
public final class MediaEngine {

    private static final Logger log = LoggerFactory.getLogger(MediaEngine.class);
    private static volatile MediaEngine instance = new MediaEngine(null, "Media playback has not been started yet.");

    private final MediaPlayerFactory factory;
    private final String problem;
    private final List<Runnable> releasers = new ArrayList<>();

    private MediaEngine(MediaPlayerFactory factory, String problem) {
        this.factory = factory;
        this.problem = problem;
    }

    public static MediaEngine get() {
        return instance;
    }

    /**
     * Initializes VLC. Slow (up to a second or two), so call it from a background thread at startup.
     *
     * @param candidates extra folders that may contain {@code libvlc.dll}
     */
    public static synchronized MediaEngine initialize(List<Path> candidates) {
        if (instance.isAvailable()) {
            return instance;
        }
        try {
            boolean found = false;
            for (Path dir : candidates) {
                if (dir != null && Files.isRegularFile(dir.resolve(RuntimeUtil.isWindows() ? "libvlc.dll" : "libvlc.so"))) {
                    NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), dir.toString());
                    NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), dir.toString());
                    log.info("Using VLC from {}", dir);
                    found = true;
                    break;
                }
            }
            if (!found) {
                found = new NativeDiscovery().discover();
            }
            if (!found) {
                instance = new MediaEngine(null, "VLC media player was not found. Install the free 64-bit VLC "
                        + "(videolan.org) or place a portable VLC in a \"vlc\" folder, then restart.");
                log.warn("VLC not found; video, audio and camera are disabled");
                return instance;
            }
            // DirectSound: each player keeps its own volume and mute. With Windows' default VLC output (mmdevice) all
            // players share one audio session, so muting any player (a silent background, a Preview video) muted every
            // video and song in the app.
            MediaPlayerFactory f = new MediaPlayerFactory("--quiet", "--no-video-title-show", "--no-snapshot-preview",
                    "--intf=dummy", "--no-stats", "--aout=directsound");
            instance = new MediaEngine(f, null);
            log.info("VLC initialized");
        } catch (Throwable e) {
            instance = new MediaEngine(null, "VLC could not be started: " + e.getMessage()
                    + ". Make sure the 64-bit version of VLC is installed.");
            log.warn("VLC initialization failed", e);
        }
        return instance;
    }

    public boolean isAvailable() {
        return factory != null;
    }

    /** Why media is unavailable, or null when it works. */
    public String problem() {
        return problem;
    }

    EmbeddedMediaPlayer newVideoPlayer() {
        requireAvailable();
        return factory.mediaPlayers().newEmbeddedMediaPlayer();
    }

    MediaPlayer newAudioPlayer() {
        requireAvailable();
        return factory.mediaPlayers().newMediaPlayer();
    }

    MediaPlayerFactory factory() {
        requireAvailable();
        return factory;
    }

    /** Registers cleanup to run on shutdown (players release before the factory). */
    synchronized void onShutdown(Runnable releaser) {
        releasers.add(releaser);
    }

    synchronized void forget(Runnable releaser) {
        releasers.remove(releaser);
    }

    /**
     * Releasing a player stops its media first, which waits for VLC: measured 75–980 ms for a streamed (Pixabay)
     * background. Players that are no longer shown are released here, one after another, instead of on the Swing
     * thread, where Preview and Program froze for that long at every background change.
     */
    private final java.util.concurrent.ExecutorService releasing = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vlc-release");
        t.setDaemon(true);
        return t;
    });

    void releaseLater(Runnable release) {
        try {
            releasing.execute(release);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            release.run(); // shutting down
        }
    }

    /** At exit, how long the Swing thread waits for VLC; the process ends right after anyway, freeing everything. */
    private static final long EXIT_RELEASE_MS = 1_500;

    /**
     * Stops and releases every player and the VLC instance. It all runs on the release thread, after the players
     * already queued there (they must be done before the VLC instance goes). VLC's stop can hang for seconds (a
     * streamed video, a stuck driver): the caller waits at most {@link #EXIT_RELEASE_MS} instead of freezing the
     * closing window, and the process exit frees whatever is left.
     */
    public void shutdown() {
        try {
            releasing.execute(this::releaseAll);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return; // already shut down
        }
        releasing.shutdown();
        try {
            if (!releasing.awaitTermination(EXIT_RELEASE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                log.warn("VLC was still releasing its players after {} ms; the app closes anyway", EXIT_RELEASE_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void releaseAll() {
        for (Runnable r : List.copyOf(releasers)) {
            try {
                r.run();
            } catch (Throwable e) {
                log.warn("Error releasing media player", e);
            }
        }
        releasers.clear();
        if (factory != null) {
            try {
                factory.release();
            } catch (Throwable e) {
                log.warn("Error releasing VLC", e);
            }
        }
    }

    private void requireAvailable() {
        if (factory == null) {
            throw new IllegalStateException(problem);
        }
    }
}
