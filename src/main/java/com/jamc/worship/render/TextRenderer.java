package com.jamc.worship.render;

import com.jamc.worship.model.FontWeight;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextAlign;

import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays out slide text inside the safe area of the 1920x1080 reference frame.
 *
 * <p>Text is word-wrapped and the font is shrunk until every line fits, so long verses never
 * run off the screen. The result is a set of glyph outlines in reference coordinates; because it
 * does not depend on the output size it is cached and reused for thumbnails, preview, program
 * and projector output alike.
 */
public final class TextRenderer {

    public static final int FRAME_WIDTH = 1920;
    public static final int FRAME_HEIGHT = 1080;
    /** Title-safe margins: 5% left/right, 6.5% top/bottom. */
    public static final int MARGIN_X = 96;
    public static final int MARGIN_Y = 70;

    private static final FontRenderContext FRC = new FontRenderContext(new AffineTransform(), true, true);
    private static final int CACHE_SIZE = 256;

    /** Glyph outlines for one slide's text, in reference-frame coordinates. */
    public record TextBlock(List<Shape> lines, Shape caption, Shape heading, float fontSize, Rectangle2D bounds) {
        public boolean isEmpty() {
            return lines.isEmpty() && caption == null && heading == null;
        }
    }

    private record Key(String text, String caption, String heading, String family, int size, FontWeight weight, boolean italic,
                       TextAlign align, com.jamc.worship.model.TextPosition position, double lineSpacing, int frameHeight,
                       int frameWidth) {
    }

    private final Map<Key, TextBlock> cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, TextBlock> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    public TextBlock layout(String text, String caption, SlideStyle style) {
        return layout(null, text, caption, style);
    }

    /**
     * Lays out an optional heading (e.g. a Bible reference) above the text and an optional caption
     * below it. Heading and caption use a smaller font that scales with the text.
     */
    public TextBlock layout(String heading, String text, String caption, SlideStyle style) {
        return layout(heading, text, caption, style, FRAME_HEIGHT);
    }

    /**
     * Same, inside a box that is {@value #FRAME_WIDTH} units wide and {@code frameHeight} units high (a
     * scene can give the lyrics a smaller, wider or lower box). Text shrinks until it fits the box.
     */
    public TextBlock layout(String heading, String text, String caption, SlideStyle style, int frameHeight) {
        return layout(heading, text, caption, style, frameHeight, FRAME_WIDTH);
    }

    /**
     * Same, in a box {@code frameWidth} units wide: the lines wrap and are aligned within that width (e.g. the part
     * of a scene's lyrics box that is on the screen, when the box is bigger than the screen).
     */
    public TextBlock layout(String heading, String text, String caption, SlideStyle style, int frameHeight, int frameWidth) {
        Key key = new Key(text == null ? "" : text.strip(), caption == null || caption.isBlank() ? null : caption.strip(),
                heading == null || heading.isBlank() ? null : heading.strip(),
                style.fontFamily(), style.fontSize(), style.fontWeight(), style.italic(), style.align(),
                style.position(), style.lineSpacing(), Math.clamp(frameHeight, 60, 20000), Math.clamp(frameWidth, 240, 20000));
        return cache.computeIfAbsent(key, TextRenderer::compute);
    }

