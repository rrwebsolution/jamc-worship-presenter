package com.jamc.worship.director;

/**
 * One digital camera position inside the full camera picture: a center (normalized 0..1) and a zoom. The cut-out is
 * {@code 1/zoom} of the frame wide and high, so it keeps the camera's own aspect ratio (16:9 from Iriun): nothing is
 * stretched. Zoom 1 is the original wide shot.
 */
public record CropRect(double cx, double cy, double zoom) {

    public static final CropRect WIDE = new CropRect(0.5, 0.5, 1.0);
    /** Lowest zoom: the whole picture. */
    public static final double MIN_ZOOM = 1.0;
    /** Zoom above this loses visible detail on a 1080p camera; the UI warns. */
    public static final double QUALITY_ZOOM = 2.0;
    /** Hard limit (a 1080p frame is then a 480×270 cut-out). */
    public static final double MAX_ZOOM = 4.0;

    public CropRect {
        zoom = Math.clamp(Double.isFinite(zoom) ? zoom : 1.0, MIN_ZOOM, MAX_ZOOM);
        double half = 0.5 / zoom;
        // crop boundary protection: the cut-out never leaves the picture (no black edges)
        cx = Math.clamp(Double.isFinite(cx) ? cx : 0.5, half, 1 - half);
        cy = Math.clamp(Double.isFinite(cy) ? cy : 0.5, half, 1 - half);
    }

    public double width() {
        return 1.0 / zoom;
    }

    public double height() {
        return 1.0 / zoom;
    }

    public Box box() {
        return new Box(cx - width() / 2, cy - height() / 2, width(), height());
    }

    public boolean isWide() {
        return zoom <= 1.0001;
    }

    public CropRect withZoom(double z) {
        return new CropRect(cx, cy, z);
    }

    public CropRect panBy(double dx, double dy) {
        return new CropRect(cx + dx, cy + dy, zoom);
    }

    /**
     * Linear mix of position and of the visible size (not of the zoom number), so a zoom-in moves evenly on screen.
     */
    public CropRect lerp(CropRect o, double t) {
        double w = width() + (o.width() - width()) * t;
        return new CropRect(cx + (o.cx - cx) * t, cy + (o.cy - cy) * t, 1.0 / w);
    }

    public boolean near(CropRect o, double eps) {
        return Math.abs(cx - o.cx) < eps && Math.abs(cy - o.cy) < eps && Math.abs(width() - o.width()) < eps;
    }

    /**
     * Frames a person: their box plus a margin fits inside, the head sits under a little headroom, and the zoom
     * never goes past {@code wantedZoom} (the shot's chosen size). A person too big for that zoom gets a wider
     * framing rather than being cut off.
     *
     * @param person    the person's box (normalized)
     * @param margin    space around the person, as a part of the person's height (0.15 = 15 %)
     * @param wantedZoom the shot's zoom (e.g. 1.5 medium, 2.0 close-up)
     */
    public static CropRect frame(Box person, double margin, double wantedZoom) {
        double m = Math.clamp(margin, 0, 1);
        double needW = person.w() * (1 + 2 * m);
        double needH = person.h() * (1 + 2 * m);
        double fitZoom = 1.0 / Math.max(1e-6, Math.max(needW, needH));
        double z = Math.clamp(Math.min(wantedZoom, fitZoom), MIN_ZOOM, MAX_ZOOM);
        double h = 1.0 / z;
        double cy;
        if (h >= needH) {
            // more room than the person needs: keep the headroom at the top and show more below (hands, instrument)
            cy = person.y() - m * person.h() + h / 2;
        } else {
            cy = person.cy();
        }
        return new CropRect(person.cx(), cy, z);
    }
}
