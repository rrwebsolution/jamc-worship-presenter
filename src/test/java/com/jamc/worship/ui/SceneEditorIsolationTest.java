package com.jamc.worship.ui;

import com.jamc.worship.camera.CameraManager;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.output.ProgramOutput;
import com.jamc.worship.repository.LowerThirdRepository;
import com.jamc.worship.repository.MediaRepository;
import com.jamc.worship.repository.SceneRepository;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.service.SceneService;
import com.jamc.worship.ui.components.ScenesPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The critical multi-camera regression: the scene editor is a third state next to Preview and Program. Editing
 * cameras in a scene (even the scene on Program) never changes Preview or Program; SAVE only updates the scene
 * library; the scene reaches Preview only when sent there, and Program only at TAKE.
 */
class SceneEditorIsolationTest {

    @TempDir
    Path tempDir;

    private static Scene cameraScene(SceneService scenes, String name, String camera) {
        Scene s = scenes.create(name);
        Source cam = new Source(camera, SourceType.CAMERA);
        cam.getConfig().camera = camera;
        s.getSources().add(cam);
        return scenes.save(s);
    }

    private static List<String> cameras(Scene s) {
        return s == null ? List.of() : s.getSources().stream().filter(x -> x.getType() == SourceType.CAMERA)
                .map(x -> x.getConfig().camera).toList();
    }

