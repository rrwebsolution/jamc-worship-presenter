package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.stream.FFmpeg;
import com.jamc.worship.stream.SecretStore;
import com.jamc.worship.stream.StreamEncoder;
import com.jamc.worship.stream.StreamManager;
import com.jamc.worship.stream.StreamSettings;
import com.jamc.worship.stream.StreamingProvider;
import com.jamc.worship.stream.StreamingProviders;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Stream page: encoder status, destination (Facebook Live or custom RTMP) with a masked stream
 * key, quality presets, a simple audio mixer, local recording of Program and the pre-stream check.
 * Every change is saved at once; nothing here blocks the Swing thread.
 */
public class StreamPanel extends JPanel {

    private static final String NONE = "(none)";

    private final StreamManager manager;
    private final Consumer<String> status;
    private final JLabel stateLabel = Ui.label("● OFFLINE", Palette::mutedText, "h3");
    private final JLabel ffmpegLabel = Ui.label(" ", Palette::text, null);
    private final JButton refresh = Ui.button("Refresh", "refresh");
    private final JButton locate = Ui.button("Locate FFmpeg…", "folder");

    private final JComboBox<StreamingProvider> platform = new JComboBox<>(StreamingProviders.all().toArray(StreamingProvider[]::new));
    private final JTextField server = new JTextField(34);
    private final JTextField destination = new JTextField(22);
    private final JLabel keyLabel = Ui.label("", Palette::text, null);
    private final JButton showKey = Ui.button("Show", null);
    private final JButton changeKey = Ui.button("Change…", "edit");
    private final JLabel help = Ui.label(" ", Palette::mutedText, "small");
    private final JButton test = Ui.button("Test Connection", "refresh");
    private final JLabel testResult = Ui.small(" ");

    private final JComboBox<Object> preset = new JComboBox<>();
    private final JComboBox<StreamSettings.EncoderChoice> encoder = new JComboBox<>(StreamSettings.EncoderChoice.values());
    private final JComboBox<Integer> videoKbps = new JComboBox<>(new Integer[]{2500, 3000, 4000, 4500, 6000, 8000, 9000, 12000});
    private final JComboBox<Integer> audioKbps = new JComboBox<>(new Integer[]{96, 128, 160, 192, 256});

    private final JComboBox<String> mic = new JComboBox<>();
    private final JSlider micVolume = new JSlider(0, 150, 80);
    private final JToggleButton micMute = Ui.muteToggle("the microphone");
    private final JComboBox<String> aux = new JComboBox<>();
    private final JSlider auxVolume = new JSlider(0, 150, 60);
    private final JToggleButton auxMute = Ui.muteToggle("camera / capture audio");
    private final JComboBox<String> mediaIn = new JComboBox<>();
    private final JSlider mediaVolume = new JSlider(0, 150, 100);
    private final JToggleButton mediaMute = Ui.muteToggle("media in the stream");
    private final JSlider masterVolume = new JSlider(0, 150, 100);
    private final JToggleButton masterMute = Ui.muteToggle("the whole stream");

    private final JButton recordButton = new JButton("● START RECORDING");
    private final JLabel recordStatus = Ui.label("Not recording", Palette::secondaryText, null);
    private final JButton openFolder = Ui.button("Open Recordings Folder", "folder");
    private final JLabel saveFolder = Ui.label("", Palette::text, null);
    private final JButton chooseFolder = Ui.button("Choose Folder…", "folder");
    private final JButton defaultFolder = Ui.button("Use Default", "refresh");
    /** The file of the recording that just ended, to say where it was saved. */
    private java.nio.file.Path lastRecording;

    private final JButton checkButton = Ui.button("Pre-stream Check", "list");
    private final JPanel checkList = Ui.transparent(new GridBagLayout());
    private final JButton startStream = new JButton("START STREAM");

    private boolean loading;

