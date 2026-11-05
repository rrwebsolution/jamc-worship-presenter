package com.jamc.worship.output;

import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.BackgroundMode;
import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.render.GlobalBackgroundView;
import com.jamc.worship.render.LiveFrames;
import com.jamc.worship.render.LowerThirdRenderer;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.util.Colors;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one Program frame from a {@link FrameSpec}: the active scene's sources are painted
 * bottom layer first. Static layers (slide background and slide text) are cached as images at
 * the exact pixel size; live layers (video, camera, clock, lower third) are drawn every frame.
 *
 * <p>In key mode (OBS green screen) the background, camera and video layers are replaced by pure
 * green so OBS can chroma-key the text and graphics over its own camera.
 */
public final class Compositor {

    /** Pure green used for chroma keying in OBS. */
    public static final Color KEY_GREEN = new Color(0x00FF00);
    private static final int W = SlideRenderer.WIDTH;
    private static final int H = SlideRenderer.HEIGHT;

    /** Layers used when no scene is set: background, slide text, lower third. */
    private static final Scene DEFAULT_SCENE = defaultScene();

    private record LayerKey(LiveContent content, int w, int h, boolean text, com.jamc.worship.model.TextPosition position,
                            Double scale, Rectangle box) {
        @Override
        public boolean equals(Object o) {
            return o instanceof LayerKey k && k.content == content && k.w == w && k.h == h && k.text == text
                    && k.position == position && java.util.Objects.equals(k.scale, scale)
                    && java.util.Objects.equals(k.box, box);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(content) * 31 + w * 7 + h + (text ? 1 : 0);
        }
    }

