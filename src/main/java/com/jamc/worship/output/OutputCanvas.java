package com.jamc.worship.output;

import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.render.Rendering;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Paints the current Program frame, including transitions, via the {@link Compositor}.
 *
 * <p>Frames are composed at the surface's real pixel size, so output is sharp on scaled displays.
 * Live layers (video, camera, clock, lower third) repaint as new frames arrive. Subclasses (the
 * operator's Program monitor) can paint overlays; the projector and OBS windows use this class
 * as-is, so no operator UI ever reaches the audience.
 */
public class OutputCanvas extends JComponent {

    private final ProgramOutput program;
    private final Compositor compositor;
    private final boolean keyMode;
    private final Runnable repaint = this::repaint;
    private final Runnable frameArrived = this::frameArrived;
    private long lastFrameRepaint;
    /** While recording or streaming the recorder needs the Swing thread first, so the small operator monitors repaint less. */
    private static volatile boolean recording;
    private static final long MONITOR_GAP_WHILE_RECORDING_NANOS = 66_000_000; // about 15 fps
    /**
     * Every video, camera and browser page reports its frames on its own clock, and each report used to recompose this
     * canvas. Out of phase, a YouTube video, a browser page and a camera made the Preview and Program monitors repaint
     * about 100 times a second (each scaling the video in software), which filled the Swing thread: YouTube, which
     * also runs on it, stuttered and the app hung. Monitors now repaint at most about 50 times a second, outputs 60.
     * Not 30: a 30 fps camera's frames arrive a little early or late, and a 33 ms gap held some of them back or
     * dropped them, so cameras on the monitors stuttered.
     */
    private static final long MONITOR_GAP_NANOS = 20_000_000;
    private static final long OUTPUT_GAP_NANOS = 15_000_000;
    /** Repaints once the gap is over, so the newest frame is always shown (e.g. the last one before a pause). */
    private final Timer trailingFrame = new Timer(10, e -> {
        lastFrameRepaint = System.nanoTime();
        repaint();
    });

    {
        trailingFrame.setRepeats(false);
    }
    private final Runnable imagesChanged;
    private final Timer clock = new Timer(1000, e -> repaint());
    private BufferedImage fromBuffer;
    private BufferedImage toBuffer;

    public OutputCanvas(ProgramOutput program) {
        this(program, false);
    }

    /** @param keyMode paint green instead of backgrounds, camera and video (OBS chroma key) */
    public OutputCanvas(ProgramOutput program, boolean keyMode) {
        this(program, keyMode, false);
    }

    /**
     * @param preview paint an operator Preview state (previewed background, video placeholder) rather than
     *                Program; the canvas class is the same, so Preview shows exactly what TAKE will put live
     */
    public OutputCanvas(ProgramOutput program, boolean keyMode, boolean preview) {
        this.compositor = new Compositor(Rendering.renderer(), preview);
        this.imagesChanged = () -> {
            compositor.invalidate();
            repaint();
        };
        this.program = program;
        this.keyMode = keyMode;
        setOpaque(true);
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(480, 270));
    }

    public ProgramOutput program() {
        return program;
    }

    private boolean programLyricsStyle;
    private final Runnable styleApplied = this::repaint;

    /**
     * This surface is the live Program picture that goes out (stream, recording, OBS window, Program monitor): song
     * lyrics use the Program lyrics style (e.g. the lower third). The projector and Preview never call this, so the
     * room keeps the full-screen lyrics.
     */
    public OutputCanvas useProgramLyricsStyle() {
        programLyricsStyle = true;
        compositor.setProgramLyrics(true);
        return this;
    }

    public static void setRecording(boolean on) {
        recording = on;
    }

    /** True for a small monitor only the operator sees; the projector and the OBS window always get every frame. */
    protected boolean operatorMonitor() {
        return false;
    }

    private void frameArrived() {
        long now = System.nanoTime();
        long gap = !operatorMonitor() ? OUTPUT_GAP_NANOS : recording ? MONITOR_GAP_WHILE_RECORDING_NANOS : MONITOR_GAP_NANOS;
        long wait = gap - (now - lastFrameRepaint);
        if (wait > 0) {
            if (!trailingFrame.isRunning()) {
                trailingFrame.setInitialDelay((int) Math.max(1, wait / 1_000_000));
                trailingFrame.restart();
            }
            return;
        }
        trailingFrame.stop();
        lastFrameRepaint = now;
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        program.addListener(repaint);
        MediaFrames.addListener(frameArrived);
        Rendering.globalBackground().addListener(repaint);
        Rendering.renderer().images().addChangeListener(imagesChanged);
        com.jamc.worship.config.ProgramLyricsStyle.addListener(styleApplied);
        clock.start();
    }

    @Override
    public void removeNotify() {
        program.removeListener(repaint);
        MediaFrames.removeListener(frameArrived);
        trailingFrame.stop();
        Rendering.globalBackground().removeListener(repaint);
        Rendering.renderer().images().removeChangeListener(imagesChanged);
        com.jamc.worship.config.ProgramLyricsStyle.removeListener(styleApplied);
        clock.stop();
        compositor.invalidate();
        fromBuffer = null;
        toBuffer = null;
        super.removeNotify();
    }

    /** Output windows only: stretch the 16:9 picture over a screen of another shape instead of black bars. */
    private java.util.function.BooleanSupplier fillScreen = () -> false;

    /** Read on every paint, so a Settings change applies at once. Operator monitors never call this. */
    public void setFillScreen(java.util.function.BooleanSupplier fill) {
        fillScreen = fill == null ? () -> false : fill;
    }

    /** The picture's area: the whole component when filling, else the 16:9 area, centered and letterboxed. */
    public Rectangle screenBounds() {
        if (fillScreen.getAsBoolean()) {
            return new Rectangle(0, 0, getWidth(), getHeight());
        }
        int w = getWidth();
        int h = (int) Math.round(w * 9 / 16.0);
        if (h > getHeight()) {
            h = getHeight();
            w = (int) Math.round(h * 16 / 9.0);
        }
        return new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (isShowing()) {
            Smoothness.drawn(operatorMonitor() ? getClass().getSimpleName() : "Output window");
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            if (isOpaque()) { // the projector letterboxes in black; operator monitors stay transparent
                g2.setColor(keyMode ? Compositor.KEY_GREEN : Color.BLACK);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            Rectangle r = screenBounds();
            if (r.width <= 0 || r.height <= 0) {
                return;
            }
            double scale = g2.getTransform().getScaleX();
            int pw = Math.max(1, (int) Math.ceil(r.width * scale));
            // always composed as a 16:9 frame, so scenes and lyrics keep their layout; filling a screen of another
            // shape only stretches that frame over it
            int ph = Math.max(1, (int) Math.round(pw * 9 / 16.0));

            FrameSpec to = program.shown();
            FrameSpec from = program.previous();
            float p = program.progress();
            // Program-style lyrics fade on their own clock; other surfaces never wait for it
            float own = programLyricsStyle ? program.overlayProgress() : 1f;
            compositor.setOverlayProgress(own);
            if (from == null || (p >= 1f && own >= 1f)) {
                // Steady state: compose straight onto the screen at device-pixel resolution.
                Graphics2D d = (Graphics2D) g2.create();
                d.translate(r.x, r.y);
                d.scale(r.width / (double) pw, r.height / (double) ph);
                d.clipRect(0, 0, pw, ph);
                compositor.paint(d, pw, ph, to, program, keyMode);
                d.dispose();
            } else if (Compositor.contentChangeOnly(from, to) || Compositor.sceneChange(from, to)) {
                // next lyrics / verse / slide in the same scene: only the content layers transition; the
                // scene's video, camera and logo keep playing at full opacity (never faded or restarted).
                // Another scene: only the layers that differ transition (CAM 1 → CAM 2 under the same lyrics
                // changes only the camera); shared layers stay.
                boolean sameScene = Compositor.contentChangeOnly(from, to);
                Transition kind = program.activeTransition();
                Graphics2D d = (Graphics2D) g2.create();
                d.translate(r.x, r.y);
                d.scale(r.width / (double) pw, r.height / (double) ph);
                d.clipRect(0, 0, pw, ph);
                d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (sameScene) {
                    compositor.paintLayered(d, pw, ph, from, to, program, keyMode, kind, p);
                } else {
                    compositor.paintSceneChange(d, pw, ph, from, to, program, keyMode, kind, p);
                }
                d.dispose();
            } else if (!keyMode && Compositor.sitsOnGlobal(from) && Compositor.sitsOnGlobal(to)) {
                // Song → verse → slide over the global video: the background is one continuous layer;
                // only the content above it changes.
                fromBuffer = ensureArgb(fromBuffer, pw, ph);
                toBuffer = ensureArgb(toBuffer, pw, ph);
                composeContent(fromBuffer, from);
                composeContent(toBuffer, to);
                g2.setColor(Color.BLACK);
                g2.fillRect(r.x, r.y, r.width, r.height);
                Graphics2D bg = (Graphics2D) g2.create();
                bg.translate(r.x, r.y);
                bg.scale(r.width / (double) pw, r.height / (double) ph);
                Rendering.globalBackground().paint(bg, new Rectangle(0, 0, pw, ph));
                bg.dispose();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                Composite base = g2.getComposite();
                // transparent content over the running background: both sides fade (or move) over it
                drawSide(g2, fromBuffer, r, program.activeTransition(), p, false, false, base);
                drawSide(g2, toBuffer, r, program.activeTransition(), p, true, false, base);
                g2.setComposite(base);
            } else {
                fromBuffer = ensure(fromBuffer, pw, ph);
                toBuffer = ensure(toBuffer, pw, ph);
                compose(fromBuffer, from);
                compose(toBuffer, to);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                Composite base = g2.getComposite();
                // whole frames: black (or the key green) behind, for a fade or where a slide / zoom leaves a gap
                g2.setColor(keyMode ? Compositor.KEY_GREEN : Color.BLACK);
                g2.fillRect(r.x, r.y, r.width, r.height);
                drawSide(g2, fromBuffer, r, program.activeTransition(), p, false, true, base);
                drawSide(g2, toBuffer, r, program.activeTransition(), p, true, true, base);
                g2.setComposite(base);
            }
            paintOverlay(g2, r);
        } finally {
            g2.dispose();
        }
    }

    /** Hook for operator-only overlays. The projector and OBS windows never override this. */
    protected void paintOverlay(Graphics2D g, Rectangle screen) {
    }

    private void compose(BufferedImage target, FrameSpec spec) {
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            compositor.paint(g, target.getWidth(), target.getHeight(), spec, program, keyMode);
        } finally {
            g.dispose();
        }
    }

    /** Content layers only, on a transparent image, for fading over the running global background. */
    private void composeContent(BufferedImage target, FrameSpec spec) {
        Graphics2D g = target.createGraphics();
        try {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, target.getWidth(), target.getHeight());
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            compositor.paint(g, target.getWidth(), target.getHeight(), spec, program, keyMode, true);
        } finally {
            g.dispose();
        }
    }

    private static BufferedImage ensureArgb(BufferedImage img, int w, int h) {
        return img != null && img.getWidth() == w && img.getHeight() == h && img.getType() == BufferedImage.TYPE_INT_ARGB
                ? img : new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    private static BufferedImage ensure(BufferedImage img, int w, int h) {
        return img != null && img.getWidth() == w && img.getHeight() == h && img.getType() == BufferedImage.TYPE_INT_RGB
                ? img : new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    /**
     * One side (old = {@code incoming} false, new = true) of a whole-frame transition, drawn into the screen
     * rectangle with the transition's clip / shift / scale ({@link TransitionFx}) and opacity.
     */
    private static void drawSide(Graphics2D g, BufferedImage img, Rectangle r, Transition t, float p, boolean incoming,
                                 boolean opaque, Composite base) {
        Graphics2D side = (Graphics2D) g.create();
        try {
            side.translate(r.x, r.y);
            side.scale(r.width / (double) img.getWidth(), r.height / (double) img.getHeight());
            side.clipRect(0, 0, img.getWidth(), img.getHeight());
            float a = TransitionFx.prepare(t, side, img.getWidth(), img.getHeight(), p, incoming, opaque);
            if (a > 0f) {
                side.setComposite(a >= 1f ? base : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
                side.drawImage(img, 0, 0, null);
            }
        } finally {
            side.dispose();
        }
    }
}
