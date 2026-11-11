package com.jamc.worship.director;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One digital camera made from the single physical camera: a cut-out of its picture, fixed or following a
 * performer. Saved as JSON (Settings), so the fields are plain.
 */
public final class VirtualShot {

    public String id = UUID.randomUUID().toString();
    public String name = "Shot";
    /** The physical camera; null = the Auto Director's camera. */
    public String camera;
    public Role role = Role.WIDE;
    /** A person picked by the operator (tracking ID); null = whoever is in the role's zone. */
    public Integer trackId;
    /** Framing when not tracking (and the size to use when tracking). */
    public double cx = 0.5;
    public double cy = 0.5;
    public double zoom = 1.0;
    /** Follow the performer (needs the AI); off = a fixed cut-out. */
    public boolean tracking;
    /** Space around the person, as a part of their height. */
    public double margin = 0.15;
    /** Transition into this shot; null = the Auto Director's default. */
    public ShotTransition transition;
    /** 0 = never chosen by Full Auto, 1..5 = how often it is chosen. */
    public int priority = 3;
    /** Full Auto: how long this shot stays on Program (seconds). */
    public double holdSeconds = 8;

    public VirtualShot() {
    }

    public static VirtualShot of(String name, Role role, double zoom, boolean tracking, int priority, double hold) {
        VirtualShot s = new VirtualShot();
        s.name = name;
        s.role = role;
        s.zoom = zoom;
        s.tracking = tracking;
        s.priority = priority;
        s.holdSeconds = hold;
        return s;
    }

    @JsonIgnore
    public CropRect fixedCrop() {
        return new CropRect(cx, cy, zoom);
    }

    public void setFixedCrop(CropRect c) {
        cx = c.cx();
        cy = c.cy();
        zoom = c.zoom();
    }

    @JsonIgnore
    public boolean isWide() {
        return role == Role.WIDE && !tracking && zoom <= 1.0001;
    }

    public VirtualShot copy() {
        VirtualShot s = new VirtualShot();
        s.id = id;
        s.name = name;
        s.camera = camera;
        s.role = role;
        s.trackId = trackId;
        s.cx = cx;
        s.cy = cy;
        s.zoom = zoom;
        s.tracking = tracking;
        s.margin = margin;
        s.transition = transition;
        s.priority = priority;
        s.holdSeconds = holdSeconds;
        return s;
    }

    /** A duplicate with its own id. */
    public VirtualShot duplicate() {
        VirtualShot s = copy();
        s.id = UUID.randomUUID().toString();
        s.name = name + " copy";
        return s;
    }

    /**
     * The five default shots (keys 1–5). The example Full Auto timings: wide 8 s, lead singer 12 s, keyboard 6 s,
     * drums 5 s. Fixed shots start on the default stage zones.
     */
    public static List<VirtualShot> defaults() {
        List<VirtualShot> out = new ArrayList<>();
        out.add(of("Wide Shot", Role.WIDE, 1.0, false, 3, 8));
        out.add(of("Lead Singer", Role.LEAD_SINGER, 1.5, true, 5, 12));
        out.add(of("Keyboardist", Role.KEYBOARD, 1.5, true, 3, 6));
        out.add(of("Drummer", Role.DRUMS, 1.5, true, 3, 5));
        out.add(of("Other Worship Member", Role.BACKUP_SINGER, 1.5, true, 2, 6));
        for (VirtualShot s : out) {
            for (StageZone z : StageZone.defaults()) {
                if (z.role() == s.role) {
                    // only the place comes from the zone: the shot keeps its own size (1.5× medium)
                    CropRect c = CropRect.frame(z.box(), 0.05, s.zoom);
                    s.cx = c.cx();
                    s.cy = c.cy();
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return name;
    }
}
