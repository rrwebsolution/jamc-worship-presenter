package com.jamc.worship.ui.components;

import com.jamc.worship.stream.MixerChannels;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** The Audio Mixer's Properties (one channel) and Advanced Audio Properties (every channel) dialogs, like OBS's. */
final class AudioChannelDialogs {

    private AudioChannelDialogs() {
    }

    /** A channel in Advanced Audio Properties. */
    record Row(String key, String name, int volume, boolean muted, boolean enabled, IntConsumer onVolume,
               Consumer<Boolean> onMute, Runnable openFilters) {
    }

    private static final String SOUND_NOTE = "Mono, balance, sync offset and filters change the recording and the "
            + "livestream from the next time recording or streaming starts. Volume and mute change at once.";

    // ------------------------------------------------------------------ Properties

    /**
     * One channel: name, input (MIC / CAMERA), volume, mute, mono, balance, sync offset and its filters.
     * {@code deviceBox} is null for channels without an input choice.
     */
    static void properties(Component parent, String name, String defaultName, MixerChannels.Channel c, MixerChannels prefs,
                           int volume, boolean muted, boolean enabled, IntConsumer onVolume, Consumer<Boolean> onMute,
                           Supplier<JComboBox<String>> deviceBox, Runnable openFilters) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), "Properties for " + name,
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JPanel root = Ui.panel(new BorderLayout(0, 12), Palette::panelBackground);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.add(Ui.label("PROPERTIES · " + name, Palette::text, "h3"), BorderLayout.NORTH);

        JPanel form = Ui.transparent(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 8, 10);

        JTextField nameField = new JTextField(name, 20);
        row(form, g, "Name", nameField);
        if (deviceBox != null) {
            JComboBox<String> box = deviceBox.get();
            box.setToolTipText("Changes at once (as in the mixer strip)");
            row(form, g, "Input", box);
        }
        JLabel volumeText = Ui.small(volume + "%");
        JSlider volumeSlider = new JSlider(0, 150, Math.clamp(volume, 0, 150));
        volumeSlider.setOpaque(false);
        volumeSlider.setEnabled(enabled && !c.locked);
        volumeSlider.addChangeListener(e -> volumeText.setText(volumeSlider.getValue() + "%  "
                + AudioMixerPanel.dbText(volumeSlider.getValue())));
        JPanel volumeRow = Ui.transparent(new BorderLayout(6, 0));
        volumeRow.add(volumeSlider, BorderLayout.CENTER);
        volumeRow.add(volumeText, BorderLayout.EAST);
        row(form, g, c.locked ? "Volume (locked)" : "Volume", volumeRow);
        JCheckBox mute = check("Muted", muted);
        mute.setEnabled(enabled);
        row(form, g, "", mute);
        JCheckBox mono = check("Mono (both speakers get the same sound)", c.mono);
        row(form, g, "", mono);
        JSlider balance = balanceSlider(c.balance);
        JLabel balanceText = Ui.small(balanceText(c.balance));
        balance.addChangeListener(e -> balanceText.setText(balanceText(balance.getValue())));
        JPanel balanceRow = Ui.transparent(new BorderLayout(6, 0));
        balanceRow.add(balance, BorderLayout.CENTER);
        balanceRow.add(balanceText, BorderLayout.EAST);
        row(form, g, "Balance", balanceRow);
        JSpinner sync = syncSpinner(c.syncMs);
        JPanel syncRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        syncRow.add(sync);
        syncRow.add(Ui.small("ms  (delays the sound when it comes before the picture)"));
        row(form, g, "Sync Offset", syncRow);
        JButton filters = Ui.button("FILTERS… (" + c.filters.size() + ")", "sliders");
        filters.addActionListener(e -> {
            openFilters.run();
            filters.setText("FILTERS… (" + c.filters.size() + ")");
        });
        row(form, g, "", filters);
        g.gridx = 0;
        g.gridwidth = 2;
        form.add(Ui.label("<html><div style='width:330px'>" + SOUND_NOTE + "</div></html>", Palette::mutedText, "small"), g);
        root.add(form, BorderLayout.CENTER);

        JButton cancel = Ui.button("CANCEL", null);
        cancel.addActionListener(e -> d.dispose());
        JButton save = Ui.primaryButton("SAVE", null);
        save.addActionListener(e -> {
            String n = nameField.getText().strip();
            c.name = n.isEmpty() || n.equals(defaultName) ? null : n;
            c.mono = mono.isSelected();
            c.balance = balance.getValue();
            c.syncMs = ((Number) sync.getValue()).intValue();
            if (volumeSlider.isEnabled() && volumeSlider.getValue() != volume) {
                onVolume.accept(volumeSlider.getValue());
            }
            if (mute.isEnabled() && mute.isSelected() != muted) {
                onMute.accept(mute.isSelected());
            }
            prefs.changed();
            d.dispose();
        });
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(save);
        root.add(buttons, BorderLayout.SOUTH);
        finish(d, root, parent, save);
    }

    // ------------------------------------------------------------------ Advanced Audio Properties

    /** Every channel in one table; changes apply at once. */
    static void advanced(Component parent, List<Row> rows, MixerChannels prefs) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), "Advanced Audio Properties",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JPanel root = Ui.panel(new BorderLayout(0, 12), Palette::panelBackground);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.add(Ui.label("ADVANCED AUDIO PROPERTIES", Palette::text, "h3"), BorderLayout.NORTH);

        JPanel table = Ui.transparent(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, 6, 12);
        String[] heads = {"Name", "Volume", "Mute", "Mono", "Balance", "Sync Offset", "Filters"};
        for (int i = 0; i < heads.length; i++) {
            g.gridx = i;
            table.add(Ui.label(heads[i], Palette::secondaryText, "semibold"), g);
        }
        for (Row r : rows) {
            MixerChannels.Channel c = prefs.channel(r.key());
            g.gridy++;
            g.gridx = 0;
            JLabel name = Ui.label(r.name(), c.hidden ? Palette::mutedText : Palette::text, null);
            name.setToolTipText(c.hidden ? r.name() + " (hidden in the mixer)" : r.name());
            table.add(name, g);
            g.gridx++;
            JSpinner volume = new JSpinner(new SpinnerNumberModel(Math.clamp(r.volume(), 0, 150), 0, 150, 1));
            volume.setEnabled(r.enabled() && !c.locked);
            volume.setToolTipText(c.locked ? "Volume locked (▾ → Lock Volume)" : "% (100 = 0 dB)");
            volume.addChangeListener(e -> r.onVolume().accept(((Number) volume.getValue()).intValue()));
            JPanel vol = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
            vol.add(volume);
            vol.add(Ui.small("%"));
            table.add(vol, g);
            g.gridx++;
            JCheckBox mute = check("", r.muted());
            mute.setEnabled(r.enabled());
            mute.addActionListener(e -> r.onMute().accept(mute.isSelected()));
            table.add(mute, g);
            g.gridx++;
            JCheckBox mono = check("", c.mono);
            mono.addActionListener(e -> {
                c.mono = mono.isSelected();
                prefs.changed();
            });
            table.add(mono, g);
            g.gridx++;
            JSlider balance = balanceSlider(c.balance);
            balance.setPreferredSize(new Dimension(130, balance.getPreferredSize().height));
            balance.setToolTipText(balanceText(c.balance));
            balance.addChangeListener(e -> {
                balance.setToolTipText(balanceText(balance.getValue()));
                if (!balance.getValueIsAdjusting()) {
                    c.balance = balance.getValue();
                    prefs.changed();
                }
            });
            table.add(balance, g);
            g.gridx++;
            JSpinner sync = syncSpinner(c.syncMs);
            sync.addChangeListener(e -> {
                c.syncMs = ((Number) sync.getValue()).intValue();
                prefs.changed();
            });
            JPanel syncCell = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
            syncCell.add(sync);
            syncCell.add(Ui.small("ms"));
            table.add(syncCell, g);
            g.gridx++;
            JButton filters = Ui.button(c.filters.isEmpty() ? "Filters…" : "Filters (" + c.filters.size() + ")…", null);
            filters.addActionListener(e -> {
                r.openFilters().run();
                filters.setText(c.filters.isEmpty() ? "Filters…" : "Filters (" + c.filters.size() + ")…");
            });
            table.add(filters, g);
        }
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        JPanel center = Ui.transparent(new BorderLayout(0, 10));
        center.add(scroll, BorderLayout.CENTER);
        center.add(Ui.label("<html>" + SOUND_NOTE + "</html>", Palette::mutedText, "small"), BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        JButton close = Ui.primaryButton("CLOSE", null);
        close.addActionListener(e -> d.dispose());
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);
        finish(d, root, parent, close);
    }

    // ------------------------------------------------------------------ parts

    private static void row(JPanel form, GridBagConstraints g, String label, JComponent field) {
        g.gridx = 0;
        g.gridwidth = 1;
        g.weightx = 0;
        form.add(Ui.label(label, Palette::secondaryText, "small"), g);
        g.gridx = 1;
        g.weightx = 1;
        form.add(field, g);
        g.gridy++;
    }

    private static JCheckBox check(String text, boolean on) {
        JCheckBox b = new JCheckBox(text, on);
        b.setOpaque(false);
        return b;
    }

    private static JSlider balanceSlider(int value) {
        JSlider s = new JSlider(-100, 100, Math.clamp(value, -100, 100));
        s.setOpaque(false);
        s.setMajorTickSpacing(100);
        s.setPaintTicks(true);
        s.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    s.setValue(0); // double-click: center
                }
            }
        });
        return s;
    }

    static String balanceText(int balance) {
        return balance == 0 ? "Center" : balance < 0 ? "Left " + -balance + "%" : "Right " + balance + "%";
    }

    private static JSpinner syncSpinner(int ms) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(Math.clamp(ms, 0, 5000), 0, 5000, 10));
        s.setPreferredSize(new Dimension(80, s.getPreferredSize().height));
        return s;
    }

    private static void finish(JDialog d, JPanel root, Component parent, JButton defaultButton) {
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(defaultButton);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.pack();
        d.setLocationRelativeTo(parent);
        com.jamc.worship.ui.SoftModal.showAndWait(d);
    }
}
