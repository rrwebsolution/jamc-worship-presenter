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

    @Override
    public void addNotify() {
        super.addNotify();
        program.addListener(repaint);
        MediaFrames.addListener(repaint);
        Rendering.globalBackground().addListener(repaint);
        Rendering.renderer().images().addChangeListener(imagesChanged);
        clock.start();
    }

    @Override
    public void removeNotify() {
        program.removeListener(repaint);
        MediaFrames.removeListener(repaint);
        Rendering.globalBackground().removeListener(repaint);
        Rendering.renderer().images().removeChangeListener(imagesChanged);
        clock.stop();
        compositor.invalidate();
        fromBuffer = null;
        toBuffer = null;
        super.removeNotify();
    }

    /** The 16:9 area, centered and letterboxed inside the component. */
    public Rectangle screenBounds() {
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
            int ph = Math.max(1, (int) Math.ceil(r.height * scale));

            FrameSpec to = program.shown();
            FrameSpec from = program.previous();
            float p = program.progress();
            if (from == null || p >= 1f) {
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
                float fromAlpha;
                float toAlpha;
                switch (program.activeTransition()) {
                    case CROSSFADE -> {
                        fromAlpha = 1f - p;
                        toAlpha = p;
                    }
                    case FADE -> {
                        fromAlpha = p < 0.5f ? 1f - p * 2f : 0f;
                        toAlpha = p < 0.5f ? 0f : p * 2f - 1f;
                    }
                    default -> {
                        fromAlpha = 0f;
                        toAlpha = 1f;
                    }
                }
                Graphics2D d = (Graphics2D) g2.create();
                d.translate(r.x, r.y);
                d.scale(r.width / (double) pw, r.height / (double) ph);
                d.clipRect(0, 0, pw, ph);
                d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (sameScene) {
                    compositor.paintLayered(d, pw, ph, from, to, program, keyMode, fromAlpha, toAlpha);
                } else {
                    compositor.paintSceneChange(d, pw, ph, from, to, program, keyMode, fromAlpha, toAlpha);
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
                switch (program.activeTransition()) {
                    case CROSSFADE -> {
                        draw(g2, fromBuffer, r, 1f - p, base);
                        draw(g2, toBuffer, r, p, base);
                    }
                    case FADE -> {
                        if (p < 0.5f) {
                            draw(g2, fromBuffer, r, 1f - p * 2f, base);
                        } else {
                            draw(g2, toBuffer, r, p * 2f - 1f, base);
                        }
                    }
                    case CUT -> draw(g2, toBuffer, r, 1f, base);
                }
                g2.setComposite(base);
            } else {
                fromBuffer = ensure(fromBuffer, pw, ph);
                toBuffer = ensure(toBuffer, pw, ph);
                compose(fromBuffer, from);
                compose(toBuffer, to);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                Composite base = g2.getComposite();
                switch (program.activeTransition()) {
                    case CROSSFADE -> {
                        draw(g2, fromBuffer, r, 1f, base);
                        draw(g2, toBuffer, r, p, base);
                    }
                    case FADE -> {
                        g2.setColor(keyMode ? Compositor.KEY_GREEN : Color.BLACK);
                        g2.fillRect(r.x, r.y, r.width, r.height);
                        if (p < 0.5f) {
                            draw(g2, fromBuffer, r, 1f - p * 2f, base);
                        } else {
                            draw(g2, toBuffer, r, p * 2f - 1f, base);
                        }
                    }
                    case CUT -> draw(g2, toBuffer, r, 1f, base);
                }
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

    private static void draw(Graphics2D g, BufferedImage img, Rectangle r, float alpha, Composite base) {
        if (alpha <= 0f) {
            return;
        }
        g.setComposite(alpha >= 1f ? base : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.drawImage(img, r.x, r.y, r.width, r.height, null);
    }
}
