package com.jamc.worship.background;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.media.MediaEngine;
import com.jamc.worship.media.VideoPlayer;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Gradient;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.render.GlobalBackgroundView;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.util.Colors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Owns the operator's global background (a looping video or a still image): one long-lived player for what is live, kept apart
 * from all content. Songs, Bible verses, slides, service items and library navigation never reach
 * this class, so the video keeps its playback position until the operator switches it off, pauses
 * or restarts it, or takes another background.
 *
 * <p>Another background can be previewed in its own player without touching Program; TAKE hands
 * the already-running preview player over to Program (no reload, no black flash) and blends from
 * the old video with Cut, Fade or Crossfade.
 *
 * <p>Settings (file, loop, mute, volume, overlay, fit, change style) are remembered between runs; the
 * background always starts switched off, because turning it on is an operator decision.
 *
 * <p>Confined to the Swing event thread.
 */
public final class GlobalBackgroundManager implements GlobalBackgroundView {

    private static final Logger log = LoggerFactory.getLogger(GlobalBackgroundManager.class);

    public enum Change { CUT, FADE, CROSSFADE;

        @Override
        public String toString() {
            return switch (this) {
                case CUT -> "Cut";
                case FADE -> "Fade";
                case CROSSFADE -> "Crossfade";
            };
        }
    }

    /** What the panel shows about the live background. */
    public enum Status { NONE, OFF, ACTIVE, PAUSED, UNAVAILABLE }

    static final String K_PATH = "background.global.path";
    static final String K_LOOP = "background.global.loop";
    static final String K_MUTED = "background.global.muted";
    static final String K_VOLUME = "background.global.volume";
    static final String K_OVERLAY = "background.global.overlay";
    static final String K_FIT = "background.global.fit";
    static final String K_CHANGE = "background.global.change";
    static final String K_CHANGE_MS = "background.global.changeMs";
    static final String K_KEEP_IN_BLACK = "background.global.keepPlayingInBlack";
    /** Whether the background was on when the app closed (off by the operator stays off after a restart). */
    static final String K_ON = "background.global.on";
    /** Settings: when the app opens, the last TAKE AS BACKGROUND plays again (default on). */
    public static final String KEY_RESUME_ON_START = "background.global.resumeOnStart";

    /** A solid-color background is stored as {@code color:#RRGGBB}. */
    public static final String COLOR_PREFIX = "color:";
    /** A gradient background is stored as {@code gradient:#RRGGBB,#RRGGBB,angle} (see {@link Gradient}). */
    public static final String GRADIENT_PREFIX = "gradient:";

    private final AppConfig config;
    private final Function<String, Path> resolver;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Timer changeTimer = new Timer(15, e -> tickChange());

    private String path;
    private Layer player;
    private boolean on;
    private boolean paused;
    private boolean pausedForBlack;
    /**
     * False while neither Preview nor Program shows a scene with a (visible) Background source: nothing draws the
     * background then, so its video is held (no decoding, no lag) and its controls are hidden, until a scene with one
     * is shown again. It continues from the same point.
     */
    private boolean used = true;

    private String previewPath;
    private Layer previewPlayer;

    /** A background video chosen in the Library, open and paused, ready for TAKE AS BACKGROUND ({@link #prepare}). */
    private String preparedPath;
    private Layer prepared;

    private Layer outgoing;
    private Change activeChange = Change.CUT;
    private long changeStart = -1;

    private boolean loop;
    private boolean muted;
    private int volume;
    private double overlay;
    private FitMode fit;
    private Change change;
    private int changeMs;
    private boolean keepPlayingInBlack;

    /** @param resolver turns a stored media path (absolute or relative to the data folder) into a file */
    public GlobalBackgroundManager(AppConfig config, Function<String, Path> resolver) {
        this.config = config;
        this.resolver = resolver;
        this.path = config.get(K_PATH, null);
        this.loop = config.getBoolean(K_LOOP, true);
        this.muted = config.getBoolean(K_MUTED, true);
        this.volume = parse(config.get(K_VOLUME, "100"), 100);
        this.overlay = Math.clamp(parse(config.get(K_OVERLAY, "0"), 0) / 100.0, 0.0, 0.9);
        this.fit = "FIT".equals(config.get(K_FIT, "FILL")) ? FitMode.FIT : FitMode.FILL;
        this.change = parseChange(config.get(K_CHANGE, "CROSSFADE"));
        this.changeMs = Math.clamp(parse(config.get(K_CHANGE_MS, "1000"), 1000), 0, 5000);
        this.keepPlayingInBlack = config.getBoolean(K_KEEP_IN_BLACK, true);
        changeTimer.setCoalesce(true);
    }

