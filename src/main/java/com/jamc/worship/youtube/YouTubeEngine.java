package com.jamc.worship.youtube;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The embedded YouTube player's engine: an off-screen Chromium (JCEF) that runs YouTube's official
 * IFrame player. The player page is served from memory inside Chromium under {@link #PAGE_ORIGIN}:
 * strict video owners (sports leagues, music labels) refuse embeds from IP / localhost pages with
 * error 150 even when embedding is allowed, so the page needs a proper https origin of its own.
 *
 * <p>Nothing starts until a YouTube video is first played. The Chromium files (about 370 MB unpacked) are
 * downloaded once into {@code <data>/cef} the first time; after that it starts in about a second.
 * Lyrics never need this: saved lyrics are plain text and work offline.
 */
public final class YouTubeEngine {

    private static final Logger log = LoggerFactory.getLogger(YouTubeEngine.class);
    private static final YouTubeEngine INSTANCE = new YouTubeEngine();
    /** The player page's own origin (".local" is reserved for local networks: never a real website). */
    static final String PAGE_ORIGIN = "https://jamc-worship-presenter.local";

    public enum State { NOT_STARTED, INSTALLING, STARTING, READY, FAILED }

    private volatile Path installDir;
    private volatile Path cacheDir;
    private volatile State state = State.NOT_STARTED;
    private volatile String status = "";
    private volatile float progress = -1;
    private volatile String problem;
    private volatile CefApp app;
    /** The bundled player page, loaded when the engine starts. */
    private volatile byte[] page;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Work waiting for READY; run on the event thread. Guarded by {@code this}. */
    private final List<Runnable> waiting = new ArrayList<>();

    private YouTubeEngine() {
    }

    public static YouTubeEngine get() {
        return INSTANCE;
    }

    /** Where the Chromium files and the browser cache live. Call once at startup. */
    public void configure(Path installDir, Path cacheDir) {
        this.installDir = installDir;
        this.cacheDir = cacheDir;
    }

    public State state() {
        return state;
    }

    /** False only after the engine failed to start (then {@link #problem()} says why). */
    public boolean isUsable() {
        return state != State.FAILED;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    /** Whether the Chromium files are already installed (so starting needs no download). */
    public boolean isInstalled() {
        Path dir = installDir;
        return dir != null && Files.isRegularFile(dir.resolve("install.lock"));
    }

    /** What the engine is doing, for the operator ("Downloading the YouTube player… 42%"). */
    public String status() {
        return status;
    }

    /** 0..100 while downloading or unpacking, else -1. */
    public float progress() {
        return progress;
    }

    public String problem() {
        return problem;
    }

    /** Called on the event thread whenever the state or progress changes. */
    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public void removeListener(Runnable l) {
        listeners.remove(l);
    }

    /**
     * Runs {@code work} on the event thread once the engine is ready, starting it if needed. Work
     * queued while the engine fails to start is dropped (the failure is reported through the listeners).
     */
    public void whenReady(Runnable work) {
        synchronized (this) {
            if (state != State.READY) {
                waiting.add(work);
                start();
                return;
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            work.run();
        } else {
            SwingUtilities.invokeLater(work);
        }
    }

    /** Starts (and on first use installs) the engine in the background. Safe to call repeatedly. */
    public synchronized void start() {
        if (state != State.NOT_STARTED && state != State.FAILED) {
            return;
        }
        if (installDir == null) {
            fail("The YouTube player is not configured.", null);
            return;
        }
        state = isInstalled() ? State.STARTING : State.INSTALLING;
        status = isInstalled() ? "Starting the YouTube player…" : "Downloading the YouTube player (one time, about 120 MB)…";
        problem = null;
        fire();
        Thread t = new Thread(this::boot, "youtube-engine");
        t.setDaemon(true);
        t.start();
    }

    private void boot() {
        try {
            page = playerPage();

            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(installDir.toFile());
            builder.getCefSettings().windowless_rendering_enabled = true;
            builder.getCefSettings().cache_path = cacheDir.toAbsolutePath().toString();
            builder.getCefSettings().log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
            builder.addJcefArgs("--autoplay-policy=no-user-gesture-required", "--disable-extensions",
                    "--no-default-browser-check", "--disable-component-update");
            builder.setProgressHandler(this::progress);
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            });
            long t0 = System.currentTimeMillis();
            CefApp built = builder.build();
            app = built;
            List<Runnable> run;
            synchronized (this) {
                state = State.READY;
                status = "";
                progress = -1;
                run = new ArrayList<>(waiting);
                waiting.clear();
            }
            log.info("YouTube player engine ready in {} ms (Chromium {})", System.currentTimeMillis() - t0, safeVersion(built));
            SwingUtilities.invokeLater(() -> {
                listeners.forEach(Runnable::run);
                run.forEach(Runnable::run);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Starting the YouTube player was interrupted.", e);
        } catch (Exception | LinkageError e) {
            fail(isInstalled() ? "The YouTube player could not start: " + e.getMessage()
                    : "The YouTube player could not be downloaded. Check the internet connection and try again.\n("
                    + e.getMessage() + ")", e);
        }
    }

    private void progress(EnumProgress p, float percent) {
        progress = percent;
        status = switch (p) {
            case LOCATING -> "Looking for the YouTube player…";
            case DOWNLOADING -> "Downloading the YouTube player (one time)…";
            case EXTRACTING -> "Unpacking the YouTube player (one time)…";
            case INSTALL -> "Installing the YouTube player…";
            default -> "Starting the YouTube player…";
        };
        fire();
    }

    private synchronized void fail(String message, Throwable e) {
        log.warn("YouTube engine: {}", message, e);
        state = State.FAILED;
        problem = message;
        status = message;
        progress = -1;
        waiting.clear();
        fire();
    }

    private void fire() {
        SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
    }

    /**
     * A new browser client (one per player, so console messages need no routing). Only when READY. Requests
     * for {@link #PAGE_ORIGIN}{@code /player.html} are answered inside Chromium with the bundled player page;
     * nothing is fetched from that address.
     */
    /** A plain browser client for any web page (Browser sources); only valid once the engine is ready. */
    public CefClient newWebClient() {
        CefApp a = app;
        if (a == null) {
            throw new IllegalStateException("The browser engine is not ready");
        }
        return a.createClient();
    }

    CefClient newClient() {
        CefApp a = app;
        byte[] html = page;
        if (a == null || html == null) {
            throw new IllegalStateException("YouTube engine is not ready");
        }
        CefClient client = a.createClient();
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame, CefRequest request,
                                                                       boolean navigation, boolean download, String initiator,
                                                                       BoolRef disableDefault) {
                return request.getURL().startsWith(PAGE_ORIGIN + "/player.html") ? new PageRequest(html) : null;
            }
        });
        return client;
    }

    /** The player page for a video. */
    String playerUrl(String videoId, boolean autoplay, boolean muted, boolean loop, int volume) {
        return PAGE_ORIGIN + "/player.html?v=" + videoId + "&autoplay=" + (autoplay ? 1 : 0)
                + "&muted=" + (muted ? 1 : 0) + "&loop=" + (loop ? 1 : 0) + "&volume=" + volume;
    }

    /** Serves the bundled player page from memory. */
    private static final class PageRequest extends CefResourceRequestHandlerAdapter {
        private final byte[] html;

        PageRequest(byte[] html) {
            this.html = html;
        }

        @Override
        public CefResourceHandler getResourceHandler(CefBrowser browser, CefFrame frame, CefRequest request) {
            return new CefResourceHandlerAdapter() {
                private int pos;

                @Override
                public boolean processRequest(CefRequest req, CefCallback callback) {
                    callback.Continue();
                    return true;
                }

                @Override
                public void getResponseHeaders(CefResponse response, IntRef length, StringRef redirect) {
                    response.setMimeType("text/html");
                    response.setStatus(200);
                    response.setHeaderByName("Cache-Control", "no-store", true);
                    length.set(html.length);
                }

                @Override
                public boolean readResponse(byte[] out, int bytesToRead, IntRef bytesRead, CefCallback callback) {
                    int n = Math.min(bytesToRead, html.length - pos);
                    if (n <= 0) {
                        bytesRead.set(0);
                        return false;
                    }
                    System.arraycopy(html, pos, out, 0, n);
                    pos += n;
                    bytesRead.set(n);
                    return true;
                }
            };
        }
    }

    /** Closes Chromium. Call once on exit. */
    public void shutdown() {
        CefApp a = app;
        app = null;
        if (a != null) {
            try {
                a.dispose();
            } catch (RuntimeException e) {
                log.debug("Error disposing the YouTube engine", e);
            }
        }
    }

    private static String safeVersion(CefApp app) {
        try {
            return String.valueOf(app.getVersion().getChromeVersion());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static byte[] playerPage() throws IOException {
        try (InputStream in = YouTubeEngine.class.getResourceAsStream("/youtube/player.html")) {
            if (in == null) {
                throw new IOException("player.html is missing from the application");
            }
            return in.readAllBytes();
        }
    }
}
