package com.jamc.worship.ui.components;

import com.jamc.worship.stream.FFmpeg;
import com.jamc.worship.stream.StreamManager;
import com.jamc.worship.stream.StreamSettings;
import com.jamc.worship.stream.SyncTest;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * SYNC TEST (CLAP) for the MIC or AUX channel: records Program for a few seconds while someone claps in front of the camera,
 * measures how late the voice is ({@link SyncTest}) and offers the Sync Offset that lines the voice up with the picture.
 */
final class SyncTestDialog {

    private static final Logger log = LoggerFactory.getLogger(SyncTestDialog.class);
    /** Closer than this is in sync: nobody sees it. */
    private static final int IN_SYNC_MS = 40;
    /** Seconds of "get ready" before CLAP NOW (the recording takes a moment to start). */
    private static final double READY_S = 2.0;

    private final StreamManager stream;
    /** True: the AUX channel (e.g. the Iriun phone microphone) is tested instead of the MIC. */
    private final boolean aux;
    private final int currentMs;
    private final IntConsumer apply;
    private final JDialog d;
    private final JLabel status = Ui.label(" ", Palette::text, "h3");
    private final JLabel detail = Ui.label(" ", Palette::secondaryText, null);
    private final JButton start = Ui.primaryButton("START", null);
    private final JButton use = Ui.primaryButton("USE THIS OFFSET", null);
    private Timer timer;
    private boolean testing;
    private int offered = -1;

