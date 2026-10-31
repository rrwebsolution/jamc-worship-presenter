package com.jamc.worship.ui.dialogs;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.bible.BibleProvider;
import com.jamc.worship.bible.BibleVersion;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.service.BibleService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Manage Bible versions: where each version's text comes from (bundled public domain, an imported
 * licensed file, or a licensed API with the church's key), turn versions on or off, test them.
 */
public class BibleVersionsDialog extends JDialog {

    public static final String KEY_PREFIX = "bible.key.";

    private final BibleService bible;
    private final AppConfig config;
    private final VersionModel model = new VersionModel();
    private final JTable table = new JTable(model);
    private final JPasswordField esvKey = new JPasswordField();
    private final JPasswordField nltKey = new JPasswordField();
    private final JPasswordField apiBibleKey = new JPasswordField();
    private final JLabel status = Ui.small(" ");
    private boolean changed;
    private final javax.swing.Timer statusRefresh = new javax.swing.Timer(600, e -> reload(null));

    private BibleVersionsDialog(Window owner, BibleService bible, AppConfig config) {
        super(owner, "Bible Versions", ModalityType.APPLICATION_MODAL);
        this.bible = bible;
        this.config = config;
        statusRefresh.setRepeats(false);

        JPanel root = Ui.panel(new BorderLayout(0, 12), Palette::panelBackground);
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        JLabel legal = Ui.label("<html><b>Use only versions your church is licensed to use.</b> NLT, ESV, NIV and "
                + "Ang Pulong Sa Dios are copyrighted by their publishers, so their text is not included. Connect each one "
                + "with your own licensed API key, or import a Bible file you are permitted to use, and show the attribution "
                + "the publisher requires. The King James Version is public domain and included.</html>", Palette::secondaryText, null);
        root.add(legal, BorderLayout.NORTH);

        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultEditor(BibleVersion.Provider.class, new DefaultCellEditor(new JComboBox<>(BibleVersion.Provider.values())));
        int[] widths = {60, 70, 190, 80, 240, 130, 260};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(1000, 230));

        JPanel keys = Ui.transparent(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 6, 10);
        keyRow(keys, g, "ESV API key (api.esv.org)", esvKey, BibleVersion.Provider.ESV_API);
        keyRow(keys, g, "NLT API key (api.nlt.to)", nltKey, BibleVersion.Provider.NLT_API);
        keyRow(keys, g, "API.Bible key (scripture.api.bible)", apiBibleKey, BibleVersion.Provider.API_BIBLE);
        g.gridx = 0;
        g.gridwidth = 2;
        keys.add(Ui.label("<html>For API.Bible, put the Bible ID your key is licensed for in the \"Provider ID\" column "
                + "(for example the ID of NIV or Ang Pulong Sa Dios in your API.Bible account). For the NLT API the column may "
                + "hold another Tyndale version code. Keys are stored only on this computer. Online versions need internet; "
                + "import a licensed file for offline use during services.</html>", Palette::mutedText, "small"), g);

        JPanel center = Ui.transparent(new BorderLayout(0, 10));
        center.add(scroll, BorderLayout.CENTER);
        center.add(keys, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        JButton importButton = Ui.primaryButton("Import Licensed File…", "folder");
        importButton.addActionListener(e -> importFile());
        JButton add = Ui.button("Add Version…", "plus");
        add.addActionListener(e -> addVersion());
        JButton remove = Ui.button("Remove", "trash");
        remove.addActionListener(e -> removeVersion());
        JButton test = Ui.button("Test", "book");
        test.addActionListener(e -> testSelected());
        JButton setUp = Ui.button("Set Up…", "edit");
        setUp.setToolTipText("Connect the selected version: API key and Bible ID, or a licensed file");
        setUp.addActionListener(e -> setUp(selected()));
        JButton close = new JButton("Close");
        close.addActionListener(e -> close());
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(setUp);
        left.add(importButton);
        left.add(add);
        left.add(remove);
        left.add(test);
        JPanel south = Ui.transparent(new BorderLayout(10, 0));
        south.add(left, BorderLayout.WEST);
        south.add(status, BorderLayout.CENTER);
        south.add(close, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);

        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
        reload(null);
    }

    /** Shows the dialog. Returns true if anything changed (versions or keys). */
    public static boolean show(Window owner, BibleService bible, AppConfig config) {
        BibleVersionsDialog d = new BibleVersionsDialog(owner, bible, config);
        d.setVisible(true);
        return d.changed;
    }