    public StreamPanel(StreamManager manager, Consumer<String> status) {
        super(new BorderLayout());
        this.manager = manager;
        this.status = status;
        setOpaque(false);

        JPanel content = Ui.transparent(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(20, 28, 20, 28));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 18, 0);
        JPanel title = Ui.transparent(new BorderLayout());
        title.add(Ui.label("Stream", Palette::text, "h2"), BorderLayout.WEST);
        title.add(stateLabel, BorderLayout.EAST);
        content.add(title, c);
        c.gridy++;

        content.add(section("Encoder", "FFmpeg turns Program into video. OBS is optional.",
                rows(flow(ffmpegLabel), flow(refresh, locate))), c);
        c.gridy++;

        help.setPreferredSize(new Dimension(560, 34));
        JPanel keyRow = flow(Ui.small("Stream key:"), keyLabel, showKey, changeKey);
        content.add(section("Destination", "Where the livestream goes. Viewers see exactly Program.",
                rows(flow(Ui.small("Platform:"), platform), flow(Ui.small("Server URL:"), server),
                        flow(Ui.small("Facebook Page / channel name:"), destination), keyRow, help,
                        flow(test, testResult))), c);
        c.gridy++;

        content.add(section("Video", "Presets suit most churches; change bitrates only if you know your upload speed.",
                rows(flow(Ui.small("Quality:"), preset, Ui.small("Encoder:"), encoder),
                        flow(Ui.small("Video bitrate (kbps):"), videoKbps, Ui.small("Audio bitrate (kbps):"), audioKbps))), c);
        c.gridy++;

        content.add(section("Audio Mixer", "What viewers and recordings hear. Changes apply when recording or streaming starts.",
                rows(mixerRow("MICROPHONE", mic, micVolume, micMute),
                        mixerRow("CAMERA / CAPTURE AUDIO", aux, auxVolume, auxMute),
                        mixerRow("MEDIA (videos, music)", mediaIn, mediaVolume, mediaMute),
                        mixerRow("MASTER", null, masterVolume, masterMute),
                        Ui.label("<html>MEDIA: \"This app (Program sound)\" records the videos, scene videos and music this app plays, "
                                + "straight from the Program output device (Settings → Audio), with no extra software. Other sounds "
                                + "playing on that device are recorded too. Never choose the same device twice: that doubles the sound.</html>", Palette::mutedText, "small"))), c);
        c.gridy++;

        recordButton.putClientProperty(FlatClientProperties.STYLE, "font: bold +1; margin: 8,16,8,16");
        content.add(section("Recording", "Saves Program (picture and mixed audio) as an MP4 file in the folder below.",
                rows(flow(recordButton, recordStatus), flow(Ui.label("Save recordings to:", Palette::secondaryText, null), saveFolder),
                        flow(chooseFolder, defaultFolder, openFolder))), c);
        c.gridy++;

        startStream.putClientProperty(FlatClientProperties.STYLE,
                "font: bold +2; margin: 10,20,10,20; background: #DC2626; foreground: #FFFFFF");
        content.add(section("Live Stream", "Check everything, then start. You always confirm before going live.",
                rows(flow(checkButton, startStream), checkList)), c);
        c.gridy++;

