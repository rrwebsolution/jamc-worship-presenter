package com.jamc.worship.ui;

import com.jamc.worship.camera.CameraManager;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
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

import javax.swing.DefaultListModel;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GROUP layers in the scene editor (as in OBS): moving, resizing and hiding the group takes its layers along. */
class SceneGroupTest {

    @TempDir
    Path tempDir;

    private static Source box(String name, double x, double y, double w, double h, String group) {
        Source s = new Source(name, SourceType.COLOR);
        SourceConfig c = s.getConfig();
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        c.groupId = group;
        return s;
    }

    @Test
    void aGroupMovesResizesAndHidesItsLayers() throws Exception {
        AppPaths paths = new AppPaths(tempDir.resolve("root"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        SceneService scenes = new SceneService(new SceneRepository(db), new LowerThirdRepository(db));
        MediaService media = new MediaService(new MediaRepository(db), paths);
        Scene scene = scenes.save(scenes.create("Grouped"));
        ScenesPanel panel = onEdt(() -> new ScenesPanel(scenes, new ProgramOutput(), new CameraManager(name -> null), file -> null, null, media));
        onEdt(() -> {
            panel.edit(scene.getId());
            return null;
        });
        long end = System.currentTimeMillis() + 10_000;
        while (onEdt(() -> field(panel, "editing")) == null && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }

        Source a = box("A", 0.1, 0.1, 0.2, 0.2, "g");
        Source b = box("B", 0.5, 0.4, 0.2, 0.3, "g");
        Source alone = box("Alone", 0.0, 0.8, 0.1, 0.1, null);
        Source group = new Source("Group", SourceType.GROUP);
        group.getConfig().uid = "g";
        onEdt(() -> {
            @SuppressWarnings("unchecked")
            DefaultListModel<Source> model = (DefaultListModel<Source>) field(panel, "sourceModel");
            model.addElement(group);
            model.addElement(a);
            model.addElement(b);
            model.addElement(alone);
            invoke(panel, "sceneEdited");
            return null;
        });
        SourceConfig g = group.getConfig();
        assertEquals(0.1, g.x, 1e-9, "the group's box is the box around its layers");
        assertEquals(0.1, g.y, 1e-9);
        assertEquals(0.6, g.w, 1e-9);
        assertEquals(0.6, g.h, 1e-9);

        // drag the group by (+0.2, +0.1): both layers move, the other one stays
        onEdt(() -> {
            g.x += 0.2;
            g.y += 0.1;
            invoke(panel, "sceneEdited");
            return null;
        });
        assertEquals(0.3, a.getConfig().x, 1e-9);
        assertEquals(0.2, a.getConfig().y, 1e-9);
        assertEquals(0.7, b.getConfig().x, 1e-9);
        assertEquals(0.0, alone.getConfig().x, 1e-9, "a layer outside the group stays");

        // make the group half as wide: its layers shrink and keep their places in it
        onEdt(() -> {
            g.w = 0.3;
            invoke(panel, "sceneEdited");
            return null;
        });
        assertEquals(0.1, a.getConfig().w, 1e-9);
        assertEquals(0.5, b.getConfig().x, 1e-9, "0.3 + (0.7 - 0.3) × 0.5");

        // move one layer alone: the group's box follows it
        onEdt(() -> {
            a.getConfig().x = 0.0;
            invoke(panel, "sceneEdited");
            return null;
        });
        assertEquals(0.0, g.x, 1e-9);
        assertEquals(0.5, b.getConfig().x, 1e-9, "the other layer did not move");

        // hide the group: its layers hide; show it: they show
        onEdt(() -> {
            group.setVisible(false);
            invoke(panel, "sceneEdited");
            return null;
        });
        assertFalse(a.isVisible());
        assertFalse(b.isVisible());
        assertTrue(alone.isVisible());
        onEdt(() -> {
            group.setVisible(true);
            invoke(panel, "sceneEdited");
            return null;
        });
        assertTrue(a.isVisible() && b.isVisible());

        // remove the group: its layers stay, on their own
        onEdt(() -> {
            javax.swing.JList<?> list = (javax.swing.JList<?>) field(panel, "sourceList");
            @SuppressWarnings("unchecked")
            DefaultListModel<Source> model = (DefaultListModel<Source>) field(panel, "sourceModel");
            list.setSelectedIndex(model.indexOf(group)); // a new scene starts with its own layers above
            invoke(panel, "removeSource");
            assertFalse(model.contains(group));
            assertTrue(model.contains(a) && model.contains(b));
            return null;
        });
        assertNull(a.getConfig().groupId);
        assertNull(b.getConfig().groupId);
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

    private static void invoke(ScenesPanel panel, String method) {
        try {
            var m = ScenesPanel.class.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(panel);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
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
