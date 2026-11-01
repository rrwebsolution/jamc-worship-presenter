package com.jamc.worship.ui.components;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Long YouTube titles stay inside their card: two lines at most, "…" after that. */
class YouTubeTitleClampTest {

    private static FontMetrics metrics() {
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().getFontMetrics(new Font("Dialog", Font.BOLD, 13));
    }

    @Test
    void aLongTitleStopsAtTwoLinesWithAnEllipsis() {
        String title = "Goodness of God (Live) | Bethel Music & Jenn Johnson | Official Music Video with Lyrics 4K Worship Night 2026";
        String html = YouTubeSearchPanel.clampLines(title, metrics(), 200, 2);
        String[] lines = html.replace("<html>", "").replace("</html>", "").split("<br>");
        assertEquals(2, lines.length);
        assertTrue(lines[1].endsWith("…"), html);
        FontMetrics fm = metrics();
        for (String l : lines) {
            assertTrue(fm.stringWidth(l.replace("&amp;", "&")) <= 200, "fits the card: " + l);
        }
    }

    @Test
    void aShortTitleIsUnchanged() {
        String html = YouTubeSearchPanel.clampLines("Way Maker", metrics(), 200, 2);
        assertEquals("<html>Way Maker</html>", html);
        assertFalse(html.contains("…"));
    }
}