    // ------------------------------------------------------------------ state

    public boolean isAvailable() {
        return MediaEngine.get().isAvailable();
    }

    public String unavailableReason() {
        return MediaEngine.get().problem();
    }

    @Override
    public boolean isActive() {
        return on && player != null;
    }

    public boolean isOn() {
        return on;
    }

    public boolean isPaused() {
        return paused;
    }

    /** The live (or last chosen) background file, or null. */
    public String path() {
        return path;
    }

    public String previewPath() {
        return previewPath;
    }

    @Override
    public boolean hasPreview() {
        return previewPlayer != null;
    }

    public Status status() {
        if (path == null) {
            return Status.NONE;
        }
        if (!canShow(path)) {
            return Status.UNAVAILABLE;
        }
        if (!on) {
            return Status.OFF;
        }
        return paused ? Status.PAUSED : Status.ACTIVE;
    }

    /** Playback position of the live background in ms (for the operator panel), or -1. */
    public long position() {
        return player == null ? -1 : player.time();
    }

    public long length() {
        return player == null ? -1 : player.length();
    }

    public boolean loop() { return loop; }
    public boolean muted() { return muted; }
    public int volume() { return volume; }
    public double overlay() { return overlay; }
    public FitMode fit() { return fit; }
    public Change change() { return change; }
    public int changeMs() { return changeMs; }
    public boolean keepPlayingInBlack() { return keepPlayingInBlack; }

    /** The one global background as Preview and Program both see it. */
    public GlobalBackgroundState state() {
        boolean video = GlobalBackgroundState.typeOf(path) == GlobalBackgroundState.Type.VIDEO;
        return new GlobalBackgroundState(GlobalBackgroundState.typeOf(path), path, isActive(),
                video && isActive() && !paused && !pausedForBlack && used, loop, muted, volume, video ? position() : -1, overlay, fit);
    }

    // ------------------------------------------------------------------ operator actions

    /**
     * TAKE AS BACKGROUND: makes this the global background under Preview and Program at once and
     * switches it on. Scenes, content and output routing are not touched.
     */
    public void takeAsBackground(String newPath) {
        if (newPath == null || !canShow(newPath)) {
            return;
        }
        if (newPath.equals(path) && player != null) {
            setOn(true); // already the background: keep its position
            return;
        }
        activate(newPath);
    }


    /** Switches the live background on or off. Off hides it and holds its position; on continues from there. */
    public void setOn(boolean turnOn) {
        if (turnOn == on) {
            return;
        }
        if (turnOn) {
            if (path == null || !canShow(path)) {
                return;
            }
            on = true;
            if (player == null) {
                player = start(path, muted, !used); // no scene shows it yet: opened, waiting on its first picture
                paused = false;
            } else if (!paused && !pausedForBlack && used) {
                player.playOrResume();
            }
            log.info("Global background on: {}", fileName(path));
        } else {
            on = false;
            if (player != null) {
                player.pause();
            }
            finishChange();
            log.info("Global background off");
        }
        save(K_ON, String.valueOf(on));
        fire();
    }

