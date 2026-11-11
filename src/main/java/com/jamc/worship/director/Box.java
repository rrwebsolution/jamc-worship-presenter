package com.jamc.worship.director;

/**
 * A rectangle in normalized picture coordinates: 0..1 across the whole camera frame, so it does not depend on the
 * capture size (the AI looks at a small copy, Program draws from the full-size frame).
 */
public record Box(double x, double y, double w, double h) {

    public double cx() {
        return x + w / 2;
    }

    public double cy() {
        return y + h / 2;
    }

    public double right() {
        return x + w;
    }

    public double bottom() {
        return y + h;
    }

    public double area() {
        return Math.max(0, w) * Math.max(0, h);
    }

    public boolean contains(double px, double py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /** Intersection over union, 0 when they do not touch. */
    public double iou(Box o) {
        double ix = Math.max(0, Math.min(right(), o.right()) - Math.max(x, o.x));
        double iy = Math.max(0, Math.min(bottom(), o.bottom()) - Math.max(y, o.y));
        double inter = ix * iy;
        double union = area() + o.area() - inter;
        return union <= 0 ? 0 : inter / union;
    }

    /** How much of this box lies inside {@code o} (0..1). */
    public double overlapIn(Box o) {
        double ix = Math.max(0, Math.min(right(), o.right()) - Math.max(x, o.x));
        double iy = Math.max(0, Math.min(bottom(), o.bottom()) - Math.max(y, o.y));
        return area() <= 0 ? 0 : ix * iy / area();
    }

    /** Linear mix: {@code t} = 0 this box, 1 the other one. */
    public Box lerp(Box o, double t) {
        return new Box(x + (o.x - x) * t, y + (o.y - y) * t, w + (o.w - w) * t, h + (o.h - h) * t);
    }

    /** Kept inside the picture. */
    public Box clamp() {
        double nw = Math.clamp(w, 0, 1);
        double nh = Math.clamp(h, 0, 1);
        return new Box(Math.clamp(x, 0, 1 - nw), Math.clamp(y, 0, 1 - nh), nw, nh);
    }

    public static Box ofCorners(double x1, double y1, double x2, double y2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
    }
}
