package com.jamc.worship.ui.dialogs;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.SectionType;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongSection;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.Theme;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.service.LyricsParser;
import com.jamc.worship.service.SlideGenerator;
import com.jamc.worship.ui.components.StyleForm;
import com.jamc.worship.util.FileUtils;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import com.jamc.worship.service.SongService;
import com.jamc.worship.service.ValidationException;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Modal editor for creating and editing songs.
 *
 * <p>Lyrics are typed as plain text with section headings ("Verse 1", "Chorus", ...). The
 * detected sections are shown live on the right so volunteers can see how the song will be split.
 */
public class SongEditorDialog extends JDialog {

    private final SongService songService;
    private final Song original;

    private final JTextField titleField = new JTextField();
    private final JTextField authorField = new JTextField();
    private final JTextField copyrightField = new JTextField();
    private final JTextField ccliField = new JTextField();
    private final JTextField tagsField = new JTextField();
    private final JCheckBox favoriteBox = new JCheckBox("Favorite");
    private final JTextArea lyricsArea = new JTextArea();
    private final Context context;
    private final JComboBox<ThemeChoice> themeCombo = new JComboBox<>();
    private final JLabel backgroundLabel = Ui.small("Theme background");
    private final JButton clearBackgroundButton = new JButton("Clear");
    private final DefaultListModel<Slide> slideModel = new DefaultListModel<>();
    private final JList<Slide> slideList = new JList<>(slideModel);
    private SlideStyle previewStyle = SlideStyle.fallback();
    private String backgroundPath;
    private boolean loadingAppearance;
    private final JLabel sectionCount = Ui.small(" ");
    private final JLabel messageLabel = new JLabel(" ");
    private final JButton saveButton = Ui.primaryButton("Save Song", null);
    private final JButton cancelButton = new JButton("Cancel");
    private final Timer parseDebounce;
    private final Timer titleCheckDebounce;

    private Song result;
    private String initialSnapshot;
    private boolean saving;

    /** Themes and services the editor needs; loaded off the event thread before the dialog opens. */
    public record Context(List<Theme> themes, Theme defaultTheme, StyleForm.ImageImporter importer,
                          java.util.function.Function<Theme, SlideStyle> lyricsStyle) {
        /** Without global typography: each theme shows its own text settings. */
        public Context(List<Theme> themes, Theme defaultTheme, StyleForm.ImageImporter importer) {
            this(themes, defaultTheme, importer, Theme::getStyle);
        }
    }

    private record ThemeChoice(Long id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private SongEditorDialog(Window owner, SongService songService, Song song, Context context) {
        super(owner, song == null || song.getId() == null ? "New Song" : "Edit Song", ModalityType.APPLICATION_MODAL);
        this.songService = songService;
        this.original = song;
        this.context = context;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });

