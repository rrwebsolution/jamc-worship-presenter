package com.jamc.worship.ui.dialogs;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.model.LyricsSync;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongSection;
import com.jamc.worship.service.LyricsParser;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.service.SongService;
import com.jamc.worship.service.TimedLyricsParser;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.components.YouTubeMonitor;
import com.jamc.worship.ui.components.YouTubeSearchPanel;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.youtube.SongTitleGuess;
import com.jamc.worship.youtube.YouTubeVideo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Library → Lyrics → Import from YouTube ("IMPORT LYRICS FROM YOUTUBE").
 *
 * <p>The YouTube search only identifies the song: it suggests the title and artist and is remembered as an
 * optional reference for lyrics sync. The result is always a normal <b>Lyrics</b> item (text sections
 * presented as lyrics cards), never a video. Lyrics are not taken from YouTube: YouTube only lets a video's
 * owner download its captions and song lyrics are copyrighted, so the operator pastes or imports lyrics the
 * church is licensed to use (TXT, LRC, SRT, VTT).
 */
public final class YouTubeLyricsImportDialog extends JDialog {

    private final SongService songs;
    private final MediaService media;
    private final Callable<SongEditorDialog.Context> editorContext;
    private final YouTubeMonitor reference = new YouTubeMonitor("REFERENCE");
    private final JLabel referenceText = Ui.label("No YouTube video selected yet.", Palette::secondaryText, "small");
    private final JTextField titleField = new JTextField();
    private final JTextField artistField = new JTextField();
    private final JTextArea lyrics = new JTextArea();
    private final JLabel summary = Ui.label(" ", Palette::secondaryText, "small");
    private final JLabel message = Ui.label(" ", Palette::danger, "small");
    private final JCheckBox remember = new JCheckBox("Remember this YouTube video for Sync Lyrics (secondary metadata)", true);
    private final JComboBox<Object> link = new JComboBox<>();
    private final JButton review = Ui.button("REVIEW", "edit");
    private final JButton save = Ui.primaryButton("SAVE TO LYRICS", "music");
    private YouTubeVideo video;
    /** From an imported LRC / SRT / VTT: section order and start times (kept while those sections exist). */
    private List<String> importedArrangement;
    private Map<String, Long> importedSync;
    private Song saved;

    private static final String NO_LINK = "None";