    /** Whether a scene on Preview or Program has a visible Background source (see {@link #used}). */
    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean v) {
        if (v == used) {
            return;
        }
        used = v;
        if (player != null && on && !paused && !pausedForBlack) {
            if (v) {
                player.playOrResume(); // continues from where it was held
            } else {
                player.pause();
            }
        }
        log.info("Global background {}", v ? "shown by a scene again: playing" : "not in any scene on Preview / Program: held");
        fire();
    }

    /**
     * App start: the last background taken (TAKE AS BACKGROUND) plays again under Preview and Program, so scenes with
     * a Background source show it as before. Only when Settings allows it and it was still on when the app closed.
     */
    public void resumeOnStart() {
        if (!config.getBoolean(KEY_RESUME_ON_START, true) || !config.getBoolean(K_ON, true)
                || path == null || !canShow(path) || on) {
            return;
        }
        try {
            setOn(true);
            log.info("Global background resumed after start: {}", fileName(path));
        } catch (RuntimeException e) {
            log.warn("Could not resume the global background {}", path, e);
        }
    }

    /**
     * Loads a background into Preview only. Program keeps its background until {@link #take()}.
     * When nothing is live yet the operator can still take it immediately.
     */
    public void preview(String newPath) {
        if (newPath == null || !canShow(newPath)) {
            return;
        }
        releasePreview();
        previewPath = newPath;
        if (prepared != null && newPath.equals(preparedPath)) {
            // chosen in the Library a moment ago: already open on its first picture, so it starts at once
            previewPlayer = prepared;
            prepared = null;
            preparedPath = null;
            ((VideoLayer) previewPlayer).go();
        } else {
            previewPlayer = start(newPath, true);
        }
        fire();
    }

    /**
     * A background video chosen in the Library (MEDIA PREVIEW): it is opened now, paused on its first picture and
     * shown nowhere, so that TAKE AS BACKGROUND starts it at once instead of waiting for VLC to open it (1–1.6 s for a
     * streamed Pixabay video). Only the one chosen is kept; null, a picture or the live background lets it go.
     */
    public void prepare(String newPath) {
        if (newPath != null && newPath.equals(preparedPath)) {
            return;
        }
        releasePrepared();
        if (newPath == null || !isVideo(newPath) || !isAvailable() || (newPath.equals(path) && player != null)) {
            return;
        }
        try {
            prepared = start(newPath, true, true);
            preparedPath = newPath;
        } catch (RuntimeException e) {
            log.debug("Could not prepare background {}", newPath, e);
        }
    }

    private void releasePrepared() {
        if (prepared != null) {
            prepared.release();
        }
        prepared = null;
        preparedPath = null;
    }

    public void clearPreview() {
        releasePreview();
        fire();
    }

    /** Puts the previewed background on Program with the chosen change, and switches the background on. */
    public void take() {
        if (previewPlayer == null) {
            return;
        }
        Layer incoming = previewPlayer;
        String incomingPath = previewPath;
        previewPlayer = null;
        previewPath = null;
        incoming.setLoop(loop);
        incoming.setVolume(volume);
        incoming.setMuted(muted);

        finishChange(); // a change still running ends now
        boolean wasShowing = isActive() && player != null && player.currentFrame() != null;
        Layer old = player;
        player = incoming;
        path = incomingPath;
        paused = false;
        pausedForBlack = false;
        on = true;
        config.set(K_PATH, path);
        config.set(K_ON, "true");
        config.save();
        if (wasShowing && change != Change.CUT && changeMs > 0) {
            outgoing = old;
            activeChange = change;
            changeStart = -1; // starts when the new video shows its first picture
            changeTimer.start();
        } else if (old != null) {
            old.release();
        }
        if (!used) {
            incoming.pause(); // no scene on Preview / Program shows it: held until one does
        }
        log.info("Global background taken: {} ({})", fileName(path), wasShowing ? change : "first");
        keepCopy(path);
        fire();
    }

    /**
     * A streamed (Pixabay) background is saved on this computer once, in the background, while it plays; then it plays
     * from the copy: seamless loops and no more data (streamed, every loop downloaded the whole video again).
     */
    private void keepCopy(String taken) {
        if (!com.jamc.worship.media.OnlineMedia.isUrl(taken) || com.jamc.worship.media.OnlineMedia.hasCopy(taken)) {
            return;
        }
        com.jamc.worship.media.OnlineMedia.keepCopy(taken, () -> {
            if (taken.equals(path) && player instanceof VideoLayer v) {
                v.reload(); // the same position, now from the copy
                log.info("Background {} now plays from its saved copy", fileName(taken));
            }
        });
    }

    /** True when the background on Program is a video now (its playback controls apply). */
    public boolean isVideoActive() {
        return isActive() && isVideo(path) && player != null;
    }

    /**
     * The live background as a {@link com.jamc.worship.media.Transport}, for the standard playback controls (a scene's
     * Background source in the scene editor shows them while the background is a video). Every action is the same as
     * the background controls under Preview / Program: it changes the background on Preview and Program.
     */
    public com.jamc.worship.media.Transport transport() {
        GlobalBackgroundManager m = this;
        return new com.jamc.worship.media.Transport() {
            public void playOrResume() { m.setPaused(false); }
            public void pause() { m.setPaused(true); }
            /** Back to the start, paused. */
            public void stop() {
                if (player != null) {
                    player.seek(0f);
                }
                m.setPaused(true);
                fire();
            }
            public void seek(float position) {
                if (player != null) {
                    player.seek(Math.clamp(position, 0f, 1f));
                    fire();
                }
            }
            public void setVolume(int v) { m.setVolume(Math.min(100, v)); }
            public int volume() { return m.volume(); }
            public void setMuted(boolean v) { m.setMuted(v); }
            public boolean isMuted() { return m.muted(); }
            public void setLoop(boolean v) { m.setLoop(v); }
            public boolean isLoop() { return m.loop(); }
            public boolean isPlaying() { return isVideoActive() && !paused && !pausedForBlack; }
            public long time() { return Math.max(0, m.position()); }
            public long length() { return Math.max(0, m.length()); }
            public boolean hasMedia() { return isVideoActive(); }
            /** Not kept: the controls ask four times a second anyway, and a kept one would outlive every panel. */
            public void addStateListener(Runnable l) { }
        };
    }

    /** Plays the chosen background straight to Program (preview + take). */
    public void activate(String newPath) {
        preview(newPath);
        take();
    }

    public void restart() {
        if (player != null) {
            player.seek(0f);
            if (paused) {
                paused = false;
                player.playOrResume();
            }
            fire();
        }
    }

    /** Jumps the background video {@code ms} forward (or back when negative). */
    public void skip(long ms) {
        if (player == null || player.length() <= 0) {
            return;
        }
        long to = Math.clamp(player.time() + ms, 0, Math.max(0, player.length() - 500));
        player.seek((float) to / player.length());
        fire();
    }

    public void setPaused(boolean pause) {
        if (player == null || pause == paused) {
            return;
        }
        paused = pause;
        if (pause) {
            player.pause();
        } else if (on && !pausedForBlack && used) {
            player.playOrResume();
        }
        fire();
    }

    /** Called by the window when Program goes to or comes back from Black. */
    public void blackChanged(boolean black) {
        boolean shouldHold = black && !keepPlayingInBlack;
        if (shouldHold == pausedForBlack || player == null) {
            pausedForBlack = shouldHold && player != null;
            return;
        }
        pausedForBlack = shouldHold;
        if (shouldHold) {
            player.pause();
        } else if (on && !paused && used) {
            player.playOrResume(); // continues from the same position
        }
    }

    public void setLoop(boolean v) {
        loop = v;
        if (player != null) {
            player.setLoop(v);
        }
        save(K_LOOP, String.valueOf(v));
    }

    public void setMuted(boolean v) {
        muted = v;
        if (player != null) {
            player.setMuted(v);
        }
        save(K_MUTED, String.valueOf(v));
    }

    public void setVolume(int v) {
        volume = Math.clamp(v, 0, 100);
        if (player != null) {
            player.setVolume(volume);
        }
        save(K_VOLUME, String.valueOf(volume));
    }

    public void setOverlay(double v) {
        overlay = Math.clamp(v, 0.0, 0.9);
        save(K_OVERLAY, String.valueOf(Math.round(overlay * 100)));
    }

    public void setFit(FitMode v) {
        fit = v == FitMode.FIT ? FitMode.FIT : FitMode.FILL;
        save(K_FIT, fit.name());
    }

    public void setChange(Change v, int ms) {
        change = v == null ? Change.CROSSFADE : v;
        changeMs = Math.clamp(ms, 0, 5000);
        config.set(K_CHANGE, change.name());
        save(K_CHANGE_MS, String.valueOf(changeMs));
    }

    public void setKeepPlayingInBlack(boolean v) {
        keepPlayingInBlack = v;
        save(K_KEEP_IN_BLACK, String.valueOf(v));
    }

    /** Releases every player (application exit). */
    public void shutdown() {
        changeTimer.stop();
        for (Layer p : new Layer[]{player, outgoing, previewPlayer, prepared}) {
            if (p != null) {
                p.release();
            }
        }
        player = null;
        outgoing = null;
        previewPlayer = null;
        prepared = null;
        preparedPath = null;
    }

    // ------------------------------------------------------------------ painting

    @Override
    public boolean paint(Graphics2D g, Rectangle r) {
        if (!isActive()) {
            return false;
        }
        BufferedImage now = player.currentFrame();
        BufferedImage old = outgoing == null ? null : outgoing.currentFrame();
        float p = changeProgress();
        Composite base = g.getComposite();
        if (old != null && p < 1f) {
            switch (activeChange) {
                case CROSSFADE -> {
                    draw(g, old, r, 1f, base);
                    draw(g, now, r, p, base);
                }
                case FADE -> {
                    g.setColor(Color.BLACK);
                    g.fillRect(r.x, r.y, r.width, r.height);
                    if (p < 0.5f) {
                        draw(g, old, r, 1f - p * 2f, base);
                    } else {
                        draw(g, now, r, p * 2f - 1f, base);
                    }
                }
                case CUT -> draw(g, now != null ? now : old, r, 1f, base);
            }
        } else if (now != null) {
            draw(g, now, r, 1f, base);
        } else {
            return false;
        }
        g.setComposite(base);
        paintOverlay(g, r);
        return true;
    }

    @Override
    public boolean paintPreview(Graphics2D g, Rectangle r) {
        if (previewPlayer == null) {
            return paint(g, r);
        }
        BufferedImage frame = previewPlayer.currentFrame();
        if (frame == null) {
            return false;
        }
        draw(g, frame, r, 1f, g.getComposite());
        paintOverlay(g, r);
        return true;
    }

    /** One frozen picture per background player, for scene thumbnails (kept until that player is replaced). */
    private final java.util.Map<Layer, BufferedImage> stills = new java.util.WeakHashMap<>();
    private final java.util.Map<Layer, Long> stillWait = new java.util.WeakHashMap<>();
    /** A nearly black first frame (a fade-in) is not frozen; after this long the next frame is taken anyway. */
    private static final long STILL_BLACK_WAIT_MS = 4000;

    @Override
    public boolean paintStill(Graphics2D g, Rectangle r, boolean preview) {
        Layer src = preview && previewPlayer != null ? previewPlayer : player;
        if (src == null || (src == player && !isActive())) {
            return false;
        }
        BufferedImage still = stills.get(src);
        if (still == null) {
            BufferedImage frame = src.currentFrame();
            if (frame == null) {
                return false; // no picture yet: the next thumbnail refresh tries again
            }
            long since = stillWait.computeIfAbsent(src, k -> System.currentTimeMillis());
            if (nearlyBlack(frame) && System.currentTimeMillis() - since < STILL_BLACK_WAIT_MS) {
                return false;
            }
            still = copy(frame); // a copy: the player keeps writing into its own frame buffers
            stills.put(src, still);
        }
        draw(g, still, r, 1f, g.getComposite());
        paintOverlay(g, r);
        return true;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** True for an (almost) black picture, sampled on a coarse grid. */
    public static boolean nearlyBlack(BufferedImage img) {
        long sum = 0;
        int n = 0;
        for (int y = img.getHeight() / 10; y < img.getHeight(); y += Math.max(1, img.getHeight() / 10)) {
            for (int x = img.getWidth() / 10; x < img.getWidth(); x += Math.max(1, img.getWidth() / 10)) {
                int rgb = img.getRGB(x, y);
                sum += ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                n++;
            }
        }
        return n == 0 || sum / (n * 3.0) < 12;
    }

    private void paintOverlay(Graphics2D g, Rectangle r) {
        if (overlay > 0) {
            g.setColor(new Color(0f, 0f, 0f, (float) overlay));
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    private void draw(Graphics2D g, BufferedImage img, Rectangle r, float alpha, Composite base) {
        if (img == null || alpha <= 0f) {
            return;
        }
        if (fit == FitMode.FIT && alpha >= 1f) {
            g.setColor(Color.BLACK);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
        g.setComposite(alpha >= 1f ? base : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        Graphics2D clip = (Graphics2D) g.create();
        clip.clipRect(r.x, r.y, r.width, r.height);
        SlideRenderer.drawFitted(clip, img, r, fit);
        clip.dispose();
        g.setComposite(base);
    }

    @Override
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Background videos are decoded on the graphics card (VLC falls back to the processor by itself when it cannot).
     * Measured with a Pixabay 2730x1440 60 fps background: about 16 frames a second on the processor, 35 with this.
     */
    static final String HW_DECODE = ":avcodec-hw=d3d11va";

    /**
     * How much of a streamed (Pixabay) background VLC buffers before its first picture. 3000 ms made the next background
     * wait 1.2–3.7 s (measured); 1000 ms showed it in 1.0–1.6 s and still looped without a hold. Less gained little.
     */
    static final String STREAM_CACHING = ":network-caching=1000";

    private Layer start(String mediaPath, boolean startMuted) {
        return start(mediaPath, startMuted, false);
    }

    /** {@code cued}: a video opens paused on its first picture and starts only on {@link VideoLayer#go()}. */
    private Layer start(String mediaPath, boolean startMuted, boolean cued) {
        if (isSolid(mediaPath)) {
            return new ImageLayer(solidImage(mediaPath));
        }
        if (com.jamc.worship.media.OnlineMedia.isUrl(mediaPath)) {
            // an online (Pixabay) background video: streamed by VLC, nothing downloaded
            return new VideoLayer(mediaPath, loop, startMuted, volume, cued, STREAM_CACHING, HW_DECODE);
        }
        Path file = resolver.apply(mediaPath);
        if (!Files.isRegularFile(file)) {
            log.warn("Background file not found: {}", file);
        }
        if (isImage(mediaPath)) {
            return new ImageLayer(file, this::fire);
        }
        return new VideoLayer(file.toString(), loop, startMuted, volume, cued, HW_DECODE);
    }

    /** True for a still-image background (JPG, PNG, WebP...); anything else is played as a video. */
    public static boolean isImage(String mediaPath) {
        if (mediaPath == null || isSolid(mediaPath)) {
            return false;
        }
        try {
            return MediaKind.of(Path.of(mediaPath)).orElse(MediaKind.VIDEO) == MediaKind.IMAGE;
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    /** True for a solid color or gradient background (no file, no player). */
    public static boolean isSolid(String mediaPath) {
        return mediaPath != null && (mediaPath.startsWith(COLOR_PREFIX) || mediaPath.startsWith(GRADIENT_PREFIX));
    }

    /** True for a video background, which plays, pauses and restarts. */
    public static boolean isVideo(String mediaPath) {
        return mediaPath != null && !isSolid(mediaPath) && !isImage(mediaPath);
    }

    /** Images, colors and gradients need nothing; videos need the VLC engine. */
    public boolean canShow(String mediaPath) {
        return !isVideo(mediaPath) || isAvailable();
    }

    /** A 16:9 picture of a color or gradient background, scaled up like any other image when drawn. */
    public static BufferedImage solidImage(String mediaPath) {
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            if (mediaPath.startsWith(GRADIENT_PREFIX)) {
                SlideRenderer.paintGradient(g, new Rectangle(0, 0, img.getWidth(), img.getHeight()),
                        Gradient.parse(mediaPath.substring(GRADIENT_PREFIX.length())));
            } else {
                g.setColor(Colors.parse(mediaPath.substring(COLOR_PREFIX.length()), Color.BLACK));
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /** What the live background is drawn from: a video player or a still image. */
    interface Layer {
        BufferedImage currentFrame();

        void playOrResume();

        void pause();

        void seek(float position);

        void setLoop(boolean loop);

        void setMuted(boolean muted);

        void setVolume(int volume);

        long time();

        long length();

        void release();
    }

    /** Repeats inside VLC's input: the end runs straight into the start (see {@link VideoLayer}). */
    static final String SEAMLESS_LOOP = ":input-repeat=65535";

    /** VLC options for a background video; a looping one repeats inside the input instead of being reopened. */
    static String[] videoOptions(boolean loop, String... base) {
        if (!loop) {
            return base;
        }
        String[] out = java.util.Arrays.copyOf(base, base.length + 1);
        out[base.length] = SEAMLESS_LOOP;
        return out;
    }

    /**
     * A background video. Looping uses VLC's input repeat, not its playlist repeat: the playlist repeat closes and reopens
     * the file (or the Pixabay stream, buffering again) at every end, and the picture froze for 0.9 to 1.3 s before the
     * start came back. Input repeat measured no freeze for a file on this computer (under 80 ms between pictures) and
     * about 0.3 s for a stream, which has to ask the internet for the start again. (A second player opening the start
     * before the end was tried for streams: no better at the loop, and it sometimes stalled the playing stream for 3.9 s.)
     * The repeat is fixed when the video opens, so switching Loop reopens it at the same point.
     *
     * <p>A layer made with {@code cued} opens paused on its first picture (a background chosen in the Library, ready
     * before TAKE AS BACKGROUND) and starts only on {@link #go()}.
     */
    static final class VideoLayer implements Layer {
        private final VideoPlayer p = new VideoPlayer();
        private final String mrl;
        private final String[] baseOptions;
        private boolean looping;
        /** A Loop change made while paused, applied on the next resume. */
        private Boolean pendingLoop;
        private boolean muted;
        /** Opened paused, waiting for {@link #go()}. */
        private boolean cued;
        /** A saved copy became ready while paused: opened from it on the next resume. */
        private boolean pendingReload;
        /** A looping video changes over to its saved copy where it next starts again. */
        private boolean switchAtLoop;
        private long lastTime;
        /** Where a reopened video goes once VLC has opened it; -1 when nowhere. */
        private long seekTo = -1;
        private final Timer watch = new Timer(50, e -> tick());

        VideoLayer(String mrl, boolean loop, boolean muted, int volume, boolean cued, String... baseOptions) {
            this.mrl = mrl;
            this.baseOptions = baseOptions;
            this.looping = loop;
            this.muted = muted;
            this.cued = cued;
            if (cued) {
                p.setMuted(true);
                p.cue(mrl, false, videoOptions(loop, baseOptions));
            } else {
                p.play(mrl, false, muted, videoOptions(loop, baseOptions));
            }
            p.setVolume(volume);
        }

        /** Starts a layer made with {@code cued}; one VLC has not opened yet is opened again with its options. */
        void go() {
            if (!cued) {
                return;
            }
            cued = false;
            if (p.length() > 0) {
                p.setMuted(muted);
                p.playOrResume();
            } else {
                p.play(mrl, false, muted, videoOptions(looping, baseOptions));
            }
        }

        public BufferedImage currentFrame() { return p.currentFrame(); }

        /**
         * Plays the video from its saved copy from now on. A looping one changes over where it starts again anyway (the
         * stream is going back to its start there), so its picture does not jump; one that does not loop, or is paused,
         * is opened again at the same point.
         */
        void reload() {
            if (cued) {
                return; // go() opens it, from the copy
            }
            if (looping && p.isPlaying()) {
                switchAtLoop = true;
                lastTime = p.time();
                watch.start();
            } else if (p.isPlaying()) {
                reopen(looping);
            } else {
                pendingReload = true;
            }
        }

        /** Every 50 ms while needed: a reopened video goes to its point once open; a waiting change-over at the loop. */
        private void tick() {
            if (seekTo >= 0 && p.length() > 0) {
                p.seekTime(seekTo);
                seekTo = -1;
            }
            if (switchAtLoop) {
                long t = p.time();
                if (t + 1000 < lastTime) { // went back to the start
                    switchAtLoop = false;
                    p.play(mrl, false, p.isMuted(), videoOptions(looping, baseOptions)); // now the copy, from 0
                }
                lastTime = t;
            }
            if (seekTo < 0 && !switchAtLoop) {
                watch.stop();
            }
        }

        public void playOrResume() {
            if (cued) {
                go();
                return;
            }
            if (pendingReload) {
                pendingReload = false;
                pendingLoop = null;
                reopen(looping);
                return;
            }
            if (pendingLoop != null) {
                boolean v = pendingLoop;
                pendingLoop = null;
                reopen(v);
                return;
            }
            p.playOrResume();
        }

        public void pause() { p.pause(); }
        public void seek(float position) { p.seek(position); }

        public void setLoop(boolean loop) {
            if (loop == looping) {
                pendingLoop = null;
                return;
            }
            if (p.isPlaying() || seekTo >= 0) { // still opening after a reopen counts as playing
                reopen(loop);
            } else {
                pendingLoop = loop;
            }
        }

        /**
         * Opens the video again with the other repeat, then goes back to where it was once it is open. Not VLC's
         * {@code :start-time}: with input repeat, every loop then started again from that point instead of the start
         * (measured: a 1.8 s hold at the same place in every loop).
         */
        private void reopen(boolean loop) {
            long at = seekTo >= 0 ? seekTo : p.time(); // reopened again before it got there: the same point
            looping = loop;
            switchAtLoop = false;
            p.play(mrl, false, p.isMuted(), videoOptions(loop, baseOptions));
            if (at > 0) {
                seekTo = at;
                watch.start();
            }
        }

        public void setMuted(boolean muted) {
            this.muted = muted;
            if (!cued) {
                p.setMuted(muted);
            }
        }

        public void setVolume(int volume) { p.setVolume(volume); }
        public long time() { return p.time(); }
        public long length() { return p.length(); }

        /** Off the Swing thread: stopping a stream waits for VLC (measured up to about 1 s). */
        public void release() {
            watch.stop();
            p.releaseLater();
        }
    }

    /** A still picture, read once off the event thread; playback controls do nothing. */
    private static final class ImageLayer implements Layer {
        private volatile BufferedImage image;

        /** An already-made picture (a color or gradient). */
        ImageLayer(BufferedImage image) {
            this.image = image;
        }

        ImageLayer(Path file, Runnable loaded) {
            Thread.ofVirtual().name("background-image").start(() -> {
                try {
                    image = javax.imageio.ImageIO.read(file.toFile());
                } catch (Exception e) {
                    log.warn("Could not read background image {}", file, e);
                }
                javax.swing.SwingUtilities.invokeLater(loaded);
            });
        }

        public BufferedImage currentFrame() { return image; }
        public void playOrResume() { }
        public void pause() { }
        public void seek(float position) { }
        public void setLoop(boolean loop) { }
        public void setMuted(boolean muted) { }
        public void setVolume(int volume) { }
        public long time() { return -1; }
        public long length() { return -1; }
        public void release() { image = null; }
    }

    private float changeProgress() {
        if (outgoing == null) {
            return 1f;
        }
        if (changeStart < 0) {
            return 0f; // the new video has not shown a picture yet: keep the old one on screen
        }
        return changeMs <= 0 ? 1f : Math.clamp((System.nanoTime() - changeStart) / 1_000_000f / changeMs, 0f, 1f);
    }

    private void tickChange() {
        if (outgoing == null) {
            changeTimer.stop();
            return;
        }
        if (changeStart < 0 && player != null && player.currentFrame() != null) {
            changeStart = System.nanoTime();
        }
        if (changeProgress() >= 1f) {
            finishChange();
        }
        fire();
    }

    private void finishChange() {
        changeTimer.stop();
        if (outgoing != null) {
            outgoing.release();
            outgoing = null;
        }
        changeStart = -1;
    }

    private void releasePreview() {
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
        previewPath = null;
    }

    private void save(String key, String value) {
        config.set(key, value);
        config.save();
        fire();
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }

    private static String fileName(String p) {
        String s = p.replace('\\', '/');
        return s.substring(s.lastIndexOf('/') + 1);
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.strip());
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static Change parseChange(String v) {
        try {
            return Change.valueOf(v);
        } catch (RuntimeException e) {
            return Change.CROSSFADE;
        }
    }
}
