package com.jamc.worship.model;

/** How a picture (camera, image, video) fills its source rectangle. */
public enum FitMode {
    /** Whole picture visible, aspect ratio kept (may leave bars). */
    FIT("Fit (keep aspect ratio)"),
    /** Rectangle completely covered, aspect ratio kept (edges cropped). */
    FILL("Fill (crop edges)"),
    /** Picture stretched to the rectangle. */
    STRETCH("Stretch");

    private final String label;

    FitMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
