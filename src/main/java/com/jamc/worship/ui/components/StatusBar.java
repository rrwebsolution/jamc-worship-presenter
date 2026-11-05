package com.jamc.worship.ui.components;

import com.jamc.worship.stream.StreamManager;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Supplier;

/** Bottom status line: transient messages on the left, persistent indicators on the right. */
public class StatusBar extends JPanel {

    private final JLabel message = Ui.small("Ready");
    private final JLabel database = Ui.small("Database");
    private final Dot databaseDot = new Dot(Palette::live);
    private final JLabel dataDir = Ui.label("", Palette::mutedText, "small");
    private final JLabel live = Ui.small("Not live");
    private final Dot liveDot = new Dot(Palette::mutedText);
    private final Timer clearTimer;

    public StatusBar() {
        super(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(3, 12, 3, 12)));

        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(liveDot);
        right.add(live);
        right.add(javax.swing.Box.createHorizontalStrut(10));
        right.add(dataDir);
        right.add(javax.swing.Box.createHorizontalStrut(10));
        right.add(databaseDot);
        right.add(database);

        add(message, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);

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

    /** The livestream's state, always in view: time and frame rate on air, the reason when it stopped. */
    public void setLiveStatus(StreamManager.Session session) {
        live.setText(liveText(session));
        live.setToolTipText(session.state() == StreamManager.State.ERROR ? session.detail() : null);
        liveDot.color = session.state() == StreamManager.State.ERROR ? Palette::danger
                : session.finishing() || session.state() == StreamManager.State.STARTING ? Palette::warning
                : session.state() == StreamManager.State.ACTIVE ? Palette::live : Palette::mutedText;
        liveDot.repaint();
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

    public void setDatabaseStatus(boolean ok, String text) {
        databaseDot.color = ok ? Palette::live : Palette::danger;
        database.setText(text);
        databaseDot.repaint();
    }

    public void setDataDirectory(String path) {
        dataDir.setText("Data: " + path);
        dataDir.setToolTipText(path);
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
