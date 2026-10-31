package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.camera.CameraManager;
import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.output.Compositor;
import com.jamc.worship.output.FrameSpec;
import com.jamc.worship.output.ProgramOutput;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.service.PresentationService;
import com.jamc.worship.service.SceneService;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.Presentation;
import com.jamc.worship.ui.dialogs.LibraryPickerDialog;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.util.Colors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Scenes page: create and arrange scenes from layered sources, preview them with the current
 * Program content, and send a scene live. Edits to the live scene apply to Program immediately.
 */
public class ScenesPanel extends JPanel {

    private final SceneService scenes;
    private final ProgramOutput program;
    private final CameraManager cameras;
    private final StyleForm.ImageImporter importer;
    private final PresentationService presentations;
    private final MediaService media;
    private final DefaultListModel<Scene> sceneModel = new DefaultListModel<>();
    private final JList<Scene> sceneList = new JList<>(sceneModel);
    private final DefaultListModel<Source> sourceModel = new DefaultListModel<>();
    private final JList<Source> sourceList = new JList<>(sourceModel);
    private final ScenePreview preview = new ScenePreview();
    private final JLabel previewTitle = Ui.label(" ", Palette::text, "h3");
    private final JButton liveButton = Ui.primaryButton("Make Live Scene", "monitor");
    private final Timer saveTimer;
    private Consumer<List<Scene>> scenesChanged = l -> { };
    private Consumer<Scene> liveSceneChosen = s -> { };
    private Consumer<String> statusHandler = s -> { };
    private Scene editing;

    // source properties
    private final JTextField nameField = new JTextField();
    // position and size in output pixels (1920 × 1080), stored as fractions of the frame
    private final JSpinner xSpin = pixels(-W, 2 * W);
    private final JSpinner ySpin = pixels(-H, 2 * H);
    private final JSpinner wSpin = pixels(10, 2 * W);
    private final JSpinner hSpin = pixels(10, 2 * H);
    private final JCheckBox visibleBox = new JCheckBox("Visible");
    private final JCheckBox keepAspect = new JCheckBox("Maintain aspect ratio (16:9)");
    // Presentation source
    private final JComboBox<SourceConfig.PresentationMode> modeCombo = new JComboBox<>(SourceConfig.PresentationMode.values());
    private final JLabel presentationLabel = Ui.label("—", Palette::text, "semibold");
    private final JButton selectFromLibrary = Ui.button("SELECT FROM LIBRARY", "library");
    private final JButton prevSlide = Ui.button("PREVIOUS", "chevron-left");
    private final JButton nextSlide = Ui.button("NEXT", "chevron-right");
    private final JLabel slideLabel = Ui.label("–", Palette::text, "semibold");
    private final DefaultListModel<Integer> slideModel = new DefaultListModel<>();
    private final JList<Integer> slideStrip = new JList<>(slideModel);
    private final JButton fitToCanvas = Ui.button("FIT TO CANVAS", "monitor");
    private final JButton resetPosition = Ui.button("RESET POSITION", "refresh");
    // VIDEO source: picture and sound
    private final JCheckBox videoBox = new JCheckBox("Video");
    private final JCheckBox audioBox = new JCheckBox("Audio");
    private final JComboBox<String> audioOut = new JComboBox<>(new String[]{"Program output (follows Program audio)", "Preview monitor only"});
    private final JSlider srcVolume = new JSlider(0, 150, 100);
    private final JLabel srcVolumeLabel = Ui.small("100%");
    private final JToggleButton srcMute = Ui.muteToggle("this source (the picture stays)");
    private final JToggleButton loopBox = Ui.loopToggle();
    private final JCheckBox autoplayBox = new JCheckBox("Autoplay when the scene goes to Program");
    private final JComboBox<String> takeCombo = new JComboBox<>(new String[]{"As in Settings → Audio", "Start from the beginning", "Continue from Preview"});
    private final JLabel audioStatus = Ui.small(" ");
    // IMAGE SLIDESHOW source
    private com.jamc.worship.output.Slideshows slideshows;
    private final JLabel slideshowStatus = Ui.label(" ", Palette::text, "semibold");
    private final JCheckBox slideAuto = new JCheckBox("Auto Play");
    private final JCheckBox slideShuffle = new JCheckBox("Shuffle");
    private final JToggleButton slideLoop = Ui.loopToggle();
    private final JSpinner slideSeconds = new JSpinner(new javax.swing.SpinnerNumberModel(5, 1, 300, 1));
    private final JComboBox<com.jamc.worship.output.Slideshows.Transition> slideTransition =
            new JComboBox<>(com.jamc.worship.output.Slideshows.Transition.values());
    private final JSpinner slideMs = new JSpinner(new javax.swing.SpinnerNumberModel(500, 0, 5000, 100));
    private final JComboBox<String> slideOnProgram = new JComboBox<>(new String[]{"Program shows the Preview image", "Program starts from the first image"});
    private final JComboBox<String> slideBackground = new JComboBox<>(new String[]{"Transparent", "Black"});
    /** Refreshes "▶ PLAYING 3 / 8 · next in 3.2 s" while a slideshow source is selected. */
    private final Timer slideshowTimer = new Timer(250, e -> updateSlideshowStatus());
    /** Thumbnail paths of the selected Presentation source's library presentation (index = slide). */
    private List<String> slideThumbs = List.of();
    private final JComboBox<FitMode> fitCombo = new JComboBox<>(FitMode.values());
    private final JLabel fileLabel = Ui.small("—");
    private final JButton fileButton = Ui.button("Choose…", "folder");
    private final JTextField textField = new JTextField();
    private final ColorButton colorButton = new ColorButton("Text color", Color.WHITE);
    private final JSpinner fontSpin = new JSpinner(new SpinnerNumberModel(48, 8, 300, 2));
    private final JComboBox<String> cameraCombo = new JComboBox<>();
    private final JSlider opacity = new JSlider(0, 100, 100);
    private final JComboBox<String> textPlace = new JComboBox<>(new String[]{"As the theme", "Top", "Middle", "Bottom"});
    private final JSlider textScale = new JSlider(30, 150, 100);
    private final JPanel props = Ui.transparent(new GridBagLayout());
    private boolean loadingProps;

    private Long selectAfterRefresh;

    private static final int W = 1920;
    private static final int H = 1080;

