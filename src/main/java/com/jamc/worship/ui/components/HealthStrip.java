package com.jamc.worship.ui.components;

import com.jamc.worship.monitor.HealthMonitor;
import com.jamc.worship.monitor.HealthMonitor.Level;
import com.jamc.worship.monitor.HealthMonitor.Report;
import com.jamc.worship.monitor.SystemSampler;
import com.jamc.worship.stream.StreamManager;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The Production Health Monitor in the footer: CPU, RAM, output FPS, stream bitrate, dropped frames, cameras,
 * stream / recording and the overall health. Only real readings are shown (N/A when there is none). On a narrow
 * window the secondary metrics give way first (they stay in the details); clicking Health (or "+N") opens the
 * details with every reading and the recent alerts.
 */
public class HealthStrip extends JPanel {

    private static final int GAP = 18;
    private static final double GB = 1024.0 * 1024 * 1024;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Metric cpu = new Metric("cpu");
    private final Metric ram = new Metric("memory");
    private final Metric fps = new Metric("gauge");
    private final Metric bitrate = new Metric("wifi");
    private final Metric dropped = new Metric("frame-drop");
    private final Metric cams = new Metric("video");
    private final Metric stream = new Metric("radio");
    private final Metric health = new Metric("help-circle");
    private final Metric more = new Metric(null);
    /** Left to right. */
    private final Metric[] order = {cpu, ram, fps, bitrate, dropped, cams, stream, more, health};
    /** Given up first when the window is narrow (Health always stays). */
    private final Metric[] hideFirst = {dropped, cams, bitrate, fps, ram, cpu, stream};
    private final List<Integer> separators = new ArrayList<>();

    private Report report = Report.unknown();
    private Supplier<List<HealthMonitor.Alert>> alerts = List::of;
    private Supplier<String> outputInfo = () -> null;
    private Supplier<String> systemInfo = () -> null;
    private Runnable openSettings;
    private JPopupMenu popup;
    private Details details;