    @Test
    void editingAndSavingCamerasNeverChangesPreviewOrProgram() throws Exception {
        AppPaths paths = new AppPaths(tempDir.resolve("root"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        SceneService scenes = new SceneService(new SceneRepository(db), new LowerThirdRepository(db));
        MediaService media = new MediaService(new MediaRepository(db), paths);

        Scene main = cameraScene(scenes, "Main Camera", "MAIN");
        Scene preacher = cameraScene(scenes, "Preacher Camera", "PREACHER");
        Scene edited = cameraScene(scenes, "Worship + PiP", "MAIN");
        ProgramOutput program = new ProgramOutput();
        ProgramOutput preview = new ProgramOutput();
        onEdt(() -> {
            program.setScene(main, false);
            preview.setScene(preacher, false);
            return null;
        });

        ScenesPanel panel = onEdt(() -> new ScenesPanel(scenes, program, new CameraManager(name -> null), file -> null, null, media));
        panel.setPreviewChosen(s -> preview.setScene(s, false)); // double-click / SEND TO PREVIEW: Preview only
        onEdt(() -> panel.edit(edited.getId()));
        waitFor(() -> onEdt(() -> {
            Scene e = editing(panel);
            return e != null && e.getId().equals(edited.getId());
        }));

        // edit the scene: add the worship team camera (moved, resized, cropped) and the main camera as a PiP
        onEdt(() -> {
            Source worship = new Source("Worship Team", SourceType.CAMERA);
            worship.getConfig().camera = "WORSHIP";
            worship.getConfig().x = 0.1;
            worship.getConfig().w = 0.6;
            worship.getConfig().cropLeft = 0.1;
            worship.getConfig().fit = FitMode.FILL;
            insert(panel, worship);
            Source pip = new Source("Main PiP", SourceType.CAMERA);
            pip.getConfig().camera = "MAIN";
            pip.getConfig().x = 0.68;
            pip.getConfig().y = 0.64;
            pip.getConfig().w = 0.28;
            pip.getConfig().h = 0.28;
            insert(panel, pip);
            return null;
        });
        assertEquals(List.of("MAIN", "WORSHIP", "MAIN"), cameras(onEdt(() -> editing(panel))), "the editor shows the new composition");
        assertEquals(List.of("PREACHER"), cameras(preview.scene()), "Preview unchanged while editing");
        assertEquals(List.of("MAIN"), cameras(program.scene()), "Program unchanged while editing");
        assertTrue(panel.hasUnsavedChanges());
        assertEquals(List.of("MAIN"), cameras(scenes.scene(edited.getId()).orElseThrow()), "nothing is saved without SAVE");

        // SAVE: the scene library changes, Preview and Program do not
        onEdt(() -> {
            invoke(panel, "saveEditing");
            return null;
        });
        waitFor(() -> onEdt(() -> !panel.hasUnsavedChanges()));
        assertEquals(List.of("MAIN", "WORSHIP", "MAIN"), cameras(scenes.scene(edited.getId()).orElseThrow()));
        assertEquals(List.of("PREACHER"), cameras(preview.scene()), "Preview unchanged after SAVE");
        assertEquals(List.of("MAIN"), cameras(program.scene()), "Program unchanged after SAVE");

        // send the saved scene to Preview: Program still unchanged
        onEdt(() -> {
            JButton send = (JButton) field(panel, "previewButton");
            send.doClick();
            return null;
        });
        assertEquals(List.of("MAIN", "WORSHIP", "MAIN"), cameras(preview.scene()), "Preview shows the new multi-camera scene");
        assertEquals(List.of("MAIN"), cameras(program.scene()), "Program waits for TAKE");

        // TAKE
        onEdt(() -> {
            program.take(null, preview.scene());
            return null;
        });
        assertEquals(List.of("MAIN", "WORSHIP", "MAIN"), cameras(program.scene()), "TAKE puts the scene on Program");
        assertFalse(panel.hasUnsavedChanges());
    }

    /**
     * The scene on Program is edited (a presentation added) and saved: it is a different layout from Program's
     * copy, so sending it to Preview queues a scene change and TAKE puts the new version on Program.
     */
    @Test
    void theLiveSceneSavedWithChangesGoesLiveAgainThroughPreviewAndTake() {
        AppPaths paths = new AppPaths(tempDir.resolve("root2"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        SceneService scenes = new SceneService(new SceneRepository(db), new LowerThirdRepository(db));
        Scene sermon = cameraScene(scenes, "Sermon", "MAIN");
        ProgramOutput program = new ProgramOutput();
        program.setScene(sermon, false);
        assertTrue(MainFrame.sameLayout(program.scene(), scenes.scene(sermon.getId()).orElseThrow()),
                "the unchanged scene is already live");

        Scene draft = scenes.scene(sermon.getId()).orElseThrow().copy();
        Source slides = new Source("Presentation", SourceType.PRESENTATION);
        slides.getConfig().presentationId = 7L;
        slides.getConfig().slideIndex = 2;
        draft.getSources().addFirst(slides);
        Scene saved = scenes.save(draft);
        assertFalse(MainFrame.sameLayout(program.scene(), saved), "the saved changes are a scene change for TAKE");

        Scene moved = saved.copy();
        moved.getSources().getFirst().getConfig().x = 0.5;
        assertFalse(MainFrame.sameLayout(saved, moved), "moving a layer is a change too");

        program.take(null, saved);
        assertEquals(SourceType.PRESENTATION, program.scene().getSources().getFirst().getType(), "TAKE shows the new version");
        assertTrue(MainFrame.sameLayout(program.scene(), saved));
    }

    private static Scene editing(ScenesPanel panel) {
        return (Scene) field(panel, "editing");
    }

    private static Object field(Object o, String name) {
        try {
            var f = ScenesPanel.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void insert(ScenesPanel panel, Source s) {
        try {
            var m = ScenesPanel.class.getDeclaredMethod("insertSource", Source.class);
            m.setAccessible(true);
            m.invoke(panel, s);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void invoke(ScenesPanel panel, String method) {
        try {
            var m = ScenesPanel.class.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(panel);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(50);
        }
    }

    private static <T> T onEdt(Callable<T> c) {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    out.set(c.call());
                } catch (Exception e) {
                    err.set(e);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (err.get() != null) {
            throw new IllegalStateException(err.get());
        }
        return out.get();
    }
}
