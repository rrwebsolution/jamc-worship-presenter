package com.jamc.worship.output;

import com.jamc.worship.config.LyricsTransitionSettings;
import com.jamc.worship.model.LowerThird;
import com.jamc.worship.model.Scene;

import javax.swing.Timer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single source of truth for what is live.
 *
 * <p>Every Program surface (projector window, operator Program monitor, OBS window) observes this
 * object and paints the same frame and transition, so they can never disagree.
 *
 * <p>Operator switches:
 * <ul>
 *   <li><b>Black</b>: the whole output goes black (toggle).</li>
 *   <li><b>Blank</b>: text disappears but the background stays; the next TAKE clears it
 *       automatically, so the operator can blank between songs without forgetting to undo it.</li>
 *   <li><b>Lyrics off</b>: text stays hidden for every slide until switched back on.</li>
 *   <li><b>Background off</b>: text on plain black, for every slide until switched back on.</li>
 * </ul>
 * Scenes decide which layers (background, camera, logo, text, lower third...) are composed.
 * Lower thirds animate in and out independently of slide changes.
 *
 * <p>Confined to the Swing event dispatch thread.
 */
public final class ProgramOutput {

    public static final int[] DURATIONS_MS = {300, 500, 1000, 2000};
    private static final int LOWER_THIRD_MS = 600;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Timer timer = new Timer(15, e -> tick());

    private LiveContent content;
    private boolean black;
    private boolean blank;
    private boolean lyricsHidden;
    private boolean backgroundHidden;
    private Scene scene;

    private Transition transition = Transition.FADE;
    private int durationMs = 500;
    /** Fade length when a TAKE goes from one camera scene to another; 0 = CUT (camera switching feels immediate). */
    private int cameraFadeMs;

    private FrameSpec shown = FrameSpec.EMPTY;
    private FrameSpec previous;
    private Transition activeTransition = Transition.CUT;
    private int activeDurationMs;
    private long startNanos;
    /** Easing of a running lyrics transition (Settings → Lyrics Transition), or null for the linear Program transition. */
    private LyricsTransitionSettings.Easing activeEasing;
    /** Where the Spotify-style lyrics were on screen when this transition cut a running one short, else null. */
    private LyricsReel.Pose reelStart;

    private LowerThird lowerThird;
    private boolean lowerThirdOn;
    private long lowerThirdStart;
    private float lowerThirdFrom;

    public ProgramOutput() {
        timer.setCoalesce(true);
    }

    // ------------------------------------------------------------------ operator actions

    /** Puts content on Program using the selected transition. Clears a one-shot Blank. */
    public void take(LiveContent newContent) {
        content = newContent;
        blank = false;
        apply(true);
    }

    /** The content on show was rendered again (a refreshed presentation): the new version at once, nothing else changes. */
    public void updateContent(LiveContent newContent) {
        content = newContent;
        apply(false);
    }

    /**
     * TAKE of a complete Preview: content and scene change together in one transition. A null
     * content keeps what is on Program (only the scene changes); a null scene keeps the live scene.
     */
    public void take(LiveContent newContent, Scene newScene) {
        if (newContent != null) {
            content = newContent;
            blank = false;
        }
        Scene old = scene;
        if (newScene != null) {
            scene = newScene.copy();
        }
        apply(true, cameraSwitch(old, scene));
    }

    /** Removes the live content (Program shows the scene without slide content). */
    public void clear() {
        content = null;
        blank = false;
        apply(true);
    }

    public void setBlack(boolean on) {
        black = on;
        apply(true);
    }

    public void toggleBlack() {
        setBlack(!black);
    }

    public void toggleBlank() {
        blank = !blank;
        apply(true);
    }

    public void setLyricsHidden(boolean hidden) {
        lyricsHidden = hidden;
        apply(true);
    }

    public void toggleLyrics() {
        setLyricsHidden(!lyricsHidden);
    }

    public void setBackgroundHidden(boolean hidden) {
        backgroundHidden = hidden;
        apply(true);
    }

    public void toggleBackground() {
        setBackgroundHidden(!backgroundHidden);
    }

    /**
     * Switches the scene. {@code animate} uses the selected transition (scene switching); pass
     * false when the same scene was only edited (a source moved or toggled) so it updates instantly.
     */
    public void setScene(Scene newScene, boolean animate) {
        Scene old = scene;
        scene = newScene == null ? null : newScene.copy();
        apply(animate, animate && cameraSwitch(old, scene));
    }

