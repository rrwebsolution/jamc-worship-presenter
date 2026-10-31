package com.jamc.worship.presentation;

import org.apache.poi.sl.draw.DrawFactory;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.usermodel.GraphicalFrame;
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.ObjectShape;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.TextRun;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Renders .pptx and .ppt with Apache POI's Java2D slide drawing.
 *
 * <p>POI draws text, shapes, pictures, tables, gradients and master backgrounds well, but it is not
 * PowerPoint: animations are not played, charts and SmartArt may be simplified, video shows its
 * poster frame, and fonts that are not installed are substituted. Those cases are reported as
 * warnings per slide. A shape that POI fails to draw is skipped (and reported) instead of failing
 * the whole slide.
 */
public final class PoiPresentationRenderer implements PresentationRenderer {

    private static final Logger log = LoggerFactory.getLogger(PoiPresentationRenderer.class);

    @Override
    public String name() {
        return "Built-in (Apache POI)";
    }

    @Override
    public boolean canOpen(Path source) {
        String n = source.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(source) && (n.endsWith(".pptx") || n.endsWith(".ppt") || n.endsWith(".pptm")
                || n.endsWith(".ppsx") || n.endsWith(".pps"));
    }

    @Override
    public Document open(Path source) throws IOException {
        SlideShow<?, ?> show;
        try {
            show = SlideShowFactory.create(source.toFile(), null, true);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // POI reports encrypted, corrupt or unknown files with various runtime exceptions
            throw new IOException(describe(e), e);
        }
        return new PoiDocument(show);
    }

    private static String describe(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.toLowerCase(Locale.ROOT).contains("password") || m.toLowerCase(Locale.ROOT).contains("encrypt")) {
            return "The presentation is password protected. Remove the password in PowerPoint, then import it again.";
        }
        return "The file could not be read as a PowerPoint presentation (" + m + ").";
    }

    private static final class PoiDocument implements Document {
        private final SlideShow<?, ?> show;
        private final List<? extends Slide<?, ?>> slides;
        private final Set<String> installedFonts;
        private final Set<String> reportedFonts = new HashSet<>();

        PoiDocument(SlideShow<?, ?> show) {
            this.show = show;
            this.slides = show.getSlides();
            Set<String> fonts = new HashSet<>();
            for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)) {
                fonts.add(f.toLowerCase(Locale.ROOT));
            }
            this.installedFonts = fonts;
        }

        @Override
        public int slideCount() {
            return slides.size();
        }

        @Override
        public String slideTitle(int index) {
            try {
                String t = slides.get(index).getTitle();
                return t == null || t.isBlank() ? null : t.replaceAll("\\s+", " ").strip();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public BufferedImage render(int index, int maxWidth, int maxHeight, List<String> warnings) {
            Slide<?, ?> slide = slides.get(index);
            Dimension page = show.getPageSize();
            double scale = Math.min(maxWidth / page.getWidth(), maxHeight / page.getHeight());
            int w = Math.max(1, (int) Math.round(page.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(page.getHeight() * scale));

            Set<String> found = new TreeSet<>();
            inspect(slide, found);
            warnings.addAll(found);

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setRenderingHint(Drawable.DRAW_FACTORY, new SafeDrawFactory(warnings));
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.scale(scale, scale);
                slide.draw(g);
            } finally {
                g.dispose();
            }
            return img;
        }

        /** Notes features that POI cannot reproduce, so the operator knows why a slide may differ. */
        private void inspect(Slide<?, ?> slide, Set<String> out) {
            if (slide instanceof XSLFSlide x) {
                String xml = x.getXmlObject().xmlText();
                if (xml.contains("<p:timing") || xml.contains(":timing>")) {
                    out.add("Animations are not played: every object is shown at once.");
                }
                if (xml.contains("<p:transition") || xml.contains(":transition>")) {
                    out.add("The slide transition is replaced by the app's own transition.");
                }
                if (xml.contains("videoFile") || xml.contains("audioFile") || xml.contains("p14:media")) {
                    out.add("Embedded video or audio does not play; its preview image is shown.");
                }
            }
            for (Shape<?, ?> shape : slide.getShapes()) {
                inspectShape(shape, out);
            }
        }

        private void inspectShape(Shape<?, ?> shape, Set<String> out) {
            if (shape instanceof GroupShape<?, ?> group) {
                for (Shape<?, ?> s : group) {
                    inspectShape(s, out);
                }
            } else if (shape instanceof ObjectShape<?, ?>) {
                out.add("An embedded object is shown as its preview image.");
            } else if (shape instanceof GraphicalFrame<?, ?>) {
                out.add("A chart or SmartArt graphic may look simpler than in PowerPoint.");
            } else if (shape instanceof TextShape<?, ?> text) {
                for (TextParagraph<?, ?, ?> p : text) {
                    for (TextRun run : p) {
                        String font = run.getFontFamily();
                        if (font != null && !font.isBlank() && !font.startsWith("+")
                                && !installedFonts.contains(font.toLowerCase(Locale.ROOT)) && reportedFonts.add(font)) {
                            out.add("Font \"" + font + "\" is not installed on this computer; a similar font is used.");
                        }
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            show.close();
        }
    }

    /** Draws each shape independently, so one unsupported shape does not blank the whole slide. */
    private static final class SafeDrawFactory extends DrawFactory {
        private final List<String> warnings;

        SafeDrawFactory(List<String> warnings) {
            this.warnings = warnings;
        }

        @Override
        public Drawable getDrawable(Shape<?, ?> shape) {
            Drawable d = super.getDrawable(shape);
            return new Drawable() {
                @Override
                public void applyTransform(Graphics2D g) {
                    guard(() -> d.applyTransform(g));
                }

                @Override
                public void draw(Graphics2D g) {
                    guard(() -> d.draw(g));
                }

                @Override
                public void drawContent(Graphics2D g) {
                    guard(() -> d.drawContent(g));
                }

                private void guard(Runnable r) {
                    try {
                        r.run();
                    } catch (RuntimeException | LinkageError e) {
                        String what = shape.getShapeName() == null ? shape.getClass().getSimpleName() : shape.getShapeName();
                        log.warn("Could not draw shape \"{}\"", what, e);
                        String msg = "\"" + what + "\" could not be drawn and was left out.";
                        if (!warnings.contains(msg)) {
                            warnings.add(msg);
                        }
                    }
                }
            };
        }
    }
}
