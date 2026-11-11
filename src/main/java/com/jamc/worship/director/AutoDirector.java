package com.jamc.worship.director;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The AI Smart Auto Director: one physical camera becomes several virtual shots (digital cut-outs), chosen by the
 * operator, recommended by the AI (AI Assisted) or switched by the AI (Full Auto).
 *
 * <h2>Control priority</h2>
 * <ol>
 *     <li>Safety: Pause AI (Esc), errors and lost tracking stop automatic switching.</li>
 *     <li>The operator: every manual action raises {@link #controlVersion()}. An AI result computed before that is
 *     rejected ({@link Status#staleRejected()}), so a slow analysis can never undo a newer manual choice.</li>
 *     <li>AI Assisted recommendations: only ever in Preview, never on Program. A Preview chosen by the operator is
 *     held until TAKE, Cancel or Next/Resume.</li>
 *     <li>Full Auto decisions: only while not paused. A manual TAKE of a shot pauses Full Auto ("AI Paused — Manual
 *     Control"). It is never resumed by a timer, only by Resume AI.</li>
 * </ol>
 *
 * <h2>Preview and Program</h2>
 * Both surfaces show the same camera frames (one capture session, the Iriun audio untouched) with their own shot,
 * framing, manual adjustment and transition. Adjusting Preview never moves Program.
 *
 * <p>Threading: state changes happen on the Swing thread; the compositor may ask for framing from any thread
 * (synchronized). The AI runs on its own low-priority thread, one frame at a time; frames arriving while it is busy
 * are skipped, never queued, so video and audio keep priority.
 */
public final class AutoDirector implements VirtualCameras.Source {

    private static final Logger log = LoggerFactory.getLogger(AutoDirector.class);

    public enum Mode {
        MANUAL("Manual"), ASSISTED("AI Assisted"), AUTO("Full Auto");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Who put the Program shot there. */
    public enum Owner { OPERATOR, AI }

    /** Everything the UI shows, read in one go. */
    public record Status(boolean enabled, Mode mode, boolean paused, String pauseReason, Owner programOwner,
                         VirtualShot previewShot, VirtualShot programShot, boolean previewPending, boolean previewByAi,
                         CropRect previewCrop, CropRect programCrop, boolean previewManual, boolean programManual,
                         PersonTracker.Person previewPerson, PersonTracker.Person programPerson, boolean programLost,
                         String aiState, boolean aiReady, double analysisMs, long analysed, long skipped,
                         long staleRejected, String camera, String recommendationWhy,
                         String previewCamera, String programCamera) {
    }

    private static final class Surface {
        VirtualShot shot;
        CropRect manual;
        CropRect shown = CropRect.WIDE;
        CropRect held;
        Integer personId;
        long lostSince;
        ShotTransition tr = ShotTransition.CUT;
        CropRect trFrom = CropRect.WIDE;
        /** The physical camera the shot shows, and the one it came from during a fade. */
        String camera;
        String trFromCamera;
        long trStart;
        int trMs;
        boolean snap = true;
        long lastStep;
        boolean lost;
    }

    private final DirectorSettings settings;
    /** One tracker per physical camera (each has its own people, ids and stage zones). */
    private final java.util.Map<String, PersonTracker> trackers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final PersonTracker NO_CAMERA = new PersonTracker();
    private final LongSupplier clock;
    private final Consumer<Runnable> toUi;
    private final ExecutorService worker;
    private final Supplier<PersonDetector> detectorFactory;
    private final Supplier<List<String>> cameraList;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private final Surface preview = new Surface();
    private final Surface program = new Surface();
    private List<VirtualShot> shots;
    private boolean enabled;
    private Mode mode = Mode.MANUAL;
    private boolean paused;
    private String pauseReason;
    private Owner programOwner = Owner.OPERATOR;
    private boolean previewPending;
    private boolean previewByAi;
    private String recommendationWhy;
    private final Set<String> rejected = new HashSet<>();
    private final List<String> history = new ArrayList<>();
    private long programSince;
    private long lastWideAt;
    private long version;
    private long staleRejected;
    private boolean adjustProgram;

    // AI
    private volatile PersonDetector detector;
    private volatile String aiState = "Off";
    private volatile String aiError;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile PersonDetector.Prepared reuse;
    private volatile double analysisMs;
    private volatile long analysed;
    private volatile long skipped;
    private String resolvedCamera;

    /**
     * @param detectorFactory loads the AI model (called once, on the AI thread); may return {@link PersonDetector#NONE}
     * @param toUi            runs a task on the Swing thread ({@code SwingUtilities::invokeLater})
     * @param cameraList      the cameras present now (to resolve "first camera")
     */
    public AutoDirector(DirectorSettings settings, Supplier<PersonDetector> detectorFactory, Consumer<Runnable> toUi,
                        LongSupplier clock, Supplier<List<String>> cameraList, ExecutorService worker) {
        this.settings = settings;
        this.detectorFactory = detectorFactory;
        this.toUi = toUi;
        this.clock = clock;
        this.cameraList = cameraList;
        this.worker = worker;
        this.shots = settings.shots();
        // always OFF when LiveCat opens (user choice): the operator turns it on; Program shows the camera as usual until then
        this.enabled = false;
        applyTrackerSettings();
        program.shot = wideShot();
        preview.shot = program.shot;
    }

    /** The app's director: the AI thread is a low-priority daemon. */
    public static AutoDirector create(DirectorSettings settings, Supplier<List<String>> cameraList) {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "auto-director-ai");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        return new AutoDirector(settings, () -> {
            try {
                return YoloxPersonDetector.load(settings.threads());
            } catch (Throwable e) {
                log.warn("The Auto Director AI model could not be loaded; zones only", e);
                return null;
            }
        }, javax.swing.SwingUtilities::invokeLater, System::currentTimeMillis, cameraList, worker);
    }

    // ------------------------------------------------------------------ listeners and settings

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public void removeListener(Runnable l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Runnable l : listeners) {
            l.run();
        }
    }

    public DirectorSettings settings() {
        return settings;
    }

    /** The main camera's tracker. */
    public PersonTracker tracker() {
        return tracker(camera());
    }

    /** The tracker of one camera (made on first use, with that camera's saved stage zones). */
    public PersonTracker tracker(String cam) {
        if (cam == null) {
            return NO_CAMERA;
        }
        return trackers.computeIfAbsent(cam, c -> {
            PersonTracker t = new PersonTracker();
            t.setZones(settings.zones(c));
            configure(t);
            return t;
        });
    }

    private void configure(PersonTracker t) {
        t.setMinConfidence(settings.confidence());
        t.setResponsiveness(settings.responsiveness());
        t.setHoldMs(settings.holdLostMs());
    }

    /** Re-reads tracking settings after the operator changed them. */
    public void applyTrackerSettings() {
        trackers.values().forEach(this::configure);
    }

    /** The camera a shot shows: its own, or the main camera. */
    public String cameraOf(VirtualShot s) {
        // one-camera setup: every shot is a cut-out of the main camera (a shot's own camera is kept for later)
        return settings.multiCamera() && s != null && s.camera != null && !s.camera.isBlank() ? s.camera : camera();
    }

    /** One camera or multiple cameras (Camera setup). */
    public void setMultiCamera(boolean on) {
        synchronized (this) {
            settings.setMultiCamera(on);
            version++;
        }
        changed();
    }

    public boolean multiCamera() {
        return settings.multiCamera();
    }

    /** Multiple cameras: which camera a shot comes from (null = the main camera). Saved with the shots. */
    public void setShotCamera(VirtualShot s, String cam) {
        synchronized (this) {
            s.camera = cam == null || cam.equals(camera()) ? null : cam;
            settings.saveShots(shots);
            version++;
        }
        changed();
    }

    /**
     * The cameras the shots use (the main camera first). Each is kept open and analysed, so a shot of another camera
     * is ready at once.
     */
    public synchronized List<String> usedCameras() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (camera() != null) {
            out.add(camera());
        }
        for (VirtualShot s : shots) {
            if (cameraOf(s) != null) {
                out.add(cameraOf(s));
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<VirtualShot> shots() {
        return List.copyOf(shots);
    }

    /** The shot list was edited (add, rename, delete, reorder…): saved, and Preview / Program keep their shots. */
    public void setShots(List<VirtualShot> list) {
        synchronized (this) {
            shots = new ArrayList<>(list);
            settings.saveShots(shots);
            preview.shot = find(preview.shot);
            program.shot = find(program.shot);
        }
        changed();
    }

    public void restoreDefaultShots() {
        settings.restoreDefaultShots();
        setShots(settings.shots());
    }

    private VirtualShot find(VirtualShot s) {
        if (s == null) {
            return wideShot();
        }
        for (VirtualShot x : shots) {
            if (x.id.equals(s.id)) {
                return x;
            }
        }
        return s.id.startsWith("person:") ? s : wideShot();
    }

    private VirtualShot wideShot() {
        return shots.stream().filter(VirtualShot::isWide).findFirst()
                .orElse(shots.stream().filter(s -> s.role == Role.WIDE).findFirst()
                        .orElse(VirtualShot.of("Wide Shot", Role.WIDE, 1, false, 3, 8)));
    }

    public void setZones(List<StageZone> zones) {
        setZones(camera(), zones);
    }

    /** Stage zones of one camera (saved per camera and profile). */
    public void setZones(String cam, List<StageZone> zones) {
        settings.saveZones(cam, zones);
        tracker(cam).setZones(zones);
        changed();
    }

    public List<StageZone> zones() {
        return tracker().zones();
    }

    public List<StageZone> zones(String cam) {
        return tracker(cam).zones();
    }

    /** A different production profile: its own shots and zones. */
    public void setProfile(String profile) {
        settings.setProfile(profile);
        trackers.forEach((cam, t) -> t.setZones(settings.zones(cam)));
        setShots(settings.shots());
    }

    // ------------------------------------------------------------------ camera

    /** The physical camera the virtual shots come from (an Iriun camera when none is chosen). */
    public synchronized String camera() {
        String chosen = settings.camera();
        if (chosen != null) {
            return chosen;
        }
        if (resolvedCamera == null) {
            List<String> all = cameraList.get();
            resolvedCamera = all.stream().filter(c -> c.toLowerCase().contains("iriun")).findFirst()
                    .orElse(all.isEmpty() ? null : all.get(0));
        }
        return resolvedCamera;
    }

    public void setCamera(String cam) {
        synchronized (this) {
            settings.setCamera(cam);
            resolvedCamera = null;
        }
        changed();
    }

    /** The camera list changed (hot plug): "first camera" may mean another one now. */
    public synchronized void camerasChanged() {
        resolvedCamera = null;
    }

    /** Whether a scene's camera source shows the director's camera ({@code blank} = the first camera). */
    synchronized boolean isDirectorCamera(String sourceCamera) {
        String mine = camera();
        if (mine == null) {
            return false;
        }
        if (sourceCamera == null || sourceCamera.isBlank()) {
            List<String> all = cameraList.get();
            return !all.isEmpty() && all.get(0).equals(mine);
        }
        return sourceCamera.equals(mine);
    }

    // ------------------------------------------------------------------ on / off and modes

    public synchronized boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        synchronized (this) {
            enabled = on;
            settings.setEnabled(on);
            version++;
            if (!on) {
                mode = Mode.MANUAL;
                paused = false;
            }
        }
        changed();
    }

    public synchronized Mode mode() {
        return mode;
    }

    public synchronized long controlVersion() {
        return version;
    }

    public void setMode(Mode m) {
        synchronized (this) {
            version++;
            mode = m;
            paused = false;
            pauseReason = null;
            rejected.clear();
            if (m != Mode.MANUAL) {
                enabled = true;
                settings.setEnabled(true);
                previewByAi = previewByAi && previewPending;
            }
            if (m == Mode.AUTO) {
                programSince = clock.getAsLong(); // the current shot gets its full time before the first cut
            }
        }
        changed();
        tick();
    }

    /** Ctrl+Shift+A: AI Assisted on, or back to Manual. */
    public void toggleAssisted() {
        setMode(mode() == Mode.ASSISTED ? Mode.MANUAL : Mode.ASSISTED);
    }

    /** Ctrl+Shift+F: Full Auto on, or back to Manual. */
    public void toggleAuto() {
        setMode(mode() == Mode.AUTO ? Mode.MANUAL : Mode.AUTO);
    }

    /** Esc / PAUSE AI: the AI stops changing anything; the operator has control. */
    public void pauseAi() {
        pause("AI Paused — Manual Control");
    }

    private void pause(String why) {
        synchronized (this) {
            version++;
            if (mode != Mode.MANUAL) {
                paused = true;
                pauseReason = why;
            }
        }
        changed();
    }

    /** RESUME AI (Ctrl+Shift+R): the only way the AI gets control back after a manual take or a pause. */
    public void resumeAi() {
        synchronized (this) {
            version++;
            paused = false;
            pauseReason = null;
            aiError = null;
            rejected.clear();
            previewByAi = true; // an operator Preview is released to the AI again
            if (mode == Mode.AUTO) {
                programSince = clock.getAsLong();
            }
        }
        changed();
        tick();
    }

    public synchronized boolean paused() {
        return paused;
    }

    // ------------------------------------------------------------------ operator actions (priority 2)

    /** Keys 1–5 / a click on a shot: the shot goes to Preview and is held there for TAKE. */
    public void selectShot(int index) {
        VirtualShot s;
        synchronized (this) {
            if (index < 0 || index >= shots.size()) {
                return;
            }
            s = shots.get(index);
        }
        selectShot(s);
    }

    public void selectShot(VirtualShot s) {
        synchronized (this) {
            version++;
            putOnPreview(s, false);
            previewPending = true;
            previewByAi = false;
        }
        changed();
    }

    /** A click on a person in the camera picture: a shot that follows exactly that person goes to Preview. */
    public void selectPerson(int trackId) {
        selectPerson(camera(), trackId);
    }

    /** A click on a person in one camera's picture: a shot of that camera that follows exactly that person. */
    public void selectPerson(String cam, int trackId) {
        VirtualShot s = personShot(cam, trackId);
        if (s != null) {
            selectShot(s);
        }
    }

    /** A shot that follows exactly this person of this camera (not put anywhere yet), or null when not seen. */
    public VirtualShot personShot(String cam, int trackId) {
        PersonTracker.Person p = tracker(cam).snapshot().stream().filter(x -> x.id() == trackId).findFirst().orElse(null);
        if (p == null) {
            return null;
        }
        VirtualShot s = VirtualShot.of(p.label(), p.role() == null ? Role.OTHER : p.role(), 1.5, true, 0, 8);
        s.id = "person:" + cam + ":" + trackId;
        s.trackId = trackId;
        s.camera = settings.multiCamera() || cam == null || cam.equals(camera()) ? cam : null;
        return s;
    }

    /**
     * Where a shot would frame now (its performer, zone or fixed cut-out), for the Auto Director's own SHOT PREVIEW.
     * Nothing changes: Preview and Program keep their shots.
     */
    public synchronized CropRect cropFor(VirtualShot s) {
        if (s == null) {
            return CropRect.WIDE;
        }
        double zoom = Math.min(s.zoom, settings.maxZoom());
        if (!s.tracking) {
            return s.fixedCrop().withZoom(zoom);
        }
        PersonTracker.Person p = personFor(s, null);
        if (p != null) {
            return CropRect.frame(p.box(), s.margin, zoom);
        }
        Box zone = zoneOf(s);
        return zone != null ? CropRect.frame(zone, 0.05, zoom) : s.fixedCrop();
    }

    /**
     * TAKE (the app's TAKE key or button): a virtual shot waiting in Preview goes to Program. A manual TAKE during
     * Full Auto pauses the AI. Returns false when no shot was waiting (the TAKE is then only about other content).
     */
    public boolean take() {
        synchronized (this) {
            if (!enabled || !previewPending) {
                return false;
            }
            version++;
            putOnProgram(preview.shot, preview.manual, Owner.OPERATOR, preview.personId);
            previewPending = false;
            previewByAi = mode == Mode.ASSISTED; // the next recommendation may come
            rejected.clear();
            if (mode == Mode.AUTO && !paused) {
                paused = true;
                pauseReason = "AI Paused — Manual Control";
            }
        }
        changed();
        return true;
    }

    /** Ctrl+1–5: straight to Program (Preview is left alone). Pauses Full Auto. */
    public void takeShot(int index) {
        VirtualShot s;
        synchronized (this) {
            if (index < 0 || index >= shots.size()) {
                return;
            }
            s = shots.get(index);
            version++;
            if (!enabled) {
                enabled = true;
                settings.setEnabled(true);
            }
            putOnProgram(s, null, Owner.OPERATOR, null);
            if (mode == Mode.AUTO && !paused) {
                paused = true;
                pauseReason = "AI Paused — Manual Control";
            }
        }
        changed();
    }

    /** Cancel: the operator's Preview is dropped; Preview shows Program's shot (AI Assisted may recommend again). */
    public void cancelPreview() {
        synchronized (this) {
            version++;
            putOnPreview(program.shot, false);
            preview.manual = program.manual;
            previewPending = false;
            previewByAi = true;
        }
        changed();
        tick();
    }

    /** AI Assisted: this recommendation is not wanted; another one comes. */
    public void rejectRecommendation() {
        synchronized (this) {
            version++;
            if (preview.shot != null) {
                rejected.add(preview.shot.id);
            }
            putOnPreview(program.shot, false);
            previewPending = false;
            previewByAi = true;
            recommendationWhy = null;
        }
        changed();
        tick();
    }

    /** AI Assisted: another recommendation (the current one is skipped for now). */
    public void nextRecommendation() {
        rejectRecommendation();
    }

    /** Pan and zoom go to Preview, or to Program while "Adjust LIVE" is on (a manual action, so Full Auto pauses). */
    public synchronized void setAdjustProgram(boolean on) {
        adjustProgram = on;
    }

    public synchronized boolean adjustProgram() {
        return adjustProgram;
    }

    /** + / −: zoom the adjusted surface; the AI stops framing that shot until tracking is re-enabled. */
    public void zoomBy(double factor) {
        adjust(c -> c.withZoom(Math.min(settings.maxZoom(), c.zoom() * factor)));
    }

    /** Arrow keys: move the adjusted surface's cut-out (fraction of its own size). */
    public void panBy(double dx, double dy) {
        adjust(c -> c.panBy(dx * c.width(), dy * c.height()));
    }

    /** Dragging the cut-out in the camera picture. */
    public void setCrop(CropRect c, boolean onProgram) {
        adjust(x -> c, onProgram);
    }

    private void adjust(Function<CropRect, CropRect> f) {
        adjust(f, adjustProgram());
    }

    private void adjust(Function<CropRect, CropRect> f, boolean onProgram) {
        synchronized (this) {
            version++;
            Surface s = onProgram ? program : preview;
            CropRect base = s.manual != null ? s.manual : s.shown;
            CropRect next = f.apply(base);
            s.manual = next.withZoom(Math.min(next.zoom(), settings.maxZoom()));
            s.trMs = 0; // the operator's zoom / pan takes over a running shot animation at once
            if (onProgram) {
                programOwner = Owner.OPERATOR;
                if (mode == Mode.AUTO && !paused) {
                    paused = true;
                    pauseReason = "AI Paused — Manual Control";
                }
            } else {
                previewPending = true;
                previewByAi = false;
            }
        }
        changed();
    }

    /** Home: Preview goes back to the original wide shot. */
    public void resetToWide() {
        selectShot(wideShot());
    }

    /** Tracking of the adjusted surface's shot on or off (and the manual framing dropped when turning it on). */
    public void setTracking(boolean on) {
        synchronized (this) {
            version++;
            Surface s = adjustProgram ? program : preview;
            if (s.shot != null) {
                s.shot.tracking = on;
                if (on) {
                    s.manual = null;
                    if (s.shot.role == Role.WIDE && s.shot.trackId == null) {
                        s.shot.role = Role.OTHER;
                    }
                } else {
                    s.manual = s.shown;
                }
                if (!s.shot.id.startsWith("person:")) {
                    settings.saveShots(shots);
                }
            }
        }
        changed();
    }

    /** Saves the Preview framing into its shot (Save framing / Save as new shot). */
    public VirtualShot saveFraming(boolean asNew, String name) {
        VirtualShot saved;
        synchronized (this) {
            VirtualShot base = preview.shot == null ? wideShot() : preview.shot;
            CropRect c = preview.manual != null ? preview.manual : preview.shown;
            saved = asNew || base.id.startsWith("person:") ? base.duplicate() : base;
            if (asNew || base.id.startsWith("person:")) {
                saved.name = name != null && !name.isBlank() ? name : base.name;
                if (saved.priority == 0) {
                    saved.priority = 2;
                }
                shots.add(saved);
            }
            saved.setFixedCrop(c);
            if (preview.manual != null) {
                saved.tracking = false; // the operator framed it by hand
            }
            settings.saveShots(shots);
            preview.shot = saved;
            preview.manual = null;
        }
        changed();
        return saved;
    }

    // ------------------------------------------------------------------ surfaces

    private void putOnPreview(VirtualShot s, boolean byAi) {
        if (s != preview.shot || preview.manual != null) {
            preview.personId = null;
            preview.held = null;
            preview.lostSince = 0;
        }
        boolean changed = s != preview.shot || preview.manual != null;
        preview.shot = s;
        preview.manual = null;
        preview.lost = false;
        if (changed) {
            // every shot choice moves the camera with the shot's transition (Pan + Zoom by default), like on LIVE
            ShotTransition t = s != null && s.transition != null ? s.transition : settings.transition();
            start(preview, t, settings.transitionMs(), cameraOf(s));
        }
        previewByAi = byAi;
    }

    private void putOnProgram(VirtualShot s, CropRect manual, Owner owner, Integer personId) {
        long now = clock.getAsLong();
        boolean same = s == program.shot && Objects.equals(manual, program.manual);
        program.shot = s;
        program.manual = manual;
        program.personId = personId;
        program.held = null;
        program.lostSince = 0;
        program.lost = false;
        programOwner = owner;
        if (!same) {
            ShotTransition t = s != null && s.transition != null ? s.transition : settings.transition();
            start(program, t, settings.transitionMs(), cameraOf(s));
            if (s != null) {
                history.add(s.id);
                if (history.size() > 20) {
                    history.remove(0);
                }
            }
        }
        programSince = now;
        if (s == null || s.isWide()) {
            lastWideAt = now;
        }
    }

    private void start(Surface s, ShotTransition t, int ms, String toCamera) {
        String fromCamera = s.camera != null ? s.camera : camera(); // nothing chosen yet = the main camera
        boolean otherCamera = fromCamera != null && toCamera != null && !fromCamera.equals(toCamera);
        if (otherCamera && t != ShotTransition.CUT && t != ShotTransition.FADE) {
            t = ShotTransition.FADE; // no pan across two cameras: the pictures fade into each other
        }
        s.trFromCamera = fromCamera;
        s.camera = toCamera;
        s.trFrom = s.shown;
        s.trStart = clock.getAsLong();
        s.tr = t;
        s.trMs = t == ShotTransition.CUT ? 0 : Math.max(0, ms);
        s.snap = s.trMs == 0;
    }

    /** The people the AI sees now. */
    public List<PersonTracker.Person> people() {
        return tracker().snapshot();
    }

    /** The people the AI sees in one camera. */
    public List<PersonTracker.Person> people(String cam) {
        return tracker(cam).snapshot();
    }

    /** The performer a shot shows now (the one it already follows when still there, so it never jumps). */
    PersonTracker.Person personFor(VirtualShot shot, Integer sticky) {
        if (shot == null || !shot.tracking) {
            return null;
        }
        List<PersonTracker.Person> people = tracker(cameraOf(shot)).snapshot();
        if (shot.trackId != null) {
            return people.stream().filter(p -> p.id() == shot.trackId).findFirst().orElse(null);
        }
        if (sticky != null) {
            // the shot keeps its person (even into another zone); gone = held still, never someone else
            for (PersonTracker.Person p : people) {
                if (p.id() == sticky) {
                    return p;
                }
            }
            return null;
        }
        PersonTracker.Person best = null;
        for (PersonTracker.Person p : people) {
            boolean match = shot.role == Role.OTHER ? p.role() == null || p.role() == Role.OTHER : p.role() == shot.role;
            if (match && (best == null || p.score() * p.box().area() > best.score() * best.box().area())) {
                best = p;
            }
        }
        return best;
    }

    private Box zoneOf(VirtualShot s) {
        return zoneOf(s.role, cameraOf(s));
    }

    private Box zoneOf(Role r, String cam) {
        for (StageZone z : tracker(cam).zones()) {
            if (z.role() == r) {
                return z.box();
            }
        }
        return null;
    }

    /** Where a surface's camera should be now (before smoothing). */
    private CropRect target(Surface s, long now) {
        if (s.manual != null) {
            return s.manual;
        }
        VirtualShot shot = s.shot;
        if (shot == null) {
            return CropRect.WIDE;
        }
        if (!shot.tracking) {
            return shot.fixedCrop().withZoom(Math.min(shot.zoom, settings.maxZoom()));
        }
        double zoom = Math.min(shot.zoom, settings.maxZoom());
        PersonTracker.Person p = personFor(shot, s.personId);
        if (p != null) {
            s.personId = p.id();
            if (p.visible()) {
                s.lostSince = 0;
                s.lost = false;
            } else if (s.lostSince == 0) {
                s.lostSince = now; // held by the tracker at the last place
            }
            CropRect t = CropRect.frame(p.box(), shot.margin, zoom);
            s.held = t;
            return t;
        }
        if (s.personId == null) {
            // nobody found yet for this shot: the stage zone is its framing (safe rotation), not a "lost" performer
            Box zone = zoneOf(shot);
            return zone != null ? CropRect.frame(zone, 0.05, zoom) : shot.fixedCrop();
        }
        if (s.lostSince == 0) {
            s.lostSince = now;
        }
        s.lost = true;
        if (s.held != null) {
            return s.held; // keep still where the performer was: never jump to someone else
        }
        Box zone = zoneOf(shot);
        return zone != null ? CropRect.frame(zone, 0.05, zoom) : shot.fixedCrop();
    }

    /** One step of a surface's camera: transition, then smooth following. */
    private VirtualCameras.Framing step(Surface s, long now) {
        CropRect target = target(s, now);
        double dt = s.lastStep == 0 ? 0 : Math.clamp((now - s.lastStep) / 1000.0, 0, 0.25);
        s.lastStep = now;
        if (s.snap) {
            s.snap = false;
            s.shown = target;
            return VirtualCameras.Framing.of(target, s.camera);
        }
        long since = now - s.trStart;
        if (s.trMs > 0) {
            if (since < s.trMs) {
                double t = since / (double) s.trMs;
                if (s.tr == ShotTransition.FADE) {
                    s.shown = target;
                    return new VirtualCameras.Framing(target, s.trFrom, (float) ShotTransition.ease(t), s.camera, s.trFromCamera);
                }
                s.shown = s.tr.at(s.trFrom, target, t);
                return VirtualCameras.Framing.of(s.shown, s.camera);
            }
            s.trMs = 0; // the transition is over: it ends exactly on the new shot
            s.shown = target;
            return VirtualCameras.Framing.of(target, s.camera);
        }
        if (s.manual != null && s.shown.near(target, 0.002)) {
            s.shown = target; // a hand-set framing lands exactly
        } else if (!s.shown.near(target, 0.002)) {
            // manual moves glide fast; following a performer is calmer (tracking sensitivity / follow speed)
            double k = s.manual != null ? 14 : 1.5 + settings.followSpeed() * 8;
            s.shown = s.shown.lerp(target, 1 - Math.exp(-k * dt));
        }
        return VirtualCameras.Framing.of(s.shown, s.camera);
    }

    /** The framing of a source that shows the main camera (the usual camera source of a scene). */
    public VirtualCameras.Framing framing(String sourceCamera, boolean onPreview) {
        return framing(sourceCamera, null, onPreview);
    }

    @Override
    public String mainCamera() {
        return camera();
    }

    @Override
    public synchronized VirtualCameras.Framing framing(String sourceCamera, Boolean director, boolean onPreview) {
        if (!enabled || Boolean.FALSE.equals(director) || director == null && !isDirectorCamera(sourceCamera)) {
            return null;
        }
        return step(onPreview ? preview : program, clock.getAsLong());
    }

    // ------------------------------------------------------------------ AI (priorities 3 and 4)

    private void ensureDetector() {
        if (detector != null || !loading.compareAndSet(false, true)) {
            return;
        }
        aiState = "Loading AI model…";
        boolean sent = submit(() -> {
            PersonDetector d = detectorFactory.get();
            detector = d == null ? PersonDetector.NONE : d;
            aiState = d == null ? "No AI model: zones only" : "Ready · " + d.describe();
            toUi.accept(this::changed);
        });
        if (!sent) {
            loading.set(false);
        }
    }

    /** Hands work to the AI thread; false once it is shut down (app closing). */
    private boolean submit(Runnable r) {
        try {
            worker.execute(r);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    public boolean aiReady() {
        PersonDetector d = detector;
        return d != null && d != PersonDetector.NONE;
    }

    /**
     * One camera frame for the AI (called on the Swing thread at the analysis rate). Skipped while the previous
     * frame is still being analysed: frames are never queued, so an overloaded PC drops AI frames, not video.
     */
    public void analyze(BufferedImage frame) {
        analyze(camera(), frame);
    }

    /** One frame of one camera for the AI (each camera has its own tracker). */
    public void analyze(String cam, BufferedImage frame) {
        if (!enabled() || cam == null) {
            return;
        }
        ensureDetector();
        PersonDetector d = detector;
        if (d == null || frame == null || d == PersonDetector.NONE) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            skipped++;
            return;
        }
        PersonDetector.Prepared p;
        try {
            p = d.prepare(frame, reuse);
        } catch (RuntimeException e) {
            busy.set(false);
            return;
        }
        reuse = p;
        long token = controlVersion();
        boolean sent = submit(() -> {
            long t0 = System.nanoTime();
            try {
                List<PersonDetector.Detection> found = d.detect(p);
                tracker(cam).update(found, p.image(), p.scale(), p.frameWidth(), p.frameHeight(), clock.getAsLong());
                analysisMs = analysisMs * 0.8 + (System.nanoTime() - t0) / 1e6 * 0.2;
                analysed++;
                aiError = null;
                aiState = "Ready · " + d.describe();
                toUi.accept(() -> decide(token));
            } catch (Throwable e) {
                log.warn("Auto Director analysis failed", e);
                aiError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                aiState = "AI error: " + aiError;
                toUi.accept(this::aiFailed);
            } finally {
                busy.set(false);
            }
        });
        if (!sent) {
            busy.set(false);
        }
    }

    /** Safety: an AI error stops automatic switching; Program keeps its picture. */
    private void aiFailed() {
        if (mode() == Mode.AUTO && !paused()) {
            pause("AI stopped (error) — Program kept");
        } else {
            changed();
        }
    }

    /** The regular director tick (Swing thread, a few times a second). */
    public void tick() {
        decide(controlVersion());
    }

    /**
     * An AI decision prepared at control version {@code token}. If the operator did anything since, it is rejected:
     * the operator's newer action wins.
     */
    public void decide(long token) {
        boolean change;
        synchronized (this) {
            if (token != version) {
                staleRejected++;
                return;
            }
            if (!enabled || mode == Mode.MANUAL || paused) {
                return;
            }
            long now = clock.getAsLong();
            change = mode == Mode.AUTO ? autoStep(now) : assistStep(now);
        }
        if (change) {
            changed();
        }
    }

    /** Called with a token from before a manual action: for tests of the stale-decision guard. */
    public synchronized long staleRejected() {
        return staleRejected;
    }

    private double available(VirtualShot s) {
        Integer sticky = s == program.shot ? program.personId : s == preview.shot ? preview.personId : null;
        return ShotPlanner.availability(s, personFor(s, sticky), zoneOf(s) != null).score();
    }

    private boolean autoStep(long now) {
        ShotPlanner.Rules rules = ShotPlanner.Rules.of(settings);
        VirtualShot current = program.shot;
        double secs = (now - programSince) / 1000.0;
        // safety: the followed performer is lost for longer than the hold time → the wide shot
        if (current != null && current.tracking && program.lost && program.lostSince > 0
                && now - program.lostSince > settings.holdLostMs() && secs >= rules.minShotSeconds()) {
            VirtualShot wide = wideShot();
            if (wide != current) {
                putOnProgram(wide, null, Owner.AI, null);
                planPreview(now, rules);
                return true;
            }
        }
        double avail = current == null ? 0 : available(current);
        boolean changed = false;
        if (ShotPlanner.timeToCut(current, secs, avail, rules)) {
            VirtualShot next = ShotPlanner.next(shots, current, history, (now - lastWideAt) / 1000.0, this::available, rules);
            if (next != null && next != current) {
                Integer person = next == preview.shot ? preview.personId : null;
                putOnProgram(next, null, Owner.AI, person);
                changed = true;
            }
        }
        return planPreview(now, rules) || changed;
    }

    /** Full Auto: Preview shows the shot the AI plans next (unless the operator chose a Preview). */
    private boolean planPreview(long now, ShotPlanner.Rules rules) {
        if (previewPending && !previewByAi) {
            return false;
        }
        List<String> after = new ArrayList<>(history);
        VirtualShot next = ShotPlanner.next(shots, program.shot, after, (now - lastWideAt) / 1000.0, this::available, rules);
        if (next != null && next != preview.shot) {
            putOnPreview(next, true);
            previewPending = false; // only a look ahead: TAKE does not take it
            recommendationWhy = "Next in Full Auto";
            return true;
        }
        return false;
    }

    private boolean assistStep(long now) {
        if (previewPending && !previewByAi) {
            return false; // the operator's own Preview is held until TAKE / Cancel / Resume
        }
        VirtualShot current = preview.shot;
        boolean stillGood = previewPending && previewByAi && current != null && current != program.shot
                && !rejected.contains(current.id) && available(current) > 0.1;
        if (stillGood) {
            return false;
        }
        List<VirtualShot> choices = shots.stream().filter(s -> !rejected.contains(s.id)).toList();
        VirtualShot rec = ShotPlanner.next(choices, program.shot, history, (now - lastWideAt) / 1000.0,
                this::available, ShotPlanner.Rules.of(settings));
        if (rec == null && !rejected.isEmpty()) {
            rejected.clear(); // all were rejected: start over
            return false;
        }
        if (rec == null || rec == program.shot) {
            return false;
        }
        putOnPreview(rec, true);
        previewPending = true;
        PersonTracker.Person p = personFor(rec, null);
        recommendationWhy = ShotPlanner.availability(rec, p, zoneOf(rec) != null).why();
        return true;
    }

    // ------------------------------------------------------------------ status

    public synchronized Status status() {
        long now = clock.getAsLong();
        PersonTracker.Person pv = personFor(preview.shot, preview.personId);
        PersonTracker.Person pg = personFor(program.shot, program.personId);
        // stepping is time based, so the status can move the cameras too (Preview may be hidden: Studio Mode off)
        CropRect pvCrop = step(preview, now).crop();
        CropRect pgCrop = step(program, now).crop();
        return new Status(enabled, mode, paused, pauseReason, programOwner, preview.shot, program.shot, previewPending,
                previewByAi, pvCrop, pgCrop, preview.manual != null, program.manual != null,
                pv, pg, program.lost && program.shot != null && program.shot.tracking && now - program.lostSince > 300,
                aiState, aiReady(), analysisMs, analysed, skipped, staleRejected, camera(), recommendationWhy,
                cameraOf(preview.shot), cameraOf(program.shot));
    }

    /** Stops the AI thread (app exit). */
    public void shutdown() {
        worker.shutdownNow();
        PersonDetector d = detector;
        if (d != null) {
            d.close();
        }
    }
}
