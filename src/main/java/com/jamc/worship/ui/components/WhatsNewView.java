package com.jamc.worship.ui.components;

import com.jamc.worship.update.WhatsNew;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;

/** Shows a version's "What's new" list (new features and fixes) in a dialog. */
public final class WhatsNewView {

    private WhatsNewView() {
    }

    /** {@code intro} on top, then the scrollable list; no list when {@code notes} is empty. */
    public static JComponent panel(String intro, String notes) {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setOpaque(false);
        if (intro != null) {
            p.add(Ui.label("<html><body style='width:440px'>" + intro + "</body></html>", Palette::text, null), BorderLayout.NORTH);
        }
        if (notes != null && !notes.isBlank()) {
            JEditorPane pane = new JEditorPane("text/html", "");
            pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            pane.setEditable(false);
            pane.setFont(Ui.label("", null, null).getFont());
            pane.setBackground(Palette.windowBackground());
            pane.setForeground(Palette.text());
            pane.setText("<html><head><style>"
                    + "body{margin:6px 10px;color:" + hex(Palette.text()) + "}"
                    + "h3{margin:8px 0 2px 0;color:" + hex(Palette.accent()) + "}"
                    + "ul{margin:2px 0 2px 18px} li{margin:3px 0} p{margin:4px 0}"
                    + "code{font-family:monospaced}"
                    + "</style></head><body>" + WhatsNew.toHtml(notes) + "</body></html>");
            pane.setCaretPosition(0);
            JScrollPane scroll = new JScrollPane(pane);
            scroll.setBorder(BorderFactory.createLineBorder(Palette.border()));
            scroll.setPreferredSize(new Dimension(500, 280));
            JPanel box = new JPanel(new BorderLayout(0, 4));
            box.setOpaque(false);
            box.add(Ui.caption("What's new"), BorderLayout.NORTH);
            box.add(scroll, BorderLayout.CENTER);
            p.add(box, BorderLayout.CENTER);
        }
        return p;
    }

    /** The "What's new in version …" window (after an update, or Help → What's New). */
    public static void show(Component parent, String appName, String version, String notes) {
        String intro = notes == null || notes.isBlank()
                ? "No list of changes was included with " + WhatsNew.label(version) + "."
                : "<b>" + escape(appName) + "</b> was updated to " + WhatsNew.label(version)
                + ". These are the new features and fixes:";
        JOptionPane.showMessageDialog(parent, panel(intro, notes), "What's new in " + escape(appName),
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
