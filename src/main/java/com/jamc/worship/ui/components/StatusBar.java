package com.jamc.worship.ui.components;

import com.jamc.worship.stream.StreamManager;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Supplier;

/**
 * Bottom status line: transient messages on the left; the global background and the Production Health Monitor
 * ({@link HealthStrip}) on the right. On a narrow window the message gives way first, then the health strip
 * puts its secondary readings in its details.
 */
public class StatusBar extends JPanel {

    private final JLabel message = Ui.small("Ready");
    private final JLabel database = Ui.small("Database");
    private final Dot databaseDot = new Dot(Palette::live);
    /** "BG: Loop.mp4" while a video / image is the global background (Take as Background); hidden when off. */
    private final JLabel background = Ui.small("");
    private final Dot backgroundDot = new Dot(Palette::live);
    private final JPanel backgroundGroup = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final HealthStrip health = new HealthStrip();
    private boolean databaseOk = true;
    private String databaseText = "Database OK";
    private String dataPath;
    private final Timer clearTimer;

    public StatusBar() {
        super(null);
        setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));

        background.setVisible(false);
        backgroundDot.setVisible(false);
        backgroundGroup.add(backgroundDot);
        backgroundGroup.add(background);
        // the database only shows here when it has a problem (otherwise it is in the health details)
        databaseDot.setVisible(false);
        database.setVisible(false);
        backgroundGroup.add(databaseDot);
        backgroundGroup.add(database);
        message.setMinimumSize(new Dimension(0, 0));
        health.setSystemInfo(() -> "Database: " + databaseText + (dataPath == null ? "" : "\nData folder: " + dataPath));

        add(message);
        add(backgroundGroup);
        add(health);

        clearTimer = new Timer(6000, e -> message.setText("Ready"));
        clearTimer.setRepeats(false);
    }

    @Override
    public Color getBackground() {
        return Palette.windowBackground();
    }

    public void showMessage(String text) {
        message.setText(text);
        clearTimer.restart();
    }

    /** The Production Health Monitor on the right of the footer. */
    public HealthStrip health() {
        return health;
    }

    @Override
    public Dimension getPreferredSize() {
        Insets in = getInsets();
        int h = Math.max(message.getPreferredSize().height, Math.max(health.getPreferredSize().height,
                backgroundGroup.getPreferredSize().height));
        return new Dimension(400, h + in.top + in.bottom);
    }

    /** Right to left: health strip, background / database, then the message in what is left. */
    @Override
    public void doLayout() {
        Insets in = getInsets();
        int h = getHeight() - in.top - in.bottom;
        int left = in.left;
        int right = getWidth() - in.right;
        int minMessage = 120;
        boolean groupShown = background.isVisible() || database.isVisible();
        int groupW = groupShown ? backgroundGroup.getPreferredSize().width : 0;
        int room = right - left - minMessage - groupW;
        int stripW = Math.min(health.getPreferredSize().width, Math.max(health.getMinimumSize().width, room));
        health.setBounds(right - stripW, in.top, stripW, h);
        right -= stripW + (stripW > 0 ? 12 : 0);
        if (groupShown) {
            int w = Math.max(0, Math.min(groupW, right - left - minMessage));
            backgroundGroup.setBounds(right - w, in.top + (h - backgroundGroup.getPreferredSize().height) / 2, w,
                    backgroundGroup.getPreferredSize().height);
            right -= w + 8;
        } else {
            backgroundGroup.setBounds(0, 0, 0, 0);
        }
        message.setBounds(left, in.top, Math.max(0, right - left), h);
    }

    static String liveText(StreamManager.Session s) {
        if (s.state() == StreamManager.State.ERROR) {
            return "Livestream stopped (see the Stream page)";
        }
        if (s.finishing()) {
            return "Stopping the livestream…";
        }
        if (s.state() == StreamManager.State.STARTING) {
            return s.detail() == null ? "Connecting the livestream…" : s.detail();
        }
        if (s.state() != StreamManager.State.ACTIVE) {
            return "Not live";
        }
        var st = s.stats();
        return "LIVE " + StreamPanel.elapsed(s.startedMillis())
                + (st == null ? "" : String.format(java.util.Locale.ROOT, "  ·  %.0f fps  ·  dropped %d", st.fps(), st.droppedFrames()));
    }

    /**
     * "BG: Loop.mp4" (green) or "BG: Loop.mp4 (paused)" (amber) while there is a global background
     * (Take as Background); {@code text} null hides it. A long name is shortened; the tooltip has it all.
     */
    public void setBackgroundState(String text, Supplier<Color> color) {
        boolean on = text != null;
        background.setVisible(on);
        backgroundDot.setVisible(on);
        if (on) {
            background.setText(shorten(text, 40));
            background.setToolTipText("<html>" + text.replace("&", "&amp;").replace("<", "&lt;")
                    + "<br>The global background under Preview and Program. Click for its controls.</html>");
            backgroundDot.color = color;
            backgroundDot.repaint();
        }
        revalidate();
        repaint();
    }

    /** Clicking "BG: …" (e.g. opens the Backgrounds library with its controls). */
    public void setBackgroundClick(Runnable action) {
        background.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        background.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                action.run();
            }
        });
    }

    /**
     * "BG: Very Long Worship Loop Background 4K.mp4 (paused)" → "BG: Very Long Worship Lo… (paused)":
     * at most {@code max} characters of the name; a "(paused)"-style ending is kept.
     */
    static String shorten(String text, int max) {
        String suffix = "";
        int open = text.lastIndexOf(" (");
        if (open > 0 && text.endsWith(")")) {
            suffix = text.substring(open);
            text = text.substring(0, open);
        }
        if (text.length() > max) {
            text = text.substring(0, max - 1).stripTrailing() + "…";
        }
        return text + suffix;
    }

    /** Shown in the health details; in the footer itself only when there is a problem. */
    public void setDatabaseStatus(boolean ok, String text) {
        databaseOk = ok;
        databaseText = text;
        databaseDot.color = ok ? Palette::live : Palette::danger;
        database.setText(text);
        databaseDot.setVisible(!ok);
        database.setVisible(!ok);
        databaseDot.repaint();
        revalidate();
    }

    public boolean databaseOk() {
        return databaseOk;
    }

    public void setDataDirectory(String path) {
        dataPath = path;
    }

    private static final class Dot extends JComponent {
        Supplier<Color> color;

        Dot(Supplier<Color> color) {
            this.color = color;
            setPreferredSize(new Dimension(8, 8));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color.get());
            g2.fillOval(0, (getHeight() - 8) / 2, 8, 8);
            g2.dispose();
        }
    }
}
