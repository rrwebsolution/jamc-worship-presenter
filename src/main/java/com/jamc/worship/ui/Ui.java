package com.jamc.worship.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.ActionEvent;
import java.util.function.Supplier;

/**
 * Small factory for consistently styled components.
 *
 * <p>Colors come from {@link Palette} through overridden getters rather than setters, so every
 * component built here repaints correctly after a dark/light theme switch.
 */
public final class Ui {

    private Ui() {
    }

    /** Panel whose background is resolved from the palette on every paint. */
    public static JPanel panel(LayoutManager layout, Supplier<Color> background) {
        JPanel p = new JPanel(layout) {
            @Override
            public Color getBackground() {
                return background == null ? super.getBackground() : background.get();
            }
        };
        p.setOpaque(true);
        return p;
    }

    public static JPanel transparent(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        return p;
    }

    /** Label in the given palette color, optionally with a FlatLaf typography class (h2, small, semibold...). */
    public static JLabel label(String text, Supplier<Color> color, String styleClass) {
        JLabel l = new JLabel(text) {
            @Override
            public Color getForeground() {
                return color == null ? super.getForeground() : color.get();
            }
        };
        if (styleClass != null) {
            l.putClientProperty(FlatClientProperties.STYLE_CLASS, styleClass);
        }
        return l;
    }

    public static JLabel secondary(String text) {
        return label(text, Palette::secondaryText, null);
    }

    public static JLabel small(String text) {
        // Helper text must remain readable on a control-room display.  FlatLaf's
        // `small` class is intentionally tiny and was falling below the useful
        // size once the application was spread across several production panes.
        return label(text, Palette::secondaryText, null);
    }

    /** Uppercase, letter-spaced caption used above groups ("LIBRARY", "PLAYLISTS"). */
    public static JLabel caption(String text) {
        JLabel l = label(text.toUpperCase(), Palette::mutedText, "small");
        l.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
        return l;
    }

    public static JButton toolButton(String icon, String tooltip) {
        JButton b = new JButton(Icons.get(icon, Icons.MEDIUM));
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.setMargin(new Insets(4, 4, 4, 4));
        return b;
    }

    public static JButton button(String text, String icon) {
        JButton b = new JButton(text, icon == null ? null : Icons.get(icon, Icons.SMALL));
        b.setIconTextGap(6);
        b.setMargin(new Insets(6, 11, 6, 11));
        return b;
    }

    public static JButton primaryButton(String text, String icon) {
        JButton b = new JButton(text, icon == null ? null : Icons.get(icon, Icons.SMALL, () -> Color.WHITE));
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, "default");
        b.setIconTextGap(6);
        b.setMargin(new Insets(7, 12, 7, 12));
        return b;
    }

    /**
     * Mute as an icon: a speaker while sound is on, a crossed-out speaker (red) when muted. Selected =
     * muted. The tooltip and accessible name follow the state ("Mute" / "Unmute").
     */
    public static JToggleButton muteToggle(String what) {
        JToggleButton b = iconToggle(Icons.get("audio", Icons.MEDIUM, Palette::secondaryText),
                Icons.get("volume-x", Icons.MEDIUM, Palette::danger));
        Runnable sync = () -> {
            String tip = (b.isSelected() ? "Unmute" : "Mute") + (what == null ? "" : " " + what);
            b.setToolTipText(tip);
            b.getAccessibleContext().setAccessibleName(tip);
        };
        b.addItemListener(e -> sync.run());
        sync.run();
        return b;
    }

    /** Loop as a repeat icon: outlined when off, accent and highlighted when on. Selected = looping. */
    public static JToggleButton loopToggle() {
        JToggleButton b = iconToggle(Icons.get("repeat", Icons.MEDIUM, Palette::secondaryText),
                Icons.get("repeat", Icons.MEDIUM, Palette::accent));
        Runnable sync = () -> {
            String tip = b.isSelected() ? "Disable Loop" : "Enable Loop";
            b.setToolTipText(tip);
            b.getAccessibleContext().setAccessibleName(tip);
        };
        b.addItemListener(e -> sync.run());
        sync.run();
        return b;
    }

    private static JToggleButton iconToggle(javax.swing.Icon off, javax.swing.Icon on) {
        JToggleButton b = new JToggleButton(off);
        b.setSelectedIcon(on);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setMargin(new Insets(4, 4, 4, 4));
        b.setFocusable(false);
        return b;
    }

    public static JToggleButton navButton(String text, String icon) {
        JToggleButton b = new JToggleButton(text, Icons.get(icon, Icons.MEDIUM,
                () -> Palette.secondaryText()));
        b.setSelectedIcon(Icons.get(icon, Icons.MEDIUM, Palette::text));
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.putClientProperty(FlatClientProperties.STYLE, "font: semibold; margin: 5,12,5,12");
        b.setIconTextGap(7);
        b.setFocusable(false);
        return b;
    }

    /** Header strip with a title on the left, optional subtitle, and action components on the right. */
    public static JPanel header(JComponent title, JComponent subtitle, JComponent... actions) {
        JPanel header = transparent(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 8));
        JPanel titles = transparent(new BorderLayout(0, 2));
        titles.add(title, BorderLayout.NORTH);
        if (subtitle != null) {
            titles.add(subtitle, BorderLayout.SOUTH);
        }
        header.add(titles, BorderLayout.CENTER);
        if (actions.length > 0) {
            JPanel right = transparent(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            for (JComponent a : actions) {
                right.add(a);
            }
            header.add(right, BorderLayout.EAST);
        }
        return header;
    }

    /** One-pixel line in the palette border color on the given sides. */
    public static Border line(boolean top, boolean left, boolean bottom, boolean right) {
        return new Border() {
            @Override
            public void paintBorder(java.awt.Component c, Graphics g, int x, int y, int w, int h) {
                g.setColor(Palette.border());
                if (top) g.fillRect(x, y, w, 1);
                if (left) g.fillRect(x, y, 1, h);
                if (bottom) g.fillRect(x, y + h - 1, w, 1);
                if (right) g.fillRect(x + w - 1, y, 1, h);
            }

            @Override
            public Insets getBorderInsets(java.awt.Component c) {
                return new Insets(top ? 1 : 0, left ? 1 : 0, bottom ? 1 : 0, right ? 1 : 0);
            }

            @Override
            public boolean isBorderOpaque() {
                return true;
            }
        };
    }

    /** Binds a keystroke on {@code component} (when it or a child has focus) to {@code action}. */
    public static void bindKey(JComponent component, KeyStroke key, String name, Runnable action, int condition) {
        component.getInputMap(condition).put(key, name);
        component.getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    public static void setEnabled(boolean enabled, AbstractButton... buttons) {
        for (AbstractButton b : buttons) {
            b.setEnabled(enabled);
        }
    }
}
