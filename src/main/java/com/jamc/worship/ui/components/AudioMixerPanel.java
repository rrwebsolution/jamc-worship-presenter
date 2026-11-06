package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.media.AudioRouting;
import com.jamc.worship.stream.StreamManager;
import com.jamc.worship.stream.StreamSettings;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The Program audio mixer, always on screen under Preview / Program (like a desk or OBS): one vertical
 * strip per channel with its fader, level meter, dB and mute. MICROPHONE and CAMERA AUDIO (what the
 * livestream and recordings take in, input chosen in the strip), MEDIA (videos, scene videos and music
 * played by this app), the Program scene's live inputs (Audio Input sources, cameras with AUDIO on) and
 * MASTER. Nothing is tied together.
 */
public class AudioMixerPanel extends JPanel {

    private static final int STRIP_WIDTH = 72;

    private final AudioRouting routing;
    private final StreamManager stream;
    private final MixerMeters meters;
    private final com.jamc.worship.media.CameraAudio sceneAudio;
    private final JPanel strips = new StripRow();
    private boolean loading;
    private Object filledCaps;

    public AudioMixerPanel(AudioRouting routing, StreamManager stream, com.jamc.worship.media.CameraAudio sceneAudio) {
        super(new BorderLayout());
        this.routing = routing;
        this.stream = stream;
        this.sceneAudio = sceneAudio;
        this.meters = new MixerMeters(stream);
        setOpaque(false);
        meters.attach(this);

        JPanel header = Ui.transparent(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(6, 10, 4, 6));
        JLabel title = Ui.label("Audio Mixer", Palette::text, "h4");
        title.setToolTipText("<html>MICROPHONE and CAMERA AUDIO go into the livestream and recordings (choose the input "
                + "in the strip; changes apply when recording or streaming starts).<br>MEDIA is what the church hears from "
                + "videos and music. MASTER is the church media and everything online.<br>Double-click a fader to set it back "
                + "to 0 dB.</html>");
        header.add(title, BorderLayout.WEST);
        JButton refresh = Ui.toolButton("refresh", "Look for sound inputs again (e.g. a Bluetooth headset or USB microphone just connected)");
        refresh.addActionListener(e -> {
            filledCaps = null;
            stream.refreshCapabilities();
        });
        JPanel tools = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        tools.add(refresh);
        header.add(tools, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        strips.setLayout(new BoxLayout(strips, BoxLayout.X_AXIS));
        strips.setOpaque(false);
        JScrollPane scroll = new JScrollPane(strips, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);

        // another scene on Program: its audio inputs appear; the input lists fill once FFmpeg listed them
        sceneAudio.addListener(() -> SwingUtilities.invokeLater(this::build));
        stream.addListener(() -> SwingUtilities.invokeLater(() -> {
            var caps = stream.capabilities();
            if (caps != null && caps != filledCaps && !stream.isProbing()) {
                build();
            }
        }));
        // back on screen (e.g. from the Stream page, which has the same channels): show the current values
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(this::build);
            }
        });
        if (stream.capabilities() == null && !stream.isProbing()) {
            stream.refreshCapabilities();
        }
        build();
    }

    private void build() {
        strips.removeAll();
        meters.clearMeters();
        loading = true;
        filledCaps = stream.capabilities();
        StreamSettings s = stream.settings();

        strip("MIC", "INPUT", deviceBox(s.micDevice(), s.auxDevice(), true), "The microphone for the livestream and recordings",
                s.micVolume(), s.micMuted(), v -> saveStream(st -> with(st, v, null, null, null, null, null, null, null)),
                m -> saveStream(st -> with(st, null, m, null, null, null, null, null, null)), s.micDevice() != null,
                s.micDevice(), true);
        strip("CAMERA", "INPUT", deviceBox(s.auxDevice(), s.micDevice(), false),
                "Camera or capture-card sound for the livestream and recordings",
                s.auxVolume(), s.auxMuted(), v -> saveStream(st -> with(st, null, null, v, null, null, null, null, null)),
                m -> saveStream(st -> with(st, null, null, null, m, null, null, null, null)), s.auxDevice() != null,
                s.auxDevice(), true);
        // the app's own sound is heard after its volume is applied, so the meter needs no extra gain
        strip("MEDIA", "APP", null, "Videos, scene videos and music from this app"
                        + (StreamSettings.APP_AUDIO.equals(s.mediaDevice()) ? "; recordings take it straight from this app."
                        : s.mediaDevice() == null ? "; recordings take it only when a MEDIA input is chosen on the Stream page."
                        : "; recordings take it from \"" + s.mediaDevice() + "\"."),
                routing.mediaVolume(), routing.mediaMuted(), routing::setMediaVolume, routing::setMediaMuted, true,
                StreamSettings.APP_AUDIO, false);
        for (var in : sceneAudio.programInputs()) {
            String device = in.device();
            strip(in.name().toUpperCase(java.util.Locale.ROOT), "PROGRAM", null,
                    device == null ? "This camera has no audio input"
                            : device.equals(s.micDevice()) || device.equals(s.auxDevice()) ? "Used here instead of the same input in MICROPHONE / CAMERA AUDIO"
                            : "On Program now: into the recording and the stream (" + device + ")",
                    in.volume(), in.muted(), v -> sceneAudio.setInputVolume(device, v), m -> sceneAudio.setInputMuted(device, m),
                    device != null, device, true);
        }
        strip("MASTER", "MASTER", null, "Church media and everything online", routing.masterVolume(), routing.masterMuted(),
                v -> {
                    routing.setMasterVolume(v);
                    saveStream(st -> with(st, null, null, null, null, v, null, null, null));
                },
                m -> {
                    routing.setMasterMuted(m);
                    saveStream(st -> with(st, null, null, null, null, null, m, null, null));
                }, true, null, true);
        strips.add(Box.createHorizontalGlue());
        loading = false;
        strips.revalidate();
        strips.repaint();
    }

    /**
     * One channel strip. {@code input} is what its meter listens to (null on MASTER, which shows the loudest
     * channel); {@code applyGain} scales the meter by this strip's fader and mute.
     */
    private void strip(String name, String badge, JComboBox<String> device, String tip, int volume, boolean muted,
                       IntConsumer onVolume, Consumer<Boolean> onMute, boolean enabled, String input, boolean applyGain) {
        JPanel strip = new JPanel(new BorderLayout(0, 4)) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(STRIP_WIDTH, super.getPreferredSize().height);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(STRIP_WIDTH, Integer.MAX_VALUE);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(STRIP_WIDTH, super.getMinimumSize().height);
            }
        };
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, false, true),
                BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        strip.setAlignmentY(Component.TOP_ALIGNMENT);

        JPanel top = Ui.transparent(null);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel badgeLabel = new JLabel(badge, SwingConstants.CENTER) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
            }
        };
        badgeLabel.setOpaque(true);
        Color badgeColor = "MASTER".equals(badge) ? Palette.live() : "PROGRAM".equals(badge) ? new Color(0xB45309) : Palette.accent();
        badgeLabel.setBackground(Palette.withAlpha(badgeColor, 120));
        badgeLabel.setForeground(Color.WHITE);
        badgeLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -3");
        badgeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        top.add(badgeLabel);
        top.add(Box.createVerticalStrut(3));
        JLabel title = Ui.label(name, Palette::text, null);
        title.putClientProperty(FlatClientProperties.STYLE, "font: bold -2");
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setMaximumSize(new Dimension(STRIP_WIDTH - 8, title.getPreferredSize().height));
        title.setToolTipText("<html><b>" + name + "</b><br>" + tip + "</html>");
        top.add(title);
        if (device != null) {
            top.add(Box.createVerticalStrut(2));
            device.putClientProperty(FlatClientProperties.STYLE, "font: -2");
            Dimension d = new Dimension(STRIP_WIDTH - 8, device.getPreferredSize().height);
            device.setPreferredSize(d);
            device.setMaximumSize(d);
            device.setAlignmentX(Component.CENTER_ALIGNMENT);
            top.add(device);
        }
        JLabel db = Ui.label(dbText(volume), Palette::secondaryText, null);
        db.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        db.setAlignmentX(Component.CENTER_ALIGNMENT);
        top.add(Box.createVerticalStrut(3));
        top.add(db);
        strip.add(top, BorderLayout.NORTH);

        JSlider fader = new JSlider(SwingConstants.VERTICAL, 0, 150, Math.clamp(volume, 0, 150));
        fader.setOpaque(false);
        fader.setFocusable(false);
        fader.setPreferredSize(new Dimension(26, 140));
        fader.setMinimumSize(new Dimension(26, 50));
        fader.setToolTipText(name + " volume: " + volume + "%  (double-click: back to 0 dB)");
        fader.addChangeListener(e -> {
            db.setText(dbText(fader.getValue()));
            fader.setToolTipText(name + " volume: " + fader.getValue() + "%  (double-click: back to 0 dB)");
            if (!loading && !fader.getValueIsAdjusting()) onVolume.accept(fader.getValue());
        });
        fader.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && fader.isEnabled()) {
                    fader.setValue(100);
                }
            }
        });
        VuMeter meter = new VuMeter();
        JPanel middle = Ui.transparent(new BorderLayout(2, 0));
        middle.add(fader, BorderLayout.WEST);
        middle.add(meter, BorderLayout.CENTER);
        strip.add(middle, BorderLayout.CENTER);

        JToggleButton speaker = Ui.muteToggle(name.toLowerCase(java.util.Locale.ROOT));
        speaker.setSelected(muted);
        speaker.addActionListener(e -> {
            if (!loading) onMute.accept(speaker.isSelected());
        });
        JPanel bottom = Ui.transparent(new FlowLayout(FlowLayout.CENTER, 0, 0));
        bottom.add(speaker);
        strip.add(bottom, BorderLayout.SOUTH);

        speaker.setEnabled(enabled);
        fader.setEnabled(enabled);

        java.util.function.DoubleSupplier gain = () -> speaker.isSelected() ? 0 : applyGain ? fader.getValue() / 100.0 : 1;
        if ("MASTER".equals(name)) {
            meters.addMaster(meter, gain);
        } else {
            meters.add(meter, () -> input, gain);
        }
        strips.add(strip);
    }

    /** "0.0 dB" for 100 %, "-inf dB" at 0. */
    static String dbText(int percent) {
        if (percent <= 0) {
            return "-inf dB";
        }
        double db = 20 * Math.log10(percent / 100.0);
        return String.format(java.util.Locale.ROOT, "%.1f dB", Math.abs(db) < 0.05 ? 0.0 : db);
    }

    private static final String NONE = "(none)";

    /**
     * Which input this channel takes: "(none)" or one of the sound inputs FFmpeg found (microphones,
     * capture cards, the phone's Iriun microphone…). The same input cannot be chosen for both channels:
     * that would double the sound.
     */
    private JComboBox<String> deviceBox(String chosen, String other, boolean mic) {
        JComboBox<String> box = new JComboBox<>();
        box.addItem(NONE);
        var caps = stream.capabilities();
        if (caps != null) {
            caps.audioDevices().forEach(box::addItem);
        }
        if (chosen != null && (caps == null || !caps.audioDevices().contains(chosen))) {
            box.addItem(chosen); // keep the saved choice even while it is unplugged
        }
        box.setSelectedItem(chosen == null ? NONE : chosen);
        box.setToolTipText(caps == null ? "Looking for sound inputs…" : (chosen == null ? "" : chosen + " · ") + (mic
                ? "The microphone for the livestream and recordings" : "Camera or capture-card sound for the livestream and recordings"));
        box.addActionListener(e -> {
            if (loading) {
                return;
            }
            Object v = box.getSelectedItem();
            String device = v == null || NONE.equals(v) ? null : v.toString();
            if (device != null && device.equals(other)) {
                javax.swing.JOptionPane.showMessageDialog(this, "\"" + device + "\" is already chosen as "
                        + (mic ? "CAMERA AUDIO" : "MICROPHONE") + ". Choosing it twice would double the sound.",
                        "Audio Mixer", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                loading = true;
                box.setSelectedItem(chosen == null ? NONE : chosen);
                loading = false;
                return;
            }
            saveStream(st -> withDevice(st, mic, device));
            SwingUtilities.invokeLater(this::build); // enables the fader / mute of the channel
        });
        return box;
    }

    /** A copy of {@code s} with the microphone ({@code mic}) or camera audio input changed. */
    static StreamSettings withDevice(StreamSettings s, boolean mic, String device) {
        return new StreamSettings(s.providerId(), s.server(), s.destinationName(), s.width(), s.height(), s.fps(),
                s.videoKbps(), s.audioKbps(), s.encoder(),
                mic ? device : s.micDevice(), s.micVolume(), s.micMuted(),
                mic ? s.auxDevice() : device, s.auxVolume(), s.auxMuted(),
                s.masterVolume(), s.masterMuted(), s.mediaDevice(), s.mediaVolume(), s.mediaMuted());
    }

    /** Fills the strips again (after settings changed elsewhere, e.g. on the Stream page). */
    public void reload() {
        build();
    }

    private void saveStream(java.util.function.UnaryOperator<StreamSettings> change) {
        if (!loading) {
            stream.saveSettings(change.apply(stream.settings()));
        }
    }

    /** A copy of {@code s} with the given mixer values changed (null = unchanged). */
    static StreamSettings with(StreamSettings s, Integer micVolume, Boolean micMuted, Integer auxVolume, Boolean auxMuted,
                               Integer masterVolume, Boolean masterMuted, Integer mediaVolume, Boolean mediaMuted) {
        return new StreamSettings(s.providerId(), s.server(), s.destinationName(), s.width(), s.height(), s.fps(),
                s.videoKbps(), s.audioKbps(), s.encoder(),
                s.micDevice(), micVolume != null ? micVolume : s.micVolume(), micMuted != null ? micMuted : s.micMuted(),
                s.auxDevice(), auxVolume != null ? auxVolume : s.auxVolume(), auxMuted != null ? auxMuted : s.auxMuted(),
                masterVolume != null ? masterVolume : s.masterVolume(), masterMuted != null ? masterMuted : s.masterMuted(),
                s.mediaDevice(), mediaVolume != null ? mediaVolume : s.mediaVolume(), mediaMuted != null ? mediaMuted : s.mediaMuted());
    }

    /** The strips fill the mixer's height; a horizontal scroll bar appears when they do not fit across. */
    private static final class StripRow extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
            return STRIP_WIDTH;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() != null && getParent().getWidth() > getPreferredSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getMinimumSize().height;
        }
    }
}
