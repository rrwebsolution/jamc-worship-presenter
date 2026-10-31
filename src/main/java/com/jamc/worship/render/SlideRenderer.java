package com.jamc.worship.render;

import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Gradient;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.util.Colors;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Draws a slide (background, overlay, text) into any rectangle.
 *
 * <p>Everything is laid out in the 1920x1080 reference frame and scaled, so a thumbnail, the
 * operator preview, the projector and the OBS window all show exactly the same composition.
 * Painting never blocks: background images come from {@link ImageCache} and show the fallback
 * color until they have loaded.
 */
public final class SlideRenderer {

    public static final int WIDTH = TextRenderer.FRAME_WIDTH;
    public static final int HEIGHT = TextRenderer.FRAME_HEIGHT;

    private final ImageCache images;
    private final TextRenderer text = new TextRenderer();

    public SlideRenderer(ImageCache images) {
        this.images = images;
    }

    public ImageCache images() {
        return images;
    }

    /**
     * Measures how text would be laid out (font size after auto-shrink, bounds) without painting.
     * Used to decide how many Bible verses fit on one slide. The style's letter case is applied first,
     * so capitals (which are wider) are measured as they will be drawn.
     */
    public TextRenderer.TextBlock measure(String heading, String body, String caption, SlideStyle style) {
        TextTransform t = style.textTransform();
        return text.layout(t.apply(heading), t.apply(body), t.apply(caption), style);
    }

    /** Paints {@code slide} using {@code base} (plus the slide's own overrides) into {@code r}. */
    public void paint(Graphics2D g, Rectangle r, Slide slide, SlideStyle base) {
        SlideStyle style = slide == null ? base : slide.styleOver(base);
        paint(g, r, slide == null ? null : slide.getHeading(), slide == null ? "" : slide.getText(),
                slide == null ? null : slide.getCaption(), style);
    }

    public void paint(Graphics2D g0, Rectangle r, String body, String caption, SlideStyle style) {
        paint(g0, r, null, body, caption, style);
    }

