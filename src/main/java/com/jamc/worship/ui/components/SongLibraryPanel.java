package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.Theme;
import com.jamc.worship.service.ThemeService;
import com.jamc.worship.model.SongQuery;
import com.jamc.worship.service.SongService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.dialogs.SongEditorDialog;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Searchable song list with add / edit / duplicate / delete / favorite actions.
 *
 * <p>All database access runs in background tasks. Results of superseded searches or
 * selections are discarded by comparing request sequence numbers, so typing quickly can
 * never show stale results.
 */
public class SongLibraryPanel extends JPanel {

    private final SongService songService;
    private final DefaultListModel<Song> model = new DefaultListModel<>();
    private final JList<Song> list = new JList<>(model);
    private final JTextField searchField = new JTextField();
    private final JComboBox<SongQuery.Sort> sortCombo = new JComboBox<>(SongQuery.Sort.values());
    private final JLabel titleLabel = Ui.label("Lyrics", Palette::text, "h3");
    private final JLabel countLabel = Ui.small(" ");
    private final JLabel emptyLabel = Ui.label("", Palette::mutedText, null);
    private final CardLayout listCards = new CardLayout();
    private final JPanel listHolder = Ui.transparent(listCards);
    private final Timer searchDebounce;

    private final JButton editButton = Ui.toolButton("edit", "Edit song (Enter)");
    private final JButton duplicateButton = Ui.toolButton("copy", "Duplicate song (Ctrl+D)");
    private final JButton deleteButton = Ui.toolButton("trash", "Delete song (Delete)");
    private final JButton favoriteButton = Ui.toolButton("star", "Add to favorites");
    private final JButton addToServiceButton = Ui.primaryButton("Add to Service", "list-plus");

    private final List<SongSelectionListener> selectionListeners = new ArrayList<>();
    private Runnable rowClickHandler = () -> { };
    private final ThemeService themeService;
    private final StyleForm.ImageImporter importer;

    /** A song loaded for preview with its resolved look. */
    public record LoadedSong(Song song, SlideStyle style, String themeName) {
    }

    /** Receives the selected song (with sections and style), or null when nothing is selected. */
    @FunctionalInterface
    public interface SongSelectionListener {
        void songSelected(LoadedSong loaded);
    }
    private Consumer<Song> addToServiceHandler = s -> { };
    private Consumer<String> statusHandler = s -> { };

    private boolean favoritesOnly;
    private long searchSeq;
    private long loadSeq;
    private boolean suppressSelectionEvents;
    private Song currentSong;

    public SongLibraryPanel(SongService songService, ThemeService themeService, StyleForm.ImageImporter importer) {
        super(new BorderLayout());
        this.songService = songService;
        this.themeService = themeService;
        this.importer = importer;
        setOpaque(false);

        JButton addButton = Ui.button("ADD LYRICS", "plus");
        addButton.setToolTipText("Create or import lyrics (Ctrl+N creates them manually)");
        addButton.addActionListener(e -> showAddMenu(addButton));
        add(buildTop(addButton), BorderLayout.NORTH);
        add(buildList(), BorderLayout.CENTER);
        add(buildActions(), BorderLayout.SOUTH);

        searchDebounce = new Timer(180, e -> refresh(selectedId()));
        searchDebounce.setRepeats(false);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { searchDebounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { searchDebounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { searchDebounce.restart(); }
        });
        sortCombo.addActionListener(e -> refresh(selectedId()));
        updateActionState();
    }

    // ------------------------------------------------------------------ layout