    private YouTubeLyricsImportDialog(Window owner, SongService songs, MediaService media, AppConfig config,
                                      Callable<SongEditorDialog.Context> editorContext) {
        super(owner, "Import Lyrics from YouTube", ModalityType.APPLICATION_MODAL);
        this.songs = songs;
        this.media = media;
        this.editorContext = editorContext;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        YouTubeSearchPanel search = new YouTubeSearchPanel(config, "Goodness of God Bethel Music", this::select);
        search.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 12));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, search, buildForm());
        split.setResizeWeight(0.4);
        split.setBorder(null);

        JPanel root = Ui.panel(new BorderLayout(), Palette::panelBackground);
        JPanel header = Ui.header(Ui.label("Import Lyrics from YouTube", Palette::text, "h3"),
                Ui.small("Find the song on YouTube to identify it. The result is saved as LYRICS (text), never as a video."));
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 16)));
        root.add(header, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);
        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose, JComponent.WHEN_IN_FOCUSED_WINDOW);

        Timer parse = new Timer(200, e -> updateSummary());
        parse.setRepeats(false);
        lyrics.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { parse.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { parse.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { parse.restart(); }
        });
        loadLinkChoices();
        updateSummary();
        setMinimumSize(new Dimension(1000, 640));
        setSize(1180, 760);
        setLocationRelativeTo(owner);
        search.focusSearch();
    }

    /** Shows the dialog; returns the saved Lyrics item, if any. */
    public static Optional<Song> showDialog(Window owner, SongService songs, MediaService media, AppConfig config,
                                            Callable<SongEditorDialog.Context> editorContext) {
        YouTubeLyricsImportDialog d = new YouTubeLyricsImportDialog(owner, songs, media, config, editorContext);
        com.jamc.worship.ui.SoftModal.showAndWait(d); // minimizing it lets the app be used meanwhile
        return Optional.ofNullable(d.saved);
    }

    // ------------------------------------------------------------------ layout

    private JComponent buildForm() {
        JPanel form = Ui.transparent(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 12, 10, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(Ui.label("IMPORT LYRICS", Palette::secondaryText, "semibold"), c);

        // the identified song: a small reference picture and its YouTube title
        reference.setPreferredSize(new Dimension(176, 99));
        reference.setMinimumSize(new Dimension(176, 99));
        JPanel ref = Ui.transparent(new BorderLayout(10, 0));
        ref.add(reference, BorderLayout.WEST);
        ref.add(referenceText, BorderLayout.CENTER);
        c.gridy++;
        form.add(ref, c);

        titleField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Goodness of God");
        artistField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Bethel Music");
        JPanel names = Ui.transparent(new java.awt.GridLayout(2, 2, 10, 2));
        names.add(Ui.caption("Title *"));
        names.add(Ui.caption("Artist"));
        names.add(titleField);
        names.add(artistField);
        c.gridy++;
        form.add(names, c);

        JLabel notice = Ui.label("<html><body style='width:540px'><b>Lyrics are not downloaded from YouTube.</b> YouTube only lets a video's owner "
                + "download its captions, and song lyrics are copyrighted. Paste or import lyrics your church is licensed "
                + "to use (for example from CCLI SongSelect). Headings such as Verse 1, Chorus and Bridge become lyrics cards.</html>",
                Palette::warning, "small");
        c.gridy++;
        form.add(notice, c);

        JButton paste = Ui.button("Paste Lyrics", "copy");
        paste.addActionListener(e -> pasteLyrics());
        JButton txt = Ui.button("Import TXT…", "import");
        txt.addActionListener(e -> importFile(false));
        JButton timed = Ui.button("Import LRC / SRT / VTT…", "import");
        timed.setToolTipText("Timed lyrics: sections, their order and their start times (for Sync Lyrics) are kept");
        timed.addActionListener(e -> importFile(true));
        JPanel sources = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sources.add(paste);
        sources.add(javax.swing.Box.createHorizontalStrut(6));
        sources.add(txt);
        sources.add(javax.swing.Box.createHorizontalStrut(6));
        sources.add(timed);
        c.gridy++;
        form.add(sources, c);

        lyrics.setLineWrap(true);
        lyrics.setWrapStyleWord(true);
        lyrics.setRows(12);
        lyrics.setFont(lyrics.getFont().deriveFont(14f));
        lyrics.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Verse 1\nI love You, Lord…\n\nChorus\nAll my life You have been faithful…");
        JScrollPane scroll = new JScrollPane(lyrics);
        c.gridy++;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 4, 0);
        form.add(scroll, c);
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy++;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(summary, c);

        remember.setOpaque(false);
        remember.setToolTipText("Saved with the lyrics as an optional reference. The lyrics are still presented as text only.");
        c.gridy++;
        c.insets = new Insets(0, 0, 4, 0);
        form.add(remember, c);
        JPanel linkRow = Ui.transparent(new BorderLayout(8, 0));
        linkRow.add(Ui.small("Linked YouTube Video (optional):"), BorderLayout.WEST);
        linkRow.add(link, BorderLayout.CENTER);
        link.setToolTipText("A YouTube video already in Library → Videos, linked for syncing. The two stay separate library items.");
        c.gridy++;
        form.add(linkRow, c);
        return form;
    }

    private JComponent buildFooter() {
        JButton cancel = Ui.button("Cancel", null);
        cancel.addActionListener(e -> dispose());
        review.setToolTipText("Open the lyrics in the song editor to check sections and slides before saving");
        review.addActionListener(e -> review());
        save.setToolTipText("Save as a Lyrics item in Library → Lyrics");
        save.addActionListener(e -> saveNow());
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(cancel);
        right.add(review);
        right.add(save);
        JPanel footer = Ui.transparent(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(8, 16, 8, 12)));
        footer.add(message, BorderLayout.CENTER);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    // ------------------------------------------------------------------ actions

    private void select(YouTubeVideo v) {
        video = v;
        reference.show(v, null);
        referenceText.setText("<html><b>" + escape(v.title()) + "</b><br>" + escape(v.channel())
                + "<br><span style='color:#94A3B8'>Used to identify the song only. Not saved as a video.</span></html>");
        SongTitleGuess.Guess g = SongTitleGuess.from(v.title(), v.channel());
        // fill what the operator has not typed yet (or what the previous selection filled)
        if (titleField.getText().isBlank() || Objects.equals(titleField.getClientProperty("guessed"), titleField.getText())) {
            titleField.setText(g.title());
            titleField.setCaretPosition(0);
            titleField.putClientProperty("guessed", g.title());
        }
        if (artistField.getText().isBlank() || Objects.equals(artistField.getClientProperty("guessed"), artistField.getText())) {
            artistField.setText(g.artist());
            artistField.setCaretPosition(0);
            artistField.putClientProperty("guessed", g.artist());
        }
        // pre-select the same video if it is already in the Video Library
        for (int i = 0; i < link.getItemCount(); i++) {
            if (link.getItemAt(i) instanceof MediaItem m && v.id().equals(m.getYoutubeVideoId())) {
                link.setSelectedIndex(i);
            }
        }
        message.setText(" ");
    }

    private void loadLinkChoices() {
        link.addItem(NO_LINK);
        BackgroundTask.run("Load YouTube videos", () -> media.find(List.of(MediaCategory.VIDEO), "", false).stream()
                .filter(MediaItem::isYouTube).toList(), items -> items.forEach(link::addItem), null);
    }

    private void pasteLyrics() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (data instanceof String s && !s.isBlank()) {
                setLyrics(s.strip(), null, null);
            } else {
                message.setText("The clipboard has no text. Copy the lyrics first.");
            }
        } catch (Exception e) {
            message.setText("The clipboard has no text. Copy the lyrics first.");
        }
    }

    private void importFile(boolean timed) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(timed ? "Import timed lyrics (LRC, SRT, VTT)" : "Import lyrics text");
        FileNameExtensionFilter filter = timed ? new FileNameExtensionFilter("Timed lyrics (*.lrc, *.srt, *.vtt)", "lrc", "srt", "vtt")
                : new FileNameExtensionFilter("Text files (*.txt)", "txt");
        chooser.addChoosableFileFilter(filter);
        chooser.setFileFilter(filter);
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = chooser.getSelectedFile();
        BackgroundTask.run("Read lyrics file", () -> Files.readString(f.toPath(), StandardCharsets.UTF_8), text -> {
            if (!timed) {
                setLyrics(text.strip(), null, null);
                return;
            }
            TimedLyricsParser.Result r = TimedLyricsParser.parse(f.getName(), text);
            if (r.text().isBlank()) {
                message.setText("No timed lyrics lines were found in " + f.getName() + ".");
                return;
            }
            setLyrics(r.text(), r.arrangement(), r.sync());
            if (titleField.getText().isBlank() && r.title() != null) {
                titleField.setText(r.title());
            }
            if (artistField.getText().isBlank() && r.artist() != null) {
                artistField.setText(r.artist());
            }
        }, err -> Dialogs.showError(this, "Could not read " + f.getName() + ".", err));
    }

    private void setLyrics(String text, List<String> arrangement, Map<String, Long> sync) {
        lyrics.setText(text);
        lyrics.setCaretPosition(0);
        importedArrangement = arrangement;
        importedSync = sync;
        message.setText(" ");
        updateSummary();
    }

    private void updateSummary() {
        List<SongSection> sections = LyricsParser.parse(lyrics.getText());
        if (sections.isEmpty()) {
            summary.setText("No lyrics yet. Paste them, or import a TXT, LRC, SRT or VTT file.");
        } else {
            List<String> labels = sections.stream().map(SongSection::getLabel).toList();
            summary.setText(sections.size() + (sections.size() == 1 ? " section: " : " sections: ") + String.join(", ", labels)
                    + (importedArrangement != null ? "   ·   order and sync times from the timed file" : ""));
        }
        boolean ok = !sections.isEmpty();
        review.setEnabled(ok);
        save.setEnabled(ok);
    }

    /** The Lyrics item to save (no id yet), or null with a message when something is missing. */
    private Song buildSong() {
        String title = titleField.getText().strip();
        if (title.isEmpty()) {
            message.setText("Please enter the song title.");
            titleField.requestFocusInWindow();
            return null;
        }
        List<SongSection> sections = LyricsParser.parse(lyrics.getText());
        if (sections.isEmpty()) {
            message.setText("Please add the lyrics.");
            return null;
        }
        Song s = new Song();
        s.setTitle(title);
        s.setAuthor(artistField.getText().strip());
        s.setSourceType(com.jamc.worship.model.LyricsSourceType.YOUTUBE_REFERENCE);
        s.setSourceProvider(video == null ? null : "YouTube: " + video.channel());
        s.setSourceReference(video == null ? null : video.watchUrl());
        s.setSections(sections);
        if (video != null && remember.isSelected()) {
            s.setYoutubeVideoId(video.id());
            s.setYoutubeUrl(video.watchUrl());
            s.setSync(sync(sections, video.id()));
        }
        if (link.getSelectedItem() instanceof MediaItem m) {
            s.setYoutubeMediaId(m.getId());
        }
        return s;
    }

    /** Start times from an imported timed file, for the sections that still exist. */
    private LyricsSync sync(List<SongSection> sections, String videoId) {
        if (importedSync == null || importedSync.isEmpty()) {
            return null;
        }
        LyricsSync sync = new LyricsSync();
        sync.type = LyricsSync.Type.YOUTUBE;
        sync.reference = videoId;
        Map<String, Long> kept = new LinkedHashMap<>();
        for (SongSection section : sections) {
            Long t = importedSync.get(section.getLabel());
            if (t != null) {
                kept.put(section.getLabel() + "/0", t); // the section's first card
            }
        }
        sync.cards = kept;
        return sync.isEmpty() ? null : sync;
    }

    private List<String> arrangement(List<SongSection> sections) {
        if (importedArrangement == null) {
            return null;
        }
        List<String> labels = sections.stream().map(SongSection::getLabel).toList();
        List<String> out = new ArrayList<>(importedArrangement.stream().filter(labels::contains).toList());
        return out.isEmpty() || out.equals(labels) ? null : out;
    }

    private void saveNow() {
        Song s = buildSong();
        if (s == null) {
            return;
        }
        List<String> order = arrangement(s.getSections());
        setBusy(true);
        BackgroundTask.run("Save lyrics", () -> {
            Song stored = songs.save(s);
            if (order != null) {
                songs.setArrangement(stored.getId(), order);
            }
            return stored;
        }, stored -> {
            saved = stored;
            dispose();
        }, err -> {
            setBusy(false);
            message.setText(err.getMessage());
        });
    }

    /** REVIEW: the song editor with these lyrics; saving there saves the Lyrics item (with the YouTube reference). */
    private void review() {
        Song s = buildSong();
        if (s == null) {
            return;
        }
        List<String> order = arrangement(s.getSections());
        setBusy(true);
        BackgroundTask.run("Load themes", editorContext, ctx -> {
            setBusy(false);
            Optional<Song> stored = SongEditorDialog.showDialog(this, songs, s, ctx);
            stored.ifPresent(st -> BackgroundTask.run("Save YouTube reference", () -> {
                // the editor saves the lyrics; the reference, link, sync times and order are stored with them
                songs.setYouTubeReference(st.getId(), s.getYoutubeVideoId(), s.getYoutubeMediaId(), s.getSync());
                if (order != null) {
                    songs.setArrangement(st.getId(), order);
                }
                return songs.load(st.getId()).orElse(st);
            }, done -> {
                saved = done;
                dispose();
            }, err -> Dialogs.showError(this, "The lyrics were saved, but the YouTube reference could not be stored.", err)));
        }, err -> {
            setBusy(false);
            Dialogs.showError(this, "Could not open the song editor.", err);
        });
    }

    private void setBusy(boolean busy) {
        review.setEnabled(!busy);
        save.setEnabled(!busy);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
