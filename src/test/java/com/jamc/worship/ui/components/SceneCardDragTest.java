package com.jamc.worship.ui.components;

import com.jamc.worship.model.Scene;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Dragging a scene card to another place saves the new order; a click without moving does not. */
class SceneCardDragTest {

    private static final class Card extends JComponent {
        final Scene scene;

        Card(Scene scene) {
            this.scene = scene;
        }
    }

    private final JPanel grid = new JPanel(new GridLayout(0, 2, 0, 0));
    private final List<Card> cards = new ArrayList<>();
    private final AtomicReference<List<Scene>> saved = new AtomicReference<>();

    SceneCardDragTest() {
        for (String name : List.of("Worship", "Camera", "Bible", "Sermon")) {
            Card c = new Card(new Scene((long) cards.size() + 1, name));
            SceneCardDrag.install(c, grid, x -> x instanceof Card k ? k.scene : null, saved::set);
            cards.add(c);
            grid.add(c);
        }
        grid.setSize(200, 200); // 2 × 2 cards of 100 × 100
        grid.doLayout();
    }

    private static void send(Card card, int id, Point p, int modifiers) {
        card.dispatchEvent(new MouseEvent(card, id, System.currentTimeMillis(), modifiers, p.x, p.y, 1, false,
                MouseEvent.BUTTON1));
    }

    private List<String> names(List<Scene> scenes) {
        return scenes.stream().map(Scene::getName).toList();
    }

    @Test
    void draggingACardOntoAnotherMovesItThere() {
        Card worship = cards.getFirst();
        send(worship, MouseEvent.MOUSE_PRESSED, new Point(50, 50), InputEvent.BUTTON1_DOWN_MASK);
        // drag to the middle of "Sermon" (bottom right: grid point 150,150 = card point 150,150)
        send(worship, MouseEvent.MOUSE_DRAGGED, new Point(60, 60), InputEvent.BUTTON1_DOWN_MASK);
        send(worship, MouseEvent.MOUSE_DRAGGED, new Point(150, 150), InputEvent.BUTTON1_DOWN_MASK);
        send(worship, MouseEvent.MOUSE_RELEASED, new Point(150, 150), 0);
        assertEquals(List.of("Camera", "Bible", "Sermon", "Worship"), names(saved.get()));
    }

    @Test
    void aClickWithoutMovingKeepsTheOrder() {
        Card camera = cards.get(1);
        send(camera, MouseEvent.MOUSE_PRESSED, new Point(50, 50), InputEvent.BUTTON1_DOWN_MASK);
        send(camera, MouseEvent.MOUSE_DRAGGED, new Point(52, 51), InputEvent.BUTTON1_DOWN_MASK);
        send(camera, MouseEvent.MOUSE_RELEASED, new Point(52, 51), 0);
        assertNull(saved.get(), "a small movement is still a click: nothing is reordered");
    }
}
