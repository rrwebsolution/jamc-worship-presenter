package com.jamc.worship.presentation;

import java.nio.file.Path;
import java.util.List;

/** The renderers to try for a source, best first. */
public final class PresentationRenderers {

    private final List<PresentationRenderer> renderers;

    public PresentationRenderers(List<PresentationRenderer> renderers) {
        this.renderers = List.copyOf(renderers);
    }

    /**
     * PowerPoint (if installed), else WPS Presentation (same automation, same exact look), then Apache POI,
     * then image folders.
     */
    public static PresentationRenderers defaults() {
        return new PresentationRenderers(List.of(new PowerPointExportRenderer(),
                new PowerPointExportRenderer("KWPP.Application", "WPS Presentation"), new PoiPresentationRenderer(),
                new ImageSlideRenderer()));
    }

    /** Renderers that can open {@code source}, in order of preference. */
    public List<PresentationRenderer> candidates(Path source) {
        return renderers.stream().filter(r -> r.canOpen(source)).filter(PresentationRenderer::isAvailable).toList();
    }
}
