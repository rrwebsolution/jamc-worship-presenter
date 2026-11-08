package com.jamc.worship.ui;

import com.jamc.worship.ui.theme.Palette;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;

/**
 * Line numbers down the left side of a text area (as a scroll pane's row header). Each written line gets one
 * number, also when a long line wraps onto several rows; the line with the caret is highlighted.
 */
public final class LineNumberGutter extends JComponent {

    private final JTextArea area;
    /** Text → the number to show on each line (0 = none); null = every line numbered 1, 2, 3… */
    private final java.util.function.Function<String, int[]> numbering;
    private int[] numbers = new int[0];
    private int largest;

    private LineNumberGutter(JTextArea area, java.util.function.Function<String, int[]> numbering) {
        this.area = area;
        this.numbering = numbering;
        recount();
        setOpaque(true);
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });
        area.addCaretListener(e -> repaint());
        area.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                changed(); // wrapping moved the lines
            }
        });
    }

    /** Puts line numbers beside the text area in its scroll pane. */
    public static void install(JScrollPane scroll, JTextArea area) {
        scroll.setRowHeaderView(new LineNumberGutter(area, null));
    }

    /**
     * Numbers beside the text area chosen by {@code numbering} (e.g. one number per slide on the line where it starts,
     * as in EasyWorship).
     */
    public static void install(JScrollPane scroll, JTextArea area, java.util.function.Function<String, int[]> numbering) {
        scroll.setRowHeaderView(new LineNumberGutter(area, numbering));
    }

    private void recount() {
        Element root = area.getDocument().getDefaultRootElement();
        if (numbering == null) {
            numbers = null;
            largest = root.getElementCount();
            return;
        }
        numbers = numbering.apply(area.getText());
        largest = 0;
        for (int n : numbers) {
            largest = Math.max(largest, n);
        }
    }

    private void changed() {
        recount();
        revalidate();
        repaint();
    }

    private Font numberFont() {
        return area.getFont().deriveFont(Font.PLAIN, Math.max(10f, area.getFont().getSize2D() - 2f));
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(numberFont());
        int digits = Math.max(2, String.valueOf(largest).length());
        return new Dimension(fm.charWidth('0') * digits + 18, area.getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Palette.secondaryBackground());
            Rectangle clip = g.getClipBounds();
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            g.setColor(Palette.border());
            g.drawLine(getWidth() - 1, clip.y, getWidth() - 1, clip.y + clip.height);
            g.setFont(numberFont());
            FontMetrics fm = g.getFontMetrics();
            FontMetrics textFm = area.getFontMetrics(area.getFont());
            Element root = area.getDocument().getDefaultRootElement();
            int caretLine = root.getElementIndex(area.getCaretPosition());
            if (numbers != null) {
                // slide numbers: the slide the caret is in lights up (its number is on the line where it starts)
                int at = Math.min(caretLine, numbers.length - 1);
                while (at > 0 && numbers[at] == 0) {
                    at--;
                }
                caretLine = at;
            }
            for (int i = 0; i < root.getElementCount(); i++) {
                Rectangle2D r;
                try {
                    r = area.modelToView2D(root.getElement(i).getStartOffset());
                } catch (BadLocationException e) {
                    continue;
                }
                if (r == null) {
                    continue;
                }
                int top = (int) r.getY();
                if (top > clip.y + clip.height) {
                    break;
                }
                if (top + r.getHeight() < clip.y) {
                    continue;
                }
                int number = numbers == null ? i + 1 : i < numbers.length ? numbers[i] : 0;
                if (number <= 0) {
                    continue; // not where a slide starts
                }
                String n = String.valueOf(number);
                // on the baseline of the line's first row
                int baseline = top + textFm.getAscent();
                g.setColor(i == caretLine ? Palette.text() : Palette.mutedText());
                g.drawString(n, getWidth() - 9 - fm.stringWidth(n), baseline);
            }
        } finally {
            g.dispose();
        }
    }
}
