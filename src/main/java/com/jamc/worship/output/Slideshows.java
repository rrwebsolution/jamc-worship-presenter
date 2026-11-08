package com.jamc.worship.output;

import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;

import javax.swing.Timer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Plays the scenes' IMAGE SLIDESHOW sources. Preview and Program each have their own position and
 * play state, so stepping the slideshow in Preview never moves Program (TAKE decides what Program
 * shows). An advance only changes what this source draws: the outputs repaint as for a new video
 * frame, nothing else in the scene is rebuilt, faded or restarted.
 *
 * <p>All calls happen on the event dispatch thread.
 */
public final class Slideshows {

    public enum Transition {
        CUT, FADE, CROSSFADE;

        public static Transition of(SourceConfig c) {
            try {
                return c.slideTransition == null ? FADE : valueOf(c.slideTransition);
            } catch (IllegalArgumentException e) {
                return FADE;
            }
        }
    }

    public enum Control { PLAY, PAUSE, STOP, NEXT, PREVIOUS, RESTART }

    /** What a slideshow draws now: the image, and while a transition runs the image it comes from. */
    public record View(String current, String previous, float progress, Transition transition) {
    }

    /** For the properties panel: "PREVIEW 3 / 8 · PROGRAM 2 / 8" (0 = not on that output); Auto Play state. */
    public record Status(boolean playing, int previewPosition, int programPosition, int count, long nextInMs, int missing) {
    }

    public static final int DEFAULT_SECONDS = 5;
    public static final int DEFAULT_TRANSITION_MS = 500;

    private final Function<Long, String> resolver;
    private final LongSupplier clock;
    private final Map<String, Play> program = new LinkedHashMap<>();
    private final Map<String, Play> preview = new LinkedHashMap<>();
    /** The scene editor's own position of each slideshow: PREV / NEXT there work without Preview or Program. */
    private final Map<String, Play> editor = new LinkedHashMap<>();
    private final Timer timer;
    private final Random random = new Random();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Called after Preview or Program moved to another image (e.g. to enable TAKE). */
    public void addListener(Runnable l) {
        listeners.add(l);
    }

    /** @param resolver library image id → stored path, or null when the item or its file is missing */
    public Slideshows(Function<Long, String> resolver) {
        this(resolver, System::nanoTime, true);
    }

    Slideshows(Function<Long, String> resolver, LongSupplier nanoClock, boolean runTimer) {
        this.resolver = resolver;
        this.clock = nanoClock;
        this.timer = runTimer ? new Timer(33, e -> tick()) : null;
    }

    /** One slideshow on one output. */
    private final class Play {
        SourceConfig config;
        List<Long> ids = List.of();
        /** Paths of the images that exist, in show order (missing ones are skipped). */
        List<String> images = List.of();
        int missing;
        /** Position in {@link #order}. */
        int position;
        List<Integer> order = List.of();
        int previousImage = -1;
        boolean playing;
        long shownAt;
        long transitionAt = Long.MIN_VALUE;

        void configure(SourceConfig c) {
            config = c;
            List<Long> want = c.imageIds == null ? List.of() : List.copyOf(c.imageIds);
            if (!want.equals(ids) || images.isEmpty()) {
                ids = want;
                List<String> found = new ArrayList<>();
                int miss = 0;
                for (Long id : want) {
                    String p = id == null ? null : resolve(id);
                    if (p == null) {
                        miss++;
                    } else {
                        found.add(p);
                    }
                }
                images = found;
                missing = miss;
                order = newOrder();
                position = Math.min(position, Math.max(0, images.size() - 1));
                previousImage = -1;
            }
        }

        List<Integer> newOrder() {
            List<Integer> o = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                o.add(i);
            }
            if (Boolean.TRUE.equals(config.shuffle)) {
                Collections.shuffle(o, random);
            }
            return o;
        }

        int image() {
            return images.isEmpty() ? -1 : order.get(Math.clamp(position, 0, order.size() - 1));
        }

        long durationNanos() {
            int s = config.slideSeconds == null ? DEFAULT_SECONDS : Math.clamp(config.slideSeconds, 1, 300);
            return s * 1_000_000_000L;
        }

        long transitionNanos() {
            return Transition.of(config) == Transition.CUT ? 0
                    : (config.slideTransitionMs == null ? DEFAULT_TRANSITION_MS : Math.clamp(config.slideTransitionMs, 0, 5000)) * 1_000_000L;
        }