    private SyncTestDialog(Component parent, StreamManager stream, boolean aux, int currentMs, IntConsumer apply) {
        this.stream = stream;
        this.aux = aux;
        this.currentMs = currentMs;
        this.apply = apply;
        d = new JDialog(SwingUtilities.getWindowAncestor(parent), "Sync Test", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
    }

    /** {@code apply} gets the new Sync Offset in ms when the operator takes it. */
    static void show(Component parent, StreamManager stream, int currentMs, IntConsumer apply) {
        show(parent, stream, false, currentMs, apply);
    }

    /** The same test for the MIC ({@code aux} false) or the AUX channel. */
    static void show(Component parent, StreamManager stream, boolean aux, int currentMs, IntConsumer apply) {
        new SyncTestDialog(parent, stream, aux, currentMs, apply).open(parent);
    }

    private void open(Component parent) {
        JPanel root = Ui.panel(new BorderLayout(0, 12), Palette::panelBackground);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.add(Ui.label(aux ? "SYNC TEST · AUX MICROPHONE" : "SYNC TEST · MICROPHONE", Palette::text, "h3"), BorderLayout.NORTH);

        JPanel center = Ui.transparent(new BorderLayout(0, 12));
        center.add(Ui.label("<html><div style='width:380px'>"
                + "Lines the voice up with the camera in recordings and the livestream.<br><br>"
                + "1. Put the <b>camera on Program</b> and stand where the camera sees your hands.<br>"
                + "2. Press <b>START</b>. When it says <b>CLAP NOW</b>, clap <b>3 times</b>, about a second apart, "
                + "loud and close to the microphone.<br>"
                + "3. After " + SyncTest.RECORD_SECONDS + " seconds the app tells you how late the voice is and the "
                + "Sync Offset to use.<br><br>"
                + "Now the " + (aux ? "AUX MICROPHONE" : "MICROPHONE") + " waits <b>" + currentMs + " ms</b>.</div></html>", Palette::secondaryText, null),
                BorderLayout.NORTH);
        JPanel result = Ui.transparent(new BorderLayout(0, 4));
        result.add(status, BorderLayout.NORTH);
        result.add(detail, BorderLayout.CENTER);
        center.add(result, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        JButton close = Ui.button("CLOSE", null);
        close.addActionListener(e -> d.dispose());
        start.addActionListener(e -> begin());
        use.setVisible(false);
        use.addActionListener(e -> {
            if (offered >= 0) {
                apply.accept(offered);
                status.setText("Saved: Sync Offset " + offered + " ms");
                detail.setText("<html><div style='width:380px'>Make a short recording and check it. "
                        + "If it is still off, run the test again.</div></html>");
                use.setVisible(false);
                start.setText("TEST AGAIN");
                d.pack();
            }
        });
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(close);
        buttons.add(use);
        buttons.add(start);
        root.add(buttons, BorderLayout.SOUTH);

        d.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (timer != null) {
                    timer.stop();
                }
                if (testing) {
                    testing = false;
                    stream.stopRecording(); // closed during the test: the file is thrown away when it is saved
                }
            }
        });
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(start);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        String problem = problem();
        if (problem != null) {
            show("Cannot test yet", problem);
            start.setEnabled(false);
        }
        d.pack();
        d.setLocationRelativeTo(parent);
        com.jamc.worship.ui.SoftModal.showAndWait(d);
    }

    /** What must change before the test can work, or null. */
    private String problem() {
        StreamSettings s = stream.settings();
        if (aux) {
            if (s.auxDevice() == null) {
                return "Choose the microphone in the AUX channel first.";
            }
            if (s.auxMuted() || s.masterMuted()) {
                return "The AUX (or MASTER) is muted. Unmute it so the claps are heard.";
            }
        } else if (s.micDevice() == null) {
            return "Choose the microphone in the MIC channel first.";
        }
        if (!aux && (s.micMuted() || s.masterMuted())) {
            return "The MIC (or MASTER) is muted. Unmute it so the claps are heard.";
        }
        if (stream.recording().active()) {
            return "A recording is running. Stop it first.";
        }
        FFmpeg.Capabilities caps = stream.capabilities();
        if (caps == null || caps.executable() == null) {
            return "FFmpeg is needed for recording (Stream page).";
        }
        return null;
    }

    private void begin() {
        String problem = problem();
        if (problem != null) {
            show("Cannot test yet", problem);
            return;
        }
        Path file;
        try {
            file = Files.createTempFile("livecat-sync-test-", ".mp4");
        } catch (java.io.IOException e) {
            show("Cannot test", e.getMessage());
            return;
        }
        testing = true;
        offered = -1;
        start.setEnabled(false);
        use.setVisible(false);
        StreamSettings s = stream.settings();
        boolean otherSound = aux ? s.micDevice() != null && !s.micMuted() : s.auxDevice() != null && !s.auxMuted();
        show("Get ready…", otherSound
                ? "Tip: the " + (aux ? "MIC" : "CAMERA / AUX") + " sound channel is on too. If the test fails, mute it "
                        + "and test again (only the " + (aux ? "AUX" : "MIC") + " is measured)."
                : " ");
        stream.startSyncTestRecording(file, error -> SwingUtilities.invokeLater(() -> {
            testing = false;
            if (timer != null) {
                timer.stop();
            }
            delete(file);
            show("The test could not record", error);
            start.setEnabled(true);
        }), saved -> measure(saved, !d.isDisplayable()));
        long began = System.nanoTime();
        timer = new Timer(200, e -> {
            double s2 = (System.nanoTime() - began) / 1e9;
            double total = SyncTest.RECORD_SECONDS + READY_S;
            if (!testing) {
                ((Timer) e.getSource()).stop();
            } else if (s2 < READY_S) {
                status.setText("Get ready…");
            } else if (s2 < total) {
                status.setText("CLAP NOW: 3 times   (" + (int) Math.ceil(total - s2) + " s)");
            } else {
                ((Timer) e.getSource()).stop();
                testing = false;
                status.setText("Measuring…");
                stream.stopRecording();
            }
        });
        timer.start();
    }

    private void measure(Path file, boolean discard) {
        if (discard || !d.isDisplayable()) {
            delete(file);
            return;
        }
        Path ffmpeg = stream.capabilities().executable();
        Thread.ofVirtual().name("sync-test").start(() -> {
            SyncTest.Result r;
            try {
                r = SyncTest.measure(ffmpeg, file);
            } catch (Exception e) {
                log.warn("Sync test could not be measured", e);
                r = new SyncTest.Result(0, 0, 0, "The test recording could not be read: " + e.getMessage());
            } finally {
                delete(file);
            }
            SyncTest.Result result = r;
            SwingUtilities.invokeLater(() -> showResult(result));
        });
    }

    private void showResult(SyncTest.Result r) {
        start.setEnabled(true);
        start.setText("TEST AGAIN");
        if (!r.ok()) {
            show("No answer: try again", r.problem());
            return;
        }
        int late = r.audioLateMs();
        String seen = "Measured from " + r.matched() + " of " + r.claps() + " claps.";
        if (Math.abs(late) <= IN_SYNC_MS) {
            show("In sync ✓", "The voice is " + Math.abs(late) + " ms " + (late >= 0 ? "late" : "early")
                    + ": nobody sees that. Keep the Sync Offset at " + currentMs + " ms. " + seen);
            return;
        }
        offered = SyncTest.newSyncMs(currentMs, late);
        String what = late > 0 ? "The voice is " + late + " ms LATE" : "The voice is " + -late + " ms EARLY";
        String text = "Sync Offset " + currentMs + " ms → " + offered + " ms. " + seen;
        if (late > 0 && currentMs - late < 0) {
            text += " Even with no wait the voice is " + (late - currentMs) + " ms late: the microphone itself is slow "
                    + "(Bluetooth or a phone microphone). Use a cable microphone for the best result.";
        }
        show(what, text);
        use.setText("USE " + offered + " ms");
        use.setVisible(true);
        d.getRootPane().setDefaultButton(use);
        d.pack();
    }

    private void show(String title, String text) {
        status.setText(title);
        detail.setText("<html><div style='width:380px'>" + text + "</div></html>");
        d.pack();
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            log.debug("Sync test file not deleted: {}", e.getMessage());
        }
    }
}
