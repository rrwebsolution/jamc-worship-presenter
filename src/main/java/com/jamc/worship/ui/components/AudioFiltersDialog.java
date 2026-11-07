package com.jamc.worship.ui.components;

import com.jamc.worship.stream.AudioFilter;
import com.jamc.worship.stream.MixerChannels;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
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

/**
 * Filters for one Audio Mixer channel, like OBS's audio filters: Noise Suppression, Noise Gate, Gain, Compressor and
 * Limiter, in the order they are listed. They change the sound of the recording and the livestream from the next
 * time recording or streaming starts. Changes are kept only on SAVE.
 */
final class AudioFiltersDialog extends JDialog {

    private final DefaultListModel<AudioFilter> model = new DefaultListModel<>();
    private final JList<AudioFilter> list = new JList<>(model);
    private final JPanel editor = Ui.transparent(new GridBagLayout());
    private boolean saved;

    private AudioFiltersDialog(Component parent, String channel, List<AudioFilter> filters) {
        super(SwingUtilities.getWindowAncestor(parent), "Filters for " + channel, ModalityType.APPLICATION_MODAL);
        filters.forEach(f -> model.addElement(f.copy()));

        JPanel root = Ui.panel(new BorderLayout(12, 10), Palette::panelBackground);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.add(Ui.label("FILTERS · " + channel, Palette::text, "h3"), BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                AudioFilter f = (AudioFilter) v;
                return super.getListCellRendererComponent(l, (f.enabled ? "☑ " : "☐ ") + f.name, i, sel, focus);
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showEditor();
            }
        });
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(190, 240));

        JButton add = Ui.toolButton("plus", "Add a filter");
        add.addActionListener(e -> addMenu().show(add, 0, add.getHeight()));
        JButton remove = Ui.toolButton("trash", "Remove the selected filter");
        remove.addActionListener(e -> {
            int i = list.getSelectedIndex();
            if (i >= 0) {
                model.remove(i);
                list.setSelectedIndex(Math.min(i, model.size() - 1));
                showEditor();
            }
        });
        JButton up = Ui.toolButton("arrow-up", "Earlier in the chain");
        up.addActionListener(e -> move(-1));
        JButton down = Ui.toolButton("arrow-down", "Later in the chain");
        down.addActionListener(e -> move(1));
        JPanel listTools = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        listTools.add(add);
        listTools.add(remove);
        listTools.add(up);
        listTools.add(down);
        JPanel left = Ui.transparent(new BorderLayout(0, 4));
        left.add(listScroll, BorderLayout.CENTER);
        left.add(listTools, BorderLayout.SOUTH);
        root.add(left, BorderLayout.WEST);

        editor.setPreferredSize(new Dimension(320, 240));
        root.add(editor, BorderLayout.CENTER);

        JLabel note = Ui.label("<html>Filters change the sound of the recording and the livestream, in this order "
                + "(top first). They take effect when recording or streaming starts.</html>", Palette::mutedText, "small");
        JButton cancel = Ui.button("CANCEL", null);
        cancel.addActionListener(e -> dispose());
        JButton save = Ui.primaryButton("SAVE", null);
        save.addActionListener(e -> {
            saved = true;
            dispose();
        });
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(save);
        JPanel south = Ui.transparent(new BorderLayout(0, 8));
        south.add(note, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
        showEditor();
        pack();
        setLocationRelativeTo(parent);
    }

    private JPopupMenu addMenu() {
        JPopupMenu m = new JPopupMenu();
        for (AudioFilter.Type t : AudioFilter.Type.values()) {
            JMenuItem i = new JMenuItem(t.label());
            i.setToolTipText(describe(t));
            i.addActionListener(e -> {
                model.addElement(AudioFilter.of(t));
                list.setSelectedIndex(model.size() - 1);
            });
            m.add(i);
        }
        return m;
    }

    static String describe(AudioFilter.Type t) {
        return switch (t) {
            case NOISE_SUPPRESSION -> "Takes out steady background noise (fans, aircon, hiss)";
            case NOISE_GATE -> "Silences the microphone while nobody speaks (below the threshold)";
            case GAIN -> "Makes the sound louder or softer";
            case COMPRESSOR -> "Evens out loud and soft speaking";
            case LIMITER -> "Never lets the sound go over the threshold (no distortion when someone shouts)";
        };
    }

    private void move(int by) {
        int i = list.getSelectedIndex();
        int j = i + by;
        if (i < 0 || j < 0 || j >= model.size()) {
            return;
        }
        AudioFilter f = model.remove(i);
        model.add(j, f);
        list.setSelectedIndex(j);
    }

    private void showEditor() {
        editor.removeAll();
        AudioFilter f = list.getSelectedValue();
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, 8, 10);
        if (f == null) {
            editor.add(Ui.label("<html><div style='width:260px'>No filter yet. Click + to add one, e.g. Noise Suppression "
                    + "for the pulpit microphone.</div></html>", Palette::secondaryText, null), g);
        } else {
            g.gridwidth = 3;
            editor.add(Ui.label(f.type.label(), Palette::text, "semibold"), g);
            g.gridy++;
            editor.add(Ui.label("<html><div style='width:260px'>" + describe(f.type) + "</div></html>", Palette::mutedText, "small"), g);
            g.gridy++;
            JCheckBox enabled = new JCheckBox("Enabled", f.enabled);
            enabled.setOpaque(false);
            enabled.addActionListener(e -> {
                f.enabled = enabled.isSelected();
                list.repaint();
            });
            editor.add(enabled, g);
            g.gridy++;
            g.gridwidth = 1;
            editor.add(Ui.small("Name"), g);
            g.gridx = 1;
            g.gridwidth = 2;
            JTextField name = new JTextField(f.name, 16);
            name.setMinimumSize(name.getPreferredSize());
            name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                private void update() {
                    f.name = name.getText().isBlank() ? f.type.label() : name.getText().strip();
                    list.repaint();
                }

                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
            });
            editor.add(name, g);
            for (AudioFilter.Param p : f.type.params()) {
                g.gridy++;
                g.gridx = 0;
                g.gridwidth = 1;
                editor.add(Ui.small(p.label()), g);
                g.gridx = 1;
                JSpinner spin = new JSpinner(new SpinnerNumberModel(f.value(p.key()), p.min(), p.max(), p.step()));
                spin.setPreferredSize(new Dimension(90, spin.getPreferredSize().height));
                spin.addChangeListener(e -> f.params.put(p.key(), ((Number) spin.getValue()).doubleValue()));
                editor.add(spin, g);
                g.gridx = 2;
                editor.add(Ui.small(p.unit()), g);
            }
        }
        g.gridy++;
        g.weighty = 1;
        editor.add(Ui.transparent(new BorderLayout()), g);
        editor.revalidate();
        editor.repaint();
    }

    /** Edits a channel's filters; SAVE stores them. */
    static void show(Component parent, String channel, String key) {
        MixerChannels prefs = MixerChannels.get();
        MixerChannels.Channel c = prefs.channel(key);
        AudioFiltersDialog d = new AudioFiltersDialog(parent, channel, c.filters);
        com.jamc.worship.ui.SoftModal.showAndWait(d);
        if (d.saved) {
            c.filters = new java.util.ArrayList<>();
            for (int i = 0; i < d.model.size(); i++) {
                c.filters.add(d.model.get(i));
            }
            prefs.changed();
        }
    }
}
