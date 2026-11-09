package com.jamc.worship.output;

import com.jamc.worship.config.LyricsTransitionSettings;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.render.TextRenderer;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Spotify-style lyrics: the current card large and bright in the middle, the previous card dimmed above it and the
 * next card dimmed below. NEXT scrolls the whole stack up (the current card shrinks and dims as it rises, the next
 * one grows and brightens into the middle); PREVIOUS scrolls it down.
 *
 * <p>Positions ({@link Pose}) are in reference-frame units (1920 wide) measured from the vertical middle of the
 * lyrics box, so one pose fits every surface: projector, OBS, the operator monitors and the stream all paint the
 * same motion at their own size. A pose is a pure function of the song's cards and the settings, so an animation
 * interrupted by another NEXT continues from where the cards are on screen ({@link ProgramOutput} keeps that start).
 *
 * <p>Each card is drawn from a small cached picture (only its text area, at the surface's pixel size), so a frame of
 * the animation is a few image copies on the Swing thread, not text rendering. Cards at rest (full size or the dimmed
 * size) are copied 1:1; only the two cards changing size are scaled.
 */
public final class LyricsReel {

    /** Size of the previous / next cards next to the current one. */
    public static final float DIM_SCALE = 0.72f;
    /** Height the current card may use before its text shrinks, so the neighbours stay in view. */
    static final int LAYOUT_HEIGHT = 680;
    /** A jump further than this inside a song crossfades instead of scrolling past many cards. */
    static final int MAX_SCROLL = 3;
    private static final int FRAME_W = TextRenderer.FRAME_WIDTH;
    private static final TextRenderer TEXT = new TextRenderer();

    /**
     * Where one card is: {@code top} from the box's vertical middle (frame units), its size (1 = current),
     * opacity, and how much of the highlight color it has (1 = current card).
     */
    public record CardPose(float top, float scale, float alpha, float highlight) {
        CardPose lerp(CardPose o, float t) {
            return new CardPose(top + (o.top - top) * t, scale + (o.scale - scale) * t, alpha + (o.alpha - alpha) * t,
                    highlight + (o.highlight - highlight) * t);
        }

        CardPose hidden() {
            return new CardPose(top, scale, 0f, highlight);
        }
    }

    /** Every card's place for one moment, keyed by card index. */
    public record Pose(Map<Integer, CardPose> cards) {
        public Pose {
            cards = Map.copyOf(cards);
        }
    }

    // ------------------------------------------------------------------ layout (shared, pure)

    /** The lyrics style with the reel's own size and alignment (font, weight, case, shadow come from Lyrics Typography). */
    static SlideStyle style(LiveContent c) {
        SlideStyle base = c.slide().styleOver(c.style());
        LyricsTransitionSettings s = c.lyrics().settings();
        StyleOverrides o = new StyleOverrides();
        o.fontSize = s.fontSize();
        o.align = s.align();
        o.position = TextPosition.MIDDLE;
        return o.applyTo(base);
    }

    /** Card {@code i} laid out at full size, or null for a blank card or one outside the song. */
    static TextRenderer.TextBlock block(LyricsContext ctx, int i, SlideStyle style) {
        String text = ctx.card(i);
        if (text == null || text.isBlank()) {
            return null;
        }
        TextRenderer.TextBlock b = TEXT.layout(null, style.textTransform().apply(text), null, style, LAYOUT_HEIGHT, FRAME_W);
        return b.isEmpty() ? null : b;
    }

    private static float height(LyricsContext ctx, int i, SlideStyle style) {
        TextRenderer.TextBlock b = block(ctx, i, style);
        return b == null ? 0f : (float) b.bounds().getHeight();
    }

    /** The resting pose with card {@code n} current, for the cards {@code lo..hi}. */
    static Pose rest(LyricsContext ctx, SlideStyle style, int n, int lo, int hi) {
        LyricsTransitionSettings s = ctx.settings();
        Map<Integer, CardPose> out = new TreeMap<>();
        float hn = height(ctx, n, style);
        float top = -hn / 2f;
        out.put(n, new CardPose(top, 1f, ctx.card(n) == null ? 0f : 1f, 1f));
        float above = top;
        for (int i = n - 1; i >= lo; i--) {
            float h = height(ctx, i, style) * DIM_SCALE;
            above = above - s.spacing() - h;
            out.put(i, new CardPose(above, DIM_SCALE, i == n - 1 && s.showPrevious() ? s.dimOpacity() : 0f, 0f));
        }
        float below = top + hn + s.spacing();
        for (int i = n + 1; i <= hi; i++) {
            out.put(i, new CardPose(below, DIM_SCALE, i == n + 1 && s.showNext() ? s.dimOpacity() : 0f, 0f));
            below += height(ctx, i, style) * DIM_SCALE + s.spacing();
        }
        return new Pose(out);
    }

    /** True when going from {@code from} to {@code to} scrolls the reel (the same song, a few cards apart). */
    public static boolean scrolls(LiveContent from, LiveContent to) {
        if (from == null || to == null || !from.spotifyLyrics() || !to.spotifyLyrics()) {
            return false;
        }
        LyricsContext a = from.lyrics();
        LyricsContext b = to.lyrics();
        return a.sameSong(b) && Math.abs(a.index() - b.index()) <= MAX_SCROLL && style(from).equals(style(to));
    }

    /**
     * The pose {@code t} (0..1, already eased) of the way from {@code from}'s card to {@code to}'s. {@code start}
     * replaces {@code from}'s resting pose when an earlier animation was cut short (rapid NEXT / PREVIOUS).
     */
    public static Pose pose(LiveContent from, LiveContent to, Pose start, float t) {
        LyricsContext ctx = to.lyrics();
        SlideStyle style = style(to);
        int a = from.lyrics().index();
        int b = ctx.index();
        int last = ctx.cards().size() - 1;
        int lo = Math.max(0, Math.min(a, b) - 2);
        int hi = Math.min(last, Math.max(a, b) + 2);
        Pose end = rest(ctx, style, b, lo, hi);
        Pose begin = start != null ? start : rest(ctx, style, a, lo, hi);
        if (t >= 1f) {
            return end;
        }
        Map<Integer, CardPose> out = new TreeMap<>();
        for (int i = lo; i <= hi; i++) {
            CardPose e = end.cards().get(i);
            CardPose s = begin.cards().get(i);
            if (s == null) {
                s = e.hidden();
            }
            out.put(i, s.lerp(e, Math.clamp(t, 0f, 1f)));
        }
        // a carried start may hold cards outside this range: they keep fading out where they are
        for (var entry : begin.cards().entrySet()) {
            if (!out.containsKey(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue().lerp(entry.getValue().hidden(), Math.clamp(t, 0f, 1f)));
            }
        }
        return new Pose(out);
    }

    /** The resting pose of one content (current card in the middle). */
    public static Pose rest(LiveContent c) {
        LyricsContext ctx = c.lyrics();
        int last = ctx.cards().size() - 1;
        return rest(ctx, style(c), ctx.index(), Math.max(0, ctx.index() - 2), Math.max(0, Math.min(last, ctx.index() + 2)));
    }

    // ------------------------------------------------------------------ painting (per surface: owns its picture cache)

    private record Sprite(BufferedImage img, double x0, double y0, double dev) {
    }

    private record SpriteKey(TextRenderer.TextBlock block, String color, long dev, boolean dim) {
        @Override
        public boolean equals(Object o) {
            return o instanceof SpriteKey k && k.block == block && k.color.equals(color) && k.dev == dev && k.dim == dim;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(block) * 31 + color.hashCode() + Long.hashCode(dev) + (dim ? 7 : 0);
        }
    }

    private final Map<SpriteKey, Sprite> sprites = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SpriteKey, Sprite> eldest) {
            return size() > 20;
        }
    };

    public void clear() {
        sprites.clear();
    }

    /** Paints {@code c} at rest (its card in the middle) into the lyrics box {@code r}. */
    public void paintRest(Graphics2D g, Rectangle r, LiveContent c, double textScale) {
        paint(g, r, c, rest(c), textScale);
    }

    /**
     * Paints the reel at {@code pose} into the box {@code r} (device pixels). {@code textScale} is the scene's text
     * size for this box (1 = as set), applied around the box's middle.
     */
    public void paint(Graphics2D g0, Rectangle r, LiveContent c, Pose pose, double textScale) {
        if (r.width <= 0 || r.height <= 0 || c == null || c.lyrics() == null) {
            return;
        }
        LyricsContext ctx = c.lyrics();
        SlideStyle style = style(c);
        String dimColor = style.fontColor();
        String hiColor = ctx.settings().highlightColor();
        double unit = r.width / (double) FRAME_W;
        double ts = textScale <= 0 ? 1 : textScale;
        double dev = unit * ts; // device pixels per frame unit for a full-size card
        double midY = r.y + r.height / 2.0;
        double midX = r.x + r.width / 2.0;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.clipRect(r.x, r.y, r.width, r.height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            Composite base = g.getComposite();
            for (var entry : pose.cards().entrySet()) {
                CardPose p = entry.getValue();
                if (p.alpha() <= 0.004f) {
                    continue;
                }
                TextRenderer.TextBlock b = block(ctx, entry.getKey(), style);
                if (b == null) {
                    continue;
                }
                Rectangle2D bb = b.bounds();
                double ax = anchorX(style.align(), bb);
                // where the card's block top-left lands, in frame units from the box's middle
                double k = p.scale();
                double fx = (ax - FRAME_W / 2.0) - (ax - bb.getX()) * k;
                double fy = p.top();
                boolean atDim = Math.abs(k - DIM_SCALE) < 0.002;
                boolean atFull = Math.abs(k - 1) < 0.002;
                float a = Math.min(1f, p.alpha());
                drawCard(g, b, style, dimColor, dev, k, atDim, atFull, midX + fx * dev, midY + fy * dev, a, base);
                if (p.highlight() > 0.004f && !hiColor.equalsIgnoreCase(dimColor)) {
                    drawCard(g, b, style, hiColor, dev, k, atDim, atFull, midX + fx * dev, midY + fy * dev,
                            a * Math.min(1f, p.highlight()), base);
                }
            }
            g.setComposite(base);
        } finally {
            g.dispose();
        }
    }

    private void drawCard(Graphics2D g, TextRenderer.TextBlock b, SlideStyle style, String color, double dev, double k,
                          boolean atDim, boolean atFull, double x, double y, float alpha, Composite base) {
        g.setComposite(alpha >= 0.999f ? base : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        if (atDim || atFull) {
            // at rest: a picture made at exactly this size, copied 1:1 (sharp and cheap)
            Sprite s = sprite(b, style, color, dev * (atDim ? DIM_SCALE : 1), atDim);
            double px = x + (s.x0 - b.bounds().getX()) * s.dev;
            double py = y + (s.y0 - b.bounds().getY()) * s.dev;
            g.drawImage(s.img, (int) Math.round(px), (int) Math.round(py), null);
            return;
        }
        // changing size: the full-size picture, scaled
        Sprite s = sprite(b, style, color, dev, false);
        double px = x + (s.x0 - b.bounds().getX()) * dev * k;
        double py = y + (s.y0 - b.bounds().getY()) * dev * k;
        AffineTransform at = new AffineTransform(k, 0, 0, k, px, py);
        g.drawImage(s.img, at, null);
    }

    private Sprite sprite(TextRenderer.TextBlock b, SlideStyle style, String color, double dev, boolean dim) {
        SpriteKey key = new SpriteKey(b, color, Math.round(dev * 10_000), dim);
        Sprite s = sprites.get(key);
        if (s != null) {
            return s;
        }
        Rectangle2D bb = b.bounds();
        double pad = Math.max(8, b.fontSize() * 0.16 + style.outlineWidth() * 2.0);
        double x0 = bb.getX() - pad;
        double y0 = bb.getY() - pad;
        int w = Math.max(1, (int) Math.ceil((bb.getWidth() + pad * 2) * dev));
        int h = Math.max(1, (int) Math.ceil((bb.getHeight() + pad * 2) * dev));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        try {
            SlideRenderer.applyQualityHints(g);
            g.scale(dev, dev);
            g.translate(-x0, -y0);
            StyleOverrides o = new StyleOverrides();
            o.fontColor = color;
            SlideRenderer.paintText(g, b, o.applyTo(style));
        } finally {
            g.dispose();
        }
        s = new Sprite(img, x0, y0, dev);
        sprites.put(key, s);
        return s;
    }

    /** The point that stays put when a card changes size: its left edge, middle or right edge. */
    private static double anchorX(TextAlign align, Rectangle2D bb) {
        return switch (align) {
            case LEFT -> bb.getMinX();
            case RIGHT -> bb.getMaxX();
            case CENTER -> bb.getCenterX();
        };
    }
}
