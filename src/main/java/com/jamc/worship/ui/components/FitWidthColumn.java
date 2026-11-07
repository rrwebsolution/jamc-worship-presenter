package com.jamc.worship.ui.components;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.Arrays;

/**
 * A page of cards (Settings, Stream) in a vertical scroll pane: exactly as wide as the window, laptop or PC, so the cards
 * fill it and nothing ever scrolls sideways. A long note that would make the page wider is wrapped onto several lines
 * instead (one Bible note was 1,600 px on one line and pushed the whole Settings page sideways).
 */
public final class FitWidthColumn extends JPanel implements Scrollable {

    /** Longer explanations wrap at about this many screen pixels. */
    public static final int TEXT_WIDTH = 760;

    private final JComponent content;

    private FitWidthColumn(JComponent content) {
        super(null);
        this.content = content;
        setOpaque(false);
        add(content);
    }

    /** {@code content} in a scroll pane that scrolls up and down only. */
    public static JScrollPane scroll(JComponent content) {
        wrapLongText(content, TEXT_WIDTH);
        JScrollPane scroll = new JScrollPane(new FitWidthColumn(content), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
    }

    /**
     * Wraps every label wider than {@code width} screen pixels: a long note (plain or HTML) becomes several lines. Safe to
     * run again, so text set later (a camera list, the FFmpeg status) is wrapped on the next layout. A label that cannot
     * wrap (a long path with no spaces) keeps one line, cut with "…", and shows the whole text on hover.
     */
    public static void wrapLongText(Component c, int width) {
        if (c instanceof JLabel l && l.getText() != null && l.getIcon() == null && l.getPreferredSize().width > width) {
            String t = l.getText();
            // Swing draws HTML "px" about a third wider than screen pixels
            String css = "width:" + Math.round(width / 1.3) + "px";
            if (Arrays.stream(t.replaceAll("<[^>]*>", " ").split("\\s+")).anyMatch(w -> w.length() > 60)) {
                l.setToolTipText(t.replaceAll("<[^>]*>", ""));
                l.setMinimumSize(new Dimension(40, l.getPreferredSize().height));
                l.setPreferredSize(new Dimension(width, l.getPreferredSize().height));
            } else if (t.matches("(?s).*width:\\s*\\d+px.*")) {
                l.setText(t.replaceFirst("width:\\s*\\d+px", css));
            } else {
                String inner = t.startsWith("<html>") ? t.substring(6).replaceFirst("</html>\\s*$", "")
                        : t.replace("&", "&amp;").replace("<", "&lt;");
                l.setText("<html><div style='" + css + "'>" + inner + "</div></html>");
            }
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                wrapLongText(child, width);
            }
        }
    }

    @Override
    public void doLayout() {
        wrapLongText(content, TEXT_WIDTH); // also text that changed since
        content.setBounds(0, 0, getWidth() > 0 ? getWidth() : content.getPreferredSize().width,
                content.getPreferredSize().height);
    }

    @Override
    public Dimension getPreferredSize() {
        return content.getPreferredSize();
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
        return Math.max(24, r.height - 48);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