        c.weighty = 1;
        content.add(Ui.transparent(new BorderLayout()), c);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);

        for (StreamSettings.Preset p : StreamSettings.Preset.values()) {
            preset.addItem(p);
        }
        preset.addItem("Custom");
        wire();
        manager.addListener(() -> SwingUtilities.invokeLater(this::sync));
        new Timer(1000, e -> syncRecording()).start();
        load();
    }

    /** Called when the page is shown: probes FFmpeg the first time. */
    public void activate() {
        if (manager.capabilities() == null && !manager.isProbing()) {
            manager.refreshCapabilities();
        }
        sync();
    }

    // ------------------------------------------------------------------ wiring

    private void wire() {
        refresh.addActionListener(e -> manager.refreshCapabilities());
        locate.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Locate ffmpeg.exe");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                manager.setFFmpegPath(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        platform.addActionListener(e -> {
            if (!loading) {
                StreamingProvider p = (StreamingProvider) platform.getSelectedItem();
                server.setText(p.defaultServer() == null ? "" : p.defaultServer());
                save();
                syncKey();
            }
        });
        for (JTextField f : new JTextField[]{server, destination}) {
            f.addActionListener(e -> save());
            f.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    save();
                }
            });
        }
        showKey.addActionListener(e -> {
            String key = manager.streamKey(provider());
            boolean showing = "Hide".equals(showKey.getText());
            keyLabel.setText(showing || key == null ? SecretStore.mask(key) : key);
            showKey.setText(showing ? "Show" : "Hide");
        });
        changeKey.addActionListener(e -> changeKey());
        test.addActionListener(e -> {
            test.setEnabled(false);
            testResult.setText("Testing…");
            StreamSettings s = current();
            BackgroundTask.run("Test connection", () -> {
                String r = manager.testConnection(s);
                return r == null ? "" : r;
            }, r -> {
                test.setEnabled(true);
                testResult.setText(r.isEmpty() ? "✓ The server answers (the stream key is not sent, nothing goes live)." : "✗ " + r);
                testResult.setForeground(r.isEmpty() ? Palette.live() : Palette.danger());
            }, err -> test.setEnabled(true));
        });
        preset.addActionListener(e -> {
            if (!loading && preset.getSelectedItem() instanceof StreamSettings.Preset p) {
                loading = true;
                videoKbps.setSelectedItem(p.videoKbps);
                loading = false;
                manager.saveSettings(current().withPreset(p));
            }
        });
        for (JComboBox<?> box : List.of(encoder, videoKbps, audioKbps, mic, aux, mediaIn)) {
            box.addActionListener(e -> save());
        }
        for (JSlider s : List.of(micVolume, auxVolume, masterVolume, mediaVolume)) {
            s.addChangeListener(e -> {
                if (!s.getValueIsAdjusting()) {
                    save();
                }
            });
        }
        for (JToggleButton b : List.of(micMute, auxMute, masterMute, mediaMute)) {
            b.addActionListener(e -> save());
        }
        chooseFolder.addActionListener(e -> {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(manager.recordingsDir().toFile());
            chooser.setDialogTitle("Save recordings to");
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showDialog(this, "Use This Folder") == javax.swing.JFileChooser.APPROVE_OPTION) {
                java.nio.file.Path folder = chooser.getSelectedFile().toPath();
                if (!Files.isDirectory(folder) || !Files.isWritable(folder)) {
                    Dialogs.showInfo(this, "Recordings folder", "Recordings cannot be saved in \"" + folder
                            + "\". Choose a folder you can write to.");
                    return;
                }
                manager.setRecordingsDir(folder);
                status.accept("Recordings are saved in " + folder + (manager.recording().active()
                        ? " (from the next recording; the current one stays where it started)." : "."));
            }
        });
        defaultFolder.addActionListener(e -> {
            manager.setRecordingsDir(null);
            status.accept("Recordings are saved in the default folder: " + manager.recordingsDir());
        });
        recordButton.addActionListener(e -> {
            if (manager.recording().finishing()) {
                return; // already stopping
            }
            if (manager.recording().active()) {
                manager.stopRecording();
                status.accept("Recording stopped. Saving the MP4…");
            } else {
                if (!chooseSoundIfSilent()) {
                    return;
                }
                manager.startRecording(msg -> Dialogs.showInfo(this, "Recording", msg));
                status.accept("Recording Program…");
            }
        });
        openFolder.addActionListener(e -> {
            try {
                Files.createDirectories(manager.recordingsDir());
                Desktop.getDesktop().open(manager.recordingsDir().toFile());
            } catch (Exception ex) {
                Dialogs.showError(this, "Could not open the recordings folder.", ex);
            }
        });
        checkButton.addActionListener(e -> runCheck(false));
        startStream.addActionListener(e -> runCheck(true));
    }

    private void changeKey() {
        StreamingProvider p = provider();
        JPasswordField field = new JPasswordField(32);
        int ok = JOptionPane.showConfirmDialog(this, new Object[]{"Paste the " + p.displayName() + " stream key:", field,
                        manager.secrets().isEncrypted() ? "It is stored encrypted for this Windows user and never written to logs."
                                : "Warning: secure storage is not available here; the key is stored encoded only."},
                "Stream Key", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok == JOptionPane.OK_OPTION) {
            char[] typed = field.getPassword();
            String key = new String(typed).strip();
            java.util.Arrays.fill(typed, ' ');
            manager.setStreamKey(p, key.isEmpty() ? null : key);
            showKey.setText("Show");
            syncKey();
        }
    }

    /** START / STOP RECORDING from anywhere (the top bar): exactly what the button on this page does. */
    public void toggleRecording() {
        recordButton.doClick();
    }

    /** START STREAM from anywhere (the top bar): the pre-stream check, then the confirmation. */
    public void startStream() {
        runCheck(true);
    }

    /** Runs the pre-stream check in the background; with {@code start}, asks to confirm afterwards. */
    private void runCheck(boolean start) {
        StreamSettings s = current();
        FFmpeg.Capabilities caps = manager.capabilities();
        checkButton.setEnabled(false);
        startStream.setEnabled(false);
        showChecks(List.of(new StreamManager.Check("Checking…", StreamManager.Check.Level.OK, "")));
        BackgroundTask.run("Pre-stream check", () -> manager.preStreamCheck(s, caps), checks -> {
            checkButton.setEnabled(true);
            startStream.setEnabled(true);
            showChecks(checks);
            if (!start) {
                return;
            }
            List<StreamManager.Check> problems = checks.stream()
                    .filter(ch -> ch.level() == StreamManager.Check.Level.PROBLEM).toList();
            if (!problems.isEmpty()) {
                Dialogs.showInfo(this, "Not ready to stream", "Please fix:\n\n" + String.join("\n",
                        problems.stream().map(p -> "• " + p.label() + ": " + p.detail()).toList()));
                return;
            }
            Object[] options = {"CANCEL", "START LIVE"};
            int choice = JOptionPane.showOptionDialog(this, "Start " + s.provider().displayName() + "?\n\n"
                            + (s.provider().asksDestinationName() ? "Facebook Page:  " + (s.destinationName() == null ? "(not named)" : s.destinationName()) + "\n" : "")
                            + "Resolution:  " + s.width() + " × " + s.height() + "\nFPS:  " + s.fps(),
                    "Start livestream", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (choice == 1) {
                Dialogs.showInfo(this, "Live Stream", "Everything is ready. Direct streaming is switched on in the next "
                        + "update, after local recording has been tested on this computer.\n\n"
                        + "For now, record Program here, or stream with OBS (Window Capture of \"JAMC Worship - OBS Output\").");
            }
        }, err -> {
            checkButton.setEnabled(true);
            startStream.setEnabled(true);
            Dialogs.showError(this, "The pre-stream check failed.", err);
        });
    }

    private void showChecks(List<StreamManager.Check> checks) {
        checkList.removeAll();
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 0, 2, 10);
        for (StreamManager.Check ch : checks) {
            g.gridx = 0;
            String mark = switch (ch.level()) {
                case OK -> "✓";
                case WARNING -> "!";
                case PROBLEM -> "✗";
            };
            JLabel m = Ui.label(mark, ch.level() == StreamManager.Check.Level.OK ? Palette::live
                    : ch.level() == StreamManager.Check.Level.WARNING ? Palette::warning : Palette::danger, "semibold");
            checkList.add(m, g);
            g.gridx = 1;
            checkList.add(Ui.label(ch.label(), Palette::text, "semibold"), g);
            g.gridx = 2;
            g.weightx = 1;
            checkList.add(Ui.label(ch.detail(), Palette::secondaryText, "small"), g);
            g.weightx = 0;
            g.gridy++;
        }
        checkList.revalidate();
        checkList.repaint();
    }

    // ------------------------------------------------------------------ state

    private StreamingProvider provider() {
        return (StreamingProvider) platform.getSelectedItem();
    }

    /**
     * START RECORDING with no sound input chosen in the Audio Mixer would record a silent file: offer the
     * microphones FFmpeg can hear (the computer's own first, then e.g. the phone's Iriun microphone). The
     * choice is kept as the MICROPHONE input. False = cancelled.
     */
    private boolean chooseSoundIfSilent() {
        StreamSettings s = current();
        boolean hasSound = (s.micDevice() != null && !s.micMuted()) || (s.auxDevice() != null && !s.auxMuted())
                || (s.mediaDevice() != null && !s.mediaMuted());
        FFmpeg.Capabilities caps = manager.capabilities();
        if (hasSound || caps == null || caps.audioDevices().isEmpty()) {
            return true;
        }
        List<String> mics = new java.util.ArrayList<>(caps.audioDevices());
        mics.sort(java.util.Comparator.comparing(d -> d.toLowerCase(java.util.Locale.ROOT).contains("iriun") ? 1 : 0));
        JComboBox<String> pick = new JComboBox<>(mics.toArray(String[]::new));
        String[] options = {"RECORD WITH THIS MICROPHONE", "RECORD WITHOUT SOUND", "CANCEL"};
        int answer = JOptionPane.showOptionDialog(this, new Object[]{
                        "No sound input is chosen in the Audio Mixer, so the recording would have no sound.",
                        "Record with this microphone:", pick,
                        Ui.label("<html>Sound of videos and music played in this app needs a loopback input (\"Stereo Mix\" "
                                + "or VB-Cable) as MEDIA in the Audio Mixer.</html>", Palette::mutedText, "small")},
                "Recording sound", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (answer == 0) {
            mic.setSelectedItem(pick.getSelectedItem());
            micMute.setSelected(false);
            save();
            status.accept("Recording with the microphone \"" + pick.getSelectedItem() + "\" (Audio Mixer → MICROPHONE).");
            return true;
        }
        return answer == 1;
    }

    private StreamSettings current() {
        StreamSettings old = manager.settings();
        return new StreamSettings(provider().id(), server.getText(), destination.getText(), old.width(), old.height(), old.fps(),
                (Integer) videoKbps.getSelectedItem(), (Integer) audioKbps.getSelectedItem(),
                (StreamSettings.EncoderChoice) encoder.getSelectedItem(), device(mic), micVolume.getValue(), micMute.isSelected(),
                device(aux), auxVolume.getValue(), auxMute.isSelected(), masterVolume.getValue(), masterMute.isSelected(),
                device(mediaIn), mediaVolume.getValue(), mediaMute.isSelected());
    }

    private static String device(JComboBox<String> box) {
        Object v = box.getSelectedItem();
        return v == null || NONE.equals(v) ? null : v.toString();
    }

    private void save() {
        if (loading) {
            return;
        }
        StreamSettings s = current();
        if (s.micDevice() != null && s.micDevice().equals(s.auxDevice())) {
            Dialogs.showInfo(this, "Audio Mixer", "\"" + s.micDevice() + "\" is already the microphone. Choosing it twice "
                    + "would double the sound, so the second input was set to none.");
            loading = true;
            aux.setSelectedItem(NONE);
            loading = false;
            s = current();
        }
        if (s.mediaDevice() != null && (s.mediaDevice().equals(s.micDevice()) || s.mediaDevice().equals(s.auxDevice()))) {
            Dialogs.showInfo(this, "Audio Mixer", "\"" + s.mediaDevice() + "\" is already used above. Choosing it twice "
                    + "would double the sound, so the media input was set to none.");
            loading = true;
            mediaIn.setSelectedItem(NONE);
            loading = false;
            s = current();
        }
        manager.saveSettings(s);
    }

    private void load() {
        loading = true;
        try {
            StreamSettings s = manager.settings();
            platform.setSelectedItem(s.provider());
            server.setText(s.server() != null ? s.server() : s.provider().defaultServer() == null ? "" : s.provider().defaultServer());
            destination.setText(s.destinationName() == null ? "" : s.destinationName());
            Object p = s.preset();
            preset.setSelectedItem(p == null ? "Custom" : p);
            encoder.setSelectedItem(s.encoder());
            videoKbps.setSelectedItem(s.videoKbps());
            audioKbps.setSelectedItem(s.audioKbps());
            micVolume.setValue(s.micVolume());
            micMute.setSelected(s.micMuted());
            auxVolume.setValue(s.auxVolume());
            auxMute.setSelected(s.auxMuted());
            masterVolume.setValue(s.masterVolume());
            masterMute.setSelected(s.masterMuted());
            mediaVolume.setValue(s.mediaVolume());
            mediaMute.setSelected(s.mediaMuted());
        } finally {
            loading = false;
        }
        sync();
    }

    private void sync() {
        FFmpeg.Capabilities caps = manager.capabilities();
        if (manager.isProbing()) {
            ffmpegLabel.setText("Looking for FFmpeg and testing encoders…");
            ffmpegLabel.setForeground(Palette.secondaryText());
        } else if (caps == null) {
            ffmpegLabel.setText("Not checked yet.");
        } else if (!caps.available()) {
            ffmpegLabel.setText("<html><font color='#F59E0B'>" + StreamManager.ffmpegMissingMessage() + "</font></html>");
        } else {
            String hw = caps.workingHardware().isEmpty() ? "no hardware encoder" : "hardware: " + String.join(", ", caps.workingHardware());
            ffmpegLabel.setText("<html>FFmpeg " + caps.version() + " ready · " + hw + (caps.software() ? " · software x264" : "")
                    + " · will use <b>" + caps.encoderFor((StreamSettings.EncoderChoice) encoder.getSelectedItem()) + "</b></html>");
            ffmpegLabel.setForeground(Palette.text());
        }
        refresh.setEnabled(!manager.isProbing());
        fillDevices(caps);
        syncKey();
        syncRecording();
    }

    private void fillDevices(FFmpeg.Capabilities caps) {
        StreamSettings s = manager.settings();
        loading = true;
        try {
            for (JComboBox<String> box : List.of(mic, aux, mediaIn)) {
                String chosen = box == mic ? s.micDevice() : box == aux ? s.auxDevice() : s.mediaDevice();
                box.removeAllItems();
                box.addItem(NONE);
                if (box == mediaIn) {
                    box.addItem(StreamSettings.APP_AUDIO); // this app's own sound: no Stereo Mix / VB-Cable needed
                }
                if (caps != null) {
                    caps.audioDevices().forEach(box::addItem);
                }
                if (chosen != null && !StreamSettings.APP_AUDIO.equals(chosen) && (caps == null || !caps.audioDevices().contains(chosen))) {
                    box.addItem(chosen); // keep the saved choice even when it is unplugged now
                }
                box.setSelectedItem(chosen == null ? NONE : chosen);
            }
        } finally {
            loading = false;
        }
    }

    private void syncKey() {
        StreamingProvider p = provider();
        boolean has = manager.hasStreamKey(p);
        if (!"Hide".equals(showKey.getText())) {
            keyLabel.setText(has ? SecretStore.mask(manager.streamKey(p)) : "not set");
        }
        showKey.setEnabled(has);
        help.setText("<html>" + p.setupHelp() + "</html>");
        destination.setEnabled(p.asksDestinationName());
    }

    private void syncRecording() {
        StreamManager.Session r = manager.recording();
        boolean active = r.active();
        java.nio.file.Path folder = manager.recordingsDir();
        saveFolder.setText(folder.toString() + (folder.equals(manager.defaultRecordingsDir()) ? "  (default)" : ""));
        defaultFolder.setEnabled(!folder.equals(manager.defaultRecordingsDir()));
        recordButton.setText(r.finishing() ? "SAVING…" : active ? "■ STOP RECORDING" : "● START RECORDING");
        recordButton.setEnabled(!r.finishing());
        recordButton.putClientProperty(FlatClientProperties.STYLE, active
                ? "font: bold +1; margin: 8,16,8,16; background: #DC2626; foreground: #FFFFFF"
                : "font: bold +1; margin: 8,16,8,16");
        if (r.state() == StreamManager.State.ERROR) {
            recordStatus.setText("Recording stopped: " + r.detail());
            recordStatus.setForeground(Palette.danger());
        } else if (active) {
            StreamEncoder.Stats st = r.stats();
            String time = elapsed(r.startedMillis());
            recordStatus.setText("● REC " + time + (r.detail() != null ? "  ·  " + r.detail() : "")
                    + (st == null ? "" : String.format("  ·  %.0f fps  ·  %.1f Mbps  ·  dropped %d", st.fps(),
                    st.bitrateKbps() / 1000, st.droppedFrames()))
                    + (r.file() == null ? "" : "  ·  " + r.file().getFileName()));
            recordStatus.setForeground(Palette.live());
            lastRecording = r.file();
        } else if (lastRecording != null) {
            recordStatus.setText("Saved: " + lastRecording);
            recordStatus.setForeground(Palette.secondaryText());
        } else {
            recordStatus.setText("Not recording");
            recordStatus.setForeground(Palette.secondaryText());
        }
        stateLabel.setText(active ? "● REC " + elapsed(r.startedMillis()) : "● OFFLINE");
        stateLabel.setForeground(active ? Palette.live() : Palette.mutedText());
    }

    /** "00:21:15" since {@code startedMillis}. */
    public static String elapsed(long startedMillis) {
        long s = Math.max(0, (System.currentTimeMillis() - startedMillis) / 1000);
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    // ------------------------------------------------------------------ helpers

    private JComponent mixerRow(String name, JComboBox<String> device, JSlider volume, JToggleButton mute) {
        JPanel p = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel n = Ui.label(name, Palette::secondaryText, "semibold");
        n.setPreferredSize(new Dimension(200, n.getPreferredSize().height));
        p.add(n);
        if (device != null) {
            device.setPrototypeDisplayValue("Microphone Array (Intel Smart Sound)");
            p.add(device);
        }
        volume.setPreferredSize(new Dimension(160, volume.getPreferredSize().height));
        JLabel value = Ui.small(volume.getValue() + "%");
        volume.addChangeListener(e -> value.setText(volume.getValue() + "%"));
        p.add(volume);
        p.add(value);
        p.add(mute);
        return p;
    }

    private static JPanel rows(JComponent... rows) {
        JPanel p = Ui.transparent(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 8, 0);
        for (JComponent r : rows) {
            p.add(r, g);
            g.gridy++;
        }
        return p;
    }

    private static JPanel flow(JComponent... items) {
        JPanel p = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (JComponent i : items) {
            p.add(i);
        }
        return p;
    }

    private static JComponent section(String title, String subtitle, JComponent body) {
        JPanel p = Ui.panel(new BorderLayout(0, 10), Palette::secondaryBackground);
        p.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, true, true, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        JPanel head = Ui.transparent(new BorderLayout(0, 2));
        head.add(Ui.label(title, Palette::text, "h4"), BorderLayout.NORTH);
        head.add(Ui.small(subtitle), BorderLayout.SOUTH);
        p.add(head, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }
}
