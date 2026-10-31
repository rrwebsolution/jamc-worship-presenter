package com.jamc.worship.ui.components;

import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Application header: brand on the left, the MAIN NAVIGATION (Library, Service, Scenes, Output, Stream,
 * Settings) in the centre and the output / live status and clock on the right. The Library categories are
 * not part of it (they belong to the Library page).
 */
public class TopBar extends JPanel {

    public enum Page {
        LIBRARY("Library", "library"),
        SERVICE("Service", "list"),
        SCENE("Scenes", "layers"),
        OUTPUT("Output", "monitor"),
        STREAM("Stream", "video"),
        SETTINGS("Settings", "sliders");

        final String title;
        final String icon;

        Page(String title, String icon) {
            this.title = title;
            this.icon = icon;
        }

        public String title() {
            return title;
        }
    }

    private final Map<Page, JToggleButton> navButtons = new EnumMap<>(Page.class);
    /** The application name from Settings → Branding (never a fixed "JAMC Worship Presenter"). */
    private final JLabel appName = Ui.label("", Palette::text, "h4");
    private final JComponent logo;
    private final StatusPill outputPill = new StatusPill("OUTPUT OFF", Palette::mutedText, false);
    private final StatusPill livePill = new StatusPill("LIVE", Palette::mutedText, true);
    private final StatusPill backgroundPill = new StatusPill("VIDEO BACKGROUND", Palette::live, true);
    /** START / STOP RECORDING from any page; shows the running time while recording. */
    private final JButton recordButton = new JButton("● START REC");
    /** START STREAM from any page (the pre-stream check, then confirm). */
    private final JButton streamButton = new JButton("START STREAM");
    private static final String REC_IDLE_STYLE = "font: bold -1; margin: 3,10,3,10; foreground: #EF4444";
    private static final String REC_ACTIVE_STYLE = "font: bold -1; margin: 3,10,3,10; background: #DC2626; foreground: #FFFFFF";

    public TopBar(Consumer<Page> onNavigate) {
        super(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));

