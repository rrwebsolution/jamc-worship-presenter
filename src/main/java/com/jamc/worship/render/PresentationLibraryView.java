package com.jamc.worship.render;

/**
 * Read access to the presentation library for scene sources: a Presentation source stores only a
 * library presentation id, and the renderers look its already-rendered slide pictures up here.
 * Nothing is imported or copied. {@link #NONE} when no library is installed (tests, tools).
 */
public interface PresentationLibraryView {

    /** Absolute path of the cached picture of slide {@code index} (0-based), or null if unknown or missing. */
    String slideImage(long presentationId, int index);

    /** Number of slides, or 0 if the presentation is not in the library (deleted) or has no cached slides. */
    int slideCount(long presentationId);

    /** Library name, or null if the presentation is no longer in the library. */
    String name(long presentationId);

    PresentationLibraryView NONE = new PresentationLibraryView() {
        @Override
        public String slideImage(long presentationId, int index) {
            return null;
        }

        @Override
        public int slideCount(long presentationId) {
            return 0;
        }

        @Override
        public String name(long presentationId) {
            return null;
        }
    };
}