    /**
     * Fade length for switching between camera scenes (CAM 1 → CAM 2); 0 = CUT, the default. Only the layers
     * that differ fade; the lyrics, logo and lower third on top stay.
     */
    public void setCameraSwitchFade(int ms) {
        cameraFadeMs = Math.clamp(ms, 0, 3000);
    }

    public int cameraSwitchFade() {
        return cameraFadeMs;
    }

    /** True when the scene changes from one showing a camera to another showing a camera. */
    static boolean cameraSwitch(Scene from, Scene to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        boolean same = java.util.Objects.equals(from.getId(), to.getId()) && from.getId() != null;
        return !same && from.has(com.jamc.worship.model.SourceType.CAMERA) && to.has(com.jamc.worship.model.SourceType.CAMERA);
    }

    public void setTransition(Transition transition, int durationMs) {
        this.transition = transition == null ? Transition.CUT : transition;
        this.durationMs = Math.clamp(durationMs, 0, 10_000);
        fire();
    }

    /** Animates a lower third in. Showing another one while one is visible swaps it instantly. */
    public void showLowerThird(LowerThird lt) {
        boolean swap = lowerThirdOn && lowerThird != null;
        lowerThird = lt.copy();
        if (swap) {
            fire();
            return;
        }
        lowerThirdFrom = lowerThirdProgress();
        lowerThirdOn = true;
        lowerThirdStart = System.nanoTime();
        timer.start();
        fire();
    }

    public void hideLowerThird() {
        if (!lowerThirdOn) {
            return;
        }
        lowerThirdFrom = lowerThirdProgress();
        lowerThirdOn = false;
        lowerThirdStart = System.nanoTime();
        timer.start();
        fire();
    }

    // ------------------------------------------------------------------ state

    public LiveContent content() { return content; }
    public boolean isBlack() { return black; }
    public boolean isBlank() { return blank; }
    public boolean isLyricsHidden() { return lyricsHidden; }
    public boolean isBackgroundHidden() { return backgroundHidden; }
    public Scene scene() { return scene; }
    public Transition transition() { return transition; }
    public int durationMs() { return durationMs; }

    /** The frame Program is showing (or transitioning to). */
    public FrameSpec shown() { return shown; }

    /** The frame being transitioned away from, or null when no transition is running. */
    public FrameSpec previous() { return previous; }

    public Transition activeTransition() { return activeTransition; }

    /** Transition progress 0..1 (eased for lyrics transitions); 1 when idle. */
    public float progress() {
        if (previous == null || activeDurationMs <= 0) {
            return 1f;
        }
        float p = Math.clamp((System.nanoTime() - startNanos) / 1_000_000f / activeDurationMs, 0f, 1f);
        return activeEasing == null ? p : activeEasing.apply(p);
    }

    /** Duration of the running transition in ms (0 when idle or a cut). */
    public int activeDurationMs() {
        return previous == null ? 0 : activeDurationMs;
    }

    /**
     * Spotify-style lyrics: where the cards were when a NEXT / PREVIOUS cut the previous scroll short, so the new
     * scroll starts from there instead of jumping; null when the scroll starts from rest.
     */
    public LyricsReel.Pose reelStart() {
        return previous == null ? null : reelStart;
    }

    public LowerThird lowerThird() {
        return lowerThird;
    }

    public boolean isLowerThirdOn() {
        return lowerThirdOn;
    }

    /** 0 = hidden, 1 = fully shown; in between while animating. */
    public float lowerThirdProgress() {
        if (lowerThird == null) {
            return 0f;
        }
        float t = Math.clamp((System.nanoTime() - lowerThirdStart) / 1_000_000f / LOWER_THIRD_MS, 0f, 1f);
        if (lowerThird.getAnimation() == LowerThird.Animation.NONE) {
            t = 1f;
        }
        return lowerThirdOn ? lowerThirdFrom + (1f - lowerThirdFrom) * t : lowerThirdFrom * (1f - t);
    }