    public ScenesPanel(SceneService scenes, ProgramOutput program, CameraManager cameras, StyleForm.ImageImporter importer,
                       PresentationService presentations, MediaService media) {
        super(new BorderLayout());
        this.scenes = scenes;
        this.program = program;
        this.cameras = cameras;
        this.importer = importer;
        this.presentations = presentations;
        this.media = media;
        setOpaque(false);

        saveTimer = new Timer(500, e -> saveEditing());
        saveTimer.setRepeats(false);

        JSplitPane right = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildPreview(), buildSources());
        right.setResizeWeight(1);
        right.setBorder(null);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSceneList(), right);
        split.setDividerLocation(230);
        split.setBorder(null);
        add(split);

        program.addListener(preview::repaint);
        MediaFrames.addListener(preview::repaint);
        Rendering.renderer().images().addChangeListener(preview::repaint);
        wireProps();
    }

    public void setScenesChanged(Consumer<List<Scene>> l) {
        scenesChanged = l;
    }

    public void setLiveSceneChosen(Consumer<Scene> l) {
        liveSceneChosen = l;
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    /** Opens the editor on one scene ("Edit Layout" from the Scenes dock). */
    public void edit(long sceneId) {
        selectAfterRefresh = sceneId;
        refresh();
    }

    public void refresh() {
        Long keep = selectAfterRefresh != null ? selectAfterRefresh : editing == null ? null : editing.getId();
        selectAfterRefresh = null;
        BackgroundTask.run("Load scenes", scenes::scenes, list -> {
            sceneModel.clear();
            sceneModel.addAll(list);
            int idx = 0;
            for (int i = 0; i < list.size(); i++) {
                if (Objects.equals(list.get(i).getId(), keep)) {
                    idx = i;
                }
            }
            if (!list.isEmpty()) {
                sceneList.setSelectedIndex(idx);
            }
            scenesChanged.accept(List.copyOf(list));
        }, err -> Dialogs.showError(this, "Could not load scenes.", err));
    }

    // ------------------------------------------------------------------ layout

    private JComponent buildSceneList() {
        JPanel p = Ui.panel(new BorderLayout(), Palette::panelBackground);
        JButton add = Ui.toolButton("plus", "New scene");
        add.addActionListener(e -> createScene());
        p.add(Ui.header(Ui.label("Scenes", Palette::text, "h3"), Ui.small("Layouts for Program"), add), BorderLayout.NORTH);
        sceneList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sceneList.setFixedCellHeight(34);
        sceneList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(l, v, i, sel, false);
                Scene s = (Scene) v;
                boolean live = program.scene() != null && Objects.equals(program.scene().getId(), s.getId());
                lbl.setText(s.getName() + (live ? "   ● LIVE" : ""));
                lbl.setIcon(Icons.get("grid", Icons.SMALL, () -> sel ? Color.WHITE : (live ? Palette.live() : Palette.secondaryText())));
                lbl.setIconTextGap(8);
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
                return lbl;
            }
        });
        sceneList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                saveEditing();
                Scene s = sceneList.getSelectedValue();
                editing = s == null ? null : s.copy();
                showEditing();
            }
        });
        program.addListener(sceneList::repaint);
        JScrollPane sp = new JScrollPane(sceneList);
        sp.setBorder(Ui.line(true, false, false, false));
        p.add(sp, BorderLayout.CENTER);

        JButton rename = Ui.toolButton("edit", "Rename");
        rename.addActionListener(e -> renameScene());
        JButton dup = Ui.toolButton("copy", "Duplicate");
        dup.addActionListener(e -> duplicateScene());
        JButton del = Ui.toolButton("trash", "Delete");
        del.addActionListener(e -> deleteScene());
        JButton up = Ui.toolButton("arrow-up", "Move up");
        up.addActionListener(e -> moveScene(-1));
        JButton down = Ui.toolButton("arrow-down", "Move down");
        down.addActionListener(e -> moveScene(1));
        JPanel bar = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 4));
        bar.add(rename);
        bar.add(dup);
        bar.add(del);
        bar.add(up);
        bar.add(down);
        bar.setBorder(Ui.line(true, false, false, false));
        p.add(bar, BorderLayout.SOUTH);
        p.setMinimumSize(new Dimension(180, 0));
        return p;
    }

    private JComponent buildPreview() {
        JPanel p = Ui.panel(new BorderLayout(0, 10), Palette::panelBackground);
        p.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        liveButton.addActionListener(e -> {
            if (editing != null) {
                saveEditing();
                liveSceneChosen.accept(editing.copy());
                sceneList.repaint();
            }
        });
        JPanel head = Ui.transparent(new BorderLayout());
        head.add(previewTitle, BorderLayout.WEST);
        head.add(liveButton, BorderLayout.EAST);
        p.add(head, BorderLayout.NORTH);
        p.add(preview, BorderLayout.CENTER);
        p.add(Ui.label("<html>The preview uses what is currently on Program. Layers are drawn from the bottom of the "
                + "Sources list upwards. Edits to the live scene appear on the projector immediately.</html>",
                Palette::mutedText, "small"), BorderLayout.SOUTH);
        p.setMinimumSize(new Dimension(300, 0));
        return p;
    }

    private JComponent buildSources() {
        JPanel p = Ui.panel(new BorderLayout(), Palette::panelBackground);
        p.setPreferredSize(new Dimension(340, 10));
        JButton add = Ui.primaryButton("ADD SOURCE", "plus");
        add.setToolTipText("Add content to this scene: from the Library, from the Service, or text, logo, lower third");
        add.addActionListener(e -> showAddSource(add));
        p.add(Ui.header(Ui.label("Sources", Palette::text, "h4"), Ui.small("Top layer first"), add), BorderLayout.NORTH);

        sourceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sourceList.setCellRenderer(new SourceRenderer());
        sourceList.setFixedCellHeight(32);
        sourceList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && sourceList.getSelectedValue() != null) {
                    nameField.requestFocusInWindow(); // double-click: edit its properties
                }
            }

            private void popup(MouseEvent e) {
                int i = sourceList.locationToIndex(e.getPoint());
                if (e.isPopupTrigger() && i >= 0) {
                    sourceList.setSelectedIndex(i);
                    showLayoutMenu(sourceList, e.getX(), e.getY());
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                int i = sourceList.locationToIndex(e.getPoint());
                if (e.isPopupTrigger()) {
                    popup(e);
                    return;
                }
                if (i >= 0 && e.getX() < 34) { // checkbox area toggles visibility
                    Source s = sourceModel.get(i);
                    s.setVisible(!s.isVisible());
                    sourceList.repaint();
                    if (s == sourceList.getSelectedValue()) {
                        loadProps(); // keep the Visible box in step
                    }
                    sceneEdited();
                }
            }
        });
        sourceList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadProps();
                preview.repaint(); // handles of the selected layer
            }
        });
        JScrollPane sp = new JScrollPane(sourceList);
        sp.setPreferredSize(new Dimension(300, 190));
        JButton remove = Ui.toolButton("trash", "Remove source");
        remove.addActionListener(e -> removeSource());
        JButton up = Ui.toolButton("arrow-up", "Move layer up");
        up.addActionListener(e -> moveSource(-1));
        JButton down = Ui.toolButton("arrow-down", "Move layer down");
        down.addActionListener(e -> moveSource(1));
        JPanel bar = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        bar.add(up);
        bar.add(down);
        bar.add(remove);
        JPanel top = Ui.transparent(new BorderLayout());
        top.add(sp, BorderLayout.CENTER);
        top.add(bar, BorderLayout.SOUTH);
        top.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        props.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane propsScroll = new JScrollPane(new WidthTracking(props)); // fields wrap to the column, never cut off
        propsScroll.setBorder(Ui.line(true, false, false, false));
        propsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel center = Ui.transparent(new BorderLayout());
        center.add(top, BorderLayout.NORTH);
        center.add(propsScroll, BorderLayout.CENTER);
        p.add(center, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ editing

    private void showEditing() {
        sourceModel.clear();
        if (editing != null) {
            sourceModel.addAll(editing.getSources());
            previewTitle.setText(editing.getName());
            if (!sourceModel.isEmpty()) {
                sourceList.setSelectedIndex(0);
            }
        } else {
            previewTitle.setText(" ");
        }
        loadProps();
        preview.repaint();
    }

    /** Called after any change to the edited scene. */
    private void sceneEdited() {
        if (editing == null) {
            return;
        }
        List<Source> list = new ArrayList<>();
        for (int i = 0; i < sourceModel.size(); i++) {
            list.add(sourceModel.get(i));
        }
        editing.setSources(list);
        preview.repaint();
        if (program.scene() != null && Objects.equals(program.scene().getId(), editing.getId())) {
            program.setScene(editing, false); // live scene: apply instantly
        }
        saveTimer.restart();
    }

    private void saveEditing() {
        saveTimer.stop();
        if (editing == null || editing.getId() == null) {
            return;
        }
        Scene snapshot = editing.copy();
        // a scene deleted meanwhile (Scenes list, right-click → Delete) is not saved back
        BackgroundTask.run("Save scene", () -> scenes.scene(snapshot.getId()).isPresent() ? scenes.save(snapshot) : null, saved -> {
            if (saved == null) {
                if (editing != null && Objects.equals(editing.getId(), snapshot.getId())) {
                    editing = null;
                }
                return;
            }
            for (int i = 0; i < sceneModel.size(); i++) {
                if (Objects.equals(sceneModel.get(i).getId(), saved.getId())) {
                    sceneModel.set(i, saved);
                }
            }
            List<Scene> all = new ArrayList<>();
            for (int i = 0; i < sceneModel.size(); i++) {
                all.add(sceneModel.get(i));
            }
            scenesChanged.accept(all);
        }, err -> Dialogs.showError(this, "The scene could not be saved.", err));
    }

    private void createScene() {
        Object name = JOptionPane.showInputDialog(this, "Scene name:", "New Scene", JOptionPane.PLAIN_MESSAGE, null, null, "New Scene");
        if (name == null || name.toString().isBlank()) {
            return;
        }
        BackgroundTask.run("Create scene", () -> scenes.create(name.toString()), s -> {
            editing = s.copy();
            refresh();
            statusHandler.accept("Created scene \"" + s.getName() + "\"");
        }, err -> Dialogs.showError(this, "Could not create the scene.", err));
    }

    private void renameScene() {
        if (editing == null) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Scene name:", "Rename Scene", JOptionPane.PLAIN_MESSAGE, null, null,
                editing.getName());
        if (name != null && !name.toString().isBlank()) {
            editing.setName(name.toString().strip());
            previewTitle.setText(editing.getName());
            saveEditing();
        }
    }

    private void duplicateScene() {
        if (editing == null) {
            return;
        }
        saveEditing();
        long id = editing.getId();
        BackgroundTask.run("Duplicate scene", () -> scenes.duplicate(id), s -> {
            editing = s.copy();
            refresh();
        }, err -> Dialogs.showError(this, "Could not duplicate the scene.", err));
    }

    private void deleteScene() {
        if (editing == null) {
            return;
        }
        if (program.scene() != null && Objects.equals(program.scene().getId(), editing.getId())) {
            Dialogs.showInfo(this, "Scene is live", "Switch Program to another scene before deleting this one.");
            return;
        }
        if (!Dialogs.confirmDestructive(this, "Delete scene", "Delete the scene \"" + editing.getName() + "\"?", "Delete")) {
            return;
        }
        long id = editing.getId();
        editing = null;
        BackgroundTask.run("Delete scene", () -> {
            scenes.delete(id);
            return true;
        }, ok -> refresh(), err -> Dialogs.showError(this, "Could not delete the scene.", err));
    }

    private void moveScene(int delta) {
        int i = sceneList.getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= sceneModel.size()) {
            return;
        }
        Scene s = sceneModel.remove(i);
        sceneModel.add(j, s);
        sceneList.setSelectedIndex(j);
        List<Long> ids = new ArrayList<>();
        List<Scene> all = new ArrayList<>();
        for (int k = 0; k < sceneModel.size(); k++) {
            ids.add(sceneModel.get(k).getId());
            all.add(sceneModel.get(k));
        }
        BackgroundTask.run("Reorder scenes", () -> {
            scenes.reorder(ids);
            return true;
        }, ok -> scenesChanged.accept(all), err -> Dialogs.showError(this, "Could not reorder scenes.", err));
    }

    private void showAddSource(Component anchor) {
        if (editing == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        section(menu, "FROM LIBRARY");
        menu.add(menuItem("Lyrics…", "music", this::addSongFromLibrary));
        menu.add(menuItem("Bible (dynamic)", "book", () -> addNamed(SourceType.BIBLE, "Bible (dynamic)")));
        menu.add(menuItem("Presentations…", "presentation", this::addPresentationFromLibrary));
        menu.add(menuItem("Images…", "image", this::addImages));
        menu.add(menuItem("Videos…", "video", () -> addMediaFromLibrary(MediaKind.VIDEO)));
        menu.add(menuItem("Audio…", "audio", () -> addMediaFromLibrary(MediaKind.AUDIO)));
        menu.add(menuItem("Media", "layers", () -> addSource(SourceType.BACKGROUND)));
        menu.add(menuItem("Camera…", "video", this::addCameraSource));
        section(menu, "FROM SERVICE");
        JMenuItem service = menuItem("Service Item…", "list", this::addFromService);
        List<com.jamc.worship.model.ServiceItem> items = serviceItems.get();
        service.setEnabled(items != null && !items.isEmpty());
        if (!service.isEnabled()) {
            service.setToolTipText("The open service schedule is empty");
        }
        menu.add(service);
        section(menu, "OTHER");
        menu.add(menuItem("Text", "edit", () -> addSource(SourceType.TEXT)));
        menu.add(menuItem("Logo", "image", () -> addSource(SourceType.CHURCH_LOGO)));
        menu.add(menuItem("Lower Third", "lower-third", () -> addSource(SourceType.LOWER_THIRD)));
        menu.add(menuItem("Color / Shape", "palette", () -> addSource(SourceType.COLOR)));
        menu.add(menuItem("Clock", "calendar", () -> addSource(SourceType.CLOCK)));
        menu.add(menuItem("Readability Overlay", "layers", () -> addSource(SourceType.OVERLAY)));
        section(menu, "ADVANCED");
        JMenuItem web = menuItem("Browser / Web Source — future", "monitor", () -> { });
        web.setEnabled(false);
        JMenuItem screen = menuItem("Screen Capture — future", "monitor", () -> { });
        screen.setEnabled(false);
        menu.add(web);
        menu.add(screen);
        menu.show(anchor, 0, anchor.getHeight());
    }

    // ------------------------------------------------------------------ library and service sources

    private com.jamc.worship.service.SongService songs;
    private java.util.function.Supplier<List<com.jamc.worship.model.ServiceItem>> serviceItems = List::of;
    private Consumer<Long> openSong = id -> { };

    /** The running slideshows (playback buttons and status of IMAGE SLIDESHOW sources). */
    public void setSlideshows(com.jamc.worship.output.Slideshows slideshows) {
        this.slideshows = slideshows;
    }

    /** ADD SOURCE → Images: two clear choices, SINGLE IMAGE or IMAGE SLIDESHOW. */
    private void addImages() {
        if (editing == null) {
            return;
        }
        Object[] options = {"SINGLE IMAGE", "IMAGE SLIDESHOW", "CANCEL"};
        JPanel ask = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 8));
        ask.add(Ui.label("How do you want to use the images?", Palette::text, "semibold"));
        JLabel single = Ui.label("SINGLE IMAGE: one image from the library as a scene source.", Palette::secondaryText, null);
        single.setIcon(Icons.get("image", Icons.MEDIUM, Palette::secondaryText));
        JLabel show = Ui.label("IMAGE SLIDESHOW: several images that play one after another, by themselves or with "
                + "Previous / Next.", Palette::secondaryText, null);
        show.setIcon(Icons.get("slides", Icons.MEDIUM, Palette::secondaryText));
        ask.add(single);
        ask.add(show);
        int choice = javax.swing.JOptionPane.showOptionDialog(this, ask, "Add Image Source",
                javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            addMediaFromLibrary(MediaKind.IMAGE);
        } else if (choice == 1) {
            addSlideshow();
        }
    }

    /** IMAGE SLIDESHOW: pick several library images, order them, set how they play; one source. */
    private void addSlideshow() {
        Source s = new Source("Image Slideshow", SourceType.SLIDESHOW);
        s.getConfig().uid = java.util.UUID.randomUUID().toString();
        SourceConfig c = s.getConfig();
        c.fit = FitMode.FIT;
        String name = com.jamc.worship.ui.dialogs.SlideshowEditorDialog.edit(this, media, "Create Image Slideshow",
                "Image Slideshow", c, "CREATE SOURCE");
        if (name != null) {
            s.setName(name);
            insertSource(s);
        }
    }

    /** EDIT IMAGES on a slideshow source: add, remove, reorder, replace; the source stays in the scene. */
    private void editSlideshow(Source s) {
        SourceConfig work = s.getConfig().copy();
        String name = com.jamc.worship.ui.dialogs.SlideshowEditorDialog.edit(this, media, "Slideshow Images",
                s.getName(), work, "SAVE");
        if (name != null) {
            s.setName(name);
            SourceConfig c = s.getConfig();
            c.imageIds = work.imageIds;
            c.slideSeconds = work.slideSeconds;
            c.slideTransition = work.slideTransition;
            c.slideTransitionMs = work.slideTransitionMs;
            c.autoplay = work.autoplay;
            c.loop = work.loop;
            c.shuffle = work.shuffle;
            c.restartOnProgram = work.restartOnProgram;
            sourceList.repaint();
            loadProps();
            sceneEdited();
        }
    }

    private void slideshowControl(Source s, com.jamc.worship.output.Slideshows.Control control) {
        if (slideshows != null) {
            slideshows.control(s, control);
            updateSlideshowStatus();
        }
    }

    private void updateSlideshowStatus() {
        Source s = sourceList.getSelectedValue();
        if (s == null || s.getType() != SourceType.SLIDESHOW || slideshows == null) {
            slideshowTimer.stop();
            return;
        }
        var st = slideshows.status(s);
        int count = s.getConfig().imageIds == null ? 0 : s.getConfig().imageIds.size();
        if (st == null) {
            slideshowStatus.setText("<html><center>" + count + (count == 1 ? " image" : " images")
                    + "<br>not in Preview</center></html>");
        } else {
            // the big number is Preview (what NEXT / PREVIOUS move); Program below it
            slideshowStatus.setText("<html><center><b style='font-size:13px'>"
                    + (st.previewPosition() > 0 ? st.previewPosition() + " / " + st.count() : "–") + "</b><br>"
                    + "<span style='font-size:9px'>PREVIEW"
                    + (st.programPosition() > 0 ? " · PROGRAM " + st.programPosition() + " / " + st.count() : "")
                    + (st.playing() && st.nextInMs() >= 0 ? " · next in " + String.format("%.1f s", st.nextInMs() / 1000.0) : "")
                    + "</span></center></html>");
        }
    }

    /** Lets Songs / Lyrics… pick from the song library. */
    public void setSongs(com.jamc.worship.service.SongService songs) {
        this.songs = songs;
    }

    /** The open service schedule, for FROM SERVICE. */
    public void setServiceItems(java.util.function.Supplier<List<com.jamc.worship.model.ServiceItem>> items) {
        this.serviceItems = items;
    }

    /** OPEN SONG on a lyrics source: shows that song's slides in the Library. */
    public void setOpenSong(Consumer<Long> handler) {
        this.openSong = handler;
    }

    private void addNamed(SourceType type, String name) {
        addSource(type);
        Source s = sourceList.getSelectedValue();
        if (s != null) {
            s.setName(name);
            sourceList.repaint();
            loadProps();
            sceneEdited();
        }
    }

    /**
     * Songs / Lyrics: a lyrics source that refers to a library song (nothing is copied). The lyrics
     * stay dynamic: whatever song slide is sent shows here, so one scene serves every song.
     */
    private void addSongFromLibrary() {
        if (editing == null || songs == null) {
            return;
        }
        com.jamc.worship.model.Song song = LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<com.jamc.worship.model.Song>(
                "Select Lyrics", "Search lyrics…", "music", "No lyrics in the library yet.",
                text -> songs.search(new com.jamc.worship.model.SongQuery(text, false, com.jamc.worship.model.SongQuery.Sort.TITLE_ASC)),
                com.jamc.worship.model.Song::getTitle,
                s -> s.getAuthor() == null || s.getAuthor().isBlank() ? "Song" : s.getAuthor(), s -> null));
        if (song == null) {
            return;
        }
        Source s = new Source("Lyrics: " + song.getTitle(), SourceType.LYRICS);
        s.getConfig().songId = song.getId();
        insertSource(s);
        statusHandler.accept("Lyrics source added for \"" + song.getTitle() + "\" (from the library, not copied).");
    }

    /** Camera: pick one of the connected devices. */
    private void addCameraSource() {
        if (editing == null) {
            return;
        }
        List<String> devices = cameras.cameras();
        String cam = LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<String>(
                "Select Camera", "Search cameras…", "video",
                "No camera detected.<br>Connect a webcam or HDMI capture device, then open Library → Camera to detect it.",
                text -> devices.stream().filter(d -> d.toLowerCase().contains(text.strip().toLowerCase())).toList(),
                d -> d, d -> "Camera / capture device", d -> null));
        if (cam == null) {
            return;
        }
        Source s = new Source(cam, SourceType.CAMERA);
        s.getConfig().camera = cam;
        s.getConfig().fit = FitMode.FILL;
        insertSource(s);
    }

    /**
     * FROM SERVICE: a source that refers to the content of a service item (song, presentation, picture,
     * video, audio, Bible or text). Nothing is imported again.
     */
    private void addFromService() {
        if (editing == null) {
            return;
        }
        List<com.jamc.worship.model.ServiceItem> items = serviceItems.get();
        com.jamc.worship.model.ServiceItem item = LibraryPickerDialog.pick(this,
                new LibraryPickerDialog.Spec<com.jamc.worship.model.ServiceItem>(
                        "Select Service Item", "Search the service…", "list", "The service schedule is empty.",
                        text -> items.stream().filter(i -> i.getTitle() != null
                                && i.getTitle().toLowerCase().contains(text.strip().toLowerCase())).toList(),
                        com.jamc.worship.model.ServiceItem::getTitle, i -> i.getType().displayName(), i -> null));
        if (item == null) {
            return;
        }
        Long ref = item.getRefId();
        Source s;
        switch (item.getType()) {
            case SONG -> {
                s = new Source("Lyrics: " + item.getTitle(), SourceType.LYRICS);
                s.getConfig().songId = ref;
            }
            case BIBLE -> s = new Source("Bible: " + item.getTitle(), SourceType.BIBLE);
            case PRESENTATION -> {
                s = new Source("Presentation: " + item.getTitle(), SourceType.PRESENTATION);
                s.getConfig().presentationId = ref;
                s.getConfig().slideIndex = 0;
                s.getConfig().fit = FitMode.FIT;
                s.getConfig().presentationMode = SourceConfig.PresentationMode.FIXED;
            }
            case IMAGE, VIDEO, AUDIO -> {
                MediaItem m = ref == null ? null : media.load(ref).orElse(null);
                if (m == null) {
                    Dialogs.showInfo(this, "Add Source", "\"" + item.getTitle() + "\" is no longer in the media library.");
                    return;
                }
                s = mediaSource(m, item.getType() == com.jamc.worship.model.ServiceItem.Type.VIDEO ? SourceType.VIDEO
                        : item.getType() == com.jamc.worship.model.ServiceItem.Type.AUDIO ? SourceType.AUDIO : SourceType.IMAGE);
            }
            case LOWER_THIRD -> s = new Source(item.getTitle(), SourceType.LOWER_THIRD);
            default -> {
                s = new Source(item.getTitle(), SourceType.TEXT);
                String text = item.getPayload() == null || item.getPayload().isBlank() ? item.getTitle() : item.getPayload();
                s.getConfig().text = text.length() > 120 ? text.substring(0, 120) : text;
                s.getConfig().y = 0.8;
                s.getConfig().h = 0.15;
                s.getConfig().fontSize = 48;
            }
        }
        s.getConfig().serviceItemId = item.getId();
        insertSource(s);
        statusHandler.accept("\"" + item.getTitle() + "\" from the service added to the scene.");
    }

    /** A source that refers to a media library item and its stored file (no copy). */
    private static Source mediaSource(MediaItem m, SourceType type) {
        Source s = new Source(m.getName(), type);
        SourceConfig c = s.getConfig();
        c.fit = FitMode.FIT;
        c.mediaId = m.getId();
        c.path = m.getPath();
        if (type == SourceType.VIDEO || type == SourceType.AUDIO) {
            c.loop = false; // plays once, with its sound
        }
        if (type == SourceType.AUDIO) {
            c.x = 0.02;
            c.y = 0.02;
            c.w = 0.3;
            c.h = 0.08; // only a label in the editor; nothing is drawn on the outputs
        }
        return s;
    }

    private static void section(JPopupMenu menu, String title) {
        if (menu.getComponentCount() > 0) {
            menu.addSeparator();
        }
        JLabel l = Ui.label(title, Palette::mutedText, "small");
        l.setBorder(BorderFactory.createEmptyBorder(4, 10, 2, 10));
        menu.add(l);
    }

    /** A source's file name for labels; a YouTube video has no file, only its reference. */
    private static String fileName(String path) {
        if (com.jamc.worship.youtube.YouTubeRef.is(path)) {
            return "YouTube video (" + com.jamc.worship.youtube.YouTubeRef.id(path) + ")";
        }
        return java.nio.file.Path.of(path).getFileName().toString();
    }

    private static JMenuItem menuItem(String text, String icon, Runnable action) {
        JMenuItem item = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        item.addActionListener(e -> action.run());
        return item;
    }

    /** The library's presentations, newest first; nothing is imported here. */
    private Presentation pickPresentation() {
        return LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<Presentation>(
                "Select Presentation", "Search presentations…", "presentation",
                "No presentations in the library yet.<br>Import one in Library → Presentations first.",
                presentations::list, Presentation::name, p -> p.slideCountText() + "  ·  " + p.sourceFileName(),
                p -> Rendering.thumbnails().get(presentations.thumbnail(p, 1).toString())));
    }

    /** + ADD SOURCE → Presentation from Library: references the library presentation by id. */
    private void addPresentationFromLibrary() {
        if (editing == null) {
            return;
        }
        Presentation p = pickPresentation();
        if (p == null) {
            return;
        }
        Source s = new Source("Presentation: " + p.name(), SourceType.PRESENTATION);
        SourceConfig c = s.getConfig();
        c.fit = FitMode.FIT;
        c.presentationId = p.id();
        c.slideIndex = 0;
        c.presentationMode = SourceConfig.PresentationMode.DYNAMIC;
        insertSource(s);
        statusHandler.accept("\"" + p.name() + "\" added to the scene from the library (not imported again).");
    }

    /** + ADD SOURCE → Image / Video from Library: references the library item and its stored file. */
    private void addMediaFromLibrary(MediaKind kind) {
        if (editing == null) {
            return;
        }
        MediaItem m = pickMedia(kind);
        if (m == null) {
            return;
        }
        insertSource(mediaSource(m, kind == MediaKind.VIDEO ? SourceType.VIDEO
                : kind == MediaKind.AUDIO ? SourceType.AUDIO : SourceType.IMAGE));
    }

    private MediaItem pickMedia(MediaKind kind) {
        String what = switch (kind) {
            case VIDEO -> "Videos";
            case AUDIO -> "Audio";
            default -> "Images";
        };
        List<MediaCategory> cats = switch (kind) {
            case VIDEO -> List.of(MediaCategory.VIDEO, MediaCategory.BACKGROUND);
            case AUDIO -> List.of(MediaCategory.AUDIO);
            default -> List.of(MediaCategory.IMAGE, MediaCategory.BACKGROUND);
        };
        return LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<MediaItem>(
                "Select " + (kind == MediaKind.AUDIO ? "Audio" : kind == MediaKind.VIDEO ? "Video" : "Image"),
                "Search " + what.toLowerCase() + "…", kind == MediaKind.VIDEO ? "video" : kind == MediaKind.AUDIO ? "audio" : "image",
                "Nothing in the library yet.<br>Import files in Library → " + what + " first.",
                text -> media.find(cats, text, false).stream().filter(m -> m.getKind() == kind).toList(),
                MediaItem::getName,
                m -> (m.isYouTube() ? "YouTube video" : (m.getCategory() == MediaCategory.BACKGROUND ? "Background " : "") + m.getKind().name().toLowerCase())
                        + (m.getWidth() == null ? "" : "  ·  " + m.getWidth() + "×" + m.getHeight())
                        + (m.getDurationMs() == null ? "" : "  ·  " + TransportControls.format(m.getDurationMs())),
                m -> kind == MediaKind.IMAGE ? Rendering.renderer().images().get(m.getPath())
                        : m.isYouTube() ? com.jamc.worship.youtube.YouTubeLibrary.thumbnail(m.getYoutubeVideoId(), m.getThumbnailUrl()) : null));
    }

    private void insertSource(Source s) {
        sourceModel.add(0, s);
        sourceList.setSelectedIndex(0);
        sceneEdited();
    }

    private void addSource(SourceType type) {
        Source s = new Source(type.toString(), type);
        SourceConfig c = s.getConfig();
        switch (type) {
            case CHURCH_LOGO -> {
                c.x = 0.86;
                c.y = 0.05;
                c.w = 0.1;
                c.h = 0.12;
                c.fit = FitMode.FIT;
            }
            case CLOCK -> {
                c.x = 0.78;
                c.y = 0.04;
                c.w = 0.18;
                c.h = 0.08;
                c.fontSize = 48;
            }
            case TEXT -> {
                c.y = 0.85;
                c.h = 0.1;
                c.text = "Welcome!";
                c.fontSize = 56;
            }
            case IMAGE, VIDEO -> c.fit = FitMode.FIT;
            case COLOR -> {
                c.y = 0.85;
                c.h = 0.15;
                c.color = "#0B1220";
                c.opacity = 0.8;
            }
            default -> {
            }
        }
        insertSource(s);
        if (type == SourceType.IMAGE || type == SourceType.CHURCH_LOGO || type == SourceType.VIDEO) {
            chooseFile();
        }
    }

    private void removeSource() {
        int i = sourceList.getSelectedIndex();
        if (i < 0) {
            return;
        }
        sourceModel.remove(i);
        if (!sourceModel.isEmpty()) {
            sourceList.setSelectedIndex(Math.min(i, sourceModel.size() - 1));
        }
        sceneEdited();
    }

    private void moveSource(int delta) {
        int i = sourceList.getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= sourceModel.size()) {
            return;
        }
        Source s = sourceModel.remove(i);
        sourceModel.add(j, s);
        sourceList.setSelectedIndex(j);
        sceneEdited();
    }

    // ------------------------------------------------------------------ source properties

    private void wireProps() {
        Runnable apply = this::applyProps;
        nameField.getDocument().addDocumentListener(doc(apply));
        textField.getDocument().addDocumentListener(doc(apply));
        for (JSpinner s : List.of(xSpin, ySpin, wSpin, hSpin, fontSpin)) {
            s.addChangeListener(e -> apply.run());
        }
        fitCombo.addActionListener(e -> apply.run());
        cameraCombo.addActionListener(e -> apply.run());
        colorButton.addColorListener(c -> apply.run());
        opacity.addChangeListener(e -> apply.run());
        textPlace.addActionListener(e -> apply.run());
        textScale.addChangeListener(e -> apply.run());
        fileButton.addActionListener(e -> chooseFile());
        visibleBox.addActionListener(e -> apply.run());
        keepAspect.addActionListener(e -> apply.run());
        modeCombo.addActionListener(e -> apply.run());
        loopBox.addActionListener(e -> apply.run());
        for (JCheckBox b : List.of(videoBox, audioBox, autoplayBox)) {
            b.addActionListener(e -> apply.run());
        }
        audioOut.addActionListener(e -> apply.run());
        for (var b : List.of(slideAuto, slideShuffle, slideLoop)) {
            b.addActionListener(e -> apply.run());
        }
        slideSeconds.addChangeListener(e -> apply.run());
        slideMs.addChangeListener(e -> apply.run());
        for (var box : List.of(slideTransition, slideOnProgram, slideBackground)) {
            box.addActionListener(e -> apply.run());
        }
        takeCombo.addActionListener(e -> apply.run());
        srcVolume.addChangeListener(e -> {
            srcVolumeLabel.setText(srcVolume.getValue() + "%");
            if (!srcVolume.getValueIsAdjusting()) apply.run();
        });
        srcMute.addActionListener(e -> {
            apply.run();
        });
        selectFromLibrary.addActionListener(e -> selectFromLibrary());
        prevSlide.addActionListener(e -> stepSlide(-1));
        nextSlide.addActionListener(e -> stepSlide(1));
        fitToCanvas.setToolTipText("Fill the whole 16:9 output (X 0, Y 0, 1920 × 1080)");
        fitToCanvas.addActionListener(e -> setBox(0, 0, 1, 1));
        resetPosition.setToolTipText("Back to where this source was when you selected it");
        resetPosition.addActionListener(e -> {
            Source s = sourceList.getSelectedValue();
            double[] b = s == null ? null : resetBoxes.get(s);
            if (b != null) {
                setBox(b[0], b[1], b[2], b[3]);
            }
        });
        slideStrip.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        slideStrip.setVisibleRowCount(1);
        slideStrip.setFixedCellWidth(96);
        slideStrip.setFixedCellHeight(64);
        slideStrip.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        slideStrip.setCellRenderer(new SlideThumbRenderer());
        slideStrip.addListSelectionListener(e -> {
            Source s = sourceList.getSelectedValue();
            int i = slideStrip.getSelectedIndex();
            if (!e.getValueIsAdjusting() && !loadingProps && s != null && i >= 0) {
                s.getConfig().slideIndex = i;
                updateSlideLabel(s);
                sceneEdited();
            }
        });
        Rendering.thumbnails().addChangeListener(slideStrip::repaint);
    }

    /** Layout of each source when it was selected, for RESET POSITION. */
    private final java.util.Map<Source, double[]> resetBoxes = new java.util.IdentityHashMap<>();

    private void setBox(double x, double y, double w, double h) {
        Source s = sourceList.getSelectedValue();
        if (s == null) {
            return;
        }
        SourceConfig c = s.getConfig();
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        loadProps();
        sceneEdited();
    }

    /** Right-click on a source (in the list or on the canvas): visibility, layout, order, lock, copy, remove. */
    private void showLayoutMenu(Component c, int x, int y) {
        Source s = sourceList.getSelectedValue();
        if (s == null) {
            return;
        }
        SourceConfig cfg = s.getConfig();
        boolean locked = Boolean.TRUE.equals(cfg.locked);
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem(s.isVisible() ? "Hide" : "Show", "layers", () -> {
            s.setVisible(!s.isVisible());
            sourceList.repaint();
            loadProps();
            sceneEdited();
        }));
        menu.add(menuItem("Edit Properties", "sliders", () -> nameField.requestFocusInWindow()));
        if (s.getType() == SourceType.SLIDESHOW) {
            menu.addSeparator();
            menu.add(menuItem("Play", "play", () -> slideshowControl(s, com.jamc.worship.output.Slideshows.Control.PLAY)));
            menu.add(menuItem("Pause", "pause", () -> slideshowControl(s, com.jamc.worship.output.Slideshows.Control.PAUSE)));
            menu.add(menuItem("Restart", "refresh", () -> slideshowControl(s, com.jamc.worship.output.Slideshows.Control.RESTART)));
            menu.add(menuItem("Previous", "chevron-left", () -> slideshowControl(s, com.jamc.worship.output.Slideshows.Control.PREVIOUS)));
            menu.add(menuItem("Next", "chevron-right", () -> slideshowControl(s, com.jamc.worship.output.Slideshows.Control.NEXT)));
            menu.add(menuItem("Edit Images…", "image", () -> editSlideshow(s)));
        }
        menu.addSeparator();
        JMenuItem fill = menuItem("Fit to Canvas", "monitor", () -> setBox(0, 0, 1, 1));
        JMenuItem center = menuItem("Center", "grid", () -> setBox((1 - cfg.w) / 2, (1 - cfg.h) / 2, cfg.w, cfg.h));
        JMenuItem pip = menuItem("Bottom-right corner (picture in picture)", "video", () -> setBox(0.68, 0.64, 0.28, 0.28));
        JMenuItem reset = menuItem("Reset Position", "refresh", () -> resetPosition.doClick());
        for (JMenuItem i : List.of(fill, center, pip, reset)) {
            i.setEnabled(!locked);
            menu.add(i);
        }
        menu.addSeparator();
        menu.add(menuItem("Move Up", "arrow-up", () -> moveSource(-1)));
        menu.add(menuItem("Move Down", "arrow-down", () -> moveSource(1)));
        menu.add(menuItem(locked ? "Unlock" : "Lock", "edit", () -> {
            cfg.locked = locked ? null : Boolean.TRUE;
            sourceList.repaint();
            sceneEdited();
        }));
        menu.add(menuItem("Duplicate", "copy", () -> {
            Source copy = s.copy();
            copy.setId(null); // a new layer of its own
            copy.setName(s.getName() + " (copy)");
            if (copy.getConfig().uid != null) {
                copy.getConfig().uid = java.util.UUID.randomUUID().toString(); // its own playback, not the original's
            }
            sourceModel.add(Math.max(0, sourceList.getSelectedIndex()), copy);
            sourceList.setSelectedValue(copy, true);
            sceneEdited();
        }));
        menu.addSeparator();
        menu.add(menuItem("Remove", "trash", this::removeSource));
        menu.show(c, x, y);
    }

    private void selectFromLibrary() {
        Source s = sourceList.getSelectedValue();
        if (s == null) {
            return;
        }
        SourceConfig c = s.getConfig();
        if (s.getType() == SourceType.PRESENTATION) {
            Presentation p = pickPresentation();
            if (p == null) {
                return;
            }
            c.presentationId = p.id();
            c.slideIndex = 0;
            if (s.getName().startsWith("Presentation")) {
                s.setName("Presentation: " + p.name());
            }
        } else {
            MediaItem m = pickMedia(s.getType() == SourceType.VIDEO ? MediaKind.VIDEO : s.getType() == SourceType.AUDIO ? MediaKind.AUDIO : MediaKind.IMAGE);
            if (m == null) {
                return;
            }
            c.mediaId = m.getId();
            c.path = m.getPath();
        }
        sourceList.repaint();
        loadProps();
        sceneEdited();
    }

    private void stepSlide(int delta) {
        Source s = sourceList.getSelectedValue();
        if (s == null || s.getConfig().presentationId == null) {
            return;
        }
        int count = Rendering.presentations().slideCount(s.getConfig().presentationId);
        int i = (s.getConfig().slideIndex == null ? 0 : s.getConfig().slideIndex) + delta;
        if (i >= 0 && i < count) {
            slideStrip.setSelectedIndex(i);
            slideStrip.ensureIndexIsVisible(i);
        }
    }

    private void updateSlideLabel(Source s) {
        SourceConfig c = s.getConfig();
        int count = c.presentationId == null ? 0 : Rendering.presentations().slideCount(c.presentationId);
        int i = c.slideIndex == null ? 0 : c.slideIndex;
        slideLabel.setText(count == 0 ? "–" : (i + 1) + " / " + count);
        prevSlide.setEnabled(count > 0 && i > 0);
        nextSlide.setEnabled(count > 0 && i < count - 1);
    }

    /** Loads the library presentation's slide thumbnails for the slide strip (they are already cached on disk). */
    private void loadSlideThumbs(Source s) {
        Long id = s.getConfig().presentationId;
        slideModel.clear();
        slideThumbs = List.of();
        if (id == null) {
            return;
        }
        BackgroundTask.run("Presentation slides", () -> presentations.find(id)
                .map(p -> presentations.slides(p).stream().map(x -> x.thumbnail().toString()).toList())
                .orElse(List.of()), thumbs -> {
            if (sourceList.getSelectedValue() != s) {
                return;
            }
            loadingProps = true;
            try {
                slideThumbs = thumbs;
                slideModel.clear();
                for (int i = 0; i < thumbs.size(); i++) {
                    slideModel.addElement(i);
                }
                int sel = s.getConfig().slideIndex == null ? 0 : s.getConfig().slideIndex;
                if (sel < thumbs.size()) {
                    slideStrip.setSelectedIndex(sel);
                    slideStrip.ensureIndexIsVisible(sel);
                }
            } finally {
                loadingProps = false;
            }
            updateSlideLabel(s);
        }, err -> Dialogs.showError(this, "Could not read the presentation's slides.", err));
    }

    /** Slide thumbnail with its number. */
    private final class SlideThumbRenderer extends JComponent implements ListCellRenderer<Integer> {
        private int index;
        private boolean selected;

        @Override
        public Component getListCellRendererComponent(JList<? extends Integer> l, Integer v, int i, boolean sel, boolean f) {
            index = v;
            selected = sel;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int w = getWidth() - 6;
            int h = w * 9 / 16;
            g2.setColor(Color.BLACK);
            g2.fillRect(3, 3, w, h);
            java.awt.image.BufferedImage img = index < slideThumbs.size() ? Rendering.thumbnails().get(slideThumbs.get(index)) : null;
            if (img != null) {
                g2.drawImage(img, 3, 3, w, h, null);
            }
            g2.setColor(selected ? Palette.accent() : Palette.border());
            g2.setStroke(new java.awt.BasicStroke(selected ? 2.5f : 1f));
            g2.drawRect(3, 3, w - 1, h - 1);
            g2.setColor(Palette.secondaryText());
            g2.setFont(getFont().deriveFont(10f));
            g2.drawString(String.valueOf(index + 1), 5, h + 3 + g2.getFontMetrics().getAscent());
            g2.dispose();
        }
    }

    private void loadProps() {
        props.removeAll();
        Source s = sourceList.getSelectedValue();
        if (s == null) {
            props.revalidate();
            props.repaint();
            return;
        }
        loadingProps = true;
        try {
            SourceConfig c = s.getConfig();
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = 0;
            g.anchor = GridBagConstraints.WEST;
            g.fill = GridBagConstraints.HORIZONTAL;
            row(g, "Name", nameField);
            nameField.setText(s.getName());
            row(g, "Type", Ui.secondary(s.getType().toString()));
            SourceType t = s.getType();
            resetBoxes.putIfAbsent(s, new double[]{c.x, c.y, c.w, c.h});
            if (t == SourceType.PRESENTATION) {
                loadPresentationProps(g, s);
            }
            JPanel pos = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
            pos.add(Ui.small("X"));
            pos.add(xSpin);
            pos.add(Ui.small("Y"));
            pos.add(ySpin);
            row(g, "Position (px)", pos);
            JPanel size = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
            size.add(Ui.small("W"));
            size.add(wSpin);
            size.add(Ui.small("H"));
            size.add(hSpin);
            row(g, "Size (px)", size);
            xSpin.setValue((int) Math.round(c.x * W));
            ySpin.setValue((int) Math.round(c.y * H));
            wSpin.setValue((int) Math.round(c.w * W));
            hSpin.setValue((int) Math.round(c.h * H));
            boolean aspect = keepsAspect(t);
            if (aspect) {
                keepAspect.setSelected(aspectOn(s));
                row(g, "", keepAspect);
            }
            hSpin.setEnabled(!(aspect && aspectOn(s)));
            visibleBox.setSelected(s.isVisible());
            row(g, "Visibility", visibleBox);
            JPanel place = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 4));
            place.add(fitToCanvas);
            place.add(resetPosition);
            row(g, "Layout", place);
            if (t == SourceType.PRESENTATION || t == SourceType.COLOR) {
                if (t == SourceType.PRESENTATION) {
                    row(g, "Fit", fitCombo);
                    fitCombo.setSelectedItem(c.fit == null ? FitMode.FIT : c.fit);
                } else {
                    colorButton.setColor(Colors.parse(c.color, Color.BLACK));
                    row(g, "Color", colorButton);
                }
                JPanel o = Ui.transparent(new BorderLayout());
                o.add(opacity);
                row(g, "Opacity", o);
                opacity.setValue(c.opacity == null ? 100 : (int) Math.round(c.opacity * 100));
            }
            if (t == SourceType.IMAGE || t == SourceType.VIDEO || t == SourceType.AUDIO) {
                row(g, "Media", Ui.label(c.path == null ? "None chosen" : fileName(c.path),
                        Palette::text, "semibold"));
                row(g, "", selectFromLibrary);
                selectFromLibrary.setText("CHANGE FROM LIBRARY");
            }
            if (t == SourceType.LYRICS && c.songId != null) {
                JButton open = Ui.button("OPEN SONG", "music");
                open.setToolTipText("Show this song's slides in Library → Lyrics (the lyrics source shows the slide you send)");
                Long id = c.songId;
                open.addActionListener(e -> openSong.accept(id));
                row(g, "Song", open);
            }
            if (t == SourceType.LYRICS || t == SourceType.BIBLE) {
                textPlace.setSelectedIndex(c.textPosition == null ? 0 : c.textPosition.ordinal() + 1);
                row(g, "Text position", textPlace);
                textScale.setValue(c.textScale == null ? 100 : (int) Math.round(c.textScale * 100));
                textScale.setToolTipText("Text size compared with the theme (smaller over a camera)");
                row(g, "Text size %", textScale);
            }
            if (t == SourceType.OVERLAY) {
                JPanel o = Ui.transparent(new BorderLayout());
                o.add(opacity);
                row(g, "Darkness", o);
                opacity.setValue(c.opacity == null ? 60 : (int) Math.round(c.opacity * 100));
                row(g, "", Ui.label("<html>Clear at the top of its area, dark at the bottom, so lyrics stay readable "
                        + "over a camera.</html>", Palette::mutedText, "small"));
            }
            if (t == SourceType.SLIDESHOW) {
                loadSlideshowProps(g, s);
            }
            if (t == SourceType.CAMERA || t == SourceType.IMAGE || t == SourceType.CHURCH_LOGO || t == SourceType.VIDEO
                    || t == SourceType.SLIDESHOW) {
                row(g, "Fit", fitCombo);
                fitCombo.setSelectedItem(c.fit == null ? (t == SourceType.SLIDESHOW ? FitMode.FIT : FitMode.FILL) : c.fit);
                fitCombo.setToolTipText("Fit: whole image, keeps its shape. Fill: covers the box, edges cropped. "
                        + "Stretch: fills the box exactly, the image may look distorted.");
                JPanel o = Ui.transparent(new BorderLayout());
                o.add(opacity);
                row(g, "Opacity", o);
                opacity.setValue(c.opacity == null ? 100 : (int) Math.round(c.opacity * 100));
            }
            if (t == SourceType.IMAGE || t == SourceType.CHURCH_LOGO || t == SourceType.VIDEO) {
                JPanel f = Ui.transparent(new BorderLayout(6, 0));
                f.add(fileButton, BorderLayout.WEST);
                f.add(fileLabel, BorderLayout.CENTER);
                fileLabel.setText(c.path == null ? "No file chosen" : fileName(c.path));
                fileLabel.setToolTipText(c.path);
                row(g, "File", f);
            }
            if (t == SourceType.VIDEO || t == SourceType.AUDIO) {
                loadVideoProps(g, s);
            }
            if (t == SourceType.CAMERA) {
                cameraCombo.removeAllItems();
                cameraCombo.addItem("First available camera");
                cameras.cameras().forEach(cameraCombo::addItem);
                cameraCombo.setSelectedItem(c.camera == null ? "First available camera" : c.camera);
                row(g, "Camera", cameraCombo);
                if (cameras.cameras().isEmpty()) {
                    row(g, "", Ui.label("No camera detected.", Palette::warning, "small"));
                }
            }
            if (t == SourceType.TEXT || t == SourceType.CLOCK) {
                textField.setText(c.text == null ? "" : c.text);
                textField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, t == SourceType.CLOCK ? "h:mm a" : "Text");
                row(g, t == SourceType.CLOCK ? "Format" : "Text", textField);
                colorButton.setColor(Colors.parse(c.color, Color.WHITE));
                row(g, "Color", colorButton);
                fontSpin.setValue(c.fontSize == null ? 48 : c.fontSize);
                row(g, "Size", fontSpin);
            }
            if (t == SourceType.LYRICS || t == SourceType.BIBLE || t == SourceType.BACKGROUND || t == SourceType.LOWER_THIRD) {
                String help = switch (t) {
                    case LYRICS, BIBLE -> "Shows the text of whatever slide is on Program. Drag the box or its corners on the "
                            + "preview to move it or make it smaller: the text shrinks to fit the box.";
                    case BACKGROUND -> "Shows the background of the slide on Program, or a video/image taken live.";
                    default -> "Shows the lower third when you press SHOW in Lower Thirds.";
                };
                row(g, "", Ui.label("<html><body style='width:190px'>" + help + "</html>", Palette::mutedText, "small"));
            }
            g.weighty = 1;
            props.add(Ui.transparent(new BorderLayout()), g);
        } finally {
            loadingProps = false;
        }
        props.revalidate();
        props.repaint();
    }

    /** IMAGE SLIDESHOW: images, playback (on Program when it is live, else Preview), timing and transition. */
    private void loadSlideshowProps(GridBagConstraints g, Source s) {
        SourceConfig c = s.getConfig();
        row(g, "", Ui.label("IMAGE SLIDESHOW", Palette::secondaryText, "semibold"));
        int count = c.imageIds == null ? 0 : c.imageIds.size();
        JButton edit = Ui.button("EDIT IMAGES", "image");
        edit.setToolTipText("Add, remove, reorder or replace images");
        edit.addActionListener(e -> editSlideshow(s));
        JPanel images = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        images.add(Ui.label(count + (count == 1 ? " image" : " images"), Palette::text, null));
        images.add(edit);
        row(g, "Images", images);
        int missing = slideshows == null ? 0 : slideshows.missing(c);
        if (missing > 0) {
            JButton drop = Ui.button("REMOVE MISSING ITEM" + (missing == 1 ? "" : "S"), "trash");
            drop.addActionListener(e -> {
                c.imageIds = slideshows.existing(c);
                loadProps();
                sceneEdited();
            });
            JPanel warn = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 4));
            warn.add(Ui.label("⚠ " + missing + (missing == 1 ? " slideshow image is" : " slideshow images are")
                    + " missing (skipped).", Palette::warning, "small"));
            warn.add(drop);
            row(g, "", warn);
        }
        // MANUAL NAVIGATION first: Previous / Next change PREVIEW; TAKE puts that image on Program
        JButton prev = Ui.button("PREVIOUS", "chevron-left");
        prev.setToolTipText("Previous image in Preview (Program changes at TAKE)");
        prev.getAccessibleContext().setAccessibleName("Previous image");
        prev.addActionListener(e -> slideshowControl(s, com.jamc.worship.output.Slideshows.Control.PREVIOUS));
        JButton next = Ui.primaryButton("NEXT", "chevron-right");
        next.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);
        next.setToolTipText("Next image in Preview (Program changes at TAKE)");
        next.getAccessibleContext().setAccessibleName("Next image");
        next.addActionListener(e -> slideshowControl(s, com.jamc.worship.output.Slideshows.Control.NEXT));
        JPanel nav = Ui.transparent(new BorderLayout(8, 0));
        nav.add(prev, BorderLayout.WEST);
        slideshowStatus.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        nav.add(slideshowStatus, BorderLayout.CENTER);
        nav.add(next, BorderLayout.EAST);
        row(g, "Navigate", nav);
        row(g, "", Ui.label("<html>NEXT / PREVIOUS change Preview. TAKE shows that image on Program "
                + "(projector and livestream).</html>", Palette::mutedText, "small"));
        // AUTO PLAY: optional and off by default; it also advances Preview only
        slideAuto.setSelected(Boolean.TRUE.equals(c.autoplay));
        slideAuto.setToolTipText("Off (default): you move the images with Previous / Next. On: Preview advances by "
                + "itself; Program still changes only at TAKE.");
        slideLoop.setSelected(c.loop == null || c.loop);
        slideShuffle.setSelected(Boolean.TRUE.equals(c.shuffle));
        JPanel modes = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        modes.add(slideAuto);
        modes.add(slideLoop);
        modes.add(slideShuffle);
        row(g, "Auto Play", modes);
        slideSeconds.setValue(c.slideSeconds == null ? com.jamc.worship.output.Slideshows.DEFAULT_SECONDS : c.slideSeconds);
        slideSeconds.setEnabled(slideAuto.isSelected());
        row(g, "Every (s)", slideSeconds);
        JPanel autoButtons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        autoButtons.add(slideButton(s, "play", "Play (Auto Play in Preview)", com.jamc.worship.output.Slideshows.Control.PLAY));
        autoButtons.add(slideButton(s, "pause", "Pause", com.jamc.worship.output.Slideshows.Control.PAUSE));
        autoButtons.add(slideButton(s, "x", "Stop (back to the first image)", com.jamc.worship.output.Slideshows.Control.STOP));
        autoButtons.add(slideButton(s, "refresh", "Restart from the first image", com.jamc.worship.output.Slideshows.Control.RESTART));
        row(g, "", autoButtons);
        slideTransition.setSelectedItem(com.jamc.worship.output.Slideshows.Transition.of(c));
        slideMs.setValue(c.slideTransitionMs == null ? com.jamc.worship.output.Slideshows.DEFAULT_TRANSITION_MS : c.slideTransitionMs);
        slideMs.setEnabled(slideTransition.getSelectedItem() != com.jamc.worship.output.Slideshows.Transition.CUT);
        JPanel trans = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        trans.add(slideTransition);
        trans.add(slideMs);
        trans.add(Ui.small("ms"));
        row(g, "Transition", trans);
        slideOnProgram.setSelectedIndex(Boolean.TRUE.equals(c.restartOnProgram) ? 1 : 0);
        row(g, "Scene TAKE", slideOnProgram);
        slideBackground.setSelectedIndex("black".equals(c.slideBackground) ? 1 : 0);
        row(g, "Background", slideBackground);
        updateSlideshowStatus();
        slideshowTimer.start();
    }


    private JButton slideButton(Source s, String icon, String tip, com.jamc.worship.output.Slideshows.Control control) {
        JButton b = Ui.toolButton(icon, tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.addActionListener(e -> slideshowControl(s, control));
        return b;
    }

    /** VIDEO SOURCE: picture and sound, playback on Program, and whether the file has sound at all. */
    private void loadVideoProps(GridBagConstraints g, Source s) {
        SourceConfig c = s.getConfig();
        row(g, "", Ui.label(s.getType() == SourceType.AUDIO ? "AUDIO SOURCE" : "VIDEO SOURCE", Palette::secondaryText, "semibold"));
        videoBox.setSelected(!Boolean.FALSE.equals(c.videoEnabled));
        audioBox.setSelected(!Boolean.FALSE.equals(c.audioEnabled));
        audioBox.setToolTipText("On: its sound plays on Program (it goes quiet while you play another video or audio). Off: picture only.");
        JPanel playback = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        if (s.getType() == SourceType.VIDEO) {
            playback.add(videoBox);
        }
        playback.add(audioBox);
        row(g, "Playback", playback);
        row(g, "", audioStatus);
        audioOut.setSelectedIndex(Boolean.TRUE.equals(c.monitorOnly) ? 1 : 0);
        audioOut.setToolTipText("Program output: heard in the church and (with the MEDIA input) online. "
                + "Preview monitor only: only you hear it, e.g. on headphones.");
        row(g, "Audio output", audioOut);
        srcVolume.setValue(c.volume == null ? 100 : c.volume);
        srcVolumeLabel.setText(srcVolume.getValue() + "%");
        srcMute.setSelected(Boolean.TRUE.equals(c.muted));
        srcVolume.setEnabled(audioBox.isSelected());
        srcMute.setEnabled(audioBox.isSelected());
        JPanel vol = Ui.transparent(new BorderLayout(4, 0));
        vol.add(srcMute, BorderLayout.WEST);
        vol.add(srcVolume, BorderLayout.CENTER);
        JPanel volEast = Ui.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        volEast.add(srcVolumeLabel);
        volEast.add(loopBox);
        vol.add(volEast, BorderLayout.EAST);
        row(g, "Volume", vol);
        loopBox.setSelected(c.loop == null || c.loop);
        autoplayBox.setSelected(c.autoplay == null || c.autoplay);
        JPanel play = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 2));
        play.add(autoplayBox);
        row(g, "", play);
        takeCombo.setSelectedIndex(c.restartOnProgram == null ? 0 : c.restartOnProgram ? 1 : 2);
        takeCombo.setToolTipText("What happens to the video's position when this scene goes from Preview to Program");
        row(g, "On TAKE", takeCombo);
        // does the file have sound at all? (a silent file is not an error)
        audioStatus.setText("Checking the audio track…");
        audioStatus.setForeground(Palette.secondaryText());
        String path = c.path;
        if (path == null) {
            audioStatus.setText(" ");
            return;
        }
        if (com.jamc.worship.youtube.YouTubeRef.is(path)) {
            audioStatus.setText("YouTube video: sound plays on the Windows default output; needs the internet");
            audioStatus.setForeground(Palette.secondaryText());
            return;
        }
        BackgroundTask.run("Audio track", () -> com.jamc.worship.media.MediaProbe.audioTracks(media.resolve(path)), n -> {
            if (sourceList.getSelectedValue() != s) {
                return;
            }
            audioStatus.setText(n > 0 ? "Audio track found" : n == 0 ? "No audio track (this video is silent)"
                    : "⚠ Unable to read the audio track");
            audioStatus.setForeground(n > 0 ? Palette.secondaryText() : n == 0 ? Palette.mutedText() : Palette.warning());
        }, err -> audioStatus.setText("⚠ Unable to read the audio track"));
    }

    /** Sources whose box usually keeps the 16:9 shape of what they show. */
    private static boolean keepsAspect(SourceType t) {
        return t == SourceType.PRESENTATION || t == SourceType.CAMERA || t == SourceType.VIDEO || t == SourceType.IMAGE
                || t == SourceType.SLIDESHOW;
    }

    /** Whether resizing keeps 16:9: on by default for presentations, off for older layouts that never set it. */
    private static boolean aspectOn(Source s) {
        Boolean v = s.getConfig().keepAspect;
        return v != null ? v : s.getType() == SourceType.PRESENTATION;
    }

    /** PRESENTATION SOURCE: mode, the library presentation, and the slide it shows. */
    private void loadPresentationProps(GridBagConstraints g, Source s) {
        SourceConfig c = s.getConfig();
        modeCombo.setSelectedItem(c.isDynamicPresentation() ? SourceConfig.PresentationMode.DYNAMIC : SourceConfig.PresentationMode.FIXED);
        modeCombo.setToolTipText("Dynamic: any presentation slide you send to Preview shows here (one Sermon scene for every sermon). "
                + "Fixed: always this presentation.");
        row(g, "Source mode", modeCombo);
        String name = c.presentationId == null ? null : Rendering.presentations().name(c.presentationId);
        boolean missing = c.presentationId != null && (name == null || Rendering.presentations().slideCount(c.presentationId) == 0);
        presentationLabel.setText(missing ? "⚠ Presentation unavailable" : name != null ? name
                : "None (shows the slide you send)");
        presentationLabel.setForeground(missing ? Palette.warning() : Palette.text());
        row(g, "Presentation", presentationLabel);
        selectFromLibrary.setText(missing ? "SELECT ANOTHER PRESENTATION" : "SELECT FROM LIBRARY");
        row(g, "", selectFromLibrary);
        if (c.presentationId != null && !missing) {
            JPanel nav = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
            nav.add(prevSlide);
            nav.add(slideLabel);
            nav.add(nextSlide);
            row(g, "Current slide", nav);
            JScrollPane strip = new JScrollPane(slideStrip);
            strip.setPreferredSize(new Dimension(200, 82));
            strip.setMinimumSize(new Dimension(120, 82));
            strip.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
            row(g, "", strip);
            updateSlideLabel(s);
            loadSlideThumbs(s);
        }
        String help = c.isDynamicPresentation()
                ? "SEND TO PREVIEW from Library → Presentations shows that slide here; the scene itself stays. "
                + "The slide above is shown while no presentation is being sent."
                : "Always this presentation. Sending one of its slides from the library moves this source to that slide.";
        row(g, "", Ui.label("<html><body style='width:190px'>" + help + "</html>", Palette::mutedText, "small"));
    }

    private void row(GridBagConstraints g, String label, JComponent c) {
        g.gridx = 0;
        g.weightx = 0;
        g.insets = new Insets(0, 0, 8, 10);
        props.add(Ui.label(label, Palette::secondaryText, "small"), g);
        g.gridx = 1;
        g.weightx = 1;
        g.insets = new Insets(0, 0, 8, 0);
        props.add(c, g);
        g.gridy++;
    }

    private void applyProps() {
        Source s = sourceList.getSelectedValue();
        if (loadingProps || s == null) {
            return;
        }
        SourceConfig c = s.getConfig();
        if (!nameField.getText().isBlank()) {
            s.setName(nameField.getText().strip());
        }
        SourceType t = s.getType();
        if (s.isVisible() != visibleBox.isSelected()) {
            s.setVisible(visibleBox.isSelected());
        }
        if (keepsAspect(t)) {
            c.keepAspect = keepAspect.isSelected();
        }
        c.x = ((Number) xSpin.getValue()).intValue() / (double) W;
        c.y = ((Number) ySpin.getValue()).intValue() / (double) H;
        c.w = Math.max(10, ((Number) wSpin.getValue()).intValue()) / (double) W;
        c.h = Math.max(10, ((Number) hSpin.getValue()).intValue()) / (double) H;
        if (keepsAspect(t) && aspectOn(s)) {
            c.h = c.w; // the frame is 16:9, so equal fractions keep a 16:9 box
            hSpin.setEnabled(false);
            if (((Number) hSpin.getValue()).intValue() != (int) Math.round(c.h * H)) {
                loadingProps = true;
                try {
                    hSpin.setValue((int) Math.round(c.h * H));
                } finally {
                    loadingProps = false;
                }
            }
        } else {
            hSpin.setEnabled(true);
        }
        if (t == SourceType.PRESENTATION) {
            c.fit = (FitMode) fitCombo.getSelectedItem();
            c.opacity = opacity.getValue() >= 100 ? null : opacity.getValue() / 100.0;
            SourceConfig.PresentationMode mode = (SourceConfig.PresentationMode) modeCombo.getSelectedItem();
            c.presentationMode = mode;
        }
        if (t == SourceType.COLOR) {
            c.color = colorButton.getHex();
            c.opacity = opacity.getValue() >= 100 ? null : opacity.getValue() / 100.0;
        }
        if (t == SourceType.CAMERA || t == SourceType.IMAGE || t == SourceType.CHURCH_LOGO || t == SourceType.VIDEO
                || t == SourceType.SLIDESHOW) {
            c.fit = (FitMode) fitCombo.getSelectedItem();
            c.opacity = opacity.getValue() >= 100 ? null : opacity.getValue() / 100.0;
        }
        if (t == SourceType.SLIDESHOW) {
            c.autoplay = slideAuto.isSelected() ? Boolean.TRUE : null; // off by default: manual Previous / Next
            slideSeconds.setEnabled(slideAuto.isSelected());
            c.loop = slideLoop.isSelected() ? null : Boolean.FALSE;
            c.shuffle = slideShuffle.isSelected() ? Boolean.TRUE : null;
            int sec = ((Number) slideSeconds.getValue()).intValue();
            c.slideSeconds = sec == com.jamc.worship.output.Slideshows.DEFAULT_SECONDS ? null : sec;
            var tr = (com.jamc.worship.output.Slideshows.Transition) slideTransition.getSelectedItem();
            c.slideTransition = tr == com.jamc.worship.output.Slideshows.Transition.FADE ? null : tr.name();
            int ms = ((Number) slideMs.getValue()).intValue();
            c.slideTransitionMs = ms == com.jamc.worship.output.Slideshows.DEFAULT_TRANSITION_MS ? null : ms;
            slideMs.setEnabled(tr != com.jamc.worship.output.Slideshows.Transition.CUT);
            c.restartOnProgram = slideOnProgram.getSelectedIndex() == 1 ? Boolean.TRUE : null;
            c.slideBackground = slideBackground.getSelectedIndex() == 1 ? "black" : null;
        }
        if (t == SourceType.LYRICS || t == SourceType.BIBLE) {
            int place = textPlace.getSelectedIndex();
            c.textPosition = place <= 0 ? null : com.jamc.worship.model.TextPosition.values()[place - 1];
            c.textScale = textScale.getValue() == 100 ? null : textScale.getValue() / 100.0;
        }
        if (t == SourceType.OVERLAY) {
            c.opacity = opacity.getValue() / 100.0;
        }
        if (t == SourceType.VIDEO || t == SourceType.AUDIO) {
            c.videoEnabled = videoBox.isSelected() ? null : Boolean.FALSE;
            c.audioEnabled = audioBox.isSelected() ? null : Boolean.FALSE;
            c.monitorOnly = audioOut.getSelectedIndex() == 1 ? Boolean.TRUE : null;
            c.volume = srcVolume.getValue() == 100 ? null : srcVolume.getValue();
            c.muted = srcMute.isSelected() ? Boolean.TRUE : null;
            c.loop = loopBox.isSelected();
            c.autoplay = autoplayBox.isSelected() ? null : Boolean.FALSE;
            c.restartOnProgram = switch (takeCombo.getSelectedIndex()) {
                case 1 -> Boolean.TRUE;
                case 2 -> Boolean.FALSE;
                default -> null;
            };
            srcVolume.setEnabled(audioBox.isSelected());
            srcMute.setEnabled(audioBox.isSelected());
        }
        if (t == SourceType.CAMERA) {
            Object cam = cameraCombo.getSelectedItem();
            c.camera = cam == null || cameraCombo.getSelectedIndex() == 0 ? null : cam.toString();
        }
        if (t == SourceType.TEXT || t == SourceType.CLOCK) {
            c.text = textField.getText().isBlank() ? null : textField.getText();
            c.color = colorButton.getHex();
            c.fontSize = ((Number) fontSpin.getValue()).intValue();
        }
        sourceList.repaint();
        sceneEdited();
    }

    private void chooseFile() {
        Source s = sourceList.getSelectedValue();
        if (s == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        boolean video = s.getType() == SourceType.VIDEO;
        if (video) {
            com.jamc.worship.ui.MediaChoosers.videoFilters(chooser);
        } else {
            com.jamc.worship.ui.MediaChoosers.imageFilters(chooser);
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.nio.file.Path file = chooser.getSelectedFile().toPath();
        BackgroundTask.run("Import scene file", () -> {
            if (video) {
                var probe = com.jamc.worship.media.MediaProbe.probeVideo(file);
                if (probe.status() == com.jamc.worship.media.MediaProbe.Status.UNPLAYABLE) {
                    throw new com.jamc.worship.service.ValidationException(probe.message());
                }
            }
            return importer.importImage(file);
        }, stored -> {
            s.getConfig().path = stored;
            loadProps();
            sceneEdited();
        }, err -> Dialogs.showError(this, "The file could not be added.", err));
    }

    private static JSpinner pixels(int min, int max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(min, 0), min, max, 10));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(4);
        return s;
    }

    private static DocumentListener doc(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    /** Composite preview of the edited scene with the current Program content. */
    /**
     * Scene preview you can work on directly: click a layer to select it, drag it to move it, drag a
     * corner or edge handle to resize it. Lyrics and Bible text fit the box (a smaller box gives smaller
     * text). Sample lyrics are shown while nothing is on Program.
     */
    private final class ScenePreview extends JComponent {
        private static final int HANDLE = 7;
        private final Compositor compositor = new Compositor(Rendering.renderer());
        private final com.jamc.worship.output.LiveContent sample = com.jamc.worship.output.LiveContent.snapshot(
                new com.jamc.worship.model.Slide("Sample", "Amazing grace\nHow sweet the sound\nThat saved a wretch like me", null),
                com.jamc.worship.model.SlideStyle.fallback().withTextTransform(com.jamc.worship.model.TextTransform.UPPERCASE), "Sample");
        private Rectangle frame = new Rectangle();
        /** 0 = move; else the handle: 1..8 clockwise from top-left. */
        private int mode = -1;
        private double startX;
        private double startY;
        private double[] startBox;

        ScenePreview() {
            setPreferredSize(new Dimension(480, 270));
            setToolTipText("Click a layer to select it. Drag to move; drag the small squares to resize. Right-click for Fill Scene.");
            compositor.setEditorMode(true); // empty or missing Presentation sources show a placeholder here only
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        popup(e);
                        return;
                    }
                    pressed(e.getX(), e.getY());
                }

                private void popup(MouseEvent e) {
                    if (handleAt(e.getX(), e.getY()) < 0) {
                        pressed(e.getX(), e.getY()); // selects the layer under the mouse
                        mode = -1;
                    }
                    showLayoutMenu(ScenePreview.this, e.getX(), e.getY());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    dragged(e.getX(), e.getY());
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        popup(e);
                        return;
                    }
                    if (mode >= 0) {
                        mode = -1;
                        sceneEdited();
                    }
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    int h = handleAt(e.getX(), e.getY());
                    setCursor(java.awt.Cursor.getPredefinedCursor(h > 0 ? cursorFor(h)
                            : h == 0 ? java.awt.Cursor.MOVE_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private Rectangle boxOf(Source s) {
            SourceConfig c = s.getConfig();
            return new Rectangle(frame.x + (int) Math.round(c.x * frame.width), frame.y + (int) Math.round(c.y * frame.height),
                    (int) Math.round(c.w * frame.width), (int) Math.round(c.h * frame.height));
        }

        private java.awt.Point[] handles(Rectangle b) {
            int cx = b.x + b.width / 2;
            int cy = b.y + b.height / 2;
            return new java.awt.Point[]{null, new java.awt.Point(b.x, b.y), new java.awt.Point(cx, b.y),
                    new java.awt.Point(b.x + b.width, b.y), new java.awt.Point(b.x + b.width, cy),
                    new java.awt.Point(b.x + b.width, b.y + b.height), new java.awt.Point(cx, b.y + b.height),
                    new java.awt.Point(b.x, b.y + b.height), new java.awt.Point(b.x, cy)};
        }

        /** Handle number under the point, 0 inside the selected box, -1 elsewhere. */
        private int handleAt(int x, int y) {
            Source s = sourceList.getSelectedValue();
            if (s == null || frame.width <= 0) {
                return -1;
            }
            Rectangle b = boxOf(s);
            java.awt.Point[] hs = handles(b);
            for (int i = 1; i < hs.length; i++) {
                if (Math.abs(x - hs[i].x) <= HANDLE && Math.abs(y - hs[i].y) <= HANDLE) {
                    return i;
                }
            }
            return b.contains(x, y) ? 0 : -1;
        }

        private int cursorFor(int h) {
            return switch (h) {
                case 1 -> java.awt.Cursor.NW_RESIZE_CURSOR;
                case 2 -> java.awt.Cursor.N_RESIZE_CURSOR;
                case 3 -> java.awt.Cursor.NE_RESIZE_CURSOR;
                case 4 -> java.awt.Cursor.E_RESIZE_CURSOR;
                case 5 -> java.awt.Cursor.SE_RESIZE_CURSOR;
                case 6 -> java.awt.Cursor.S_RESIZE_CURSOR;
                case 7 -> java.awt.Cursor.SW_RESIZE_CURSOR;
                default -> java.awt.Cursor.W_RESIZE_CURSOR;
            };
        }

        private void pressed(int x, int y) {
            if (editing == null || frame.width <= 0) {
                return;
            }
            int h = handleAt(x, y);
            if (h < 0) {
                // select the top visible layer under the mouse (list order = top layer first)
                for (int i = 0; i < sourceModel.size(); i++) {
                    Source s = sourceModel.get(i);
                    if (s.isVisible() && boxOf(s).contains(x, y)) {
                        sourceList.setSelectedIndex(i);
                        h = 0;
                        break;
                    }
                }
            }
            Source s = sourceList.getSelectedValue();
            if (h < 0 || s == null || Boolean.TRUE.equals(s.getConfig().locked)) {
                mode = -1; // nothing under the mouse, or a locked layer: select only, never move
                return;
            }
            mode = h;
            startX = x;
            startY = y;
            SourceConfig c = s.getConfig();
            startBox = new double[]{c.x, c.y, c.w, c.h};
        }

        private void dragged(int x, int y) {
            Source s = sourceList.getSelectedValue();
            if (mode < 0 || s == null || startBox == null) {
                return;
            }
            double dx = (x - startX) / frame.width;
            double dy = (y - startY) / frame.height;
            double bx = startBox[0];
            double by = startBox[1];
            double bw = startBox[2];
            double bh = startBox[3];
            double min = 0.05;
            if (mode == 0) {
                bx = Math.clamp(bx + dx, 0, 1 - bw);
                by = Math.clamp(by + dy, 0, 1 - bh);
            } else {
                if (mode == 1 || mode == 7 || mode == 8) { // left edge
                    double nx = Math.clamp(bx + dx, 0, bx + bw - min);
                    bw = bw + (bx - nx);
                    bx = nx;
                }
                if (mode == 3 || mode == 4 || mode == 5) { // right edge
                    bw = Math.clamp(bw + dx, min, 1 - bx);
                }
                if (mode == 1 || mode == 2 || mode == 3) { // top edge
                    double ny = Math.clamp(by + dy, 0, by + bh - min);
                    bh = bh + (by - ny);
                    by = ny;
                }
                if (mode == 5 || mode == 6 || mode == 7) { // bottom edge
                    bh = Math.clamp(bh + dy, min, 1 - by);
                }
                if (keepsAspect(s.getType()) && aspectOn(s)) {
                    // keep 16:9 (equal fractions of the 16:9 frame): the edge being dragged decides the size
                    double size = mode == 2 || mode == 6 ? bh : bw;
                    boolean fromLeft = mode == 1 || mode == 7 || mode == 8;
                    boolean fromTop = mode == 1 || mode == 2 || mode == 3;
                    double right = bx + bw;
                    double bottom = by + bh;
                    size = Math.clamp(size, min, Math.min(fromLeft ? right : 1 - bx, fromTop ? bottom : 1 - by));
                    if (fromLeft) {
                        bx = right - size;
                    }
                    if (fromTop) {
                        by = bottom - size;
                    }
                    bw = size;
                    bh = size;
                }
            }
            SourceConfig c = s.getConfig();
            c.x = round(bx);
            c.y = round(by);
            c.w = round(bw);
            c.h = round(bh);
            loadingProps = true;
            try {
                xSpin.setValue((int) Math.round(c.x * W));
                ySpin.setValue((int) Math.round(c.y * H));
                wSpin.setValue((int) Math.round(c.w * W));
                hSpin.setValue((int) Math.round(c.h * H));
            } finally {
                loadingProps = false;
            }
            sceneEdited(); // live scene: the projector follows the drag
        }

        private static double round(double v) {
            return Math.round(v * 1000) / 1000.0;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth();
            int h = (int) Math.round(w * 9 / 16.0);
            if (h > getHeight()) {
                h = getHeight();
                w = (int) Math.round(h * 16 / 9.0);
            }
            Rectangle r = new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
            frame = r;
            double scale = g2.getTransform().getScaleX();
            int pw = Math.max(1, (int) Math.ceil(r.width * scale));
            int ph = Math.max(1, (int) Math.ceil(r.height * scale));
            g2.translate(r.x, r.y);
            g2.scale(r.width / (double) pw, r.height / (double) ph);
            g2.clipRect(0, 0, pw, ph);
            FrameSpec live = program.shown();
            com.jamc.worship.output.LiveContent content = live.content() != null ? live.content() : sample;
            if (Compositor.presentationSlot(editing, content) != null) {
                content = sample; // while editing, Presentation sources show the slide picked here, not Program's
            }
            FrameSpec spec = new FrameSpec(content, true, live.background(), false, editing);
            compositor.paint(g2, pw, ph, spec, program, false);
            g2.dispose();
            Graphics2D g3 = (Graphics2D) g.create();
            g3.setColor(Palette.border());
            g3.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            if (editing != null && program.scene() != null && Objects.equals(program.scene().getId(), editing.getId())) {
                g3.setColor(Palette.live());
                g3.drawRect(r.x, r.y, r.width - 1, r.height - 1);
                g3.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
            }
            Source sel = sourceList.getSelectedValue();
            if (sel != null && editing != null) {
                Rectangle b = boxOf(sel);
                g3.setColor(Palette.accent());
                g3.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER,
                        10f, new float[]{6f, 4f}, 0f));
                g3.drawRect(b.x, b.y, Math.max(1, b.width - 1), Math.max(1, b.height - 1));
                g3.setStroke(new java.awt.BasicStroke(1f));
                java.awt.Point[] hs = handles(b);
                for (int i = 1; i < hs.length; i++) {
                    g3.setColor(Color.WHITE);
                    g3.fillRect(hs[i].x - HANDLE / 2 - 1, hs[i].y - HANDLE / 2 - 1, HANDLE + 1, HANDLE + 1);
                    g3.setColor(Palette.accent());
                    g3.drawRect(hs[i].x - HANDLE / 2 - 1, hs[i].y - HANDLE / 2 - 1, HANDLE + 1, HANDLE + 1);
                }
                g3.setFont(getFont().deriveFont(java.awt.Font.BOLD, 10f));
                String label = sel.getName();
                java.awt.FontMetrics fm = g3.getFontMetrics();
                int ly = Math.max(r.y + fm.getAscent() + 2, b.y - 4);
                g3.setColor(Palette.accent());
                g3.fillRect(b.x, ly - fm.getAscent() - 1, fm.stringWidth(label) + 8, fm.getHeight());
                g3.setColor(Color.WHITE);
                g3.drawString(label, b.x + 4, ly);
            }
            g3.dispose();
        }
    }

    /** Scroll view that is always as wide as the properties column, so wide fields shrink instead of being cut off. */
    private static final class WidthTracking extends JPanel implements javax.swing.Scrollable {
        WidthTracking(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.NORTH);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
            return r.height - 32;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Source row: visibility checkbox, type icon and name. */
    private static final class SourceRenderer extends JPanel implements ListCellRenderer<Source> {
        private final JCheckBox visible = new JCheckBox();
        private final JLabel name = new JLabel();

        SourceRenderer() {
            super(new BorderLayout(6, 0));
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            visible.setOpaque(false);
            add(visible, BorderLayout.WEST);
            add(name, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Source> l, Source s, int i, boolean sel, boolean f) {
            visible.setSelected(s.isVisible());
            name.setText(s.getName() + (Boolean.TRUE.equals(s.getConfig().locked) ? "   (locked)" : ""));
            name.setIcon(Icons.get(s.getType().icon(), Icons.SMALL, () -> sel ? Color.WHITE : Palette.secondaryText()));
            name.setIconTextGap(8);
            name.setForeground(sel ? l.getSelectionForeground() : (s.isVisible() ? Palette.text() : Palette.mutedText()));            setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            setOpaque(sel);
            return this;
        }
    }
}
