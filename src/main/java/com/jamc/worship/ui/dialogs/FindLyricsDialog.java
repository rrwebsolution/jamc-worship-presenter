package com.jamc.worship.ui.dialogs;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.lyrics.LocalLibraryProvider;
import com.jamc.worship.lyrics.LyricsFolderProvider;
import com.jamc.worship.lyrics.LyricsProvider;
import com.jamc.worship.lyrics.LyricsProviders;
import com.jamc.worship.lyrics.LyricsResult;
import com.jamc.worship.model.LyricsSourceType;
import com.jamc.worship.model.Song;
import com.jamc.worship.service.LyricsFormatter;
import com.jamc.worship.service.SongService;
import com.jamc.worship.service.TimedLyricsParser;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.components.TransportControls;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * FIND LYRICS: searches the configured lyrics providers (all of them with fallback, or one source),
 * previews a result and hands it to REVIEW LYRICS — nothing is saved without review. When nothing is found,
 * the operator can try another source, paste, create manually or import a file: never blocked.
 */
public final class FindLyricsDialog extends JDialog {

    /** The other ways to add lyrics, offered when a search finds nothing. */
    public interface Alternatives {
        void paste(String title);

        void createManually();

        void importFile();

        /** Identify the song on YouTube (YouTube Sync). */
        void youtube();

        /** The song is already in the library: show it there. */
        void openExisting(long songId);
    }

    private static final String ALL = "All Sources";
    private static final String YOUTUBE = "YouTube Reference (identify the song)";

    private final SongService songs;
    private final LyricsProviders providers;
    private final Alternatives alternatives;
    private final JTextField query = new JTextField();
    private final JComboBox<Object> source = new JComboBox<>();
    private final JButton search = Ui.primaryButton("SEARCH", "search");
    private final DefaultListModel<LyricsResult> model = new DefaultListModel<>();
    private final JList<LyricsResult> results = new JList<>(model);
    private final JLabel notes = Ui.label(" ", Palette::secondaryText, "small");
    private final CardLayout leftCards = new CardLayout();
    private final JPanel left = Ui.transparent(leftCards);
    private final JLabel noneText = Ui.label(" ", Palette::secondaryText, null);
    private final JTextArea preview = new JTextArea();
    private final JLabel previewHead = Ui.label(" ", Palette::text, "h4");
    private final JButton previewButton = Ui.button("PREVIEW LYRICS", "monitor");
    private final JButton use = Ui.primaryButton("USE LYRICS", "music");
    private Runnable after;
    private Song saved;
    private long seq;

