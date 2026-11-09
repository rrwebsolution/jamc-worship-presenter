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

    private String previewPath;
    private Layer previewPlayer;

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
                video && isActive() && !paused && !pausedForBlack, loop, muted, volume, video ? position() : -1, overlay, fit);
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
                player = start(path, muted);
                paused = false;
            } else if (!paused && !pausedForBlack) {
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
        fire();
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
        previewPlayer = start(newPath, true);
        fire();
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
        config.save();
        if (wasShowing && change != Change.CUT && changeMs > 0) {
            outgoing = old;
            activeChange = change;
            changeStart = -1; // starts when the new video shows its first picture
            changeTimer.start();
        } else if (old != null) {
            old.release();
        }
        log.info("Global background taken: {} ({})", fileName(path), wasShowing ? change : "first");
        fire();
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
        } else if (on && !pausedForBlack) {
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
        } else if (on && !paused) {
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
        for (Layer p : new Layer[]{player, outgoing, previewPlayer}) {
            if (p != null) {
                p.release();
            }
        }
        player = null;
        outgoing = null;
        previewPlayer = null;
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

    private Layer start(String mediaPath, boolean startMuted) {
        if (isSolid(mediaPath)) {
            return new ImageLayer(solidImage(mediaPath));
        }
        if (com.jamc.worship.media.OnlineMedia.isUrl(mediaPath)) {
            // an online (Pixabay) background video: streamed by VLC, nothing downloaded
            return new VideoLayer(mediaPath, loop, startMuted, volume, ":network-caching=3000", HW_DECODE);
        }
        Path file = resolver.apply(mediaPath);
        if (!Files.isRegularFile(file)) {
            log.warn("Background file not found: {}", file);
        }
        if (isImage(mediaPath)) {
            return new ImageLayer(file, this::fire);
        }
        return new VideoLayer(file.toString(), loop, startMuted, volume, HW_DECODE);
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
     * start came back. Input repeat measured no freeze at all (the normal 33 ms between frames). The repeat is fixed when
     * the video opens, so switching Loop reopens it at the same point (only when the operator changes Loop).
     */
    private static final class VideoLayer implements Layer {
        private final VideoPlayer p = new VideoPlayer();
        private final String mrl;
        private final String[] baseOptions;
        private boolean looping;
        /** A Loop change made while paused, applied on the next resume. */
        private Boolean pendingLoop;

        VideoLayer(String mrl, boolean loop, boolean muted, int volume, String... baseOptions) {
            this.mrl = mrl;
            this.baseOptions = baseOptions;
            this.looping = loop;
            p.play(mrl, false, muted, videoOptions(loop, baseOptions));
            p.setVolume(volume);
        }

        public BufferedImage currentFrame() { return p.currentFrame(); }

        public void playOrResume() {
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
            if (p.isPlaying()) {
                reopen(loop);
            } else {
                pendingLoop = loop;
            }
        }

        /** Opens the video again with the other repeat, from where it was. */
        private void reopen(boolean loop) {
            long at = p.time();
            looping = loop;
            String[] opts = videoOptions(loop, baseOptions);
            String[] withStart = java.util.Arrays.copyOf(opts, opts.length + 1);
            withStart[opts.length] = ":start-time=" + (at / 1000.0);
            p.play(mrl, false, p.isMuted(), withStart);
        }

        public void setMuted(boolean muted) { p.setMuted(muted); }
        public void setVolume(int volume) { p.setVolume(volume); }
        public long time() { return p.time(); }
        public long length() { return p.length(); }
        public void release() { p.release(); }
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
