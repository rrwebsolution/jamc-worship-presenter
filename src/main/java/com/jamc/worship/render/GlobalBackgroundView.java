package com.jamc.worship.render;

import com.jamc.worship.model.BackgroundMode;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;

import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * The operator's global video background as the renderers see it: a layer that sits under every
 * piece of content and is drawn from a player that content changes never touch. Implemented by
 * {@code GlobalBackgroundManager}; {@link #NONE} when no background system is installed.
 */
public interface GlobalBackgroundView {

    /** True while a global background is switched on (whether or not content currently covers it). */
    boolean isActive();

    /** Paints the live (Program) background with its fit, overlay and any background change in progress. */
    boolean paint(Graphics2D g, Rectangle r);

    /** True if the operator is previewing a different background that is not live yet. */
    boolean hasPreview();

    /** Paints the previewed background, or the live one if nothing else is being previewed. */
    boolean paintPreview(Graphics2D g, Rectangle r);

    /**
     * A STILL picture of the background (live, or the previewed one when {@code preview}): one frame, frozen, that
     * changes only when the background changes. For scene thumbnails, which must never look like they are playing.
     */
    default boolean paintStill(Graphics2D g, Rectangle r, boolean preview) {
        return preview ? paintPreview(g, r) : paint(g, r);
    }

    void addListener(Runnable listener);

    void removeListener(Runnable listener);

    /**
     * True if this content sits on the global background. Full-screen pictures (PowerPoint, images)
     * cover it, and content set to Override or Hide shows its own background; in every case the
     * global video keeps playing underneath.
     */
    static boolean usesGlobal(Slide slide, SlideStyle style) {
        return slide != null && style != null && !slide.coversBackground() && style.backgroundMode() == BackgroundMode.USE_GLOBAL;
    }

    GlobalBackgroundView NONE = new GlobalBackgroundView() {
        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public boolean paint(Graphics2D g, Rectangle r) {
            return false;
        }

        @Override
        public boolean hasPreview() {
            return false;
        }

        @Override
        public boolean paintPreview(Graphics2D g, Rectangle r) {
            return false;
        }

        @Override
        public void addListener(Runnable listener) {
        }

        @Override
        public void removeListener(Runnable listener) {
        }
    };
}
