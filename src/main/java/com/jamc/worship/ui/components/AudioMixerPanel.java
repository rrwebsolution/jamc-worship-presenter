package com.jamc.worship.ui.components;

import com.jamc.worship.media.AudioRouting;
import com.jamc.worship.stream.StreamManager;
import com.jamc.worship.stream.StreamSettings;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The Program audio mixer: MICROPHONE and CAMERA / CAPTURE audio (what the livestream and recordings
 * take in), MEDIA (videos, scene videos and music played by this app, heard in the church and, through
 * the loopback input, online) and MASTER. Each channel has a volume and a mute; nothing is tied together.
 */
public class AudioMixerPanel extends JPanel {

    private final AudioRouting routing;
    private final StreamManager stream;
    private final MixerMeters meters;
    private boolean loading;

    public AudioMixerPanel(AudioRouting routing, StreamManager stream) {
        super(new GridBagLayout());
        this.routing = routing;
        this.stream = stream;
        this.meters = new MixerMeters(stream);
        meters.attach(this);
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        build();
    }

    private void build() {
        removeAll();
        meters.clearMeters();
        loading = true;
        StreamSettings s = stream.settings();
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, 10, 8);
        g.gridx = 0;
        g.gridwidth = 5;
        add(Ui.label("AUDIO MIXER", Palette::text, "h4"), g);
        g.gridwidth = 1;
        g.gridy++;

        channel(g, "MICROPHONE", deviceBox(s.micDevice(), s.auxDevice(), true),
                s.micVolume(), s.micMuted(), v -> saveStream(st -> with(st, v, null, null, null, null, null, null, null)),
                m -> saveStream(st -> with(st, null, m, null, null, null, null, null, null)), s.micDevice() != null,
                s.micDevice(), true);
        channel(g, "CAMERA AUDIO", deviceBox(s.auxDevice(), s.micDevice(), false),
                s.auxVolume(), s.auxMuted(), v -> saveStream(st -> with(st, null, null, v, null, null, null, null, null)),
                m -> saveStream(st -> with(st, null, null, null, m, null, null, null, null)), s.auxDevice() != null,
                s.auxDevice(), true);
        // the app's own sound is heard after its volume is applied, so the wave needs no extra gain
        channel(g, "MEDIA", Ui.label("Videos, scene videos and music from this app", Palette::mutedText, "small"), routing.mediaVolume(), routing.mediaMuted(),
                routing::setMediaVolume, routing::setMediaMuted, true, StreamSettings.APP_AUDIO, false);
        channel(g, "MASTER", Ui.label("Church media and everything online", Palette::mutedText, "small"), routing.masterVolume(), routing.masterMuted(),
                v -> {
                    routing.setMasterVolume(v);
                    saveStream(st -> with(st, null, null, null, null, v, null, null, null));
                },
                m -> {
                    routing.setMasterMuted(m);
                    saveStream(st -> with(st, null, null, null, null, null, m, null, null));
                }, true, null, true);

        g.gridx = 0;
        g.gridwidth = 5;
        g.insets = new Insets(4, 0, 0, 0);
        add(Ui.label("<html><body style='width:420px'>Choose the MICROPHONE and CAMERA AUDIO inputs above; they go into "
                + "the livestream and recordings (changes apply when recording or streaming starts). MEDIA is what the "
                + "church hears from videos and music; recordings take it "
                + (StreamSettings.APP_AUDIO.equals(s.mediaDevice()) ? "straight from this app."
                : s.mediaDevice() == null ? "only when a MEDIA input is chosen on the Stream page." : "from \"" + s.mediaDevice() + "\".")
                + "</body></html>",
                Palette::mutedText, "small"), g);
        loading = false;
        revalidate();
        repaint();
    }

    /**
     * One mixer row. {@code input} is what its wave listens to (null on MASTER, which shows the loudest
     * channel); {@code applyGain} scales the wave by this row's volume and mute.
     */
    private void channel(GridBagConstraints g, String name, javax.swing.JComponent detail, int volume, boolean muted,
                         IntConsumer onVolume, Consumer<Boolean> onMute, boolean enabled, String input, boolean applyGain) {
        JToggleButton speaker = Ui.muteToggle(name.toLowerCase());
        speaker.setSelected(muted);
        JSlider slider = new JSlider(0, 150, Math.clamp(volume, 0, 150));
        slider.setPreferredSize(new Dimension(170, slider.getPreferredSize().height));
        JLabel percent = Ui.label(volume + "%", Palette::text, "semibold");
        percent.setPreferredSize(new Dimension(44, 10));
        speaker.addActionListener(e -> {
            if (!loading) onMute.accept(speaker.isSelected());
        });
        slider.addChangeListener(e -> {
            percent.setText(slider.getValue() + "%");
            if (!loading && !slider.getValueIsAdjusting()) onVolume.accept(slider.getValue());
        });
        speaker.setEnabled(enabled);
        slider.setEnabled(enabled);

        JPanel label = Ui.transparent(new java.awt.BorderLayout(0, 1));
        label.add(Ui.label(name, Palette::text, "semibold"), java.awt.BorderLayout.NORTH);
        detail.setPreferredSize(new Dimension(230, detail.getPreferredSize().height));
        label.add(detail, java.awt.BorderLayout.SOUTH);
        g.insets = new Insets(0, 0, 10, 8);
        g.gridx = 0;
        add(label, g);
        g.gridx = 1;
        add(speaker, g);
        g.gridx = 2;
        add(slider, g);
        g.gridx = 3;
        add(percent, g);
        LevelMeter meter = new LevelMeter();
        java.util.function.DoubleSupplier gain = () -> speaker.isSelected() ? 0 : applyGain ? slider.getValue() / 100.0 : 1;
        if ("MASTER".equals(name)) {
            meters.addMaster(meter, gain);
        } else {
            meters.add(meter, () -> input, gain);
        }
        g.gridx = 4;
        add(meter, g);
        g.gridy++;
    }

    private static final String NONE = "(none)";

    /**
     * Which input this channel takes: "(none)" or one of the sound inputs FFmpeg found (microphones,
     * capture cards, the phone's Iriun microphone…). The same input cannot be chosen for both channels:
     * that would double the sound.
     */
    private javax.swing.JComboBox<String> deviceBox(String chosen, String other, boolean mic) {
        javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>();
        box.addItem(NONE);
        var caps = stream.capabilities();
        if (caps != null) {
            caps.audioDevices().forEach(box::addItem);
        }
        if (chosen != null && (caps == null || !caps.audioDevices().contains(chosen))) {
            box.addItem(chosen); // keep the saved choice even while it is unplugged
        }
        box.setSelectedItem(chosen == null ? NONE : chosen);
        box.setToolTipText(caps == null ? "Looking for sound inputs…" : mic
                ? "The microphone for the livestream and recordings" : "Camera or capture-card sound for the livestream and recordings");
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
            javax.swing.SwingUtilities.invokeLater(this::build); // enables the volume / mute of the channel
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

    /** Fills the lists again (after the sound inputs were detected, or settings changed elsewhere). */
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
}
