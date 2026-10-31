package com.jamc.worship.ui.components;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.function.Supplier;

/**
 * Letterboxed 16:9 monitor that shows a slide through the shared {@link SlideRenderer}, so what
 * the operator sees here matches the projector output exactly.
 */
public class SlidePreviewCanvas extends JComponent {

    public static final double ASPECT = 16.0 / 9.0;

    private final Runnable imageListener = this::repaint;
    private Slide slide;
    private SlideStyle style;
    private String emptyMessage = "";
    private String badge;
    private Supplier<Color> badgeColor = Palette::mutedText;
    private Supplier<Color> frameColor;
    private boolean compact;

    public SlidePreviewCanvas() {
        setPreferredSize(new Dimension(480, 270));
        setMinimumSize(new Dimension(160, 90));
    }

    /** Shows {@code slide} styled with {@code style}; a null slide shows the empty message. */
    public void setContent(Slide slide, SlideStyle style) {
        this.slide = slide;
        this.style = style;
        repaint();
    }

    public Slide getSlide() {
        return slide;
    }

    public void setEmptyMessage(String emptyMessage) {
        this.emptyMessage = emptyMessage == null ? "" : emptyMessage;
        repaint();
    }

    /** Corner badge, e.g. "PREVIEW" or "PROGRAM"; null hides it. */
    public void setBadge(String badge, Supplier<Color> color) {
        this.badge = badge;
        this.badgeColor = color == null ? Palette::mutedText : color;
        repaint();
    }

    /** Highlight frame drawn around the 16:9 area, or null for the default hairline. */
    public void setFrameColor(Supplier<Color> frameColor) {
        this.frameColor = frameColor;
        repaint();
    }

    /** Compact mode for thumbnails: smaller badge, no empty message. */
    public void setCompact(boolean compact) {
        this.compact = compact;
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        Rendering.renderer().images().addChangeListener(imageListener);
        Rendering.thumbnails().addChangeListener(imageListener);
    }

    @Override
    public void removeNotify() {
        Rendering.renderer().images().removeChangeListener(imageListener);
        Rendering.thumbnails().removeChangeListener(imageListener);
        super.removeNotify();
    }

    /** The 16:9 area inside the component, centered and letterboxed. */
    public Rectangle screenBounds() {
        Insets in = getInsets();
        int availW = Math.max(1, getWidth() - in.left - in.right);
        int availH = Math.max(1, getHeight() - in.top - in.bottom);
        int w = availW;
        int h = (int) Math.round(w / ASPECT);
        if (h > availH) {
            h = availH;
            w = (int) Math.round(h * ASPECT);
        }
        return new Rectangle(in.left + (availW - w) / 2, in.top + (availH - h) / 2, w, h);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            SlideRenderer.applyQualityHints(g2);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle r = screenBounds();

            java.awt.image.BufferedImage thumb = compact && slide != null && slide.getThumbnail() != null
                    ? Rendering.thumbnails().get(slide.getThumbnail()) : null;
            if (thumb != null) {
                g2.drawImage(thumb, r.x, r.y, r.width, r.height, null);
            } else if (slide != null && slide.getThumbnail() != null && compact) {
                g2.setColor(Palette.monitorBackground()); // thumbnail still loading
                g2.fillRect(r.x, r.y, r.width, r.height);
            } else if (slide != null && style != null) {
                Rendering.renderer().paint(g2, r, slide, style);
            } else {
                g2.setColor(Palette.monitorBackground());
                g2.fillRect(r.x, r.y, r.width, r.height);
                if (!compact && !emptyMessage.isEmpty()) {
                    paintCentered(g2, r, emptyMessage);
                }
            }

            if (badge != null) {
                paintBadge(g2, r);
            }

            Color frame = frameColor == null ? Palette.border() : frameColor.get();
            float stroke = frameColor == null ? 1f : 2f;
            g2.setColor(frame);
            g2.setStroke(new BasicStroke(stroke));
            g2.drawRect(r.x, r.y, r.width - 1, r.height - 1);
        } finally {
            g2.dispose();
        }
    }

    private void paintCentered(Graphics2D g2, Rectangle r, String text) {
        Font font = getFont().deriveFont(Font.PLAIN, Math.max(11f, r.height * 0.045f));
        FontMetrics fm = g2.getFontMetrics(font);
        g2.setFont(font);
        g2.setColor(Palette.mutedText());
        g2.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2, r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void paintBadge(Graphics2D g2, Rectangle r) {
        Font f = getFont().deriveFont(Font.BOLD, compact ? 9f : 10f);
        FontMetrics fm = g2.getFontMetrics(f);
        int padX = 6;
        int w = fm.stringWidth(badge) + padX * 2;
        int h = fm.getHeight() + 2;
        int x = r.x + 6;
        int y = r.y + 6;
        g2.setColor(badgeColor.get());
        g2.fillRoundRect(x, y, w, h, h, h);
        g2.setColor(Color.WHITE);
        g2.setFont(f);
        g2.drawString(badge, x + padX, y + 1 + fm.getAscent());
    }
}
