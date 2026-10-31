package com.jamc.worship.ui.dialogs;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Theme;
import com.jamc.worship.service.ThemeService;
import com.jamc.worship.service.ValidationException;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.components.SlidePreviewCanvas;
import com.jamc.worship.ui.components.StyleForm;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Optional;

/** Create or edit a theme with a real-time 16:9 preview. */
public class ThemeEditorDialog extends JDialog {

    private static final String SAMPLE_TEXT = "Amazing grace! How sweet the sound\nThat saved a wretch like me!\n"
            + "I once was lost, but now am found;\nWas blind, but now I see.";

    private final ThemeService themeService;
    private final Theme original;
    private final JTextField nameField = new JTextField();
    private final StyleForm form;
    private final javax.swing.JCheckBox overrideTypography = new javax.swing.JCheckBox(
            "Use this theme's own text settings instead of the global typography");
    private boolean initialOverride;
    private final SlidePreviewCanvas preview = new SlidePreviewCanvas();
    private final JTextArea sampleText = new JTextArea(SAMPLE_TEXT, 4, 30);
    private final JTextField sampleCaption = new JTextField();
    private final JLabel message = new JLabel(" ");
    private final JButton saveButton = Ui.primaryButton("Save Theme", null);
    private final JButton saveAsButton = new JButton("Save as New Theme…");
    private final JButton cancelButton = new JButton("Cancel");
    private Theme result;
    private SlideStyle initialStyle;
    private String initialName;
    private boolean saving;

