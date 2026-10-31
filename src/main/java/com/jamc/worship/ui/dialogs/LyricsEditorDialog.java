package com.jamc.worship.ui.dialogs;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.LyricsSourceType;
import com.jamc.worship.model.LyricsSync;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongSection;
import com.jamc.worship.service.LyricsFormatter;
import com.jamc.worship.service.LyricsParser;
import com.jamc.worship.service.SongService;
import com.jamc.worship.service.TimedLyricsParser;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PASTE LYRICS / REVIEW LYRICS: every way of adding lyrics (search result, paste, file import) ends here
 * before anything is saved. The operator checks the title, artist and words, can AUTO FORMAT the section
 * labels, SPLIT INTO CARDS and merge / split / reorder / edit single cards, then SAVE. Timestamps that came
 * with the lyrics (LRC / SRT / VTT, a synced provider) are kept per card.
 */
public final class LyricsEditorDialog extends JDialog {

    /**
     * What to review.
     *
     * @param cues        timed lines (from a timed file or provider), or null
     * @param arrangement sung section order from a timed file, or null
     */
    public record Draft(String heading, String title, String artist, String text, LyricsSourceType sourceType,
                        String provider, String reference, List<TimedLyricsParser.Cue> cues, List<String> arrangement) {

        public static Draft paste() {
            return new Draft("PASTE LYRICS", "", "", "", LyricsSourceType.PASTED, null, null, null, null);
        }

        public boolean timed() {
            return cues != null && !cues.isEmpty();
        }
    }

    private final SongService songs;
    private final Draft draft;
    private final JTextField title = new JTextField();
    private final JTextField artist = new JTextField();
    private final JTextArea lyrics = new JTextArea();
    private final DefaultListModel<LyricsFormatter.Card> cardModel = new DefaultListModel<>();
    private final JList<LyricsFormatter.Card> cardList = new JList<>(cardModel);
    private final JLabel summary = Ui.label(" ", Palette::secondaryText, "small");
    private final JLabel message = Ui.label(" ", Palette::danger, "small");
    private final JSpinner linesPerCard = new JSpinner(new SpinnerNumberModel(2, 1, 8, 1));
    private final JButton save = Ui.primaryButton("SAVE", "music");
    private final JButton saveSync = Ui.button("SAVE & SYNC", "play");
    private final Timer reparse = new Timer(200, e -> refreshCards());
    private Song saved;

