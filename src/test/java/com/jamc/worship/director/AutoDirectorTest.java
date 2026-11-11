package com.jamc.worship.director;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Modes, control priority, stale AI decisions and independent Preview / Program framing. */
class AutoDirectorTest {

    @TempDir
    Path dir;

    private long now = 1_000_000;
    private DirectorSettings settings;
    private final List<Runnable> uiQueue = new ArrayList<>();
    private final List<Runnable> aiQueue = new ArrayList<>();
    private FakeDetector detector;
    private AutoDirector director;

    /** Runs submitted AI work only when the test says so (to put manual actions in between). */
    private final class StepExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable r) { aiQueue.add(r); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    /** Returns the people the test puts in front of the camera. */
    static final class FakeDetector implements PersonDetector {
        List<Detection> people = new ArrayList<>();
        boolean fail;

        @Override
        public List<Detection> detect(Prepared input) {
            if (fail) {
                throw new IllegalStateException("model crashed");
            }
            return people;
        }

        @Override
        public Prepared prepare(BufferedImage frame, Prepared reuse) {
            return new Prepared(null, 1, 1920, 1080);
        }

        @Override public String describe() { return "fake"; }
        @Override public void close() { }
    }

    @BeforeEach
    void setUp() {
        AppPaths paths = new AppPaths(dir);
        paths.ensureDirectories();
        settings = new DirectorSettings(new AppConfig(paths));
        settings.setEnabled(true);
        settings.setMinShotSeconds(4);
        detector = new FakeDetector();
        // keyboard left, lead singer center, drums right (inside the default zones)
        detector.people = new ArrayList<>(List.of(
                new PersonDetector.Detection(new Box(0.10, 0.40, 0.10, 0.40), 0.9f),
                new PersonDetector.Detection(new Box(0.45, 0.30, 0.10, 0.50), 0.9f),
                new PersonDetector.Detection(new Box(0.80, 0.40, 0.10, 0.40), 0.9f)));
        director = new AutoDirector(settings, () -> detector, uiQueue::add, () -> now,
                () -> List.of("Iriun Webcam"), new StepExecutor());
        director.setEnabled(true); // the operator turns it on (it is always off when LiveCat opens)
    }

    private void runAll() {
        for (int guard = 0; guard < 50 && (!aiQueue.isEmpty() || !uiQueue.isEmpty()); guard++) {
            List<Runnable> ai = new ArrayList<>(aiQueue);
            aiQueue.clear();
            ai.forEach(Runnable::run);
            List<Runnable> ui = new ArrayList<>(uiQueue);
            uiQueue.clear();
            ui.forEach(Runnable::run);
        }
    }

    /** Analyses frames (the first call also loads the model; a person is confirmed after two sightings). */
    private void see() {
        for (int i = 0; i < 3; i++) {
            director.analyze(new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB));
            runAll();
            now += 200;
        }
    }

    /** Lets the smoothed cameras catch up (as the monitors would, many times a second). */
    private void settle() {
        for (int i = 0; i < 30; i++) {
            now += 100;
            director.status();
        }
    }

    private void advance(double seconds) {
        long end = now + (long) (seconds * 1000);
        while (now < end) {
            now += 250;
            director.tick();
        }
    }

    @Test
    void manualSelectionGoesToPreviewOnlyAndTakeSendsItToProgram() {
        VirtualShot wide = director.status().programShot();
        director.selectShot(2); // keyboardist
        assertEquals("Keyboardist", director.status().previewShot().name);
        assertSame(wide, director.status().programShot(), "Program must not change before TAKE");
        assertTrue(director.take());
        assertEquals("Keyboardist", director.status().programShot().name);
        assertEquals(AutoDirector.Owner.OPERATOR, director.status().programOwner());
        assertFalse(director.take(), "nothing waits in Preview any more");
    }

    @Test
    void takeWithoutAWaitingShotLeavesTheCameraAloneSoOtherContentCanBeTaken() {
        assertFalse(director.take());
        director.setEnabled(false);
        director.selectShot(1);
        assertFalse(director.take(), "the director off never takes anything");
    }

    @Test
    void aiAssistedRecommendsInPreviewAndNeverTouchesProgram() {
        see();
        VirtualShot programBefore = director.status().programShot();
        director.setMode(AutoDirector.Mode.ASSISTED);
        advance(60);
        var st = director.status();
        assertSame(programBefore, st.programShot(), "AI Assisted must never change Program");
        assertNotSame(programBefore, st.previewShot());
        assertTrue(st.previewByAi());
        assertTrue(st.previewPending(), "the recommendation is ready for TAKE");
        assertTrue(director.take(), "only the operator's TAKE broadcasts it");
        assertSame(st.previewShot(), director.status().programShot());
    }

    @Test
    void aiAssistedHoldsTheOperatorsOwnPreview() {
        see();
        director.setMode(AutoDirector.Mode.ASSISTED);
        director.tick();
        director.selectShot(3); // drummer, chosen by the operator
        advance(120);
        assertEquals("Drummer", director.status().previewShot().name, "AI must not overwrite the operator's Preview");
        assertFalse(director.status().previewByAi());
    }

    @Test
    void rejectAndNextGiveAnotherRecommendation() {
        see();
        director.setMode(AutoDirector.Mode.ASSISTED);
        director.tick();
        VirtualShot first = director.status().previewShot();
        director.rejectRecommendation();
        VirtualShot second = director.status().previewShot();
        assertNotSame(first, second);
        director.nextRecommendation();
        assertNotSame(second, director.status().previewShot());
    }

    @Test
    void fullAutoSwitchesProgramByItself() {
        see();
        director.setMode(AutoDirector.Mode.AUTO);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            advance(5);
            see();
            String name = director.status().programShot().name;
            if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(name)) {
                seen.add(name);
            }
        }
        assertTrue(seen.size() >= 4, "Full Auto should switch between several shots: " + seen);
        assertTrue(seen.contains("Wide Shot") && seen.contains("Lead Singer"), seen.toString());
        assertEquals(AutoDirector.Owner.AI, director.status().programOwner());
    }

    @Test
    void fullAutoNeverCutsFasterThanTheMinimumShotTime() {
        see();
        settings.setMinShotSeconds(6);
        director.setMode(AutoDirector.Mode.AUTO);
        VirtualShot last = director.status().programShot();
        long lastChange = now;
        for (int i = 0; i < 400; i++) {
            now += 100;
            director.tick();
            VirtualShot s = director.status().programShot();
            if (s != last) {
                assertTrue(now - lastChange >= 6000, "cut after " + (now - lastChange) + " ms");
                last = s;
                lastChange = now;
            }
        }
    }

    @Test
    void manualTakeDuringFullAutoPausesTheAiUntilResume() {
        see();
        director.setMode(AutoDirector.Mode.AUTO);
        director.selectShot(2);
        director.take();
        var st = director.status();
        assertTrue(st.paused());
        assertEquals("AI Paused — Manual Control", st.pauseReason());
        assertEquals("Keyboardist", st.programShot().name);
        advance(300); // no timer ever resumes the AI
        assertEquals("Keyboardist", director.status().programShot().name);
        assertTrue(director.status().paused());
        director.resumeAi();
        assertFalse(director.status().paused());
        advance(30);
        assertNotEquals("Keyboardist", director.status().programShot().name, "the AI switches again after Resume");
    }

    @Test
    void directTakeShortcutGoesStraightToProgramAndPausesFullAuto() {
        see();
        director.setMode(AutoDirector.Mode.AUTO);
        VirtualShot preview = director.status().previewShot();
        director.takeShot(3);
        assertEquals("Drummer", director.status().programShot().name);
        assertTrue(director.status().paused());
        assertSame(preview, director.status().previewShot(), "a direct TAKE leaves Preview alone");
    }

    @Test
    void escPausesAndAiDecisionsStop() {
        see();
        director.setMode(AutoDirector.Mode.AUTO);
        director.pauseAi();
        VirtualShot program = director.status().programShot();
        advance(200);
        assertSame(program, director.status().programShot());
    }

    @Test
    void anAiResultFromBeforeAManualActionIsRejected() {
        see();
        director.setMode(AutoDirector.Mode.AUTO);
        advance(20);
        // the AI starts analysing a frame...
        director.analyze(new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB));
        // ...the operator acts while it runs...
        director.selectShot(3);
        long rejectedBefore = director.staleRejected();
        VirtualShot preview = director.status().previewShot();
        now += 60_000; // long enough that a fresh decision would cut
        runAll(); // ...and the old result arrives
        assertEquals(rejectedBefore + 1, director.staleRejected());
        assertSame(preview, director.status().previewShot());
    }

    @Test
    void staleTokenNeverChangesProgram() {
        see();
        director.setMode(AutoDirector.Mode.AUTO);
        long token = director.controlVersion();
        director.takeShot(1); // operator
        director.resumeAi(); // even after a resume, the old token is old
        VirtualShot program = director.status().programShot();
        now += 120_000;
        director.decide(token);
        assertSame(program, director.status().programShot());
    }

    @Test
    void previewZoomNeverMovesProgram() {
        see();
        director.selectShot(1);
        director.take(); // lead singer on Program
        now += 2000;
        CropRect programBefore = director.status().programCrop();
        director.selectShot(0);
        director.zoomBy(1.6);
        director.panBy(0.3, 0);
        now += 2000;
        var st = director.status();
        assertTrue(st.previewManual());
        assertFalse(st.programManual());
        assertTrue(programBefore.near(st.programCrop(), 1e-6), programBefore + " vs " + st.programCrop());
        assertTrue(st.previewCrop().zoom() > 1.5);
    }

    @Test
    void manualFramingStopsTrackingOnThatSurfaceUntilReEnabled() {
        see();
        director.selectShot(1);
        director.zoomBy(1.2);
        settle();
        CropRect held = director.status().previewCrop();
        detector.people.set(1, new PersonDetector.Detection(new Box(0.40, 0.30, 0.10, 0.50), 0.9f));
        see();
        now += 3000;
        assertTrue(held.near(director.status().previewCrop(), 0.01), "manual framing must not fight the tracking");
        director.setTracking(true);
        now += 3000;
        assertFalse(director.status().previewManual());
    }

    @Test
    void zoomIsLimitedUnlessHigherZoomIsAllowed() {
        director.selectShot(0);
        for (int i = 0; i < 20; i++) {
            director.zoomBy(1.5);
        }
        settle();
        assertEquals(CropRect.QUALITY_ZOOM, director.status().previewCrop().zoom(), 1e-6);
        settings.setAllowHighZoom(true);
        for (int i = 0; i < 20; i++) {
            director.zoomBy(1.5);
        }
        settle();
        assertEquals(CropRect.MAX_ZOOM, director.status().previewCrop().zoom(), 1e-6);
    }

    @Test
    void lostPerformerInFullAutoFallsBackToTheWideShot() {
        see();
        director.setMode(AutoDirector.Mode.AUTO);
        director.takeShot(1); // lead singer
        director.resumeAi();
        detector.people.remove(1); // the lead singer walks off
        for (int i = 0; i < 30; i++) {
            see();
            director.tick();
        }
        assertEquals("Wide Shot", director.status().programShot().name);
    }

    @Test
    void lostPerformerInManualHoldsTheFramingStill() {
        see();
        director.takeShot(1);
        now += 3000;
        CropRect before = director.status().programCrop();
        detector.people.remove(1);
        for (int i = 0; i < 30; i++) {
            see();
            settle();
        }
        assertEquals("Lead Singer", director.status().programShot().name);
        assertTrue(before.near(director.status().programCrop(), 0.02), "no jump to another person");
        assertTrue(director.status().programLost());
    }

    @Test
    void anAiErrorStopsFullAutoAndKeepsProgram() {
        see();
        director.setMode(AutoDirector.Mode.AUTO);
        VirtualShot program = director.status().programShot();
        detector.fail = true;
        director.analyze(new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB));
        runAll();
        assertTrue(director.status().paused());
        assertTrue(director.status().pauseReason().contains("error"));
        assertSame(program, director.status().programShot());
    }

    @Test
    void framingIsOnlyForTheDirectorsCameraAndOnlyWhenOn() {
        director.selectShot(1);
        director.take();
        assertNotNull(director.framing("Iriun Webcam", false));
        assertNotNull(director.framing("", false), "blank = first camera");
        assertNull(director.framing("Integrated Camera", false));
        director.setEnabled(false);
        assertNull(director.framing("Iriun Webcam", false), "off = the camera is drawn exactly as before");
    }

    @Test
    void fadeMixesTheOldAndNewCutOut() {
        settings.setTransition(ShotTransition.FADE);
        settings.setTransitionMs(1000);
        director.framing("Iriun Webcam", false);
        director.selectShot(2);
        director.take();
        now += 500;
        VirtualCameras.Framing f = director.framing("Iriun Webcam", false);
        assertNotNull(f.from());
        assertTrue(f.fadeIn() > 0.2 && f.fadeIn() < 0.8);
        now += 600;
        f = director.framing("Iriun Webcam", false);
        assertNull(f.from());
    }

    @Test
    void choosingAShotAnimatesPreviewToo() {
        settings.setTransition(ShotTransition.PAN_ZOOM);
        settings.setTransitionMs(1000);
        director.status(); // Preview on the wide shot
        director.selectShot(3); // drummer
        now += 500;
        CropRect mid = director.status().previewCrop();
        now += 600;
        CropRect end = director.status().previewCrop();
        assertTrue(end.zoom() > 1.1, "the drummer shot is zoomed in, was " + end);
        assertTrue(mid.zoom() > 1.0 && mid.zoom() < end.zoom() - 0.01, "half way: between wide and the shot, was " + mid);
        now += 1000;
        assertTrue(end.near(director.status().previewCrop(), 1e-6), "the animation ended on the shot");
    }

    @Test
    void aShotOfAnotherCameraShowsThatCameraAndFadesAcross() {
        director.setMultiCamera(true);
        settings.setTransition(ShotTransition.PAN_ZOOM);
        settings.setTransitionMs(1000);
        List<VirtualShot> list = new ArrayList<>(director.shots());
        VirtualShot drums = VirtualShot.of("Drums close", Role.WIDE, 1.0, false, 3, 6);
        drums.camera = "Cam 2";
        list.add(drums);
        director.setShots(list);
        assertEquals(List.of("Iriun Webcam", "Cam 2"), director.usedCameras());
        director.framing("Iriun Webcam", false); // Program on the main camera's wide shot
        director.selectShot(drums);
        director.take();
        now += 500;
        VirtualCameras.Framing f = director.framing("Iriun Webcam", false);
        assertEquals("Cam 2", f.camera(), "the scene's camera source now shows the other camera");
        assertEquals("Iriun Webcam", f.fromCamera(), "the old camera fades out under it");
        assertNotNull(f.from(), "no pan across two cameras: a fade");
        now += 600;
        f = director.framing("Iriun Webcam", false);
        assertEquals("Cam 2", f.camera());
        assertNull(f.from());
        assertEquals("Cam 2", director.status().programCamera());
        // back to a shot of the main camera
        director.takeShot(0);
        assertEquals("Iriun Webcam", director.framing("Iriun Webcam", false).camera());
    }

    @Test
    void oneCameraSetupKeepsEveryShotOnTheMainCamera() {
        List<VirtualShot> list = new ArrayList<>(director.shots());
        director.setShotCamera(list.get(1), "Cam 2"); // remembered for when Multiple cameras is chosen
        assertFalse(director.multiCamera(), "one camera is the default");
        assertEquals(List.of("Iriun Webcam"), director.usedCameras());
        director.takeShot(1);
        assertEquals("Iriun Webcam", director.framing("Iriun Webcam", false).camera());
        director.setMultiCamera(true);
        assertEquals(List.of("Iriun Webcam", "Cam 2"), director.usedCameras());
        assertEquals("Cam 2", director.cameraOf(director.shots().get(1)));
    }

    @Test
    void eachCameraHasItsOwnPeople() {
        see(); // the main camera sees three people
        director.analyze("Cam 2", new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB));
        runAll();
        assertEquals(3, director.people("Iriun Webcam").size());
        assertTrue(director.people("Cam 2").size() <= 1, "Cam 2 has had only one look so far");
        assertNotSame(director.tracker("Iriun Webcam"), director.tracker("Cam 2"));
    }

    @Test
    void eachCameraSourceChoosesWhetherTheDirectorControlsIt() {
        director.selectShot(1);
        director.take();
        assertNotNull(director.framing("Iriun Webcam", null, false), "a box of the main camera: controlled by default");
        assertNull(director.framing("Iriun Webcam", Boolean.FALSE, false), "a plain picture-in-picture opts out");
        assertNotNull(director.framing("Integrated Camera", Boolean.TRUE, false), "an Auto Director source is always controlled");
        assertNull(director.framing("Integrated Camera", null, false));
        assertNull(VirtualCameras.framing("Iriun Webcam", Boolean.FALSE, false));
    }

    @Test
    void modeToggles() {
        director.toggleAssisted();
        assertEquals(AutoDirector.Mode.ASSISTED, director.mode());
        director.toggleAssisted();
        assertEquals(AutoDirector.Mode.MANUAL, director.mode());
        director.toggleAuto();
        assertEquals(AutoDirector.Mode.AUTO, director.mode());
        director.toggleAssisted();
        assertEquals(AutoDirector.Mode.ASSISTED, director.mode());
    }

    @Test
    void shotsArePersistedAcrossRestarts() {
        List<VirtualShot> list = new ArrayList<>(director.shots());
        VirtualShot dup = list.get(1).duplicate();
        dup.name = "Lead close-up";
        dup.zoom = 2.0;
        list.add(dup);
        director.setShots(list);
        AppConfig again = new AppConfig(new AppPaths(dir));
        again.load();
        List<VirtualShot> reloaded = new DirectorSettings(again).shots();
        assertEquals(6, reloaded.size());
        assertEquals("Lead close-up", reloaded.get(5).name);
        assertEquals(2.0, reloaded.get(5).zoom);
        assertEquals(Role.LEAD_SINGER, reloaded.get(5).role);
    }

    @Test
    void theModeIsNeverRestoredAfterARestart() {
        director.setMode(AutoDirector.Mode.AUTO);
        AppConfig again = new AppConfig(new AppPaths(dir));
        again.load();
        AutoDirector fresh = new AutoDirector(new DirectorSettings(again), () -> detector, uiQueue::add, () -> now,
                () -> List.of("Iriun Webcam"), new StepExecutor());
        assertEquals(AutoDirector.Mode.MANUAL, fresh.mode());
        assertFalse(fresh.enabled(), "the Auto Director is always off when LiveCat opens");
    }
}
