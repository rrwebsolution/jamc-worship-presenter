package com.jamc.worship.ui.components;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.media.AudioRouting;
import com.jamc.worship.media.AudioRouting.Device;
import com.jamc.worship.stream.StreamSettings;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;

/**
 * Settings → Audio: the Program output (the church's speakers), the Preview monitor device
 * (headphones), what TAKE does with a scene video's position, and whether Program media reaches the
 * livestream and recordings. A device that is unplugged falls back to the Windows default and says so.
 */
public class AudioSettingsPanel extends JPanel {

    private final AudioRouting routing;
    private final AppConfig config;
    private final JComboBox<Device> program = new JComboBox<>();
    private final JComboBox<Device> monitor = new JComboBox<>();
    private final JLabel programWarning = Ui.label(" ", Palette::warning, "small");
    private final JLabel monitorWarning = Ui.label(" ", Palette::warning, "small");
    private final JButton useDefault = Ui.button("USE DEFAULT DEVICE", "refresh");
    private final JButton refresh = Ui.button("Refresh Devices", "refresh");
    private final JLabel streamMedia = Ui.label(" ", Palette::text, null);
    private boolean loading;

    public AudioSettingsPanel(AudioRouting routing, AppConfig config) {
        super();
        setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
        this.routing = routing;
        this.config = config;
        setOpaque(false);

        program.setToolTipText("Program videos, scene videos and music play here (the church's sound system)");
        monitor.setToolTipText("Where you hear Preview when MONITOR is on (e.g. headphones), without the church hearing it");
        program.addActionListener(e -> {
            if (!loading && program.getSelectedItem() instanceof Device d) routing.setProgramDevice(d.id());
            sync();
        });
        monitor.addActionListener(e -> {
            if (!loading && monitor.getSelectedItem() instanceof Device d) routing.setMonitorDevice(d.id());
            sync();
        });
        useDefault.addActionListener(e -> {
            routing.setProgramDevice("");
            fill();
        });
        refresh.addActionListener(e -> refreshDevices());

        JPanel takeRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        takeRow.add(Ui.small("When a scene with a video is taken to Program:"));
        ButtonGroup group = new ButtonGroup();
        for (AudioRouting.TakeBehavior b : AudioRouting.TakeBehavior.values()) {
            JRadioButton r = new JRadioButton(b.toString(), routing.takeBehavior() == b);
            r.setOpaque(false);
            r.addActionListener(e -> routing.setTakeBehavior(b));
            group.add(r);
            takeRow.add(r);
        }

        add(Ui.label("PROGRAM AUDIO OUTPUT", Palette::secondaryText, "semibold"));
        add(flow(Ui.small("Device:"), program, refresh));
        add(flow(programWarning, useDefault));
        add(Ui.label("PREVIEW MONITOR DEVICE", Palette::secondaryText, "semibold"));
        add(flow(Ui.small("Device:"), monitor));
        add(monitorWarning);
        add(takeRow);
        add(Ui.label("STREAM & RECORDING AUDIO", Palette::secondaryText, "semibold"));
        add(streamMedia);
        add(Ui.label("<html>Videos inside a scene are picture only (tick Audio on a scene video to hear it). The sound comes from "
                + "what you play: a Program video, the audio player or Media Preview. Scene videos with Audio on are silent in Preview until you press "
                + "PREVIEW AUDIO under the Preview monitor (it becomes MONITOR ON), so the church never hears a video twice. Global background videos "
                + "stay muted unless you unmute them in the background settings.</html>", Palette::mutedText, "small"));
        fill();
        refreshDevices();
    }

    @Override
    public java.awt.Component add(java.awt.Component c) {
        if (c instanceof JComponent j) {
            j.setAlignmentX(LEFT_ALIGNMENT);
            j.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        }
        return super.add(c);
    }

    private static JComponent flow(JComponent... parts) {
        JPanel p = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (JComponent c : parts) {
            p.add(c);
        }
        return p;
    }

    /** Re-reads the outputs (after plugging in headphones, HDMI or a USB interface). */
    public void refreshDevices() {
        refresh.setEnabled(false);
        BackgroundTask.run("Audio devices", routing::refreshDevices, list -> {
            refresh.setEnabled(true);
            fill();
        }, err -> refresh.setEnabled(true));
    }

    private void fill() {
        loading = true;
        try {
            List<Device> devices = routing.devices();
            for (Object[] pair : new Object[][]{{program, routing.programDevice()}, {monitor, routing.monitorDevice()}}) {
                @SuppressWarnings("unchecked")
                JComboBox<Device> box = (JComboBox<Device>) pair[0];
                String chosen = (String) pair[1];
                box.removeAllItems();
                devices.forEach(box::addItem);
                Device pick = devices.stream().filter(d -> d.id().equals(chosen)).findFirst().orElse(null);
                if (pick == null && !chosen.isEmpty()) {
                    pick = new Device(chosen, "⚠ " + routing.nameOf(chosen) + " (not connected)");
                    box.addItem(pick); // keep the choice visible: it is used again when plugged back in
                }
                box.setSelectedItem(pick == null ? Device.DEFAULT : pick);
            }
        } finally {
            loading = false;
        }
        sync();
    }

    private void sync() {
        boolean programMissing = !routing.available(routing.programDevice());
        programWarning.setText(programMissing ? "⚠ Program audio device unavailable: the Windows default output is used." : " ");
        useDefault.setVisible(programMissing);
        monitorWarning.setText(!routing.available(routing.monitorDevice())
                ? "⚠ Monitor device unavailable: the Windows default output is used." : " ");
        StreamSettings s = StreamSettings.load(config);
        boolean included = s.mediaDevice() != null && !s.mediaMuted();
        streamMedia.setText("<html>" + (included
                ? "✓ Program media audio is included in the livestream and recordings (MEDIA input: \"" + s.mediaDevice() + "\")."
                : "<font color='#F59E0B'>Program media audio is not in the livestream / recordings yet.</font> On the Stream page, "
                + "choose a loopback as the MEDIA input: \"Stereo Mix\", or VB-Cable's \"CABLE Output\" with the Program output above set "
                + "to \"CABLE Input\".") + "</html>");
        if (Objects.equals(program.getSelectedItem(), monitor.getSelectedItem()) && !routing.programDevice().isEmpty()) {
            monitorWarning.setText("Monitor and Program use the same device: monitoring will be heard in the church.");
        }
    }
}
