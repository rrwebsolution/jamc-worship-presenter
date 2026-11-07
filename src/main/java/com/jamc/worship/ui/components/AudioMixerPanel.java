package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.media.AudioRouting;
import com.jamc.worship.stream.AudioFilter;
import com.jamc.worship.stream.MixerChannels;
import com.jamc.worship.stream.StreamManager;
import com.jamc.worship.stream.StreamSettings;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The Program audio mixer, always on screen under Preview / Program (like a desk or OBS): one strip per channel with
 * its fader, level meter, dB and mute. MICROPHONE and CAMERA AUDIO (what the livestream and recordings take in, input
 * chosen in the strip), MEDIA (videos, scene videos and music played by this app), the Program scene's live inputs
 * (Audio Input sources, cameras with AUDIO on) and MASTER. Nothing is tied together.
 *
 * <p>Like OBS: each channel's ▾ menu (or a right-click) has Hide / Unhide, Pin, Lock Volume, Copy / Paste Filters,
 * Rename, Filters and Properties; the ⋯ button has Unhide All, the inactive / hidden channel options, the horizontal or
 * vertical layout and Advanced Audio Properties. What is set there is kept in {@link MixerChannels}.
 */
public class AudioMixerPanel extends JPanel {

    private static final int STRIP_WIDTH = 72;
    private static final int ROW_NAME_WIDTH = 132;
    /** Copy Filters → Paste Filters, between channels. */
    private static List<AudioFilter> copiedFilters;

    private final AudioRouting routing;
    private final StreamManager stream;
    private final MixerMeters meters;
    private final com.jamc.worship.media.CameraAudio sceneAudio;
    private final MixerChannels prefs = MixerChannels.get();
    private final StripRow strips = new StripRow();
    private final JScrollPane scroll;
    private boolean loading;
    private Object filledCaps;
    /** Every channel as last built (also the hidden / inactive ones), for Advanced Audio Properties. */
    private List<Spec> allSpecs = List.of();

