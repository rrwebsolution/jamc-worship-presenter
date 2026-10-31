package com.jamc.worship.ui;

import com.jamc.worship.ui.LibraryWorkspaceController.Workspace;
import com.jamc.worship.ui.components.Sidebar.Category;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workspace beside the monitors always belongs to the selected Library category, and only one is shown. */
class LibraryWorkspaceControllerTest {

    @Test
    void everyCategoryHasItsOwnWorkspace() {
        assertEquals(Workspace.SLIDES, LibraryWorkspaceController.workspaceFor(Category.SONGS));
        assertEquals(Workspace.SLIDES, LibraryWorkspaceController.workspaceFor(Category.BIBLE));
        assertEquals(Workspace.SLIDES, LibraryWorkspaceController.workspaceFor(Category.PRESENTATIONS));
        for (Category media : List.of(Category.IMAGES, Category.VIDEOS, Category.AUDIO, Category.BACKGROUNDS)) {
            assertEquals(Workspace.MEDIA, LibraryWorkspaceController.workspaceFor(media), media.name());
        }
        assertEquals(Workspace.CAMERA, LibraryWorkspaceController.workspaceFor(Category.CAMERA));
        for (Category c : Category.values()) {
            LibraryWorkspaceController.workspaceFor(c); // every category is mapped
        }
    }

    @Test
    void switchingCategoriesReplacesTheWholeWorkspace() {
        LibraryWorkspaceController controller = new LibraryWorkspaceController();
        Map<Workspace, JComponent> views = new EnumMap<>(Workspace.class);
        for (Workspace w : Workspace.values()) {
            JPanel view = new JPanel();
            view.setName(w.name());
            views.put(w, view);
            controller.add(w, view);
        }
        // the required manual test, step by step
        List<Category> clicks = List.of(Category.SONGS, Category.VIDEOS, Category.AUDIO, Category.IMAGES, Category.BACKGROUNDS,
                Category.PRESENTATIONS, Category.BIBLE, Category.CAMERA, Category.SONGS);
        for (Category c : clicks) {
            controller.select(c);
            Workspace expected = LibraryWorkspaceController.workspaceFor(c);
            assertEquals(c, controller.currentCategory());
            assertSame(views.get(expected), controller.visibleView());
            int shown = 0;
            for (Component child : controller.container().getComponents()) {
                if (child.isVisible()) {
                    shown++;
                    assertSame(views.get(expected), child, "after " + c + " only its workspace is visible");
                }
            }
            assertEquals(1, shown, "exactly one workspace after " + c);
        }
        assertTrue(views.get(Workspace.SLIDES).isVisible(), "back on Songs, the song workspace (kept alive) is shown again");
    }
}
