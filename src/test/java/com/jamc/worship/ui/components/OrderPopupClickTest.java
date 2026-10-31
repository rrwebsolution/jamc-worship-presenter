package com.jamc.worship.ui.components;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import org.junit.jupiter.api.Test;

import javax.swing.JList;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lyrics → ORDER: one click on a card selects it exactly once (one Preview change, one transition). */
class OrderPopupClickTest {

    @Test
    void oneClickIsOneSelection() throws Exception {
        SlideThumbnailStrip strip = new SlideThumbnailStrip();
        strip.setSlides(List.of(new Slide("Verse 1", "AMAZING GRACE\nHOW SWEET THE SOUND", null),
                new Slide("Verse 1", "THAT SAVED A WRETCH\nLIKE ME", null),
                new Slide("Verse 2", "I ONCE WAS LOST\nBUT NOW AM FOUND", null)), SlideStyle.fallback());
        List<Integer> selected = new ArrayList<>();
        ActionOrderPanel order = new ActionOrderPanel(strip, new ActionOrderPanel.Host() {
            @Override public void select(int i) { selected.add(i); strip.select(i); }
            @Override public void take(int i) { }
            @Override public boolean doubleClickTakes() { return false; }
            @Override public boolean canEditOrder() { return true; }
            @Override public void applyOrder(List<List<Slide>> entries) { }
            @Override public boolean canResetOrder() { return false; }
            @Override public void resetOrder() { }
        });
        order.setSize(420, 700);
        layout(order);
        var f = ActionOrderPanel.class.getDeclaredField("list");
        f.setAccessible(true);
        JList<?> list = (JList<?>) f.get(order);

        click(list, 1);
        assertEquals(List.of(1), selected, "the first click on another card selects it once");
        assertEquals(1, strip.getSelectedIndex());

        Thread.sleep(20);
        click(list, 2);
        assertEquals(List.of(1, 2), selected, "every click: exactly one selection");

        Thread.sleep(20);
        click(list, 2);
        assertEquals(List.of(1, 2, 2), selected, "clicking the PREVIEW card again still counts once");
    }

    /** On the Swing thread, after everything queued there (the panel updates its list there). */
    private static void click(JList<?> list, int row) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> clickNow(list, row));
    }

    private static void clickNow(JList<?> list, int row) {
        list.doLayout();
        Rectangle r = list.getCellBounds(row, row);
        int x = r.x + 20;
        int y = r.y + r.height / 2;
        long now = System.currentTimeMillis();
        list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, now + 1, 0, x, y, 1, false, MouseEvent.BUTTON1));
        list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, now + 1, 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component child : k.getComponents()) {
                layout(child);
            }
        }
    }
}
