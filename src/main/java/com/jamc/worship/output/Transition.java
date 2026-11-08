package com.jamc.worship.output;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** How Program changes from one frame to the next (see {@link TransitionFx} for how each one is drawn). */
public enum Transition {
    /** Instant switch. */
    CUT("Cut"),
    /** Old frame fades to black, then the new frame fades in. */
    FADE("Fade"),
    /** Old frame dissolves directly into the new one. */
    CROSSFADE("Crossfade"),
    /** The new frame is revealed behind an edge moving to the left (it appears from the right). */
    WIPE_LEFT("Wipe Left"),
    WIPE_RIGHT("Wipe Right"),
    WIPE_UP("Wipe Up"),
    WIPE_DOWN("Wipe Down"),
    /** The new frame pushes the old one out to the left. */
    SLIDE_LEFT("Slide Left"),
    SLIDE_RIGHT("Slide Right"),
    SLIDE_UP("Slide Up"),
    SLIDE_DOWN("Slide Down"),
    /** The new frame grows from the middle while it fades in. */
    ZOOM("Zoom"),
    /** The new frame opens in a circle from the middle. */
    CIRCLE("Circle");

    private final String label;

    Transition(String label) {
        this.label = label;
    }

    public static Transition parse(String value, Transition fallback) {
        try {
            return value == null ? fallback : valueOf(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Shown next to TAKE when the operator has not chosen yet: the classic three. */
    public static final Set<Transition> DEFAULT_SHOWN = EnumSet.of(CUT, FADE, CROSSFADE);

    /** "CUT,FADE,WIPE_LEFT" → those transitions (unknown names skipped; none = {@link #DEFAULT_SHOWN}). */
    public static Set<Transition> parseSet(String value) {
        Set<Transition> out = EnumSet.noneOf(Transition.class);
        if (value != null) {
            for (String part : value.split(",")) {
                Transition t = parse(part.strip(), null);
                if (t != null) {
                    out.add(t);
                }
            }
        }
        return out.isEmpty() ? EnumSet.copyOf(DEFAULT_SHOWN) : out;
    }

    public static String format(Set<Transition> set) {
        List<String> names = new ArrayList<>();
        for (Transition t : values()) {
            if (set.contains(t)) {
                names.add(t.name());
            }
        }
        return String.join(",", names);
    }

    @Override
    public String toString() {
        return label;
    }
}
