package com.jamc.worship.director;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Follows the people found in each analysed frame and gives each one a stable number (tracking ID).
 *
 * <ul>
 *     <li>Each new detection is matched to the closest existing person (box overlap, distance and clothing colour),
 *     never to someone far away, so the shot does not jump to another performer.</li>
 *     <li>Boxes are smoothed so the digital camera does not shake.</li>
 *     <li>A person who is not found for a moment (turned away, hidden by a mic stand) is held at their last place
 *     for {@link #holdMs}; their shot stays still meanwhile.</li>
 *     <li>Someone who comes back after that, at the same place or in the same zone with the same colours, gets their
 *     old number (and name) back instead of a new one.</li>
 *     <li>Each person gets the role of the stage zone they are in, unless the operator named them.</li>
 * </ul>
 *
 * <p>{@link #update} runs on the analysis thread; {@link #snapshot()} may be read from any thread.
 */
public final class PersonTracker {

    /** A tracked person as the rest of the app sees them. */
    public record Person(int id, Box box, float score, Role role, String name, boolean visible, long lostMs,
                         boolean manualRole, double motion) {
        public String label() {
            return (name != null && !name.isBlank() ? name : role != null ? role.label() : "Person") + " #" + id;
        }
    }

    /** The operator's own naming of a person (kept while the person is tracked and on re-association). */
    public record Assignment(String name, Role role) {
    }

    private static final int BINS = 8;
    private static final long REMEMBER_MS = 60_000;

    private final List<Track> tracks = new ArrayList<>();
    private final List<Track> gone = new ArrayList<>();
    private final Map<Integer, Assignment> assignments = new ConcurrentHashMap<>();
    private volatile List<Person> snapshot = List.of();
    private volatile List<StageZone> zones = StageZone.defaults();
    private int nextId = 1;

    /** How long a missing person is held at their last place (ms). */
    private volatile long holdMs = 2_500;
    /** 0 = very smooth (slow), 1 = no smoothing. */
    private volatile double responsiveness = 0.35;
    /** Detections below this are ignored. */
    private volatile double minConfidence = 0.45;

    private static final class Track {
        int id;
        Box box;
        float score;
        int hits;
        long lastSeen;
        float[] colours;
        Role zoneRole;
        /** How much the person moves: picture widths per second, smoothed. */
        double motion;
    }

    public void setZones(List<StageZone> z) {
        zones = List.copyOf(z);
    }

    public List<StageZone> zones() {
        return zones;
    }

    public void setHoldMs(long ms) {
        holdMs = Math.max(0, ms);
    }

    public void setResponsiveness(double r) {
        responsiveness = Math.clamp(r, 0.05, 1.0);
    }

    public void setMinConfidence(double c) {
        minConfidence = Math.clamp(c, 0.05, 0.95);
    }

    public double minConfidence() {
        return minConfidence;
    }

    /** The operator names a person and/or fixes their role (null role = follow the zone). */
    public void assign(int id, String name, Role role) {
        if ((name == null || name.isBlank()) && role == null) {
            assignments.remove(id);
        } else {
            assignments.put(id, new Assignment(name, role));
        }
        synchronized (this) {
            snapshot = build(System.currentTimeMillis());
        }
    }

    public Assignment assignment(int id) {
        return assignments.get(id);
    }

    public List<Person> snapshot() {
        return snapshot;
    }

    /** Forget everyone (camera changed). Names given by the operator are forgotten too. */
    public synchronized void reset() {
        tracks.clear();
        gone.clear();
        assignments.clear();
        snapshot = List.of();
    }

    /**
     * One analysed frame.
     *
     * @param found the detections in this frame
     * @param image the analysis picture they were found in (for clothing colours), or null
     * @param scale how the analysis picture maps to the frame ({@link PersonDetector.Prepared#scale()})
     */
    public synchronized List<Person> update(List<PersonDetector.Detection> found, BufferedImage image, double scale,
                                            int frameW, int frameH, long now) {
        List<PersonDetector.Detection> dets = found.stream().filter(d -> d.score() >= minConfidence).toList();
        float[][] colours = new float[dets.size()][];
        for (int i = 0; i < dets.size(); i++) {
            colours[i] = colours(image, dets.get(i).box(), scale, frameW, frameH);
        }
        // greedy matching, best pairs first; far-away pairs are never matched
        record Pair(int t, int d, double cost) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int t = 0; t < tracks.size(); t++) {
            for (int d = 0; d < dets.size(); d++) {
                double cost = cost(tracks.get(t).box, tracks.get(t).colours, dets.get(d).box(), colours[d]);
                if (cost < 1.0) {
                    pairs.add(new Pair(t, d, cost));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::cost));
        boolean[] usedT = new boolean[tracks.size()];
        boolean[] usedD = new boolean[dets.size()];
        double a = responsiveness;
        for (Pair p : pairs) {
            if (usedT[p.t] || usedD[p.d]) {
                continue;
            }
            usedT[p.t] = usedD[p.d] = true;
            Track tr = tracks.get(p.t);
            PersonDetector.Detection d = dets.get(p.d);
            double dt = Math.max(0.05, (now - tr.lastSeen) / 1000.0);
            double moved = Math.hypot(d.box().cx() - tr.box.cx(), d.box().cy() - tr.box.cy()) / dt;
            tr.motion = tr.motion * 0.7 + moved * 0.3;
            tr.box = tr.box.lerp(d.box(), a);
            tr.score = tr.score * 0.6f + d.score() * 0.4f;
            tr.hits++;
            tr.lastSeen = now;
            tr.colours = blend(tr.colours, colours[p.d]);
        }
        for (int d = 0; d < dets.size(); d++) {
            if (!usedD[d]) {
                tracks.add(newTrack(dets.get(d), colours[d], now));
            }
        }
        for (Iterator<Track> it = tracks.iterator(); it.hasNext(); ) {
            Track tr = it.next();
            boolean tentative = tr.hits < 2 && now - tr.lastSeen > 600;
            if (tentative || now - tr.lastSeen > holdMs) {
                it.remove();
                if (!tentative) {
                    gone.add(tr);
                }
            }
        }
        gone.removeIf(t -> now - t.lastSeen > REMEMBER_MS);
        for (Track tr : tracks) {
            tr.zoneRole = zoneOf(tr.box);
        }
        snapshot = build(now);
        return snapshot;
    }

    private Track newTrack(PersonDetector.Detection d, float[] colours, long now) {
        Track tr = new Track();
        tr.box = d.box();
        tr.score = d.score();
        tr.hits = 1;
        tr.lastSeen = now;
        tr.colours = colours;
        // safe re-association: the same place (or the same zone) and similar colours as someone recently lost
        Role zone = zoneOf(d.box());
        Track best = null;
        double bestCost = 0.85;
        for (Track g : gone) {
            double dist = Math.hypot(g.box.cx() - d.box().cx(), g.box.cy() - d.box().cy());
            boolean sameZone = zone != null && zone == g.zoneRole;
            if (dist > 0.12 && !sameZone) {
                continue;
            }
            double cost = Math.min(1, dist / 0.25) * 0.5 + (1 - similarity(g.colours, colours)) * 0.5;
            if (cost < bestCost) {
                bestCost = cost;
                best = g;
            }
        }
        if (best != null) {
            gone.remove(best);
            tr.id = best.id;
            tr.hits = 2; // known person: confirmed at once
        } else {
            tr.id = nextId++;
        }
        tr.zoneRole = zone;
        return tr;
    }

    /** 0 = same person for sure, ≥ 1 = not the same person. */
    static double cost(Box prev, float[] prevColours, Box now, float[] nowColours) {
        double iou = prev.iou(now);
        double dist = Math.hypot(prev.cx() - now.cx(), prev.cy() - now.cy());
        double size = Math.max(prev.w(), prev.h());
        double near = dist / Math.max(0.02, size); // in person sizes
        if (iou < 0.05 && near > 0.9) {
            return 1.5; // too far for the time between two analysed frames
        }
        double sim = similarity(prevColours, nowColours);
        return (1 - iou) * 0.55 + Math.min(1, near) * 0.25 + (1 - sim) * 0.20;
    }

    Role zoneOf(Box b) {
        Role best = null;
        double bestArea = 0;
        double cx = b.cx();
        double cy = b.y() + b.h() * 0.35; // the upper body decides (a drummer's legs reach the next zone)
        for (StageZone z : zones) {
            if (z.box().contains(cx, cy)) {
                double overlap = b.overlapIn(z.box());
                if (best == null || overlap > bestArea) {
                    best = z.role();
                    bestArea = overlap;
                }
            }
        }
        return best;
    }

    private List<Person> build(long now) {
        List<Person> out = new ArrayList<>();
        for (Track t : tracks) {
            if (t.hits < 2) {
                continue; // seen once: could be a false detection
            }
            Assignment as = assignments.get(t.id);
            Role role = as != null && as.role() != null ? as.role() : t.zoneRole;
            long lost = Math.max(0, now - t.lastSeen);
            out.add(new Person(t.id, t.box, t.score, role, as == null ? null : as.name(), lost < 400, lost,
                    as != null && as.role() != null, t.motion));
        }
        out.sort(Comparator.comparingDouble(p -> p.box().cx()));
        return List.copyOf(out);
    }

    // ---- clothing colours (upper body): a small RGB histogram from the analysis picture

    static float[] colours(BufferedImage img, Box b, double scale, int frameW, int frameH) {
        if (img == null || frameW <= 0) {
            return null;
        }
        int x0 = (int) Math.round(b.x() * frameW * scale);
        int y0 = (int) Math.round((b.y() + b.h() * 0.2) * frameH * scale);
        int x1 = (int) Math.round(b.right() * frameW * scale);
        int y1 = (int) Math.round((b.y() + b.h() * 0.6) * frameH * scale);
        x0 = Math.clamp(x0, 0, img.getWidth() - 1);
        x1 = Math.clamp(x1, x0 + 1, img.getWidth());
        y0 = Math.clamp(y0, 0, img.getHeight() - 1);
        y1 = Math.clamp(y1, y0 + 1, img.getHeight());
        float[] h = new float[BINS * 3];
        int step = Math.max(1, Math.min(x1 - x0, y1 - y0) / 16);
        int n = 0;
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int rgb = img.getRGB(x, y);
                h[((rgb >> 16) & 0xFF) * BINS / 256]++;
                h[BINS + ((rgb >> 8) & 0xFF) * BINS / 256]++;
                h[2 * BINS + (rgb & 0xFF) * BINS / 256]++;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        for (int i = 0; i < h.length; i++) {
            h[i] /= n;
        }
        return h;
    }

    /** Histogram intersection, 0..1 (0.5 when either side is unknown). */
    static double similarity(float[] a, float[] b) {
        if (a == null || b == null) {
            return 0.5;
        }
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += Math.min(a[i], b[i]);
        }
        return s / 3.0;
    }

    private static float[] blend(float[] old, float[] now) {
        if (old == null) {
            return now;
        }
        if (now == null) {
            return old;
        }
        float[] out = new float[old.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = old[i] * 0.8f + now[i] * 0.2f;
        }
        return out;
    }
}
