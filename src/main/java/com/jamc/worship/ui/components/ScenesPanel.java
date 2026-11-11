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
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Scene editor: create and arrange scenes from layered sources on the editor's own canvas (with the current
 * Program content as sample lyrics). The editor is a third, independent state next to Preview and Program:
 * edits change only its draft, SAVE SCENE writes the scene library, and nothing reaches Preview or Program
 * until the scene is sent to Preview and taken.
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
    private final JButton saveButton = Ui.primaryButton("SAVE SCENE", null);
    private final JButton revertButton = Ui.button("REVERT", "refresh");
    private final JButton previewButton = Ui.button("SEND TO PREVIEW", "monitor");
    private final JLabel draftState = Ui.label(" ", Palette::secondaryText, "small");
    private Consumer<List<Scene>> scenesChanged = l -> { };
    private Consumer<Scene> previewChosen = s -> { };
    private Consumer<Scene> sceneSaved = s -> { };
    private Consumer<String> statusHandler = s -> { };
    /**
     * The scene being edited: a private draft, never Preview's or Program's scene. Changes stay here (and on this
     * canvas) until SAVE SCENE writes them to the scene library; even then Preview and Program keep what they show
     * until the scene is sent to Preview and taken.
     */
    private Scene editing;
    /** The draft has changes that are not saved yet. */
    private boolean dirty;
    /** Counts edits, so a save that finishes after newer edits does not mark the draft as saved. */
    private int editCount;

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
    private final JComboBox<String> audioInputCombo = new JComboBox<>();
    private final JCheckBox audioListen = new JCheckBox("Listen on the monitor device (not recorded)");
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
    /** TEXT / CLOCK: how heavy the letters are, and slanted or not. */
    private final JComboBox<com.jamc.worship.model.FontWeight> weightCombo = new JComboBox<>(com.jamc.worship.model.FontWeight.values());
    private final JCheckBox italicBox = new JCheckBox("Italic");
    private final JComboBox<String> cameraCombo = new JComboBox<>();
    private final JComboBox<String> songMode = new JComboBox<>(new String[]{"From library (any song sent)", "This song only"});
    // CAMERA source: crop (% of the picture at each edge), rotation, mirror, offline picture
    private final JSpinner cropLeft = percent();
    private final JSpinner cropTop = percent();
    private final JSpinner cropRight = percent();
    private final JSpinner cropBottom = percent();
    private final JComboBox<String> rotationCombo = new JComboBox<>(new String[]{"0°", "90°", "180°", "270°"});
    private final JCheckBox mirrorBox = new JCheckBox("Mirror (flip left to right)");
    private final JCheckBox directorBox = new JCheckBox("Controlled by Auto Director (virtual shots)");
    // CHROMA KEY (camera / NDI): the green background of the picture becomes transparent
    private final JCheckBox chromaBox = new JCheckBox("Remove green background (chroma key)");
    private final ColorButton chromaColor = new ColorButton("Key color", new Color(0x00FF00));
    private final JButton chromaGreen = new JButton("Green");
    private final JButton chromaBlue = new JButton("Blue");
    private final JSpinner chromaSimilarity = new JSpinner(new SpinnerNumberModel(40, 1, 100, 1));
    private final JSpinner chromaSmoothness = new JSpinner(new SpinnerNumberModel(8, 0, 100, 1));
    private final JCheckBox chromaSpill = new JCheckBox("Reduce green edges (spill)", true);
    private final JComboBox<String> offlineCombo = new JComboBox<>(new String[]{"\"Camera Offline\" card", "Black", "Image (e.g. church logo)"});
    private final JButton offlineImageButton = Ui.button("Choose Image…", "image");
    private com.jamc.worship.camera.CameraNames cameraNames;
    private final JSlider opacity = new JSlider(0, 100, 100);
    private final JComboBox<String> textPlace = new JComboBox<>(new String[]{"As the theme", "Top", "Middle", "Bottom"});
    /** LYRICS: this scene's lyrics layout (null = Settings default, else a ProgramLyricsStyle.Mode). */
    private final JComboBox<String> lyricsLayout = new JComboBox<>(new String[]{"Full lyrics (normal)",
            "Lower third (bar per line)", "Centered bars", "Full screen (dimmed)"});
    /** null = Full lyrics, the default of every Lyrics layer. */
    private static final String[] LAYOUTS = {null, "LOWER_THIRD", "CENTERED", "FULL_SCREEN"};
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
        // the editor goes off screen (another page, back to the scene list): its videos and
        // slideshows pause by themselves, so nothing keeps playing unseen
        preview.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !preview.isShowing()) {
                pauseEditorPlayback();
            }
        });
        wireProps();
        // Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) while the scene editor is on screen
        bindKey("scene.undo", javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK), this::undo);
        bindKey("scene.redo", javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK), this::redo);
        bindKey("scene.redo2", javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,
                java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK), this::redo);
    }

    private void bindKey(String name, javax.swing.KeyStroke key, Runnable action) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(key, name);
        getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }

            @Override
            public boolean isEnabled() {
                return preview.isShowing(); // only while the editor is on screen; elsewhere the key goes on as usual
            }
        });
    }

    /**
     * Ctrl+F in the scene editor: FIT TO CANVAS for the selected layer. Returns false when the editor is not on
     * screen or nothing is selected (Ctrl+F is then Find Lyrics as usual).
     */
    public boolean fitSelectedToCanvas() {
        if (!preview.isShowing() || editing == null || sourceList.getSelectedValue() == null) {
            return false;
        }
        setBox(0, 0, 1, 1);
        statusHandler.accept("\"" + sourceList.getSelectedValue().getName() + "\" fits the canvas (Ctrl+Z undoes it).");
        return true;
    }

    /** Pauses every video and slideshow playing in the scene editor (Preview and Program are not touched). */
    private void pauseEditorPlayback() {
        Rendering.frames().pauseEditorVideos();
        if (slideshows != null) {
            slideshows.pauseEditor();
        }
    }

    public void setScenesChanged(Consumer<List<Scene>> l) {
        scenesChanged = l;
    }

    /** SEND TO PREVIEW: the saved scene goes to Preview (Program changes only at TAKE). */
    /** Called after SAVE SCENE with the scene as saved. */
    public void setSceneSaved(Consumer<Scene> l) {
        sceneSaved = l;
    }

    public void setPreviewChosen(Consumer<Scene> l) {
        previewChosen = l;
    }

    /** True while the edited scene has changes that are not saved. */
    public boolean hasUnsavedChanges() {
        return dirty && editing != null;
    }

    /**
     * Before leaving the edited scene: Save, Discard or Cancel when it has unsaved changes. Returns false when
     * the operator cancels (stay in the editor).
     */
    public boolean confirmLeave() {
        if (!hasUnsavedChanges()) {
            return true;
        }
        int answer = JOptionPane.showOptionDialog(this, "Save the changes to the scene \"" + editing.getName() + "\"?\n"
                        + "Saving does not change Preview or Program.", "Unsaved Scene", JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, new Object[]{"Save", "Discard", "Cancel"}, "Save");
        if (answer == 0) {
            saveEditing();
            return true;
        }
        if (answer == 1) {
            discardChanges();
            return true;
        }
        return false;
    }

    /** On exit: offers to save a draft with unsaved changes (saved at once, before the database closes). */
    public void saveOnExit() {
        if (!hasUnsavedChanges() || editing.getId() == null) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this, "Save the changes to the scene \"" + editing.getName() + "\" before closing?",
                "Unsaved Scene", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            try {
                if (scenes.scene(editing.getId()).isPresent()) {
                    scenes.save(editing.copy());
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(ScenesPanel.class).warn("Could not save the scene on exit", e);
            }
        }
        dirty = false;
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    /**
     * Opens the editor on one scene ("Edit Layout" from the Scenes dock). Returns false when another scene has
     * unsaved changes and the operator chose Cancel.
     */
    public boolean edit(long sceneId) {
        if (editing != null && !Objects.equals(editing.getId(), sceneId) && !confirmLeave()) {
            return false;
        }
        selectAfterRefresh = sceneId;
        refresh();
        return true;
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
            if (e.getValueIsAdjusting() || reselecting) {
                return;
            }
            Scene s = sceneList.getSelectedValue();
            if (s != null && editing != null && Objects.equals(s.getId(), editing.getId()) && dirty) {
                return; // the list was reloaded: keep the unsaved draft of this scene
            }
            if (editing != null && (s == null || !Objects.equals(s.getId(), editing.getId())) && !confirmLeave()) {
                reselect(editing.getId()); // Cancel: stay on the scene with unsaved changes
                return;
            }
            editing = s == null ? null : s.copy();
            dirty = false;
            showEditing();
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
        saveButton.setToolTipText("Save this scene. If it is on Preview or Program it updates there at once; otherwise TAKE shows it.");
        saveButton.addActionListener(e -> saveEditing());
        revertButton.setToolTipText("Throw away the unsaved changes and go back to the saved scene");
        revertButton.addActionListener(e -> {
            if (hasUnsavedChanges() && Dialogs.confirmDestructive(this, "Revert scene",
                    "Throw away the unsaved changes to \"" + editing.getName() + "\"?", "Revert")) {
                discardChanges();
            }
        });
        previewButton.setToolTipText("Send the saved scene to Preview. Program changes only at TAKE.");
        previewButton.addActionListener(e -> {
            Scene saved = savedScene();
            if (saved != null && !hasUnsavedChanges()) {
                previewChosen.accept(saved);
            }
        });
        undoButton.setToolTipText("Undo the last change (Ctrl+Z). Back to the saved scene, SAVE SCENE is not needed.");
        undoButton.addActionListener(e -> undo());
        redoButton.setToolTipText("Redo (Ctrl+Y or Ctrl+Shift+Z)");
        redoButton.addActionListener(e -> redo());
        undoButton.setEnabled(false);
        redoButton.setEnabled(false);
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(draftState);
        buttons.add(undoButton);
        buttons.add(redoButton);
        buttons.add(revertButton);
        buttons.add(saveButton);
        buttons.add(previewButton);
        JPanel head = Ui.transparent(new BorderLayout());
        head.add(previewTitle, BorderLayout.WEST);
        head.add(buttons, BorderLayout.EAST);
        p.add(head, BorderLayout.NORTH);
        p.add(preview, BorderLayout.CENTER);
        p.add(Ui.label("<html>SCENE EDITOR: changes here stay a draft until SAVE SCENE. Saved, a scene on Preview or Program "
                + "updates there at once (other scenes show at TAKE). Layers are drawn from the "
                + "bottom of the Sources list upwards; lyrics show what is on Program.</html>",
                Palette::mutedText, "small"), BorderLayout.SOUTH);
        updateDraftState();
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

    // ------------------------------------------------------------------ undo / redo (Ctrl+Z / Ctrl+Y)

    /** States before each edit (newest last), and states undone (for redo). */
    private final java.util.ArrayDeque<Scene> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Scene> redoStack = new java.util.ArrayDeque<>();
    /** The draft as it is now (after the last edit), and the saved version as text (unsaved = different). */
    private Scene historyCurrent;
    private String savedSnapshot;
    private long lastEditAt;
    private static final int UNDO_LIMIT = 100;
    /** Edits closer together than this (a drag, typing in a box) are one undo step. */
    private static final long MERGE_MS = 600;
    private static final com.fasterxml.jackson.databind.ObjectMapper SNAPSHOT_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private boolean restoringHistory;
    private final JButton undoButton = Ui.button("UNDO", null);
    private final JButton redoButton = Ui.button("REDO", null);

    /** The scene's layers as text: two drafts with the same text look and work the same. */
    private static String snapshot(Scene s) {
        if (s == null) {
            return "";
        }
        try {
            return s.getName() + "|" + SNAPSHOT_JSON.writeValueAsString(s.getSources());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return s.getName() + "|" + System.identityHashCode(s);
        }
    }

    /** A scene was opened, reverted or reloaded: a fresh history, and this version counts as saved. */
    private void resetHistory() {
        undoStack.clear();
        redoStack.clear();
        historyCurrent = editing == null ? null : editing.copy();
        savedSnapshot = snapshot(editing);
        lastEditAt = 0;
        updateUndoButtons();
    }

    private void recordEdit() {
        if (restoringHistory || editing == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (historyCurrent != null && (undoStack.isEmpty() || now - lastEditAt > MERGE_MS)) {
            undoStack.addLast(historyCurrent);
            if (undoStack.size() > UNDO_LIMIT) {
                undoStack.removeFirst();
            }
        }
        lastEditAt = now;
        historyCurrent = editing.copy();
        redoStack.clear();
        updateUndoButtons();
    }

    /** Ctrl+Z: back one step. Back to the saved version, SAVE SCENE is not clickable again. */
    private void undo() {
        if (editing == null || undoStack.isEmpty()) {
            return;
        }
        redoStack.addLast(historyCurrent);
        restoreHistory(undoStack.removeLast());
        statusHandler.accept("Undo" + (dirty ? "" : ": back to the saved scene"));
    }

    /** Ctrl+Y / Ctrl+Shift+Z: the step undone again. */
    private void redo() {
        if (editing == null || redoStack.isEmpty()) {
            return;
        }
        undoStack.addLast(historyCurrent);
        restoreHistory(redoStack.removeLast());
        statusHandler.accept("Redo");
    }

    private void restoreHistory(Scene state) {
        int selected = sourceList.getSelectedIndex();
        historyCurrent = state;
        editing = state.copy();
        lastEditAt = 0; // the next edit starts a new step
        restoringHistory = true;
        try {
            showEditing();
            if (selected >= 0 && selected < sourceModel.size()) {
                sourceList.setSelectedIndex(selected); // the same layer stays selected where it can
            }
        } finally {
            restoringHistory = false;
        }
        dirty = !snapshot(editing).equals(savedSnapshot);
        editCount++;
        updateDraftState();
    }

    private void updateUndoButtons() {
        undoButton.setEnabled(editing != null && !undoStack.isEmpty());
        redoButton.setEnabled(editing != null && !redoStack.isEmpty());
    }

    private void showEditing() {
        if (!restoringHistory) {
            resetHistory(); // another scene, the saved version again, or a reload
        }
        sourceModel.clear();
        groupRects.clear(); // groups take their box from their layers again (another scene, undo, revert)
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
        updateDraftState();
    }

    /** Called after any change to the edited scene. */
    private void sceneEdited() {
        if (editing == null) {
            return;
        }
        syncGroups();
        List<Source> list = new ArrayList<>();
        for (int i = 0; i < sourceModel.size(); i++) {
            list.add(sourceModel.get(i));
        }
        editing.setSources(list);
        preview.repaint();
        // only the draft changes: Preview and Program keep their own copies until SAVE → Preview → TAKE
        recordEdit();
        // unsaved only when it differs from the saved version (an edit undone by hand counts as saved again)
        dirty = savedSnapshot == null || !snapshot(editing).equals(savedSnapshot);
        editCount++;
        updateDraftState();
    }

    private void updateDraftState() {
        boolean has = editing != null;
        saveButton.setEnabled(has && dirty);
        revertButton.setEnabled(has && dirty);
        previewButton.setEnabled(has && !dirty);
        previewButton.setToolTipText(dirty ? "Save the scene first; then it can go to Preview."
                : "Send the saved scene to Preview. Program changes only at TAKE.");
        draftState.setText(!has ? " " : dirty ? "Unsaved changes" : "Saved");
        draftState.setForeground(dirty ? Palette.warning() : Palette.secondaryText());
        preview.repaint();
    }

    /** The edited scene as saved in the library (what SEND TO PREVIEW sends), or null. */
    private Scene savedScene() {
        if (editing == null) {
            return null;
        }
        for (int i = 0; i < sceneModel.size(); i++) {
            if (Objects.equals(sceneModel.get(i).getId(), editing.getId())) {
                return sceneModel.get(i);
            }
        }
        return null;
    }

    /** Back to the saved version of the edited scene. */
    private void discardChanges() {
        Scene saved = savedScene();
        editing = saved == null ? null : saved.copy();
        dirty = false;
        showEditing();
    }

    private boolean reselecting;

    private void reselect(Long id) {
        reselecting = true;
        try {
            for (int i = 0; i < sceneModel.size(); i++) {
                if (Objects.equals(sceneModel.get(i).getId(), id)) {
                    sceneList.setSelectedIndex(i);
                }
            }
        } finally {
            reselecting = false;
        }
    }

    /**
     * SAVE SCENE: writes the draft to the scene library and tells {@link #setSceneSaved} (the app selects it for TAKE).
     * Preview and Program change only at TAKE.
     */
    private void saveEditing() {
        if (editing == null || editing.getId() == null) {
            return;
        }
        Scene snapshot = editing.copy();
        int version = editCount;
        // a scene deleted meanwhile (Scenes list, right-click → Delete) is not saved back
        BackgroundTask.run("Save scene", () -> scenes.scene(snapshot.getId()).isPresent() ? scenes.save(snapshot) : null, saved -> {
            if (saved == null) {
                if (editing != null && Objects.equals(editing.getId(), snapshot.getId())) {
                    editing = null;
                    dirty = false;
                    showEditing();
                }
                statusHandler.accept("The scene was deleted meanwhile; it was not saved.");
                return;
            }
            if (editing != null && Objects.equals(editing.getId(), saved.getId())) {
                // what was written is the saved version now; Ctrl+Z can still go back past it (it is then unsaved)
                savedSnapshot = snapshot(snapshot);
                dirty = editCount != version && !snapshot(editing).equals(savedSnapshot);
                updateUndoButtons();
                updateDraftState();
            }
            statusHandler.accept("Scene \"" + saved.getName() + "\" saved.");
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
            sceneSaved.accept(saved);
        }, err -> Dialogs.showError(this, "The scene could not be saved.", err));
    }

    private void createScene() {
        if (!confirmLeave()) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Scene name:", "New Scene", JOptionPane.PLAIN_MESSAGE, null, null, "New Scene");
        if (name == null || name.toString().isBlank()) {
            return;
        }
        BackgroundTask.run("Create scene", () -> scenes.create(name.toString()), s -> {
            editing = s.copy();
            dirty = false;
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
            String newName = name.toString().strip();
            editing.setName(newName);
            previewTitle.setText(newName);
            long id = editing.getId();
            // only the name is saved: unsaved layout changes stay a draft until SAVE SCENE
            BackgroundTask.run("Rename scene", () -> scenes.scene(id).map(stored -> {
                stored.setName(newName);
                return scenes.save(stored);
            }).orElse(null), saved -> {
                if (saved != null) {
                    for (int i = 0; i < sceneModel.size(); i++) {
                        if (Objects.equals(sceneModel.get(i).getId(), id)) {
                            sceneModel.set(i, saved);
                        }
                    }
                    List<Scene> all = new ArrayList<>();
                    for (int i = 0; i < sceneModel.size(); i++) {
                        all.add(sceneModel.get(i));
                    }
                    scenesChanged.accept(all);
                }
            }, err -> Dialogs.showError(this, "The scene could not be renamed.", err));
        }
    }

    private void duplicateScene() {
        if (editing == null) {
            return;
        }
        Scene unsaved = null;
        if (hasUnsavedChanges()) {
            int answer = JOptionPane.showOptionDialog(this, "\"" + editing.getName() + "\" has unsaved changes. Save them "
                            + "before duplicating?", "Unsaved Scene", JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, new Object[]{"Save", "Discard", "Cancel"}, "Save");
            if (answer != 0 && answer != 1) {
                return;
            }
            unsaved = answer == 0 ? editing.copy() : null;
        }
        long id = editing.getId();
        Scene toSave = unsaved;
        // save first (when asked) and duplicate in one task, so the copy has the saved changes
        BackgroundTask.run("Duplicate scene", () -> {
            if (toSave != null) {
                scenes.save(toSave);
            }
            return scenes.duplicate(id);
        }, s -> {
            editing = s.copy();
            dirty = false;
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
        dirty = false;
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
        menu.add(menuItem("Videos…", "video", this::addVideos));
        JMenuItem playlist = menuItem("Video Playlist (multiple videos)…", "video", () -> pickPlaylist(null));
        playlist.setToolTipText("Several videos as one source (like OBS VLC Video): Autoplay plays the next one when one ends; loop, shuffle");
        menu.add(playlist);
        menu.add(menuItem("Audio…", "audio", () -> addMediaFromLibrary(MediaKind.AUDIO)));
        menu.add(menuItem("Media", "layers", () -> addSource(SourceType.BACKGROUND)));
        menu.add(menuItem("Camera…", "video", this::addCameraSource));
        JMenuItem director = menuItem("Auto Director (virtual shots)", "cpu", this::addAutoDirectorSource);
        director.setToolTipText("A camera box whose picture the AI Auto Director chooses (Wide, Lead Singer, Drummer… "
                + "from one or several cameras). Set it up in Library → Camera → AUTO DIRECTOR.");
        menu.add(director);
        menu.add(menuItem("Audio Input…", "audio", this::addAudioInput));
        JMenuItem pcSound = menuItem("Audio Output Capture", "audio", this::addAudioOutput);
        pcSound.setToolTipText("The PC's sound (other programs) into the recording and the stream, as in OBS");
        JMenuItem appSound = menuItem("Application Audio Capture…", "audio", () -> pickApp(null));
        appSound.setToolTipText("One program's sound (a browser, Zoom…) into the recording and the stream, as in OBS");
        if (!com.jamc.worship.capture.WindowCapture.supported()) {
            pcSound.setEnabled(false);
            appSound.setEnabled(false);
        }
        menu.add(pcSound);
        menu.add(appSound);
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
        JMenuItem window = menuItem("Window Capture…", "monitor", () -> pickWindow(null));
        if (!com.jamc.worship.capture.WindowCapture.supported()) {
            window.setEnabled(false);
            window.setToolTipText("Window Capture works on Windows only");
        }
        menu.add(window);
        JMenuItem display = menuItem("Display Capture…", "monitor", () -> pickDisplay(null));
        if (!com.jamc.worship.capture.DisplayCapture.supported()) {
            display.setEnabled(false);
            display.setToolTipText("Display Capture works on Windows only");
        }
        menu.add(display);
        menu.add(menuItem("Browser…", "monitor", () -> askUrl(null)));
        JMenuItem scene = menuItem("Scene…", "layers-stack", () -> pickScene(null));
        scene.setToolTipText("Another saved scene shown inside this one (e.g. a picture-in-picture), as in OBS");
        menu.add(scene);
        JMenuItem group = menuItem("Group…", "folder", this::addGroup);
        group.setToolTipText("Keep several layers together: they move, show and hide as one, as in OBS");
        menu.add(group);
        JMenuItem ndi = menuItem("NDI…", "video", this::addNdiSource);
        ndi.setToolTipText(com.jamc.worship.camera.Ndi.isInstalled()
                ? "A video sent over the network with NDI (another computer, a phone app, a PTZ camera, OBS)"
                : "Needs the free NDI Runtime (NDI Tools from ndi.video)");
        menu.add(ndi);
        narrowTooltips(menu);
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** Tooltip width in the Add Source menu: long explanations wrap onto more lines instead of one wide line. */
    static final int TOOLTIP_WIDTH = 220;

    /** Every tooltip of the menu becomes a narrow box; long text wraps word by word onto the next lines. */
    static void narrowTooltips(JPopupMenu menu) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JComponent item && item.getToolTipText() != null) {
                item.setToolTipText(narrowTooltip(item.getToolTipText()));
            }
        }
    }

    /** "<html>…</html>" with a fixed width, so Swing wraps it (HTML line breaks already in it are kept). */
    static String narrowTooltip(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String body = text.startsWith("<html>") ? text.substring(6).replaceFirst("(?s)</html>\\s*$", "")
                : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        body = body.replaceFirst("^<body[^>]*>", "").replaceFirst("(?s)</body>\\s*$", "");
        // Swing draws HTML px about a third wider than screen pixels: 220 screen px ≈ 170 css px
        return "<html><body style='width:" + Math.round(TOOLTIP_WIDTH / 1.3) + "px'>" + body + "</body></html>";
    }

    // ------------------------------------------------------------------ library and service sources

    private com.jamc.worship.service.SongService songs;
    private java.util.function.Supplier<List<com.jamc.worship.model.ServiceItem>> serviceItems = List::of;
    private Consumer<Long> openSong = id -> { };

    /** The running slideshows (playback buttons and status of IMAGE SLIDESHOW sources). */
    public void setSlideshows(com.jamc.worship.output.Slideshows slideshows) {
        this.slideshows = slideshows;
    }

    /**
     * The two ways to add library content: FROM LIBRARY (dynamic: whatever of that kind is sent to Preview / TAKE
     * shows here) or SPECIFIC FILE (only the chosen one, whatever else is clicked). Returns 0 (dynamic),
     * 1 (specific) or the index of an extra option, -1 for cancel.
     */
    private int askLibraryMode(String title, String what, String dynamicText, String specificText, String icon,
                               String... extra) {
        List<Object> options = new ArrayList<>(List.of("FROM LIBRARY", "SPECIFIC FILE"));
        options.addAll(List.of(extra));
        options.add("CANCEL");
        JPanel ask = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 8));
        ask.add(Ui.label("How should this " + what + " source work?", Palette::text, "semibold"));
        JLabel dyn = Ui.label("<html><b>FROM LIBRARY</b>: " + dynamicText + "</html>", Palette::secondaryText, null);
        dyn.setIcon(Icons.get("library", Icons.MEDIUM, Palette::secondaryText));
        JLabel one = Ui.label("<html><b>SPECIFIC FILE</b>: " + specificText + "</html>", Palette::secondaryText, null);
        one.setIcon(Icons.get(icon, Icons.MEDIUM, Palette::secondaryText));
        ask.add(dyn);
        ask.add(one);
        int choice = javax.swing.JOptionPane.showOptionDialog(this, ask, title, javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, options.toArray(), options.get(1));
        return choice < 0 || choice == options.size() - 1 ? -1 : choice;
    }

    /**
     * ADD SOURCE → Images: ONE IMAGE (Select Image, with UPLOAD FILE… in it) or IMAGE SLIDESHOW. Whatever image is
     * sent to Preview is the separate "Media" source.
     */
    private void addImages() {
        if (editing == null) {
            return;
        }
        Object[] options = {"ONE IMAGE", "IMAGE SLIDESHOW", "CANCEL"};
        int choice = javax.swing.JOptionPane.showOptionDialog(this,
                Ui.label("<html>Add <b>one image</b>, or an <b>image slideshow</b> of several?</html>", Palette::text, null),
                "Add Image Source", javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            addMediaFromLibrary(MediaKind.IMAGE);
        } else if (choice == 1) {
            addSlideshow();
        }
    }

    /** ADD SOURCE → Videos: straight to Select Video (UPLOAD FILE… there adds one from the computer). */
    private void addVideos() {
        if (editing != null) {
            addMediaFromLibrary(MediaKind.VIDEO);
        }
    }


    /**
     * ADD SOURCE → VLC Video (Playlist), like OBS: several library videos as ONE source, played one after another
     * (Loop repeats the whole list, Shuffle plays it in random order). {@code existing} null adds a new source;
     * otherwise its list is chosen again. YouTube videos are not offered: they play in another player.
     */
    private void pickPlaylist(Source existing) {
        if (editing == null) {
            return;
        }
        List<MediaCategory> cats = List.of(MediaCategory.VIDEO, MediaCategory.BACKGROUND);
        LibraryPickerDialog.Spec<MediaItem> spec = new LibraryPickerDialog.Spec<MediaItem>(
                existing == null ? "Video Playlist: tick the videos" : "Playlist videos: ticked = in the playlist", "Search videos…", "video",
                "No videos in the library yet.<br>Click UPLOAD FILES… to add some from this computer.",
                text -> media.find(cats, text, false).stream()
                        .filter(m -> m.getKind() == MediaKind.VIDEO && !m.isYouTube()).toList(),
                MediaItem::getName,
                m -> (m.isPixabay() ? "Pixabay video" : "video")
                        + (m.getDurationMs() == null ? "" : "  ·  " + TransportControls.format(m.getDurationMs())),
                this::thumbnailOf).withUpload(com.jamc.worship.ui.dialogs.LibraryUploads.media(media, MediaKind.VIDEO));
        // Change…: the videos already in the playlist open ticked (untick to remove one, tick others to add them)
        List<MediaItem> already = new ArrayList<>();
        if (existing != null && existing.getConfig().videoIds != null) {
            List<MediaItem> all = media.find(cats, "", false);
            for (Long id : existing.getConfig().videoIds) {
                all.stream().filter(m -> id != null && id.equals(m.getId())).findFirst().ifPresent(already::add);
            }
        }
        List<MediaItem> picked = LibraryPickerDialog.pickMany(this, spec, "videos", already);
        if (picked == null || picked.isEmpty()) {
            return;
        }
        Source s = existing != null ? existing : new Source("Video Playlist", SourceType.VIDEO);
        SourceConfig c = s.getConfig();
        c.videoIds = new ArrayList<>(picked.stream().map(MediaItem::getId).toList());
        c.videoPaths = new ArrayList<>(picked.stream().map(MediaItem::getPath).toList());
        c.path = c.videoPaths.getFirst();
        c.mediaId = c.videoIds.getFirst();
        if (existing == null) {
            c.fit = FitMode.FIT;
            c.loop = false; // plays once: Loop only when the operator turns it on
            insertSource(s);
        } else {
            loadProps();
            sceneEdited();
        }
        statusHandler.accept(picked.size() == 1 ? "One video chosen: it plays as a normal video source."
                : "Playlist of " + picked.size() + " videos: they play one after another.");
    }

    /** The playlist's videos, in order, with Change… and Shuffle (Loop, volume and the rest are the video rows). */
    private void loadPlaylistProps(GridBagConstraints g, Source s) {
        SourceConfig c = s.getConfig();
        List<String> names = c.videoPaths.stream().map(ScenesPanel::fileName).toList();
        JLabel what = Ui.small(names.size() + " videos: " + String.join(", ", names));
        what.setToolTipText("<html>" + String.join("<br>", names) + "</html>");
        JButton change = Ui.button("Edit Videos…", "video");
        change.setToolTipText("Add, remove or replace videos: the ones in the playlist are ticked; untick to remove, tick others to add");
        change.addActionListener(e -> pickPlaylist(s));
        JPanel row = Ui.transparent(new BorderLayout(6, 0));
        row.add(change, BorderLayout.WEST);
        row.add(what, BorderLayout.CENTER);
        row(g, "Playlist", row);
        // every chosen video, in play order, with its picture; the one playing now is marked
        row(g, "Videos", new PlaylistView(c));
        JCheckBox shuffle = new JCheckBox("Shuffle (random order)", Boolean.TRUE.equals(c.shuffle));
        shuffle.setOpaque(false);
        shuffle.addActionListener(e -> {
            c.shuffle = shuffle.isSelected() ? Boolean.TRUE : null;
            sceneEdited();
        });
        row(g, "Order", shuffle);
        // AUTOPLAY (on by default): when a video ends the next one starts by itself; off = it waits for NEXT
        JCheckBox autoNext = new JCheckBox("Autoplay: play the next video when one ends", c.autoNext == null || c.autoNext);
        autoNext.setOpaque(false);
        autoNext.setToolTipText("<html>On: video 1 ends, video 2 starts by itself.<br>"
                + "Off: each video stops at its end; press NEXT under Preview / Program for the next one.</html>");
        autoNext.addActionListener(e -> {
            c.autoNext = autoNext.isSelected() ? null : Boolean.FALSE;
            sceneEdited();
        });
        row(g, "Autoplay", autoNext);
        row(g, "", Ui.label("<html>Autoplay on: the videos play one after another. Off: each video stops at its end "
                + "and NEXT (under Preview / Program) plays the next one. Loop (below) starts the list over after the "
                + "last one; without it the last picture stays.</html>", Palette::mutedText, "small"));
    }

    // ------------------------------------------------------------------ Scene and Group sources (as in OBS)

    /** ADD SOURCE → Scene: another saved scene inside this one. {@code existing} null adds a new source. */
    private void pickScene(Source existing) {
        if (editing == null) {
            return;
        }
        List<Scene> others = new ArrayList<>();
        for (int i = 0; i < sceneModel.size(); i++) {
            Scene sc = sceneModel.get(i);
            if (sc.getId() != null && !java.util.Objects.equals(sc.getId(), editing.getId())) {
                others.add(sc);
            }
        }
        Scene picked = LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<Scene>(
                "Select Scene", "Search scenes…", "layers-stack",
                "No other saved scene yet.<br>Make and save another scene first.",
                text -> others.stream().filter(x -> x.getName().toLowerCase().contains(text.strip().toLowerCase())).toList(),
                Scene::getName, x -> x.getSources().size() + " layers", x -> null));
        if (picked == null) {
            return;
        }
        Source s = existing != null ? existing : new Source("Scene: " + picked.getName(), SourceType.SCENE);
        s.getConfig().sceneId = picked.getId();
        if (existing == null) {
            insertSource(s); // full screen by default; drag it smaller for a picture-in-picture
        } else {
            s.setName("Scene: " + picked.getName());
            loadProps();
            sceneEdited();
        }
        statusHandler.accept("\"" + picked.getName() + "\" shows inside this scene. Changes saved to that scene show here too.");
    }

    private void loadSceneProps(GridBagConstraints g, Source s) {
        Scene inner = com.jamc.worship.model.SceneLookup.find(s.getConfig().sceneId);
        JLabel what = Ui.small(inner != null ? inner.getName() : s.getConfig().sceneId == null ? "No scene chosen" : "Scene not found");
        JButton change = Ui.button("Change…", "layers-stack");
        change.addActionListener(e -> pickScene(s));
        JPanel row = Ui.transparent(new BorderLayout(6, 0));
        row.add(change, BorderLayout.WEST);
        row.add(what, BorderLayout.CENTER);
        row(g, "Scene", row);
        JPanel o = Ui.transparent(new BorderLayout());
        o.add(opacity);
        row(g, "Opacity", o);
        opacity.setValue(s.getConfig().opacity == null ? 100 : (int) Math.round(s.getConfig().opacity * 100));
        row(g, "", Ui.label("<html>The whole scene, live, in this box (its lyrics, Bible and slides follow what is sent). "
                + "Edit that scene to change what shows here.</html>", Palette::mutedText, "small"));
    }

    /** ADD SOURCE → Group: tick the layers to keep together; they then move, show and hide as one. */
    private void addGroup() {
        if (editing == null) {
            return;
        }
        Source group = new Source("Group", SourceType.GROUP);
        group.getConfig().uid = java.util.UUID.randomUUID().toString();
        if (!chooseGroupMembers(group)) {
            return;
        }
        int top = sourceModel.size();
        for (int i = 0; i < sourceModel.size(); i++) {
            if (group.getConfig().uid.equals(sourceModel.get(i).getConfig().groupId)) {
                top = Math.min(top, i);
            }
        }
        sourceModel.add(Math.min(top, sourceModel.size()), group); // above its layers
        sourceList.setSelectedValue(group, true);
        sceneEdited();
        statusHandler.accept("Group made: drag it to move its layers together; hide it to hide them all.");
    }

    /** The layers of a group, chosen with ticks. False when cancelled or nothing was ticked. */
    private boolean chooseGroupMembers(Source group) {
        String uid = group.getConfig().uid;
        List<JCheckBox> boxes = new ArrayList<>();
        List<Source> candidates = new ArrayList<>();
        JPanel list = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 2));
        for (int i = 0; i < sourceModel.size(); i++) {
            Source s = sourceModel.get(i);
            if (s.getType() == SourceType.GROUP || (s.getConfig().groupId != null && !uid.equals(s.getConfig().groupId))) {
                continue; // a layer belongs to one group; groups are not nested
            }
            JCheckBox b = new JCheckBox(s.getName(), uid.equals(s.getConfig().groupId));
            boxes.add(b);
            candidates.add(s);
            list.add(b);
        }
        if (candidates.isEmpty()) {
            Dialogs.showInfo(this, "Group", "There are no layers to group yet. Add some sources first.");
            return false;
        }
        JTextField name = new JTextField(group.getName(), 20);
        JPanel ask = Ui.transparent(new BorderLayout(0, 8));
        JPanel head = Ui.transparent(new BorderLayout(6, 0));
        head.add(Ui.small("Name"), BorderLayout.WEST);
        head.add(name, BorderLayout.CENTER);
        ask.add(head, BorderLayout.NORTH);
        ask.add(Ui.label("Layers in this group:", Palette::text, "semibold"), BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(320, Math.min(260, 28 * candidates.size() + 8)));
        ask.add(scroll, BorderLayout.SOUTH);
        if (javax.swing.JOptionPane.showConfirmDialog(this, ask, "Group", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) {
            return false;
        }
        if (boxes.stream().noneMatch(JCheckBox::isSelected)) {
            Dialogs.showInfo(this, "Group", "Tick at least one layer.");
            return false;
        }
        if (!name.getText().isBlank()) {
            group.setName(name.getText().strip());
        }
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).getConfig().groupId = boxes.get(i).isSelected() ? uid : null;
        }
        groupRects.remove(uid); // the group's box is made again from its layers
        return true;
    }

    private void loadGroupProps(GridBagConstraints g, Source s) {
        String uid = s.getConfig().uid;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sourceModel.size(); i++) {
            if (uid != null && uid.equals(sourceModel.get(i).getConfig().groupId)) {
                names.add(sourceModel.get(i).getName());
            }
        }
        JLabel what = Ui.small(names.isEmpty() ? "No layers" : String.join(", ", names));
        what.setToolTipText(what.getText());
        JButton change = Ui.button("Layers…", "folder");
        change.addActionListener(e -> {
            if (chooseGroupMembers(s)) {
                sourceList.repaint();
                loadProps();
                sceneEdited();
            }
        });
        JButton ungroup = Ui.button("Ungroup", null);
        ungroup.setToolTipText("Keep the layers where they are and remove only the group");
        ungroup.addActionListener(e -> ungroup(s));
        JPanel row = Ui.transparent(new BorderLayout(6, 0));
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(change);
        buttons.add(ungroup);
        row.add(buttons, BorderLayout.WEST);
        row.add(what, BorderLayout.CENTER);
        row(g, "Group", row);
        row(g, "", Ui.label("<html>Drag or resize the group to move all its layers. Hide it to hide them all. "
                + "Select a layer in the list to change it alone.</html>", Palette::mutedText, "small"));
    }

    /** Removes the group only: its layers stay, on their own. */
    private void ungroup(Source group) {
        String uid = group.getConfig().uid;
        for (int i = 0; i < sourceModel.size(); i++) {
            if (uid != null && uid.equals(sourceModel.get(i).getConfig().groupId)) {
                sourceModel.get(i).getConfig().groupId = null;
            }
        }
        sourceModel.removeElement(group);
        groupRects.remove(uid);
        sourceList.repaint();
        sceneEdited();
    }

    /** Each group's box and visibility as last seen: {x, y, w, h, visible}. */
    private final java.util.Map<String, double[]> groupRects = new java.util.HashMap<>();

    /**
     * Keeps groups and their layers together after any edit: the group moved or resized → its layers follow (scaled
     * in the same proportion); the group shown / hidden → its layers too; a layer moved on its own → the group's box
     * becomes the box around its layers again.
     */
    private void syncGroups() {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (int gi = 0; gi < sourceModel.size(); gi++) {
            Source group = sourceModel.get(gi);
            String uid = group.getConfig().uid;
            if (group.getType() != SourceType.GROUP || uid == null) {
                continue;
            }
            live.add(uid);
            List<Source> members = new ArrayList<>();
            for (int i = 0; i < sourceModel.size(); i++) {
                if (uid.equals(sourceModel.get(i).getConfig().groupId)) {
                    members.add(sourceModel.get(i));
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            SourceConfig gc = group.getConfig();
            double[] last = groupRects.get(uid);
            if (last != null && last[2] > 0 && last[3] > 0
                    && (gc.x != last[0] || gc.y != last[1] || gc.w != last[2] || gc.h != last[3])) {
                double sx = gc.w / last[2];
                double sy = gc.h / last[3];
                for (Source m : members) {
                    SourceConfig mc = m.getConfig();
                    mc.x = gc.x + (mc.x - last[0]) * sx;
                    mc.y = gc.y + (mc.y - last[1]) * sy;
                    mc.w *= sx;
                    mc.h *= sy;
                }
            }
            if (last != null && (last[4] == 1) != group.isVisible()) {
                members.forEach(m -> m.setVisible(group.isVisible()));
            }
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for (Source m : members) {
                SourceConfig mc = m.getConfig();
                x0 = Math.min(x0, mc.x);
                y0 = Math.min(y0, mc.y);
                x1 = Math.max(x1, mc.x + mc.w);
                y1 = Math.max(y1, mc.y + mc.h);
            }
            gc.x = x0;
            gc.y = y0;
            gc.w = x1 - x0;
            gc.h = y1 - y0;
            groupRects.put(uid, new double[]{gc.x, gc.y, gc.w, gc.h, group.isVisible() ? 1 : 0});
        }
        groupRects.keySet().retainAll(live);
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
            // the editor's own images move (also when the scene is in neither Preview nor Program); Preview and
            // Program are not changed from the editor (PREV / NEXT under the monitors do that)
            slideshows.controlEditor(s, control);
            preview.repaint();
            updateSlideshowStatus();
        }
    }

    private void updateSlideshowStatus() {
        Source s = sourceList.getSelectedValue();
        if (s == null || s.getType() != SourceType.SLIDESHOW || slideshows == null) {
            slideshowTimer.stop();
            return;
        }
        // the big number is the editor's image (what PREV / NEXT here move); Preview / Program below it when live
        int[] at = slideshows.editorPosition(s);
        var st = slideshows.status(s);
        String live = st == null ? "" : (st.previewPosition() > 0 ? " · PREVIEW " + st.previewPosition() + " / " + st.count() : "")
                + (st.programPosition() > 0 ? " · PROGRAM " + st.programPosition() + " / " + st.count() : "");
        slideshowStatus.setText("<html><center><b style='font-size:13px'>"
                + (at[1] > 0 ? at[0] + " / " + at[1] : "–") + "</b><br>"
                + "<span style='font-size:9px'>" + (at[1] == 0 ? "no images" : "IN EDITOR"
                + (slideshows.editorPlaying(s) ? " · playing" : "")) + live + "</span></center></html>");
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
     * Songs / Lyrics. FROM LIBRARY: whatever lyrics are sent show here, so one scene serves every song. SPECIFIC
     * FILE: a library song (referenced, not copied); only its lyrics show here, whatever other song is clicked.
     */
    private void addSongFromLibrary() {
        if (editing == null || songs == null) {
            return;
        }
        int mode = askLibraryMode("Add Lyrics Source", "lyrics",
                "shows the lyrics of whatever song you send to Preview / TAKE (one scene for every song).",
                "choose one song; only that song's lyrics show here, even when you click another song.", "music");
        if (mode < 0) {
            return;
        }
        if (mode == 0) {
            addNamed(SourceType.LYRICS, "Lyrics (from library)");
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
        s.getConfig().fixedSong = Boolean.TRUE;
        insertSource(s);
        statusHandler.accept("Lyrics source for \"" + song.getTitle() + "\" only: other songs never show in it.");
    }

    /** Audio Input: live sound from any connected input (a microphone, a camera's audio, the audio tech's mixer). */
    private void addAudioInput() {
        if (editing == null) {
            return;
        }
        BackgroundTask.run("Find audio inputs", cameras::detect, found -> { // just plugged in: it is listed too
            List<String> inputs = cameras.audioInputs();
            String input = LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<String>(
                    "Select Audio Input", "Search audio inputs…", "audio",
                    "No audio input detected.<br>Connect a microphone, camera or mixer, then try again.",
                    text -> inputs.stream().filter(d -> d.toLowerCase().contains(text.strip().toLowerCase())).toList(),
                    d -> d, d -> "Sound input", d -> null));
            if (input == null) {
                return;
            }
            Source s = new Source(input, SourceType.AUDIO_INPUT);
            s.getConfig().audioInput = input;
            s.getConfig().x = 0.02;
            s.getConfig().y = 0.02;
            s.getConfig().w = 0.3;
            s.getConfig().h = 0.08; // only a label in the editor; nothing is drawn on the outputs
            insertSource(s);
        }, e -> statusHandler.accept("Could not look for audio inputs: " + e.getMessage()));
    }

    /** A sound-only source: a small label in the editor, nothing on the outputs. */
    private static void placeSoundLabel(Source s) {
        s.getConfig().x = 0.02;
        s.getConfig().y = 0.02;
        s.getConfig().w = 0.3;
        s.getConfig().h = 0.08;
    }

    /** ADD SOURCE → Audio Output Capture: everything the PC plays except this app, into the recording and stream. */
    private void addAudioOutput() {
        if (editing == null) {
            return;
        }
        Source s = new Source("PC Sound (Audio Output)", SourceType.AUDIO_OUTPUT);
        placeSoundLabel(s);
        insertSource(s);
        statusHandler.accept("The PC's sound (other programs) goes into the recording and the stream while this scene is on Program.");
    }

    /**
     * ADD SOURCE → Application Audio Capture: what one program plays (a browser, Zoom…), into the recording and stream.
     * {@code existing} null adds a new source; otherwise that source changes program.
     */
    private void pickApp(Source existing) {
        if (editing == null) {
            return;
        }
        java.util.TreeMap<String, String> programs = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var w : com.jamc.worship.capture.WindowCapture.windows()) {
            if (w.exe() != null) {
                programs.putIfAbsent(w.exe(), w.title()); // a program with an open window, with one of its titles
            }
        }
        List<String> exes = new ArrayList<>(programs.keySet());
        String exe = LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<String>(
                "Select Application", "Search programs…", "audio",
                "No other program with a window is open.<br>Open the program (a browser, Zoom…), then try again.",
                text -> exes.stream().filter(x -> (x + " " + programs.get(x)).toLowerCase().contains(text.strip().toLowerCase())).toList(),
                x -> x, x -> programs.get(x), x -> null));
        if (exe == null) {
            return;
        }
        Source s = existing != null ? existing : new Source("App Sound: " + exe, SourceType.APP_AUDIO);
        s.getConfig().appExe = exe;
        if (existing == null) {
            placeSoundLabel(s);
            insertSource(s);
        } else {
            s.setName("App Sound: " + exe);
            loadProps();
            sceneEdited();
        }
        statusHandler.accept("The sound of " + exe + " goes into the recording and the stream while this scene is on Program.");
    }

    /** Audio Output / Application Audio: what is captured, volume and mute. */
    private void loadCaptureAudioProps(GridBagConstraints g, Source s) {
        SourceConfig c = s.getConfig();
        if (s.getType() == SourceType.APP_AUDIO) {
            JButton change = Ui.button("Change…", "audio");
            change.addActionListener(e -> pickApp(s));
            JPanel row = Ui.transparent(new BorderLayout(6, 0));
            row.add(change, BorderLayout.WEST);
            row.add(Ui.small(c.appExe == null ? "No program chosen" : c.appExe), BorderLayout.CENTER);
            row(g, "Program", row);
        }
        srcVolume.setValue(c.volume == null ? 100 : c.volume);
        srcVolumeLabel.setText(srcVolume.getValue() + "%");
        srcMute.setSelected(Boolean.TRUE.equals(c.muted));
        srcVolume.setEnabled(true);
        srcMute.setEnabled(true);
        JPanel vol = Ui.transparent(new BorderLayout(4, 0));
        vol.add(srcMute, BorderLayout.WEST);
        vol.add(srcVolume, BorderLayout.CENTER);
        vol.add(srcVolumeLabel, BorderLayout.EAST);
        row(g, "Volume", vol);
        row(g, "", Ui.label("<html><body style='width:190px'>" + (s.getType() == SourceType.AUDIO_OUTPUT
                ? "Everything the PC plays (other programs, a browser, Zoom…) except LiveCat, whose own sound is "
                + "recorded already, so nothing is heard twice."
                : "Only what this program plays. If it is closed, its sound comes in by itself once it is opened.")
                + " It goes into the recording and the stream while this scene is on Program; it is not played "
                + "again on the speakers. Needs Windows 10 (2004) or newer.</html>", Palette::mutedText, "small"));
    }

    /** Camera: pick one of the connected devices (detected again first, so a camera just started is listed). */
    /**
     * Window Capture: pick an open window. {@code existing} null adds a new source; otherwise that source
     * switches to the chosen window.
     */
    private void pickWindow(Source existing) {
        if (editing == null) {
            return;
        }
        List<com.jamc.worship.capture.WindowCapture.WindowInfo> windows = com.jamc.worship.capture.WindowCapture.windows();
        var w = LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<com.jamc.worship.capture.WindowCapture.WindowInfo>(
                "Select Window", "Search windows…", "monitor",
                "No open windows found.<br>Open the program to capture (a browser, Zoom, …), then try again.",
                text -> windows.stream().filter(x -> x.label().toLowerCase().contains(text.strip().toLowerCase())).toList(),
                com.jamc.worship.capture.WindowCapture.WindowInfo::title,
                x -> x.exe() == null ? "" : x.exe(), x -> null));
        if (w == null) {
            return;
        }
        Source s = existing != null ? existing : new Source(windowName(w.title()), SourceType.WINDOW_CAPTURE);
        s.getConfig().windowTitle = w.title();
        s.getConfig().windowExe = w.exe();
        if (existing == null) {
            s.getConfig().fit = FitMode.FIT;
            insertSource(s);
        } else {
            s.setName(windowName(w.title()));
            loadProps();
            sceneEdited();
        }
        statusHandler.accept("Capturing \"" + w.title() + "\". Keep the window open (it may be behind others, not minimized).");
    }

    /**
     * Display Capture: pick a monitor. {@code existing} null adds a new source; otherwise that source switches to the
     * chosen monitor. With one monitor it is taken at once.
     */
    private void pickDisplay(Source existing) {
        if (editing == null) {
            return;
        }
        List<com.jamc.worship.capture.DisplayCapture.Display> displays = com.jamc.worship.capture.DisplayCapture.displays();
        if (displays.isEmpty()) {
            Dialogs.showInfo(this, "Display Capture", "No display was found.");
            return;
        }
        com.jamc.worship.capture.DisplayCapture.Display d = displays.size() == 1 && existing == null ? displays.getFirst()
                : LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<com.jamc.worship.capture.DisplayCapture.Display>(
                        "Select Display", "Search displays…", "monitor", "No display was found.",
                        text -> displays.stream().filter(x -> x.label().toLowerCase().contains(text.strip().toLowerCase())).toList(),
                        com.jamc.worship.capture.DisplayCapture.Display::label, x -> x.device(), x -> null));
        if (d == null) {
            return;
        }
        Source s = existing != null ? existing : new Source("Display " + d.number(), SourceType.DISPLAY_CAPTURE);
        s.getConfig().display = d.device();
        if (existing == null) {
            s.getConfig().fit = FitMode.FIT;
            insertSource(s);
        } else {
            s.setName("Display " + d.number());
            loadProps();
            sceneEdited();
        }
        statusHandler.accept("Capturing " + d.label() + ".");
    }

    /** The captured monitor with Change…, and whether the mouse pointer shows. */
    private void loadDisplayProps(GridBagConstraints g, Source s) {
        SourceConfig c = s.getConfig();
        var shown = com.jamc.worship.capture.DisplayCapture.displays().stream()
                .filter(d -> d.device().equals(c.display)).findFirst();
        JLabel what = Ui.small(shown.map(com.jamc.worship.capture.DisplayCapture.Display::label)
                .orElse(c.display == null ? "No display chosen" : c.display + " (not connected)"));
        JButton change = Ui.button("Change…", "monitor");
        change.addActionListener(e -> pickDisplay(s));
        JPanel row = Ui.transparent(new BorderLayout(6, 0));
        row.add(change, BorderLayout.WEST);
        row.add(what, BorderLayout.CENTER);
        row(g, "Display", row);
        JCheckBox cursor = new JCheckBox("Show the mouse pointer", !Boolean.FALSE.equals(c.captureCursor));
        cursor.setOpaque(false);
        cursor.addActionListener(e -> {
            c.captureCursor = cursor.isSelected() ? null : Boolean.FALSE;
            sceneEdited();
        });
        row(g, "Pointer", cursor);
        row(g, "", Ui.label("<html>The whole monitor, live. Capturing the monitor the projector is on shows the "
                + "picture inside itself, as in OBS.</html>", Palette::mutedText, "small"));
    }

    private static String windowName(String title) {
        String t = title.length() > 40 ? title.substring(0, 39) + "…" : title;
        return "Window: " + t;
    }

    /** Browser: ask for a web address. {@code existing} null adds a new source; otherwise its address changes. */
    private void askUrl(Source existing) {
        if (editing == null) {
            return;
        }
        String current = existing == null ? "https://" : existing.getConfig().url;
        Object answer = javax.swing.JOptionPane.showInputDialog(this,
                "Web address to show (e.g. a countdown or an online overlay):", "Browser Source",
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, current);
        if (answer == null || answer.toString().isBlank() || answer.toString().strip().equals("https://")) {
            return;
        }
        String url = com.jamc.worship.capture.BrowserSources.normalize(answer.toString());
        Source s = existing != null ? existing : new Source("Browser", SourceType.BROWSER);
        s.getConfig().url = url;
        String host = url.replaceFirst("(?i)^[a-z]+://", "").replaceFirst("/.*$", "");
        if (existing == null) {
            s.setName("Browser: " + host);
            s.getConfig().fit = FitMode.FIT;
            insertSource(s);
        } else {
            loadProps();
            sceneEdited();
        }
        statusHandler.accept("Loading " + url + " (the first time the browser engine may need a moment to start).");
    }

    private void addCameraSource() {
        if (editing == null) {
            return;
        }
        statusHandler.accept("Looking for cameras…");
        BackgroundTask.run("Detect cameras", cameras::detect, this::pickCamera,
                e -> pickCamera(cameras.cameras()));
    }

    private void pickCamera(List<String> devices) {
        if (editing == null) {
            return;
        }
        java.util.function.Function<String, String> label = d -> cameraNames == null ? d : cameraNames.full(d);
        String cam = LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<String>(
                "Select Camera", "Search cameras…", "video",
                "No camera detected.<br>Connect a webcam or HDMI capture device, then open Library → Camera to detect it.",
                text -> devices.stream().filter(d -> label.apply(d).toLowerCase().contains(text.strip().toLowerCase())).toList(),
                label::apply, d -> d + " · " + cameras.status(d).label(), d -> null));
        if (cam == null) {
            return;
        }
        insertCameraSource(cam);
    }

    /**
     * NDI: a video source sent over the network (another computer's NDI output, a phone NDI camera app, a PTZ
     * camera, OBS with NDI). It is received like a camera, so it has the camera's fit, crop and audio options.
     */
    private void addNdiSource() {
        if (editing == null) {
            return;
        }
        if (!com.jamc.worship.camera.Ndi.isInstalled()) {
            Dialogs.showInfo(this, "NDI not installed",
                    "NDI needs the free NDI Runtime (it comes with NDI Tools from ndi.video).\n"
                            + "Install it, then choose Add Source → NDI… again.");
            return;
        }
        statusHandler.accept("Looking for NDI sources on the network…");
        BackgroundTask.run("Find NDI sources", com.jamc.worship.camera.Ndi::sources, found -> {
            if (editing == null) {
                return;
            }
            String problem = com.jamc.worship.camera.Ndi.problem();
            String cam = LibraryPickerDialog.pick(this, new LibraryPickerDialog.Spec<String>(
                    "Select NDI Source", "Search NDI sources…", "video",
                    problem != null ? problem
                            : "No NDI source found.<br>Check that the sending computer or phone app is on the same network "
                            + "and sending NDI, then try again.",
                    text -> found.stream().filter(d -> d.toLowerCase().contains(text.strip().toLowerCase())).toList(),
                    d -> d.substring(com.jamc.worship.camera.Ndi.PREFIX.length()), d -> "NDI · " + cameras.status(d).label(),
                    d -> null));
            if (cam != null) {
                insertCameraSource(cam);
            }
        }, err -> Dialogs.showError(this, "Could not look for NDI sources.", err));
    }

    /**
     * An "Auto Director" source: a full-screen camera box whose picture the AI Auto Director chooses. It is a box of the
     * director's main camera (its sound, when switched on, is that camera's), cut out and switched by the director.
     */
    private void addAutoDirectorSource() {
        if (editing == null) {
            return;
        }
        String cam = com.jamc.worship.director.VirtualCameras.mainCamera();
        if (cam == null) {
            List<String> all = cameras.cameras();
            cam = all.stream().filter(d -> d.toLowerCase().contains("iriun")).findFirst().orElse(all.isEmpty() ? null : all.get(0));
        }
        Source s = new Source("Auto Director", SourceType.CAMERA);
        s.getConfig().camera = cam;
        s.getConfig().fit = FitMode.FILL;
        s.getConfig().autoDirector = Boolean.TRUE;
        insertSource(s);
        statusHandler.accept("Auto Director source added. Turn it on and choose the shots in Library → Camera → AUTO DIRECTOR.");
    }

    /** A Camera source for this camera (a device or an NDI source); a second camera starts picture-in-picture. */
    private void insertCameraSource(String cam) {
        Source s = new Source(cameraNames == null ? cam : cameraNames.label(cam), SourceType.CAMERA);
        s.getConfig().camera = cam;
        s.getConfig().fit = FitMode.FILL;
        // a second camera in the scene starts as picture-in-picture (bottom right) over the first
        if (editing.getSources().stream().anyMatch(x -> x.getType() == SourceType.CAMERA)) {
            s.getConfig().x = 0.68;
            s.getConfig().y = 0.64;
            s.getConfig().w = 0.28;
            s.getConfig().h = 0.28;
        }
        // camera audio stays off: switched on per source, so several camera mics never echo
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
                                && i.getType() != com.jamc.worship.model.ServiceItem.Type.SCENE // no scene inside a scene
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
                s.getConfig().fixedSong = ref == null ? null : Boolean.TRUE; // this service song only
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
    /**
     * A playlist's videos, one row each: number, picture and name. The video playing now (on Program, else in
     * Preview) is marked; double-click a row to play that video now.
     */
    private final class PlaylistView extends JComponent {
        private static final int ROW = 58;
        private static final int THUMB_W = 88;
        private final SourceConfig config;
        private final javax.swing.Timer timer = new javax.swing.Timer(500, e -> repaint());
        private final Runnable stillReady = this::repaint;

        PlaylistView(SourceConfig config) {
            this.config = config;
            setToolTipText("Double-click a video to play it now");
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    int i = e.getY() / ROW;
                    if (e.getClickCount() == 2 && i >= 0 && i < config.videoPaths.size()) {
                        var videos = sceneVideos();
                        if (videos == null || !videos.playIndex(config.uid, i)) {
                            statusHandler.accept("Put the scene in Preview or on Program to play its videos.");
                        }
                        repaint();
                    }
                }
            });
        }

        @Override
        public void addNotify() {
            super.addNotify();
            timer.start();
            com.jamc.worship.media.VideoStills.addListener(stillReady);
        }

        @Override
        public void removeNotify() {
            timer.stop();
            com.jamc.worship.media.VideoStills.removeListener(stillReady);
            super.removeNotify();
        }

        private com.jamc.worship.media.SceneVideos sceneVideos() {
            return com.jamc.worship.render.Rendering.frames() instanceof com.jamc.worship.media.MediaHub hub
                    ? hub.sceneVideos() : null;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(260, Math.max(1, config.videoPaths.size()) * ROW);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            var videos = sceneVideos();
            int[] playing = videos == null ? null : videos.playingIndex(config.uid);
            java.awt.Font bold = getFont().deriveFont(java.awt.Font.BOLD, 12f);
            java.awt.Font small = getFont().deriveFont(10f);
            for (int i = 0; i < config.videoPaths.size(); i++) {
                int y = i * ROW;
                boolean now = playing != null && playing[0] == i;
                Color mark = playing != null && playing[1] == 1 ? Palette.live() : Palette.accent();
                g.setColor(now ? Palette.withAlpha(mark, 40) : Palette.secondaryBackground());
                g.fillRoundRect(0, y + 2, getWidth() - 1, ROW - 4, 8, 8);
                if (now) {
                    g.setColor(mark);
                    g.setStroke(new java.awt.BasicStroke(2f));
                    g.drawRoundRect(1, y + 3, getWidth() - 3, ROW - 6, 8, 8);
                }
                g.setFont(bold);
                g.setColor(Palette.secondaryText());
                g.drawString(String.valueOf(i + 1), 8, y + ROW / 2 + 5);
                int th = THUMB_W * 9 / 16;
                int tx = 26;
                int ty = y + (ROW - th) / 2;
                g.setColor(Palette.monitorBackground());
                g.fillRoundRect(tx, ty, THUMB_W, th, 4, 4);
                java.awt.image.BufferedImage still = com.jamc.worship.media.VideoStills.get(media.resolve(config.videoPaths.get(i)));
                if (still != null) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.clip(new java.awt.geom.RoundRectangle2D.Double(tx, ty, THUMB_W, th, 4, 4));
                    g2.drawImage(still, tx, ty, THUMB_W, th, null);
                    g2.dispose();
                }
                int textX = tx + THUMB_W + 8;
                int textW = getWidth() - textX - 6;
                g.setFont(bold);
                g.setColor(Palette.text());
                String name = fileName(config.videoPaths.get(i));
                java.awt.FontMetrics fm = g.getFontMetrics();
                while (fm.stringWidth(name) > textW && name.length() > 4) {
                    name = name.substring(0, name.length() - 2);
                }
                g.drawString(name, textX, y + ROW / 2 - 2);
                g.setFont(small);
                g.setColor(now ? mark : Palette.mutedText());
                g.drawString(now ? (playing[1] == 1 ? "▶ PLAYING ON PROGRAM" : "▶ PLAYING IN PREVIEW") : "double-click to play",
                        textX, y + ROW / 2 + 13);
            }
            g.dispose();
        }
    }

    private static String fileName(String path) {
        if (com.jamc.worship.youtube.YouTubeRef.is(path)) {
            return "YouTube video (" + com.jamc.worship.youtube.YouTubeRef.id(path) + ")";
        }
        if (com.jamc.worship.media.OnlineMedia.isUrl(path)) {
            return "Pixabay video (" + path.substring(path.lastIndexOf('/') + 1) + ")";
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
                "No presentations in the library yet.<br>Click UPLOAD FILE… to add a PowerPoint.",
                presentations::list, Presentation::name, p -> p.slideCountText() + "  ·  " + p.sourceFileName(),
                p -> Rendering.thumbnails().get(presentations.thumbnail(p, 1).toString()))
                .withUpload(com.jamc.worship.ui.dialogs.LibraryUploads.presentations(presentations)));
    }

    /** + ADD SOURCE → Presentation from Library: references the library presentation by id. */
    private void addPresentationFromLibrary() {
        if (editing == null) {
            return;
        }
        int mode = askLibraryMode("Add Presentation Source", "presentation",
                "shows the slide of whatever presentation you send to Preview / TAKE.",
                "choose one presentation; only its slides show here, even when you click another presentation.",
                "presentation");
        if (mode < 0) {
            return;
        }
        Source s;
        if (mode == 0) {
            s = new Source("Presentation (from library)", SourceType.PRESENTATION);
            s.getConfig().presentationMode = SourceConfig.PresentationMode.DYNAMIC;
        } else {
            Presentation p = pickPresentation();
            if (p == null) {
                return;
            }
            s = new Source("Presentation: " + p.name(), SourceType.PRESENTATION);
            s.getConfig().presentationId = p.id();
            s.getConfig().slideIndex = 0;
            s.getConfig().presentationMode = SourceConfig.PresentationMode.FIXED; // only this file, whatever is clicked
            statusHandler.accept("\"" + p.name() + "\" only: other presentations never show in this source.");
        }
        s.getConfig().fit = FitMode.FIT;
        insertSource(s);
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
                "Nothing in the library yet.<br>Click UPLOAD FILE… to add one from this computer.",
                text -> media.find(cats, text, false).stream().filter(m -> m.getKind() == kind).toList(),
                MediaItem::getName,
                m -> (m.isYouTube() ? "YouTube video" : (m.getCategory() == MediaCategory.BACKGROUND ? "Background " : "") + m.getKind().name().toLowerCase())
                        + (m.getWidth() == null ? "" : "  ·  " + m.getWidth() + "×" + m.getHeight())
                        + (m.getDurationMs() == null ? "" : "  ·  " + TransportControls.format(m.getDurationMs())),
                this::thumbnailOf).withUpload(com.jamc.worship.ui.dialogs.LibraryUploads.media(media, kind)));
    }

    /** A picture of a library item: the image itself, a still of a video, YouTube's picture; null for audio. */
    private java.awt.image.BufferedImage thumbnailOf(MediaItem m) {
        if (m == null) {
            return null;
        }
        if (m.getKind() == MediaKind.IMAGE) {
            return Rendering.renderer().images().get(m.getPath());
        }
        if (m.isYouTube()) {
            return com.jamc.worship.youtube.YouTubeLibrary.thumbnail(m.getYoutubeVideoId(), m.getThumbnailUrl());
        }
        if (m.isPixabay()) {
            return com.jamc.worship.pixabay.PixabayThumbnails.get(m.getThumbnailUrl());
        }
        return m.getKind() == MediaKind.VIDEO ? com.jamc.worship.media.VideoStills.get(media.resolve(m)) : null;
    }

    /** The picture of a source's chosen file (image, video still, YouTube picture), for its properties. */
    private java.awt.image.BufferedImage thumbnailOf(SourceConfig c, SourceType t) {
        if (c.path == null) {
            return null;
        }
        if (t == SourceType.IMAGE) {
            return Rendering.renderer().images().get(c.path);
        }
        if (t == SourceType.VIDEO) {
            if (com.jamc.worship.youtube.YouTubeRef.is(c.path)) {
                return com.jamc.worship.youtube.YouTubeLibrary.thumbnail(com.jamc.worship.youtube.YouTubeRef.id(c.path), null);
            }
            if (com.jamc.worship.media.OnlineMedia.isUrl(c.path)) {
                return com.jamc.worship.pixabay.PixabayThumbnails.forVideo(c.path);
            }
            return com.jamc.worship.media.VideoStills.get(media.resolve(c.path));
        }
        return null;
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
        Source removed = sourceModel.remove(i);
        if (removed.getType() == SourceType.GROUP && removed.getConfig().uid != null) {
            // removing a group removes the group only: its layers stay, on their own
            for (int k = 0; k < sourceModel.size(); k++) {
                if (removed.getConfig().uid.equals(sourceModel.get(k).getConfig().groupId)) {
                    sourceModel.get(k).getConfig().groupId = null;
                }
            }
        }
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
        songMode.addActionListener(e -> apply.run());
        for (JSpinner s : List.of(cropLeft, cropTop, cropRight, cropBottom)) {
            s.addChangeListener(e -> apply.run());
        }
        rotationCombo.addActionListener(e -> apply.run());
        mirrorBox.addActionListener(e -> apply.run());
        directorBox.addActionListener(e -> apply.run());
        chromaBox.addActionListener(e -> apply.run());
        chromaColor.addColorListener(col -> apply.run());
        chromaGreen.addActionListener(e -> {
            chromaColor.setColor(new Color(0x00FF00));
            apply.run();
        });
        chromaBlue.addActionListener(e -> {
            chromaColor.setColor(new Color(0x0000FF));
            apply.run();
        });
        chromaSimilarity.addChangeListener(e -> apply.run());
        chromaSmoothness.addChangeListener(e -> apply.run());
        chromaSpill.addActionListener(e -> apply.run());
        offlineCombo.addActionListener(e -> apply.run());
        offlineImageButton.addActionListener(e -> chooseOfflineImage());
        colorButton.addColorListener(c -> apply.run());
        weightCombo.addActionListener(e -> apply.run());
        italicBox.addActionListener(e -> apply.run());
        opacity.addChangeListener(e -> apply.run());
        textPlace.addActionListener(e -> apply.run());
        lyricsLayout.addActionListener(e -> apply.run());
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
        audioInputCombo.addActionListener(e -> apply.run());
        audioListen.addActionListener(e -> apply.run());
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
        fitToCanvas.setToolTipText("Fill the whole 16:9 output (X 0, Y 0, 1920 × 1080). Shortcut: Ctrl+F");
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
        JMenuItem left = menuItem("Left half (split screen)", "grid", () -> setBox(0, 0, 0.5, 1));
        JMenuItem right = menuItem("Right half (split screen)", "grid", () -> setBox(0.5, 0, 0.5, 1));
        JMenuItem reset = menuItem("Reset Position", "refresh", () -> resetPosition.doClick());
        for (JMenuItem i : List.of(fill, center, pip, left, right, reset)) {
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
            c.videoIds = null; // one chosen file: no longer a playlist
            c.videoPaths = null;
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
            audioBox.setEnabled(true); // a camera without an audio input disables it; other sources need it back
            audioOut.setEnabled(true);
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
                // a Video Playlist has its own list of videos and Edit Videos… (its first video alone
                // here, with CHANGE FROM LIBRARY, would only confuse)
                boolean playlist = t == SourceType.VIDEO && c.isPlaylist();
                if (t != SourceType.AUDIO && !playlist) {
                    // a picture of the chosen file (a video as one still): the operator sees what this source shows
                    row(g, "", new MediaThumb(() -> thumbnailOf(c, t), c.path == null ? "None chosen" : null));
                }
                if (!playlist) {
                    row(g, "Media", Ui.label(c.path == null ? "None chosen" : fileName(c.path),
                            Palette::text, "semibold"));
                    row(g, "", selectFromLibrary);
                    selectFromLibrary.setText("CHANGE FROM LIBRARY");
                }
                if (t == SourceType.VIDEO && c.path != null) {
                    row(g, "Editor", editorVideoControls(s));
                }
            }
            if (t == SourceType.LYRICS && c.songId != null) {
                JButton open = Ui.button("OPEN SONG", "music");
                open.setToolTipText("Show this song's slides in Library → Lyrics (the lyrics source shows the slide you send)");
                Long id = c.songId;
                open.addActionListener(e -> openSong.accept(id));
                row(g, "Song", open);
                songMode.setSelectedIndex(c.isFixedSong() ? 1 : 0);
                songMode.setToolTipText("From library: any song you send shows here. This song only: other songs never "
                        + "show here (it keeps this song's last slide).");
                row(g, "Shows", songMode);
            }
            if (t == SourceType.LYRICS || t == SourceType.BIBLE) {
                textPlace.setSelectedIndex(c.textPosition == null ? 0 : c.textPosition.ordinal() + 1);
                row(g, "Text position", textPlace);
                textScale.setValue(c.textScale == null ? 100 : (int) Math.round(c.textScale * 100));
                textScale.setToolTipText("Text size compared with the theme (smaller over a camera)");
                row(g, "Text size %", textScale);
            }
            if (t == SourceType.LYRICS) {
                lyricsLayout.setSelectedIndex(Math.max(0, java.util.Arrays.asList(LAYOUTS).indexOf(c.lyricsLayout))); // unknown / "OFF" = Full lyrics
                lyricsLayout.setToolTipText("<html>How song lyrics look in THIS scene, on Preview and Program (and the stream).<br>"
                        + "Lower third: a bar per line over the camera. Full lyrics: the normal big words.<br>"
                        + "Full lyrics is the default. The bars' font and colors are set in Settings → Lyrics Appearance.<br>"
                        + "The church projector always shows the full lyrics.</html>");
                row(g, "Lyrics layout", lyricsLayout);
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
            if (t == SourceType.CAMERA || t == SourceType.IMAGE || t == SourceType.CHURCH_LOGO || t == SourceType.VIDEO || isCapture(t)
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
            // a Video Playlist chooses its files with Edit Videos… (no single "Choose…" file here)
            if ((t == SourceType.IMAGE || t == SourceType.CHURCH_LOGO || t == SourceType.VIDEO) && !c.isPlaylist()) {
                JPanel f = Ui.transparent(new BorderLayout(6, 0));
                f.add(fileButton, BorderLayout.WEST);
                f.add(fileLabel, BorderLayout.CENTER);
                fileLabel.setText(c.path == null ? "No file chosen" : fileName(c.path));
                fileLabel.setToolTipText(c.path);
                row(g, "File", f);
            }
            if (isCapture(t)) {
                loadCaptureProps(g, s);
            }
            if (t == SourceType.VIDEO && c.isPlaylist()) {
                loadPlaylistProps(g, s);
            }
            if (t == SourceType.SCENE) {
                loadSceneProps(g, s);
            }
            if (t == SourceType.GROUP) {
                loadGroupProps(g, s);
            }
            if (t == SourceType.VIDEO || t == SourceType.AUDIO) {
                loadVideoProps(g, s);
            }
            if (t == SourceType.CAMERA) {
                loadCameraProps(g, s);
            }
            if (t == SourceType.AUDIO_INPUT) {
                loadAudioInputProps(g, c);
            }
            if (t == SourceType.AUDIO_OUTPUT || t == SourceType.APP_AUDIO) {
                loadCaptureAudioProps(g, s);
            }
            if (t == SourceType.TEXT || t == SourceType.CLOCK) {
                textField.setText(c.text == null ? "" : c.text);
                textField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, t == SourceType.CLOCK ? "h:mm a" : "Text");
                row(g, t == SourceType.CLOCK ? "Format" : "Text", textField);
                colorButton.setColor(Colors.parse(c.color, Color.WHITE));
                row(g, "Color", colorButton);
                fontSpin.setValue(c.fontSize == null ? 48 : c.fontSize);
                row(g, "Size", fontSpin);
                weightCombo.setSelectedItem(c.fontWeight == null ? com.jamc.worship.model.FontWeight.BOLD : c.fontWeight);
                weightCombo.setToolTipText("How heavy the letters are (Bold when none is chosen)");
                row(g, "Weight", weightCombo);
                italicBox.setSelected(Boolean.TRUE.equals(c.italic));
                italicBox.setToolTipText("Slanted letters");
                row(g, "Style", italicBox);
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
        // MANUAL NAVIGATION first: Previous / Next move the images HERE in the editor (Preview / Program are not changed)
        JButton prev = Ui.button("PREVIOUS", "chevron-left");
        prev.setToolTipText("Previous image here in the editor (Preview and Program are not changed)");
        prev.getAccessibleContext().setAccessibleName("Previous image");
        prev.addActionListener(e -> slideshowControl(s, com.jamc.worship.output.Slideshows.Control.PREVIOUS));
        JButton next = Ui.primaryButton("NEXT", "chevron-right");
        next.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);
        next.setToolTipText("Next image here in the editor (Preview and Program are not changed)");
        next.getAccessibleContext().setAccessibleName("Next image");
        next.addActionListener(e -> slideshowControl(s, com.jamc.worship.output.Slideshows.Control.NEXT));
        JPanel nav = Ui.transparent(new BorderLayout(8, 0));
        nav.add(prev, BorderLayout.WEST);
        slideshowStatus.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        nav.add(slideshowStatus, BorderLayout.CENTER);
        nav.add(next, BorderLayout.EAST);
        row(g, "Navigate", nav);
        row(g, "", Ui.label("<html>NEXT / PREVIOUS look through the images here in the editor. During the service use "
                + "PREV / NEXT under Preview / Program (or the arrow keys); TAKE shows the image live.</html>", Palette::mutedText, "small"));
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
        autoButtons.add(slideButton(s, "play", "Play (here in the editor)", com.jamc.worship.output.Slideshows.Control.PLAY));
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

    /** AUDIO INPUT: which connected input, where it is heard and how loud. */
    private void loadAudioInputProps(GridBagConstraints g, SourceConfig c) {
        audioInputCombo.removeAllItems();
        List<String> inputs = new ArrayList<>(cameras.audioInputs());
        if (c.audioInput != null && !inputs.contains(c.audioInput)) {
            inputs.add(c.audioInput); // an input that is unplugged now stays chosen
        }
        inputs.forEach(audioInputCombo::addItem);
        audioInputCombo.setSelectedItem(c.audioInput);
        row(g, "Input", audioInputCombo);
        audioListen.setSelected(Boolean.TRUE.equals(c.monitorOnly));
        audioListen.setToolTipText("For checking on headphones. Off: it goes only into the recording and the stream.");
        row(g, "", audioListen);
        srcVolume.setValue(c.volume == null ? 100 : c.volume);
        srcVolumeLabel.setText(srcVolume.getValue() + "%");
        srcMute.setSelected(Boolean.TRUE.equals(c.muted));
        srcVolume.setEnabled(true);
        srcMute.setEnabled(true);
        JPanel vol = Ui.transparent(new BorderLayout(4, 0));
        vol.add(srcMute, BorderLayout.WEST);
        vol.add(srcVolume, BorderLayout.CENTER);
        vol.add(srcVolumeLabel, BorderLayout.EAST);
        row(g, "Volume", vol);
        row(g, "", Ui.label("<html><body style='width:190px'>Goes into the recording and the stream with this scene, not "
                + "the speakers, so it does not echo. The wave in the MIXER shows sound is coming in.</html>",
                Palette::mutedText, "small"));
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
        if (com.jamc.worship.media.OnlineMedia.isUrl(path)) {
            audioStatus.setText("Pixabay video: streams from Pixabay (needs the internet); background videos are usually silent");
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
                || t == SourceType.SLIDESHOW || t == SourceType.SCENE || isCapture(t);
    }

    /**
     * Layers that resize as a whole when a handle is dragged (Shift + drag stretches one side): pictures, and the
     * lyrics / Bible words, whose size follows the box, so a bigger box (even past the scene's edges) gives bigger words.
     */
    private static boolean growsAsWhole(SourceType t) {
        return keepsAspect(t) || t == SourceType.LYRICS || t == SourceType.BIBLE;
    }

    /** Window / Display Capture and Browser: live pictures from outside the app. */
    private static boolean isCapture(SourceType t) {
        return t == SourceType.WINDOW_CAPTURE || t == SourceType.BROWSER || t == SourceType.DISPLAY_CAPTURE;
    }

    /** The window or web address of a Window Capture / Browser source, with Change… (and Reload for pages). */
    private void loadCaptureProps(GridBagConstraints g, Source s) {
        SourceConfig c = s.getConfig();
        if (s.getType() == SourceType.DISPLAY_CAPTURE) {
            loadDisplayProps(g, s);
            return;
        }
        boolean window = s.getType() == SourceType.WINDOW_CAPTURE;
        JLabel what = Ui.small(window
                ? (c.windowTitle == null ? "No window chosen" : c.windowTitle + (c.windowExe == null ? "" : "  ·  " + c.windowExe))
                : (c.url == null ? "No address" : c.url));
        what.setToolTipText(what.getText());
        JButton change = Ui.button("Change…", window ? "monitor" : "edit");
        change.addActionListener(e -> {
            if (window) {
                pickWindow(s);
            } else {
                askUrl(s);
            }
        });
        JPanel row = Ui.transparent(new BorderLayout(6, 0));
        row.add(change, BorderLayout.WEST);
        row.add(what, BorderLayout.CENTER);
        if (!window) {
            JButton reload = Ui.toolButton("refresh", "Load the page again");
            reload.addActionListener(e -> com.jamc.worship.capture.BrowserSources.reload(c.url));
            row.add(reload, BorderLayout.EAST);
        }
        row(g, window ? "Window" : "Address", row);
        row(g, "", Ui.label(window
                ? "<html>Keep the window open; it may be behind other windows, but not minimized. If its title changes, "
                        + "the same program's window is used.</html>"
                : "<html>The page runs in a hidden browser at 1920 × 1080. Its sound plays on the Windows default "
                        + "output.</html>", Palette::mutedText, "small"));
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
        modeCombo.setToolTipText("From library (dynamic): any presentation slide you send to Preview shows here (one Sermon "
                + "scene for every sermon). Specific file only: only this presentation, whatever else you click.");
        row(g, "Source mode", modeCombo);
        if (!c.isDynamicPresentation() && editing != null && editing.getSources().stream().anyMatch(o -> o != s
                && o.isVisible() && o.getType() == SourceType.PRESENTATION && o.getConfig().isDynamicPresentation())) {
            row(g, "", Ui.label("<html><body style='width:190px'>This scene also has a FROM LIBRARY presentation source: "
                    + "other presentations still show in that one. Remove it or set it to Specific file only.</html>",
                    Palette::warning, "small"));
        }
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
                ? "A slide double-clicked in Library → Presentations (Preview, then TAKE) shows here; the scene itself stays. "
                + "The slide above is shown while no presentation is being sent."
                : "Only this presentation. Its slides sent from the library show here; other presentations never do "
                + "(this box keeps its last slide).";
        row(g, "", Ui.label("<html><body style='width:190px'>" + help + "</html>", Palette::mutedText, "small"));
    }

    /**
     * The scene editor's video controls, like PROGRAM MEDIA: play / pause / stop, ±10 s and a seek bar whose time runs
     * ("VIDEO 2 / 3" above it for a playlist). Silent, its own player: Preview and Program are not affected. PLAY after
     * the end starts again from the beginning (a playlist: from its first video).
     */
    private JComponent editorVideoControls(Source s) {
        if (!(Rendering.frames() instanceof com.jamc.worship.media.MediaHub hub)) {
            return editorPlayButton(s); // no media engine: the simple PLAY / PAUSE
        }
        var videos = hub.sceneVideos();
        TransportControls controls = new TransportControls(true);
        controls.setSoundControlsVisible(false); // the editor is silent
        controls.bind(videos.editorTransport(s));
        JPanel box = Ui.transparent(new BorderLayout(0, 2));
        if (s.getConfig().isPlaylist()) {
            JLabel which = Ui.label(" ", Palette::secondaryText, "small");
            Timer follow = new Timer(300, ev -> {
                if (!which.isDisplayable()) {
                    ((Timer) ev.getSource()).stop();
                    return;
                }
                String text = videos.editorPlaylistSummary(s);
                which.setText(text == null ? "VIDEO 1 / " + s.getConfig().videoPaths.size() : text);
                preview.repaint();
            });
            follow.start();
            box.add(which, BorderLayout.NORTH);
        }
        box.add(controls, BorderLayout.CENTER);
        box.setToolTipText("Plays here in the editor only (silent). Preview and Program are not affected.");
        return box;
    }

    /**
     * PLAY / PAUSE of a video in the scene editor (paused by default). Only the editor's own silent player moves:
     * Preview and Program, and how the video plays when the scene goes live, are not changed.
     */
    private JButton editorPlayButton(Source s) {
        JButton b = Ui.button("PLAY", "play");
        b.setToolTipText("Play or pause this video here in the editor (silent). Preview and Program are not affected.");
        Runnable show = () -> {
            boolean playing = Rendering.frames().editorVideoPlaying(s);
            b.setText(playing ? "PAUSE" : "PLAY");
            b.setIcon(Icons.get(playing ? "pause" : "play", Icons.SMALL));
        };
        b.addActionListener(e -> {
            Rendering.frames().setEditorVideoPlaying(s, !Rendering.frames().editorVideoPlaying(s));
            preview.repaint();
            // VLC changes state a moment later; the button follows (also when a video that does not loop ends)
            Timer t = new Timer(300, ev -> show.run());
            t.setRepeats(false);
            t.start();
        });
        // keeps the button right while it is on screen (e.g. the video reached its end)
        Timer follow = new Timer(1000, ev -> {
            if (b.isShowing()) {
                show.run();
            } else if (!b.isDisplayable()) {
                ((Timer) ev.getSource()).stop();
            }
        });
        follow.start();
        show.run();
        return b;
    }

    /**
     * A source's chosen file as a picture (16:9, the image fitted inside): an image itself, a video as one still
     * (never playing), a YouTube video's picture. Shown again once a still or picture has loaded.
     */
    private static final class MediaThumb extends JComponent {
        private final java.util.function.Supplier<java.awt.image.BufferedImage> picture;
        private final String emptyText;
        private final Runnable redraw = this::repaint;

        MediaThumb(java.util.function.Supplier<java.awt.image.BufferedImage> picture, String emptyText) {
            this.picture = picture;
            this.emptyText = emptyText;
            setPreferredSize(new Dimension(192, 108));
            setMinimumSize(new Dimension(120, 68));
        }

        @Override
        public void addNotify() {
            super.addNotify();
            com.jamc.worship.media.VideoStills.addListener(redraw);
            com.jamc.worship.youtube.YouTubeLibrary.addListener(redraw);
            Rendering.renderer().images().addChangeListener(redraw);
        }

        @Override
        public void removeNotify() {
            com.jamc.worship.media.VideoStills.removeListener(redraw);
            com.jamc.worship.youtube.YouTubeLibrary.removeListener(redraw);
            Rendering.renderer().images().removeChangeListener(redraw);
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = Math.min(getWidth(), (int) Math.round(getHeight() * 16 / 9.0));
            int h = (int) Math.round(w * 9 / 16.0);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            java.awt.image.BufferedImage img = picture.get();
            if (img != null) {
                double sc = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
                int iw = (int) Math.round(img.getWidth() * sc);
                int ih = (int) Math.round(img.getHeight() * sc);
                g.drawImage(img, (w - iw) / 2, (h - ih) / 2, iw, ih, null);
            } else {
                String text = emptyText != null ? emptyText : "Loading picture…";
                g.setColor(Palette.mutedText());
                g.setFont(getFont().deriveFont(11f));
                java.awt.FontMetrics fm = g.getFontMetrics();
                g.drawString(text, (w - fm.stringWidth(text)) / 2, h / 2 + fm.getAscent() / 2);
            }
            g.setColor(Palette.border());
            g.drawRect(0, 0, w - 1, h - 1);
            g.dispose();
        }
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
        if (t == SourceType.SCENE) {
            c.opacity = opacity.getValue() >= 100 ? null : opacity.getValue() / 100.0;
        }
        if (t == SourceType.CAMERA || t == SourceType.IMAGE || t == SourceType.CHURCH_LOGO || t == SourceType.VIDEO || isCapture(t)
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
            if (t == SourceType.LYRICS && c.songId != null) {
                c.fixedSong = songMode.getSelectedIndex() == 1 ? Boolean.TRUE : null;
            }
            if (t == SourceType.LYRICS) {
                c.lyricsLayout = LAYOUTS[Math.max(0, lyricsLayout.getSelectedIndex())];
            }
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
            applyCameraProps(c);
        }
        if (t == SourceType.AUDIO_INPUT) {
            String input = (String) audioInputCombo.getSelectedItem();
            if (input != null) {
                c.audioInput = input;
            }
            c.monitorOnly = audioListen.isSelected() ? Boolean.TRUE : null;
            c.volume = srcVolume.getValue() == 100 ? null : srcVolume.getValue();
            c.muted = srcMute.isSelected() ? Boolean.TRUE : null;
        }
        if (t == SourceType.AUDIO_OUTPUT || t == SourceType.APP_AUDIO) {
            c.volume = srcVolume.getValue() == 100 ? null : srcVolume.getValue();
            c.muted = srcMute.isSelected() ? Boolean.TRUE : null;
        }
        if (t == SourceType.TEXT || t == SourceType.CLOCK) {
            c.text = textField.getText().isBlank() ? null : textField.getText();
            c.color = colorButton.getHex();
            c.fontSize = ((Number) fontSpin.getValue()).intValue();
            com.jamc.worship.model.FontWeight w = (com.jamc.worship.model.FontWeight) weightCombo.getSelectedItem();
            c.fontWeight = w == null || w == com.jamc.worship.model.FontWeight.BOLD ? null : w; // Bold = the default (null)
            c.italic = italicBox.isSelected() ? Boolean.TRUE : null;
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
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, this) != JFileChooser.APPROVE_OPTION) {
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

    private static JSpinner percent() {
        JSpinner s = new JSpinner(new SpinnerNumberModel(0, 0, 45, 1));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(2);
        return s;
    }

    /** Camera numbers and nicknames ("CAM 2 · PREACHER CAMERA") for the camera pickers. */
    public void setCameraNames(com.jamc.worship.camera.CameraNames names) {
        this.cameraNames = names;
        cameraCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                String device = v == null ? null : v.toString();
                String text = device == null || FIRST_CAMERA.equals(device) ? FIRST_CAMERA : names.full(device);
                return super.getListCellRendererComponent(l, text, i, sel, f);
            }
        });
    }

    private static final String FIRST_CAMERA = "First available camera";

    /** CAMERA source: device, its live state, crop, rotation, mirror, sound and what shows while it is offline. */
    private void loadCameraProps(GridBagConstraints g, Source s) {
        SourceConfig c = s.getConfig();
        cameraCombo.removeAllItems();
        cameraCombo.addItem(FIRST_CAMERA);
        List<String> devices = new ArrayList<>(cameras.cameras());
        if (c.camera != null && !devices.contains(c.camera)) {
            devices.add(c.camera); // a camera that is unplugged now stays chosen (it shows DISCONNECTED)
        }
        devices.forEach(cameraCombo::addItem);
        cameraCombo.setSelectedItem(c.camera == null ? FIRST_CAMERA : c.camera);
        cameraCombo.setToolTipText("The physical camera this source shows. Each camera is opened once and shared by every "
                + "scene, so switching scenes never restarts it.");
        row(g, "Camera", cameraCombo);
        com.jamc.worship.camera.CameraStatus st = cameras.status(c.camera);
        String size = cameras.liveSize(c.camera);
        int fps = cameras.liveFps(c.camera);
        String state = st.label() + (size == null ? "" : " · " + size) + (fps > 0 ? " · " + fps + " FPS" : "");
        JLabel stateLabel = Ui.label(state, st.isOffline() ? Palette::warning : Palette::text, "semibold");
        stateLabel.setToolTipText(cameras.problem(c.camera));
        row(g, "Status", stateLabel);
        row(g, "", Ui.label("<html><body style='width:190px'>Resolution and frame rate belong to the camera itself "
                + "(Library → Camera → Configure), so every scene shows the same picture.</html>", Palette::mutedText, "small"));
        if (cameras.cameras().isEmpty()) {
            row(g, "", Ui.label("No camera detected.", Palette::warning, "small"));
        }
        JPanel crop = Ui.transparent(new java.awt.GridLayout(2, 4, 4, 4));
        crop.add(Ui.small("Left"));
        crop.add(cropLeft);
        crop.add(Ui.small("Right"));
        crop.add(cropRight);
        crop.add(Ui.small("Top"));
        crop.add(cropTop);
        crop.add(Ui.small("Bottom"));
        crop.add(cropBottom);
        cropLeft.setValue(pct(c.cropLeft));
        cropTop.setValue(pct(c.cropTop));
        cropRight.setValue(pct(c.cropRight));
        cropBottom.setValue(pct(c.cropBottom));
        crop.setToolTipText("Cut off part of the picture at each edge (% of the camera picture)");
        row(g, "Crop %", crop);
        int rot = c.rotation == null ? 0 : Math.floorMod(c.rotation, 360) / 90;
        rotationCombo.setSelectedIndex(Math.min(3, rot));
        row(g, "Rotation", rotationCombo);
        mirrorBox.setSelected(Boolean.TRUE.equals(c.mirror));
        row(g, "", mirrorBox);
        // AUTO DIRECTOR: by default a box of the director's main camera is controlled; a plain PiP can opt out
        String main = com.jamc.worship.director.VirtualCameras.mainCamera();
        boolean mainCam = main != null && main.equals(c.camera);
        directorBox.setSelected(Boolean.TRUE.equals(c.autoDirector) || c.autoDirector == null && mainCam);
        directorBox.setToolTipText("<html>On: the AI Auto Director chooses this box's picture (its shots, zoom and cameras).<br>"
                + "Off: a plain camera that always shows the whole picture (e.g. a picture-in-picture).</html>");
        row(g, "", directorBox);
        // CHROMA KEY: NDI from other programs (graphics, a presenter on a green screen) often comes on green
        boolean keyed = Boolean.TRUE.equals(c.chromaKey);
        chromaBox.setSelected(keyed);
        chromaBox.setToolTipText("<html>The key color (green) becomes transparent, so the layers under this one show "
                + "through.<br>For NDI from other programs and green screens.</html>");
        row(g, "Chroma key", chromaBox);
        chromaColor.setColor(new Color(c.chromaKeyColor == null ? 0x00FF00 : c.chromaKeyColor & 0xFFFFFF));
        chromaSimilarity.setValue(c.chromaSimilarity == null ? 40 : Math.clamp(c.chromaSimilarity, 1, 100));
        chromaSmoothness.setValue(c.chromaSmoothness == null ? 8 : Math.clamp(c.chromaSmoothness, 0, 100));
        chromaSpill.setSelected(!Boolean.FALSE.equals(c.chromaSpill));
        if (keyed) {
            JPanel colors = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
            chromaGreen.setFocusable(false);
            chromaBlue.setFocusable(false);
            colors.add(chromaColor);
            colors.add(chromaGreen);
            colors.add(chromaBlue);
            row(g, "Key color", colors);
            chromaSimilarity.setToolTipText("How close to the key color is removed (higher removes more; too high eats into the picture)");
            chromaSmoothness.setToolTipText("How soft the edge is after that");
            row(g, "Similarity %", chromaSimilarity);
            row(g, "Smoothness %", chromaSmoothness);
            row(g, "", chromaSpill);
        }
        // AUDIO: off by default; one camera mic at a time (several give echo and feedback)
        String input = cameras.audioDevice(c.camera);
        audioBox.setSelected(Boolean.TRUE.equals(c.audioEnabled));
        audioBox.setEnabled(input != null || Boolean.TRUE.equals(c.audioEnabled));
        audioBox.setToolTipText("Add this camera's own microphone / HDMI sound to the recording and the stream (not the "
                + "speakers). Off by default: the church mixer is the main sound, and several camera mics together cause echo.");
        row(g, "Audio", audioBox);
        row(g, "", Ui.label("<html><body style='width:190px'>" + (input == null
                ? "No audio input found for this camera." : "Input: " + input) + "</html>", Palette::mutedText, "small"));
        srcMute.setSelected(Boolean.TRUE.equals(c.muted));
        srcVolume.setValue(c.volume == null ? 100 : c.volume);
        srcVolumeLabel.setText(srcVolume.getValue() + "%");
        JPanel vol = Ui.transparent(new BorderLayout(6, 0));
        vol.add(srcVolume, BorderLayout.CENTER);
        vol.add(srcVolumeLabel, BorderLayout.EAST);
        row(g, "Volume", vol);
        row(g, "", srcMute);
        audioOut.setSelectedIndex(Boolean.TRUE.equals(c.monitorOnly) ? 1 : 0);
        row(g, "Monitoring", audioOut);
        boolean on = audioBox.isSelected();
        srcVolume.setEnabled(on);
        srcMute.setEnabled(on);
        audioOut.setEnabled(on);
        // OFFLINE: what the audience sees while the camera is unplugged or failed (never an error message)
        offlineCombo.setSelectedIndex("BLACK".equals(c.offline) ? 1 : "IMAGE".equals(c.offline) ? 2 : 0);
        row(g, "When offline", offlineCombo);
        if ("IMAGE".equals(c.offline)) {
            JPanel f = Ui.transparent(new BorderLayout(6, 0));
            f.add(offlineImageButton, BorderLayout.WEST);
            f.add(Ui.small(c.offlineImage == null ? "No image" : fileName(c.offlineImage)), BorderLayout.CENTER);
            row(g, "", f);
        }
    }

    private static int pct(Double v) {
        return v == null ? 0 : (int) Math.round(Math.clamp(v, 0.0, 0.45) * 100);
    }

    private static Double fraction(JSpinner s) {
        int v = ((Number) s.getValue()).intValue();
        return v <= 0 ? null : v / 100.0;
    }

    private void applyCameraProps(SourceConfig c) {
        Object cam = cameraCombo.getSelectedItem();
        c.camera = cam == null || cameraCombo.getSelectedIndex() == 0 ? null : cam.toString();
        c.cropLeft = fraction(cropLeft);
        c.cropTop = fraction(cropTop);
        c.cropRight = fraction(cropRight);
        c.cropBottom = fraction(cropBottom);
        c.rotation = rotationCombo.getSelectedIndex() <= 0 ? null : rotationCombo.getSelectedIndex() * 90;
        c.mirror = mirrorBox.isSelected() ? Boolean.TRUE : null;
        // stored only when it differs from the default (a box of the main camera is controlled), so old scenes keep working
        String mainCamera = com.jamc.worship.director.VirtualCameras.mainCamera();
        boolean byDefault = mainCamera != null && mainCamera.equals(c.camera);
        c.autoDirector = directorBox.isSelected() == byDefault ? null : directorBox.isSelected();
        boolean wasKeyed = Boolean.TRUE.equals(c.chromaKey);
        c.chromaKey = chromaBox.isSelected() ? Boolean.TRUE : null;
        if (wasKeyed) { // the key settings are on screen only while the key is on
            int rgb = chromaColor.getColor().getRGB() & 0xFFFFFF;
            c.chromaKeyColor = rgb == 0x00FF00 ? null : rgb;
            int sim = ((Number) chromaSimilarity.getValue()).intValue();
            int smooth = ((Number) chromaSmoothness.getValue()).intValue();
            c.chromaSimilarity = sim == 40 ? null : sim;
            c.chromaSmoothness = smooth == 8 ? null : smooth;
            c.chromaSpill = chromaSpill.isSelected() ? null : Boolean.FALSE;
        }
        if (wasKeyed != chromaBox.isSelected()) {
            javax.swing.SwingUtilities.invokeLater(this::loadProps); // show / hide the key settings
        }
        c.audioEnabled = audioBox.isSelected() ? Boolean.TRUE : null;
        c.muted = srcMute.isSelected() ? Boolean.TRUE : null;
        c.volume = srcVolume.getValue() == 100 ? null : srcVolume.getValue();
        c.monitorOnly = audioOut.getSelectedIndex() == 1 ? Boolean.TRUE : null;
        srcVolume.setEnabled(audioBox.isSelected());
        srcMute.setEnabled(audioBox.isSelected());
        audioOut.setEnabled(audioBox.isSelected());
        String offline = switch (offlineCombo.getSelectedIndex()) {
            case 1 -> "BLACK";
            case 2 -> "IMAGE";
            default -> null;
        };
        boolean reload = !Objects.equals(offline, c.offline);
        c.offline = offline;
        if (reload) {
            javax.swing.SwingUtilities.invokeLater(this::loadProps); // show / hide the image chooser
        }
    }

    private void chooseOfflineImage() {
        Source s = sourceList.getSelectedValue();
        if (s == null || s.getType() != SourceType.CAMERA) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        com.jamc.worship.ui.MediaChoosers.imageFilters(chooser);
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.nio.file.Path file = chooser.getSelectedFile().toPath();
        BackgroundTask.run("Import offline image", () -> importer.importImage(file), stored -> {
            s.getConfig().offlineImage = stored;
            loadProps();
            sceneEdited();
        }, err -> Dialogs.showError(this, "The image could not be added.", err));
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
        /** While moving: the layer's center sits on the scene's center line (snapped; the guide lights up). */
        private boolean snapX;
        private boolean snapY;

        ScenePreview() {
            setPreferredSize(new Dimension(480, 270));
            setToolTipText("<html>Click a layer to select it. Drag to move; drag the small squares to resize (a picture grows as a "
                    + "whole; Shift + drag stretches one side).<br>Layers may go past the edges. Right-click for Fill Scene.</html>");
            compositor.setEditorMode(true); // empty or missing Presentation sources show a placeholder here only
            compositor.setProgramLyrics(true); // Edit Scene shows the Lyrics layout chosen for this scene
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
                    dragged(e.getX(), e.getY(), e.isShiftDown());
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        popup(e);
                        return;
                    }
                    if (mode >= 0) {
                        mode = -1;
                        snapX = false;
                        snapY = false;
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

        private void dragged(int x, int y, boolean free) {
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
            // a layer may go PAST the edges of the scene (bigger than the screen, or partly off it, e.g. to crop a
            // camera): edges between -1 and 2 scene widths and sizes up to 2x, the same range as X / Y / W / H
            double lo = -1;
            double hi = 2;
            double maxSize = 2;
            snapX = false;
            snapY = false;
            if (mode == 0) {
                // a moved layer keeps a little of itself on screen, so it can always be grabbed again
                bx = clampSafe(bx + dx, Math.max(lo, 0.02 - bw), Math.min(hi - bw, 0.98));
                by = clampSafe(by + dy, Math.max(lo, 0.02 - bh), Math.min(hi - bh, 0.98));
                // CENTER GUIDES: near the middle it snaps to the exact center (the cross lights up)
                double snap = 8.0 / Math.max(1, frame.width);
                if (Math.abs(bx + bw / 2 - 0.5) < snap) {
                    bx = 0.5 - bw / 2;
                    snapX = true;
                }
                if (Math.abs(by + bh / 2 - 0.5) < snap * 16 / 9.0) {
                    by = 0.5 - bh / 2;
                    snapY = true;
                }
            } else {
                if (mode == 1 || mode == 7 || mode == 8) { // left edge
                    double nx = clampSafe(bx + dx, Math.max(lo, bx + bw - maxSize), bx + bw - min);
                    bw = bw + (bx - nx);
                    bx = nx;
                }
                if (mode == 3 || mode == 4 || mode == 5) { // right edge
                    bw = clampSafe(bw + dx, min, Math.min(maxSize, hi - bx));
                }
                if (mode == 1 || mode == 2 || mode == 3) { // top edge
                    double ny = clampSafe(by + dy, Math.max(lo, by + bh - maxSize), by + bh - min);
                    bh = bh + (by - ny);
                    by = ny;
                }
                if (mode == 5 || mode == 6 || mode == 7) { // bottom edge
                    bh = clampSafe(bh + dy, min, Math.min(maxSize, hi - by));
                }
                if (keepsAspect(s.getType()) && aspectOn(s)) {
                    // keep 16:9 (equal fractions of the 16:9 frame): the edge being dragged decides the size
                    double size = mode == 2 || mode == 6 ? bh : bw;
                    boolean fromLeft = mode == 1 || mode == 7 || mode == 8;
                    boolean fromTop = mode == 1 || mode == 2 || mode == 3;
                    double right = bx + bw;
                    double bottom = by + bh;
                    size = clampSafe(size, min, Math.max(min, Math.min(maxSize,
                            Math.min(fromLeft ? right - lo : hi - bx, fromTop ? bottom - lo : hi - by))));
                    if (fromLeft) {
                        bx = right - size;
                    }
                    if (fromTop) {
                        by = bottom - size;
                    }
                    bw = size;
                    bh = size;
                } else if (growsAsWhole(s.getType()) && !free) {
                    // a PICTURE layer (image, video, camera, slideshow…) grows and shrinks as a whole: width and height
                    // together, in the box's own shape, so the picture really gets bigger (a Fit picture in a box made
                    // only wider would stay the same size). Shift + drag stretches one side only.
                    double sw = startBox[2];
                    double sh = startBox[3];
                    double scale = mode == 2 || mode == 6 ? bh / sh : mode == 4 || mode == 8 ? bw / sw
                            : Math.max(bw / sw, bh / sh); // a corner: whichever way the mouse went further
                    scale = clampSafe(scale, Math.max(min / sw, min / sh), Math.min(maxSize / sw, maxSize / sh));
                    double nw = sw * scale;
                    double nh = sh * scale;
                    boolean fromLeft = mode == 1 || mode == 7 || mode == 8;
                    boolean fromTop = mode == 1 || mode == 2 || mode == 3;
                    double right = startBox[0] + sw;
                    double bottom = startBox[1] + sh;
                    // the opposite corner (or the opposite side's middle) stays where it was
                    bx = fromLeft ? right - nw : mode == 2 || mode == 6 ? startBox[0] + (sw - nw) / 2 : startBox[0];
                    by = fromTop ? bottom - nh : mode == 4 || mode == 8 ? startBox[1] + (sh - nh) / 2 : startBox[1];
                    bx = clampSafe(bx, lo, hi - nw); // still within the range X / Y can hold
                    by = clampSafe(by, lo, hi - nh);
                    bw = nw;
                    bh = nh;
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

        /** Like Math.clamp, but never throws when a box typed far outside the scene leaves no room (lo wins). */
        private static double clampSafe(double v, double lo, double hi) {
            return hi < lo ? lo : Math.clamp(v, lo, hi);
        }

        private static final Color GUIDE = new Color(255, 255, 255, 70);
        private static final Color GUIDE_ON = new Color(0x22C55E);

        /**
         * CENTER GUIDES: a thin dashed cross through the middle of the scene (editor only, never on the outputs).
         * A line turns green and solid when the selected layer's center is exactly on it.
         */
        private void paintCenterGuides(Graphics2D g, Rectangle r) {
            if (editing == null) {
                return;
            }
            Source sel = sourceList.getSelectedValue();
            boolean onX = snapX;
            boolean onY = snapY;
            if (sel != null && !onX && !onY) {
                SourceConfig c = sel.getConfig();
                onX = c.w < 0.98 && Math.abs(c.x + c.w / 2 - 0.5) < 0.0015; // a full-width layer is always "centered"
                onY = c.h < 0.98 && Math.abs(c.y + c.h / 2 - 0.5) < 0.0015;
            }
            int cx = r.x + r.width / 2;
            int cy = r.y + r.height / 2;
            java.awt.Stroke dashed = new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER,
                    10f, new float[]{5f, 5f}, 0f);
            java.awt.Stroke solid = new java.awt.BasicStroke(1.5f);
            g.setStroke(onX ? solid : dashed);
            g.setColor(onX ? GUIDE_ON : GUIDE);
            g.drawLine(cx, r.y, cx, r.y + r.height - 1); // vertical line: centered left-right
            g.setStroke(onY ? solid : dashed);
            g.setColor(onY ? GUIDE_ON : GUIDE);
            g.drawLine(r.x, cy, r.x + r.width - 1, cy); // horizontal line: centered top-bottom
            // a small mark in the middle
            g.setStroke(new java.awt.BasicStroke(1.5f));
            g.setColor(onX && onY ? GUIDE_ON : new Color(255, 255, 255, 120));
            g.drawOval(cx - 4, cy - 4, 8, 8);
            if (onX || onY) {
                String text = onX && onY ? "CENTERED" : onX ? "CENTERED LEFT–RIGHT" : "CENTERED TOP–BOTTOM";
                g.setFont(getFont().deriveFont(java.awt.Font.BOLD, 9.5f));
                java.awt.FontMetrics fm = g.getFontMetrics();
                int tw = fm.stringWidth(text) + 10;
                g.setColor(new Color(0, 0, 0, 170));
                g.fillRoundRect(cx - tw / 2, r.y + r.height - fm.getHeight() - 10, tw, fm.getHeight() + 2, 8, 8);
                g.setColor(GUIDE_ON);
                g.drawString(text, cx - tw / 2 + 5, r.y + r.height - 9 - fm.getDescent());
            }
            g.setStroke(new java.awt.BasicStroke(1f));
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
            paintCenterGuides(g3, r);
            if (editing != null) {
                // this canvas is the editor's own draft: say so, so nobody mistakes it for Preview or Program
                boolean onProgram = program.scene() != null && Objects.equals(program.scene().getId(), editing.getId());
                String badge = "SCENE EDITOR" + (dirty ? " · UNSAVED" : "")
                        + (onProgram ? (dirty ? " · ON PROGRAM: SAVE TO UPDATE IT" : " · ON PROGRAM") : "");
                g3.setFont(getFont().deriveFont(java.awt.Font.BOLD, 9.5f));
                java.awt.FontMetrics bm = g3.getFontMetrics();
                int bw = bm.stringWidth(badge) + 12;
                int bx = r.x + r.width - bw - 6; // top right: the selected layer's name tag uses the top left
                g3.setColor(new Color(0, 0, 0, 170));
                g3.fillRoundRect(bx, r.y + 6, bw, bm.getHeight() + 2, 10, 10);
                g3.setColor(Color.WHITE);
                g3.drawString(badge, bx + 6, r.y + 7 + bm.getAscent());
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
            // a layer in a group shows under it, like OBS
            name.setText((s.getConfig().groupId != null ? "↳  " : "") + s.getName()
                    + (Boolean.TRUE.equals(s.getConfig().locked) ? "   (locked)" : ""));
            name.setIcon(Icons.get(s.getType().icon(), Icons.SMALL, () -> sel ? Color.WHITE : Palette.secondaryText()));
            name.setIconTextGap(8);
            name.setForeground(sel ? l.getSelectionForeground() : (s.isVisible() ? Palette.text() : Palette.mutedText()));            setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            setOpaque(sel);
            return this;
        }
    }
}