    private final SlideRenderer renderer;
    private final boolean preview;
    private final Map<LayerKey, BufferedImage> layers = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LayerKey, BufferedImage> eldest) {
            return size() > 6;
        }
    };
    private final DateTimeFormatter clockFormat = DateTimeFormatter.ofPattern("h:mm a");
    private boolean editorMode;

    public Compositor(SlideRenderer renderer) {
        this(renderer, false);
    }

    /**
     * @param preview compose the operator's Preview instead of Program: the previewed global background,
     *                and a still placeholder instead of the playing Program video
     */
    public Compositor(SlideRenderer renderer, boolean preview) {
        this.renderer = renderer;
        this.preview = preview;
    }

    /** Drops cached layers (e.g. after a background image finished loading). */
    public void invalidate() {
        layers.clear();
    }

    /**
     * Paints the frame into {@code g}, whose coordinate system maps the {@code pw}x{@code ph}
     * pixel area at the origin.
     */
    public void paint(Graphics2D g, int pw, int ph, FrameSpec spec, ProgramOutput lowerThirdSource, boolean keyMode) {
        paint(g, pw, ph, spec, lowerThirdSource, keyMode, false);
    }

    /**
     * @param contentOnly leave out the global video background and the black base, so the result can be
     *                    faded over a background that keeps running (see {@link #sitsOnGlobal})
     */
    public void paint(Graphics2D g, int pw, int ph, FrameSpec spec, ProgramOutput lowerThirdSource, boolean keyMode,
                      boolean contentOnly) {
        if (!contentOnly) {
            g.setColor(keyMode ? KEY_GREEN : Color.BLACK);
            g.fillRect(0, 0, pw, ph);
        }
        if (spec == null || spec.black()) {
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        Scene scene = spec.scene() == null ? DEFAULT_SCENE : spec.scene();
        List<Source> sources = scene.getSources();
        Source slot = presentationSlot(scene, spec.content());
        for (int i = sources.size() - 1; i >= 0; i--) {
            Source s = sources.get(i);
            if (s.isVisible()) {
                paintSource(g, pw, ph, s, spec, lowerThirdSource, keyMode, contentOnly, slot);
            }
        }
    }

    /**
     * Scene editor mode: Presentation sources with nothing to show, or whose library presentation is
     * gone, draw a labelled placeholder instead of nothing (never on Preview / Program outputs).
     */
    public void setEditorMode(boolean on) {
        this.editorMode = on;
    }

    /**
     * The visible Presentation source that shows this content's slide in its own box, or null when the
     * slide fills the screen as usual. A DYNAMIC source takes any library presentation slide; a FIXED one
     * only slides of its own presentation.
     */
    public static Source presentationSlot(Scene scene, LiveContent content) {
        if (scene == null || content == null || content.isVideo() || content.slide().getPresentationId() == null) {
            return null;
        }
        long id = content.slide().getPresentationId();
        // a SPECIFIC source for this very presentation always wins; a FROM LIBRARY (dynamic) source takes the rest
        for (Source s : scene.getSources()) {
            if (s.isVisible() && s.getType() == SourceType.PRESENTATION && !s.getConfig().isDynamicPresentation()
                    && java.util.Objects.equals(s.getConfig().presentationId, id)) {
                return s;
            }
        }
        for (Source s : scene.getSources()) {
            if (s.isVisible() && s.getType() == SourceType.PRESENTATION && s.getConfig().isDynamicPresentation()) {
                return s;
            }
        }
        return null;
    }

    /** Renders a complete frame into a new image (used for tests and stills). */
    public BufferedImage render(FrameSpec spec, int w, int h, boolean keyMode) {
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            SlideRenderer.applyQualityHints(g);
            paint(g, img.getWidth(), img.getHeight(), spec, null, keyMode);
        } finally {
            g.dispose();
        }
        return img;
    }

    private void paintSource(Graphics2D g, int pw, int ph, Source s, FrameSpec spec, ProgramOutput lt, boolean keyMode,
                             boolean contentOnly, Source slot) {
        SourceConfig c = s.getConfig();
        Rectangle rect = rect(c, pw, ph);
        LiveFrames frames = com.jamc.worship.render.Rendering.frames();
        switch (s.getType()) {
            case BACKGROUND -> {
                LiveContent content = spec.content();
                if (keyMode) {
                    return;
                }
                GlobalBackgroundView global = Rendering.globalBackground();
                if (content == null || hidesPresentation(spec, slot)) {
                    // nothing taken yet (or cleared), or a presentation page in a scene without a
                    // Presentation source: the global background alone
                    if (spec.background() && !contentOnly) {
                        paintGlobal(global, g, rect);
                    }
                    return;
                }
                if (content.isVideo()) {
                    if (preview) {
                        // Preview plays the video too (silent): Program's own picture when Program shows the
                        // same video, else a muted looping player; the still card only until the first frame
                        BufferedImage frame = java.util.Objects.equals(frames.programVideoPath(), content.videoPath())
                                ? frames.programVideo() : frames.previewVideo(content.videoPath());
                        if (frame != null) {
                            drawFit(g, frame, rect, FitMode.FIT, 1f);
                        } else {
                            paintVideoPlaceholder(g, rect, content.title());
                        }
                    } else {
                        drawFit(g, frames.programVideo(), rect, FitMode.FIT, 1f);
                    }
                } else if (spec.background()) {
                    SlideStyle style = content.slide().styleOver(content.style());
                    boolean globalOn = global.isActive() || (preview && global.hasPreview());
                    if (slot != null) {
                        // the slide is drawn inside the scene's Presentation box; underneath is the global background
                        if (globalOn && !contentOnly) {
                            paintGlobal(global, g, rect);
                        }
                    } else if (globalOn && GlobalBackgroundView.usesGlobal(content.slide(), style)) {
                        if (!contentOnly) {
                            paintGlobal(global, g, rect); // the same running video under every song, verse and slide
                        }
                    } else if (style.backgroundMode() == BackgroundMode.HIDE) {
                        // text on black
                    } else if (style.backgroundType() == BackgroundType.VIDEO) {
                        // Live video background: drawn every frame, never cached.
                        renderer.paintBackground(g, rect, style, true);
                    } else {
                        g.drawImage(layer(content, pw, ph, false), 0, 0, null);
                    }
                }
            }
            case LYRICS, BIBLE -> {
                boolean fixed = s.getType() == SourceType.LYRICS && c.isFixedSong();
                // a source fixed to one song shows only that song (its last slide while another is sent)
                LiveContent content = fixed ? songContent(c, spec.content()) : spec.content();
                if (fixed && content == null && editorMode) {
                    SourceConfig label = new SourceConfig();
                    label.fontSize = 36;
                    label.color = "#94A3B8";
                    drawText(g, rect, s.getName() + " · this song only", label, ph);
                }
                if (content != null && !content.isVideo() && spec.text() && (fixed || !hidesPresentation(spec, slot))) {
                    // a scene may place the text itself (e.g. smaller, at the bottom over a camera)
                    // inside the layer's box when the scene gives it one (smaller box = smaller text)
                    boolean fullFrame = c.x <= 0 && c.y <= 0 && c.w >= 1 && c.h >= 1;
                    g.drawImage(layer(content, pw, ph, true, c.textPosition, c.textScale, fullFrame ? null : rect), 0, 0, null);
                }
            }
            case OVERLAY -> {
                if (!keyMode) {
                    paintOverlay(g, rect, c.opacity == null ? 0.6f : c.opacity.floatValue());
                }
            }
            case CAMERA -> {
                if (!keyMode) {
                    BufferedImage frame = frames.camera(c.camera);
                    if (frame != null) {
                        drawCamera(g, frame, rect, c, opacity(c));
                    } else {
                        paintCameraOffline(g, rect, s, frames.cameraStatus(c.camera), ph);
                    }
                }
            }
            case IMAGE, CHURCH_LOGO -> {
                if (c.path != null) {
                    drawFit(g, renderer.images().get(c.path), rect, c.fit == null ? FitMode.FIT : c.fit, opacity(c));
                }
            }
            case VIDEO -> {
                if (!keyMode && c.path != null) {
                    // picture of the source's own player (on Program it also plays the sound)
                    BufferedImage frame = frames.sceneVideo(s);
                    if (!Boolean.FALSE.equals(c.videoEnabled)) {
                        drawFit(g, frame, rect, c.fit, opacity(c));
                    }
                }
            }
            case PRESENTATION -> paintPresentation(g, rect, s, spec.content(), s == slot, ph);
            case SLIDESHOW -> paintSlideshow(g, rect, s, keyMode, ph);
            case COLOR -> {
                if (!keyMode) {
                    Color fill = Colors.parse(c.color, Color.BLACK);
                    g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), Math.round(opacity(c) * 255)));
                    g.fillRect(rect.x, rect.y, rect.width, rect.height);
                }
            }
            case AUDIO, AUDIO_INPUT -> {
                // sound only: nothing on the outputs; a small label in the scene editor
                if (editorMode) {
                    SourceConfig label = new SourceConfig();
                    label.fontSize = 36;
                    label.color = "#94A3B8";
                    drawText(g, rect, "♪ " + s.getName(), label, ph);
                }
            }
            case TEXT -> drawText(g, rect, c.text, c, ph);
            case CLOCK -> drawText(g, rect, LocalTime.now().format(clockPattern(c)), c, ph);
            case LOWER_THIRD -> {
                if (lt != null && lt.lowerThird() != null) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.scale(pw / (double) W, ph / (double) H);
                    LowerThirdRenderer.paint(g2, lt.lowerThird(), lt.lowerThirdProgress(), renderer.images());
                    g2.dispose();
                }
            }
        }
    }

    private static volatile Slideshows slideshows;

    /** The running IMAGE SLIDESHOW sources (Preview and Program each have their own position). */
    public static void installSlideshows(Slideshows s) {
        slideshows = s;
    }

    /**
     * An IMAGE SLIDESHOW source: its current image, and while it changes images its own fade or
     * crossfade, inside this layer only (no other layer is touched).
     */
    private void paintSlideshow(Graphics2D g, Rectangle rect, Source s, boolean keyMode, int ph) {
        Slideshows shows = slideshows;
        Slideshows.View v = shows == null ? null : shows.view(s, preview || editorMode);
        SourceConfig c = s.getConfig();
        float op = opacity(c);
        if ("black".equals(c.slideBackground) && !keyMode) {
            g.setColor(new Color(0, 0, 0, Math.round(op * 255)));
            g.fillRect(rect.x, rect.y, rect.width, rect.height);
        }
        if (v == null) {
            if (editorMode) {
                SourceConfig label = new SourceConfig();
                label.fontSize = 36;
                label.color = "#94A3B8";
                drawText(g, rect, "Image Slideshow: no images", label, ph);
            }
            return;
        }
        FitMode fit = c.fit == null ? FitMode.FIT : c.fit;
        BufferedImage current = renderer.images().get(v.current());
        BufferedImage previous = v.previous() == null ? null : renderer.images().get(v.previous());
        float p = v.progress();
        if (previous == null || p >= 1f || current == null) {
            // a new image still loading keeps the previous one until it is ready
            drawFit(g, current != null ? current : previous, rect, fit, op);
            return;
        }
        switch (v.transition()) {
            case CROSSFADE -> {
                drawFit(g, previous, rect, fit, op);
                drawFit(g, current, rect, fit, op * p);
            }
            case FADE -> {
                if (p < 0.5f) {
                    drawFit(g, previous, rect, fit, op * (1f - p * 2f));
                } else {
                    drawFit(g, current, rect, fit, op * (p * 2f - 1f));
                }
            }
            case CUT -> drawFit(g, current, rect, fit, op);
        }
    }

    /**
     * A Presentation source: the slide being sent when this box takes it, otherwise its own presentation's
     * remembered slide (looked up in the library by id). In the scene editor an empty or broken source
     * shows a labelled placeholder; on the outputs it simply shows nothing.
     */
    /** Last slide each SPECIFIC song / presentation showed on this output (kept while another one is sent). */
    private final Map<Long, LiveContent> lastSongSlide = new java.util.HashMap<>();
    private final Map<Long, LiveContent> lastPresentationSlide = new java.util.HashMap<>();

    /**
     * What a lyrics source fixed to one song shows: the content when it is a slide of that song (remembered), else
     * the song's last slide shown here, or null when none has been sent yet.
     */
    LiveContent songContent(SourceConfig c, LiveContent content) {
        if (content != null && !content.isVideo() && java.util.Objects.equals(content.slide().getSongId(), c.songId)) {
            lastSongSlide.put(c.songId, content);
            return content;
        }
        return lastSongSlide.get(c.songId);
    }

    private void paintPresentation(Graphics2D g, Rectangle rect, Source s, LiveContent content, boolean takesContent, int ph) {
        SourceConfig c = s.getConfig();
        FitMode fit = c.fit == null ? FitMode.FIT : c.fit;
        String image = null;
        if (takesContent) {
            StyleOverrides o = content.slide().getOverrides();
            image = o == null ? null : o.backgroundValue;
            if (!c.isDynamicPresentation() && c.presentationId != null) {
                lastPresentationSlide.put(c.presentationId, content);
            }
        } else if (c.presentationId != null) {
            // a SPECIFIC presentation keeps its last sent slide while another presentation is clicked
            LiveContent last = c.isDynamicPresentation() ? null : lastPresentationSlide.get(c.presentationId);
            StyleOverrides o = last == null ? null : last.slide().getOverrides();
            image = o != null && o.backgroundValue != null ? o.backgroundValue
                    : Rendering.presentations().slideImage(c.presentationId, c.slideIndex == null ? 0 : c.slideIndex);
        }
        BufferedImage img = image == null ? null : renderer.images().get(image);
        if (img != null) {
            drawFit(g, img, rect, fit, opacity(c));
            return;
        }
        if (!editorMode) {
            return;
        }
        boolean missing = c.presentationId != null && Rendering.presentations().slideCount(c.presentationId) == 0;
        String label = missing ? "⚠ Presentation unavailable"
                : c.presentationId == null || takesContent ? "PRESENTATION" + (c.isDynamicPresentation() ? " · dynamic" : "")
                : "Loading slide…";
        String hint = missing ? "Select another presentation" : c.isDynamicPresentation() && c.presentationId == null
                ? "Shows the presentation slide you send to Preview" : null;
        g.setColor(new Color(0x172033));
        g.fillRect(rect.x, rect.y, rect.width, rect.height);
        g.setColor(missing ? new Color(0xF59E0B) : new Color(0x64748B));
        g.drawRect(rect.x, rect.y, Math.max(1, rect.width - 1), Math.max(1, rect.height - 1));
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.clipRect(rect.x, rect.y, rect.width, rect.height);
        g2.setFont(new Font("Segoe UI", Font.BOLD, Math.max(10, Math.round(40f * ph / H))));
        FontMetrics fm = g2.getFontMetrics();
        int y = rect.y + rect.height / 2;
        g2.drawString(label, rect.x + (rect.width - fm.stringWidth(label)) / 2, y);
        if (hint != null) {
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, g2.getFont().getSize2D() * 0.6f));
            FontMetrics hm = g2.getFontMetrics();
            g2.setColor(new Color(0x94A3B8));
            g2.drawString(hint, rect.x + (rect.width - hm.stringWidth(hint)) / 2, y + hm.getHeight() + 4);
        }
        g2.dispose();
    }

    /** Slide background or slide text rendered once per content and size. */
    private BufferedImage layer(LiveContent content, int pw, int ph, boolean text) {
        return layer(content, pw, ph, text, null, null, null);
    }

    private BufferedImage layer(LiveContent content, int pw, int ph, boolean text, com.jamc.worship.model.TextPosition position,
                                Double scale, Rectangle box) {
        LayerKey key = new LayerKey(content, pw, ph, text, position, scale, box);
        BufferedImage img = layers.get(key);
        if (img == null) {
            img = new BufferedImage(pw, ph, text ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            SlideRenderer.applyQualityHints(g);
            Slide slide = content.slide();
            SlideStyle style = slide.styleOver(content.style());
            if (position != null || scale != null) {
                com.jamc.worship.model.StyleOverrides o = new com.jamc.worship.model.StyleOverrides();
                o.position = position;
                o.fontSize = scale == null ? null : (int) Math.round(style.fontSize() * scale);
                style = o.applyTo(style);
            }
            Rectangle r = box != null ? box : new Rectangle(0, 0, pw, ph);
            if (text) {
                renderer.paintTextLayer(g, r, slide.getHeading(), slide.getText(), slide.getCaption(), style);
            } else {
                renderer.paintBackground(g, r, style, true);
            }
            g.dispose();
            layers.put(key, img);
        }
        return img;
    }

    /** Readability overlay: clear at the top of its area, darkest at the bottom where the lyrics sit. */
    private static void paintOverlay(Graphics2D g, Rectangle r, float opacity) {
        if (r.width <= 0 || r.height <= 0 || opacity <= 0f) {
            return;
        }
        var old = g.getPaint();
        int alpha = Math.round(Math.clamp(opacity, 0f, 1f) * 255);
        g.setPaint(new java.awt.GradientPaint(r.x, r.y, new Color(0, 0, 0, 0), r.x, r.y + r.height * 0.6f,
                new Color(0, 0, 0, alpha)));
        g.fillRect(r.x, r.y, r.width, r.height);
        g.setPaint(old);
    }

    /**
     * A camera picture in its box: cropped, rotated, mirrored, then fitted (Fit / Fill / Stretch) at an opacity.
     * Shared by Program, Preview, the scene editor and the camera library, so they all look the same.
     */
    public static void drawCamera(Graphics2D g, BufferedImage img, Rectangle r, SourceConfig c, float alpha) {
        if (img == null || r.width <= 0 || r.height <= 0) {
            return;
        }
        BufferedImage pic = crop(img, c);
        int rot = rotation(c);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.clipRect(r.x, r.y, r.width, r.height);
        Rectangle box = r;
        if (rot != 0) {
            // turn the drawing around the box's center; for 90° / 270° the picture is fitted to the box turned on its side
            g2.translate(r.getCenterX(), r.getCenterY());
            g2.rotate(Math.toRadians(rot));
            int bw = rot % 180 == 0 ? r.width : r.height;
            int bh = rot % 180 == 0 ? r.height : r.width;
            box = new Rectangle(-bw / 2, -bh / 2, bw, bh);
        }
        if (Boolean.TRUE.equals(c.mirror)) {
            g2.translate(box.x * 2 + box.width, 0);
            g2.scale(-1, 1); // flipped around the box's vertical center line
        }
        drawFit(g2, pic, box, c.fit, alpha);
        g2.dispose();
    }

    /** 0, 90, 180 or 270. */
    static int rotation(SourceConfig c) {
        int r = c.rotation == null ? 0 : Math.floorMod(c.rotation, 360);
        return r - r % 90;
    }

    /** The part of the picture left after the source's crop (the whole picture when nothing is cropped). */
    static BufferedImage crop(BufferedImage img, SourceConfig c) {
        double l = edge(c.cropLeft);
        double t = edge(c.cropTop);
        double r = edge(c.cropRight);
        double b = edge(c.cropBottom);
        if (l + t + r + b <= 0) {
            return img;
        }
        int x = (int) Math.round(img.getWidth() * l);
        int y = (int) Math.round(img.getHeight() * t);
        int w = Math.max(1, img.getWidth() - x - (int) Math.round(img.getWidth() * r));
        int h = Math.max(1, img.getHeight() - y - (int) Math.round(img.getHeight() * b));
        return img.getSubimage(Math.min(x, img.getWidth() - 1), Math.min(y, img.getHeight() - 1),
                Math.min(w, img.getWidth() - x), Math.min(h, img.getHeight() - y));
    }

    private static double edge(Double v) {
        return v == null ? 0 : Math.clamp(v, 0.0, 0.45);
    }

    /**
     * A camera with no picture. While it starts: nothing (or a placeholder in the scene editor). Disconnected
     * or failed: the source's offline choice, "CAMERA OFFLINE" by default, black, or an image such as the church
     * logo. Never an error message on the outputs.
     */
    private void paintCameraOffline(Graphics2D g, Rectangle r, Source s, com.jamc.worship.camera.CameraStatus status, int ph) {
        if (r.width <= 0 || r.height <= 0) {
            return;
        }
        SourceConfig c = s.getConfig();
        boolean offline = status != null && status.isOffline();
        if (!offline) {
            if (editorMode) {
                slate(g, r, s.getName(), status == null ? "STARTING" : status.label(), ph);
            }
            return;
        }
        String mode = c.offline == null ? "SLATE" : c.offline;
        if ("BLACK".equals(mode)) {
            g.setColor(Color.BLACK);
            g.fillRect(r.x, r.y, r.width, r.height);
        } else if ("IMAGE".equals(mode) && c.offlineImage != null && renderer.images().get(c.offlineImage) != null) {
            g.setColor(Color.BLACK);
            g.fillRect(r.x, r.y, r.width, r.height);
            drawFit(g, renderer.images().get(c.offlineImage), r, FitMode.FIT, 1f);
        } else {
            slate(g, r, "CAMERA OFFLINE", editorMode ? s.getName() + " · " + status.label() : null, ph);
        }
    }

    private static void slate(Graphics2D g, Rectangle r, String title, String detail, int ph) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.clipRect(r.x, r.y, r.width, r.height);
        g2.setPaint(new java.awt.GradientPaint(r.x, r.y, new Color(0x1E293B), r.x, r.y + r.height, new Color(0x0F172A)));
        g2.fillRect(r.x, r.y, r.width, r.height);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int size = Math.max(9, Math.min(r.height / 9, Math.round(56f * ph / H)));
        g2.setFont(new Font("Segoe UI", Font.BOLD, size));
        FontMetrics fm = g2.getFontMetrics();
        int y = r.y + r.height / 2 + (detail == null ? fm.getAscent() / 2 : 0);
        g2.setColor(new Color(0xCBD5E1));
        g2.drawString(title, r.x + (r.width - fm.stringWidth(title)) / 2, y);
        if (detail != null) {
            g2.setFont(new Font("Segoe UI", Font.PLAIN, Math.max(8, size * 2 / 3)));
            FontMetrics dm = g2.getFontMetrics();
            g2.setColor(new Color(0x94A3B8));
            g2.drawString(detail, r.x + (r.width - dm.stringWidth(detail)) / 2, y + dm.getHeight() + 2);
        }
        g2.dispose();
    }

    private static void drawFit(Graphics2D g, BufferedImage img, Rectangle r, FitMode fit, float alpha) {
        if (img == null || r.width <= 0 || r.height <= 0) {
            return;
        }
        Composite old = g.getComposite();
        if (alpha < 1f) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.clipRect(r.x, r.y, r.width, r.height);
        double sx = r.width / (double) img.getWidth();
        double sy = r.height / (double) img.getHeight();
        switch (fit == null ? FitMode.FILL : fit) {
            case STRETCH -> g2.drawImage(img, r.x, r.y, r.width, r.height, null);
            case FIT -> {
                double s = Math.min(sx, sy);
                int w = (int) Math.round(img.getWidth() * s);
                int h = (int) Math.round(img.getHeight() * s);
                g2.drawImage(img, r.x + (r.width - w) / 2, r.y + (r.height - h) / 2, w, h, null);
            }
            case FILL -> {
                double s = Math.max(sx, sy);
                int w = (int) Math.ceil(img.getWidth() * s);
                int h = (int) Math.ceil(img.getHeight() * s);
                g2.drawImage(img, r.x + (r.width - w) / 2, r.y + (r.height - h) / 2, w, h, null);
            }
        }
        g2.dispose();
        g.setComposite(old);
    }

    private static void drawText(Graphics2D g, Rectangle r, String text, SourceConfig c, int ph) {
        if (text == null || text.isBlank()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float size = (c.fontSize == null ? 48 : c.fontSize) * ph / (float) H;
        Font font = new Font(c.fontFamily == null ? "Segoe UI" : c.fontFamily, Font.BOLD, Math.max(1, Math.round(size)));
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int x = r.x + (r.width - fm.stringWidth(text)) / 2;
        int y = r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent();
        g2.setColor(new Color(0, 0, 0, 150));
        g2.drawString(text, x + Math.max(1, Math.round(size / 24)), y + Math.max(1, Math.round(size / 24)));
        g2.setColor(Colors.parse(c.color, Color.WHITE));
        g2.drawString(text, x, y);
        g2.dispose();
    }

    private DateTimeFormatter clockPattern(SourceConfig c) {
        if (c.text != null && !c.text.isBlank()) {
            try {
                return DateTimeFormatter.ofPattern(c.text);
            } catch (IllegalArgumentException ignored) {
                // fall back to the default pattern
            }
        }
        return clockFormat;
    }

    private static float opacity(SourceConfig c) {
        return c.opacity == null ? 1f : (float) Math.clamp(c.opacity, 0.0, 1.0);
    }

    private static Rectangle rect(SourceConfig c, int pw, int ph) {
        return new Rectangle((int) Math.round(c.x * pw), (int) Math.round(c.y * ph),
                (int) Math.round(c.w * pw), (int) Math.round(c.h * ph));
    }

    private void paintGlobal(GlobalBackgroundView global, Graphics2D g, Rectangle rect) {
        if (preview) {
            global.paintPreview(g, rect);
        } else {
            global.paint(g, rect);
        }
    }

    /** Preview of a service video: a still card; the video itself only plays once it is taken. */
    private static void paintVideoPlaceholder(Graphics2D g, Rectangle r, String title) {
        g.setColor(new Color(0x0B1220));
        g.fillRect(r.x, r.y, r.width, r.height);
        int s = Math.max(10, r.height / 6);
        int cx = r.x + r.width / 2;
        int cy = r.y + r.height / 2 - s / 3;
        g.setColor(new Color(255, 255, 255, 200));
        g.fillPolygon(new int[]{cx - s / 2, cx + s / 2, cx - s / 2}, new int[]{cy - s / 2, cy, cy + s / 2}, 3);
        if (title != null) {
            Font f = new Font("Segoe UI", Font.BOLD, Math.max(9, r.height / 14));
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(title, cx - fm.stringWidth(title) / 2, cy + s / 2 + fm.getHeight() + 2);
        }
    }

    /**
     * True when a change between these frames is a CONTENT change inside one scene (next lyrics, another
     * verse or slide, lyrics on/off): it then transitions per layer with {@link #paintLayered} and the
     * scene's video, camera, logo and other sources keep running untouched. Going to or from black, or
     * switching to another scene, is a whole-frame transition.
     */
    public static boolean contentChangeOnly(FrameSpec from, FrameSpec to) {
        if (from == null || to == null || from.black() || to.black()) {
            return false;
        }
        Scene a = from.scene();
        Scene b = to.scene();
        return a == b || (a != null && b != null && a.getId() != null && a.getId().equals(b.getId()));
    }

    /**
     * One frame of a content transition inside a scene: every layer that does not show the content is
     * painted once, at full opacity, from {@code to} (so a playing video never fades, pauses or dims);
     * only the content layers (Lyrics, Bible, the slide background, the Presentation box taking the
     * slide) fade from the old content ({@code fromAlpha}) to the new one ({@code toAlpha}). A slide
     * background that is the same before and after (the running global video, one video or image theme)
     * is also painted only once.
     */
    public void paintLayered(Graphics2D g, int pw, int ph, FrameSpec from, FrameSpec to, ProgramOutput lowerThirdSource,
                             boolean keyMode, float fromAlpha, float toAlpha) {
        g.setColor(keyMode ? KEY_GREEN : Color.BLACK);
        g.fillRect(0, 0, pw, ph);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        Scene scene = to.scene() == null ? DEFAULT_SCENE : to.scene();
        Source fromSlot = presentationSlot(scene, from.content());
        Source toSlot = presentationSlot(scene, to.content());
        String fromBackground = backgroundKey(from, fromSlot);
        boolean sameBackground = fromBackground != null && fromBackground.equals(backgroundKey(to, toSlot));
        List<Source> sources = scene.getSources();
        for (int i = sources.size() - 1; i >= 0; i--) {
            Source s = sources.get(i);
            if (!s.isVisible()) {
                continue;
            }
            boolean content = switch (s.getType()) {
                // a source fixed to one song changes only when that song's slide changes
                case LYRICS -> !s.getConfig().isFixedSong()
                        || songContent(s.getConfig(), from.content()) != songContent(s.getConfig(), to.content());
                case BIBLE -> true;
                case BACKGROUND -> !sameBackground;
                case PRESENTATION -> s == fromSlot || s == toSlot;
                default -> false;
            };
            if (!content) {
                paintSource(g, pw, ph, s, to, lowerThirdSource, keyMode, false, toSlot);
                continue;
            }
            fadeSource(g, pw, ph, s, from, fromSlot, lowerThirdSource, keyMode, fromAlpha);
            fadeSource(g, pw, ph, s, to, toSlot, lowerThirdSource, keyMode, toAlpha);
        }
    }

    /**
     * True for a change from one scene to another (both showing, neither black): it transitions per layer with
     * {@link #paintSceneChange}, so layers both scenes share keep going untouched.
     */
    public static boolean sceneChange(FrameSpec from, FrameSpec to) {
        return from != null && to != null && !from.black() && !to.black() && !contentChangeOnly(from, to);
    }

    /**
     * One frame of a scene change. A layer that looks the same in both scenes (the lyrics on Program, the logo,
     * the global background, a lower third...) is painted once at full opacity, so it never fades or blinks;
     * only the layers that differ fade: the old scene's out ({@code fromAlpha}), the new scene's in
     * ({@code toAlpha}). Switching CAM 1 to CAM 2 under the same lyrics therefore changes only the camera.
     */
    public void paintSceneChange(Graphics2D g, int pw, int ph, FrameSpec from, FrameSpec to, ProgramOutput lowerThirdSource,
                                 boolean keyMode, float fromAlpha, float toAlpha) {
        g.setColor(keyMode ? KEY_GREEN : Color.BLACK);
        g.fillRect(0, 0, pw, ph);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        Scene a = from.scene() == null ? DEFAULT_SCENE : from.scene();
        Scene b = to.scene() == null ? DEFAULT_SCENE : to.scene();
        Source fromSlot = presentationSlot(a, from.content());
        Source toSlot = presentationSlot(b, to.content());
        List<Source> as = a.getSources().stream().filter(Source::isVisible).toList();
        List<Source> bs = b.getSources().stream().filter(Source::isVisible).toList();
        java.util.Set<String> aKeys = new java.util.HashSet<>();
        java.util.Set<String> bKeys = new java.util.HashSet<>();
        String[] ak = new String[as.size()];
        String[] bk = new String[bs.size()];
        for (int i = 0; i < as.size(); i++) {
            ak[i] = layerKey(as.get(i), from, fromSlot);
            aKeys.add(ak[i]);
        }
        for (int i = 0; i < bs.size(); i++) {
            bk[i] = layerKey(bs.get(i), to, toSlot);
            bKeys.add(bk[i]);
        }
        // walk both stacks from the bottom up: the old scene's own layers fade out where they were, the new
        // scene's own layers fade in above them, and each shared layer is painted once, from the new scene
        int ia = as.size() - 1;
        int ib = bs.size() - 1;
        while (ia >= 0 || ib >= 0) {
            while (ia >= 0 && !bKeys.contains(ak[ia])) {
                fadeSource(g, pw, ph, as.get(ia), from, fromSlot, lowerThirdSource, keyMode, fromAlpha);
                ia--;
            }
            while (ib >= 0 && !aKeys.contains(bk[ib])) {
                fadeSource(g, pw, ph, bs.get(ib), to, toSlot, lowerThirdSource, keyMode, toAlpha);
                ib--;
            }
            if (ib >= 0) {
                paintSource(g, pw, ph, bs.get(ib), to, lowerThirdSource, keyMode, false, toSlot);
                ib--;
            }
            if (ia >= 0) {
                ia--;
            }
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper LAYER_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final Map<Source, String> configKeys = new java.util.WeakHashMap<>();

    /**
     * What a layer shows, as text: its type and settings, plus, for layers that show the live content, which
     * content. Two layers with the same key look the same.
     */
    private String layerKey(Source s, FrameSpec spec, Source slot) {
        String config = configKeys.computeIfAbsent(s, src -> {
            SourceConfig c = src.getConfig().copy();
            c.uid = null; // bookkeeping that does not change the picture
            c.locked = null;
            c.keepAspect = null;
            try {
                return src.getType() + LAYER_JSON.writeValueAsString(c);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return src.getType() + "@" + System.identityHashCode(src);
            }
        });
        LiveContent content = spec.content();
        return switch (s.getType()) {
            case LYRICS, BIBLE -> config + "|" + System.identityHashCode(s.getConfig().isFixedSong() && s.getType() == SourceType.LYRICS
                    ? songContent(s.getConfig(), content) : content) + "|" + spec.text();
            case BACKGROUND -> {
                String bg = backgroundKey(spec, slot);
                yield config + "|" + (bg != null ? bg : "c" + System.identityHashCode(content)) + "|" + spec.background();
            }
            case PRESENTATION -> config + "|" + (s == slot ? System.identityHashCode(content) : "own");
            default -> config;
        };
    }

    private BufferedImage fadeBuffer;

    /** Paints one source at an opacity (through a buffer, so every kind of layer fades the same way). */
    private void fadeSource(Graphics2D g, int pw, int ph, Source s, FrameSpec spec, Source slot, ProgramOutput lt,
                            boolean keyMode, float alpha) {
        if (alpha <= 0f || (spec.black())) {
            return;
        }
        if (alpha >= 1f) {
            paintSource(g, pw, ph, s, spec, lt, keyMode, false, slot);
            return;
        }
        if (fadeBuffer == null || fadeBuffer.getWidth() != pw || fadeBuffer.getHeight() != ph) {
            fadeBuffer = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        }
        Graphics2D b = fadeBuffer.createGraphics();
        try {
            b.setComposite(AlphaComposite.Clear);
            b.fillRect(0, 0, pw, ph);
            b.setComposite(AlphaComposite.SrcOver);
            b.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            b.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            paintSource(b, pw, ph, s, spec, lt, keyMode, false, slot);
        } finally {
            b.dispose();
        }
        java.awt.Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.drawImage(fadeBuffer, 0, 0, null);
        g.setComposite(old);
    }

    /**
     * What the BACKGROUND layer shows for this frame, as a comparable key, or null when it cannot be
     * compared. Two frames with the same key share one background (painted once during a transition).
     */
    private String backgroundKey(FrameSpec spec, Source slot) {
        LiveContent content = spec.content();
        GlobalBackgroundView global = Rendering.globalBackground();
        boolean globalOn = global.isActive() || (preview && global.hasPreview());
        if (!spec.background()) {
            return content != null && content.isVideo() ? null : "none";
        }
        if (content == null) {
            return "global";
        }
        if (content.isVideo()) {
            return null; // the operator's own video is the content itself
        }
        SlideStyle style = content.slide().styleOver(content.style());
        if (slot != null) {
            return globalOn ? "global" : "none";
        }
        if (globalOn && GlobalBackgroundView.usesGlobal(content.slide(), style)) {
            return "global";
        }
        if (style.backgroundMode() == BackgroundMode.HIDE) {
            return "none";
        }
        return style.backgroundType() + ":" + style.backgroundValue() + ":" + style.backgroundColor() + ":"
                + style.overlayOpacity();
    }

    /**
     * True if this frame shows the global video background behind its content, so a change between two
     * such frames can fade only the content while the background keeps running.
     */
    public static boolean sitsOnGlobal(FrameSpec spec) {
        if (spec == null || spec.black() || !spec.background() || !Rendering.globalBackground().isActive()) {
            return false;
        }
        Scene scene = spec.scene() == null ? DEFAULT_SCENE : spec.scene();
        boolean hasBackground = scene.getSources().stream()
                .anyMatch(s -> s.isVisible() && s.getType() == SourceType.BACKGROUND);
        LiveContent c = spec.content();
        return hasBackground && (c == null || (!c.isVideo()
                && GlobalBackgroundView.usesGlobal(c.slide(), c.slide().styleOver(c.style()))));
    }

    /**
     * Presentation pages show only in a scene's Presentation source, never through its Lyrics / Bible or
     * Media layers: a scene without a (matching) Presentation source does not show them. Lyrics, Bible
     * and media are unchanged. Without a scene (the built-in composition) pages fill the screen as before.
     */
    static boolean hidesPresentation(FrameSpec spec, Source slot) {
        LiveContent c = spec.content();
        return spec.scene() != null && slot == null && c != null && !c.isVideo()
                && c.slide().getPresentationId() != null;
    }

    private static Scene defaultScene() {
        Scene s = new Scene(null, "Default");
        s.getSources().add(new Source("Lower Third", SourceType.LOWER_THIRD));
        s.getSources().add(new Source("Text", SourceType.LYRICS));
        s.getSources().add(new Source("Background", SourceType.BACKGROUND));
        return s;
    }
}