        boolean loops() {
            return config.loop == null || config.loop;
        }

        /** Moves to another position with this slideshow's transition. */
        void go(int newPosition, long now) {
            if (images.isEmpty()) {
                return;
            }
            int before = image();
            position = newPosition;
            if (image() != before) {
                previousImage = before;
                transitionAt = now;
            }
            shownAt = now;
            preload();
            MediaFrames.frameArrived();
        }

        /** Starts loading the image after this one so it is ready when its turn comes. */
        void preload() {
            if (images.size() > 1) {
                String next = images.get(order.get((position + 1) % order.size()));
                try {
                    com.jamc.worship.render.Rendering.renderer().images().get(next);
                } catch (RuntimeException ignored) {
                    // no renderer (tests)
                }
            }
        }

        void step(int delta, long now) {
            if (images.isEmpty()) {
                return;
            }
            int next = position + delta;
            if (next >= order.size()) {
                if (!loops()) {
                    playing = false; // stays on the last image
                    return;
                }
                order = newOrder();
                next = 0;
            } else if (next < 0) {
                next = loops() ? order.size() - 1 : 0;
            }
            go(next, now);
        }
    }

    // ------------------------------------------------------------------ scenes on the outputs

    /**
     * Program's scene changed. A slideshow that arrives on Program starts from its first image (or, with
     * "continue", where Preview was) and plays if it auto-plays; one already on Program keeps going.
     */
    public void syncProgram(Scene scene) {
        sync(scene, program, true);
    }

    /** Preview's scene changed (or was edited): its own slideshow position, separate from Program. */
    public void syncPreview(Scene scene) {
        sync(scene, preview, false);
    }

    private void sync(Scene scene, Map<String, Play> plays, boolean onProgram) {
        paths.clear();
        Map<String, Source> want = slideshows(scene);
        plays.keySet().retainAll(want.keySet());
        long now = clock.getAsLong();
        for (Map.Entry<String, Source> e : want.entrySet()) {
            SourceConfig c = e.getValue().getConfig();
            Play p = plays.get(e.getKey());
            if (p == null) {
                p = new Play();
                p.configure(c);
                // a scene TAKE shows on Program the image the operator checked in Preview (unless the source
                // is set to start from its first image)
                Play fromPreview = onProgram ? preview.get(e.getKey()) : null;
                boolean continueFromPreview = !Boolean.TRUE.equals(c.restartOnProgram) && fromPreview != null;
                p.position = continueFromPreview ? fromPreview.position : 0;
                if (continueFromPreview) {
                    p.order = new ArrayList<>(fromPreview.order);
                }
                // Program never moves by itself: NEXT / PREVIOUS / Auto Play change Preview, TAKE changes Program
                p.playing = !onProgram && Boolean.TRUE.equals(c.autoplay);
                p.shownAt = now;
                p.preload();
                plays.put(e.getKey(), p);
                MediaFrames.frameArrived();
            } else {
                p.configure(c);
            }
        }
        updateTimer();
        fire();
    }

    // ------------------------------------------------------------------ drawing

    /**
     * What the slideshow shows on Preview ({@code preview}) or Program. Without a running state (the scene
     * editor, or before the first sync) it shows its first image.
     */
    public View view(Source s, boolean preview) {
        Play p = (preview ? this.preview : program).get(key(s));
        if (p == null && preview) {
            p = program.get(key(s));
        }
        if (p == null) {
            String first = firstImage(s.getConfig());
            return first == null ? null : new View(first, null, 1f, Transition.CUT);
        }
        return view(p, s);
    }

    /** The current image of one running slideshow, with its transition from the previous one. */
    private View view(Play p, Source s) {
        int img = p.image();
        if (img < 0) {
            return null;
        }
        float progress = 1f;
        String previous = null;
        long length = p.transitionNanos();
        if (p.previousImage >= 0 && p.previousImage < p.images.size() && length > 0) {
            progress = Math.clamp((clock.getAsLong() - p.transitionAt) / (float) length, 0f, 1f);
            if (progress < 1f) {
                previous = p.images.get(p.previousImage);
            }
        }
        return new View(p.images.get(img), previous, progress, Transition.of(p.config));
    }

    private String firstImage(SourceConfig c) {
        if (c.imageIds == null) {
            return null;
        }
        for (Long id : c.imageIds) {
            String path = id == null ? null : resolve(id);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ operator controls

    /**
     * Previous / Next (and the optional Play / Pause / Stop / Restart of Auto Play) change PREVIEW only;
     * Program (projector, livestream, recording) follows at TAKE.
     */
    public void control(Source s, Control control) {
        Play p = preview.get(key(s));
        if (p != null) {
            control(p, control);
        }
    }

    /**
     * PREV / NEXT (and Play / Pause / Stop / Restart) in the SCENE EDITOR: the editor's own position of this
     * slideshow, whether or not the scene is in Preview or on Program (those are not changed here).
     */
    public void controlEditor(Source s, Control control) {
        Play p = editor.computeIfAbsent(key(s), k -> new Play());
        p.configure(s.getConfig());
        if (p.images.isEmpty()) {
            return;
        }
        control(p, control);
    }

    /** What the scene editor shows for this slideshow: its own position, else the first image. */
    public View editorView(Source s) {
        Play p = editor.get(key(s));
        if (p == null) {
            return view(s, true);
        }
        p.configure(s.getConfig()); // images added / removed in the editor
        return view(p, s);
    }

    /** The editor's position (1-based) and image count of this slideshow; position 1 before PREV / NEXT. */
    public int[] editorPosition(Source s) {
        Play p = editor.get(key(s));
        if (p == null) {
            int n = existing(s.getConfig()).size();
            return new int[]{n == 0 ? 0 : 1, n};
        }
        return new int[]{p.images.isEmpty() ? 0 : p.position + 1, p.images.size()};
    }

    /** The scene editor left the screen: its slideshows stop advancing (they keep their image). */
    public void pauseEditor() {
        editor.values().forEach(p -> p.playing = false);
        updateTimer();
    }

    /** True while the editor's own copy of this slideshow plays by itself (PLAY in the editor). */
    public boolean editorPlaying(Source s) {
        Play p = editor.get(key(s));
        return p != null && p.playing;
    }

    /** The NEXT / PREVIOUS image keyboard shortcuts: every slideshow in the Preview scene. */
    public boolean controlPreview(Control control) {
        preview.values().forEach(p -> control(p, control));
        return !preview.isEmpty();
    }

    /**
     * PREV / NEXT under the Program monitor: the operator steps the live slideshow directly. Preview moves
     * to the same image too, so a following TAKE does not bring the old image back.
     */
    public boolean controlProgram(Control control) {
        long now = clock.getAsLong();
        for (Map.Entry<String, Play> e : program.entrySet()) {
            Play pg = e.getValue();
            control(pg, control);
            pg.playing = false; // Program never runs by itself
            Play pv = preview.get(e.getKey());
            if (pv != null && pv.images.equals(pg.images)) {
                pv.order = new ArrayList<>(pg.order);
                pv.go(pg.position, now);
            }
        }
        fire();
        return !program.isEmpty();
    }

    /** "IMAGE 2 / 5" for the (first) slideshow on Preview or Program, or null when it has none. */
    public String summary(boolean onProgram) {
        for (Play p : (onProgram ? program : preview).values()) {
            if (!p.images.isEmpty()) {
                return "IMAGE " + (p.position + 1) + " / " + p.images.size();
            }
        }
        return null;
    }

    /** True when a slideshow in Preview shows another image than the same slideshow on Program. */
    public boolean takePending() {
        for (Map.Entry<String, Play> e : program.entrySet()) {
            Play pv = preview.get(e.getKey());
            if (pv != null && pv.image() != e.getValue().image()) {
                return true;
            }
        }
        return false;
    }

    /**
     * TAKE: each slideshow on Program goes to the image Preview shows, with its own transition in its own
     * layer (nothing else in the scene changes).
     */
    public void take() {
        long now = clock.getAsLong();
        for (Map.Entry<String, Play> e : program.entrySet()) {
            Play pv = preview.get(e.getKey());
            Play pg = e.getValue();
            if (pv != null && !pv.images.isEmpty() && pv.images.equals(pg.images)) {
                pg.order = new ArrayList<>(pv.order);
                pg.go(pv.position, now);
            }
        }
        updateTimer();
        fire();
    }

    private void control(Play p, Control control) {
        long now = clock.getAsLong();
        switch (control) {
            case PLAY -> {
                if (!p.playing) {
                    p.playing = true;
                    p.shownAt = now; // the current image gets its full time again
                }
            }
            case PAUSE -> p.playing = false;
            case STOP -> {
                p.playing = false;
                p.go(0, now);
            }
            case NEXT -> p.step(1, now);
            case PREVIOUS -> p.step(-1, now);
            case RESTART -> {
                p.order = p.newOrder();
                p.go(0, now);
                p.playing = true;
            }
        }
        MediaFrames.frameArrived();
        updateTimer();
        fire();
    }

    /** For the properties panel: Preview's image (what NEXT / PREVIOUS move) and Program's; null when in neither. */
    public Status status(Source s) {
        Play pv = preview.get(key(s));
        Play pg = program.get(key(s));
        Play p = pv != null ? pv : pg;
        if (p == null) {
            return null;
        }
        long left = pv != null && pv.playing ? Math.max(0, pv.durationNanos() - (clock.getAsLong() - pv.shownAt)) / 1_000_000L : -1;
        return new Status(pv != null && pv.playing, pv == null || pv.images.isEmpty() ? 0 : pv.position + 1,
                pg == null || pg.images.isEmpty() ? 0 : pg.position + 1, p.images.size(), left, p.missing);
    }

    /** How many of this slideshow's images cannot be found (deleted from the library, or the file is gone). */
    public int missing(SourceConfig c) {
        int n = 0;
        if (c.imageIds != null) {
            for (Long id : c.imageIds) {
                if (id == null || resolve(id) == null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The ids whose image can still be found (REMOVE MISSING ITEMS). */
    public List<Long> existing(SourceConfig c) {
        List<Long> out = new ArrayList<>();
        if (c.imageIds != null) {
            for (Long id : c.imageIds) {
                if (id != null && resolve(id) != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ internals

    /** Advances playing slideshows whose image time is up; repaints while a transition runs. */
    void tick() {
        long now = clock.getAsLong();
        boolean repaint = false;
        boolean advanced = false;
        for (Map<String, Play> plays : List.of(program, preview, editor)) {
            for (Play p : plays.values()) {
                if (p.playing && p.images.size() > 1 && now - p.shownAt >= p.durationNanos()) {
                    p.step(1, now);
                    advanced = true;
                }
                if (p.previousImage >= 0) {
                    repaint = true; // every frame of the transition (and the last one)
                    if (now - p.transitionAt >= p.transitionNanos()) {
                        p.previousImage = -1;
                    }
                }
            }
        }
        if (advanced) {
            fire();
        }
        if (repaint) {
            MediaFrames.frameArrived();
        }
        updateTimer();
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }

    private void updateTimer() {
        if (timer == null) {
            return;
        }
        boolean needed = false;
        for (Map<String, Play> plays : List.of(program, preview, editor)) {
            for (Play p : plays.values()) {
                needed |= p.playing || p.previousImage >= 0;
            }
        }
        if (needed && !timer.isRunning()) {
            timer.start();
        } else if (!needed && timer.isRunning()) {
            timer.stop();
        }
    }

    /** Library id → path, remembered until the next scene sync (painting never queries the library). */
    private final Map<Long, java.util.Optional<String>> paths = new HashMap<>();

    private String resolve(Long id) {
        return paths.computeIfAbsent(id, i -> java.util.Optional.ofNullable(resolver.apply(i))).orElse(null);
    }

    /** Looks the library up again (an image was imported, relinked or deleted). */
    public void refresh() {
        paths.clear();
    }

    /** The layer's permanent uid (database source ids change on every save); its name for older layers. */
    static String key(Source s) {
        String uid = s.getConfig().uid;
        return uid != null ? "uid:" + uid : "name:" + s.getName();
    }

    private static Map<String, Source> slideshows(Scene scene) {
        Map<String, Source> out = new HashMap<>();
        if (scene == null) {
            return out;
        }
        for (Iterator<Source> it = scene.getSources().iterator(); it.hasNext(); ) {
            Source s = it.next();
            if (s.isVisible() && s.getType() == SourceType.SLIDESHOW) {
                out.putIfAbsent(key(s), s);
            }
        }
        return out;
    }
}
