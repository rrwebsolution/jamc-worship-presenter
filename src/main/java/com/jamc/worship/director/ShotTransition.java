package com.jamc.worship.director;

/**
 * How the digital camera moves from one shot to the next. All of them work on the same camera frames (no second
 * capture, no decoder restart), so there is never a black or frozen frame between shots.
 */
public enum ShotTransition {
    CUT("Cut"),
    FADE("Fade"),
    /** Slides across at the old size, then settles to the new size. */
    SMOOTH_PAN("Smooth Pan"),
    /** Zooms out towards the wide shot and back in on the new framing. */
    SMOOTH_ZOOM("Smooth Zoom"),
    /** Moves and zooms at the same time, straight to the new framing. */
    PAN_ZOOM("Pan + Zoom");

    private final String label;

    ShotTransition(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /** Smooth start and end (ease in-out). */
    static double ease(double t) {
        t = Math.clamp(t, 0, 1);
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    /**
     * The camera position {@code t} (0..1) of the way from {@code a} to {@code b}. FADE and CUT do not move the
     * camera: they return {@code b} (the fade mixes two pictures instead).
     */
    public CropRect at(CropRect a, CropRect b, double t) {
        double e = ease(t);
        return switch (this) {
            case CUT, FADE -> b;
            case PAN_ZOOM -> a.lerp(b, e);
            case SMOOTH_PAN -> {
                // move at the old size for the first 70 %, then change size
                CropRect moved = new CropRect(b.cx(), b.cy(), a.zoom());
                yield t < 0.7 ? a.lerp(moved, ease(t / 0.7)) : moved.lerp(b, ease((t - 0.7) / 0.3));
            }
            case SMOOTH_ZOOM -> {
                // out to a wider view that holds both framings, then in
                double outW = Math.min(1.0, Math.max(a.box().right(), b.box().right()) - Math.min(a.box().x(), b.box().x()));
                double outH = Math.min(1.0, Math.max(a.box().bottom(), b.box().bottom()) - Math.min(a.box().y(), b.box().y()));
                double z = 1.0 / Math.max(outW, outH);
                CropRect mid = new CropRect((a.cx() + b.cx()) / 2, (a.cy() + b.cy()) / 2, z);
                yield t < 0.5 ? a.lerp(mid, ease(t / 0.5)) : mid.lerp(b, ease((t - 0.5) / 0.5));
            }
        };
    }
}