    /**
     * One mixer channel: its key in {@link MixerChannels}, its own name, what its fader / mute change, what its meter
     * listens to. {@code device} is the input list (MIC / CAMERA only); {@code micChannel} says which of the two.
     */
    private record Spec(String key, String defaultName, String badge, JComboBox<String> device, Boolean micChannel,
                        String tip, int volume, boolean muted, IntConsumer onVolume, Consumer<Boolean> onMute,
                        boolean enabled, String input, boolean applyGain, boolean notRecorded, boolean active) {
        boolean master() {
            return MixerChannels.MASTER.equals(key);
        }
    }

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
                + "to 0 dB. Right-click a channel (or its ▾) for Hide, Pin, Lock Volume, Filters and Properties.</html>");
        header.add(title, BorderLayout.WEST);
        JButton refresh = Ui.toolButton("refresh", "Look for sound inputs again (e.g. a Bluetooth headset or USB microphone just connected)");
        refresh.addActionListener(e -> {
            filledCaps = null;
            stream.refreshCapabilities();
        });
        JButton options = Ui.toolButton("more-horizontal", "Audio Mixer options: hidden and inactive channels, layout, "
                + "Advanced Audio Properties");
        options.addActionListener(e -> optionsMenu().show(options, 0, options.getHeight()));
        JPanel tools = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        tools.add(refresh);
        tools.add(options);
        header.add(tools, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        strips.setOpaque(false);
        scroll = new JScrollPane(strips, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);

        // another scene on Program: its audio inputs appear; the input lists fill once FFmpeg listed them
        sceneAudio.addListener(() -> SwingUtilities.invokeLater(this::build));
        stream.addListener(() -> SwingUtilities.invokeLater(() -> {
            var caps = stream.capabilities();
            if (caps != null && caps != filledCaps && !stream.isProbing()) {
                build();
            }
        }));
        prefs.addListener(() -> SwingUtilities.invokeLater(this::build)); // hidden, pinned, renamed, layout…
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

    // ------------------------------------------------------------------ channels

    private List<Spec> specs() {
        StreamSettings s = stream.settings();
        List<Spec> out = new ArrayList<>();
        out.add(new Spec(MixerChannels.MIC, "MIC", "INPUT", deviceBox(s.micDevice(), s.auxDevice(), true), true,
                "The microphone for the livestream and recordings", s.micVolume(), s.micMuted(),
                v -> saveStream(st -> with(st, v, null, null, null, null, null, null, null)),
                m -> saveStream(st -> with(st, null, m, null, null, null, null, null, null)), s.micDevice() != null,
                s.micDevice(), true, false, s.micDevice() != null));
        out.add(new Spec(MixerChannels.AUX, "CAMERA", "INPUT", deviceBox(s.auxDevice(), s.micDevice(), false), false,
                "Camera or capture-card sound for the livestream and recordings", s.auxVolume(), s.auxMuted(),
                v -> saveStream(st -> with(st, null, null, v, null, null, null, null, null)),
                m -> saveStream(st -> with(st, null, null, null, m, null, null, null, null)), s.auxDevice() != null,
                s.auxDevice(), true, false, s.auxDevice() != null));
        // the app's own sound is heard after its volume is applied, so the meter needs no extra gain
        out.add(new Spec(MixerChannels.MEDIA, "MEDIA", "APP", null, null, "Videos, scene videos and music from this app"
                + (StreamSettings.APP_AUDIO.equals(s.mediaDevice()) ? "; recordings take it straight from this app."
                : s.mediaDevice() == null ? "; recordings take it only when a MEDIA input is chosen on the Stream page."
                : "; recordings take it from \"" + s.mediaDevice() + "\"."),
                routing.mediaVolume(), routing.mediaMuted(), routing::setMediaVolume, routing::setMediaMuted, true,
                StreamSettings.APP_AUDIO, false, false, true));
        for (var in : sceneAudio.programInputs()) {
            String device = in.device();
            out.add(new Spec(device == null ? "scene:" + in.name() : MixerChannels.input(device),
                    in.name().toUpperCase(java.util.Locale.ROOT), "PROGRAM", null, null,
                    device == null ? "This camera has no audio input"
                            : in.monitorOnly() ? "Heard on the monitor device only: NOT in the recording or the stream, so no wave is shown ("
                            + device + ")"
                            : device.equals(s.micDevice()) || device.equals(s.auxDevice()) ? "Used here instead of the same input in MICROPHONE / CAMERA AUDIO"
                            : "On Program now: into the recording and the stream (" + device + ")",
                    in.volume(), in.muted(), v -> sceneAudio.setInputVolume(device, v), m -> sceneAudio.setInputMuted(device, m),
                    device != null, device, true, device != null && in.monitorOnly(), device != null));
        }
        out.add(new Spec(MixerChannels.MASTER, "MASTER", "MASTER", null, null, "Church media and everything online",
                routing.masterVolume(), routing.masterMuted(),
                v -> {
                    routing.setMasterVolume(v);
                    saveStream(st -> with(st, null, null, null, null, v, null, null, null));
                },
                m -> {
                    routing.setMasterMuted(m);
                    saveStream(st -> with(st, null, null, null, null, null, m, null, null));
                }, true, null, true, false, true));
        return out;
    }

    private MixerChannels.Channel settings(String key) {
        return prefs.channel(key);
    }

    private String nameOf(Spec spec) {
        return prefs.name(spec.key(), spec.defaultName());
    }

    /**
     * Which channels show, in which order: pinned first, then the others, then (when asked) the inactive ones and the
     * hidden ones on the right; MASTER always last.
     */
    private List<Spec> shown(List<Spec> all) {
        MixerChannels.Options o = prefs.options();
        List<Spec> pinned = new ArrayList<>();
        List<Spec> normal = new ArrayList<>();
        List<Spec> inactive = new ArrayList<>();
        List<Spec> hidden = new ArrayList<>();
        Spec master = null;
        for (Spec spec : all) {
            MixerChannels.Channel c = settings(spec.key());
            if (spec.master()) {
                master = c.hidden && !o.showHidden ? null : spec;
            } else if (c.hidden) {
                if (o.showHidden) {
                    hidden.add(spec);
                }
            } else if (c.pinned) {
                pinned.add(spec);
            } else if (!spec.active()) {
                if (o.showInactive) {
                    (o.keepInactiveRight ? inactive : normal).add(spec);
                }
            } else {
                normal.add(spec);
            }
        }
        List<Spec> out = new ArrayList<>(pinned);
        out.addAll(normal);
        out.addAll(inactive);
        out.addAll(hidden);
        if (master != null) {
            out.add(master);
        }
        return out;
    }

    private void build() {
        strips.removeAll();
        meters.clearMeters();
        loading = true;
        filledCaps = stream.capabilities();
        boolean horizontal = prefs.options().horizontal;
        strips.horizontal = horizontal;
        strips.setLayout(new BoxLayout(strips, horizontal ? BoxLayout.Y_AXIS : BoxLayout.X_AXIS));
        allSpecs = specs();
        List<Spec> shown = shown(allSpecs);
        for (Spec spec : shown) {
            if (horizontal) {
                row(spec);
            } else {
                strip(spec);
            }
        }
        if (shown.isEmpty()) {
            JLabel none = Ui.label("All channels are hidden. ⋯ → Unhide All brings them back.", Palette::mutedText, "small");
            none.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            strips.add(none);
        }
        strips.add(horizontal ? Box.createVerticalGlue() : Box.createHorizontalGlue());
        loading = false;
        strips.revalidate();
        strips.repaint();
    }

    // ------------------------------------------------------------------ vertical layout: one strip per channel

    /**
     * One channel strip. {@code input} is what its meter listens to (null on MASTER, which shows the loudest
     * channel); {@code applyGain} scales the meter by this strip's fader and mute.
     */
    private void strip(Spec spec) {
        MixerChannels.Channel c = settings(spec.key());
        String name = nameOf(spec);
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
        JLabel badgeLabel = badge(spec, c);
        badgeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        top.add(badgeLabel);
        top.add(Box.createVerticalStrut(3));
        JPanel titleRow = Ui.transparent(new BorderLayout(0, 0));
        JLabel title = title(spec, c, name);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(menuButton(spec), BorderLayout.EAST);
        titleRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        titleRow.setMaximumSize(new Dimension(STRIP_WIDTH - 8, title.getPreferredSize().height + 2));
        top.add(titleRow);
        if (spec.device() != null) {
            top.add(Box.createVerticalStrut(2));
            JComboBox<String> device = spec.device();
            device.putClientProperty(FlatClientProperties.STYLE, "font: -2");
            Dimension d = new Dimension(STRIP_WIDTH - 8, device.getPreferredSize().height);
            device.setPreferredSize(d);
            device.setMaximumSize(d);
            device.setAlignmentX(Component.CENTER_ALIGNMENT);
            top.add(device);
        }
        JLabel db = Ui.label(dbText(spec.volume()), Palette::secondaryText, null);
        db.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        db.setAlignmentX(Component.CENTER_ALIGNMENT);
        top.add(Box.createVerticalStrut(3));
        top.add(db);
        strip.add(top, BorderLayout.NORTH);

        JSlider fader = fader(spec, c, name, db, SwingConstants.VERTICAL);
        fader.setPreferredSize(new Dimension(26, 140));
        fader.setMinimumSize(new Dimension(26, 50));
        VuMeter meter = new VuMeter();
        JPanel middle = Ui.transparent(new BorderLayout(2, 0));
        middle.add(fader, BorderLayout.WEST);
        middle.add(meter, BorderLayout.CENTER);
        strip.add(middle, BorderLayout.CENTER);

        JToggleButton speaker = speaker(spec, name);
        JPanel bottom = Ui.transparent(new FlowLayout(FlowLayout.CENTER, 0, 0));
        bottom.add(speaker);
        strip.add(bottom, BorderLayout.SOUTH);

        connectMeter(spec, meter, fader, speaker);
        addMenu(strip, spec);
        strips.add(strip);
    }

    // ------------------------------------------------------------------ horizontal layout: one row per channel

    private void row(Spec spec) {
        MixerChannels.Channel c = settings(spec.key());
        String name = nameOf(spec);
        JPanel row = new JPanel(new BorderLayout(8, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(4, 8, 4, 6)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel left = Ui.transparent(null);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JPanel names = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        names.add(badge(spec, c));
        names.add(title(spec, c, name));
        names.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(names);
        if (spec.device() != null) {
            JComboBox<String> device = spec.device();
            device.putClientProperty(FlatClientProperties.STYLE, "font: -2");
            device.setAlignmentX(Component.LEFT_ALIGNMENT);
            device.setMaximumSize(new Dimension(ROW_NAME_WIDTH, device.getPreferredSize().height));
            left.add(Box.createVerticalStrut(2));
            left.add(device);
        }
        Dimension lw = new Dimension(ROW_NAME_WIDTH, left.getPreferredSize().height);
        left.setPreferredSize(lw);
        left.setMinimumSize(lw);
        row.add(left, BorderLayout.WEST);

        JLabel db = Ui.label(dbText(spec.volume()), Palette::secondaryText, null);
        db.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        db.setPreferredSize(new Dimension(52, db.getPreferredSize().height));
        JSlider fader = fader(spec, c, name, db, SwingConstants.HORIZONTAL);
        LevelMeter meter = new LevelMeter();
        JPanel middle = Ui.transparent(new BorderLayout(0, 2));
        middle.add(meter, BorderLayout.CENTER);
        middle.add(fader, BorderLayout.SOUTH);
        row.add(middle, BorderLayout.CENTER);

        JToggleButton speaker = speaker(spec, name);
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right.add(db);
        right.add(speaker);
        right.add(menuButton(spec));
        row.add(right, BorderLayout.EAST);

        connectMeter(spec, meter, fader, speaker);
        addMenu(row, spec);
        strips.add(row);
    }

    // ------------------------------------------------------------------ strip parts

    private JLabel badge(Spec spec, MixerChannels.Channel c) {
        JLabel badgeLabel = new JLabel(c.pinned ? "📌 " + spec.badge() : spec.badge(), SwingConstants.CENTER) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
            }
        };
        badgeLabel.setOpaque(true);
        Color badgeColor = spec.master() ? Palette.live() : "PROGRAM".equals(spec.badge()) ? new Color(0xB45309) : Palette.accent();
        badgeLabel.setBackground(Palette.withAlpha(c.hidden ? Palette.mutedText() : badgeColor, 120));
        badgeLabel.setForeground(Color.WHITE);
        badgeLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -3");
        return badgeLabel;
    }

    private JLabel title(Spec spec, MixerChannels.Channel c, String name) {
        JLabel title = Ui.label(name, c.hidden || !spec.active() ? Palette::mutedText : Palette::text, null);
        title.putClientProperty(FlatClientProperties.STYLE, "font: bold -2");
        title.setToolTipText("<html><b>" + name + "</b>" + (c.hidden ? " (hidden)" : "") + (c.pinned ? " (pinned)" : "")
                + (c.locked ? " (volume locked)" : "") + (c.filters.isEmpty() ? "" : " · " + c.filters.size()
                + (c.filters.size() == 1 ? " filter" : " filters")) + "<br>" + spec.tip() + "</html>");
        return title;
    }

    private JSlider fader(Spec spec, MixerChannels.Channel c, String name, JLabel db, int orientation) {
        JSlider fader = new JSlider(orientation, 0, 150, Math.clamp(spec.volume(), 0, 150));
        fader.setOpaque(false);
        fader.setFocusable(false);
        fader.setToolTipText(faderTip(name, spec.volume(), c.locked));
        fader.addChangeListener(e -> {
            db.setText(dbText(fader.getValue()));
            fader.setToolTipText(faderTip(name, fader.getValue(), c.locked));
            if (!loading && !fader.getValueIsAdjusting()) spec.onVolume().accept(fader.getValue());
        });
        fader.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && fader.isEnabled()) {
                    fader.setValue(100);
                }
            }
        });
        fader.setEnabled(spec.enabled() && !c.locked); // Lock Volume: the fader cannot move by accident
        return fader;
    }

    private static String faderTip(String name, int volume, boolean locked) {
        return name + " volume: " + volume + "%" + (locked ? "  (locked: ▾ → Lock Volume to change it)" : "  (double-click: back to 0 dB)");
    }

    private JToggleButton speaker(Spec spec, String name) {
        JToggleButton speaker = Ui.muteToggle(name.toLowerCase(java.util.Locale.ROOT));
        speaker.setSelected(spec.muted());
        speaker.addActionListener(e -> {
            if (!loading) spec.onMute().accept(speaker.isSelected());
        });
        speaker.setEnabled(spec.enabled());
        return speaker;
    }

    private void connectMeter(Spec spec, MixerMeters.Meter meter, JSlider fader, JToggleButton speaker) {
        java.util.function.DoubleSupplier gain = () -> speaker.isSelected() ? 0 : spec.applyGain() ? fader.getValue() / 100.0 : 1;
        String input = spec.input();
        if (spec.master()) {
            meters.addMaster(meter, gain);
        } else if (spec.notRecorded()) {
            meters.addSilent(meter, "not recorded");
        } else {
            meters.add(meter, () -> input, gain);
        }
    }

    private JButton menuButton(Spec spec) {
        JButton b = Ui.toolButton("chevron-down", nameOf(spec) + ": Hide, Pin, Lock Volume, Filters, Properties…");
        b.putClientProperty(FlatClientProperties.STYLE, "margin: 0,0,0,0");
        b.setPreferredSize(new Dimension(16, 16));
        b.addActionListener(e -> channelMenu(spec).show(b, 0, b.getHeight()));
        return b;
    }

    /** Right-click anywhere on the channel opens its menu too. */
    private void addMenu(JComponent area, Spec spec) {
        MouseAdapter popup = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                show(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                show(e);
            }

            private void show(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    channelMenu(spec).show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };
        addRecursively(area, popup);
    }

    private static void addRecursively(Component c, MouseAdapter l) {
        if (c instanceof JSlider || c instanceof JComboBox<?>) {
            return; // their own mouse handling stays
        }
        c.addMouseListener(l);
        if (c instanceof java.awt.Container k) {
            for (Component child : k.getComponents()) {
                addRecursively(child, l);
            }
        }
    }

    // ------------------------------------------------------------------ menus

    /** A channel's menu: Hide, Unhide, Pin, Lock Volume, Copy / Paste Filters, Rename, Filters, Properties. */
    private JPopupMenu channelMenu(Spec spec) {
        MixerChannels.Channel c = settings(spec.key());
        String name = nameOf(spec);
        JPopupMenu m = new JPopupMenu();
        JMenuItem hide = item("Hide", () -> {
            c.hidden = true;
            prefs.changed();
        });
        hide.setEnabled(!c.hidden);
        hide.setToolTipText("Takes the channel off the mixer; its sound is not changed. ⋯ → Unhide All brings it back.");
        m.add(hide);
        JMenuItem unhide = item("Unhide", () -> {
            c.hidden = false;
            prefs.changed();
        });
        unhide.setEnabled(c.hidden);
        m.add(unhide);
        JCheckBoxMenuItem pin = new JCheckBoxMenuItem("Pin", c.pinned);
        pin.setToolTipText("Keeps the channel first in the mixer, and shown even while it is inactive");
        pin.addActionListener(e -> {
            c.pinned = pin.isSelected();
            prefs.changed();
        });
        m.add(pin);
        JCheckBoxMenuItem lock = new JCheckBoxMenuItem("Lock Volume", c.locked);
        lock.setToolTipText("The fader cannot be moved (mute still works)");
        lock.addActionListener(e -> {
            c.locked = lock.isSelected();
            prefs.changed();
        });
        m.add(lock);
        m.addSeparator();
        JMenuItem copy = item("Copy Filters", () -> copiedFilters = c.filters.stream().map(AudioFilter::copy).toList());
        copy.setEnabled(!c.filters.isEmpty());
        m.add(copy);
        JMenuItem paste = item("Paste Filters", () -> {
            for (AudioFilter f : copiedFilters) {
                c.filters.add(f.copy());
            }
            prefs.changed();
        });
        paste.setEnabled(copiedFilters != null && !copiedFilters.isEmpty());
        m.add(paste);
        m.addSeparator();
        m.add(item("Rename…", () -> rename(spec)));
        m.add(item("Filters…", () -> AudioFiltersDialog.show(this, name, spec.key())));
        m.add(item("Properties…", () -> properties(spec)));
        return m;
    }

    /** The ⋯ menu: Unhide All, the channel options, the layout and Advanced Audio Properties. */
    private JPopupMenu optionsMenu() {
        MixerChannels.Options o = prefs.options();
        JPopupMenu m = new JPopupMenu();
        m.add(item("Unhide All", prefs::unhideAll));
        m.addSeparator();
        m.add(option("Show Inactive Sources", o.showInactive, v -> o.showInactive = v,
                "Also show MIC / CAMERA with no input chosen and Program inputs without a sound device"));
        m.add(option("Show Hidden Sources (to the Right)", o.showHidden, v -> o.showHidden = v,
                "Hidden channels show dimmed at the end, where ▾ → Unhide brings one back"));
        m.add(option("Keep Inactive Sources to the Right", o.keepInactiveRight, v -> o.keepInactiveRight = v,
                "Inactive channels (when shown) go after the active ones"));
        m.addSeparator();
        ButtonGroup layout = new ButtonGroup();
        JRadioButtonMenuItem horizontal = new JRadioButtonMenuItem("Horizontal Layout", o.horizontal);
        JRadioButtonMenuItem vertical = new JRadioButtonMenuItem("Vertical Layout", !o.horizontal);
        horizontal.setToolTipText("One row per channel with a horizontal fader");
        vertical.setToolTipText("One column per channel with an upright fader, like a mixing desk");
        horizontal.addActionListener(e -> {
            o.horizontal = true;
            prefs.changed();
        });
        vertical.addActionListener(e -> {
            o.horizontal = false;
            prefs.changed();
        });
        layout.add(horizontal);
        layout.add(vertical);
        m.add(horizontal);
        m.add(vertical);
        m.addSeparator();
        m.add(item("Advanced Audio Properties…", this::advancedProperties));
        return m;
    }

    private JCheckBoxMenuItem option(String text, boolean on, Consumer<Boolean> set, String tip) {
        JCheckBoxMenuItem i = new JCheckBoxMenuItem(text, on);
        i.setToolTipText(tip);
        i.addActionListener(e -> {
            set.accept(i.isSelected());
            prefs.changed();
        });
        return i;
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> action.run());
        return i;
    }

    // ------------------------------------------------------------------ rename, properties, advanced

    private void rename(Spec spec) {
        Object answer = javax.swing.JOptionPane.showInputDialog(this, "Name for this channel (empty = " + spec.defaultName() + "):",
                "Rename", javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, nameOf(spec));
        if (answer != null) {
            String n = answer.toString().strip();
            settings(spec.key()).name = n.isEmpty() || n.equals(spec.defaultName()) ? null : n;
            prefs.changed();
        }
    }

    private void properties(Spec spec) {
        AudioChannelDialogs.properties(this, nameOf(spec), spec.defaultName(), settings(spec.key()), prefs,
                spec.volume(), spec.muted(), spec.enabled(),
                v -> {
                    spec.onVolume().accept(v);
                    build();
                },
                m -> {
                    spec.onMute().accept(m);
                    build();
                },
                spec.micChannel() == null ? null : () -> deviceBox(spec.micChannel() ? stream.settings().micDevice()
                        : stream.settings().auxDevice(), spec.micChannel() ? stream.settings().auxDevice()
                        : stream.settings().micDevice(), spec.micChannel()),
                () -> AudioFiltersDialog.show(this, nameOf(spec), spec.key()));
    }

    private void advancedProperties() {
        List<AudioChannelDialogs.Row> rows = new ArrayList<>();
        for (Spec spec : allSpecs) {
            rows.add(new AudioChannelDialogs.Row(spec.key(), nameOf(spec), spec.volume(), spec.muted(), spec.enabled(),
                    v -> {
                        spec.onVolume().accept(v);
                        build();
                    },
                    m -> {
                        spec.onMute().accept(m);
                        build();
                    },
                    () -> AudioFiltersDialog.show(this, nameOf(spec), spec.key())));
        }
        AudioChannelDialogs.advanced(this, rows, prefs);
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

    /**
     * Vertical layout: the strips fill the mixer's height and a horizontal scroll bar appears when they do not fit
     * across. Horizontal layout: the rows fill its width and scroll up and down.
     */
    private static final class StripRow extends JPanel implements Scrollable {
        boolean horizontal;

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
            return horizontal ? 60 : STRIP_WIDTH;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return horizontal || (getParent() != null && getParent().getWidth() > getPreferredSize().width);
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return !horizontal && getParent() != null && getParent().getHeight() > getMinimumSize().height;
        }
    }
}
