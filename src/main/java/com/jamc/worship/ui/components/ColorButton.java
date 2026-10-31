package com.jamc.worship.ui.components;

import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.util.Colors;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Button showing a color swatch and its hex code; click opens a color chooser. */
public class ColorButton extends JButton {

    private Color color;
    private final List<Consumer<Color>> listeners = new ArrayList<>();

    public ColorButton(String title, Color initial) {
        this.color = initial;
        setIcon(new Swatch());
        setIconTextGap(8);
        setHorizontalAlignment(LEFT);
        updateText();
        addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(this, title, color);
            if (chosen != null && !chosen.equals(color)) {
                setColor(chosen);
                listeners.forEach(l -> l.accept(chosen));
            }
        });
    }

    public Color getColor() {
        return color;
    }

    public String getHex() {
        return Colors.toHex(color);
    }

    /** Sets the color without notifying listeners. */
    public void setColor(Color color) {
        this.color = color;
        updateText();
        repaint();
    }

    public void addColorListener(Consumer<Color> listener) {
        listeners.add(listener);
    }

    private void updateText() {
        setText(Colors.toHex(color));
    }

    private final class Swatch implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(x, y, getIconWidth(), getIconHeight(), 5, 5);
            g2.setColor(Palette.border());
            g2.drawRoundRect(x, y, getIconWidth() - 1, getIconHeight() - 1, 5, 5);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }
    }
}
