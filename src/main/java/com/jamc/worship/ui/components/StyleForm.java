package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.BackgroundMode;
import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.FontWeight;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.model.Gradient;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.media.MediaProbe;
import com.jamc.worship.ui.MediaChoosers;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.util.Colors;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Editor for every visual property of a slide: background, font, color, alignment, position,
 * spacing, shadow and outline. Used by the theme editor and the slide editor.
 *
 * <p>{@link #setStyle} loads values without notifying listeners; every user change notifies
 * change listeners so previews update in real time.
 */
public class StyleForm extends JPanel {

    /** Copies a chosen image into the library and returns the path to store. */
    @FunctionalInterface
    public interface ImageImporter {
        String importImage(Path file) throws IOException;
    }

    private static String[] fontFamilies;

    private final ImageImporter importer;
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean loading;

    private final JComboBox<BackgroundType> bgType = new JComboBox<>(BackgroundType.values());
    private final ColorButton bgColor = new ColorButton("Background color", Color.BLACK);
    private final JLabel bgImageLabel = Ui.small("No image");
    private final JButton bgImageButton = Ui.button("Choose File…", "image");
    private final ColorButton gradientFrom = new ColorButton("Gradient start color", Gradient.DEFAULT.fromColor());
    private final ColorButton gradientTo = new ColorButton("Gradient end color", Gradient.DEFAULT.toColor());
    private final JComboBox<Direction> gradientDirection = new JComboBox<>(Direction.values());
    private final JComboBox<FitMode> fit = new JComboBox<>(new FitMode[]{FitMode.FILL, FitMode.FIT});
    private final JComboBox<BackgroundMode> backgroundMode = new JComboBox<>(BackgroundMode.values());
    private final JLabel videoHint = Ui.small("<html><body style='width:190px'>Muted, loops continuously and keeps playing between slides. "
            + "Best: MP4 (H.264, AAC), 1920x1080, 30 FPS. Other formats work if VLC plays them.</html>");
    private JLabel colorLabel;
    private JLabel gradientLabel;
    private JLabel directionLabel;
    private JLabel fileLabel;
    private JLabel fitLabel;
    private JLabel hintLabel;
    private JComponent colorField;
    private JComponent gradientField;
    private JComponent fileField;
    private final JSlider overlay = new JSlider(0, 90, 0);
    private final JLabel overlayValue = Ui.small("0%");
    private final JComboBox<String> fontFamily = new JComboBox<>(fontFamilies());
    private final JSpinner fontSize = new JSpinner(new SpinnerNumberModel(72, SlideStyle.MIN_FONT_SIZE, SlideStyle.MAX_FONT_SIZE, 2));
    private final JComboBox<FontWeight> weight = new JComboBox<>(FontWeight.values());
    private final JToggleButton italic = styleToggle("I", "Italic", "font: italic");
    private final ColorButton fontColor = new ColorButton("Text color", Color.WHITE);
    private final Map<TextAlign, JToggleButton> alignButtons = new EnumMap<>(TextAlign.class);
    private final Map<TextPosition, JToggleButton> positionButtons = new EnumMap<>(TextPosition.class);
    private final JSpinner lineSpacing = new JSpinner(new SpinnerNumberModel(1.15, 0.7, 2.5, 0.05));
    private final JComboBox<TextTransform> textCase = new JComboBox<>(TextTransform.values());
    private final JCheckBox shadow = new JCheckBox("Drop shadow");
    private final JSlider shadowStrength = new JSlider(0, 100, 55);
    private final JCheckBox outline = new JCheckBox("Outline");
    private final ColorButton outlineColor = new ColorButton("Outline color", Color.BLACK);
    private final JSpinner outlineWidth = new JSpinner(new SpinnerNumberModel(4, 1, 20, 1));
    private String imagePath;

    public StyleForm(ImageImporter importer) {
        super(new GridBagLayout());
        this.importer = importer;
        setOpaque(false);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        section(c, "Background");
        row(c, "Type", bgType);
        colorField = bgColor;
        colorLabel = row(c, "Color", bgColor);
        JPanel gradientRow = Ui.transparent(new java.awt.GridLayout(1, 2, 6, 0));
        gradientRow.add(gradientFrom);
        gradientRow.add(gradientTo);
        gradientField = gradientRow;
        gradientLabel = row(c, "Colors", gradientRow);
        directionLabel = row(c, "Direction", gradientDirection);
        JPanel imageRow = Ui.transparent(new BorderLayout(6, 0));
        imageRow.add(bgImageButton, BorderLayout.WEST);
        imageRow.add(bgImageLabel, BorderLayout.CENTER);
        fileField = imageRow;
        fileLabel = row(c, "File", imageRow);
        fit.setToolTipText("Fill covers the whole slide (edges may be cropped); Fit shows the whole picture with bars");
        fitLabel = row(c, "Fit", fit);
        hintLabel = row(c, "", videoHint);
        backgroundMode.setToolTipText("What this content does when a global video background is ON. "
                + "The global background keeps running in every case.");
        row(c, "Global video", backgroundMode);
        JPanel overlayRow = Ui.transparent(new BorderLayout(6, 0));
        overlayRow.add(overlay, BorderLayout.CENTER);
        overlayValue.setPreferredSize(new java.awt.Dimension(34, 10));
        overlayRow.add(overlayValue, BorderLayout.EAST);
        overlay.setToolTipText("Darkens the background so text stays readable");
        row(c, "Dim background", overlayRow);

        section(c, "Text");
        fontFamily.setPrototypeDisplayValue("Segoe UI Semibold");
        fontFamily.setMaximumRowCount(18);
        row(c, "Font", fontFamily);
        JPanel sizeRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fontSize.setToolTipText("Size at 1920x1080. Text shrinks automatically if it does not fit.");
        ((JSpinner.DefaultEditor) fontSize.getEditor()).getTextField().setColumns(4);
        sizeRow.add(fontSize);
        weight.setToolTipText("Font style. SemiBold, Medium, Light... use the matching font face when it is installed, else the nearest of Regular and Bold.");
        sizeRow.add(weight);
        sizeRow.add(italic);
        row(c, "Size / style", sizeRow);
        row(c, "Color", fontColor);
        row(c, "Align", segmented(alignButtons, TextAlign.values()));
        row(c, "Position", segmented(positionButtons, TextPosition.values()));
        ((JSpinner.DefaultEditor) lineSpacing.getEditor()).getTextField().setColumns(4);
        JPanel spacingRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        spacingRow.add(lineSpacing);
        row(c, "Line spacing", spacingRow);
        textCase.setToolTipText("Letter case on screen only. The words are stored as typed, so Original brings their capitals back.");
        row(c, "Letter case", textCase);

        section(c, "Effects");
        JPanel shadowRow = Ui.transparent(new BorderLayout(6, 0));
        shadowRow.add(shadow, BorderLayout.WEST);
        shadowStrength.setToolTipText("Shadow strength");
        shadowRow.add(shadowStrength, BorderLayout.CENTER);
        row(c, "", shadowRow);
        JPanel outlineRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        outlineRow.add(outline);
        outlineRow.add(outlineColor);
        ((JSpinner.DefaultEditor) outlineWidth.getEditor()).getTextField().setColumns(2);
        outlineRow.add(outlineWidth);
        row(c, "", outlineRow);

        c.weighty = 1;
        add(Ui.transparent(new BorderLayout()), c);

        wireListeners();
        setStyle(SlideStyle.fallback());
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Loads a style into the controls without firing change events. */
    public void setStyle(SlideStyle s) {
        loading = true;
        try {
            bgType.setSelectedItem(s.backgroundType());
            bgColor.setColor(Colors.parse(s.effectiveBackgroundColor(), Color.BLACK));
            imagePath = s.backgroundType().usesFile() ? s.backgroundValue() : null;
            Gradient gradient = s.backgroundType() == BackgroundType.GRADIENT ? Gradient.parse(s.backgroundValue()) : Gradient.DEFAULT;
            gradientFrom.setColor(gradient.fromColor());
            gradientTo.setColor(gradient.toColor());
            gradientDirection.setSelectedItem(Direction.of(gradient.angle()));
            fit.setSelectedItem(s.backgroundFit() == FitMode.FIT ? FitMode.FIT : FitMode.FILL);
            backgroundMode.setSelectedItem(s.backgroundMode());
            overlay.setValue((int) Math.round(s.overlayOpacity() * 100));
            selectFont(s.fontFamily());
            fontSize.setValue(s.fontSize());
            weight.setSelectedItem(s.fontWeight());
            textCase.setSelectedItem(s.textTransform());
            shadowStrength.setValue((int) Math.round(s.shadowStrength() * 100));
            italic.setSelected(s.italic());
            fontColor.setColor(Colors.parse(s.fontColor(), Color.WHITE));
            alignButtons.get(s.align()).setSelected(true);
            positionButtons.get(s.position()).setSelected(true);
            lineSpacing.setValue(Math.round(s.lineSpacing() * 100) / 100.0);
            shadow.setSelected(s.shadow());
            outline.setSelected(s.outline());
            outlineColor.setColor(Colors.parse(s.outlineColor(), Color.BLACK));
            outlineWidth.setValue(s.outlineWidth());
            updateDependentControls();
        } finally {
            loading = false;
        }
    }

    public SlideStyle getStyle() {
        BackgroundType type = (BackgroundType) bgType.getSelectedItem();
        String color = bgColor.getHex();
        boolean file = type != null && type.usesFile() && imagePath != null && matchesType(imagePath);
        boolean gradient = type == BackgroundType.GRADIENT;
        return new SlideStyle(
                file || gradient ? type : BackgroundType.COLOR,
                file ? imagePath : gradient ? new Gradient(gradientFrom.getHex(), gradientTo.getHex(),
                        ((Direction) gradientDirection.getSelectedItem()).angle).format() : color,
                gradient ? gradientFrom.getHex() : color,
                overlay.getValue() / 100.0,
                (String) fontFamily.getSelectedItem(),
                ((Number) fontSize.getValue()).intValue(),
                (FontWeight) weight.getSelectedItem(),
                italic.isSelected(),
                fontColor.getHex(),
                selected(alignButtons, TextAlign.CENTER),
                selected(positionButtons, TextPosition.MIDDLE),
                ((Number) lineSpacing.getValue()).doubleValue(),
                shadow.isSelected(),
                outline.isSelected(),
                outlineColor.getHex(),
                ((Number) outlineWidth.getValue()).intValue(),
                (FitMode) fit.getSelectedItem(),
                (BackgroundMode) backgroundMode.getSelectedItem(),
                (TextTransform) textCase.getSelectedItem(),
                shadowStrength.getValue() / 100.0);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setEnabledRecursive(this, enabled);
        if (enabled) {
            updateDependentControls();
        }
    }

    // ------------------------------------------------------------------ internals

    private void wireListeners() {
        bgType.addActionListener(e -> {
            updateDependentControls();
            BackgroundType t = (BackgroundType) bgType.getSelectedItem();
            if (t != null && t.usesFile() && !loading && (imagePath == null || !matchesType(imagePath))) {
                chooseImage();
            }
            changed();
        });
        bgColor.addColorListener(col -> changed());
        bgImageButton.addActionListener(e -> chooseImage());
        gradientFrom.addColorListener(col -> changed());
        gradientTo.addColorListener(col -> changed());
        gradientDirection.addActionListener(e -> changed());
        fit.addActionListener(e -> changed());
        backgroundMode.addActionListener(e -> changed());
        overlay.addChangeListener(e -> {
            overlayValue.setText(overlay.getValue() + "%");
            changed();
        });
        fontFamily.addActionListener(e -> changed());
        fontSize.addChangeListener(e -> changed());
        weight.addActionListener(e -> changed());
        textCase.addActionListener(e -> changed());
        italic.addActionListener(e -> changed());
        fontColor.addColorListener(col -> changed());
        alignButtons.values().forEach(b -> b.addActionListener(e -> changed()));
        positionButtons.values().forEach(b -> b.addActionListener(e -> changed()));
        lineSpacing.addChangeListener(e -> changed());
        shadow.addActionListener(e -> {
            updateDependentControls();
            changed();
        });
        shadowStrength.addChangeListener(e -> {
            if (!shadowStrength.getValueIsAdjusting()) {
                changed();
            }
        });
        outline.addActionListener(e -> {
            updateDependentControls();
            changed();
        });
        outlineColor.addColorListener(col -> changed());
        outlineWidth.addChangeListener(e -> changed());
    }

    private void changed() {
        if (!loading) {
            listeners.forEach(Runnable::run);
        }
    }

    private void updateDependentControls() {
        BackgroundType type = (BackgroundType) bgType.getSelectedItem();
        boolean image = type != null && type.usesFile();
        boolean video = type == BackgroundType.VIDEO;
        boolean gradient = type == BackgroundType.GRADIENT;
        show(colorLabel, colorField, !gradient);
        colorLabel.setText(image ? "Behind / bars" : "Color");
        show(gradientLabel, gradientField, gradient);
        show(directionLabel, gradientDirection, gradient);
        show(fileLabel, fileField, image);
        fileLabel.setText(video ? "Video" : "Image");
        bgImageButton.setText(video ? "Choose Video…" : "Choose Image…");
        show(fitLabel, fit, image);
        show(hintLabel, videoHint, video);
        bgImageButton.setEnabled(image && isEnabled());
        if (imagePath == null || !matchesType(imagePath)) {
            bgImageLabel.setText(image ? (video ? "No video chosen" : "No image chosen") : "—");
            bgImageLabel.setToolTipText(null);
        } else {
            String name = Path.of(imagePath).getFileName().toString();
            bgImageLabel.setText(image ? name : "—");
            bgImageLabel.setToolTipText(imagePath);
        }
        overlayValue.setText(overlay.getValue() + "%");
        outlineColor.setEnabled(outline.isSelected() && isEnabled());
        outlineWidth.setEnabled(outline.isSelected() && isEnabled());
        shadowStrength.setEnabled(shadow.isSelected() && isEnabled());
    }

    private void chooseImage() {
        JFileChooser chooser = new JFileChooser();
        boolean video = bgType.getSelectedItem() == BackgroundType.VIDEO;
        chooser.setDialogTitle(video ? "Choose a looping background video" : "Choose a background image");
        if (video) {
            MediaChoosers.videoFilters(chooser);
        } else {
            MediaChoosers.imageFilters(chooser);
        }
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, this) != JFileChooser.APPROVE_OPTION) {
            revertType();
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        bgImageButton.setEnabled(false);
        bgImageLabel.setText(video ? "Checking the video…" : "Importing…");
        // Let VLC (or ImageIO) decide whether the file really plays, whatever its extension
        BackgroundTask.run("Import background", () -> {
            if (video) {
                MediaProbe.Result r = MediaProbe.probeVideo(file);
                if (r.status() == MediaProbe.Status.UNPLAYABLE) {
                    return new Object[]{null, r};
                }
                return new Object[]{importer.importImage(file), r};
            }
            if (javax.imageio.ImageIO.read(file.toFile()) == null) {
                return new Object[]{null, null};
            }
            return new Object[]{importer.importImage(file), null};
        }, result -> {
            MediaProbe.Result probe = (MediaProbe.Result) result[1];
            if (result[0] == null) {
                revertType();
                updateDependentControls();
                Dialogs.showInfo(this, video ? "Video background" : "Background image", video
                        ? probe.message() + "\n\n" + MediaProbe.RECOMMENDED
                        : "Unable to open this image.\nPlease use a JPG, PNG, WebP, GIF or BMP file.");
                return;
            }
            imagePath = (String) result[0];
            loading = true;
            bgType.setSelectedItem(video ? BackgroundType.VIDEO : BackgroundType.IMAGE);
            loading = false;
            updateDependentControls();
            changed();
            if (probe != null && probe.status() == MediaProbe.Status.UNVERIFIED) {
                Dialogs.showInfo(this, "Video background", "The video was added, but it could not be checked because "
                        + "the video engine is not available:\n" + probe.message());
            }
        }, err -> {
            revertType();
            updateDependentControls();
            Dialogs.showError(this, "The background could not be imported.", err);
        });
    }

    /** After a cancelled or rejected choice, go back to the type that matches the current file (or color). */
    private void revertType() {
        BackgroundType keep = BackgroundType.COLOR;
        if (imagePath != null) {
            MediaKind kind = MediaKind.of(Path.of(imagePath)).orElse(MediaKind.VIDEO);
            keep = kind == MediaKind.IMAGE ? BackgroundType.IMAGE : BackgroundType.VIDEO;
        }
        if (bgType.getSelectedItem() != keep) {
            loading = true;
            bgType.setSelectedItem(keep);
            loading = false;
            updateDependentControls();
        }
    }

    /**
     * True if the chosen file suits the selected background type. Videos are anything that is not an
     * image or audio file, since VLC plays many more formats than any extension list.
     */
    private boolean matchesType(String path) {
        var kind = MediaKind.of(Path.of(path));
        if (bgType.getSelectedItem() == BackgroundType.VIDEO) {
            return kind.isEmpty() || kind.get() == MediaKind.VIDEO;
        }
        return kind.isPresent() && kind.get() == MediaKind.IMAGE;
    }

    private static void show(JComponent label, JComponent field, boolean visible) {
        label.setVisible(visible);
        field.setVisible(visible);
    }

    /** Gradient directions offered in the editor. */
    private enum Direction {
        DOWN("Top → bottom", 90), RIGHT("Left → right", 0), DIAGONAL_DOWN("Diagonal ↘", 45),
        DIAGONAL_UP("Diagonal ↗", 315), UP("Bottom → top", 270);

        final String label;
        final int angle;

        Direction(String label, int angle) {
            this.label = label;
            this.angle = angle;
        }

        static Direction of(int angle) {
            for (Direction d : values()) {
                if (d.angle == angle) {
                    return d;
                }
            }
            return DOWN;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void selectFont(String family) {
        for (int i = 0; i < fontFamily.getItemCount(); i++) {
            if (fontFamily.getItemAt(i).equalsIgnoreCase(family)) {
                fontFamily.setSelectedIndex(i);
                return;
            }
        }
        fontFamily.insertItemAt(family, 0); // font not installed here: keep the stored name selectable
        fontFamily.setSelectedIndex(0);
    }

    private void section(GridBagConstraints c, String title) {
        c.gridwidth = 2;
        c.gridx = 0;
        c.insets = new Insets(c.gridy == 0 ? 0 : 14, 0, 8, 0);
        JLabel caption = Ui.caption(title);
        add(caption, c);
        c.gridy++;
        c.gridwidth = 1;
    }

    private JLabel row(GridBagConstraints c, String label, JComponent field) {
        c.insets = new Insets(0, 0, 8, 10);
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        JLabel labelComponent = Ui.label(label, Palette::secondaryText, "small");
        add(labelComponent, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 8, 0);
        add(field, c);
        c.gridy++;
        c.gridx = 0;
        return labelComponent;
    }

    private static <E extends Enum<E>> JComponent segmented(Map<E, JToggleButton> buttons, E[] values) {
        JPanel p = Ui.transparent(new GridLayout(1, values.length, 0, 0));
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < values.length; i++) {
            JToggleButton b = new JToggleButton(values[i].toString());
            b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            b.putClientProperty(FlatClientProperties.STYLE, "font: -1; margin: 3,6,3,6");
            b.setFocusable(false);
            group.add(b);
            buttons.put(values[i], b);
            p.add(b);
        }
        p.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, true, true, true),
                BorderFactory.createEmptyBorder(1, 1, 1, 1)));
        return p;
    }

    private static <E> E selected(Map<E, JToggleButton> buttons, E fallback) {
        return buttons.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey)
                .findFirst().orElse(fallback);
    }

    private static JToggleButton styleToggle(String text, String tooltip, String style) {
        JToggleButton b = new JToggleButton(text);
        b.setToolTipText(tooltip);
        b.putClientProperty(FlatClientProperties.STYLE, style + "; margin: 3,9,3,9");
        b.setFocusable(false);
        return b;
    }

    private static void setEnabledRecursive(java.awt.Container c, boolean enabled) {
        for (java.awt.Component child : c.getComponents()) {
            child.setEnabled(enabled);
            if (child instanceof java.awt.Container cc) {
                setEnabledRecursive(cc, enabled);
            }
        }
    }

    private static synchronized String[] fontFamilies() {
        if (fontFamilies == null) {
            fontFamilies = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        }
        return fontFamilies;
    }
}
