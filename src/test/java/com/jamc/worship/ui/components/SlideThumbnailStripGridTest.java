package com.jamc.worship.ui.components;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Library lyrics cards: Cards = one per row, Grid = several per row, the same cards and selection. */
class SlideThumbnailStripGridTest {

    @Test
    void gridPutsSeveralLyricsCardsOnARowAndCardsOne() throws Exception {
        AtomicReference<int[]> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SlideThumbnailStrip strip = new SlideThumbnailStrip();
            List<Slide> slides = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                slides.add(new Slide("Verse " + i, "Amazing grace\nhow sweet the sound " + i, null));
            }
            strip.setCardMode(true, "card");
            strip.setSlides(slides, SlideStyle.fallback());
            strip.setSize(1000, 700);
            layout(strip);
            int cardsColumns = columns(strip);
            int wordCards = count(strip, "LyricsCard");

            List<Boolean> heard = new ArrayList<>();
            strip.addViewListener(heard::add);
            strip.setGridView(true); // restoring the saved choice: listeners are not told
            layout(strip);
            int gridColumns = columns(strip);
            int gridWordCards = count(strip, "LyricsCard");
            int gridPictures = count(strip, "SlidePreviewCanvas");
            int selected = strip.getSelectedIndex();

            strip.setCardMode(false, "page"); // page thumbnails keep their single list
            layout(strip);
            int pageColumns = columns(strip);
            result.set(new int[]{cardsColumns, gridColumns, selected, pageColumns, heard.size(), wordCards,
                    gridWordCards, gridPictures});
        });
        int[] r = result.get();
        assertEquals(6, r[5], "Cards show the words");
        assertEquals(0, r[6], "Grid shows no word cards");
        assertEquals(6, r[7], "Grid shows each slide as a picture");
        assertEquals(1, r[0], "Cards: one card per row");
        assertTrue(r[1] >= 3, "Grid: several cards per row at 1000 px, got " + r[1]);
        assertEquals(0, r[2], "the selection is kept");
        assertEquals(1, r[3], "pages are not put in a grid");
        assertEquals(0, r[4]);
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container cc) {
                layout(cc);
            }
        }
    }

    /** Components of a class (by simple name) shown in the strip. */
    private static int count(Container c, String simpleName) {
        int n = 0;
        for (Component child : c.getComponents()) {
            if (child.getClass().getSimpleName().equals(simpleName) && child.getParent().isVisible()
                    && child.getParent().getComponentZOrder(child) >= 0 && isInTile(child)) {
                n++;
            }
            if (child instanceof Container cc) {
                n += count(cc, simpleName);
            }
        }
        return n;
    }

    /** Only what a tile actually shows (each tile also keeps a hidden canvas for its LIVE frame in Cards view). */
    private static boolean isInTile(Component c) {
        return c.getParent() != null && c.getParent().getClass().getSimpleName().equals("Tile");
    }

    /** Distinct x positions of the cards = the number of columns. */
    private static int columns(Container strip) {
        Set<Integer> xs = new TreeSet<>();
        collect(strip, xs);
        return xs.size();
    }

    private static void collect(Container c, Set<Integer> xs) {
        for (Component child : c.getComponents()) {
            if (child.getClass().getSimpleName().equals("Tile")) {
                xs.add(child.getX());
            } else if (child instanceof Container cc) {
                collect(cc, xs);
            }
        }
    }
}