    private JComponent buildTop(JButton addButton) {
        JPanel top = Ui.transparent(new BorderLayout());
        top.add(Ui.header(titleLabel, countLabel, addButton), BorderLayout.NORTH);

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search lyrics…");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("search", Icons.SMALL));
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setToolTipText("Search by title, author, words or tags (Ctrl+F)");
        Ui.bindKey(searchField, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "toList", () -> {
            if (!model.isEmpty()) {
                list.requestFocusInWindow();
                if (list.getSelectedIndex() < 0) {
                    list.setSelectedIndex(0);
                }
            }
        }, WHEN_FOCUSED);
        Ui.bindKey(searchField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "searchNow", () -> {
            searchDebounce.stop();
            refresh(selectedId());
        }, WHEN_FOCUSED);

        JPanel searchRow = Ui.transparent(new BorderLayout(6, 0));
        searchRow.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        searchRow.add(searchField, BorderLayout.CENTER);

        JPanel sortRow = Ui.transparent(new BorderLayout(6, 0));
        sortRow.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        sortRow.add(Ui.small("Sort"), BorderLayout.WEST);
        sortCombo.putClientProperty(FlatClientProperties.STYLE, "minimumWidth: 100");
        sortRow.add(sortCombo, BorderLayout.CENTER);

        JPanel controls = Ui.transparent(new BorderLayout());
        controls.add(searchRow, BorderLayout.NORTH);
        controls.add(sortRow, BorderLayout.SOUTH);
        top.add(controls, BorderLayout.CENTER);
        return top;
    }