    public HealthStrip() {
        super(null);
        setOpaque(false);
        for (Metric m : order) {
            add(m);
        }
        health.setFont(health.getFont().deriveFont(Font.BOLD));
        health.setToolTipText("Production health. Click for the details and recent alerts.");
        more.setToolTipText("More readings: click for the details");
        for (Metric m : new Metric[]{health, more}) {
            m.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            m.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    toggleDetails();
                }
            });
        }
        show(report);
    }

    /** Where the recent alerts come from (newest first). */
    public void setAlerts(Supplier<List<HealthMonitor.Alert>> alerts) {
        this.alerts = alerts;
    }

    /** One line about the output (e.g. "1920×1080 at 30 fps → Facebook"), for the details; null for none. */
    public void setOutputInfo(Supplier<String> info) {
        this.outputInfo = info;
    }

    /** Extra system lines for the details (data folder, database), HTML-free; null for none. */
    public void setSystemInfo(Supplier<String> info) {
        this.systemInfo = info;
    }

    /** The details get a "Thresholds…" link that runs this (e.g. opens Settings → Health Monitor). */
    public void setOpenSettings(Runnable openSettings) {
        this.openSettings = openSettings;
    }

    public Report report() {
        return report;
    }

    // ------------------------------------------------------------------ the footer

    /** Shows a new report (Swing thread). */
    public void show(Report r) {
        report = r;
        SystemSampler.Reading s = r.in().system();
        StreamManager.Session live = r.in().live();
        StreamManager.Session rec = r.in().recording();

        // CPU
        if (Double.isNaN(s.cpu())) {
            cpu.set("CPU N/A", Palette::mutedText, "CPU usage is not reported on this computer.");
        } else {
            boolean high = r.issues().stream().anyMatch(i -> i.key().equals("cpu"));
            cpu.set(String.format("CPU %.0f%%", s.cpu() * 100), high ? tone(issueLevel(r, "cpu")) : Palette::text,
                    html("Whole computer: " + pct(s.cpu()),
                            "LiveCat: " + (Double.isNaN(s.appCpu()) ? "N/A" : pct(s.appCpu())),
                            Double.isNaN(r.cpuAverage()) ? "Average: still measuring"
                                    : String.format("Average over the last %d s: %.0f%%", sustainSeconds, r.cpuAverage()),
                            s.cores() + " processor threads"));
        }

        // RAM
        if (s.ramTotal() <= 0) {
            ram.set("RAM N/A", Palette::mutedText, "Memory use is not reported on this computer.");
        } else {
            boolean high = r.issues().stream().anyMatch(i -> i.key().equals("ram"));
            ram.set(String.format(Locale.ROOT, "RAM %.1f GB", s.ramUsed() / GB), high ? Palette::warning : Palette::text,
                    html(String.format(Locale.ROOT, "In use on this computer: %.1f of %.1f GB (%.0f%%)",
                                    s.ramUsed() / GB, s.ramTotal() / GB, 100.0 * s.ramUsed() / s.ramTotal()),
                            s.heapUsed() < 0 ? null : String.format(Locale.ROOT, "LiveCat (Java): %.0f MB of %s",
                                    s.heapUsed() / 1048576.0, s.heapMax() < 0 ? "no limit"
                                            : String.format(Locale.ROOT, "%.1f GB", s.heapMax() / GB))));
        }

        // output FPS: measured by the encoder (only while streaming or recording)
        StreamManager.Session enc = encoding(live) ? live : encoding(rec) ? rec : null;
        if (enc == null) {
            fps.set("FPS N/A", Palette::mutedText, html("Output FPS is measured while streaming or recording.",
                    "Nothing is being sent or recorded now."));
        } else {
            fps.set(String.format("%.0f FPS", enc.stats().fps()), Palette::text,
                    html("Frames per second the " + (enc == live ? "livestream" : "recording") + " encoder is producing."));
        }

        // stream bitrate
        if (!encoding(live)) {
            bitrate.set("N/A Mbps", Palette::mutedText, "Streaming bitrate: the livestream is not running.");
        } else {
            bitrate.set(String.format(Locale.ROOT, "%.1f Mbps", live.stats().bitrateKbps() / 1000),
                    Palette::text, "Upload to the livestream, as the encoder reports it.");
        }

        // dropped frames
        double total = encoding(live) ? r.liveDropTotal() : r.recDropTotal();
        double recent = encoding(live) ? r.liveDropRecent() : r.recDropRecent();
        if (enc == null || Double.isNaN(total)) {
            dropped.set("Drop N/A", Palette::mutedText, "Dropped frames are counted while streaming or recording.");
        } else {
            String key = encoding(live) ? "stream-drops" : "recording-drops";
            Level l = issueLevel(r, key);
            dropped.set(String.format(Locale.ROOT, "Drop %.1f%%", total), l == null ? Palette::text : tone(l),
                    html(String.format(Locale.ROOT, "Dropped frames since it started: %.1f%% (%d frames)", total,
                                    enc.stats().droppedFrames()),
                            Double.isNaN(recent) ? "Last 30 s: still measuring"
                                    : String.format(Locale.ROOT, "Last 30 s: %.1f%%", recent)));
        }

        // cameras
        int camTotal = r.camerasTotal();
        long camOk = r.camerasConnected();
        if (camTotal == 0) {
            cams.set("CAM 0", Palette::mutedText, "No camera detected.");
        } else {
            List<String> lines = new ArrayList<>();
            r.in().cameras().forEach(c -> lines.add(c.name() + ": " + (c.status() == null ? "unknown" : c.status().label())));
            cams.set("CAM " + camOk + "/" + camTotal,
                    camOk < camTotal ? tone(worstCamera(r)) : Palette::text, html(lines.toArray(String[]::new)));
        }

        // stream / recording
        streamStatus(live, rec);

        // overall
        Level level = r.level();
        health.setIconName(switch (level) {
            case HEALTHY -> "check-circle";
            case WARNING -> "alert-triangle";
            case CRITICAL -> "alert-octagon";
            default -> "help-circle";
        });
        List<String> why = new ArrayList<>();
        why.add("Production health: " + level.label());
        r.issues().forEach(i -> why.add("• " + i.text()));
        why.add("Click for the details and recent alerts.");
        health.set(level.label(), tone(level), html(why.toArray(String[]::new)));
        health.setIconTone(tone(level));

        if (popup != null && popup.isVisible()) {
            details.rebuild();
            if (!popup.getSize().equals(popup.getPreferredSize())) {
                popup.pack();
            }
        }
        revalidate();
        repaint();
    }

    private void streamStatus(StreamManager.Session live, StreamManager.Session rec) {
        List<String> parts = new ArrayList<>();
        Supplier<Color> color = Palette::mutedText;
        if (live != null && live.state() == StreamManager.State.ERROR) {
            parts.add("Stream error");
            color = Palette::danger;
        } else if (live != null && live.finishing()) {
            parts.add("Stopping");
            color = Palette::warning;
        } else if (live != null && live.state() == StreamManager.State.STARTING) {
            parts.add(live.detail() != null && live.detail().startsWith("Reconnecting") ? "Reconnecting" : "Connecting");
            color = Palette::warning;
        } else if (live != null && live.state() == StreamManager.State.ACTIVE) {
            parts.add("LIVE " + StreamPanel.elapsed(live.startedMillis()));
            color = Palette::live;
        }
        if (rec != null && rec.state() == StreamManager.State.ERROR) {
            parts.add("Rec error");
            color = Palette::danger;
        } else if (rec != null && rec.active()) {
            parts.add(rec.finishing() ? "Saving" : "REC " + StreamPanel.elapsed(rec.startedMillis()));
            if (parts.size() == 1) {
                color = Palette::live; // only recording
            }
        }
        String text = parts.isEmpty() ? "Offline" : String.join(" · ", parts);
        stream.set(text, parts.isEmpty() ? Palette::mutedText : color, html(
                "Livestream: " + (live == null ? "not live" : StatusBar.liveText(live)),
                "Recording: " + recordingText(rec),
                parts.isEmpty() ? "Not streaming or recording (stopped on purpose is not a problem)." : null));
    }

    static String recordingText(StreamManager.Session rec) {
        if (rec == null || rec.state() == StreamManager.State.IDLE) {
            return "not recording";
        }
        if (rec.state() == StreamManager.State.ERROR) {
            return "stopped: " + (rec.detail() == null ? "an error" : rec.detail());
        }
        if (rec.finishing()) {
            return "saving the file…";
        }
        if (rec.state() == StreamManager.State.STARTING) {
            return "starting…";
        }
        return "REC " + StreamPanel.elapsed(rec.startedMillis());
    }

    private static boolean encoding(StreamManager.Session s) {
        return s != null && s.state() == StreamManager.State.ACTIVE && s.stats() != null;
    }

    private int sustainSeconds = com.jamc.worship.monitor.HealthThresholds.DEFAULTS.sustainSeconds();

    /** The sustain time shown in the CPU tooltip. */
    public void setSustainSeconds(int seconds) {
        sustainSeconds = seconds;
    }

    private static Level issueLevel(Report r, String key) {
        return r.issues().stream().filter(i -> i.key().equals(key)).map(HealthMonitor.Issue::level).findFirst().orElse(null);
    }

    private static Level worstCamera(Report r) {
        return r.issues().stream().anyMatch(i -> i.key().startsWith("camera:") && i.level() == Level.CRITICAL)
                ? Level.CRITICAL : Level.WARNING;
    }

    static Supplier<Color> tone(Level l) {
        if (l == null) {
            return Palette::text;
        }
        return switch (l) {
            case HEALTHY -> () -> new Color(0x22C55E);
            case WARNING -> () -> new Color(0xFACC15);
            case CRITICAL -> Palette::danger;
            default -> Palette::mutedText;
        };
    }

    private static String pct(double v) {
        return String.format("%.0f%%", v * 100);
    }

    /** Tooltip lines (null lines are left out), escaped. */
    static String html(String... lines) {
        StringBuilder b = new StringBuilder("<html>");
        boolean first = true;
        for (String l : lines) {
            if (l == null) {
                continue;
            }
            if (!first) {
                b.append("<br>");
            }
            b.append(l.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
            first = false;
        }
        return b.append("</html>").toString();
    }

    // ------------------------------------------------------------------ layout: narrow windows drop the extras

    @Override
    public Dimension getPreferredSize() {
        int w = 0;
        int h = 0;
        int n = 0;
        for (Metric m : order) {
            if (m == more) {
                continue;
            }
            Dimension d = m.getPreferredSize();
            w += d.width;
            h = Math.max(h, d.height);
            n++;
        }
        return new Dimension(w + GAP * (n - 1), h);
    }

    @Override
    public Dimension getMinimumSize() {
        more.setText("+" + hideFirst.length);
        return new Dimension(health.getPreferredSize().width + GAP + more.getPreferredSize().width,
                health.getPreferredSize().height);
    }

    @Override
    public void doLayout() {
        List<Metric> shown = new ArrayList<>(List.of(order));
        shown.remove(more);
        int hidden = 0;
        while (width(shown) > getWidth() && hidden < hideFirst.length) {
            shown.remove(hideFirst[hidden++]);
            more.setText("+" + hidden);
            if (!shown.contains(more)) {
                shown.add(shown.indexOf(health), more);
            }
        }
        if (hidden > 0) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < hidden; i++) {
                names.add(hideFirst[i].getText());
            }
            more.setToolTipText(html(names.toArray(String[]::new)) .replace("</html>", "<br>Click for the details</html>"));
        }
        for (Metric m : order) {
            if (!shown.contains(m)) {
                m.setBounds(0, 0, 0, 0);
            }
        }
        separators.clear();
        int x = Math.max(0, getWidth() - width(shown));
        for (int i = 0; i < shown.size(); i++) {
            Metric m = shown.get(i);
            Dimension d = m.getPreferredSize();
            if (i > 0) {
                separators.add(x - GAP / 2);
            }
            m.setBounds(x, (getHeight() - d.height) / 2, d.width, d.height);
            x += d.width + GAP;
        }
    }

    private static int width(List<Metric> ms) {
        int w = 0;
        for (Metric m : ms) {
            w += m.getPreferredSize().width;
        }
        return w + GAP * Math.max(0, ms.size() - 1);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(Palette.border());
        g2.setStroke(new BasicStroke(1f));
        int h = getHeight();
        for (int x : separators) {
            g2.drawLine(x, h / 2 - 6, x, h / 2 + 6);
        }
        g2.dispose();
    }

    // ------------------------------------------------------------------ details

    /** A fresh details view of the current report (what the popover shows). */
    JComponent detailsView() {
        Details d = new Details();
        d.rebuild();
        return d;
    }

    private void toggleDetails() {
        if (popup != null && popup.isVisible()) {
            popup.setVisible(false);
            return;
        }
        details = new Details();
        details.rebuild();
        JScrollPane scroll = new JScrollPane(details);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        popup = new JPopupMenu() {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = details.getPreferredSize();
                int maxH = Math.max(200, (HealthStrip.this.getGraphicsConfiguration() == null ? 800
                        : HealthStrip.this.getGraphicsConfiguration().getBounds().height) - 160);
                return new Dimension(440, Math.min(maxH, d.height + 4));
            }
        };
        popup.setLayout(new java.awt.BorderLayout());
        popup.add(scroll);
        popup.setBorder(BorderFactory.createLineBorder(Palette.border()));
        Dimension d = popup.getPreferredSize();
        popup.show(this, Math.max(0, getWidth() - d.width), -d.height - 6);
    }

    /** The diagnostics: overall, stream, recording, cameras, system, recent alerts. */
    private final class Details extends JPanel {
        Details() {
            super(new GridBagLayout());
            setBackground(Palette.panelBackground());
            setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        }

        private int row;

        void rebuild() {
            removeAll();
            row = 0;
            Report r = report;
            SystemSampler.Reading s = r.in().system();
            StreamManager.Session live = r.in().live();
            StreamManager.Session rec = r.in().recording();

            JLabel title = new JLabel("Production Health: " + r.level().label());
            title.setIcon(Icons.get(health.iconName, 16, tone(r.level())));
            title.setForeground(tone(r.level()).get());
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1f));
            full(title);
            if (r.issues().isEmpty()) {
                full(Ui.small(r.level() == Level.UNKNOWN ? "Waiting for the first readings…" : "No problems right now."));
            } else {
                r.issues().forEach(i -> full(Ui.label("• " + i.text(), tone(i.level()), "small")));
            }

            heading("Livestream");
            pair("Status", live == null ? "Not live" : StatusBar.liveText(live));
            if (encoding(live)) {
                var st = live.stats();
                pair("Output FPS", String.format("%.1f", st.fps()));
                pair("Bitrate (upload)", String.format(Locale.ROOT, "%.2f Mbps", st.bitrateKbps() / 1000));
                pair("Dropped frames", dropText(st.droppedFrames(), r.liveDropTotal(), r.liveDropRecent()));
                pair("Frames sent", Long.toString(st.frames()));
            }
            String out = outputInfo.get();
            if (out != null) {
                pair("Output", out);
            }

            heading("Recording");
            pair("Status", recordingText(rec));
            if (encoding(rec)) {
                var st = rec.stats();
                pair("Output FPS", String.format("%.1f", st.fps()));
                pair("Bitrate", String.format(Locale.ROOT, "%.2f Mbps", st.bitrateKbps() / 1000));
                pair("Dropped frames", dropText(st.droppedFrames(), r.recDropTotal(), r.recDropRecent()));
            }
            if (rec != null && rec.file() != null) {
                pair("File", rec.file().getFileName().toString());
            }

            heading("Cameras (" + r.camerasConnected() + "/" + r.camerasTotal() + ")");
            if (r.in().cameras().isEmpty()) {
                full(Ui.small("No camera detected."));
            }
            for (HealthMonitor.Camera c : r.in().cameras()) {
                String v = (c.status() == null ? "Unknown" : c.status().label())
                        + (c.size() == null ? "" : " · " + c.size())
                        + (c.fps() > 0 ? " · " + c.fps() + " fps" : "")
                        + (c.problem() == null ? "" : " · " + c.problem());
                pair(c.name(), v);
            }

            heading("System");
            pair("CPU (computer)", Double.isNaN(s.cpu()) ? "N/A" : pct(s.cpu())
                    + (Double.isNaN(r.cpuAverage()) ? "" : String.format(" · %.0f%% average over %d s", r.cpuAverage(), sustainSeconds)));
            pair("CPU (LiveCat)", Double.isNaN(s.appCpu()) ? "N/A" : pct(s.appCpu()));
            pair("Processor threads", s.cores() > 0 ? Integer.toString(s.cores()) : "N/A");
            pair("RAM", s.ramTotal() <= 0 ? "N/A" : String.format(Locale.ROOT, "%.1f of %.1f GB in use (%.0f%%)",
                    s.ramUsed() / GB, s.ramTotal() / GB, 100.0 * s.ramUsed() / s.ramTotal()));
            pair("LiveCat memory", s.heapUsed() < 0 ? "N/A" : String.format(Locale.ROOT, "%.0f MB (Java)", s.heapUsed() / 1048576.0));
            String sys = systemInfo.get();
            if (sys != null) {
                for (String line : sys.split("\n")) {
                    int colon = line.indexOf(": ");
                    if (colon > 0) {
                        pair(line.substring(0, colon), line.substring(colon + 2));
                    } else {
                        full(Ui.small(line));
                    }
                }
            }

            heading("Network");
            pair("Upload", encoding(live) ? String.format(Locale.ROOT, "%.2f Mbps to the livestream", live.stats().bitrateKbps() / 1000)
                    : "N/A (measured while the livestream runs)");

            heading("Recent alerts");
            List<HealthMonitor.Alert> list = alerts.get();
            if (list.isEmpty()) {
                full(Ui.small("No alerts since LiveCat started."));
            }
            for (HealthMonitor.Alert a : list.subList(0, Math.min(15, list.size()))) {
                JLabel l = Ui.label("<html>" + CLOCK.format(Instant.ofEpochMilli(a.at())) + "&nbsp;&nbsp;"
                        + a.text().replace("&", "&amp;").replace("<", "&lt;") + "</html>", tone(a.level()), "small");
                full(l);
            }

            if (openSettings != null) {
                JLabel link = Ui.label("<html><u>Alert thresholds… (Settings → Health Monitor)</u></html>", Palette::accent, "small");
                link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                link.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (popup != null) {
                            popup.setVisible(false);
                        }
                        openSettings.run();
                    }
                });
                GridBagConstraints c = constraints(0, 2);
                c.insets = new Insets(12, 0, 0, 0);
                add(link, c);
            }
            GridBagConstraints filler = constraints(0, 2);
            filler.weighty = 1;
            add(Box.createGlue(), filler);
            revalidate();
            repaint();
        }

        private String dropText(long frames, double total, double recent) {
            return (Double.isNaN(total) ? "N/A" : String.format(Locale.ROOT, "%.2f%% (%d frames)", total, frames))
                    + (Double.isNaN(recent) ? "" : String.format(Locale.ROOT, " · last 30 s %.2f%%", recent));
        }

        private GridBagConstraints constraints(int x, int width) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = x;
            c.gridy = row;
            c.gridwidth = width;
            c.anchor = GridBagConstraints.NORTHWEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = width == 2 || x == 1 ? 1 : 0;
            c.insets = new Insets(1, 0, 1, 0);
            return c;
        }

        private void heading(String text) {
            JLabel l = Ui.label(text.toUpperCase(Locale.ROOT), Palette::accent, "small");
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            GridBagConstraints c = constraints(0, 2);
            c.insets = new Insets(12, 0, 3, 0);
            add(l, c);
            row++;
        }

        private void pair(String key, String value) {
            JLabel k = Ui.small(key);
            k.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
            add(k, constraints(0, 1));
            JLabel v = Ui.label("<html><div style='width:250px'>" + value.replace("&", "&amp;").replace("<", "&lt;")
                    + "</div></html>", Palette::text, "small");
            add(v, constraints(1, 1));
            row++;
        }

        private void full(JComponent comp) {
            add(comp, constraints(0, 2));
            row++;
        }
    }

    // ------------------------------------------------------------------ one metric

    /** A small icon + text; the text color shows the state. */
    static final class Metric extends JLabel {
        private Supplier<Color> textTone = Palette::text;
        private Supplier<Color> iconTone = Palette::secondaryText;
        String iconName;

        Metric(String icon) {
            setHorizontalAlignment(SwingConstants.LEFT);
            setIconTextGap(5);
            putClientProperty("FlatLaf.styleClass", "small");
            setIconName(icon);
        }

        void setIconName(String name) {
            if (name != null && !name.equals(iconName)) {
                iconName = name;
                setIcon(Icons.get(name, 14, () -> iconTone.get()));
            }
        }

        void setIconTone(Supplier<Color> tone) {
            iconTone = tone;
        }

        void set(String text, Supplier<Color> tone, String tip) {
            setText(text);
            textTone = tone;
            setToolTipText(tip);
        }

        @Override
        public Color getForeground() {
            return textTone == null ? super.getForeground() : textTone.get();
        }
    }
}
