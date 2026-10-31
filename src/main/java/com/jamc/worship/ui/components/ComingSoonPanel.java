package com.jamc.worship.ui.components;

import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;

/** Friendly placeholder for modules scheduled for a later development phase. */
public class ComingSoonPanel extends JPanel {

    public ComingSoonPanel(String icon, String title, String description, String phase) {
        super(new GridBagLayout());
        setOpaque(false);

        JPanel box = Ui.transparent(new BorderLayout(0, 10));
        JLabel iconLabel = new JLabel(Icons.get(icon, 40, Palette::mutedText));
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel titleLabel = Ui.label(title, Palette::text, "h3");
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel text = Ui.label("<html><div style='text-align:center;width:240px'>" + description
                + "<br><br><b>Planned for " + phase + "</b></div></html>", Palette::secondaryText, null);
        text.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel titles = Ui.transparent(new BorderLayout(0, 6));
        titles.add(titleLabel, BorderLayout.NORTH);
        titles.add(text, BorderLayout.CENTER);
        box.add(iconLabel, BorderLayout.NORTH);
        box.add(titles, BorderLayout.CENTER);
        add(box);
    }
}
