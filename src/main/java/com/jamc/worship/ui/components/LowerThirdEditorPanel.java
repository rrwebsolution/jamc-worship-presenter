package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.LowerThird;
import com.jamc.worship.output.ProgramOutput;
import com.jamc.worship.render.LowerThirdRenderer;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.service.SceneService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.util.Colors;
import com.jamc.worship.util.FileUtils;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;

/** Edit a lower third with a design preview, and SHOW / HIDE it on the main Preview and Program monitors. */
public class LowerThirdEditorPanel extends JPanel {

    private final SceneService scenes;
    private final ProgramOutput program;
    private final ProgramOutput previewState;
    private final StyleForm.ImageImporter importer;
    private final JTextField nameField = new JTextField();
    private final JTextField titleField = new JTextField();
    private final JTextField subtitleField = new JTextField();
    private final JComboBox<String> fontCombo = new JComboBox<>(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    private final JSpinner sizeSpin = new JSpinner(new SpinnerNumberModel(52, 16, 160, 2));
    private final ColorButton titleColor = new ColorButton("Name color", Color.WHITE);
    private final ColorButton subtitleColor = new ColorButton("Subtitle color", Color.LIGHT_GRAY);
    private final ColorButton backgroundColor = new ColorButton("Panel color", Color.BLACK);
    private final ColorButton accentColor = new ColorButton("Accent color", Color.BLUE);
    private final JComboBox<LowerThird.Position> positionCombo = new JComboBox<>(LowerThird.Position.values());
    private final JComboBox<LowerThird.Animation> animationCombo = new JComboBox<>(LowerThird.Animation.values());
    /** Classic box or the 3-line name tag, its top line, and how far from the screen edge (user 2026-11-17). */
    private final JComboBox<LowerThird.Style> styleCombo = new JComboBox<>(LowerThird.Style.values());
    private final JTextField labelField = new JTextField();
    private final JSpinner marginSpin = new JSpinner(new SpinnerNumberModel(90, 0, 900, 10));
    /** Box width in 1080p pixels, 0 = Auto (as wide as the words). */
    private final JSpinner widthSpin = new JSpinner(new SpinnerNumberModel(0, 0, 1728, 20));
    /** Name and subtitle alignment; AUTO_ALIGN = the design's usual (classic left, name tag centered). */
    private final JComboBox<Object> alignCombo = new JComboBox<>(new Object[]{"Auto", LowerThird.TextAlign.LEFT,
            LowerThird.TextAlign.CENTER, LowerThird.TextAlign.RIGHT});
    private final JLabel logoLabel = Ui.small("No logo");
    private final JButton showButton = new JButton("SHOW LOWER THIRD");
    private final JButton hideButton = new JButton("HIDE LOWER THIRD");
    private final Preview preview = new Preview();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private final Timer saveTimer;
    private LowerThird editing;
    private boolean loading;
    private Consumer<LowerThird> savedListener = l -> { };

    public LowerThirdEditorPanel(SceneService scenes, ProgramOutput program, ProgramOutput previewState,
                                 StyleForm.ImageImporter importer) {
        super(new BorderLayout());
        this.scenes = scenes;
        this.program = program;
        this.previewState = previewState;
        this.importer = importer;
        setOpaque(false);

        showButton.putClientProperty(FlatClientProperties.STYLE,
                "font: bold; background: #16A34A; foreground: #FFFFFF; margin: 8,14,8,14");
        hideButton.putClientProperty(FlatClientProperties.STYLE, "font: bold; margin: 8,14,8,14");
        showButton.addActionListener(e -> {
            if (editing != null) {
                previewState.showLowerThird(editing);
                program.showLowerThird(editing);
            }
        });
        hideButton.addActionListener(e -> {
            // only this lower third: the others shown stay on (user 2026-11-17)
            if (editing != null) {
                previewState.hideLowerThird(editing.getId());
                program.hideLowerThird(editing.getId());
            }
        });
        JPanel liveRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        liveRow.add(showButton);
        liveRow.add(hideButton);
        JLabel note = Ui.small("Shown on Preview and Program when their scenes contain a Lower Third source.");
        liveRow.add(note);

        JPanel form = Ui.transparent(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        row(form, g, "Name", titleField, "Subtitle", subtitleField);
        fontCombo.setPrototypeDisplayValue("Segoe UI Semibold");
        row(form, g, "Font", fontCombo, "Size", sizeSpin);
        row(form, g, "Name color", titleColor, "Subtitle color", subtitleColor);
        row(form, g, "Panel color", backgroundColor, "Accent color", accentColor);
        row(form, g, "Design", styleCombo, "Top line", labelField);
        row(form, g, "Position", positionCombo, "Animation", animationCombo);
        marginSpin.setToolTipText("Distance from the bottom (or top) of the screen; raise it above a title banner");
        widthSpin.setToolTipText("Width of the lower third (0 = Auto: as wide as the words). Long words shrink to fit.");
        row(form, g, "Height", marginSpin, "Width (0 = Auto)", widthSpin);
        alignCombo.setToolTipText("Where the name and subtitle sit: Left, Center or Right (Auto = the design's usual)");
        row(form, g, "Align text", alignCombo, "", Ui.transparent(new BorderLayout()));
        JButton logoButton = Ui.button("Choose Logo…", "image");
        logoButton.addActionListener(e -> chooseLogo());
        JButton clearLogo = new JButton("No Logo");
        clearLogo.addActionListener(e -> {
            if (editing != null) {
                editing.setLogoPath(null);
                logoLabel.setText("No logo");
                changed();
            }
        });
        JPanel logoRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        logoRow.add(logoButton);
        logoRow.add(clearLogo);
        logoRow.add(logoLabel);
        row(form, g, "Logo", logoRow, "List name", nameField);

        JPanel content = Ui.transparent(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 0, 12, 0));
        JPanel previewHolder = Ui.transparent(new BorderLayout());
        previewHolder.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
        previewHolder.add(preview);
        content.add(previewHolder, BorderLayout.CENTER);
        JPanel south = Ui.transparent(new BorderLayout(0, 8));
        JPanel lr = Ui.transparent(new BorderLayout());
        lr.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
        lr.add(liveRow);
        south.add(lr, BorderLayout.NORTH);
        JScrollPane fs = new JScrollPane(form);
        fs.setBorder(null);
        fs.setOpaque(false);
        fs.getViewport().setOpaque(false);
        south.add(fs, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        JLabel empty = Ui.label("Select a lower third, or click + to create one.", Palette::mutedText, null);
        empty.setHorizontalAlignment(JLabel.CENTER);
        body.add(content, "edit");
        body.add(empty, "empty");
        add(body);
        cards.show(body, "empty");

        saveTimer = new Timer(600, e -> save());
        saveTimer.setRepeats(false);
        DocumentListener dl = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        };
        nameField.getDocument().addDocumentListener(dl);
        titleField.getDocument().addDocumentListener(dl);
        subtitleField.getDocument().addDocumentListener(dl);
        labelField.getDocument().addDocumentListener(dl);
        labelField.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "Preaching Today (name tag only)");
        marginSpin.addChangeListener(e -> changed());
        widthSpin.addChangeListener(e -> changed());
        alignCombo.addActionListener(e -> changed());
        styleCombo.addActionListener(e -> {
            if (!loading && editing != null && styleCombo.getSelectedItem() == LowerThird.Style.BADGE
                    && editing.getStyle() != LowerThird.Style.BADGE) {
                // the name tag's own colours: white name bar, blue top line, dark words on the gold role bar
                loading = true;
                try {
                    backgroundColor.setColor(Color.WHITE);
                    titleColor.setColor(new Color(0x0B1A3A));
                    subtitleColor.setColor(new Color(0x111827));
                    accentColor.setColor(new Color(0x1E4FD8));
                    if (labelField.getText().isBlank()) {
                        labelField.setText("Preaching Today");
                    }
                } finally {
                    loading = false;
                }
            }
            changed();
        });
        fontCombo.addActionListener(e -> changed());
        sizeSpin.addChangeListener(e -> changed());
        positionCombo.addActionListener(e -> changed());
        animationCombo.addActionListener(e -> changed());
        for (ColorButton b : new ColorButton[]{titleColor, subtitleColor, backgroundColor, accentColor}) {
            b.addColorListener(c -> changed());
        }
        program.addListener(this::updateLiveButtons);
        previewState.addListener(this::updateLiveButtons);
    }

