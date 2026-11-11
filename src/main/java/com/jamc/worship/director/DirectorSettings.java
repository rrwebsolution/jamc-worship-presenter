package com.jamc.worship.director;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.jamc.worship.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Auto Director's saved settings ({@code director.*} in settings.properties). Shots are saved per production
 * profile, stage zones per profile and camera. The operating mode is NOT saved: the app always starts in Manual, so
 * the AI never takes over Program by itself after a restart.
 */
public final class DirectorSettings {

    private static final Logger log = LoggerFactory.getLogger(DirectorSettings.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final String DEFAULT_PROFILE = "Default";

    private final AppConfig config;

    public DirectorSettings(AppConfig config) {
        this.config = config;
    }

    // ---- simple values

    public boolean enabled() {
        return config.getBoolean("director.enabled", false);
    }

    public void setEnabled(boolean on) {
        put("director.enabled", String.valueOf(on));
    }

    /** The physical camera the virtual shots come from; null = pick (an Iriun camera first). */
    public String camera() {
        String v = config.get("director.camera", null);
        return v == null || v.isBlank() ? null : v;
    }

    public void setCamera(String cam) {
        put("director.camera", cam);
    }

    /**
     * Camera setup: false = one camera (every shot is a cut-out of the main camera), true = multiple cameras (each
     * shot may come from its own camera).
     */
    public boolean multiCamera() {
        return config.getBoolean("director.multiCamera", false);
    }

    public void setMultiCamera(boolean on) {
        put("director.multiCamera", String.valueOf(on));
    }

    /**
     * The cameras are opened and the AI looks only while the Auto Director page is open (no extra load elsewhere).
     * When this is on, Full Auto and AI Assisted keep watching on the other pages too (they need to see).
     */
    public boolean aiOnOtherPages() {
        return config.getBoolean("director.aiOnOtherPages", true);
    }

    public void setAiOnOtherPages(boolean on) {
        put("director.aiOnOtherPages", String.valueOf(on));
    }

    /** Analysed frames per second (the video itself is not affected). */
    public double analysisFps() {
        return num("director.fps", 5, 0.5, 15);
    }

    public void setAnalysisFps(double v) {
        put("director.fps", String.valueOf(v));
    }

    /** CPU cores the AI may use. */
    public int threads() {
        return (int) num("director.threads", 2, 1, 8);
    }

    public double confidence() {
        return num("director.confidence", 0.45, 0.05, 0.95);
    }

    public void setConfidence(double v) {
        put("director.confidence", String.valueOf(v));
    }

    /** Tracking sensitivity: 0.05 = very smooth and slow, 1 = follows every movement. */
    public double responsiveness() {
        return num("director.responsiveness", 0.35, 0.05, 1);
    }

    public void setResponsiveness(double v) {
        put("director.responsiveness", String.valueOf(v));
    }

    public ShotTransition transition() {
        try {
            return ShotTransition.valueOf(config.get("director.transition", ShotTransition.PAN_ZOOM.name()));
        } catch (IllegalArgumentException e) {
            return ShotTransition.PAN_ZOOM;
        }
    }

    public void setTransition(ShotTransition t) {
        put("director.transition", t.name());
    }

    public int transitionMs() {
        return (int) num("director.transitionMs", 900, 0, 5000);
    }

    public void setTransitionMs(int ms) {
        put("director.transitionMs", String.valueOf(ms));
    }

    /** Full Auto never cuts sooner than this (seconds). */
    public double minShotSeconds() {
        return num("director.minShotSec", 4, 1, 60);
    }

    public void setMinShotSeconds(double v) {
        put("director.minShotSec", String.valueOf(v));
    }

    /** Full Auto goes back to the wide shot at least this often (seconds). */
    public double wideEverySeconds() {
        return num("director.wideEverySec", 40, 10, 600);
    }

    public void setWideEverySeconds(double v) {
        put("director.wideEverySec", String.valueOf(v));
    }

    /** How fast a following shot catches up with the performer: 0.1 slow .. 1 fast. */
    public double followSpeed() {
        return num("director.followSpeed", 0.5, 0.05, 1);
    }

    public void setFollowSpeed(double v) {
        put("director.followSpeed", String.valueOf(v));
    }

    /** Highest zoom the operator may set; above {@link CropRect#QUALITY_ZOOM} only with "allow higher zoom". */
    public double maxZoom() {
        return allowHighZoom() ? CropRect.MAX_ZOOM : CropRect.QUALITY_ZOOM;
    }

    public boolean allowHighZoom() {
        return config.getBoolean("director.allowHighZoom", false);
    }

    public void setAllowHighZoom(boolean on) {
        put("director.allowHighZoom", String.valueOf(on));
    }

    /** How long a shot holds a performer who is not seen any more (ms). */
    public long holdLostMs() {
        return (long) num("director.holdLostMs", 2500, 300, 15000);
    }

    // ---- production profiles

    public String profile() {
        return config.get("director.profile", DEFAULT_PROFILE);
    }

    public void setProfile(String name) {
        Set<String> all = new LinkedHashSet<>(profiles());
        all.add(name);
        config.set("director.profiles", String.join("|", all));
        put("director.profile", name);
    }

    public List<String> profiles() {
        Set<String> out = new LinkedHashSet<>();
        out.add(DEFAULT_PROFILE);
        for (String p : config.get("director.profiles", "").split("\\|")) {
            if (!p.isBlank()) {
                out.add(p.trim());
            }
        }
        out.add(profile());
        return new ArrayList<>(out);
    }

    // ---- shots and zones

    public List<VirtualShot> shots() {
        String json = config.get(shotsKey(), null);
        if (json != null) {
            try {
                JavaType t = JSON.getTypeFactory().constructCollectionType(List.class, VirtualShot.class);
                List<VirtualShot> list = JSON.readValue(json, t);
                if (list != null && !list.isEmpty()) {
                    return new ArrayList<>(list);
                }
            } catch (Exception e) {
                log.warn("Auto Director shots could not be read; using the defaults", e);
            }
        }
        return VirtualShot.defaults();
    }

    public void saveShots(List<VirtualShot> shots) {
        try {
            put(shotsKey(), JSON.writeValueAsString(shots));
        } catch (Exception e) {
            log.warn("Could not save the Auto Director shots", e);
        }
    }

    public void restoreDefaultShots() {
        put(shotsKey(), null);
    }

    public List<StageZone> zones(String camera) {
        String json = config.get(zonesKey(camera), null);
        if (json != null) {
            try {
                JavaType t = JSON.getTypeFactory().constructCollectionType(List.class, StageZone.class);
                List<StageZone> list = JSON.readValue(json, t);
                if (list != null) {
                    return List.copyOf(list);
                }
            } catch (Exception e) {
                log.warn("Auto Director zones could not be read; using the defaults", e);
            }
        }
        return StageZone.defaults();
    }

    public void saveZones(String camera, List<StageZone> zones) {
        try {
            put(zonesKey(camera), JSON.writeValueAsString(zones));
        } catch (Exception e) {
            log.warn("Could not save the Auto Director zones", e);
        }
    }

    private String shotsKey() {
        return "director." + slug(profile()) + ".shots";
    }

    private String zonesKey(String camera) {
        return "director." + slug(profile()) + ".zones." + slug(camera == null ? "camera" : camera);
    }

    static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private double num(String key, double def, double min, double max) {
        try {
            return Math.clamp(Double.parseDouble(config.get(key, String.valueOf(def))), min, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void put(String key, String value) {
        config.set(key, value);
        config.save();
    }
}
