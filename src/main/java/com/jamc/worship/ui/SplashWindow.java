package com.jamc.worship.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The LiveCat logo on white while the app starts (the logo's blue text needs a white surface, in either
 * theme). It is the product's own splash, so it does not follow a custom name in Settings → Branding: the
 * settings are read later. Call on the Swing thread.
 */
public final class SplashWindow {

    private static final int LOGO_WIDTH = 520;
    private static JWindow window;

    private SplashWindow() {
    }

    /** Shows the splash until {@link #close()}; nothing shows if the logo cannot be read, and the app starts as usual. */
    public static void open(String version) {
        BufferedImage logo = BrandingManager.defaultLogo();
        if (logo == null) {
            return;
        }
        JComponent art = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(logo, 0, 0, getWidth(), getHeight(), null);
                g2.dispose();
            }
        };
        art.setPreferredSize(new Dimension(LOGO_WIDTH, LOGO_WIDTH * logo.getHeight() / logo.getWidth()));

        JLabel status = new JLabel("Version " + version + "  ·  Starting…", JLabel.CENTER);
        status.setForeground(new Color(0x64748B));
        status.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x1677FF)),
                BorderFactory.createEmptyBorder(32, 40, 20, 40)));
        content.add(art, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);

        window = new JWindow();
        window.setContentPane(content);
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    public static void close() {
        if (window != null) {
            window.dispose();
            window = null;
        }
    }
}