    private void keyRow(JPanel p, GridBagConstraints g, String label, JPasswordField field, BibleVersion.Provider provider) {
        field.setText(config.get(KEY_PREFIX + provider.name(), ""));
        field.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true");
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void save() {
                String v = new String(field.getPassword()).strip();
                config.set(KEY_PREFIX + provider.name(), v.isEmpty() ? null : v);
                config.save();
                bible.clearCache();
                changed = true;
                statusRefresh.restart();
            }

            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { save(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { save(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { save(); }
        });
        g.gridx = 0;
        g.weightx = 0;
        p.add(Ui.label(label, Palette::secondaryText, "small"), g);
        g.gridx = 1;
        g.weightx = 1;
        p.add(field, g);
        g.gridy++;
    }

    private void reload(String select) {
        BackgroundTask.run("Load versions", () -> {
            List<BibleVersion> list = bible.versions();
            java.util.Map<String, String> statuses = new java.util.HashMap<>();
            for (BibleVersion v : list) {
                String p = bible.problem(v);
                statuses.put(v.abbreviation(), p == null ? (v.provider().online() ? "Ready (online)" : "Ready") : p);
            }
            model.statuses = statuses;
            return list;
        }, list -> {
            model.setVersions(list);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).abbreviation().equalsIgnoreCase(select)) {
                    table.setRowSelectionInterval(i, i);
                }
            }
        }, err -> Dialogs.showError(this, "Could not load Bible versions.", err));
    }

    private BibleVersion selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.versions.get(table.convertRowIndexToModel(row));
    }

    private void save(BibleVersion v) {
        BackgroundTask.run("Save version", () -> bible.saveVersion(v), saved -> {
            changed = true;
            reload(saved.abbreviation());
        }, err -> Dialogs.showError(this, "Could not save the version.", err));
    }

    private void importFile() {
        BibleVersion v = selected();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import a licensed Bible file" + (v == null ? "" : " for " + v.abbreviation()));
        chooser.setFileFilter(new FileNameExtensionFilter("Bible files (JSON, Zefania XML, CSV/TSV)",
                "json", "gz", "xml", "csv", "tsv", "txt"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String abbr = v == null ? null : v.abbreviation();
        if (abbr == null) {
            abbr = JOptionPane.showInputDialog(this, "Abbreviation for this version (e.g. NIV):");
            if (abbr == null || abbr.isBlank()) {
                return;
            }
        }
        if (JOptionPane.showConfirmDialog(this, "Confirm that your church is licensed to use this " + abbr
                + " text for projection.", "License", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        String code = abbr;
        java.nio.file.Path file = chooser.getSelectedFile().toPath();
        status.setText("Importing " + code + "…");
        BackgroundTask.run("Import Bible", () -> bible.importFile(file, code, v == null ? null : v.name(),
                v == null ? null : v.language()), saved -> {
            changed = true;
            status.setText("Imported " + saved.abbreviation() + ".");
            reload(saved.abbreviation());
        }, err -> {
            status.setText(" ");
            Dialogs.showError(this, "The Bible file could not be imported.", err);
        });
    }

    private void addVersion() {
        JTextField abbr = new JTextField();
        JTextField name = new JTextField();
        JTextField lang = new JTextField("English");
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 6, 8);
        String[] labels = {"Abbreviation", "Name", "Language"};
        JTextField[] fields = {abbr, name, lang};
        for (int i = 0; i < 3; i++) {
            g.gridy = i;
            g.gridx = 0;
            p.add(new JLabel(labels[i]), g);
            g.gridx = 1;
            g.weightx = 1;
            fields[i].setColumns(22);
            p.add(fields[i], g);
        }
        if (JOptionPane.showConfirmDialog(this, p, "Add Bible Version", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION || abbr.getText().isBlank()) {
            return;
        }
        save(new BibleVersion(null, abbr.getText().strip().toUpperCase(), name.getText().isBlank() ? abbr.getText().strip()
                : name.getText().strip(), lang.getText().strip(), BibleVersion.Provider.NONE, null, false, null, 30));
    }

    private void removeVersion() {
        BibleVersion v = selected();
        if (v == null || !Dialogs.confirmDestructive(this, "Remove version", "Remove " + v.abbreviation()
                + " and any text imported for it?", "Remove")) {
            return;
        }
        BackgroundTask.run("Remove version", () -> {
            bible.deleteVersion(v);
            return true;
        }, ok -> {
            changed = true;
            reload(null);
        }, err -> Dialogs.showError(this, "Could not remove the version.", err));
    }

    private void testSelected() {
        BibleVersion v = selected();
        if (v == null) {
            return;
        }
        status.setText("Testing " + v.abbreviation() + "…");
        BackgroundTask.run("Test version", () -> bible.verses(v, 43, 1), verses -> {
            BibleProvider.Verse first = verses.isEmpty() ? null : verses.getFirst();
            status.setText(first == null ? v.abbreviation() + ": no text returned."
                    : v.abbreviation() + " works: " + verses.size() + " verses loaded for John 1.");
        }, err -> status.setText(v.abbreviation() + ": " + err.getMessage()));
    }

    /**
     * SET UP a version (also opens when a version that is not set up is switched on): where its text comes
     * from, and the API key / Bible ID for it, or a licensed file. Saved, then tested with John 1.
     */
    private void setUp(BibleVersion v) {
        if (v == null) {
            status.setText("Select a version first.");
            return;
        }
        if (v.provider() == BibleVersion.Provider.PUBLIC_DOMAIN) {
            status.setText(v.abbreviation() + " is public domain and already included.");
            return;
        }
        List<BibleVersion.Provider> sources = new ArrayList<>();
        if (v.abbreviation().equalsIgnoreCase("ESV")) sources.add(BibleVersion.Provider.ESV_API);
        if (v.abbreviation().equalsIgnoreCase("NLT")) sources.add(BibleVersion.Provider.NLT_API);
        sources.add(BibleVersion.Provider.API_BIBLE);
        sources.add(BibleVersion.Provider.LOCAL_FILE);
        JComboBox<BibleVersion.Provider> source = new JComboBox<>(sources.toArray(BibleVersion.Provider[]::new));
        if (sources.contains(v.provider())) {
            source.setSelectedItem(v.provider());
        }
        JPasswordField key = new JPasswordField(36);
        key.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true");
        JTextField bibleId = new JTextField(v.providerConfig() == null ? "" : v.providerConfig(), 36);
        bibleId.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. 78a9f6124f344018-01");
        JLabel keyLabel = new JLabel("API key");
        JLabel idLabel = new JLabel("Bible ID");
        JLabel hint = Ui.label(" ", Palette::mutedText, "small");
        Runnable sync = () -> {
            BibleVersion.Provider p = (BibleVersion.Provider) source.getSelectedItem();
            boolean file = p == BibleVersion.Provider.LOCAL_FILE;
            key.setText(file ? "" : config.get(KEY_PREFIX + p.name(), ""));
            key.setEnabled(!file);
            keyLabel.setEnabled(!file);
            boolean needsId = p == BibleVersion.Provider.API_BIBLE || p == BibleVersion.Provider.NLT_API;
            bibleId.setEnabled(needsId);
            idLabel.setEnabled(needsId);
            idLabel.setText(p == BibleVersion.Provider.NLT_API ? "Version code (optional)" : "Bible ID");
            hint.setText("<html><body style='width:420px'>" + switch (p) {
                case API_BIBLE -> "Sign in at scripture.api.bible, copy your API key, then open Bibles and copy the ID of "
                        + v.abbreviation() + " that your key is licensed for. One key works for every API.Bible version.";
                case ESV_API -> "Create a free API key at api.esv.org (Account → API Applications).";
                case NLT_API -> "Request an API key at api.nlt.to. The version code is only needed for another Tyndale version.";
                case LOCAL_FILE -> "Choose a Bible file your church is licensed to use (JSON, Zefania XML, CSV/TSV). Works "
                        + "offline during services.";
                default -> "";
            } + "</body></html>");
        };
        source.addActionListener(e -> sync.run());
        sync.run();

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 8, 10);
        g.gridy = 0;
        g.gridx = 0;
        g.gridwidth = 2;
        p.add(Ui.label("<html><b>" + v.name() + " (" + v.abbreviation() + ")</b> is copyrighted: use it only with your "
                + "church's licence.</html>", Palette::text, null), g);
        g.gridwidth = 1;
        JComponent[][] rows = {{new JLabel("Text source"), source}, {keyLabel, key}, {idLabel, bibleId}};
        for (JComponent[] row : rows) {
            g.gridy++;
            g.gridx = 0;
            g.weightx = 0;
            p.add(row[0], g);
            g.gridx = 1;
            g.weightx = 1;
            p.add(row[1], g);
        }
        g.gridy++;
        g.gridx = 0;
        g.gridwidth = 2;
        p.add(hint, g);

        Object[] options = {"SAVE & TEST", "CANCEL"};
        if (JOptionPane.showOptionDialog(this, p, "Set Up " + v.abbreviation(), JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]) != 0) {
            return;
        }
        BibleVersion.Provider chosen = (BibleVersion.Provider) source.getSelectedItem();
        if (chosen == BibleVersion.Provider.LOCAL_FILE) {
            selectRow(v.abbreviation());
            importFile();
            return;
        }
        String k = new String(key.getPassword()).strip();
        String id = bibleId.getText().strip();
        if (k.isEmpty()) {
            Dialogs.showInfo(this, "Set Up " + v.abbreviation(), "Paste the API key first.");
            return;
        }
        if (com.jamc.worship.bible.OnlineBibleProviders.looksLikeBibleId(k)) {
            Dialogs.showInfo(this, "Set Up " + v.abbreviation(), "\"" + k + "\" is a Bible ID, not an API key.\n\n"
                    + "Put it in Bible ID, and paste the API key from your API.Bible Applications page in API key.");
            return;
        }
        if (chosen == BibleVersion.Provider.API_BIBLE && id.isEmpty()) {
            Dialogs.showInfo(this, "Set Up " + v.abbreviation(), "Paste the Bible ID of " + v.abbreviation()
                    + " from your API.Bible account.");
            return;
        }
        // the key field below shows (and stores) the key, for this and every version using the same service
        switch (chosen) {
            case ESV_API -> esvKey.setText(k);
            case NLT_API -> nltKey.setText(k);
            case API_BIBLE -> apiBibleKey.setText(k);
            default -> { }
        }
        config.set(KEY_PREFIX + chosen.name(), k);
        config.save();
        bible.clearCache();
        BibleVersion updated = v.withProvider(chosen, null, id.isEmpty() ? null : id, true);
        status.setText("Saving " + v.abbreviation() + "…");
        BackgroundTask.run("Save version", () -> bible.saveVersion(updated), saved -> {
            changed = true;
            reload(saved.abbreviation());
            status.setText("Testing " + saved.abbreviation() + "…");
            BackgroundTask.run("Test version", () -> bible.verses(saved, 43, 1), verses -> status.setText(verses.isEmpty()
                    ? saved.abbreviation() + ": no text returned. Check the key and the Bible ID."
                    : saved.abbreviation() + " works: " + verses.size() + " verses loaded for John 1."),
                    err -> status.setText(saved.abbreviation() + ": " + err.getMessage()));
        }, err -> Dialogs.showError(this, "Could not save the version.", err));
    }

    private void selectRow(String abbreviation) {
        for (int i = 0; i < model.versions.size(); i++) {
            if (model.versions.get(i).abbreviation().equalsIgnoreCase(abbreviation)) {
                int view = table.convertRowIndexToView(i);
                table.setRowSelectionInterval(view, view);
            }
        }
    }

    private void close() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        dispose();
    }

    /** Columns: On, Abbreviation, Name, Language, Source, Provider ID, Status. */
    private final class VersionModel extends AbstractTableModel {
        private final String[] columns = {"On", "Version", "Name", "Language", "Text source", "Provider ID", "Status"};
        private List<BibleVersion> versions = new ArrayList<>();
        private volatile java.util.Map<String, String> statuses = java.util.Map.of();

        void setVersions(List<BibleVersion> v) {
            versions = new ArrayList<>(v);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return versions.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return switch (c) {
                case 0 -> Boolean.class;
                case 4 -> BibleVersion.Provider.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0 || c == 2 || c == 3 || c == 4 || c == 5;
        }

        @Override
        public Object getValueAt(int r, int c) {
            BibleVersion v = versions.get(r);
            return switch (c) {
                case 0 -> v.enabled();
                case 1 -> v.abbreviation();
                case 2 -> v.name();
                case 3 -> v.language();
                case 4 -> v.provider();
                case 5 -> v.provider() == BibleVersion.Provider.LOCAL_FILE ? v.localFile() : v.providerConfig();
                default -> statuses.getOrDefault(v.abbreviation(), "…");
            };
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            BibleVersion v = versions.get(r);
            BibleVersion updated = switch (c) {
                case 0 -> {
                    if (Boolean.TRUE.equals(value) && v.provider() == BibleVersion.Provider.NONE) {
                        // switched on but not connected yet: ask where its text comes from (API key / file)
                        javax.swing.SwingUtilities.invokeLater(() -> setUp(v));
                        yield null;
                    }
                    yield v.withEnabled((Boolean) value);
                }
                case 2 -> new BibleVersion(v.id(), v.abbreviation(), value.toString(), v.language(), v.provider(), v.localFile(),
                        v.enabled(), v.providerConfig(), v.sortOrder());
                case 3 -> new BibleVersion(v.id(), v.abbreviation(), v.name(), value.toString(), v.provider(), v.localFile(),
                        v.enabled(), v.providerConfig(), v.sortOrder());
                case 4 -> {
                    BibleVersion.Provider p = (BibleVersion.Provider) value;
                    if (p == BibleVersion.Provider.PUBLIC_DOMAIN && !v.abbreviation().equalsIgnoreCase("KJV")) {
                        status.setText("Only public-domain texts may use that source.");
                        yield null;
                    }
                    yield v.withProvider(p, v.localFile(), v.providerConfig(), p != BibleVersion.Provider.NONE);
                }
                case 5 -> v.provider() == BibleVersion.Provider.LOCAL_FILE ? null
                        : v.withProvider(v.provider(), v.localFile(), value.toString().strip(), v.enabled());
                default -> null;
            };
            if (updated != null) {
                save(updated);
            }
        }
    }
}