        JPanel brand = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        // the JAMC logo goes back to the Library
        LogoBadge home = new LogoBadge();
        home.setToolTipText("Library");
        home.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        home.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                selectPage(Page.LIBRARY);
                onNavigate.accept(Page.LIBRARY);
            }
        });
        brand.add(home);
        logo = home;
        brand.add(appName);
        refreshBranding();

        // MAIN NAVIGATION, centred between the brand and the status: the active page has a filled
        // background, an accent underline and bold text (not colour alone)
        JPanel nav = Ui.transparent(new FlowLayout(FlowLayout.CENTER, 2, 0));
        ButtonGroup group = new ButtonGroup();
        for (Page page : Page.values()) {
            JToggleButton b = Ui.navButton(page.title, page.icon);
            b.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE,
                    com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TAB);
            b.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, NAV_STYLE);
            b.addItemListener(e -> b.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE,
                    b.isSelected() ? NAV_STYLE_ACTIVE : NAV_STYLE));
            b.addActionListener(e -> onNavigate.accept(page));
            group.add(b);
            navButtons.put(page, b);
            nav.add(b);
        }

        JPanel status = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        backgroundPill.setVisible(false);
        backgroundPill.setToolTipText("The global background under Preview and Program. Click for its controls.");
        recordButton.setFocusable(false);
        recordButton.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, REC_IDLE_STYLE);
        recordButton.setToolTipText("Start recording Program as MP4 (folder: Stream page)");
        streamButton.setFocusable(false);
        streamButton.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: bold -1; margin: 3,10,3,10");
        streamButton.setToolTipText("Start the livestream: checks camera, microphone, encoder and internet first");
        status.add(recordButton);
        status.add(streamButton);
        status.add(backgroundPill);
        status.add(outputPill);
        status.add(livePill);
        JLabel clock = Ui.label("", Palette::text, null);
        clock.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: bold +1");
        clock.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("h:mm:ss a");
        Timer timer = new Timer(1000, e -> clock.setText(LocalTime.now().format(fmt)));
        timer.setInitialDelay(0);
        timer.start();
        status.add(clock);

        add(brand, BorderLayout.WEST);
        add(nav, BorderLayout.CENTER);
        add(status, BorderLayout.EAST);
    }

    private static final String NAV_STYLE = "font: semibold; margin: 6,12,6,12; tab.underlineHeight: 3; "
            + "tab.selectedBackground: $Button.toolbar.selectedBackground";
    private static final String NAV_STYLE_ACTIVE = "font: bold; margin: 6,12,6,12; tab.underlineHeight: 3; "
            + "tab.selectedBackground: $Button.toolbar.selectedBackground";

    @Override
    public Color getBackground() {
        return Palette.windowBackground();
    }

    /** Shows the current application name and logo (Settings → Branding). */
    public void refreshBranding() {
        appName.setText(com.jamc.worship.ui.BrandingManager.name());
        logo.repaint();
        revalidate();
        repaint();
    }

    public void selectPage(Page page) {
        navButtons.get(page).setSelected(true);
    }

    public void setOutputActive(boolean active) {
        outputPill.update(active ? "OUTPUT ON" : "OUTPUT OFF", active ? Palette::accent : Palette::mutedText);
    }

    /** "● VIDEO BACKGROUND" while the global background is on; hidden when it is off. */
    /** Clicking the small "BG:" indicator (e.g. to open the Backgrounds library and its controls). */
    public void setBackgroundClick(Runnable action) {
        backgroundPill.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        backgroundPill.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                action.run();
            }
        });
    }

    public void setBackgroundState(String text, Supplier<Color> color) {
        backgroundPill.setVisible(text != null);
        if (text != null) {
            backgroundPill.update(text, color);
        }
    }

    /** The REC and STREAM buttons' actions (the Stream page does the work). */
    public void setRecordActions(Runnable record, Runnable stream) {
        recordButton.addActionListener(e -> record.run());
        streamButton.addActionListener(e -> stream.run());
    }

    /**
     * "■ 00:21:15" (red: click to stop) while recording, "SAVING…" while the file is finished, null = not
     * recording ("● REC" starts it). The operator always sees it, on every page.
     */
    public void setRecording(String text, boolean saving) {
        boolean active = text != null;
        recordButton.setText(active ? text : "● START REC");
        recordButton.setEnabled(!saving);
        recordButton.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, active ? REC_ACTIVE_STYLE : REC_IDLE_STYLE);
        recordButton.setToolTipText(active ? "Recording Program. Click to stop and save the MP4"
                : "Start recording Program as MP4 (folder: Stream page)");
    }

    /** "■ LIVE 00:21:15" (red: click to stop) while streaming, "CONNECTING…", or null = not live. */
    public void setStreaming(String text, boolean live) {
        streamButton.setText(text == null ? "START STREAM" : text);
        streamButton.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, text == null
                ? "font: bold -1; margin: 3,10,3,10"
                : "font: bold -1; margin: 3,10,3,10; background: " + (live ? "#DC2626" : "#B45309") + "; foreground: #FFFFFF");
        streamButton.setToolTipText(text == null ? "Start the livestream: checks camera, microphone, encoder and internet first"
                : "Livestreaming Program. Click to stop");
    }

    public void setLive(boolean live) {
        livePill.update("LIVE", live ? Palette::live : Palette::mutedText);
    }

    /** Rounded status pill with an optional leading dot. */
    static final class StatusPill extends JComponent {
        private String text;
        private Supplier<Color> color;
        private final boolean dot;

        StatusPill(String text, Supplier<Color> color, boolean dot) {
            this.text = text;
            this.color = color;
            this.dot = dot;
            setFont(new JLabel().getFont().deriveFont(Font.BOLD, 11f));
        }

        void update(String text, Supplier<Color> color) {
            this.text = text;
            this.color = color;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + (dot ? 30 : 20), 24);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color c = color.get();
            int h = getHeight();
            g2.setColor(Palette.withAlpha(c, 40));
            g2.fillRoundRect(0, 0, getWidth(), h, h, h);
            g2.setColor(Palette.withAlpha(c, 140));
            g2.drawRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
            int x = 10;
            if (dot) {
                g2.setColor(c);
                g2.fillOval(x, h / 2 - 4, 8, 8);
                x += 14;
            }
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(c.equals(Palette.mutedText()) ? Palette.secondaryText() : Palette.text());
            g2.drawString(text, x, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** The branding logo (Settings → Branding); by default a rounded accent square with the JAMC initial. */
    private static final class LogoBadge extends JComponent {
        LogoBadge() {
            setPreferredSize(new Dimension(32, 32));
        }

        @Override
        protected void paintComponent(Graphics g) {
            var branding = com.jamc.worship.ui.BrandingManager.get();
            if (branding != null && branding.customLogo() != null) {
                // drawn at the screen's real pixel size so the logo stays sharp on scaled displays
                Graphics2D g2 = (Graphics2D) g.create();
                double scale = g2.getTransform().getScaleX();
                int px = (int) Math.ceil(Math.min(getWidth(), getHeight()) * scale);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.drawImage(branding.icon(px), 0, 0, getWidth(), getHeight(), null);
                g2.dispose();
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x2B86FF), getWidth(), getHeight(), new Color(0x0F4FC2)));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.setColor(Color.WHITE);
            g2.setFont(getFont().deriveFont(Font.BOLD, 17f));
            FontMetrics fm = g2.getFontMetrics();
            String s = "J";
            g2.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
}
