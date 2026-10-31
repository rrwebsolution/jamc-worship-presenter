package com.jamc.worship.presentation;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A strategy that turns a presentation into slide images.
 *
 * <p>Implementations: {@link PowerPointExportRenderer} (Microsoft PowerPoint, exact, only where
 * Office is installed), {@link PoiPresentationRenderer} (Apache POI, built in, close but not
 * pixel-perfect) and {@link ImageSlideRenderer} (a folder of slides already exported as images).
 * New strategies (for example LibreOffice) only need to implement this interface and be added to
 * {@link PresentationRenderers}.
 */
public interface PresentationRenderer {

    /** Shown to the operator, e.g. "Microsoft PowerPoint". */
    String name();

    /** True if this renderer understands the file or folder. */
    boolean canOpen(Path source);

    /** True if the renderer can run on this computer (e.g. PowerPoint is installed). */
    default boolean isAvailable() {
        return true;
    }

    /** Opens the presentation. The caller closes the document. */
    Document open(Path source) throws IOException;

    /** An opened presentation. Slides are numbered from 0. */
    interface Document extends AutoCloseable {

        int slideCount();

        /** The slide's title text, or null if it has none. */
        String slideTitle(int index);

        /**
         * Renders one slide at its own aspect ratio, as large as fits in {@code maxWidth} x {@code maxHeight}.
         * Features that cannot be reproduced exactly are added to {@code warnings}; only a slide that
         * cannot be drawn at all throws.
         */
        BufferedImage render(int index, int maxWidth, int maxHeight, List<String> warnings) throws IOException;

        @Override
        void close() throws IOException;
    }
}
