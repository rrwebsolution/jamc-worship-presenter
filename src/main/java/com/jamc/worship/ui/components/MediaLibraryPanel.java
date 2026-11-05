package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.background.BackgroundPresets;
import com.jamc.worship.background.GlobalBackgroundManager;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.MediaChoosers;
import com.jamc.worship.media.MediaProbe;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.youtube.YouTubeClient;
import com.jamc.worship.youtube.YouTubeLibrary;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Searchable media browser (images, videos, audio, backgrounds) shown as a thumbnail grid or a list,
 * with import, rename, delete and favorites. Clicking an item only selects it for inspection in the
 * MEDIA PREVIEW monitor; nothing reaches Preview or Program until the operator asks.
 */
public class MediaLibraryPanel extends JPanel {

    private static final String ALL = "All media";
    static final String VIDEOS_ALL = "All videos";
    static final String VIDEOS_LOCAL = "Local videos";
    static final String VIDEOS_YOUTUBE = "YouTube videos";

    private final MediaService media;
    private final List<MediaCategory> fixedCategories;
    private final MediaCategory importCategory;
    private final DefaultListModel<MediaItem> model = new DefaultListModel<>();
    private final JList<MediaItem> list = new JList<>(model);
    private final JTextField search = new JTextField();
    private final JComboBox<Object> typeFilter = new JComboBox<>();
    private final JLabel title;
    private final JLabel count = Ui.small(" ");
    private final JLabel empty = Ui.label("", Palette::mutedText, null);
    private final CardLayout cards = new CardLayout();
    private final JPanel holder = Ui.transparent(cards);
    private final JButton renameButton = Ui.toolButton("edit", "Rename");
    private final JButton deleteButton = Ui.toolButton("trash", "Remove from library (Delete)");
    private final JButton favoriteButton = Ui.toolButton("star", "Favorite");
    private final JButton gridButton = Ui.toolButton("grid", "Grid / list view");
    private final MediaRenderer listRenderer = new MediaRenderer();
    private final TileRenderer tileRenderer = new TileRenderer();
    private boolean grid;
    private final List<Consumer<MediaItem>> selectionListeners = new ArrayList<>();
    private Consumer<ServiceItem> addToServiceHandler = i -> { };
    private Consumer<String> statusHandler = s -> { };
    private long seq;
    private boolean suppress;

