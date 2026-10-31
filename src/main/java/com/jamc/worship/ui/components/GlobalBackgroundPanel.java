package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.background.GlobalBackgroundManager;
import com.jamc.worship.background.GlobalBackgroundManager.Status;
import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.media.MediaProbe;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.MediaChoosers;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JToggleButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The global background's contextual controls, shown under the MEDIA PREVIEW monitor while a
 * background is chosen: which background is ACTIVE, Pause / Restart / Change / OFF, and a Settings
 * popup with a small live view, loop, mute, overlay, fit, change style and Black behaviour.
 *
 * <p>The background is one application-wide layer under Preview and Program. Nothing here touches
 * scenes, content or output routing, and content changes never touch the background.
 */
public class GlobalBackgroundPanel extends JPanel {

    /** Copies a chosen file into the media library and returns its stored path. */
    public interface Importer {
        String importFile(Path file) throws Exception;
    }

    private final GlobalBackgroundManager manager;
    private final Importer importer;
    private final StatusDot status = new StatusDot();
    private final JLabel fileLabel = Ui.label("No background", Palette::text, "semibold");
    private final JLabel timeLabel = Ui.small("");
    private final JButton onOff = Ui.button("OFF", null);
    private final JButton changeButton = Ui.button("Change", "landscape");
    private final JButton restartButton = Ui.button("Restart", "refresh");
    private final JButton pauseButton = Ui.button("Pause", "pause");
    private final JButton settingsButton = Ui.toolButton("sliders", "Background settings: loop, mute, overlay, fit, change style");
    private final JPanel details = Ui.transparent(null);
    private final Monitor monitor = new Monitor();
    private final JToggleButton loop = Ui.loopToggle();
    private final JToggleButton mute = Ui.muteToggle("the background video");
    private final JSlider overlay = new JSlider(0, 90, 0);
    private final JLabel overlayValue = Ui.small("0%");
    private final JComboBox<FitMode> fit = new JComboBox<>(new FitMode[]{FitMode.FILL, FitMode.FIT});
    private final JComboBox<GlobalBackgroundManager.Change> change = new JComboBox<>(GlobalBackgroundManager.Change.values());
    private final JSpinner changeSeconds = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 5.0, 0.25));
    private final JCheckBox keepInBlack = new JCheckBox("Keep playing while Black is on");
    private Consumer<String> statusHandler = s -> { };
    private Runnable browseHandler = () -> { };
    private boolean loading;

    public GlobalBackgroundPanel(GlobalBackgroundManager manager, Importer importer) {
        super(new BorderLayout(8, 4));
        this.manager = manager;
        this.importer = importer;
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(8, 0, 0, 0)));

        JLabel title = Ui.label("BACKGROUND", Palette::secondaryText, "semibold");
        title.setIcon(Icons.get("landscape", Icons.SMALL, Palette::secondaryText));
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(title);
        left.add(status);
        left.add(fileLabel);
        left.add(timeLabel);

        onOff.putClientProperty(FlatClientProperties.STYLE, "font: bold; minimumWidth: 56");
        onOff.setToolTipText("Turn the global background OFF or back ON (a video keeps its position while off)");
        JPanel controls = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        controls.add(pauseButton);
        controls.add(restartButton);
        controls.add(changeButton);
        controls.add(onOff);
        controls.add(settingsButton);

        add(left, BorderLayout.NORTH);
        add(controls, BorderLayout.CENTER);
        buildDetails();

        onOff.addActionListener(e -> {
            boolean turnOn = !manager.isOn();
            if (turnOn && !manager.canShow(manager.path())) {
                Dialogs.showInfo(this, "Background", manager.unavailableReason());
                return;
            }
            manager.setOn(turnOn);
            statusHandler.accept(turnOn ? "Background ON under Preview and Program." : "Background OFF. Scenes and content are unchanged.");
        });
        changeButton.setToolTipText("Pick another background from the library or a file");
        changeButton.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem browse = new JMenuItem("Browse Backgrounds library", Icons.get("library", Icons.SMALL));
            browse.addActionListener(a -> browseHandler.run());
            JMenuItem file = new JMenuItem("Choose a file…", Icons.get("folder", Icons.SMALL));
            file.addActionListener(a -> choose());
            menu.add(browse);
            menu.add(file);
            menu.show(changeButton, 0, changeButton.getHeight());
        });
        restartButton.addActionListener(e -> manager.restart());
        pauseButton.addActionListener(e -> manager.setPaused(!manager.isPaused()));
        settingsButton.addActionListener(e -> {
            JPopupMenu popup = new JPopupMenu();
            popup.add(details);
            popup.pack();
            popup.show(settingsButton, settingsButton.getWidth() - popup.getPreferredSize().width,
                    -popup.getPreferredSize().height - 4);
        });

        manager.addListener(this::sync);
        Timer clock = new Timer(500, e -> updateTime());
        clock.start();
        sync();
    }

    /** The settings popup: a live view of the background plus loop, mute, overlay, fit, change and Black behaviour. */
    private void buildDetails() {
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        monitor.setAlignmentX(Component.LEFT_ALIGNMENT);
        monitor.setPreferredSize(new Dimension(320, 180));
        details.add(monitor);
        details.add(javax.swing.Box.createVerticalStrut(8));

        JPanel toggles = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toggles.add(loop);
        toggles.add(mute);
        JPanel overlayRow = Ui.transparent(new BorderLayout(6, 0));
        overlayRow.add(Ui.small("Overlay"), BorderLayout.WEST);
        overlay.setToolTipText("Darkens the background so text stays readable");
        overlayRow.add(overlay, BorderLayout.CENTER);
        overlayValue.setPreferredSize(new Dimension(32, 10));
        overlayRow.add(overlayValue, BorderLayout.EAST);
        JPanel fitRow = Ui.transparent(new BorderLayout(6, 0));
        fitRow.add(Ui.small("Fit"), BorderLayout.WEST);
        fitRow.add(fit, BorderLayout.CENTER);
        JPanel changeRow = Ui.transparent(new BorderLayout(6, 0));
        changeRow.add(Ui.small("Change"), BorderLayout.WEST);
        JPanel changeFields = Ui.transparent(new GridLayout(1, 2, 4, 0));
        changeFields.add(change);
        ((JSpinner.DefaultEditor) changeSeconds.getEditor()).getTextField().setColumns(3);
        changeSeconds.setToolTipText("Seconds");
        changeFields.add(changeSeconds);
        changeRow.add(changeFields, BorderLayout.CENTER);
        for (JComponent c : new JComponent[]{toggles, overlayRow, fitRow, changeRow, keepInBlack}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            details.add(c);
            details.add(javax.swing.Box.createVerticalStrut(4));
        }

        loop.addActionListener(e -> { if (!loading) manager.setLoop(loop.isSelected()); });
        mute.addActionListener(e -> { if (!loading) manager.setMuted(mute.isSelected()); });
        overlay.addChangeListener(e -> {
            overlayValue.setText(overlay.getValue() + "%");
            if (!loading) manager.setOverlay(overlay.getValue() / 100.0);
        });
        fit.addActionListener(e -> { if (!loading) manager.setFit((FitMode) fit.getSelectedItem()); });
        Runnable changeChanged = () -> {
            if (!loading) {
                manager.setChange((GlobalBackgroundManager.Change) change.getSelectedItem(),
                        (int) Math.round(((Number) changeSeconds.getValue()).doubleValue() * 1000));
            }
        };
        change.addActionListener(e -> changeChanged.run());
        changeSeconds.addChangeListener(e -> changeChanged.run());
        keepInBlack.setToolTipText("Off: the video pauses while the screen is black and continues from the same point");
        keepInBlack.addActionListener(e -> { if (!loading) manager.setKeepPlayingInBlack(keepInBlack.isSelected()); });
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    /** CHANGE → Browse Backgrounds library. */
    public void setBrowseHandler(Runnable h) {
        browseHandler = h;
    }

    /**
     * TAKE AS BACKGROUND: the stored path (library file, color or gradient) becomes the global
     * background under Preview and Program immediately. Scenes and content stay as they are.
     */
    public void takeAsBackground(String storedPath, String name) {
        if (!manager.canShow(storedPath)) {
            Dialogs.showInfo(this, "Background", manager.unavailableReason());
            return;
        }
        manager.takeAsBackground(storedPath);
        statusHandler.accept("\"" + name + "\" is now the background under Preview and Program. Scenes are unchanged.");
    }

    /** Chooses a video (checked by VLC) or an image and takes it as the background. */
    private void choose() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a background video or image");
        MediaChoosers.backgroundFilters(chooser);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        boolean image = GlobalBackgroundManager.isImage(file.toString());
        if (!image && !manager.isAvailable()) {
            Dialogs.showInfo(this, "Background", manager.unavailableReason());
            return;
        }
        changeButton.setEnabled(false);
        statusHandler.accept("Checking \"" + file.getFileName() + "\"…");
        BackgroundTask.run("Choose background", () -> {
            if (image) {
                if (javax.imageio.ImageIO.read(file.toFile()) == null) {
                    throw new com.jamc.worship.service.ValidationException("Unable to open this image. "
                            + "Please use a JPG, PNG, WebP, GIF or BMP file.");
                }
            } else {
                MediaProbe.Result r = MediaProbe.probeVideo(file);
                if (r.status() == MediaProbe.Status.UNPLAYABLE) {
                    throw new com.jamc.worship.service.ValidationException(r.message() + "\n\n" + MediaProbe.RECOMMENDED);
                }
            }
            return importer.importFile(file);
        }, stored -> {
            changeButton.setEnabled(true);
            takeAsBackground(stored, file.getFileName().toString());
        }, err -> {
            changeButton.setEnabled(true);
            Dialogs.showError(this, "This file cannot be used as a background.", err);
        });
    }

    private void sync() {
        loading = true;
        try {
            setVisible(manager.path() != null);
            status.set(manager.status());
            fileLabel.setText(manager.path() == null ? "No background" : displayName(manager.path()));
            fileLabel.setToolTipText(manager.path());
            boolean video = GlobalBackgroundManager.isVideo(manager.path());
            fileLabel.setIcon(Icons.get(video ? "video" : "image", Icons.SMALL));
            onOff.setText(manager.isOn() ? "OFF" : "ON");
            onOff.setToolTipText(manager.isOn() ? "Turn the background off (scenes and content stay as they are)"
                    : "Turn the background back on");
            boolean live = manager.isActive();
            restartButton.setVisible(video);
            pauseButton.setVisible(video);
            restartButton.setEnabled(live);
            pauseButton.setEnabled(live);
            pauseButton.setText(manager.isPaused() ? "Play" : "Pause");
            pauseButton.setIcon(Icons.get(manager.isPaused() ? "play" : "pause", Icons.SMALL));
            loop.setSelected(manager.loop());
            mute.setSelected(manager.muted());
            overlay.setValue((int) Math.round(manager.overlay() * 100));
            overlayValue.setText(overlay.getValue() + "%");
            fit.setSelectedItem(manager.fit());
            change.setSelectedItem(manager.change());
            changeSeconds.setValue(manager.changeMs() / 1000.0);
            keepInBlack.setSelected(manager.keepPlayingInBlack());
            updateTime();
            monitor.repaint();
            revalidate();
        } finally {
            loading = false;
        }
    }

    private void updateTime() {
        long t = manager.position();
        timeLabel.setText(t >= 0 && manager.isActive() ? TransportControls.format(t) : "");
    }

    /** File name of a stored background, or "Solid color #000000" / "Gradient" for built-in ones. */
    public static String displayName(String p) {
        String preset = com.jamc.worship.background.BackgroundPresets.nameOf(p);
        if (preset != null) {
            return preset;
        }
        if (p.startsWith(GlobalBackgroundManager.COLOR_PREFIX)) {
            return "Solid color " + p.substring(GlobalBackgroundManager.COLOR_PREFIX.length());
        }
        if (p.startsWith(GlobalBackgroundManager.GRADIENT_PREFIX)) {
            return "Gradient";
        }
        String s = p.replace('\\', '/');
        return s.substring(s.lastIndexOf('/') + 1);
    }

    /** "● ACTIVE" / "PAUSED" / "OFF" pill. */
    private static final class StatusDot extends JComponent {
        private Status st = Status.NONE;

        void set(Status s) {
            st = s;
            revalidate();
            repaint();
        }

        private String text() {
            return switch (st) {
                case ACTIVE -> "ACTIVE";
                case PAUSED -> "PAUSED";
                case OFF -> "OFF";
                case UNAVAILABLE -> "NO VLC";
                case NONE -> "NONE";
            };
        }

        private Color color() {
            return switch (st) {
                case ACTIVE -> Palette.live();
                case PAUSED -> Palette.warning();
                case UNAVAILABLE -> Palette.danger();
                default -> Palette.mutedText();
            };
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont().deriveFont(Font.BOLD, 10f));
            return new Dimension(fm.stringWidth(text()) + 26, 20);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color c = color();
            int h = getHeight();
            g2.setColor(Palette.withAlpha(c, 45));
            g2.fillRoundRect(0, 0, getWidth(), h, h, h);
            g2.setColor(c);
            g2.fillOval(8, h / 2 - 4, 8, 8);
            g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(Palette.text());
            g2.drawString(text(), 20, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Small live view of the global background, exactly as Preview and Program draw it. */
    private final class Monitor extends JComponent {
        private final Runnable repaint = this::repaint;

        Monitor() {
            setPreferredSize(new Dimension(240, 135));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        }

        @Override
        public void addNotify() {
            super.addNotify();
            MediaFrames.addListener(repaint);
        }

        @Override
        public void removeNotify() {
            MediaFrames.removeListener(repaint);
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!isShowing()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth();
            int h = (int) Math.round(w * 9 / 16.0);
            if (h > getHeight()) {
                h = getHeight();
                w = (int) Math.round(h * 16 / 9.0);
            }
            Rectangle r = new Rectangle(0, 0, w, h);
            g2.setColor(Color.BLACK);
            g2.fillRect(r.x, r.y, r.width, r.height);
            boolean drawn = manager.paint(g2, r);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont().deriveFont(Font.BOLD, 9.5f));
            if (manager.isActive()) {
                String badge = "GLOBAL BACKGROUND";
                FontMetrics fm = g2.getFontMetrics();
                int bw = fm.stringWidth(badge) + 12;
                g2.setColor(Palette.danger());
                g2.fillRoundRect(6, 6, bw, fm.getHeight() + 2, 10, 10);
                g2.setColor(Color.WHITE);
                g2.drawString(badge, 12, 7 + fm.getAscent());
            }
            if (!drawn) {
                g2.setColor(Palette.mutedText());
                String s = manager.path() == null ? "No background chosen" : "Background is off";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(s, (w - fm.stringWidth(s)) / 2, h / 2);
            }
            g2.setColor(Palette.border());
            g2.drawRect(0, 0, w - 1, h - 1);
            g2.dispose();
        }
    }
}