    private LyricsEditorDialog(Window owner, SongService songs, Draft draft) {
        super(owner, draft.heading().charAt(0) + draft.heading().substring(1).toLowerCase(), ModalityType.APPLICATION_MODAL);
        this.songs = songs;
        this.draft = draft;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        title.setText(draft.title() == null ? "" : draft.title());
        artist.setText(draft.artist() == null ? "" : draft.artist());
        title.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Song title");
        artist.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Artist / author");
        lyrics.setText(draft.text() == null ? "" : draft.text());
        lyrics.setCaretPosition(0);
        lyrics.setFont(lyrics.getFont().deriveFont(14f));
        lyrics.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Paste lyrics here…");
        reparse.setRepeats(false);
        lyrics.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { reparse.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { reparse.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { reparse.restart(); }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildText(), buildCards());
        split.setResizeWeight(0.55);
        split.setBorder(null);
        JPanel root = Ui.panel(new BorderLayout(), Palette::panelBackground);
        String source = draft.provider() != null ? draft.provider()
                : draft.sourceType() == null ? "Manual" : draft.sourceType().label();
        JPanel header = Ui.header(Ui.label(draft.heading(), Palette::text, "h3"),
                Ui.small("Source: " + source + (draft.timed() ? "  ·  timestamps included" : "")
                        + "  ·  Nothing is saved until you press SAVE. Saved lyrics stay on this computer and work offline."));
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 16)));
        root.add(header, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);
        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose, JComponent.WHEN_IN_FOCUSED_WINDOW);
        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK), "save", () -> save(false),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        refreshCards();
        setMinimumSize(new Dimension(980, 600));
        setSize(1180, 760);
        setLocationRelativeTo(owner);
    }

    /** Shows the editor; returns the saved Lyrics item, if any. */
    public static Optional<Song> showDialog(Window owner, SongService songs, Draft draft) {
        LyricsEditorDialog d = new LyricsEditorDialog(owner, songs, draft);
        d.setVisible(true);
        return Optional.ofNullable(d.saved);
    }

    private JComponent buildText() {
        JPanel names = Ui.transparent(new GridLayout(2, 2, 10, 2));
        names.add(Ui.caption("Title *"));
        names.add(Ui.caption("Artist"));
        names.add(title);
        names.add(artist);

        JButton paste = Ui.button("Paste", "copy");
        paste.addActionListener(e -> paste());
        JButton format = Ui.button("AUTO FORMAT", "edit");
        format.setToolTipText("Recognise Verse / Chorus / Bridge… labels and tidy the layout. The words are not changed.");
        format.addActionListener(e -> setText(LyricsFormatter.autoFormat(lyrics.getText())));
        JButton cards = Ui.button("SPLIT INTO CARDS", "slides");
        cards.setToolTipText("Re-split every section into cards of this many lines");
        cards.addActionListener(e -> setText(LyricsFormatter.splitIntoCards(lyrics.getText(), (Integer) linesPerCard.getValue())));
        JPanel tools = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.add(paste);
        tools.add(format);
        tools.add(cards);
        tools.add(linesPerCard);
        tools.add(Ui.small("lines per card"));

        JPanel top = Ui.transparent(new BorderLayout(0, 8));
        top.add(names, BorderLayout.NORTH);
        top.add(tools, BorderLayout.SOUTH);
        JPanel p = Ui.transparent(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 10));
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(lyrics), BorderLayout.CENTER);
        p.add(Ui.small("<html>Section labels on their own line (Verse 1, Chorus, Bridge, Tag, Ending…). "
                + "A blank line inside a section starts a new card.</html>"), BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildCards() {
        cardList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cardList.setCellRenderer(new CardRenderer());
        JButton merge = Ui.button("Merge ↓", null);
        merge.setToolTipText("Join this card with the next card of the same section");
        merge.addActionListener(e -> cardOp(i -> LyricsFormatter.mergeWithNext(lyrics.getText(), i), 0));
        JButton splitCard = Ui.button("Split", null);
        splitCard.setToolTipText("Split this card into two");
        splitCard.addActionListener(e -> splitSelected());
        JButton up = Ui.button("▲", null);
        up.setToolTipText("Move up within its section");
        up.addActionListener(e -> cardOp(i -> LyricsFormatter.move(lyrics.getText(), i, -1), -1));
        JButton down = Ui.button("▼", null);
        down.setToolTipText("Move down within its section");
        down.addActionListener(e -> cardOp(i -> LyricsFormatter.move(lyrics.getText(), i, 1), 1));
        JButton edit = Ui.button("Edit…", "edit");
        edit.addActionListener(e -> editSelected());
        JButton delete = Ui.button("Delete", "trash");
        delete.addActionListener(e -> cardOp(i -> LyricsFormatter.replace(lyrics.getText(), i, ""), 0));
        JPanel tools = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JButton b : new JButton[]{merge, splitCard, up, down, edit, delete}) {
            tools.add(b);
        }
        JPanel p = Ui.transparent(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 10, 10, 16));
        p.add(Ui.label("PRESENTATION CARDS", Palette::secondaryText, "semibold"), BorderLayout.NORTH);
        p.add(new JScrollPane(cardList), BorderLayout.CENTER);
        p.add(tools, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildFooter() {
        JButton cancel = Ui.button("Cancel", null);
        cancel.addActionListener(e -> dispose());
        save.setToolTipText("Save to Library → Lyrics (Ctrl+S)");
        save.addActionListener(e -> save(false));
        saveSync.setToolTipText(draft.timed() ? "Save, then test the imported timing and correct it if needed"
                : "Save, then sync the cards with YouTube, local audio / video, a timing file or a manual timeline");
        saveSync.setText(draft.timed() ? "SAVE & TEST SYNC" : "SAVE & SYNC");
        saveSync.addActionListener(e -> save(true));
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(cancel);
        right.add(saveSync);
        right.add(save);
        JPanel left = Ui.transparent(new GridLayout(0, 1));
        left.add(summary);
        left.add(message);
        JPanel footer = Ui.transparent(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(8, 16, 8, 12)));
        footer.add(left, BorderLayout.CENTER);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    // ------------------------------------------------------------------ editing

    private void setText(String text) {
        lyrics.setText(text);
        lyrics.setCaretPosition(0);
        refreshCards();
    }

    private void paste() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (data instanceof String s && !s.isBlank()) {
                setText(s.strip());
                message.setText(" ");
                return;
            }
        } catch (Exception ignored) {
            // nothing usable on the clipboard
        }
        message.setText("The clipboard has no text. Copy the lyrics first.");
    }

    private void refreshCards() {
        int keep = cardList.getSelectedIndex();
        List<LyricsFormatter.Card> cards = LyricsFormatter.cards(lyrics.getText());
        cardModel.clear();
        cardModel.addAll(cards);
        if (keep >= 0 && keep < cards.size()) {
            cardList.setSelectedIndex(keep);
        }
        List<SongSection> sections = LyricsParser.parse(lyrics.getText());
        summary.setText(sections.isEmpty() ? "No lyrics yet."
                : sections.size() + (sections.size() == 1 ? " section · " : " sections · ") + cards.size()
                + (cards.size() == 1 ? " card" : " cards") + (draft.timed() ? " · times are kept for the matching cards" : ""));
        save.setEnabled(!sections.isEmpty());
        saveSync.setEnabled(!sections.isEmpty());
    }

    /** Runs a card edit on the selected card and keeps it selected ({@code shift}: where it moved). */
    private void cardOp(java.util.function.IntFunction<String> op, int shift) {
        int i = cardList.getSelectedIndex();
        if (i < 0) {
            message.setText("Select a card first.");
            return;
        }
        lyrics.setText(op.apply(i));
        refreshCards();
        int target = Math.clamp(i + shift, 0, Math.max(0, cardModel.size() - 1));
        if (!cardModel.isEmpty()) {
            cardList.setSelectedIndex(target);
        }
        message.setText(" ");
    }

    private void splitSelected() {
        LyricsFormatter.Card c = cardList.getSelectedValue();
        if (c == null) {
            message.setText("Select a card first.");
            return;
        }
        String[] lines = c.text().split("\n");
        if (lines.length < 2) {
            message.setText("A one-line card cannot be split.");
            return;
        }
        Object at = JOptionPane.showInputDialog(this, "Split after line (1–" + (lines.length - 1) + "):", "Split Card",
                JOptionPane.PLAIN_MESSAGE, null, null, String.valueOf(lines.length / 2));
        if (at == null) {
            return;
        }
        try {
            int n = Integer.parseInt(at.toString().strip());
            cardOp(i -> LyricsFormatter.split(lyrics.getText(), i, n), 0);
        } catch (NumberFormatException e) {
            message.setText("Enter a line number.");
        }
    }

    private void editSelected() {
        LyricsFormatter.Card c = cardList.getSelectedValue();
        if (c == null) {
            message.setText("Select a card first.");
            return;
        }
        JTextArea area = new JTextArea(c.text(), 6, 40);
        area.setFont(area.getFont().deriveFont(14f));
        int r = JOptionPane.showConfirmDialog(this, new JScrollPane(area), "Edit Card", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (r == JOptionPane.OK_OPTION) {
            // a blank line typed inside the card would start a new card: keep it one card
            String text = area.getText().replaceAll("\n\\s*\n", "\n");
            cardOp(i -> LyricsFormatter.replace(lyrics.getText(), i, text), 0);
        }
    }

    // ------------------------------------------------------------------ saving

    private void save(boolean thenSync) {
        String t = title.getText().strip();
        if (t.isEmpty()) {
            message.setText("Please enter the song title.");
            title.requestFocusInWindow();
            return;
        }
        List<SongSection> sections = LyricsParser.parse(lyrics.getText());
        if (sections.isEmpty()) {
            message.setText("Please add the lyrics.");
            return;
        }
        Song s = new Song();
        s.setTitle(t);
        s.setAuthor(artist.getText().strip());
        s.setSections(sections);
        s.setSourceType(draft.sourceType());
        s.setSourceProvider(draft.provider());
        s.setSourceReference(draft.reference());
        save.setEnabled(false);
        saveSync.setEnabled(false);
        BackgroundTask.run("Save lyrics", () -> {
            Song stored = songs.save(s);
            List<String> labels = stored.getSections().stream().map(SongSection::getLabel).toList();
            if (draft.arrangement() != null) {
                List<String> order = new ArrayList<>(draft.arrangement().stream().filter(labels::contains).toList());
                if (!order.isEmpty() && !order.equals(labels)) {
                    songs.setArrangement(stored.getId(), order);
                }
            }
            if (draft.timed()) {
                // the timestamps that came with the lyrics, per card
                Map<String, Long> times = LyricsFormatter.cardTimes(SyncEditorDialog.cardTexts(stored), draft.cues());
                if (!times.isEmpty()) {
                    LyricsSync sync = new LyricsSync();
                    sync.type = draft.sourceType() == LyricsSourceType.FILE_IMPORT ? LyricsSync.Type.FILE : LyricsSync.Type.PROVIDER;
                    sync.reference = draft.reference();
                    sync.cards.putAll(times);
                    sync.total = SyncEditorDialog.cardTexts(stored).size();
                    songs.setSync(stored.getId(), sync);
                }
            }
            return songs.load(stored.getId()).orElse(stored);
        }, stored -> {
            saved = stored;
            dispose();
            if (thenSync) {
                Window owner = getOwner();
                if (draft.timed() && stored.getSync() != null) {
                    SyncEditorDialog.edit(owner, stored, songs, new SyncEditorDialog.Reference(stored.getSync().type,
                            stored.getSync().reference, "imported timing (test with the manual timeline)"));
                } else {
                    SyncEditorDialog.sync(owner, stored, songs, stored.getYoutubeVideoId());
                }
            }
        }, err -> {
            save.setEnabled(true);
            saveSync.setEnabled(true);
            Dialogs.showError(this, "Could not save the lyrics.", err);
        });
    }

    /** Card name (Verse 1A) and its lines. */
    private final class CardRenderer extends JPanel implements ListCellRenderer<LyricsFormatter.Card> {
        private final JLabel name = new JLabel();
        private final JLabel text = new JLabel();

        CardRenderer() {
            super(new BorderLayout(0, 2));
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3),
                    BorderFactory.createCompoundBorder(Ui.line(true, true, true, true), BorderFactory.createEmptyBorder(6, 10, 6, 10))));
            name.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            add(name, BorderLayout.NORTH);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends LyricsFormatter.Card> list, LyricsFormatter.Card c, int index,
                                                      boolean selected, boolean focus) {
            int inSection = 0;
            for (int i = 0; i < cardModel.size(); i++) {
                if (cardModel.get(i).section() == c.section()) {
                    inSection++;
                }
            }
            name.setText(c.name(inSection).toUpperCase());
            text.setText("<html>" + c.text().toUpperCase().replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>");
            name.setForeground(selected ? Color.WHITE : Palette.secondaryText());
            text.setForeground(selected ? Color.WHITE : Palette.text());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setOpaque(true);
            return this;
        }
    }
}