    /**
     * @param categories      categories shown (empty = all with a type filter)
     * @param importCategory  category given to imported files (null = by file type)
     */
    public MediaLibraryPanel(MediaService media, String titleText, List<MediaCategory> categories, MediaCategory importCategory) {
        super(new BorderLayout());
        this.media = media;
        this.fixedCategories = categories;
        this.importCategory = importCategory;
        this.title = Ui.label(titleText, Palette::text, "h3");
        setOpaque(false);

        JButton importButton = Ui.toolButton("plus", importCategory == MediaCategory.VIDEO ? "Import video (local file or YouTube)" : "Import files");
        importButton.addActionListener(e -> {
            if (importCategory == MediaCategory.VIDEO) {
                showVideoImportMenu(importButton);
            } else {
                importFiles();
            }
        });
        gridButton.addActionListener(e -> setGrid(!grid));
        JPanel top = Ui.transparent(new BorderLayout());
        top.add(Ui.header(title, count, gridButton, importButton), BorderLayout.NORTH);
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search media…");
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("search", Icons.SMALL));
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        JPanel controls = Ui.transparent(new BorderLayout(0, 6));
        controls.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        controls.add(search, BorderLayout.NORTH);
        if (categories.isEmpty()) {
            typeFilter.addItem(ALL);
            for (MediaCategory c : MediaCategory.values()) {
                typeFilter.addItem(c);
            }
            typeFilter.addItem("★ Favorites");
            typeFilter.addActionListener(e -> refresh(null));
            controls.add(typeFilter, BorderLayout.SOUTH);
        } else if (importCategory == MediaCategory.BACKGROUND) {
            for (String k : new String[]{"All", "Images", "Videos", "Colors", "Gradients", "★ Favorites"}) {
                typeFilter.addItem(k);
            }
            typeFilter.addActionListener(e -> refresh(null));
            controls.add(typeFilter, BorderLayout.SOUTH);
        } else if (importCategory == MediaCategory.VIDEO) {
            // local files and YouTube references live together; local ones are listed first.
            // VideoLibraryPanel's tabs choose between them, so this filter is not shown.
            for (String k : new String[]{VIDEOS_ALL, VIDEOS_LOCAL, VIDEOS_YOUTUBE}) {
                typeFilter.addItem(k);
            }
            typeFilter.addActionListener(e -> refresh(null));
        }
        top.add(controls, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        Timer debounce = new Timer(200, e -> refresh(null));
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        // pictures and videos browse best as thumbnails; audio and the mixed list as rows
        setGrid(importCategory != null && importCategory != MediaCategory.AUDIO);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !suppress) {
                updateButtons();
                selectionListeners.forEach(l -> l.accept(list.getSelectedValue()));
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelected, WHEN_FOCUSED);
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename", this::renameSelected, WHEN_FOCUSED);
        list.setDragEnabled(true);
        list.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected java.awt.datatransfer.Transferable createTransferable(JComponent c) {
                MediaItem m = list.getSelectedValue();
                ServiceItem item = m == null ? null : serviceItem(m);
                return item == null ? null : new ServiceItemTransfer(item);
            }
        });
        Rendering.renderer().images().addChangeListener(list::repaint);
        VideoThumbnails.addListener(list::repaint);
        YouTubeLibrary.addListener(list::repaint); // thumbnails and ONLINE / OFFLINE checks

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setVerticalAlignment(SwingConstants.TOP);
        empty.setBorder(BorderFactory.createEmptyBorder(40, 16, 0, 16));
        holder.add(scroll, "list");
        holder.add(empty, "empty");
        holder.setBorder(Ui.line(true, false, false, false));
        add(holder, BorderLayout.CENTER);

        renameButton.addActionListener(e -> renameSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        favoriteButton.addActionListener(e -> toggleFavorite());
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(renameButton);
        left.add(deleteButton);
        left.add(favoriteButton);
        JPanel bar = Ui.transparent(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 6, 6, 8)));
        bar.add(left, BorderLayout.WEST);
        bar.add(Ui.small("Click to inspect in Media Preview"), BorderLayout.EAST);
        add(bar, BorderLayout.SOUTH);
        updateButtons();
    }

    public void addSelectionListener(Consumer<MediaItem> l) {
        selectionListeners.add(l);
    }

    public void setAddToServiceHandler(Consumer<ServiceItem> h) {
        addToServiceHandler = h;
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    public MediaItem selected() {
        return list.getSelectedValue();
    }

    /** Videos: list all, only local or only YouTube videos (VIDEOS_ALL, VIDEOS_LOCAL, VIDEOS_YOUTUBE). */
    void showVideos(String filter) {
        typeFilter.setSelectedItem(filter);
    }

    public void select(long id) {
        for (int i = 0; i < model.size(); i++) {
            if (Objects.equals(model.get(i).getId(), id)) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
        if (!search.getText().isEmpty()) {
            search.setText("");
        }
        refresh(id);
    }

    /** The schedule item for this media, or null for a built-in color / gradient background. */
    public static ServiceItem serviceItem(MediaItem m) {
        if (BackgroundPresets.isPreset(m)) {
            return null;
        }
        ServiceItem.Type t = switch (m.getKind()) {
            case IMAGE -> ServiceItem.Type.IMAGE;
            case VIDEO -> ServiceItem.Type.VIDEO;
            case AUDIO -> ServiceItem.Type.AUDIO;
        };
        return new ServiceItem(t, m.getName(), m.getId());
    }

    public void refresh(Long selectId) {
        long s = ++seq;
        List<MediaCategory> cats = new ArrayList<>(fixedCategories);
        boolean favorites = false;
        Object filter = typeFilter.getSelectedItem();
        if (fixedCategories.isEmpty() && filter instanceof MediaCategory c) {
            cats.add(c);
        } else if (fixedCategories.isEmpty() && filter instanceof String str && str.startsWith("★")) {
            favorites = true;
        }
        boolean fav = favorites;
        String text = search.getText();
        Long keep = selectId != null ? selectId : (list.getSelectedValue() == null ? null : list.getSelectedValue().getId());
        // Backgrounds: All / Images / Videos / Colors / Gradients / Favorites
        String kind = importCategory == MediaCategory.BACKGROUND && filter instanceof String k ? k : "All";
        boolean bgFav = kind.startsWith("★");
        boolean presets = importCategory == MediaCategory.BACKGROUND && !fav && !bgFav;
        String videoFilter = importCategory == MediaCategory.VIDEO && filter instanceof String v ? v : null;
        BackgroundTask.run("Load media", () -> {
            List<MediaItem> found = new ArrayList<>(media.find(cats, text, fav || bgFav));
            if (presets) {
                String q = text.strip().toLowerCase(java.util.Locale.ROOT);
                BackgroundPresets.all().stream().filter(p -> p.getName().toLowerCase(java.util.Locale.ROOT).contains(q))
                        .forEach(found::add);
            }
            found.removeIf(m -> switch (kind) {
                case "Images" -> BackgroundPresets.isPreset(m) || m.getKind() != MediaKind.IMAGE;
                case "Videos" -> m.getKind() != MediaKind.VIDEO;
                case "Colors" -> !m.getPath().startsWith(GlobalBackgroundManager.COLOR_PREFIX);
                case "Gradients" -> !m.getPath().startsWith(GlobalBackgroundManager.GRADIENT_PREFIX);
                default -> false;
            });
            if (videoFilter != null) {
                found.removeIf(m -> videoFilter.equals(VIDEOS_LOCAL) ? m.isYouTube() : videoFilter.equals(VIDEOS_YOUTUBE) && !m.isYouTube());
                found.sort(java.util.Comparator.comparing(MediaItem::isYouTube)); // LOCAL first, then YOUTUBE (stable: by name)
            }
            return found;
        }, items -> {
            if (s != seq) {
                return;
            }
            suppress = true;
            try {
                model.clear();
                model.addAll(items);
                for (int i = 0; i < items.size(); i++) {
                    if (Objects.equals(items.get(i).getId(), keep)) {
                        list.setSelectedIndex(i);
                        list.ensureIndexIsVisible(i);
                    }
                }
            } finally {
                suppress = false;
            }
            count.setText(items.size() + (items.size() == 1 ? " item" : " items"));
            empty.setText(text.isBlank() ? (importCategory == MediaCategory.VIDEO
                    ? "<html><center>Nothing here yet.<br>Click + to import a local video<br>or add one from YouTube.</center></html>"
                    : "<html><center>Nothing here yet.<br>Click + to import files.</center></html>")
                    : "<html><center>No media matches your search.</center></html>");
            cards.show(holder, items.isEmpty() ? "empty" : "list");
            updateButtons();
            selectionListeners.forEach(l -> l.accept(list.getSelectedValue()));
        }, err -> Dialogs.showError(this, "Could not load the media library.", err));
    }

    private com.jamc.worship.config.AppConfig config;

    /** Videos → Import from YouTube needs the settings (the church's YouTube API key). */
    public void setYouTubeSupport(com.jamc.worship.config.AppConfig config) {
        this.config = config;
    }

    /** + IMPORT VIDEO: a local file (VLC) or a YouTube video (a reference played by the embedded YouTube player). */
    private void showVideoImportMenu(JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Import Local Video…", "video", this::importFiles));
        JMenuItem yt = menuItem("Import from YouTube…", "youtube", this::importFromYouTube);
        yt.setToolTipText("Add a YouTube video to the Video Library. Nothing is downloaded; it plays from YouTube.");
        yt.setEnabled(config != null);
        menu.add(yt);
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void importFromYouTube() {
        java.util.Optional<MediaItem> added = com.jamc.worship.ui.dialogs.YouTubeVideoImportDialog.showDialog(
                javax.swing.SwingUtilities.getWindowAncestor(this), media, config);
        added.ifPresent(m -> {
            statusHandler.accept("YouTube video \"" + m.getName() + "\" is in Library → Videos. Select it to open it in Media Preview.");
            if (!search.getText().isEmpty()) {
                search.setText("");
            }
            refresh(m.getId());
        });
    }

    private void importFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Import media");
        List<String> exts = new ArrayList<>();
        for (MediaKind k : MediaKind.values()) {
            if (importCategory != MediaCategory.BACKGROUND || k != MediaKind.AUDIO) {
                exts.addAll(k.extensions());
            }
        }
        boolean backgrounds = importCategory == MediaCategory.BACKGROUND;
        FileNameExtensionFilter everything = new FileNameExtensionFilter(backgrounds ? "Background Files (images and videos)"
                : "Media Files (images, videos and audio)", exts.toArray(String[]::new));
        if (importCategory == MediaCategory.VIDEO) {
            MediaChoosers.videoFilters(chooser);
        } else if (importCategory == MediaCategory.IMAGE) {
            MediaChoosers.imageFilters(chooser);
        } else if (importCategory == MediaCategory.AUDIO) {
            FileNameExtensionFilter audio = new FileNameExtensionFilter("Audio Files", MediaKind.AUDIO.extensions().toArray(String[]::new));
            chooser.addChoosableFileFilter(audio);
            chooser.setFileFilter(audio);
        } else {
            MediaChoosers.videoFilters(chooser);
            chooser.addChoosableFileFilter(everything);
            chooser.setFileFilter(everything);
        }
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] files = chooser.getSelectedFiles();
        Object[] options = {"Copy into Library", "Link to Original", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this,
                "Copy the files into the application's media folder (recommended: they keep working if the\n"
                        + "originals are moved or a USB drive is removed), or link to where they are now?",
                "Import " + files.length + (files.length == 1 ? " file" : " files"), JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice != 0 && choice != 1) {
            return;
        }
        boolean copy = choice == 0;
        BackgroundTask.run("Import media", () -> {
            List<String> failed = new ArrayList<>();
            MediaItem last = null;
            for (File f : files) {
                try {
                    MediaKind kind = MediaKind.of(f.toPath()).orElse(null);
                    java.awt.Dimension size = null;
                    if (kind == null || kind == MediaKind.VIDEO) {
                        // VLC, not the extension, decides whether a video plays
                        MediaProbe.Result probe = MediaProbe.probeVideo(f.toPath());
                        if (probe.status() == MediaProbe.Status.UNPLAYABLE) {
                            failed.add(f.getName() + ": " + probe.message().replace("\n", " "));
                            continue;
                        }
                        if (kind == null && probe.status() != MediaProbe.Status.PLAYABLE) {
                            failed.add(f.getName() + ": not a known image, video or audio type.");
                            continue;
                        }
                        kind = MediaKind.VIDEO;
                        size = new java.awt.Dimension(probe.width(), probe.height());
                    }
                    // Images / Videos / Audio file what they really are (a video picked in Images goes to Videos)
                    MediaCategory target = importCategory == null || importCategory == MediaCategory.BACKGROUND
                            ? importCategory : MediaCategory.forKind(kind);
                    last = media.importFile(f.toPath(), copy, target, kind, size);
                } catch (Exception e) {
                    failed.add(f.getName() + ": " + e.getMessage());
                }
            }
            return new Object[]{last, failed};
        }, r -> {
            MediaItem last = (MediaItem) r[0];
            @SuppressWarnings("unchecked")
            List<String> failed = (List<String>) r[1];
            int ok = files.length - failed.size();
            statusHandler.accept("Imported " + ok + (ok == 1 ? " file" : " files"));
            refresh(last == null ? null : last.getId());
            if (!failed.isEmpty()) {
                boolean videos = failed.stream().anyMatch(s -> s.contains("Unable to play"));
                Dialogs.showInfo(this, "Some files were not imported", String.join("\n", failed)
                        + (videos ? "\n\n" + MediaProbe.RECOMMENDED : ""));
            }
        }, err -> Dialogs.showError(this, "Import failed.", err));
    }

    private void renameSelected() {
        MediaItem m = list.getSelectedValue();
        if (m == null) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Name:", "Rename", JOptionPane.PLAIN_MESSAGE, null, null, m.getName());
        if (name == null || name.toString().isBlank()) {
            return;
        }
        BackgroundTask.run("Rename media", () -> {
            media.rename(m.getId(), name.toString());
            return true;
        }, ok -> refresh(m.getId()), err -> Dialogs.showError(this, "Could not rename.", err));
    }

    private void deleteSelected() {
        MediaItem m = list.getSelectedValue();
        if (m == null) {
            return;
        }
        boolean deleteFile = false;
        if (m.isCopied()) {
            Object[] options = {"Remove and Delete File", "Remove from Library Only", "Cancel"};
            int c = JOptionPane.showOptionDialog(this, "Remove \"" + m.getName() + "\" from the library?\n\n"
                            + "The file was copied into the media folder. Delete that copy too?",
                    "Remove media", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            if (c == 2 || c < 0) {
                return;
            }
            deleteFile = c == 0;
        } else if (!Dialogs.confirmDestructive(this, "Remove media", "Remove \"" + m.getName()
                + "\" from the library?\n\n" + (m.isYouTube() ? "Only the reference is removed; nothing changes on YouTube."
                : "The original file is not deleted."), "Remove")) {
            return;
        }
        boolean del = deleteFile;
        BackgroundTask.run("Remove media", () -> {
            media.delete(m, del);
            return true;
        }, ok -> {
            statusHandler.accept("Removed \"" + m.getName() + "\"");
            list.clearSelection();
            refresh(null);
        }, err -> Dialogs.showError(this, "Could not remove the media.", err));
    }

    private void toggleFavorite() {
        MediaItem m = list.getSelectedValue();
        if (m == null) {
            return;
        }
        BackgroundTask.run("Favorite", () -> {
            media.setFavorite(m.getId(), !m.isFavorite());
            return true;
        }, ok -> refresh(m.getId()), err -> Dialogs.showError(this, "Could not update favorites.", err));
    }

    private void updateButtons() {
        MediaItem m = list.getSelectedValue();
        boolean has = m != null && !BackgroundPresets.isPreset(m);
        Ui.setEnabled(has, renameButton, deleteButton, favoriteButton);
        favoriteButton.setIcon(Icons.get(has && m.isFavorite() ? "star-filled" : "star", Icons.MEDIUM));
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int i = list.locationToIndex(e.getPoint());
        if (i < 0) {
            return;
        }
        list.setSelectedIndex(i);
        if (BackgroundPresets.isPreset(model.get(i))) {
            return; // built in: nothing to rename, move or remove
        }
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Rename…", "edit", this::renameSelected));
        menu.add(menuItem(model.get(i).isFavorite() ? "Remove from Favorites" : "Add to Favorites", "star", this::toggleFavorite));
        menu.add(menuItem("Add to Service", "list-plus", () -> addToServiceHandler.accept(serviceItem(model.get(i)))));
        JMenuItem move = menuItem(model.get(i).getCategory() == MediaCategory.BACKGROUND ? "Move to " + MediaCategory.forKind(model.get(i).getKind())
                : "Use as Background", "layers", () -> toggleBackgroundCategory(model.get(i)));
        // a YouTube video can only be video content (Video sources, fullscreen); backgrounds are local files
        move.setEnabled(model.get(i).getKind() != MediaKind.AUDIO && !model.get(i).isYouTube());
        menu.add(move);
        menu.addSeparator();
        menu.add(menuItem("Remove from Library…", "trash", this::deleteSelected));
        menu.show(list, e.getX(), e.getY());
    }

    private void toggleBackgroundCategory(MediaItem m) {
        MediaCategory target = m.getCategory() == MediaCategory.BACKGROUND ? MediaCategory.forKind(m.getKind()) : MediaCategory.BACKGROUND;
        BackgroundTask.run("Move media", () -> {
            media.setCategory(m.getId(), target);
            return true;
        }, ok -> {
            statusHandler.accept("\"" + m.getName() + "\" moved to " + target);
            refresh(null);
        }, err -> Dialogs.showError(this, "Could not move the media.", err));
    }

    private static JMenuItem menuItem(String text, String icon, Runnable action) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        i.addActionListener(e -> action.run());
        return i;
    }

    /** Small "VIDEO" tag in the thumbnail corner, so video backgrounds stand out from pictures. */
    static void paintVideoBadge(Graphics2D g2, int height) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        java.awt.Font f = g2.getFont().deriveFont(java.awt.Font.BOLD, 8.5f);
        var fm = g2.getFontMetrics(f);
        int w = fm.stringWidth("VIDEO") + 8;
        int h = fm.getHeight();
        g2.setColor(Palette.danger());
        g2.fillRoundRect(3, height - h - 3, w, h, 6, 6);
        g2.setColor(Color.WHITE);
        g2.setFont(f);
        g2.drawString("VIDEO", 7, height - h - 3 + fm.getAscent());
    }

    private void setGrid(boolean on) {
        grid = on;
        if (on) {
            list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
            list.setVisibleRowCount(-1);
            list.setCellRenderer(tileRenderer);
            list.setFixedCellWidth(TileRenderer.W);
            list.setFixedCellHeight(TileRenderer.H);
        } else {
            list.setLayoutOrientation(JList.VERTICAL);
            list.setVisibleRowCount(8);
            list.setCellRenderer(listRenderer);
            list.setFixedCellWidth(-1);
            list.setFixedCellHeight(58);
        }
        gridButton.setIcon(Icons.get(on ? "list" : "grid", Icons.MEDIUM));
        gridButton.setToolTipText(on ? "Show as a list" : "Show as thumbnails");
        list.revalidate();
        list.repaint();
    }

    /** Picture, video still, color / gradient swatch or type icon, letterboxed on black. */
    private void paintThumb(Graphics2D g2, Component c, MediaItem item, int width, int height) {
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, width, height);
        if (item == null) {
            return;
        }
        BufferedImage img = null;
        if (BackgroundPresets.isPreset(item)) {
            img = GlobalBackgroundManager.solidImage(item.getPath());
        } else if (item.getKind() == MediaKind.IMAGE) {
            img = Rendering.renderer().images().get(item.getPath());
        } else if (item.isYouTube()) {
            img = YouTubeLibrary.thumbnail(item.getYoutubeVideoId(), item.getThumbnailUrl());
        } else if (item.getKind() == MediaKind.VIDEO) {
            img = VideoThumbnails.get(media.resolve(item));
        }
        if (img != null) {
            double s = BackgroundPresets.isPreset(item) ? Math.max(width / (double) img.getWidth(), height / (double) img.getHeight())
                    : Math.min(width / (double) img.getWidth(), height / (double) img.getHeight());
            int w = (int) Math.ceil(img.getWidth() * s);
            int h = (int) Math.ceil(img.getHeight() * s);
            g2.drawImage(img, (width - w) / 2, (height - h) / 2, w, h, null);
        } else if (item.getKind() != MediaKind.IMAGE) {
            int size = Math.min(28, height / 2);
            Icons.get(item.getKind() == MediaKind.VIDEO ? "video" : "music", size, Palette::secondaryText)
                    .paintIcon(c, g2, (width - size) / 2, (height - size) / 2);
        }
        if (item.isYouTube()) {
            paintYouTubeBadges(g2, width, height, YouTubeLibrary.availability(item.getYoutubeVideoId()));
        } else if (item.getKind() == MediaKind.VIDEO) {
            paintVideoBadge(g2, height);
        }
    }

    /** "YOUTUBE" in the corner and a status dot, so YouTube references never look like local files. */
    static void paintYouTubeBadges(Graphics2D g2, int width, int height, YouTubeClient.Availability availability) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        java.awt.Font f = g2.getFont().deriveFont(java.awt.Font.BOLD, 8.5f);
        var fm = g2.getFontMetrics(f);
        int w = fm.stringWidth("YOUTUBE") + 8;
        int h = fm.getHeight();
        g2.setColor(new Color(0xDC2626));
        g2.fillRoundRect(3, height - h - 3, w, h, 6, 6);
        g2.setColor(Color.WHITE);
        g2.setFont(f);
        g2.drawString("YOUTUBE", 7, height - h - 3 + fm.getAscent());
        String status = availability.label();
        int sw = fm.stringWidth(status) + 16;
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRoundRect(width - sw - 3, 3, sw, h, 6, 6);
        g2.setColor(availabilityColor(availability));
        g2.fillOval(width - sw + 1, 3 + (h - 6) / 2, 6, 6);
        g2.setColor(Color.WHITE);
        g2.drawString(status, width - sw + 10, 3 + fm.getAscent());
    }

    static Color availabilityColor(YouTubeClient.Availability a) {
        return switch (a) {
            case ONLINE -> new Color(0x22C55E);
            case OFFLINE -> new Color(0xF59E0B);
            case UNAVAILABLE -> new Color(0xEF4444);
            case UNKNOWN -> new Color(0x94A3B8);
        };
    }

    /** Thumbnail tile with the name underneath (grid view). */
    private final class TileRenderer extends JPanel implements ListCellRenderer<MediaItem> {
        static final int W = 148;
        static final int H = 112;
        private final JLabel name = new JLabel();
        private MediaItem item;
        private boolean selected;

        TileRenderer() {
            super(new BorderLayout(0, 4));
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            JComponent thumb = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    paintThumb(g2, this, item, getWidth(), getHeight());
                    if (selected) {
                        g2.setColor(Palette.accent());
                        g2.setStroke(new java.awt.BasicStroke(3f));
                        g2.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
                    }
                    g2.dispose();
                }
            };
            thumb.setPreferredSize(new Dimension(W - 12, (W - 12) * 9 / 16));
            name.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            name.setHorizontalAlignment(SwingConstants.CENTER);
            add(thumb, BorderLayout.CENTER);
            add(name, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends MediaItem> l, MediaItem m, int index, boolean isSelected,
                                                      boolean focused) {
            item = m;
            selected = isSelected;
            name.setText((m.isFavorite() ? "★ " : "") + m.getName());
            name.setForeground(isSelected ? Palette.accent() : Palette.text());
            List<String> tip = new ArrayList<>();
            tip.add(BackgroundPresets.isPreset(m) ? BackgroundPresets.describe(m) : m.isYouTube()
                    ? "YouTube video" + (m.getChannelName() == null || m.getChannelName().isBlank() ? "" : " · " + m.getChannelName())
                    : m.getKind().name().toLowerCase());
            if (m.getWidth() != null) {
                tip.add(m.getWidth() + "×" + m.getHeight());
            }
            if (m.getDurationMs() != null) {
                tip.add(TransportControls.format(m.getDurationMs()));
            }
            setToolTipText(m.getName() + "  ·  " + String.join("  ·  ", tip));
            setOpaque(false);
            return this;
        }
    }

    /** Thumbnail (images) or type icon, name and details. */
    private final class MediaRenderer extends JPanel implements ListCellRenderer<MediaItem> {
        private final JLabel name = new JLabel();
        private final JLabel info = new JLabel();
        private MediaItem item;

        MediaRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
            JComponent thumb = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    paintThumb(g2, this, item, getWidth(), getHeight());
                    g2.dispose();
                }
            };
            thumb.setPreferredSize(new Dimension(80, 45));
            name.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
            info.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            JPanel text = new JPanel(new BorderLayout(0, 2));
            text.setOpaque(false);
            text.add(name, BorderLayout.NORTH);
            text.add(info, BorderLayout.SOUTH);
            add(thumb, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends MediaItem> l, MediaItem m, int index, boolean selected,
                                                      boolean focused) {
            item = m;
            name.setText((m.isFavorite() ? "★ " : "") + m.getName());
            List<String> parts = new ArrayList<>();
            if (BackgroundPresets.isPreset(m)) {
                info.setText(BackgroundPresets.describe(m) + "  ·  built in");
                name.setText(m.getName());
                name.setForeground(selected ? l.getSelectionForeground() : Palette.text());
                info.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
                setBackground(selected ? l.getSelectionBackground() : l.getBackground());
                setOpaque(selected);
                return this;
            }
            if (m.isYouTube()) {
                parts.add("YouTube");
                if (m.getChannelName() != null && !m.getChannelName().isBlank()) {
                    parts.add(m.getChannelName());
                }
                if (m.getDurationMs() != null) {
                    parts.add(TransportControls.format(m.getDurationMs()));
                }
                parts.add(YouTubeLibrary.availability(m.getYoutubeVideoId()).label());
                info.setText(String.join("  ·  ", parts));
                name.setForeground(selected ? l.getSelectionForeground() : Palette.text());
                info.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
                setBackground(selected ? l.getSelectionBackground() : l.getBackground());
                setOpaque(selected);
                return this;
            }
            parts.add(m.getCategory() == MediaCategory.BACKGROUND ? "Background " + m.getKind().name().toLowerCase() : m.getKind().name().toLowerCase());
            if (m.getWidth() != null) {
                parts.add(m.getWidth() + "×" + m.getHeight());
            }
            if (m.getDurationMs() != null) {
                parts.add(TransportControls.format(m.getDurationMs()));
            }
            parts.add(m.isCopied() ? "in library" : "linked");
            info.setText(String.join("  ·  ", parts));
            name.setForeground(selected ? l.getSelectionForeground() : Palette.text());
            info.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            setOpaque(selected);
            return this;
        }
    }
}