    private ThemeEditorDialog(Window owner, ThemeService themeService, Theme theme, String suggestedName,
                              SlideStyle startStyle, StyleForm.ImageImporter importer) {
        super(owner, theme == null ? "New Theme" : "Edit Theme", ModalityType.APPLICATION_MODAL);
        this.themeService = themeService;
        this.original = theme;
        this.form = new StyleForm(importer);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });

        JPanel root = Ui.panel(new BorderLayout(), Palette::panelBackground);
        JLabel title = Ui.label(theme == null ? "New Theme" : "Edit Theme", Palette::text, "h3");
        JLabel subtitle = Ui.small(theme != null && theme.isBuiltin()
                ? "Built-in theme: you can change it, and duplicate it to keep the original."
                : "Changes appear in the preview as you make them.");
        JPanel header = Ui.header(title, subtitle);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 16)));
        root.add(header, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildFormPane(), buildPreviewPane());
        split.setBorder(null);
        split.setDividerLocation(380);
        split.setContinuousLayout(true);
        root.add(split, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);

        nameField.setText(theme == null ? suggestedName : theme.getName());
        form.setStyle(theme == null ? startStyle : theme.getStyle());
        overrideTypography.setSelected(theme != null && theme.isTypographyOverride());
        overrideTypography.setToolTipText("<html>Off: songs, verses and slides with this theme use the global typography of "
                + "Settings &rarr; Presentation (when it is switched on); only the background comes from the theme.<br>"
                + "On: the font, size, letter case, color, alignment, shadow and outline below win (e.g. a Youth theme).</html>");
        form.addChangeListener(this::updatePreview);
        sampleText.getDocument().addDocumentListener(onChange(this::updatePreview));
        sampleCaption.getDocument().addDocumentListener(onChange(this::updatePreview));
        updatePreview();
        initialStyle = form.getStyle();
        initialName = nameField.getText();
        initialOverride = overrideTypography.isSelected();

        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK), "save", () -> save(false),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel", this::cancel,
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        java.awt.Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(860, screen.width - 40), Math.min(560, screen.height - 40)));
        setSize(Math.min(1180, screen.width - 40), Math.min(760, screen.height - 40));
        setLocationRelativeTo(owner);
    }

    /** Edits an existing theme. Returns the saved theme (which may be a new one from "Save as New"). */
    public static Optional<Theme> edit(Window owner, ThemeService service, Theme theme, StyleForm.ImageImporter importer) {
        ThemeEditorDialog d = new ThemeEditorDialog(owner, service, theme, null, null, importer);
        d.setVisible(true);
        return Optional.ofNullable(d.result);
    }

    /** Creates a new theme starting from {@code startStyle}. */
    public static Optional<Theme> create(Window owner, ThemeService service, String suggestedName, SlideStyle startStyle,
                                         StyleForm.ImageImporter importer) {
        ThemeEditorDialog d = new ThemeEditorDialog(owner, service, null, suggestedName, startStyle, importer);
        d.setVisible(true);
        return Optional.ofNullable(d.result);
    }

    private JComponent buildFormPane() {
        JPanel p = Ui.panel(new BorderLayout(0, 12), Palette::panelBackground);
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 14));
        JPanel nameRow = Ui.transparent(new BorderLayout(0, 4));
        nameRow.add(Ui.label("Theme name *", Palette::secondaryText, "small"), BorderLayout.NORTH);
        nameRow.add(nameField, BorderLayout.CENTER);
        JPanel top = Ui.transparent(new BorderLayout(0, 8));
        top.add(nameRow, BorderLayout.NORTH);
        top.add(overrideTypography, BorderLayout.SOUTH);
        p.add(top, BorderLayout.NORTH);
        p.add(form, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(p);
        scroll.setBorder(Ui.line(false, false, false, true));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setMinimumSize(new Dimension(320, 0));
        return scroll;
    }

    private JComponent buildPreviewPane() {
        JPanel p = Ui.panel(new BorderLayout(0, 12), Palette::panelBackground);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 16));
        preview.setBadge("PREVIEW 1920×1080", Palette::mutedText);
        p.add(preview, BorderLayout.CENTER);

        JPanel sample = Ui.transparent(new BorderLayout(0, 4));
        sample.add(Ui.label("Sample text (not saved with the theme)", Palette::secondaryText, "small"), BorderLayout.NORTH);
        sampleText.setLineWrap(true);
        sampleText.setWrapStyleWord(true);
        JScrollPane textScroll = new JScrollPane(sampleText);
        textScroll.setPreferredSize(new Dimension(200, 92));
        sample.add(textScroll, BorderLayout.CENTER);
        sampleCaption.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Optional caption, e.g. John 3:16");
        sample.add(sampleCaption, BorderLayout.SOUTH);
        p.add(sample, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildFooter() {
        message.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        saveButton.addActionListener(e -> save(false));
        saveButton.setToolTipText("Save (Ctrl+S)");
        saveAsButton.addActionListener(e -> save(true));
        saveAsButton.setVisible(original != null);
        cancelButton.addActionListener(e -> cancel());
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancelButton);
        buttons.add(saveAsButton);
        buttons.add(saveButton);
        JPanel footer = Ui.transparent(new BorderLayout(12, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        footer.add(message, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    private void updatePreview() {
        String caption = sampleCaption.getText().isBlank() ? null : sampleCaption.getText();
        preview.setContent(new Slide("Sample", sampleText.getText(), caption), form.getStyle());
    }

    private void save(boolean asNew) {
        if (saving) {
            return;
        }
        String name = nameField.getText().strip();
        if (asNew) {
            String suggested = name + " Copy";
            Object input = JOptionPane.showInputDialog(this, "Name for the new theme:", "Save as New Theme",
                    JOptionPane.PLAIN_MESSAGE, null, null, suggested);
            if (input == null) {
                return;
            }
            name = input.toString().strip();
        }
        if (name.isEmpty()) {
            showMessage("Please enter a theme name.", Palette.danger());
            nameField.requestFocusInWindow();
            return;
        }
        Theme toSave = asNew || original == null
                ? new Theme(null, name, false, form.getStyle())
                : new Theme(original.getId(), name, original.isBuiltin(), form.getStyle());
        toSave.setTypographyOverride(overrideTypography.isSelected());
        setSaving(true);
        BackgroundTask.run("Save theme", () -> themeService.save(toSave), saved -> {
            result = saved;
            dispose();
        }, err -> {
            setSaving(false);
            if (err instanceof ValidationException) {
                showMessage(err.getMessage(), Palette.danger());
            } else {
                Dialogs.showError(this, "The theme could not be saved.", err);
            }
        });
    }

    private void cancel() {
        if (saving) {
            return;
        }
        boolean dirty = !form.getStyle().equals(initialStyle) || !nameField.getText().equals(initialName)
                || overrideTypography.isSelected() != initialOverride;
        if (dirty) {
            Object[] options = {"Discard changes", "Keep editing"};
            int choice = JOptionPane.showOptionDialog(this, "You have unsaved changes to this theme.",
                    "Discard changes?", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            if (choice != 0) {
                return;
            }
        }
        dispose();
    }

    private void setSaving(boolean saving) {
        this.saving = saving;
        saveButton.setEnabled(!saving);
        saveAsButton.setEnabled(!saving);
        cancelButton.setEnabled(!saving);
        saveButton.setText(saving ? "Saving…" : "Save Theme");
    }

    private void showMessage(String text, Color color) {
        message.setText(text);
        message.setForeground(color == null ? Palette.secondaryText() : color);
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }
}
