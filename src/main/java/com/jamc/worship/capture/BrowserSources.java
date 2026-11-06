package com.jamc.worship.capture;

import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.youtube.YouTubeEngine;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefPaintEvent;
import org.cef.browser.HeadlessBrowser;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BROWSER sources: web pages drawn by an off-screen Chromium (the same engine as the YouTube player), live.
 * One page runs per address and size, only while some output asks for its picture; it closes a while after
 * the last request. The page's sound plays on the Windows default output.
 */
public final class BrowserSources {

    private static final Logger log = LoggerFactory.getLogger(BrowserSources.class);
    /** A page not asked for this long is closed. */
    private static final long IDLE_MS = 15_000;
    public static final int DEFAULT_WIDTH = 1920;
    public static final int DEFAULT_HEIGHT = 1080;

    private static final Map<String, Page> PAGES = new ConcurrentHashMap<>();
    private static Timer reaper;

    private BrowserSources() {
    }

    /** The page's latest picture, or null while it loads (or when the browser engine is unavailable). */
    public static BufferedImage frame(String url, Integer width, Integer height) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int w = width == null || width < 64 ? DEFAULT_WIDTH : Math.min(width, 3840);
        int h = height == null || height < 64 ? DEFAULT_HEIGHT : Math.min(height, 2160);
        String address = normalize(url);
        Page p = PAGES.computeIfAbsent(address + "|" + w + "x" + h, k -> {
            Page created = new Page(address, w, h);
            SwingUtilities.invokeLater(created::open);
            return created;
        });
        p.lastAsked = System.currentTimeMillis();
        ensureReaper();
        return p.front;
    }

    /** Why there is no picture yet ("Loading…", the engine's problem), or null when the page shows. */
    public static String status(String url) {
        if (!YouTubeEngine.get().isUsable()) {
            return "Browser unavailable: " + YouTubeEngine.get().problem();
        }
        String address = url == null ? "" : normalize(url);
        for (Page p : PAGES.values()) {
            if (p.url.equals(address)) {
                return p.front == null ? "Loading " + address + "…" : null;
            }
        }
        return "Loading…";
    }

    /** Loads the page again (e.g. after it changed online). */
    public static void reload(String url) {
        String address = url == null ? "" : normalize(url);
        SwingUtilities.invokeLater(() -> PAGES.values().stream().filter(p -> p.url.equals(address))
                .forEach(p -> {
                    if (p.browser != null) {
                        p.browser.reload();
                    }
                }));
    }

    /** "example.com" becomes "https://example.com"; file paths and full addresses are kept. */
    public static String normalize(String url) {
        String u = url.strip();
        if (u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            return u;
        }
        if (u.matches("(?i)^[a-z]:\\\\.*") || u.startsWith("/")) {
            return java.nio.file.Path.of(u).toUri().toString();
        }
        return "https://" + u;
    }

    private static void ensureReaper() {
        SwingUtilities.invokeLater(() -> {
            if (reaper == null) {
                reaper = new Timer(5000, e -> {
                    long now = System.currentTimeMillis();
                    PAGES.entrySet().removeIf(en -> {
                        if (now - en.getValue().lastAsked > IDLE_MS) {
                            en.getValue().close();
                            return true;
                        }
                        return false;
                    });
                });
                reaper.start();
            }
        });
    }

    // ------------------------------------------------------------------ one running page

    private static final class Page {
        final String url;
        final int width;
        final int height;
        volatile long lastAsked = System.currentTimeMillis();
        volatile BufferedImage front;
        BufferedImage back;
        volatile boolean closed;
        CefClient client;
        HeadlessBrowser browser;

        Page(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }

        void open() {
            YouTubeEngine.get().whenReady(() -> {
                if (closed || browser != null) {
                    return;
                }
                try {
                    client = YouTubeEngine.get().newWebClient();
                    client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
                        @Override
                        public boolean onBeforePopup(CefBrowser b, CefFrame frame, String target, String name) {
                            return true; // pages never open extra windows
                        }
                    });
                    browser = new HeadlessBrowser(client, url, width, height);
                    browser.addOnPaintListener(this::onPaint);
                    browser.createImmediately();
                    browser.setWindowlessFrameRate(30);
                } catch (RuntimeException e) {
                    log.warn("Could not open the browser source {}", url, e);
                }
            });
        }

        /** CEF paint callback: the buffer is only valid during this call, so copy it now. */
        private void onPaint(CefPaintEvent e) {
            if (closed) {
                return;
            }
            int w = e.getWidth();
            int h = e.getHeight();
            BufferedImage img = back;
            if (img == null || img.getWidth() != w || img.getHeight() != h) {
                img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            ByteBuffer buf = e.getRenderedFrame();
            buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(0, px, 0, Math.min(px.length, buf.capacity() / 4));
            back = front;
            front = img;
            MediaFrames.frameArrived();
        }

        void close() {
            closed = true;
            try {
                if (browser != null) {
                    browser.close(true);
                }
                if (client != null) {
                    client.dispose();
                }
            } catch (RuntimeException e) {
                log.debug("Error closing the browser source {}", url, e);
            }
            browser = null;
            client = null;
            front = null;
            back = null;
        }
    }
}
