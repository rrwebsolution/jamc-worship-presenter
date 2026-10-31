package com.jamc.worship.render;

/**
 * Application-wide renderer and live-media frame source. Installed once at startup so every
 * preview surface (thumbnails, editors, monitors, projector, OBS window) shares one image and
 * layout cache and the same running videos.
 */
public final class Rendering {

    private static volatile SlideRenderer renderer;
    private static volatile LiveFrames frames = LiveFrames.NONE;
    private static volatile GlobalBackgroundView globalBackground = GlobalBackgroundView.NONE;
    private static volatile PresentationLibraryView presentations = PresentationLibraryView.NONE;
    /** Small pre-rendered slide pictures (imported presentations); kept apart so they never evict full-size images. */
    private static final ImageCache THUMBNAILS = new ImageCache(null, 600);

    private Rendering() {
    }

    public static void install(SlideRenderer r) {
        renderer = r;
    }

    public static void installFrames(LiveFrames f) {
        frames = f == null ? LiveFrames.NONE : f;
    }

    public static ImageCache thumbnails() {
        return THUMBNAILS;
    }

    public static void installPresentations(PresentationLibraryView view) {
        presentations = view == null ? PresentationLibraryView.NONE : view;
    }

    /** The presentation library as Presentation scene sources see it (never null). */
    public static PresentationLibraryView presentations() {
        return presentations;
    }

    public static void installGlobalBackground(GlobalBackgroundView view) {
        globalBackground = view == null ? GlobalBackgroundView.NONE : view;
    }

    /** The operator's global video background layer (never null). */
    public static GlobalBackgroundView globalBackground() {
        return globalBackground;
    }

    public static LiveFrames frames() {
        return frames;
    }

    public static SlideRenderer renderer() {
        SlideRenderer r = renderer;
        if (r == null) {
            synchronized (Rendering.class) {
                if (renderer == null) {
                    renderer = new SlideRenderer(new ImageCache(null));
                }
                r = renderer;
            }
        }
        return r;
    }
}
