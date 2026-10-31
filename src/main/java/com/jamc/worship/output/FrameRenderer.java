package com.jamc.worship.output;

import com.jamc.worship.render.SlideRenderer;

import java.awt.image.BufferedImage;

/** Renders a {@link FrameSpec} to an image, applying black / hidden text / hidden background and the scene. */
public final class FrameRenderer {

    private FrameRenderer() {
    }

    public static BufferedImage render(SlideRenderer renderer, FrameSpec spec, int width, int height) {
        return new Compositor(renderer).render(spec, width, height, false);
    }
}
