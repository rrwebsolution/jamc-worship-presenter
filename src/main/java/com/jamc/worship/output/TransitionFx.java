package com.jamc.worship.output;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;

/**
 * How each {@link Transition} draws the two sides of a change at progress {@code p} (0 → 1): the outgoing frame
 * (or layer) and the incoming one. {@link #prepare} sets up the Graphics for one side (clip for wipes and the
 * circle, a shift for slides, a scale for zoom) and returns the opacity to draw it with.
 *
 * <p>Wipes and the circle cut the OLD side away where the new one is revealed, so two transparent layers (old
 * and new lyrics) never show on top of each other.
 */
public final class TransitionFx {

    private TransitionFx() {
    }

    /**
     * @param opaque true when the sides are whole opaque frames drawn over each other (the old frame then stays
     *               at full opacity under a crossfade); false for transparent layers, which both fade
     * @return the opacity for this side; 0 = do not draw it
     */
    public static float prepare(Transition t, Graphics2D g, int w, int h, float p, boolean incoming, boolean opaque) {
        p = Math.clamp(p, 0f, 1f);
        switch (t == null ? Transition.CUT : t) {
            case CUT -> {
                return incoming ? 1f : 0f;
            }
            case FADE -> {
                return incoming ? (p < 0.5f ? 0f : p * 2f - 1f) : (p < 0.5f ? 1f - p * 2f : 0f);
            }
            case CROSSFADE -> {
                return incoming ? p : (opaque ? 1f : 1f - p);
            }
            case WIPE_LEFT, WIPE_RIGHT, WIPE_UP, WIPE_DOWN, CIRCLE -> {
                Shape in = revealed(t, w, h, p);
                if (incoming) {
                    g.clip(in);
                } else {
                    Area out = new Area(new Rectangle(0, 0, w, h));
                    out.subtract(new Area(in));
                    g.clip(out);
                }
                return 1f;
            }
            case SLIDE_LEFT -> g.translate(incoming ? Math.round(w * (1 - p)) : -Math.round(w * p), 0);
            case SLIDE_RIGHT -> g.translate(incoming ? -Math.round(w * (1 - p)) : Math.round(w * p), 0);
            case SLIDE_UP -> g.translate(0, incoming ? Math.round(h * (1 - p)) : -Math.round(h * p));
            case SLIDE_DOWN -> g.translate(0, incoming ? -Math.round(h * (1 - p)) : Math.round(h * p));
            case ZOOM -> {
                // the new frame grows from 85 % to full size while it fades in; the old one swells a little and fades
                double s = incoming ? 0.85 + 0.15 * p : 1 + 0.08 * p;
                g.translate(w / 2.0, h / 2.0);
                g.scale(s, s);
                g.translate(-w / 2.0, -h / 2.0);
                return incoming ? p : (opaque ? 1f : 1f - p);
            }
        }
        return 1f; // slides: both sides fully drawn, side by side
    }

    /** Where the incoming side shows for a wipe or the circle. */
    static Shape revealed(Transition t, int w, int h, float p) {
        return switch (t) {
            case WIPE_LEFT -> new Rectangle(Math.round(w * (1 - p)), 0, w - Math.round(w * (1 - p)), h);
            case WIPE_RIGHT -> new Rectangle(0, 0, Math.round(w * p), h);
            case WIPE_UP -> new Rectangle(0, Math.round(h * (1 - p)), w, h - Math.round(h * (1 - p)));
            case WIPE_DOWN -> new Rectangle(0, 0, w, Math.round(h * p));
            case CIRCLE -> {
                double r = Math.hypot(w, h) / 2.0 * p; // from a dot to beyond the corners
                yield new Ellipse2D.Double(w / 2.0 - r, h / 2.0 - r, r * 2, r * 2);
            }
            default -> new Rectangle(0, 0, w, h);
        };
    }
}