    private static TextBlock compute(Key k) {
        String[] rawLines = k.text().isEmpty() ? new String[0] : k.text().split("\n", -1);
        if (rawLines.length == 0 && k.caption() == null && k.heading() == null) {
            return new TextBlock(List.of(), null, null, k.size(), new Rectangle2D.Float());
        }
        Font base = Fonts.create(k.family(), k.weight(), k.italic(), k.size());

        float boxW = k.frameWidth() - 2f * MARGIN_X;
        float marginY = Math.min(MARGIN_Y, k.frameHeight() * 0.065f);
        float boxH = k.frameHeight() - 2f * marginY;
        float minSize = Math.min(SlideStyle.MIN_FONT_SIZE * 2f, k.size());

        float size = k.size();
        List<String> lines;
        while (true) {
            Font font = base.deriveFont(size);
            lines = wrap(rawLines, font, boxW);
            float captionReserve = (k.caption() == null ? 0 : captionSize(size) * 1.8f)
                    + (k.heading() == null ? 0 : captionSize(size) * 2.0f);
            boolean widthOk = lines.stream().allMatch(l -> width(l, font) <= boxW);
            if ((widthOk && blockHeight(lines, font, k.lineSpacing()) <= boxH - captionReserve) || size <= minSize) {
                break;
            }
            size = Math.max(minSize, size * 0.93f);
        }

        Font font = base.deriveFont(size);
        float lineHeight = lineHeight(font, k.lineSpacing());
        float textH = blockHeight(lines, font, k.lineSpacing());
        Font captionFont = Fonts.create(k.family(), FontWeight.REGULAR, k.italic(), captionSize(size));
        float captionGap = k.caption() == null ? 0 : captionSize(size) * 0.8f;
        float captionH = k.caption() == null ? 0 : captionFont.getLineMetrics(k.caption(), FRC).getHeight();
        Font headingFont = Fonts.create(k.family(), FontWeight.BOLD, false, captionSize(size) * 1.05f);
        float headingH = k.heading() == null ? 0 : headingFont.getLineMetrics(k.heading(), FRC).getHeight();
        float headingGap = k.heading() == null ? 0 : captionSize(size) * 0.7f;
        float totalH = headingH + headingGap + textH + captionGap + captionH;

        float top = switch (k.position()) {
            case TOP -> marginY;
            case MIDDLE -> marginY + (boxH - totalH) / 2f;
            case BOTTOM -> marginY + boxH - totalH;
        };

        List<Shape> shapes = new ArrayList<>();
        Rectangle2D bounds = null;
        Shape headingShape = null;
        if (k.heading() != null) {
            TextLayout tl = new TextLayout(k.heading(), headingFont, FRC);
            headingShape = tl.getOutline(AffineTransform.getTranslateInstance(alignX(k.align(), tl.getAdvance(), k.frameWidth()), top + tl.getAscent()));
            bounds = union(bounds, headingShape.getBounds2D());
            top += headingH + headingGap;
        }
        float ascent = font.getLineMetrics("Ag", FRC).getAscent();
        float y = top + ascent;
        for (String line : lines) {
            if (!line.isEmpty()) {
                TextLayout tl = new TextLayout(line, font, FRC);
                float x = alignX(k.align(), tl.getAdvance(), k.frameWidth());
                Shape s = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
                shapes.add(s);
                bounds = union(bounds, s.getBounds2D());
                y += lineHeight;
            } else {
                y += lineHeight * 0.5f; // blank line: half-height spacer
            }
        }

        Shape captionShape = null;
        if (k.caption() != null) {
            TextLayout tl = new TextLayout(k.caption(), captionFont, FRC);
            float cy = top + textH + captionGap + tl.getAscent();
            captionShape = tl.getOutline(AffineTransform.getTranslateInstance(alignX(k.align(), tl.getAdvance(), k.frameWidth()), cy));
            bounds = union(bounds, captionShape.getBounds2D());
        }
        return new TextBlock(List.copyOf(shapes), captionShape, headingShape, size, bounds == null ? new Rectangle2D.Float() : bounds);
    }

    private static float captionSize(float textSize) {
        return Math.max(22f, textSize * 0.5f);
    }

    private static float alignX(TextAlign align, float advance, int frameWidth) {
        return switch (align) {
            case LEFT -> MARGIN_X;
            case CENTER -> (frameWidth - advance) / 2f;
            case RIGHT -> frameWidth - MARGIN_X - advance;
        };
    }

    private static float lineHeight(Font font, double spacing) {
        var m = font.getLineMetrics("Ag", FRC);
        return (float) ((m.getAscent() + m.getDescent()) * spacing);
    }

    private static float blockHeight(List<String> lines, Font font, double spacing) {
        float lh = lineHeight(font, spacing);
        float h = 0;
        for (String line : lines) {
            h += line.isEmpty() ? lh * 0.5f : lh;
        }
        // The last line needs its glyph height, not the full spaced line height.
        var m = font.getLineMetrics("Ag", FRC);
        return lines.isEmpty() ? 0 : h - lh + m.getAscent() + m.getDescent();
    }

    private static float width(String s, Font font) {
        return s.isEmpty() ? 0 : (float) font.getStringBounds(s, FRC).getWidth();
    }

    /** Word-wraps each line; consecutive blank lines collapse into one spacer. */
    static List<String> wrap(String[] rawLines, Font font, float maxWidth) {
        List<String> out = new ArrayList<>();
        for (String raw : rawLines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (!out.isEmpty() && !out.getLast().isEmpty()) {
                    out.add("");
                }
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : line.split("\\s+")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (current.isEmpty() || width(candidate, font) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                } else {
                    out.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                }
            }
            out.add(current.toString());
        }
        while (!out.isEmpty() && out.getLast().isEmpty()) {
            out.removeLast();
        }
        return out;
    }

    private static Rectangle2D union(Rectangle2D a, Rectangle2D b) {
        if (a == null) {
            return b;
        }
        Rectangle2D r = new Rectangle2D.Double();
        Rectangle2D.union(a, b, r);
        return r;
    }
}
