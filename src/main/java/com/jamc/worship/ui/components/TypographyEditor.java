package com.jamc.worship.ui.components;

import com.jamc.worship.config.TypographySettings;
import com.jamc.worship.model.ContentKind;
import com.jamc.worship.model.FontWeight;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.model.Typography;
import com.jamc.worship.render.Fonts;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.util.Colors;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Settings editor for one global typography profile (song lyrics, Bible...). Every change is saved
 * at once and shows in Preview straight away; Program keeps what it shows until the next TAKE.
 */
public class TypographyEditor extends JPanel {

    private final TypographySettings settings;
    private final ContentKind kind;
    private final JCheckBox useGlobal;
    private final JComboBox<String> family = new JComboBox<>();
    private final JComboBox<FontWeight> weight = new JComboBox<>(FontWeight.values());
    private final JToggleButton italic = new JToggleButton("I");
    private final JSpinner size = new JSpinner(new SpinnerNumberModel(54, SlideStyle.MIN_FONT_SIZE, SlideStyle.MAX_FONT_SIZE, 2));
    private final JComboBox<TextTransform> transform = new JComboBox<>(TextTransform.values());
    private final ColorButton color = new ColorButton("Text color", Color.WHITE);
    private final JComboBox<TextAlign> align = new JComboBox<>(TextAlign.values());
    private final JComboBox<TextPosition> position = new JComboBox<>(TextPosition.values());
    private final JSpinner lineSpacing = new JSpinner(new SpinnerNumberModel(1.2, 0.7, 2.5, 0.05));
    private final JCheckBox shadow = new JCheckBox("Text shadow");
    private final JSlider shadowStrength = new JSlider(0, 100, 40);
    private final JLabel shadowValue = Ui.small("40%");
    private final JCheckBox outline = new JCheckBox("Text outline");
    private final JSpinner outlineSize = new JSpinner(new SpinnerNumberModel(2, 1, 20, 1));
    private final ColorButton outlineColor = new ColorButton("Outline color", Color.BLACK);
    private final JButton restore = Ui.button("Restore Defaults", null);
    private final JLabel fontNote = Ui.label(" ", Palette::mutedText, "small");
    private final SlidePreviewCanvas sample = new SlidePreviewCanvas();
    private final JPanel fields = Ui.transparent(new GridBagLayout());
    private SlideStyle base = SlideStyle.fallback();
    private boolean loading;

