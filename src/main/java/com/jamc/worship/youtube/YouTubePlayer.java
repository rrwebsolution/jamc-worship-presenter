package com.jamc.worship.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.media.VideoPlayback;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefPaintEvent;
import org.cef.browser.HeadlessBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plays a YouTube video in YouTube's official embedded player (off-screen Chromium, see
 * {@link YouTubeEngine}) and offers its pictures like a VLC player does, so Media Preview, the main
 * Preview, Program, the projector and the stream all draw the same frames.
 *
 * <p>Play, pause, seek, volume and mute go through the YouTube IFrame Player API; only what that
 * player supports is available (no audio device choice: YouTube's sound goes to the Windows default
 * output). The first use may wait for the engine to install; until then {@link #currentFrame()} is null.
 */
public final class YouTubePlayer implements VideoPlayback {

    private static final Logger log = LoggerFactory.getLogger(YouTubePlayer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int width;
    private final int height;
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> finishedListeners = new CopyOnWriteArrayList<>();
    private CefClient client;
    private HeadlessBrowser browser;
    private volatile BufferedImage front;
    private BufferedImage back;
    private volatile String mrl;
    private volatile String videoId;
    private volatile boolean loop;
    private volatile boolean muted;
    private volatile int volume = 100;
    private volatile boolean released;
    private volatile String error;
    // last state reported by the page
    private volatile int ytState = -1;
    private volatile long timeMs;
    private volatile long lengthMs = -1;
    /** Play / pause asked for but not yet confirmed by the page (keeps the PLAY button honest meanwhile). */
    private volatile Boolean wantPlaying;
    /** Whether YouTube's own player is muted right now (null until it reports). */
    private volatile Boolean pageMuted;
    /** The last play / pause / stop asked for (kept after the page confirms it). */
    private volatile boolean intendPlaying;
    /** Playing YouTube's Mix after the first video (UP NEXT); the video changes as YouTube moves on. */
    private volatile boolean upNext;
    /** The video really playing now, as the page reports it (changes during UP NEXT). */
    private volatile String nowId;
    private volatile String nowTitle;
    private volatile String nowChannel;

    public YouTubePlayer() {
        this(1280, 720);
    }

    /** @param width,height the size of the delivered frames */
    public YouTubePlayer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void play(String path, boolean loop, boolean muted, String... options) {
        load(path, true, loop, muted);
    }

    @Override
    public void cue(String path, boolean loop) {
        load(path, false, loop, muted);
    }

    private void load(String path, boolean autoplay, boolean loop, boolean muted) {
        String id = YouTubeRef.id(path);
        if (released || id == null) {
            return;
        }
        this.mrl = path;
        this.videoId = id;
        this.loop = loop;
        this.muted = muted;
        this.error = null;
        this.timeMs = 0;
        this.lengthMs = -1;
        this.ytState = -1;
        this.wantPlaying = autoplay;
        this.intendPlaying = autoplay;
        this.upNext = false;
        this.nowId = id;
        fireState();
        YouTubeEngine.get().whenReady(() -> {
            if (released || !id.equals(videoId)) {
                return;
            }
            if (browser == null) {
                createBrowser(id, autoplay);
            } else {
                js("jamc.loop(" + loop + ");jamc.mute(" + muted + ");jamc.load('" + id + "'," + autoplay + ")");
            }
        });
    }

    private void createBrowser(String id, boolean autoplay) {
        try {
            YouTubeEngine engine = YouTubeEngine.get();
            client = engine.newClient();
            client.addDisplayHandler(new CefDisplayHandlerAdapter() {
                @Override
                public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level, String message, String source, int line) {
                    if (message != null && message.startsWith("JAMC:")) {
                        onMessage(message.substring(5));
                    }
                    return true;
                }
            });
            client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
                @Override
                public boolean onBeforePopup(CefBrowser b, org.cef.browser.CefFrame frame, String url, String name) {
                    return true; // a click can never open YouTube pages or ads in new windows
                }
            });
            browser = new HeadlessBrowser(client, engine.playerUrl(id, autoplay, muted, loop, Math.min(100, volume)), width, height);
            browser.addOnPaintListener(this::onPaint);
            browser.createImmediately();
            browser.setWindowlessFrameRate(30);
        } catch (RuntimeException e) {
            error = "The YouTube player could not start: " + e.getMessage();
            log.warn("Could not create the YouTube browser for {}", id, e);
            fireState();
        }
    }

    // ------------------------------------------------------------------ frames and state from the page

    /** CEF paint callback: the buffer is only valid during this call, so copy it now. */
    private void onPaint(CefPaintEvent e) {
        if (released) {
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
        // BGRA bytes = little-endian 0xAARRGGBB ints; TYPE_INT_RGB ignores the alpha byte
        buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(0, px, 0, Math.min(px.length, buf.capacity() / 4));
        back = front;
        front = img;
        MediaFrames.frameArrived();
    }

    private void onMessage(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            String ev = n.path("e").asText();
            if (n.has("t")) {
                timeMs = Math.round(n.path("t").asDouble() * 1000);
            }
            if (n.has("d") && n.path("d").asDouble() > 0) {
                lengthMs = Math.round(n.path("d").asDouble() * 1000);
            }
            if (n.has("m")) {
                pageMuted = n.path("m").asBoolean();
            }
            int before = ytState;
            if (n.has("s")) {
                ytState = n.path("s").asInt();
            }
            String vid = n.path("vid").asText("");
            boolean moved = !vid.isEmpty() && !vid.equals(nowId);
            if (!vid.isEmpty()) {
                nowId = vid;
                nowTitle = n.path("ti").asText("");
                nowChannel = n.path("au").asText("");
            }
            if (moved) {
                lengthMs = -1; // the next video's length comes with its next report
                fireState();
            }
            switch (ev) {
                case "error" -> {
                    error = errorText(n.path("code").asInt());
                    log.warn("YouTube player error {} for {}", n.path("code").asInt(), videoId);
                    fireState();
                }
                case "state" -> {
                    if (ytState == 1 || ytState == 2 || ytState == 0) {
                        wantPlaying = null; // the page confirmed play / pause / end
                    }
                    fireState();
                    if (ytState == 0) {
                        SwingUtilities.invokeLater(() -> finishedListeners.forEach(Runnable::run));
                    }
                }
                case "ready" -> fireState();                default -> {
                    if (before != ytState) {
                        fireState();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Unreadable YouTube player message {}", json, e);
        }
    }

    static String errorText(int code) {
        return switch (code) {
            case 2 -> "YouTube does not recognise this video id.";
            case 5 -> "YouTube's player could not play this video here.";
            case 100 -> "This YouTube video was removed or made private.";
            case 101, 150 -> "The owner of this YouTube video does not allow it to be played outside YouTube.";
            case 152, 153 -> "YouTube refused the player's setup (error " + code + "). Restart the app and try again.";
            default -> "The YouTube video could not be played (error " + code + ").";
        };
    }

    /** Why the video does not play, or null. */
    public String errorMessage() {
        return error;
    }

    /** YouTube's own mute state as last reported by the page (null = not known yet). */
    public Boolean pageMuted() {
        return pageMuted;
    }

    public String videoId() {
        return videoId;
    }

    /**
     * UP NEXT, like youtube.com: continues with YouTube's own Mix for the video that was played (from the video after
     * it); YouTube then moves on by itself. Videos that may not play outside YouTube are skipped.
     */
    public void playUpNext() {
        String seed = nowId != null ? nowId : videoId;
        if (seed == null || released) {
            return;
        }
        upNext = true;
        wantPlaying = true;
        intendPlaying = true;
        js("jamc.upNext('" + seed.replaceAll("[^A-Za-z0-9_-]", "") + "')");
        fireState();
    }

    /** True while UP NEXT plays YouTube's Mix (YouTube chooses each next video). */
    public boolean isUpNext() {
        return upNext;
    }

    /** The video playing now (it changes during UP NEXT): id, title and channel as YouTube reports them. */
    public String nowPlayingId() {
        return nowId;
    }

    public String nowPlayingTitle() {
        return nowTitle;
    }

    public String nowPlayingChannel() {
        return nowChannel;
    }

    // ------------------------------------------------------------------ controls

    private void js(String code) {
        if (released) {
            return;
        }
        Runnable run = () -> {
            if (browser != null && !released) {
                browser.executeJavaScript(code, "jamc", 0);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            run.run();
        } else {
            SwingUtilities.invokeLater(run);
        }
    }

    @Override
    public void playOrResume() {
        if (mrl == null) {
            return;
        }
        wantPlaying = true;
        intendPlaying = true;
        js("jamc.play()");
        fireState();
    }

    @Override
    public void pause() {
        wantPlaying = false;
        intendPlaying = false;
        js("jamc.pause()");
        fireState();
    }

    @Override
    public void stop() {
        wantPlaying = false;
        intendPlaying = false;
        timeMs = 0;
        js("jamc.stop()");
        fireState();
    }

    @Override
    public void seek(float position) {
        if (lengthMs > 0) {
            seekTime((long) (Math.clamp(position, 0f, 1f) * lengthMs));
        }
    }

    @Override
    public void seekTime(long ms) {
        timeMs = Math.max(0, ms);
        js("jamc.seek(" + (Math.max(0, ms) / 1000.0) + ")");
    }

    @Override
    public void setVolume(int volume) {
        this.volume = Math.clamp(volume, 0, 200);
        js("jamc.volume(" + Math.min(100, this.volume) + ")"); // YouTube's player stops at 100 %
    }

    @Override
    public int volume() {
        return volume;
    }

    @Override
    public void setMuted(boolean muted) {
        this.muted = muted;
        js("jamc.mute(" + muted + ")");
    }

    @Override
    public boolean isMuted() {
        return muted;
    }

    @Override
    public void setLoop(boolean loop) {
        this.loop = loop;
        js("jamc.loop(" + loop + ")");
    }

    @Override
    public boolean isLoop() {
        return loop;
    }

    @Override
    public boolean isPlaying() {
        Boolean want = wantPlaying;
        if (want != null) {
            return want;
        }
        // buffering counts only when playing was asked for: seeking a paused or stopped video also buffers
        return ytState == 1 || (ytState == 3 && intendPlaying);
    }

    @Override
    public long time() {
        return timeMs;
    }

    @Override
    public long length() {
        return lengthMs;
    }

    @Override
    public boolean hasMedia() {
        return mrl != null;
    }

    @Override
    public String mrl() {
        return mrl;
    }

    @Override
    public BufferedImage currentFrame() {
        return front;
    }

    @Override
    public boolean hasError() {
        return error != null;
    }

    /** YouTube's sound always goes to the Windows default output device. */
    @Override
    public void setOutputDevice(String deviceId) {
    }

    @Override
    public void addStateListener(Runnable listener) {
        stateListeners.add(listener);
    }

    @Override
    public void addFinishedListener(Runnable listener) {
        finishedListeners.add(listener);
    }

    @Override
    public void release() {
        if (released) {
            return;
        }
        released = true;
        Runnable close = () -> {
            try {
                if (browser != null) {
                    browser.close(true);
                }
                if (client != null) {
                    client.dispose();
                }
            } catch (RuntimeException e) {
                log.debug("Error closing the YouTube player", e);
            }
            browser = null;
            client = null;
        };
        if (SwingUtilities.isEventDispatchThread()) {
            close.run();
        } else {
            SwingUtilities.invokeLater(close);
        }
        front = null;
        back = null;
    }

    private void fireState() {
        SwingUtilities.invokeLater(() -> stateListeners.forEach(Runnable::run));
    }
}
