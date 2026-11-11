package com.jamc.worship.director;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Chooses the next virtual shot for Full Auto and the recommendation for AI Assisted. Pure logic (no clock, no UI),
 * so it is unit tested.
 *
 * <p>Each shot gets a score: its priority × how well its performer can be shown now (found by the AI, framed well,
 * moving) × how recently it was used. The shot on Program is never chosen again, and after a few close shots, or
 * when the wide shot has not been seen for a while, the wide shot comes back. When the AI is unsure the shots of the
 * configured stage zones still rotate safely (they get a lower but non-zero availability).
 */
public final class ShotPlanner {

    /** How a shot can be shown right now. */
    public record Availability(double score, String why) {
        public static final Availability NONE = new Availability(0, "not available");
    }

    /** Settings the planner needs. */
    public record Rules(double minShotSeconds, double wideEverySeconds, int maxCloseShotsInRow) {
        public static Rules of(DirectorSettings s) {
            return new Rules(s.minShotSeconds(), s.wideEverySeconds(), 3);
        }
    }

    private ShotPlanner() {
    }

    /** Whether Full Auto should leave the current shot now. */
    public static boolean timeToCut(VirtualShot current, double secondsOnShot, double currentAvailability, Rules rules) {
        if (current == null) {
            return true;
        }
        if (secondsOnShot < rules.minShotSeconds()) {
            return false; // never cut faster than the minimum, whatever happens (avoids excessive switching)
        }
        if (currentAvailability <= 0.05) {
            return true; // its performer is gone
        }
        return secondsOnShot >= Math.max(rules.minShotSeconds(), current.holdSeconds);
    }

    /**
     * The best next shot, or null when nothing can be shown.
     *
     * @param history          ids of the shots that were on Program, oldest first (the current one last)
     * @param secondsSinceWide time since the wide shot was last on Program
     * @param availability     how well each shot can be shown now (0..1)
     */
    public static VirtualShot next(List<VirtualShot> shots, VirtualShot current, List<String> history,
                                   double secondsSinceWide, ToDoubleFunction<VirtualShot> availability, Rules rules) {
        VirtualShot wide = shots.stream().filter(VirtualShot::isWide).findFirst()
                .orElse(shots.stream().filter(s -> s.role == Role.WIDE).findFirst().orElse(null));
        boolean currentIsWide = current != null && current == wide;
        if (wide != null && !currentIsWide && wide.priority > 0) {
            if (secondsSinceWide >= rules.wideEverySeconds() || closeShotsInRow(history, wide) >= rules.maxCloseShotsInRow()) {
                return wide; // periodically back to the whole team
            }
        }
        VirtualShot best = null;
        double bestScore = 0;
        int start = current == null ? 0 : Math.max(0, shots.indexOf(current) + 1);
        for (int k = 0; k < shots.size(); k++) {
            VirtualShot s = shots.get((start + k) % shots.size()); // ties go to the next shot in the list (rotation)
            if (s == current || s.priority <= 0) {
                continue;
            }
            double a = Math.clamp(availability.applyAsDouble(s), 0, 1);
            if (a <= 0.05) {
                continue;
            }
            double score = (s.priority / 5.0) * a * recency(history, s.id);
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : current != wide ? wide : null;
    }

    /** 0.1 for the shot that was just on, rising for older ones, 1 for shots not used lately. */
    static double recency(List<String> history, String id) {
        for (int k = 1; k <= Math.min(4, history.size()); k++) {
            if (history.get(history.size() - k).equals(id)) {
                return switch (k) {
                    case 1 -> 0.10;
                    case 2 -> 0.35;
                    case 3 -> 0.60;
                    default -> 0.80;
                };
            }
        }
        return 1.0;
    }

    static int closeShotsInRow(List<String> history, VirtualShot wide) {
        int n = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).equals(wide.id)) {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * How well a shot can be shown with the people the AI sees.
     *
     * @param person the performer this shot would follow (null when nobody suitable is seen)
     * @param hasZone the shot's role has a configured stage zone (safe fallback framing)
     */
    public static Availability availability(VirtualShot s, PersonTracker.Person person, boolean hasZone) {
        if (s.role == Role.WIDE && !s.tracking) {
            return new Availability(0.9, "wide");
        }
        if (!s.tracking) {
            return new Availability(0.75, "fixed framing");
        }
        if (person == null || !person.visible()) {
            // the AI is not sure: the zone framing is a safe, lower-scored choice
            return hasZone ? new Availability(0.35, "zone (performer not detected)") : Availability.NONE;
        }
        double a = person.score();
        // framing quality: a person so big that the shot is nearly the wide shot is a poor close-up
        CropRect framed = CropRect.frame(person.box(), s.margin, s.zoom);
        if (framed.zoom() < 1.15 && s.zoom > 1.15) {
            a *= 0.5;
        }
        // a person at the very edge of the picture is half cut off
        if (person.box().x() < 0.01 || person.box().right() > 0.99) {
            a *= 0.7;
        }
        // visible movement (a singer stepping forward, a drummer playing) makes a shot a little more interesting
        a = Math.min(1.0, a + Math.min(0.2, person.motion() * 0.5));
        return new Availability(a, "tracked");
    }
}
