package com.jamc.worship.ui.components;

import org.junit.jupiter.api.Test;

import javax.swing.JToolTip;

import static org.junit.jupiter.api.Assertions.*;

/** Add Source tooltips are narrow boxes: long text wraps onto more lines instead of one wide line. */
class AddSourceTooltipTest {

    private static java.awt.Dimension size(String text) {
        JToolTip tip = new JToolTip();
        tip.setTipText(text);
        return tip.getPreferredSize();
    }

    @Test
    void longTooltipsWrapIntoANarrowBox() {
        String text = "Several videos as one source (like OBS VLC Video): Autoplay plays the next one when one ends; loop, shuffle";
        java.awt.Dimension wide = size(text);
        java.awt.Dimension narrow = size(ScenesPanel.narrowTooltip(text));
        System.out.println("tooltip one line " + wide + " -> narrow " + narrow);
        assertTrue(narrow.width <= ScenesPanel.TOOLTIP_WIDTH + 30, "narrow: " + narrow.width);
        assertTrue(narrow.height > wide.height * 2, "wrapped onto several lines");
    }

    @Test
    void htmlTooltipsKeepTheirLineBreaks() {
        String t = ScenesPanel.narrowTooltip("<html>On: the AI chooses.<br>Off: a plain camera.</html>");
        assertTrue(t.contains("<br>"));
        assertTrue(t.startsWith("<html><body style='width:"));
        assertEquals(1, t.split("<html>", -1).length - 1, "one html wrapper");
    }
}