    public TypographyEditor(TypographySettings settings, ContentKind kind) {
        super(new BorderLayout(16, 8));
        this.settings = settings;
        this.kind = kind;
        setOpaque(false);
        useGlobal = new JCheckBox("Use global " + kind + " typography for every "
                + (kind == ContentKind.LYRICS ? "song" : kind == ContentKind.BIBLE ? "verse" : "slide")
                + " (themes marked \"own text settings\" keep theirs)");

        for (String f : Fonts.families()) {
            family.addItem(f);
        }
        family.setMaximumRowCount(18);
        com.jamc.worship.ui.FontChoices.familyRenderer(family);
        family.setPrototypeDisplayValue("Segoe UI Semibold Wide");
        italic.setToolTipText("Italic");
        italic.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: italic; margin: 3,9,3,9");
        italic.setFocusable(false);
        ((JSpinner.DefaultEditor) size.getEditor()).getTextField().setColumns(4);
        ((JSpinner.DefaultEditor) lineSpacing.getEditor()).getTextField().setColumns(4);
        ((JSpinner.DefaultEditor) outlineSize.getEditor()).getTextField().setColumns(3);
        size.setToolTipText("Size at 1920x1080. Long lyrics shrink automatically to fit.");
        transform.setToolTipText("Shown on Preview, Program, projector and OBS only; the words are stored exactly as typed.");
        shadowStrength.setPreferredSize(new Dimension(120, shadowStrength.getPreferredSize().height));

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        com.jamc.worship.ui.FontChoices.weightRenderer(weight, () -> (String) family.getSelectedItem());
        javax.swing.JButton addFont = com.jamc.worship.ui.Ui.button("Add Font…", "plus");
        addFont.setToolTipText("Add TTF / OTF font files (each weight file, e.g. Light / SemiBold / Black, is a thickness choice)");
        addFont.addActionListener(e -> com.jamc.worship.ui.FontChoices.addFonts(this, added -> {
            if (!added.isEmpty()) {
                family.setSelectedItem(added.getFirst()); // the list was refilled by the Fonts listener
            }
        }));
        com.jamc.worship.render.Fonts.addListener(() -> com.jamc.worship.ui.FontChoices.refill(family,
                () -> loading = true, () -> loading = false));
        row(c, "Font family", flow(family, addFont), "Thickness", flow(weight, italic));
        row(c, "Font size", size, "Text transform", transform);
        row(c, "Text color", color, "Alignment", align);
        row(c, "Vertical position", position, "Line spacing", lineSpacing);
        row(c, "", flow(shadow, Ui.small("Strength"), shadowStrength, shadowValue), "", null);
        row(c, "", flow(outline, Ui.small("Size"), outlineSize, Ui.small("px"), outlineColor), "", null);
        c.gridx = 0;
        c.gridwidth = 4;
        fields.add(fontNote, c);
        c.gridy++;
        fields.add(flow(restore), c);

        // PREVIEW right beside the controls (not at the far edge of the wide Settings page, where it could be
        // scrolled out of sight): every change shows here at once
        sample.setBadge("SAMPLE", Palette::mutedText);
        Dimension sampleSize = new Dimension(400, 225);
        sample.setPreferredSize(sampleSize);
        sample.setMinimumSize(sampleSize);
        JPanel sampleColumn = Ui.transparent(new BorderLayout(0, 6));
        sampleColumn.add(Ui.label("PREVIEW", Palette::secondaryText, "semibold"), BorderLayout.NORTH);
        sampleColumn.add(sample, BorderLayout.CENTER);
        sampleColumn.add(Ui.label("Changes show here as you make them.", Palette::mutedText, "small"), BorderLayout.SOUTH);
        JPanel sampleHolder = Ui.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        sampleHolder.add(sampleColumn);

        add(useGlobal, BorderLayout.NORTH);
        JPanel body = Ui.transparent(new BorderLayout(28, 0));
        body.add(fields, BorderLayout.WEST);
        body.add(sampleHolder, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        load();
        wire();
    }

    /** The background the sample is drawn on (the default theme's). */
    public void setBaseStyle(SlideStyle base) {
        this.base = base == null ? SlideStyle.fallback() : base;
        updateSample();
    }

    /** Reloads the controls from the saved profile. */
    public void load() {
        loading = true;
        try {
            Typography t = settings.get(kind);
            useGlobal.setSelected(settings.isGlobal(kind));
            selectFamily(t.fontFamily());
            weight.setSelectedItem(t.fontWeight());
            italic.setSelected(t.italic());
            size.setValue(t.fontSize());
            transform.setSelectedItem(t.textTransform());
            color.setColor(Colors.parse(t.fontColor(), Color.WHITE));
            align.setSelectedItem(t.align());
            position.setSelectedItem(t.position());
            lineSpacing.setValue(Math.round(t.lineSpacing() * 100) / 100.0);
            shadow.setSelected(t.shadow());
            shadowStrength.setValue((int) Math.round(t.shadowStrength() * 100));
            outline.setSelected(t.outline());
            outlineSize.setValue(t.outlineWidth());
            outlineColor.setColor(Colors.parse(t.outlineColor(), Color.BLACK));
        } finally {
            loading = false;
        }
        updateDependent();
        updateSample();
    }

    private void wire() {
        useGlobal.addActionListener(e -> {
            settings.setGlobal(kind, useGlobal.isSelected());
            updateDependent();
            updateSample();
        });
        family.addActionListener(e -> changed());
        weight.addActionListener(e -> changed());
        italic.addActionListener(e -> changed());
        size.addChangeListener(e -> changed());
        transform.addActionListener(e -> changed());
        color.addColorListener(col -> changed());
        align.addActionListener(e -> changed());
        position.addActionListener(e -> changed());
        lineSpacing.addChangeListener(e -> changed());
        shadow.addActionListener(e -> changed());
        shadowStrength.addChangeListener(e -> {
            shadowValue.setText(shadowStrength.getValue() + "%");
            if (!shadowStrength.getValueIsAdjusting()) {
                changed();
            }
        });
        outline.addActionListener(e -> changed());
        outlineSize.addChangeListener(e -> changed());
        outlineColor.addColorListener(col -> changed());
        restore.addActionListener(e -> {
            settings.restoreDefaults(kind);
            load();
        });
    }

    private void changed() {
        if (loading) {
            return;
        }
        settings.set(kind, read());
        updateDependent();
        updateSample();
    }

    private Typography read() {
        return new Typography((String) family.getSelectedItem(), (FontWeight) weight.getSelectedItem(), italic.isSelected(),
                ((Number) size.getValue()).intValue(), (TextTransform) transform.getSelectedItem(), color.getHex(),
                (TextAlign) align.getSelectedItem(), (TextPosition) position.getSelectedItem(),
                ((Number) lineSpacing.getValue()).doubleValue(), shadow.isSelected(), shadowStrength.getValue() / 100.0,
                outline.isSelected(), outlineColor.getHex(), ((Number) outlineSize.getValue()).intValue());
    }

    private void updateDependent() {
        boolean on = useGlobal.isSelected();
        setEnabledRecursive(fields, on);
        shadowStrength.setEnabled(on && shadow.isSelected());
        outlineSize.setEnabled(on && outline.isSelected());
        outlineColor.setEnabled(on && outline.isSelected());
        shadowValue.setText(shadowStrength.getValue() + "%");
        String fam = (String) family.getSelectedItem();
        fontNote.setText(!on ? "Off: each theme's own text settings are used (edit them under Themes)."
                : fam != null && !Fonts.isInstalled(fam)
                ? "\"" + fam + "\" is not installed on this computer; " + Fonts.effectiveFamily(fam) + " is used until it is."
                : "Changes show in Preview at once. Program and the projector change on the next TAKE.");
    }

    private void updateSample() {
        SlideStyle style = settings.apply(kind, base, false);
        sample.setContent(new Slide("Sample", sampleText(), kind == ContentKind.BIBLE ? "John 3:16" : null), style);
    }

    private String sampleText() {
        return switch (kind) {
            case LYRICS -> "Amazing grace\nHow sweet the sound\nThat saved a wretch like me";
            case BIBLE -> "For God so loved the world, that he gave his only Son";
            case ANNOUNCEMENTS -> "Welcome to our church family\nFellowship lunch after the service";
            case CUSTOM_SLIDES -> "Welcome\nPlease silence your phones";
        };
    }

    private void selectFamily(String name) {
        for (int i = 0; i < family.getItemCount(); i++) {
            if (family.getItemAt(i).equalsIgnoreCase(name)) {
                family.setSelectedIndex(i);
                return;
            }
        }
        family.insertItemAt(name, 0); // not installed here: keep the saved name
        family.setSelectedIndex(0);
    }

    private void row(GridBagConstraints c, String l1, JComponent f1, String l2, JComponent f2) {
        c.gridwidth = 1;
        c.insets = new Insets(0, 0, 6, 8);
        c.gridx = 0;
        fields.add(Ui.label(l1, Palette::secondaryText, "small"), c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 6, 18);
        if (f2 == null) {
            c.gridwidth = 3;
        }
        fields.add(f1, c);
        if (f2 != null) {
            c.gridx = 2;
            c.insets = new Insets(0, 0, 6, 8);
            fields.add(Ui.label(l2, Palette::secondaryText, "small"), c);
            c.gridx = 3;
            fields.add(f2, c);
        }
        c.gridy++;
    }

    private static JPanel flow(JComponent... items) {
        JPanel p = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (JComponent i : items) {
            p.add(i);
        }
        return p;
    }

    private static void setEnabledRecursive(java.awt.Container c, boolean enabled) {
        for (java.awt.Component child : c.getComponents()) {
            child.setEnabled(enabled);
            if (child instanceof java.awt.Container cc) {
                setEnabledRecursive(cc, enabled);
            }
        }
    }
}