    /** True if something other than black is on Program. */
    public boolean isShowingContent() {
        return shown.showsSomething();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ internals

    private void apply(boolean allowAnimation) {
        apply(allowAnimation, false);
    }

    /** @param cameraSwitch a switch between camera scenes: the camera transition (CUT unless a fade is set) */
    private void apply(boolean allowAnimation, boolean cameraSwitch) {
        FrameSpec next = new FrameSpec(content, !lyricsHidden && !blank, !backgroundHidden, black, scene);
        if (next.equals(shown)) {
            fire();
            return;
        }
        // lyrics to lyrics in the same scene: the lyrics transition chosen in Settings (Smooth Fade / Spotify-style)
        LyricsTransitionSettings lyricsFx = cameraSwitch ? null : lyricsTransition(shown, next);
        Transition kind = cameraSwitch ? (cameraFadeMs > 0 ? Transition.CROSSFADE : Transition.CUT)
                : lyricsFx != null ? Transition.CROSSFADE : transition;
        int ms = cameraSwitch ? cameraFadeMs : lyricsFx != null ? lyricsFx.durationMs() : durationMs;
        boolean animate = allowAnimation && kind != Transition.CUT && ms > 0;
        // NEXT pressed while the lyrics are still scrolling: continue from where the cards are now, no jump
        LyricsReel.Pose carry = null;
        if (animate && previous != null && progress() < 1f && LyricsReel.scrolls(shown.content(), next.content())
                && LyricsReel.scrolls(previous.content(), shown.content())) {
            carry = LyricsReel.pose(previous.content(), shown.content(), reelStart, progress());
        }
        // the Program lyrics style (lower third on the stream) fades the words on its own clock, even under a CUT
        boolean overlay = allowAnimation && !cameraSwitch && overlayMs > 0 && overlayLyricsChange(shown, next);
        boolean hold = animate || overlay;
        // A new change during a running transition starts from the frame being transitioned to.
        previous = hold ? shown : null;
        shown = next;
        activeTransition = animate ? kind : Transition.CUT;
        activeDurationMs = animate ? ms : 0;
        activeEasing = animate && lyricsFx != null ? lyricsFx.easing() : null;
        overlayChange = overlay;
        reelStart = carry;
        startNanos = System.nanoTime();
        if (hold) {
            timer.start();
        }
        fire();
    }

    /** Fade length of the Program lyrics style (lower third), 0 = cut / style off. */
    private int overlayMs;
    /** The running change also fades the Program-style lyrics (on {@link #overlayProgress()}). */
    private boolean overlayChange;

    /** Sets how long Program-style lyrics (the stream's lower third) take to change; 0 = at once. */
    public void setLyricsOverlayFade(int ms) {
        overlayMs = Math.clamp(ms, 0, 2000);
    }

    /**
     * Progress 0..1 of the Program-style lyrics (lower third) in the running change, on their own fade length; 1 when
     * the change is not one of theirs or is over. The projector keeps using {@link #progress()}.
     */
    public float overlayProgress() {
        if (previous == null || !overlayChange || overlayMs <= 0) {
            return 1f;
        }
        return Math.clamp((System.nanoTime() - startNanos) / 1_000_000f / overlayMs, 0f, 1f);
    }

    /** Song lyrics appear, change or disappear in the same scene (what the Program lyrics style draws). */
    static boolean overlayLyricsChange(FrameSpec from, FrameSpec to) {
        if (!Compositor.contentChangeOnly(from, to)) {
            return false;
        }
        LiveContent a = from.content();
        LiveContent b = to.content();
        return (isSongLyrics(a) || isSongLyrics(b)) && (a != b || from.text() != to.text());
    }

    /** A song's lyrics card (not a Bible verse, announcement, picture or video). */
    public static boolean isSongLyrics(LiveContent c) {
        return c != null && !c.isVideo() && c.slide() != null && c.slide().getSongId() != null;
    }

    /**
     * The lyrics transition for a change from {@code from} to {@code to}, or null for Program's own transition:
     * only between two lyrics cards sent with a lyrics transition (not NONE), in the same scene, with the words
     * showing on both sides (Blank, Lyrics off and black keep the Program transition).
     */
    static LyricsTransitionSettings lyricsTransition(FrameSpec from, FrameSpec to) {
        LiveContent a = from.content();
        LiveContent b = to.content();
        if (a == null || b == null || a == b || a.lyrics() == null || b.lyrics() == null || a.isVideo() || b.isVideo()
                || !from.text() || !to.text() || from.background() != to.background()
                || !Compositor.contentChangeOnly(from, to)) {
            return null;
        }
        return b.lyrics().settings().mode() == LyricsTransitionSettings.Mode.NONE ? null : b.lyrics().settings();
    }

    private void tick() {
        boolean transitionDone = progress() >= 1f && overlayProgress() >= 1f;
        if (transitionDone) {
            previous = null;
        }
        boolean lowerThirdDone = (System.nanoTime() - lowerThirdStart) / 1_000_000f >= LOWER_THIRD_MS;
        if (!lowerThirdOn && lowerThirdDone) {
            lowerThird = lowerThird != null && lowerThirdProgress() <= 0f ? null : lowerThird;
        }
        if (transitionDone && lowerThirdDone) {
            timer.stop();
        }
        fire();
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }
}