    private JComponent buildList() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SongCellRenderer());
        list.setFixedCellHeight(50);
        list.setOpaque(false);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !suppressSelectionEvents) {
                onSelectionChanged();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && rowAt(e) >= 0) {
                    editSelected();
                } else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 && rowAt(e) >= 0) {
                    rowClickHandler.run();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "edit", this::editSelected, WHEN_FOCUSED);
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelected, WHEN_FOCUSED);
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK), "duplicate",
                this::duplicateSelected, WHEN_FOCUSED);

        list.setDragEnabled(true);
        list.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                Song song = list.getSelectedValue();
                return song == null ? null : new ServiceItemTransfer(ServiceItem.ofSong(song));
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);

        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setVerticalAlignment(SwingConstants.TOP);
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(40, 16, 0, 16));

        listHolder.add(scroll, "list");
        listHolder.add(emptyLabel, "empty");
        listHolder.setBorder(Ui.line(true, false, false, false));
        return listHolder;
    }

    private JComponent buildActions() {
        editButton.addActionListener(e -> editSelected());
        duplicateButton.addActionListener(e -> duplicateSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        favoriteButton.addActionListener(e -> toggleFavoriteSelected());
        addToServiceButton.addActionListener(e -> addSelectedToService());
        addToServiceButton.setToolTipText("Add the selected song to the service schedule (or drag it there)");

        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(editButton);
        left.add(duplicateButton);
        left.add(deleteButton);
        left.add(favoriteButton);

        JPanel bar = Ui.transparent(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 6, 6, 8)));
        bar.add(left, BorderLayout.WEST);
        bar.add(addToServiceButton, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------------ public API

    /** Called on a single click on a song row (also on the song that is already selected). */
    public void setRowClickHandler(Runnable handler) {
        this.rowClickHandler = handler;
    }

    public void addSelectionListener(SongSelectionListener listener) {
        selectionListeners.add(listener);
    }

    public void setAddToServiceHandler(Consumer<Song> handler) {
        this.addToServiceHandler = handler;
    }

    public void setStatusHandler(Consumer<String> handler) {
        this.statusHandler = handler;
    }

    public void setFavoritesOnly(boolean favoritesOnly) {
        if (this.favoritesOnly == favoritesOnly) {
            return;
        }
        this.favoritesOnly = favoritesOnly;
        titleLabel.setText(favoritesOnly ? "Favorite Lyrics" : "Lyrics");
        refresh(selectedId());
    }

    /** Clears the search and selects the song with this id (used when a service item is presented). */
    public void showSong(long id) {
        if (!searchField.getText().isEmpty()) {
            searchField.setText("");
        }
        searchDebounce.stop();
        refresh(id);
    }

    public void focusSearch() {
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    /** Song currently loaded for preview (with sections), or null. */
    public Song currentSong() {
        return currentSong;
    }

    /** Reloads the list, then reselects {@code selectId} if it is still visible. */
    public void refresh(Long selectId) {
        long seq = ++searchSeq;
        SongQuery query = new SongQuery(searchField.getText(), favoritesOnly, (SongQuery.Sort) sortCombo.getSelectedItem());
        BackgroundTask.run("Search songs", () -> songService.search(query), songs -> {
            if (seq != searchSeq) {
                return; // a newer search is in flight
            }
            applyResults(songs, selectId, query);
        }, err -> Dialogs.showError(this, "Could not load the song library.", err));
    }

    // ------------------------------------------------------------------ actions

    public void addSong() {
        BackgroundTask.run("Load themes", this::editorContext, ctx -> {
            Optional<Song> saved = SongEditorDialog.showDialog(SwingUtilities.getWindowAncestor(this), songService,
                    null, ctx);
            saved.ifPresent(s -> {
                statusHandler.accept("Added \"" + s.getTitle() + "\"");
                if (!searchField.getText().isBlank()) {
                    searchField.setText(""); // make sure the new song is visible
                }
                refresh(s.getId());
            });
        }, err -> Dialogs.showError(this, "Could not open the song editor.", err));
    }

    private com.jamc.worship.service.MediaService mediaService;
    private com.jamc.worship.config.AppConfig config;
    private com.jamc.worship.lyrics.LyricsProviders providers;

    /** What searching and YouTube Sync need: the lyrics providers' settings and the Video Library (optional links). */
    public void setYouTubeSupport(com.jamc.worship.service.MediaService media, com.jamc.worship.config.AppConfig config) {
        this.mediaService = media;
        this.config = config;
        this.providers = com.jamc.worship.lyrics.LyricsProviders.create(config, songService);
    }

    /** + ADD LYRICS: every way in is reviewed and ends as a normal Lyrics item (text). YouTube is optional. */
    private void showAddMenu(JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem find = menuItem("Search Lyrics…", "search", () -> searchLyrics(""));
        find.setToolTipText("Search the configured lyrics sources (Settings → Integrations → Lyrics)");
        find.setEnabled(providers != null);
        menu.add(find);
        menu.add(menuItem("Paste Lyrics…", "copy", () -> pasteLyrics("")));
        menu.add(menuItem("Create Manually", "edit", this::addSong));
        menu.add(menuItem("Import Lyrics File… (TXT, LRC, SRT, VTT)", "import", this::importLyricsFile));
        JMenuItem yt = menuItem("YouTube Sync…", "youtube", this::importFromYouTube);
        yt.setToolTipText("Identify the song on YouTube and keep it as a sync reference. Saves LYRICS, not a video.");
        yt.setEnabled(mediaService != null);
        menu.add(yt);
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** SEARCH LYRICS: FIND LYRICS → REVIEW LYRICS → SAVE (never saved without review). */
    public void searchLyrics(String words) {
        if (providers == null) {
            return;
        }
        Optional<Song> saved = com.jamc.worship.ui.dialogs.FindLyricsDialog.showDialog(SwingUtilities.getWindowAncestor(this),
                songService, providers, new com.jamc.worship.ui.dialogs.FindLyricsDialog.Alternatives() {
                    @Override public void paste(String title) { pasteLyrics(title); }
                    @Override public void createManually() { addSong(); }
                    @Override public void importFile() { importLyricsFile(); }
                    @Override public void youtube() { importFromYouTube(); }
                    @Override public void openExisting(long songId) {
                        searchField.setText("");
                        showSong(songId);
                    }
                }, words);
        saved.ifPresent(this::added);
    }

    /** PASTE LYRICS (always available): paste, AUTO FORMAT, SPLIT INTO CARDS, SAVE. */
    public void pasteLyrics(String title) {
        com.jamc.worship.ui.dialogs.LyricsEditorDialog.Draft d = com.jamc.worship.ui.dialogs.LyricsEditorDialog.Draft.paste();
        d = new com.jamc.worship.ui.dialogs.LyricsEditorDialog.Draft(d.heading(), title, "", "", d.sourceType(), null, null, null, null);
        com.jamc.worship.ui.dialogs.LyricsEditorDialog.showDialog(SwingUtilities.getWindowAncestor(this), songService, d)
                .ifPresent(this::added);
    }

    /** IMPORT FILE: TXT → plain lyrics; LRC / SRT / VTT → lyrics with timestamps per card. Reviewed before saving. */
    private void importLyricsFile() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Import lyrics file");
        javax.swing.filechooser.FileNameExtensionFilter filter = new javax.swing.filechooser.FileNameExtensionFilter(
                "Lyrics files (*.txt, *.lrc, *.srt, *.vtt)", "txt", "lrc", "srt", "vtt");
        chooser.addChoosableFileFilter(filter);
        chooser.setFileFilter(filter);
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.io.File f = chooser.getSelectedFile();
        BackgroundTask.run("Import lyrics", () -> {
            String text = java.nio.file.Files.readString(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            String name = f.getName().replaceFirst("\\.[^.]+$", "");
            int dash = name.indexOf(" - ");
            String title = dash > 0 ? name.substring(dash + 3) : name;
            String artist = dash > 0 ? name.substring(0, dash) : "";
            if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
                return new com.jamc.worship.ui.dialogs.LyricsEditorDialog.Draft("REVIEW LYRICS", title, artist,
                        com.jamc.worship.service.LyricsFormatter.autoFormat(text),
                        com.jamc.worship.model.LyricsSourceType.FILE_IMPORT, f.getName(), f.getAbsolutePath(), null, null);
            }
            com.jamc.worship.service.TimedLyricsParser.Result r = com.jamc.worship.service.TimedLyricsParser.parse(f.getName(), text);
            return new com.jamc.worship.ui.dialogs.LyricsEditorDialog.Draft("REVIEW LYRICS",
                    r.title() != null ? r.title() : title, r.artist() != null ? r.artist() : artist, r.text(),
                    com.jamc.worship.model.LyricsSourceType.FILE_IMPORT, f.getName(), f.getAbsolutePath(), r.cues(), r.arrangement());
        }, draft -> {
            if (draft.text().isBlank()) {
                Dialogs.showInfo(this, "Import lyrics", "No lyrics were found in " + f.getName() + ".");
                return;
            }
            com.jamc.worship.ui.dialogs.LyricsEditorDialog.showDialog(SwingUtilities.getWindowAncestor(this), songService, draft)
                    .ifPresent(this::added);
        }, err -> Dialogs.showError(this, "Could not import " + f.getName() + ".", err));
    }

    /** YOUTUBE SYNC: identify the song on YouTube; the result is a Lyrics item with an optional YouTube reference. */
    private void importFromYouTube() {
        if (mediaService == null) {
            return;
        }
        Optional<Song> saved = com.jamc.worship.ui.dialogs.YouTubeLyricsImportDialog.showDialog(SwingUtilities.getWindowAncestor(this),
                songService, mediaService, config, this::editorContext);
        saved.ifPresent(this::added);
    }

    private void added(Song s) {
        statusHandler.accept("Saved \"" + s.getTitle() + "\" to Lyrics");
        searchField.setText("");
        refresh(s.getId());
    }

    /**
     * SYNC LYRICS for a saved song: with YouTube, local audio, local video, a timing file or a manual timeline.
     * Works for every song, whether or not it came from YouTube.
     */
    public void syncSelected() {
        Song selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        BackgroundTask.run("Load song", () -> {
            Song s = songService.load(selected.getId()).orElse(null);
            String videoId = s == null ? null : s.getYoutubeVideoId();
            if (s != null && s.getSync() != null && s.getSync().youtubeVideoId() != null) {
                videoId = s.getSync().youtubeVideoId();
            }
            if (s != null && (videoId == null || videoId.isBlank()) && s.getYoutubeMediaId() != null && mediaService != null) {
                videoId = mediaService.load(s.getYoutubeMediaId()).map(com.jamc.worship.model.MediaItem::getYoutubeVideoId).orElse(null);
            }
            return new Object[]{s, videoId};
        }, r -> {
            Song s = (Song) r[0];
            if (s == null) {
                refresh(null);
                return;
            }
            if (com.jamc.worship.ui.dialogs.SyncEditorDialog.sync(SwingUtilities.getWindowAncestor(this), s, songService, (String) r[1])) {
                statusHandler.accept("Sync times saved for \"" + s.getTitle() + "\". Presenting still shows the text lyrics.");
                refresh(s.getId());
                reloadCurrent();
            }
        }, err -> Dialogs.showError(this, "Could not open Sync Lyrics.", err));
    }

    /** Removes the sync times; the lyrics are unchanged and still present normally. */
    private void removeSync() {
        Song selected = list.getSelectedValue();
        if (selected == null || !Dialogs.confirmDestructive(this, "Remove sync", "Remove the sync times of \""
                + selected.getTitle() + "\"?\n\nThe lyrics are not changed.", "Remove")) {
            return;
        }
        BackgroundTask.run("Remove sync", () -> {
            songService.setSync(selected.getId(), null);
            return true;
        }, ok -> refresh(selected.getId()), err -> Dialogs.showError(this, "Could not remove the sync times.", err));
    }

    /** Forgets the YouTube reference, link and sync times; the lyrics are unchanged. */
    private void removeYouTubeReference() {
        Song selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        BackgroundTask.run("Remove YouTube reference", () -> {
            songService.setYouTubeReference(selected.getId(), null, null, null);
            return true;
        }, ok -> {
            statusHandler.accept("YouTube reference removed from \"" + selected.getTitle() + "\"");
            refresh(selected.getId());
        }, err -> Dialogs.showError(this, "Could not remove the YouTube reference.", err));
    }

    public void editSelected() {
        Song selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        BackgroundTask.run("Load song for editing",
                () -> new Object[]{songService.load(selected.getId()), editorContext()}, r -> {
            @SuppressWarnings("unchecked")
            Optional<Song> loaded = (Optional<Song>) r[0];
            if (loaded.isEmpty()) {
                Dialogs.showInfo(this, "Song not found", "This song was removed. The list will be refreshed.");
                refresh(null);
                return;
            }
            Optional<Song> saved = SongEditorDialog.showDialog(SwingUtilities.getWindowAncestor(this),
                    songService, loaded.get(), (SongEditorDialog.Context) r[1]);
            saved.ifPresent(s -> {
                statusHandler.accept("Saved \"" + s.getTitle() + "\"");
                // The refreshed row carries a new updated-at time, so the preview reloads automatically.
                refresh(s.getId());
            });
        }, err -> Dialogs.showError(this, "Could not open the song.", err));
    }

    public void duplicateSelected() {
        Song selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        BackgroundTask.run("Duplicate song", () -> songService.duplicate(selected.getId()), copy -> {
            statusHandler.accept("Duplicated as \"" + copy.getTitle() + "\"");
            refresh(copy.getId());
        }, err -> Dialogs.showError(this, "Could not duplicate the song.", err));
    }

    public void deleteSelected() {
        Song selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        boolean confirmed = Dialogs.confirmDestructive(this, "Delete song",
                "Delete \"" + selected.getTitle() + "\" from the library?\n\nThis cannot be undone.", "Delete");
        if (!confirmed) {
            return;
        }
        int index = list.getSelectedIndex();
        Long neighbour = index + 1 < model.size() ? model.get(index + 1).getId()
                : index - 1 >= 0 ? model.get(index - 1).getId() : null;
        BackgroundTask.run("Delete song", () -> songService.delete(selected.getId()), deleted -> {
            statusHandler.accept("Deleted \"" + selected.getTitle() + "\"");
            refresh(neighbour);
        }, err -> Dialogs.showError(this, "Could not delete the song.", err));
    }

    public void toggleFavoriteSelected() {
        Song selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        boolean favorite = !selected.isFavorite();
        BackgroundTask.run("Toggle favorite", () -> {
            songService.setFavorite(selected.getId(), favorite);
            return favorite;
        }, fav -> {
            statusHandler.accept((fav ? "Added to" : "Removed from") + " favorites: \"" + selected.getTitle() + "\"");
            refresh(selected.getId());
        }, err -> Dialogs.showError(this, "Could not update favorites.", err));
    }

    public void addSelectedToService() {
        Song selected = list.getSelectedValue();
        if (selected != null) {
            addToServiceHandler.accept(selected);
        }
    }

    // ------------------------------------------------------------------ internals

    private void applyResults(List<Song> songs, Long selectId, SongQuery query) {
        suppressSelectionEvents = true;
        try {
            model.clear();
            model.addAll(songs);
            int index = -1;
            if (selectId != null) {
                for (int i = 0; i < songs.size(); i++) {
                    if (selectId.equals(songs.get(i).getId())) {
                        index = i;
                        break;
                    }
                }
            }
            if (index >= 0) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
            } else {
                list.clearSelection();
            }
        } finally {
            suppressSelectionEvents = false;
        }

        countLabel.setText(songs.size() + (songs.size() == 1 ? " song" : " songs")
                + (query.text().isBlank() ? "" : " found"));
        if (songs.isEmpty()) {
            emptyLabel.setText(emptyMessage(query));
            listCards.show(listHolder, "empty");
        } else {
            listCards.show(listHolder, "list");
        }
        updateActionState();
        onSelectionChanged();
    }

    private String emptyMessage(SongQuery query) {
        if (!query.text().isBlank()) {
            return "<html><center>No songs match “" + escape(query.text().strip()) + "”.</center></html>";
        }
        if (query.favoritesOnly()) {
            return "<html><center>No favorites yet.<br>Select a song and click the star.</center></html>";
        }
        return "<html><center>Your song library is empty.<br>Click + to add your first song.</center></html>";
    }

    private void onSelectionChanged() {
        updateActionState();
        Song selected = list.getSelectedValue();
        long seq = ++loadSeq;
        if (selected == null) {
            if (currentSong != null) {
                currentSong = null;
                selectionListeners.forEach(l -> l.songSelected(null));
            }
            return;
        }
        if (currentSong != null && currentSong.getId().equals(selected.getId())
                && sameVersion(currentSong, selected)) {
            return; // already showing this exact song
        }
        BackgroundTask.run("Load song", () -> loadForPreview(selected.getId()), loaded -> {
            if (seq != loadSeq) {
                return;
            }
            currentSong = loaded == null ? null : loaded.song();
            selectionListeners.forEach(l -> l.songSelected(loaded));
        }, err -> Dialogs.showError(this, "Could not load the song.", err));
    }

    /** Reloads the selected song and its style, e.g. after its theme was edited. */
    public void reloadCurrent() {
        currentSong = null;
        onSelectionChanged();
    }

    private LoadedSong loadForPreview(long id) {
        Optional<Song> song = songService.load(id);
        if (song.isEmpty()) {
            return null;
        }
        Theme theme = themeService.resolve(song.get().getThemeId());
        return new LoadedSong(song.get(), themeService.styleForSong(song.get()), theme.getName());
    }

    private SongEditorDialog.Context editorContext() {
        return new SongEditorDialog.Context(themeService.list(), themeService.defaultTheme(), importer,
                t -> themeService.styleFor(com.jamc.worship.model.ContentKind.LYRICS, t));
    }

    private static boolean sameVersion(Song a, Song b) {
        return a.getUpdatedAt() != null && a.getUpdatedAt().equals(b.getUpdatedAt()) && a.isFavorite() == b.isFavorite();
    }

    private void updateActionState() {
        Song selected = list.getSelectedValue();
        boolean has = selected != null;
        Ui.setEnabled(has, editButton, duplicateButton, deleteButton, favoriteButton, addToServiceButton);
        boolean fav = has && selected.isFavorite();
        favoriteButton.setIcon(Icons.get(fav ? "star-filled" : "star", Icons.MEDIUM));
        favoriteButton.setToolTipText(fav ? "Remove from favorites" : "Add to favorites");
    }

    private Long selectedId() {
        Song s = list.getSelectedValue();
        return s == null ? null : s.getId();
    }

    private int rowAt(MouseEvent e) {
        int row = list.locationToIndex(e.getPoint());
        return row >= 0 && list.getCellBounds(row, row).contains(e.getPoint()) ? row : -1;
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = rowAt(e);
        if (row < 0) {
            return;
        }
        list.setSelectedIndex(row);
        Song song = model.get(row);
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Edit…", "edit", this::editSelected));
        menu.add(menuItem("Duplicate", "copy", this::duplicateSelected));
        menu.add(menuItem(song.isFavorite() ? "Remove from Favorites" : "Add to Favorites", "star",
                this::toggleFavoriteSelected));
        menu.add(menuItem("Add to Service", "list-plus", this::addSelectedToService));
        // every song can be synced later (YouTube, local audio / video, timing file or manual timeline)
        menu.addSeparator();
        menu.add(menuItem("Sync Lyrics…", "play", this::syncSelected));
        if (song.getSync() != null && !song.getSync().isEmpty()) {
            menu.add(menuItem("Remove Sync Times", "x", this::removeSync));
        }
        if (song.hasYouTubeReference() || song.getYoutubeMediaId() != null) {
            menu.add(menuItem("Remove YouTube Reference", "x", this::removeYouTubeReference));
        }
        menu.addSeparator();
        menu.add(menuItem("Delete…", "trash", this::deleteSelected));
        menu.show(list, e.getX(), e.getY());
    }

    private static JMenuItem menuItem(String text, String icon, Runnable action) {
        JMenuItem item = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        item.addActionListener(e -> action.run());
        return item;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Two-line row: title (with favorite star) and "author · tags". */
    private static final class SongCellRenderer extends JPanel implements ListCellRenderer<Song> {
        private final JLabel title = new JLabel();
        private final JLabel subtitle = new JLabel();
        private final JLabel star = new JLabel();

        SongCellRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
            title.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
            subtitle.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            JPanel text = new JPanel(new BorderLayout(0, 2));
            text.setOpaque(false);
            text.add(title, BorderLayout.NORTH);
            text.add(subtitle, BorderLayout.SOUTH);
            add(text, BorderLayout.CENTER);
            add(star, BorderLayout.EAST);
            // a lyrics item is text: the music icon tells it apart from a YouTube video in the Video Library
            JLabel kind = new JLabel(Icons.get("music", Icons.MEDIUM, Palette::secondaryText));
            add(kind, BorderLayout.WEST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Song> list, Song song, int index,
                                                      boolean selected, boolean focused) {
            title.setText(song.getTitle());
            List<String> parts = new ArrayList<>();
            if (song.getAuthor() != null && !song.getAuthor().isBlank()) {
                parts.add(song.getAuthor());
            }
            if (!song.tagList().isEmpty()) {
                parts.add(String.join(", ", song.tagList()));
            }
            // sync status (optional: unsynced lyrics present normally, card by card)
            parts.add(song.getSync() == null ? com.jamc.worship.model.LyricsSync.Status.NOT_SYNCED.label()
                    : song.getSync().summaryStatus().label());
            if (song.hasYouTubeReference() || song.getYoutubeMediaId() != null) {
                // still a Lyrics item (text); the YouTube video is only a reference for syncing
                parts.add("YouTube ref");
            }
            subtitle.setText(parts.isEmpty() ? "No author" : String.join("  ·  ", parts));
            star.setIcon(song.isFavorite() ? Icons.get("star-filled", Icons.SMALL) : null);

            Color fg = selected ? list.getSelectionForeground() : Palette.text();
            Color fg2 = selected ? new Color(255, 255, 255, 200) : Palette.secondaryText();
            title.setForeground(fg);
            subtitle.setForeground(fg2);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setOpaque(selected);
            return this;
        }
    }
}