    public void setSavedListener(Consumer<LowerThird> l) {
        savedListener = l;
    }

    public void edit(LowerThird lt) {
        save();
        editing = lt == null ? null : lt.copy();
        if (editing == null) {
            cards.show(body, "empty");
            return;
        }
        loading = true;
        try {
            nameField.setText(editing.getName());
            titleField.setText(editing.getTitle());
            subtitleField.setText(editing.getSubtitle() == null ? "" : editing.getSubtitle());
            fontCombo.setSelectedItem(editing.getFontFamily());
            sizeSpin.setValue(editing.getFontSize());
            titleColor.setColor(Colors.parse(editing.getTitleColor(), Color.WHITE));
            subtitleColor.setColor(Colors.parse(editing.getSubtitleColor(), Color.LIGHT_GRAY));
            backgroundColor.setColor(Colors.parse(editing.getBackgroundColor(), Color.BLACK));
            accentColor.setColor(Colors.parse(editing.getAccentColor(), Color.BLUE));
            positionCombo.setSelectedItem(editing.getPosition());
            animationCombo.setSelectedItem(editing.getAnimation());
            styleCombo.setSelectedItem(editing.getStyle());
            labelField.setText(editing.getLabel() == null ? "" : editing.getLabel());
            marginSpin.setValue(editing.getMarginY());
            widthSpin.setValue(editing.getWidth());
            alignCombo.setSelectedItem(editing.getTextAlign() == null ? "Auto" : editing.getTextAlign());
            logoLabel.setText(editing.getLogoPath() == null ? "No logo" : java.nio.file.Path.of(editing.getLogoPath()).getFileName().toString());
        } finally {
            loading = false;
        }
        updateLiveButtons();
        preview.repaint();
        cards.show(body, "edit");
    }

