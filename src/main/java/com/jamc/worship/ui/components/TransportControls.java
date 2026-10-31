package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.media.AudioPlayer;
import com.jamc.worship.media.Transport;
import com.jamc.worship.ui.Ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * Play / pause / stop, seek bar with time, volume, mute and loop for any {@link Transport}.
 * Audio players also get fade in / fade out buttons.
 */
public class TransportControls extends JPanel {

    private Transport transport;
    private final JButton playButton = small("▶");
    private final JButton stopButton = small("■");
    private final JSlider seek = new JSlider(0, 1000, 0);
    private final JLabel time = Ui.small("0:00 / 0:00");
    private final JSlider volume = new JSlider(0, 150, 100);
    private final JToggleButton mute = Ui.muteToggle(null);
    private final JToggleButton loop = Ui.loopToggle();
    private final JButton fadeIn = small("Fade In");
    private final JButton fadeOut = small("Fade Out");
    private final Timer poll = new Timer(250, e -> refresh());
    private boolean dragging;
    private boolean syncing;

    public TransportControls(boolean compact) {
        super(new BorderLayout(6, 2));
        setOpaque(false);

        playButton.setToolTipText("Play / pause");
        playButton.addActionListener(e -> {
            if (transport == null) {
                return;
            }
            if (transport.isPlaying()) {
                transport.pause();
            } else {
                transport.playOrResume();
            }
        });
        stopButton.setToolTipText("Stop");
        stopButton.addActionListener(e -> {
            if (transport != null) {
                transport.stop();
            }
        });
        seek.setToolTipText("Position");
        seek.addChangeListener(e -> {
            dragging = seek.getValueIsAdjusting();
            if (!syncing && !seek.getValueIsAdjusting() && transport != null) {
                transport.seek(seek.getValue() / 1000f);
            }
        });
        volume.setToolTipText("Volume");
        volume.setPreferredSize(new Dimension(compact ? 70 : 100, 20));
        volume.addChangeListener(e -> {
            if (!syncing && transport != null) {
                transport.setVolume(volume.getValue());
            }
        });
        mute.addActionListener(e -> {
            if (transport != null) {
                transport.setMuted(mute.isSelected());
            }
        });
        loop.addActionListener(e -> {
            if (transport != null) {
                transport.setLoop(loop.isSelected());
            }
        });
        fadeIn.addActionListener(e -> {
            if (unwrap(transport) instanceof AudioPlayer a) {
                a.fadeIn(3000);
            }
        });
        fadeOut.addActionListener(e -> {
            if (unwrap(transport) instanceof AudioPlayer a) {
                a.fadeOut(3000);
            }
        });

        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 3, 0));
        buttons.add(playButton);
        buttons.add(stopButton);
        buttons.add(time);
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        right.add(fadeIn);
        right.add(fadeOut);
        right.add(mute);
        right.add(loop);
        right.add(volume);
        add(seek, BorderLayout.NORTH);
        add(buttons, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        bind(null);
    }

    /** The player behind a remembering wrapper (MediaStates). */
    private static Transport unwrap(Transport t) {
        return t instanceof com.jamc.worship.media.MediaStates.Remembered r ? r.delegate() : t;
    }

    /** Connects the controls to a player (or disconnects with null). */
    public void bind(Transport t) {
        this.transport = t;
        boolean has = t != null;
        for (var c : new javax.swing.JComponent[]{playButton, stopButton, seek, volume, mute, loop, fadeIn, fadeOut}) {
            c.setEnabled(has);
        }
        boolean audio = unwrap(t) instanceof AudioPlayer;
        fadeIn.setVisible(audio);
        fadeOut.setVisible(audio);
        if (has) {
            t.addStateListener(this::refresh);
            syncing = true;
            volume.setValue(Math.min(150, t.volume() <= 0 ? 100 : t.volume()));
            syncing = false;
            poll.start();
        } else {
            poll.stop();
        }
        refresh();
    }

    public void refresh() {
        Transport t = transport;
        if (t == null) {
            time.setText("–:–");
            playButton.setText("▶");
            return;
        }
        long len = t.length();
        long pos = t.time();
        time.setText(format(pos) + " / " + format(len));
        playButton.setText(t.isPlaying() ? "❚❚" : "▶");
        if (!dragging && len > 0) {
            syncing = true;
            seek.setValue((int) Math.min(1000, pos * 1000 / len));
            syncing = false;
        }
        mute.setSelected(t.isMuted());
        loop.setSelected(t.isLoop());
    }

    @Override
    public void removeNotify() {
        poll.stop();
        super.removeNotify();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (transport != null) {
            poll.start();
        }
    }

    public static String format(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }

    private static JButton small(String text) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.putClientProperty(FlatClientProperties.STYLE, "font: -1; margin: 2,8,2,8");
        return b;
    }
}
