package com.jamc.worship.ui;

import com.jamc.worship.ui.components.Sidebar;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The dynamic Library workspace under the library list and the Preview / Program monitors. Exactly
 * one workspace is visible, and it always belongs to the selected Library category: Songs, Bible and
 * Presentations show their slide cards; Images, Videos, Audio and Backgrounds the MEDIA PREVIEW
 * monitor; Camera the camera preview and controls. Switching replaces the whole view (CardLayout);
 * the hidden workspaces stay alive and keep their selection for a fast return. The output monitors,
 * scenes, background and stream are outside this container and are never touched.
 */
public final class LibraryWorkspaceController {

    /** The workspace views; several categories can share one (e.g. all media kinds use MEDIA). */
    public enum Workspace { SLIDES, MEDIA, CAMERA, THEME, LOWER_THIRD }

    private final CardLayout cards = new CardLayout();
    private final JPanel container = Ui.transparent(cards);
    private final Map<Workspace, JComponent> views = new EnumMap<>(Workspace.class);
    private Sidebar.Category currentCategory;
    private Workspace current;
    private Consumer<Workspace> listener = w -> { };

    public JComponent container() {
        return container;
    }

    public void add(Workspace w, JComponent view) {
        views.put(w, view);
        container.add(view, w.name());
    }

    public void setListener(Consumer<Workspace> l) {
        listener = l;
    }

    /** Which workspace a Library category shows. */
    public static Workspace workspaceFor(Sidebar.Category c) {
        return switch (c) {
            case SONGS, FAVORITES, BIBLE, PRESENTATIONS, SLIDES -> Workspace.SLIDES;
            case IMAGES, VIDEOS, AUDIO, BACKGROUNDS, MEDIA -> Workspace.MEDIA;
            case CAMERA -> Workspace.CAMERA;
            case THEMES -> Workspace.THEME;
            case LOWER_THIRDS -> Workspace.LOWER_THIRD;
        };
    }

    /** The operator chose a Library category: its workspace replaces whatever was shown. */
    public void select(Sidebar.Category category) {
        currentCategory = category;
        show(workspaceFor(category));
    }

    /** Shows a workspace directly (e.g. a service item opens its slides). */
    public void show(Workspace w) {
        current = w;
        cards.show(container, w.name());
        listener.accept(w);
    }

    public Sidebar.Category currentCategory() {
        return currentCategory;
    }

    public Workspace current() {
        return current;
    }

    /** The view that is on screen now (for tests). */
    public JComponent visibleView() {
        return current == null ? null : views.get(current);
    }
}