    private FindLyricsDialog(Window owner, SongService songs, LyricsProviders providers, Alternatives alternatives, String initial) {
        super(owner, "Find Lyrics", ModalityType.APPLICATION_MODAL);
        this.songs = songs;
        this.providers = providers;
        this.alternatives = alternatives;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        source.addItem(ALL);
        for (LyricsProvider p : providers.configured()) {
            source.addItem(p);
        }
        source.addItem(YOUTUBE);
        source.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v instanceof LyricsProvider p ? p.name() : v, i, s, f);
            }
        });
        query.setText(initial == null ? "" : initial);
        query.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Goodness of God Bethel Music");
        query.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("search", Icons.SMALL));
        search.addActionListener(e -> search());
        Ui.bindKey(query, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "search", this::search, JComponent.WHEN_FOCUSED);

        JPanel bar = Ui.transparent(new BorderLayout(8, 0));
        bar.add(query, BorderLayout.CENTER);
        JPanel barRight = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        barRight.add(Ui.small("Source:"));
        barRight.add(source);
        barRight.add(search);
        bar.add(barRight, BorderLayout.EAST);
        JPanel top = Ui.transparent(new BorderLayout(0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
        top.add(Ui.label("FIND LYRICS", Palette::text, "h3"), BorderLayout.NORTH);
        top.add(bar, BorderLayout.CENTER);
        top.add(notes, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildResults(), buildPreview());
        split.setResizeWeight(0.45);
        split.setBorder(Ui.line(true, false, false, false));

        JPanel root = Ui.panel(new BorderLayout(), Palette::panelBackground);
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);
        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose, JComponent.WHEN_IN_FOCUSED_WINDOW);
        setMinimumSize(new Dimension(900, 560));
        setSize(1080, 680);
        setLocationRelativeTo(owner);
        if (initial != null && !initial.isBlank()) {
            javax.swing.SwingUtilities.invokeLater(this::search);
        }
    }

    /**
     * Shows FIND LYRICS; returns the Lyrics item saved after review, if any. An alternative chosen here
     * (paste, manual, file, YouTube, open existing) runs after the dialog closed.
     */
    public static Optional<Song> showDialog(Window owner, SongService songs, LyricsProviders providers, Alternatives alternatives,
                                            String initial) {
        FindLyricsDialog d = new FindLyricsDialog(owner, songs, providers, alternatives, initial);
        d.setVisible(true);
        if (d.after != null) {
            d.after.run();
        }
        return Optional.ofNullable(d.saved);
    }

    private JComponent buildResults() {
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setCellRenderer(new ResultRenderer());
        results.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selected(results.getSelectedValue());
            }
        });
        JScrollPane scroll = new JScrollPane(results);
        scroll.setBorder(null);

        // NO LYRICS FOUND: other ways in, right away
        noneText.setHorizontalAlignment(SwingConstants.CENTER);
        JButton another = Ui.button("SEARCH ANOTHER SOURCE", "search");
        another.addActionListener(e -> {
            source.requestFocusInWindow();
            source.showPopup();
        });
        JButton paste = Ui.primaryButton("PASTE LYRICS", "copy");
        paste.addActionListener(e -> close(() -> alternatives.paste(query.getText().strip())));
        JButton manual = Ui.button("CREATE MANUALLY", "edit");
        manual.addActionListener(e -> close(alternatives::createManually));
        JButton file = Ui.button("IMPORT FILE", "import");
        file.addActionListener(e -> close(alternatives::importFile));
        JPanel buttons = Ui.transparent(new GridLayout(0, 1, 0, 6));
        buttons.add(another);
        buttons.add(paste);
        buttons.add(manual);
        buttons.add(file);
        JPanel none = Ui.transparent(new BorderLayout(0, 14));
        none.add(Ui.label("NO LYRICS FOUND", Palette::text, "h3"), BorderLayout.NORTH);
        none.add(noneText, BorderLayout.CENTER);
        none.add(buttons, BorderLayout.SOUTH);
        JPanel noneHolder = Ui.transparent(new GridBagLayout());
        noneHolder.add(none);

        JLabel start = Ui.label("<html><center>Search the configured lyrics sources.<br>Lyrics are reviewed before anything is saved."
                + "<br><br>Sources are set up in Settings → Integrations → Lyrics.</center></html>", Palette::mutedText, null);
        start.setHorizontalAlignment(SwingConstants.CENTER);

        left.add(start, "start");
        left.add(scroll, "results");
        left.add(noneHolder, "none");
        leftCards.show(left, "start");
        return left;
    }

    private JComponent buildPreview() {
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        preview.setFont(preview.getFont().deriveFont(14f));
        previewButton.addActionListener(e -> loadPreview(results.getSelectedValue(), false));
        use.addActionListener(e -> loadPreview(results.getSelectedValue(), true));
        Ui.setEnabled(false, previewButton, use);
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(previewButton);
        buttons.add(use);
        JPanel p = Ui.transparent(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 16));
        p.add(previewHead, BorderLayout.NORTH);
        p.add(new JScrollPane(preview), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildFooter() {
        JButton paste = Ui.button("Paste Lyrics", "copy");
        paste.addActionListener(e -> close(() -> alternatives.paste(query.getText().strip())));
        JButton manual = Ui.button("Create Manually", "edit");
        manual.addActionListener(e -> close(alternatives::createManually));
        JButton file = Ui.button("Import File", "import");
        file.addActionListener(e -> close(alternatives::importFile));
        JButton cancel = Ui.button("Close", null);
        cancel.addActionListener(e -> dispose());
        JPanel leftButtons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftButtons.add(paste);
        leftButtons.add(manual);
        leftButtons.add(file);
        JPanel footer = Ui.transparent(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        footer.add(leftButtons, BorderLayout.WEST);
        footer.add(cancel, BorderLayout.EAST);
        return footer;
    }

    private void close(Runnable then) {
        after = then;
        dispose();
    }

    // ------------------------------------------------------------------ search

    private void search() {
        String q = query.getText().strip();
        if (q.isEmpty()) {
            return;
        }
        Object src = source.getSelectedItem();
        if (YOUTUBE.equals(src)) {
            close(alternatives::youtube);
            return;
        }
        String providerId = src instanceof LyricsProvider p ? p.id() : null;
        long s = ++seq;
        search.setEnabled(false);
        notes.setText("Searching " + (providerId == null ? "all sources" : ((LyricsProvider) src).name()) + "…");
        BackgroundTask.run("Find lyrics", () -> providers.search(q, providerId), outcome -> {
            if (s != seq) {
                return;
            }
            search.setEnabled(true);
            model.clear();
            model.addAll(outcome.results());
            List<String> info = new ArrayList<>();
            info.add("Searched: " + (outcome.searched().isEmpty() ? "no configured source" : String.join(", ", outcome.searched())));
            outcome.notes().stream().filter(n -> !n.endsWith("no results")).forEach(info::add);
            notes.setText(String.join("   ·   ", info));
            if (outcome.results().isEmpty()) {
                noneText.setText("<html><center>Nothing for \"" + esc(q) + "\" in " + (providerId == null ? "any configured source"
                        : ((LyricsProvider) src).name()) + ".<br>Try another source or add the lyrics yourself.</center></html>");
                leftCards.show(left, "none");
                selected(null);
            } else {
                leftCards.show(left, "results");
                results.setSelectedIndex(0);
            }
        }, err -> {
            search.setEnabled(true);
            notes.setText(err.getMessage());
        });
    }

    private void selected(LyricsResult r) {
        Ui.setEnabled(r != null, previewButton, use);
        if (r == null) {
            previewHead.setText(" ");
            preview.setText("");
            return;
        }
        previewHead.setText("<html>" + esc(r.title()) + (r.artist() == null || r.artist().isBlank() ? "" : " — " + esc(r.artist()))
                + "<br><span style='font-size:9px;color:#94A3B8'>Source: " + esc(r.providerName()) + "</span></html>");
        boolean local = r.existingSongId() != null;
        use.setText(local ? "OPEN IN LIBRARY" : "USE LYRICS");
        if (r.hasLyrics()) {
            showText(r);
        } else {
            preview.setText(local ? "Already saved in your library. OPEN IN LIBRARY shows it (it is not copied or overwritten)."
                    : "Press PREVIEW LYRICS to load the lyrics.");
        }
    }

    private void loadPreview(LyricsResult r, boolean thenUse) {
        if (r == null) {
            return;
        }
        if (thenUse && r.existingSongId() != null) {
            close(() -> alternatives.openExisting(r.existingSongId()));
            return;
        }
        LyricsProvider p = providers.byId(r.providerId());
        Ui.setEnabled(false, previewButton, use);
        BackgroundTask.run("Load lyrics", () -> p == null ? r : p.fetch(r), full -> {
            Ui.setEnabled(true, previewButton, use);
            int i = results.getSelectedIndex();
            if (i >= 0 && model.get(i) == r) {
                model.set(i, full);
            }
            showText(full);
            if (thenUse) {
                review(full);
            }
        }, err -> {
            Ui.setEnabled(true, previewButton, use);
            Dialogs.showError(this, "Could not load the lyrics.", err);
        });
    }

    private void showText(LyricsResult r) {
        String text = r.plainLyrics() != null ? r.plainLyrics()
                : r.hasTimed() ? TimedLyricsParser.parse(r.reference() != null && r.reference().contains(".") ? r.reference() : "x.lrc",
                r.timedLyrics()).text() : "";
        preview.setText(text);
        preview.setCaretPosition(0);
    }

    /** REVIEW LYRICS for a result; saving there closes this dialog. */
    private void review(LyricsResult r) {
        String text;
        List<TimedLyricsParser.Cue> cues = null;
        List<String> arrangement = null;
        if (r.hasTimed()) {
            String name = r.reference() != null && r.reference().contains(".") ? r.reference() : "lyrics.lrc";
            TimedLyricsParser.Result timed = TimedLyricsParser.parse(name, r.timedLyrics());
            text = timed.text();
            cues = timed.cues();
            arrangement = timed.arrangement();
        } else {
            text = LyricsFormatter.autoFormat(r.plainLyrics());
        }
        LyricsSourceType type = r.providerId().equals(LyricsFolderProvider.ID) ? LyricsSourceType.FILE_IMPORT
                : r.trust() == LyricsProvider.Trust.LICENSED ? LyricsSourceType.LICENSED_API : LyricsSourceType.AUTHORIZED_PROVIDER;
        LyricsEditorDialog.Draft draft = new LyricsEditorDialog.Draft("REVIEW LYRICS", r.title(), r.artist(), text, type,
                r.providerName(), r.reference(), cues, arrangement);
        Optional<Song> stored = LyricsEditorDialog.showDialog(this, songs, draft);
        stored.ifPresent(s -> {
            saved = s;
            dispose();
        });
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Title, artist, source and SYNCED / PLAIN / IN LIBRARY badges. */
    private static final class ResultRenderer extends JPanel implements ListCellRenderer<LyricsResult> {
        private final JLabel title = new JLabel();
        private final JLabel info = new JLabel();
        private final JLabel badge = new JLabel();

        ResultRenderer() {
            super(new BorderLayout(8, 2));
            setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
            title.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
            info.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            badge.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            badge.setOpaque(true);
            badge.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            JPanel text = new JPanel(new BorderLayout(0, 2));
            text.setOpaque(false);
            text.add(title, BorderLayout.NORTH);
            text.add(info, BorderLayout.SOUTH);
            JPanel east = new JPanel(new GridBagLayout());
            east.setOpaque(false);
            east.add(badge);
            add(text, BorderLayout.CENTER);
            add(east, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends LyricsResult> list, LyricsResult r, int index,
                                                      boolean selected, boolean focused) {
            title.setText(r.title());
            List<String> parts = new ArrayList<>();
            if (r.artist() != null && !r.artist().isBlank()) {
                parts.add(r.artist());
            }
            parts.add(r.providerName());
            if (r.durationMs() != null) {
                parts.add(TransportControls.format(r.durationMs()));
            }
            info.setText(String.join("  ·  ", parts));
            boolean local = LocalLibraryProvider.ID.equals(r.providerId());
            badge.setText(local ? "IN LIBRARY" : r.hasTimed() ? "SYNCED" : "PLAIN");
            badge.setBackground(local ? Palette.accent() : r.hasTimed() ? Palette.live() : Palette.secondaryBackground());
            badge.setForeground(local || r.hasTimed() ? Color.WHITE : Palette.secondaryText());
            title.setForeground(selected ? list.getSelectionForeground() : Palette.text());
            info.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setOpaque(selected);
            return this;
        }
    }
}
