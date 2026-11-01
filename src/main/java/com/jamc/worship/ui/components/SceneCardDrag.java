package com.jamc.worship.ui.components;

import com.jamc.worship.model.Scene;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Drag a scene card to another place in its grid (Scenes page, Scenes list beside the Library). The card moves
 * live while dragging; on release the new order is handed to {@code reordered} to be saved. A small movement
 * is still a click, so selecting, double-click (Preview) and right-click work as before. Reordering never
 * changes Preview or Program.
 */
final class SceneCardDrag extends MouseAdapter {

    /** Pixels the mouse must move before a press becomes a drag. */
    private static final int THRESHOLD = 6;

    private final JComponent card;
    private final JPanel grid;
    private final Function<Component, Scene> sceneOf;
    private final Consumer<List<Scene>> reordered;
    private Point start;
    private boolean dragging;
    private int startIndex;

    private SceneCardDrag(JComponent card, JPanel grid, Function<Component, Scene> sceneOf, Consumer<List<Scene>> reordered) {
        this.card = card;
        this.grid = grid;
        this.sceneOf = sceneOf;
        this.reordered = reordered;
    }

    /** Makes {@code card} draggable inside {@code grid}. */
    static void install(JComponent card, JPanel grid, Function<Component, Scene> sceneOf, Consumer<List<Scene>> reordered) {
        SceneCardDrag d = new SceneCardDrag(card, grid, sceneOf, reordered);
        card.addMouseListener(d);
        card.addMouseMotionListener(d);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        start = SwingUtilities.isLeftMouseButton(e) ? e.getPoint() : null;
        dragging = false;
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (start == null) {
            return;
        }
        if (!dragging) {
            if (start.distance(e.getPoint()) < THRESHOLD) {
                return;
            }
            dragging = true;
            startIndex = indexOf(card);
            card.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        }
        Point p = SwingUtilities.convertPoint(card, e.getPoint(), grid);
        Component over = grid.getComponentAt(p);
        if (over == null || over == card || over == grid) {
            return;
        }
        int target = indexOf(over);
        if (target >= 0) {
            grid.remove(card); // the card takes the place of the one under the mouse; the others move along
            grid.add(card, target);
            grid.revalidate();
            grid.repaint();
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        boolean moved = dragging && indexOf(card) != startIndex;
        if (dragging) {
            card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        dragging = false;
        start = null;
        if (moved) {
            List<Scene> order = new ArrayList<>();
            for (Component c : grid.getComponents()) {
                Scene s = sceneOf.apply(c);
                if (s != null) {
                    order.add(s);
                }
            }
            reordered.accept(order);
        }
    }

    private int indexOf(Component c) {
        Component[] all = grid.getComponents();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