    private void changed() {
        if (loading || editing == null) {
            return;
        }
        editing.setName(nameField.getText());
        editing.setTitle(titleField.getText());
        editing.setSubtitle(subtitleField.getText().isBlank() ? null : subtitleField.getText());
        editing.setFontFamily((String) fontCombo.getSelectedItem());
        editing.setFontSize(((Number) sizeSpin.getValue()).intValue());
        editing.setTitleColor(titleColor.getHex());
        editing.setSubtitleColor(subtitleColor.getHex());
        editing.setBackgroundColor(backgroundColor.getHex());
        editing.setAccentColor(accentColor.getHex());
        editing.setPosition((LowerThird.Position) positionCombo.getSelectedItem());
        editing.setAnimation((LowerThird.Animation) animationCombo.getSelectedItem());
        editing.setStyle((LowerThird.Style) styleCombo.getSelectedItem());
        editing.setLabel(labelField.getText().isBlank() ? null : labelField.getText().strip());
        editing.setMarginY(((Number) marginSpin.getValue()).intValue());
        editing.setWidth(((Number) widthSpin.getValue()).intValue());
        editing.setTextAlign(alignCombo.getSelectedItem() instanceof LowerThird.TextAlign a ? a : null);
        preview.repaint();
        if (program.isLowerThirdOn(editing.getId())) {
            program.showLowerThird(editing); // live: update the on-air graphic immediately
        }
        if (previewState.isLowerThirdOn(editing.getId())) {
            previewState.showLowerThird(editing); // the prepared composition follows edits too
        }
        saveTimer.restart();
    }

    private void save() {
        saveTimer.stop();
        if (editing == null || editing.getId() == null || editing.getTitle().isBlank()) {
            return;
        }
        LowerThird snapshot = editing.copy();
        BackgroundTask.run("Save lower third", () -> scenes.saveLowerThird(snapshot), savedListener::accept,
                err -> Dialogs.showError(this, "The lower third could not be saved.", err));
    }

    private void chooseLogo() {
        if (editing == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Images", FileUtils.IMAGE_EXTENSIONS.toArray(String[]::new)));
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.nio.file.Path file = chooser.getSelectedFile().toPath();
        BackgroundTask.run("Import logo", () -> importer.importImage(file), stored -> {
            editing.setLogoPath(stored);
            logoLabel.setText(file.getFileName().toString());
            changed();
        }, err -> Dialogs.showError(this, "The logo could not be imported.", err));
    }

    private void updateLiveButtons() {
        Long id = editing == null ? null : editing.getId();
        // HIDE stays usable for this one: it may be on through a scene layer set to it, without SHOW
        hideButton.setEnabled(editing != null && (program.fixedLowerThirdProgress(id) > 0f
                || previewState.fixedLowerThirdProgress(id) > 0f));
        boolean sameOnBoth = editing != null && program.isLowerThirdOn(id) && previewState.isLowerThirdOn(id);
        showButton.setText(sameOnBoth ? "ON PREVIEW + PROGRAM" : "SHOW LOWER THIRD");
        hideButton.setText("HIDE THIS ONE");
    }

    private static void row(JPanel form, GridBagConstraints g, String l1, JComponent c1, String l2, JComponent c2) {
        g.gridx = 0;
        g.weightx = 0;
        g.insets = new Insets(0, 0, 8, 8);
        form.add(Ui.label(l1, Palette::secondaryText, "small"), g);
        g.gridx = 1;
        g.weightx = 1;
        g.insets = new Insets(0, 0, 8, 16);
        form.add(c1, g);
        g.gridx = 2;
        g.weightx = 0;
        g.insets = new Insets(0, 0, 8, 8);
        form.add(Ui.label(l2, Palette::secondaryText, "small"), g);
        g.gridx = 3;
        g.weightx = 1;
        g.insets = new Insets(0, 0, 8, 0);
        form.add(c2, g);
        g.gridy++;
    }

    /** The lower third over a neutral gradient, as it will appear on screen. */
    private final class Preview extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth();
            int h = (int) Math.round(w * 9 / 16.0);
            if (h > getHeight()) {
                h = getHeight();
                w = (int) Math.round(h * 16 / 9.0);
            }
            int x = (getWidth() - w) / 2;
            int y = (getHeight() - h) / 2;
            g2.setPaint(new GradientPaint(x, y, new Color(0x334155), x + w, y + h, new Color(0x0F172A)));
            g2.fillRect(x, y, w, h);
            g2.translate(x, y);
            g2.scale(w / 1920.0, h / 1080.0);
            LowerThirdRenderer.paint(g2, editing, 1f, Rendering.renderer().images());
            g2.dispose();
            g.setColor(Palette.border());
            g.drawRect(x, y, w - 1, h - 1);
        }
    }
}