    public void paint(Graphics2D g0, Rectangle r, String heading, String body, String caption, SlideStyle style) {
        if (r.width <= 0 || r.height <= 0) {
            return;
        }
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.clipRect(r.x, r.y, r.width, r.height);
            paintBackground(g, r, style, false);
            paintTextLayer(g, r, heading, body, caption, style);
        } finally {
            g.dispose();
        }
    }

    /**
     * Paints only the text (with shadow/outline) over whatever is already there. Used by scenes,
     * where the text layer and the background layer can have other sources (camera) between them.
     */
    public void paintTextLayer(Graphics2D g0, Rectangle r, String heading, String body, String caption, SlideStyle style) {
        if (r.width <= 0 || r.height <= 0) {
            return;
        }
        // uniform scale: a 16:9 area is the 1920x1080 frame; another box (a scene's lyrics box) keeps the
        // text proportions and the text shrinks to fit the box
        double scale = r.width / (double) WIDTH;
        int frameHeight = (int) Math.round(r.height / scale);
        TextTransform t = style.textTransform();
        TextRenderer.TextBlock block = text.layout(t.apply(heading), t.apply(body), t.apply(caption), style,
                Math.abs(frameHeight - HEIGHT) <= 2 ? HEIGHT : frameHeight);
        if (block.isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.translate(r.x, r.y);
            g.scale(scale, r.height / (double) (Math.abs(frameHeight - HEIGHT) <= 2 ? HEIGHT : frameHeight));
            paintText(g, block, style);
        } finally {
            g.dispose();
        }
    }

    /** Renders to a new image of the given size, e.g. 1920x1080 for output or 320x180 for a cached thumbnail. */
    public BufferedImage render(Slide slide, SlideStyle style, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            applyQualityHints(g);
            paint(g, new Rectangle(0, 0, width, height), slide, style);
        } finally {
            g.dispose();
        }
        return img;
    }

    public static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /**
     * Paints the background (color, image or video frame) and dim overlay.
     *
     * @param live true on Program surfaces: a video background is started if it is not running yet.
     *             Previews only show a video background that is already playing, plus a video marker.
     */
    public void paintBackground(Graphics2D g, Rectangle r, SlideStyle style, boolean live) {
        g.setColor(Colors.parse(style.effectiveBackgroundColor(), Color.BLACK));
        g.fillRect(r.x, r.y, r.width, r.height);

        if (style.backgroundType() == BackgroundType.GRADIENT) {
            paintGradient(g, r, Gradient.parse(style.backgroundValue()));
        } else if (style.backgroundType() == BackgroundType.IMAGE) {
            BufferedImage img = images.get(style.backgroundValue());
            if (img != null) {
                drawFitted(g, img, r, style.backgroundFit());
            }
        } else if (style.backgroundType() == BackgroundType.VIDEO) {
            LiveFrames frames = Rendering.frames();
            BufferedImage frame = live ? frames.video(style.backgroundValue()) : frames.videoPoster(style.backgroundValue());
            if (frame != null) {
                drawFitted(g, frame, r, style.backgroundFit());
            } else if (!live) {
                paintVideoMarker(g, r);
            }
        }
        if (style.overlayOpacity() > 0) {
            g.setColor(new Color(0f, 0f, 0f, (float) style.overlayOpacity()));
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    /** Small play triangle in the corner of previews whose video background is not running. */
    private static void paintVideoMarker(Graphics2D g, Rectangle r) {
        int s = Math.max(8, r.height / 8);
        int x = r.x + r.width - s - Math.max(4, r.width / 40);
        int y = r.y + Math.max(4, r.height / 20);
        g.setColor(new Color(255, 255, 255, 150));
        g.fillPolygon(new int[]{x, x + s, x}, new int[]{y, y + s / 2, y + s}, 3);
    }

    /** Linear two-color gradient across the rectangle at the gradient's angle. */
    public static void paintGradient(Graphics2D g, Rectangle r, Gradient gradient) {
        double rad = Math.toRadians(gradient.angle());
        double dx = Math.cos(rad);
        double dy = Math.sin(rad);
        // half the projection of the rectangle onto the gradient direction, so the colors reach the corners
        double half = (Math.abs(dx) * r.width + Math.abs(dy) * r.height) / 2;
        double cx = r.x + r.width / 2.0;
        double cy = r.y + r.height / 2.0;
        var old = g.getPaint();
        g.setPaint(new java.awt.GradientPaint((float) (cx - dx * half), (float) (cy - dy * half), gradient.fromColor(),
                (float) (cx + dx * half), (float) (cy + dy * half), gradient.toColor()));
        g.fillRect(r.x, r.y, r.width, r.height);
        g.setPaint(old);
    }

    /** Draws an image or video frame with the chosen fit; FIT leaves the background color in the bars. */
    public static void drawFitted(Graphics2D g, BufferedImage img, Rectangle r, FitMode fit) {
        switch (fit) {
            case FIT -> {
                double scale = Math.min(r.width / (double) img.getWidth(), r.height / (double) img.getHeight());
                int w = (int) Math.round(img.getWidth() * scale);
                int h = (int) Math.round(img.getHeight() * scale);
                drawScaled(g, img, r.x + (r.width - w) / 2, r.y + (r.height - h) / 2, w, h);
            }
            case STRETCH -> drawScaled(g, img, r.x, r.y, r.width, r.height);
            default -> drawCover(g, img, r);
        }
    }

    private static void drawScaled(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, x, y, w, h, null);
        if (hint != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        }
    }

    /** Scales the image to cover the whole rectangle, cropping the overflow (like CSS background-size: cover). */
    public static void drawCover(Graphics2D g, BufferedImage img, Rectangle r) {
        double scale = Math.max(r.width / (double) img.getWidth(), r.height / (double) img.getHeight());
        int w = (int) Math.ceil(img.getWidth() * scale);
        int h = (int) Math.ceil(img.getHeight() * scale);
        Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, r.x + (r.width - w) / 2, r.y + (r.height - h) / 2, w, h, null);
        if (hint != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        }
    }

    private static void paintText(Graphics2D g, TextRenderer.TextBlock block, SlideStyle style) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        Color fill = Colors.parse(style.fontColor(), Color.WHITE);

        if (style.shadow()) {
            float offset = Math.max(3f, block.fontSize() * 0.045f);
            // strength 0.55 is the classic look: passes at 0.35 and 0.55 opacity
            float strength = (float) style.shadowStrength();
            var old = g.getComposite();
            // Two offset passes give a soft-edged drop shadow without an expensive blur.
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, strength * 0.64f));
            g.setColor(Color.BLACK);
            drawAll(g, block, AffineTransform.getTranslateInstance(offset * 1.6, offset * 1.6), true, null);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, strength));
            drawAll(g, block, AffineTransform.getTranslateInstance(offset, offset), true, null);
            g.setComposite(old);
        }
        if (style.outline()) {
            g.setColor(Colors.parse(style.outlineColor(), Color.BLACK));
            BasicStroke stroke = new BasicStroke(style.outlineWidth() * 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            drawAll(g, block, null, false, stroke);
        }
        g.setColor(fill);
        drawAll(g, block, null, true, null);
    }

    private static void drawAll(Graphics2D g, TextRenderer.TextBlock block, AffineTransform offset, boolean fill,
                                BasicStroke stroke) {
        var oldStroke = g.getStroke();
        if (stroke != null) {
            g.setStroke(stroke);
        }
        for (Shape s : block.lines()) {
            drawShape(g, offset == null ? s : offset.createTransformedShape(s), fill);
        }
        for (Shape extra : new Shape[]{block.caption(), block.heading()}) {
            if (extra != null) {
                drawShape(g, offset == null ? extra : offset.createTransformedShape(extra), fill);
            }
        }
        g.setStroke(oldStroke);
    }

    private static void drawShape(Graphics2D g, Shape s, boolean fill) {
        if (fill) {
            g.fill(s);
        } else {
            g.draw(s);
        }
    }
}