        JPanel root = Ui.panel(new BorderLayout(), Palette::panelBackground);
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);

        parseDebounce = new Timer(150, e -> updateSections());
        parseDebounce.setRepeats(false);
        titleCheckDebounce = new Timer(400, e -> checkDuplicateTitle());
        titleCheckDebounce.setRepeats(false);

        loadingAppearance = true;
        populate(song);
        loadingAppearance = false;
        lyricsArea.getDocument().addDocumentListener(onChange(parseDebounce::restart));
        titleField.getDocument().addDocumentListener(onChange(titleCheckDebounce::restart));

        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK), "save", this::save,
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel", this::cancel,
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(null); // Enter must insert new lines in the lyrics area

        setMinimumSize(new Dimension(820, 540));
        setSize(fitToScreen(owner, 1040, 700));
        setLocationRelativeTo(owner);
    }

    /** Shows the editor and returns the saved song, or empty if the user cancelled. */
    public static Optional<Song> showDialog(Window owner, SongService songService, Song song, Context context) {
        SongEditorDialog dialog = new SongEditorDialog(owner, songService, song, context);
        com.jamc.worship.ui.SoftModal.showAndWait(dialog); // minimizing it lets the app be used meanwhile
        return Optional.ofNullable(dialog.result);
    }

    // ------------------------------------------------------------------ layout

    private JComponent buildHeader() {
        boolean isNew = original == null || original.getId() == null;
        JLabel title = Ui.label(isNew ? "New Song" : "Edit Song", Palette::text, "h3");
        JLabel subtitle = Ui.small(isNew
                ? "Enter the song details and lyrics. Sections are detected from headings."
                : "Changes are saved to the library when you click Save Song.");
        JPanel header = Ui.header(title, subtitle);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 16)));
        return header;
    }

    private JComponent buildBody() {
        JSplitPane lyricsSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLyricsPane(), buildSectionsPane());
        lyricsSplit.setResizeWeight(0.72);
        lyricsSplit.setBorder(null);
        lyricsSplit.setContinuousLayout(true);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildDetailsPane(), lyricsSplit);
        split.setResizeWeight(0.0);
        split.setDividerLocation(290);
        split.setBorder(null);
        split.setContinuousLayout(true);
        return split;
    }

    private JComponent buildDetailsPane() {
        JPanel form = Ui.panel(new GridBagLayout(), Palette::panelBackground);
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        titleField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. Amazing Grace");
        authorField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Songwriter(s)");
        copyrightField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "© year publisher, or Public Domain");
        ccliField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Optional");
        tagsField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "worship, communion, christmas");

        addField(form, c, "Title *", titleField);
        addField(form, c, "Author", authorField);
        addField(form, c, "Copyright", copyrightField);
        addField(form, c, "CCLI song number", ccliField);
        addField(form, c, "Tags (comma separated)", tagsField);
        c.insets = new Insets(2, 0, 10, 0);
        form.add(favoriteBox, c);
        c.gridy++;

        c.insets = new Insets(6, 0, 10, 0);
        form.add(new JSeparator(), c);
        c.gridy++;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(Ui.caption("Appearance (optional)"), c);
        c.gridy++;

        String defaultName = context.defaultTheme().getName();
        themeCombo.addItem(new ThemeChoice(null, "Default theme (" + defaultName + ")"));
        for (Theme t : context.themes()) {
            themeCombo.addItem(new ThemeChoice(t.getId(), t.getName()));
        }
        themeCombo.setPrototypeDisplayValue(new ThemeChoice(null, "Default theme"));
        addField(form, c, "Theme", themeCombo);

        JButton chooseBackground = Ui.button("Choose…", "image");
        chooseBackground.addActionListener(e -> chooseBackground());
        clearBackgroundButton.addActionListener(e -> {
            backgroundPath = null;
            updateBackgroundLabel();
            updateSections();
        });
        clearBackgroundButton.setToolTipText("Use the theme's background");
        JPanel bgButtons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bgButtons.add(chooseBackground);
        bgButtons.add(javax.swing.Box.createHorizontalStrut(6));
        bgButtons.add(clearBackgroundButton);
        JPanel bgRow = Ui.transparent(new BorderLayout(0, 4));
        bgRow.add(backgroundLabel, BorderLayout.NORTH);
        bgRow.add(bgButtons, BorderLayout.CENTER);
        addField(form, c, "Background image", bgRow);

        Runnable restyle = () -> {
            if (!loadingAppearance) {
                updateSections();
            }
        };
        themeCombo.addActionListener(e -> restyle.run());

        c.weighty = 1;
        form.add(Ui.transparent(new BorderLayout()), c);

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(Ui.line(false, false, false, true));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setMinimumSize(new Dimension(240, 0));
        return scroll;
    }

    private static void addField(JPanel form, GridBagConstraints c, String label, JComponent field) {
        c.insets = new Insets(0, 0, 4, 0);
        form.add(Ui.label(label, Palette::secondaryText, "small"), c);
        c.gridy++;
        c.insets = new Insets(0, 0, 10, 0);
        form.add(field, c);
        c.gridy++;
    }

    private JComponent buildLyricsPane() {
        JPanel pane = Ui.panel(new BorderLayout(), Palette::panelBackground);
        pane.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 10));

        JPanel top = Ui.transparent(new BorderLayout(0, 6));
        top.add(Ui.label("Lyrics", Palette::text, "semibold"), BorderLayout.NORTH);

        JPanel inserts = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        inserts.add(Ui.small("Insert:"));
        for (SectionType type : List.of(SectionType.VERSE, SectionType.PRE_CHORUS, SectionType.CHORUS,
                SectionType.BRIDGE, SectionType.INTRO, SectionType.OUTRO, SectionType.ENDING)) {
            JButton b = new JButton(type.displayName());
            b.putClientProperty(FlatClientProperties.STYLE, "font: -1; margin: 2,8,2,8");
            b.setFocusable(false);
            b.addActionListener(e -> insertHeading(type));
            inserts.add(b);
        }
        JButton custom = new JButton("Custom…");
        custom.putClientProperty(FlatClientProperties.STYLE, "font: -1; margin: 2,8,2,8");
        custom.setFocusable(false);
        custom.addActionListener(e -> insertCustomHeading());
        inserts.add(custom);
        top.add(inserts, BorderLayout.CENTER);
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        pane.add(top, BorderLayout.NORTH);

        lyricsArea.setFont(lyricsArea.getFont().deriveFont(14f));
        lyricsArea.setLineWrap(true);
        lyricsArea.setWrapStyleWord(true);
        lyricsArea.setTabSize(4);
        lyricsArea.setMargin(new Insets(10, 12, 10, 12));
        JScrollPane scroll = new JScrollPane(lyricsArea);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        pane.add(scroll, BorderLayout.CENTER);

        JLabel help = Ui.small("<html>Put each heading (Verse 1, Chorus, Bridge, [Tag]…) on its own line above its lyrics. "
                + "A blank line inside a section will start a new slide.</html>");
        help.setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 0));
        pane.add(help, BorderLayout.SOUTH);
        return pane;
    }

    private JComponent buildSectionsPane() {
        JPanel pane = Ui.panel(new BorderLayout(), Palette::panelBackground);
        pane.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 14));
        JPanel head = Ui.transparent(new BorderLayout(0, 2));
        head.add(Ui.label("Slide preview", Palette::text, "semibold"), BorderLayout.NORTH);
        head.add(sectionCount, BorderLayout.SOUTH);
        head.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        pane.add(head, BorderLayout.NORTH);

        slideList.setCellRenderer(new SlideCellRenderer());
        slideList.setFocusable(false);
        slideList.setSelectionModel(new javax.swing.DefaultListSelectionModel() {
            @Override
            public void setSelectionInterval(int a, int b) {
                // display only
            }
        });
        Rendering.renderer().images().addChangeListener(slideList::repaint);
        JScrollPane scroll = new JScrollPane(slideList);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        pane.add(scroll, BorderLayout.CENTER);
        pane.setMinimumSize(new Dimension(200, 0));
        return pane;
    }

    private JComponent buildFooter() {
        messageLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        saveButton.addActionListener(e -> save());
        saveButton.setToolTipText("Save (Ctrl+S)");
        cancelButton.addActionListener(e -> cancel());

        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancelButton);
        buttons.add(saveButton);

        JPanel footer = Ui.transparent(new BorderLayout(12, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        footer.add(messageLabel, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    // ------------------------------------------------------------------ behaviour

    private void populate(Song song) {
        if (song != null) {
            titleField.setText(song.getTitle());
            authorField.setText(nz(song.getAuthor()));
            copyrightField.setText(nz(song.getCopyright()));
            ccliField.setText(nz(song.getCcliNumber()));
            tagsField.setText(nz(song.getTags()));
            favoriteBox.setSelected(song.isFavorite());
            lyricsArea.setText(LyricsParser.format(song.getSections()));
            for (int i = 0; i < themeCombo.getItemCount(); i++) {
                if (java.util.Objects.equals(themeCombo.getItemAt(i).id(), song.getThemeId())) {
                    themeCombo.setSelectedIndex(i);
                }
            }
            backgroundPath = song.getBackground() == null || song.getBackground().isBlank() ? null : song.getBackground();
        }
        updateBackgroundLabel();
        lyricsArea.setCaretPosition(0);
        updateSections();
        initialSnapshot = snapshot();
        SwingUtilities.invokeLater(titleField::requestFocusInWindow);
    }

    /** Re-parses the lyrics and re-renders the slide previews with the current theme and overrides. */
    private void updateSections() {
        List<SongSection> parsed = LyricsParser.parse(lyricsArea.getText());
        Song preview = new Song();
        preview.setSections(parsed);
        // fonts are set in Settings → Lyrics Typography
        preview.setFontFamily(original == null ? null : original.getFontFamily());
        preview.setFontSize(original == null ? null : original.getFontSize());
        preview.setBackground(backgroundPath);
        preview.setTypography(original == null ? null : original.getTypography());
        previewStyle = StyleOverrides.forSong(preview).applyTo(selectedThemeStyle());
        List<Slide> slides = SlideGenerator.fromSong(preview);
        slideModel.clear();
        slideModel.addAll(slides);
        sectionCount.setText(parsed.size() + (parsed.size() == 1 ? " section · " : " sections · ")
                + slides.size() + (slides.size() == 1 ? " slide" : " slides"));
        slideList.repaint();
    }

    private Long selectedThemeId() {
        ThemeChoice choice = (ThemeChoice) themeCombo.getSelectedItem();
        return choice == null ? null : choice.id();
    }

    private SlideStyle selectedThemeStyle() {
        Long id = selectedThemeId();
        if (id != null) {
            for (Theme t : context.themes()) {
                if (t.getId().equals(id)) {
                    return context.lyricsStyle().apply(t);
                }
            }
        }
        return context.lyricsStyle().apply(context.defaultTheme());
    }

    private void chooseBackground() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Background image or looping video for this song");
        com.jamc.worship.ui.MediaChoosers.videoFilters(chooser);
        java.util.List<String> both = new java.util.ArrayList<>(FileUtils.IMAGE_EXTENSIONS);
        both.addAll(com.jamc.worship.model.MediaKind.VIDEO.extensions());
        FileNameExtensionFilter backgrounds = new FileNameExtensionFilter("Backgrounds (images and videos)", both.toArray(String[]::new));
        chooser.addChoosableFileFilter(backgrounds);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("Image Files (JPG, PNG, WebP, GIF, BMP)",
                FileUtils.IMAGE_EXTENSIONS.toArray(String[]::new)));
        chooser.setFileFilter(backgrounds);
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.nio.file.Path file = chooser.getSelectedFile().toPath();
        backgroundLabel.setText("Importing…");
        BackgroundTask.run("Import song background", () -> {
            boolean image = com.jamc.worship.model.MediaKind.of(file).orElse(null) == com.jamc.worship.model.MediaKind.IMAGE;
            if (!image) {
                var probe = com.jamc.worship.media.MediaProbe.probeVideo(file);
                if (probe.status() == com.jamc.worship.media.MediaProbe.Status.UNPLAYABLE) {
                    throw new com.jamc.worship.service.ValidationException(probe.message());
                }
            } else if (javax.imageio.ImageIO.read(file.toFile()) == null) {
                throw new com.jamc.worship.service.ValidationException("Unable to open this image.\nPlease use a JPG, PNG, WebP, GIF or BMP file.");
            }
            return context.importer().importImage(file);
        }, stored -> {
            backgroundPath = stored;
            updateBackgroundLabel();
            updateSections();
        }, err -> {
            updateBackgroundLabel();
            Dialogs.showError(this, "The background could not be used.", err);
        });
    }

    private void updateBackgroundLabel() {
        backgroundLabel.setText(backgroundPath == null ? "Theme background"
                : java.nio.file.Path.of(backgroundPath).getFileName().toString());
        backgroundLabel.setToolTipText(backgroundPath);
        clearBackgroundButton.setEnabled(backgroundPath != null);
    }

    private void insertHeading(SectionType type) {
        String label = type.displayName();
        if (type == SectionType.VERSE) {
            long verses = LyricsParser.parse(lyricsArea.getText()).stream()
                    .filter(s -> s.getType() == SectionType.VERSE).count();
            label += " " + (verses + 1);
        }
        insertAtLineStart(label);
    }

    private void insertCustomHeading() {
        String name = JOptionPane.showInputDialog(this, "Section name (e.g. Tag, Vamp, Final Chorus):",
                "Custom section", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.isBlank()) {
            insertAtLineStart("[" + name.strip().replace("[", "").replace("]", "") + "]");
        }
    }

    /** Inserts "heading\n" on its own line at the caret, separated from earlier text by a blank line. */
    private void insertAtLineStart(String heading) {
        try {
            int pos = lyricsArea.getCaretPosition();
            String before = lyricsArea.getText(0, pos);
            StringBuilder insert = new StringBuilder();
            if (!before.isEmpty() && !before.endsWith("\n")) {
                insert.append("\n");
            }
            if (!before.isBlank() && !before.endsWith("\n\n")) {
                insert.append("\n");
            }
            insert.append(heading).append("\n");
            lyricsArea.getDocument().insertString(pos, insert.toString(), null);
            lyricsArea.requestFocusInWindow();
        } catch (BadLocationException e) {
            lyricsArea.append("\n" + heading + "\n");
        }
    }

    private void checkDuplicateTitle() {
        String title = titleField.getText().strip();
        Long excludeId = original == null ? null : original.getId();
        if (title.isEmpty()) {
            showMessage(" ", null);
            return;
        }
        BackgroundTask.run("Check duplicate title", () -> songService.isDuplicateTitle(title, excludeId), dup -> {
            if (!saving && title.equals(titleField.getText().strip())) {
                showMessage(dup ? "Another song already has this title. You can still save it." : " ",
                        dup ? Palette.warning() : null);
            }
        }, null);
    }

    private Song buildSong() {
        Song s = new Song();
        s.setId(original == null ? null : original.getId());
        s.setThemeId(selectedThemeId());
        s.setBackground(backgroundPath);
        s.setTitle(titleField.getText().strip());
        s.setAuthor(authorField.getText());
        s.setCopyright(copyrightField.getText());
        s.setCcliNumber(ccliField.getText());
        s.setTags(normalizeTags(tagsField.getText()));
        s.setFavorite(favoriteBox.isSelected());
        s.setFontFamily(original == null ? null : original.getFontFamily());
        s.setFontSize(original == null ? null : original.getFontSize());
        s.setTypography(original == null || original.getTypography() == null ? null : original.getTypography().copy());
        s.setSections(LyricsParser.parse(lyricsArea.getText()));
        s.setSourceType(original != null && original.getSourceType() != null ? original.getSourceType() : com.jamc.worship.model.LyricsSourceType.MANUAL);
        s.setSourceProvider(original == null ? null : original.getSourceProvider());
        s.setSourceReference(original == null ? null : original.getSourceReference());
        return s;
    }

    private void save() {
        if (saving) {
            return;
        }
        Song song;
        try {
            song = buildSong();
        } catch (ValidationException e) {
            showMessage(e.getMessage(), Palette.danger());
            return;
        }
        if (song.getTitle().isEmpty()) {
            showMessage("Please enter a song title.", Palette.danger());
            titleField.requestFocusInWindow();
            return;
        }
        if (song.getSections().isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "This song has no lyrics yet. Save it anyway?", "No lyrics",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                lyricsArea.requestFocusInWindow();
                return;
            }
        }
        setSaving(true);
        BackgroundTask.run("Save song", () -> songService.save(song), saved -> {
            result = saved;
            dispose();
        }, err -> {
            setSaving(false);
            if (err instanceof ValidationException) {
                showMessage(err.getMessage(), Palette.danger());
            } else {
                Dialogs.showError(this, "The song could not be saved.", err);
            }
        });
    }

    private void cancel() {
        if (saving) {
            return;
        }
        if (!snapshot().equals(initialSnapshot)) {
            Object[] options = {"Discard changes", "Keep editing"};
            int choice = JOptionPane.showOptionDialog(this, "You have unsaved changes to this song.",
                    "Discard changes?", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    options, options[1]);
            if (choice != 0) {
                return;
            }
        }
        dispose();
    }

    private void setSaving(boolean saving) {
        this.saving = saving;
        saveButton.setEnabled(!saving);
        cancelButton.setEnabled(!saving);
        saveButton.setText(saving ? "Saving…" : "Save Song");
        if (saving) {
            showMessage(" ", null);
        }
    }

    private void showMessage(String text, Color color) {
        messageLabel.setText(text);
        messageLabel.setForeground(color == null ? Palette.secondaryText() : color);
    }

    private String snapshot() {
        return String.join("\u0000", titleField.getText(), authorField.getText(), copyrightField.getText(),
                ccliField.getText(), tagsField.getText(), String.valueOf(favoriteBox.isSelected()),
                lyricsArea.getText(), String.valueOf(selectedThemeId()), String.valueOf(backgroundPath));
    }

    private static String normalizeTags(String raw) {
        List<String> tags = new ArrayList<>();
        for (String t : raw.split(",")) {
            String tag = t.strip();
            if (!tag.isEmpty() && tags.stream().noneMatch(tag::equalsIgnoreCase)) {
                tags.add(tag);
            }
        }
        return String.join(", ", tags);
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    private static Dimension fitToScreen(Window owner, int w, int h) {
        java.awt.Rectangle screen = owner != null && owner.getGraphicsConfiguration() != null
                ? owner.getGraphicsConfiguration().getBounds()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        return new Dimension(Math.min(w, screen.width - 40), Math.min(h, screen.height - 60));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Rendered slide thumbnail with its label, so the operator sees exactly how the song will look. */
    private final class SlideCellRenderer extends JPanel implements ListCellRenderer<Slide> {
        private final JLabel label = new JLabel();
        private Slide slide;

        SlideCellRenderer() {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(6, 4, 8, 4));
            JComponent thumb = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    if (slide == null) {
                        return;
                    }
                    Graphics2D g2 = (Graphics2D) g.create();
                    SlideRenderer.applyQualityHints(g2);
                    int w = getWidth();
                    int h = (int) Math.round(w * 9 / 16.0);
                    Rendering.renderer().paint(g2, new Rectangle(0, 0, w, h), slide, previewStyle);
                    g2.setColor(Palette.border());
                    g2.drawRect(0, 0, w - 1, h - 1);
                    g2.dispose();
                }
            };
            thumb.setPreferredSize(new Dimension(176, 99));
            label.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
            add(thumb, BorderLayout.CENTER);
            add(label, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Slide> list, Slide s, int index,
                                                      boolean selected, boolean focused) {
            slide = s;
            label.setText((index + 1) + "   " + s.getLabel());
            label.setForeground(Palette.secondaryText());
            return this;
        }
    }
}
