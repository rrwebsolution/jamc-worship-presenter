package com.jamc.worship.ui.components;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.LyricsLayouts;
import com.jamc.worship.config.ProgramLyricsStyle;
import com.jamc.worship.config.ProgramLyricsStyle.Fade;
import com.jamc.worship.config.ProgramLyricsStyle.Easing;
import com.jamc.worship.config.ProgramLyricsStyle.Mode;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.output.LowerThirdLyrics;
import com.jamc.worship.output.LyricsContext;
import com.jamc.worship.config.LyricsTransitionSettings;
import com.jamc.worship.render.Fonts;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.util.Colors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * Settings → Lyrics Appearance: PREVIEW STYLE (the normal lyrics look of Preview and the church projector, set in
 * Lyrics Typography and Lyrics Transition) and PROGRAM STYLE (what the livestream, recording, OBS window and Program
 * monitor show: by default a lower third over the camera).
 *
 * <p>Program Style edits a draft with a live sample picture. SAVE SETTINGS keeps the draft; only APPLY TO PROGRAM
 * changes the live outputs, so nothing on air changes while the operator is still adjusting.
 */
public final class LyricsAppearancePanel extends JPanel implements SettingsCard {

    private static final String SAMPLE = "Worthy of ev'ry breath\nWe could ever breathe";

    private final AppConfig cfg;
    /** Lower Third, Centered bars, Full Screen, then the operator's own layouts (My layouts). */
    private final JComboBox<Object> mode = new JComboBox<>();
    private final JButton newLayout = Ui.button("New Layout…", "plus");
    private final JButton editLayout = Ui.button("Edit…", null);
    private final JButton deleteLayout = Ui.button("Delete", null);
    private final JComboBox<String> family = new JComboBox<>();
    private final JSpinner size = new JSpinner(new SpinnerNumberModel(42, 16, 160, 1));
    private final JCheckBox bold = new JCheckBox("Bold");
    private final JCheckBox uppercase = new JCheckBox("UPPERCASE");
    private final ColorButton textColor = new ColorButton("Text color");
    private final JComboBox<TextAlign> align = new JComboBox<>(TextAlign.values());
    private final ColorButton barColor = new ColorButton("Bar color");
    private final JSlider barOpacity = percent(0, 100);
    private final JSlider offsetX = percent(-40, 40);
    private final JSlider bottomMargin = percent(0, 40);
    private final JSpinner lineSpacing = new JSpinner(new SpinnerNumberModel(8, 0, 80, 1));
    private final JSpinner padV = new JSpinner(new SpinnerNumberModel(12, 0, 60, 1));
    private final JSpinner padH = new JSpinner(new SpinnerNumberModel(24, 0, 120, 2));
    private final JSpinner maxLines = new JSpinner(new SpinnerNumberModel(2, 1, 6, 1));
    private final JSlider minWidth = percent(0, 94);
    private final JSlider maxWidth = percent(20, 94);
    private final JSpinner corner = new JSpinner(new SpinnerNumberModel(0, 0, 40, 1));
    private final JCheckBox outline = new JCheckBox("Text outline");
    private final JCheckBox shadow = new JCheckBox("Text shadow");
    private final JComboBox<Fade> fade = new JComboBox<>(Fade.values());
    private final JSpinner fadeMs = new JSpinner(new SpinnerNumberModel(400, 0, 2000, 50));
    private final JCheckBox showPrevious = new JCheckBox("Show previous lyrics");
    private final JCheckBox showNext = new JCheckBox("Show next lyrics");
    private final JSpinner spotifySpacing = new JSpinner(new SpinnerNumberModel(170, 20, 400, 10));
    private final JComboBox<Easing> easing = new JComboBox<>(Easing.values());
    private final JCheckBox transparent = new JCheckBox("<html>Use Transparent Lyrics Overlay in Program<br><small>the camera / scene stays visible; the song's own background is not drawn</small></html>");
    private final JLabel status = Ui.label(" ", Palette::secondaryText, "small");
    private final Sample sample = new Sample();
    private boolean loading;

    public LyricsAppearancePanel(AppConfig cfg) {
        super(new BorderLayout());
        setOpaque(false);
        this.cfg = cfg;
        fillModes();
        add(programTab(), BorderLayout.CENTER);
        load(ProgramLyricsStyle.draft(cfg));
    }

    private JComponent programTab() {
        JPanel grid = Ui.transparent(new GridBagLayout());
        family.setModel(new javax.swing.DefaultComboBoxModel<>(Fonts.pickerFamilies(ProgramLyricsStyle.draft(cfg).fontFamily())
                .toArray(String[]::new)));
        int r = 0;
        row(grid, r++, "Sample shows:", mode);
        row(grid, r++, "My layouts:", flow(newLayout, editLayout, deleteLayout,
                Ui.small("your own lyrics box: drag it anywhere on the picture")));
        row(grid, r++, "Font:", flow(family, size, Ui.small("px at 1080p")));
        row(grid, r++, "", flow(bold, uppercase, outline, shadow));
        row(grid, r++, "Text color / alignment:", flow(textColor, align));
        row(grid, r++, "Bar color / opacity:", flow(barColor, barOpacity, Ui.small("Corners"), corner));
        row(grid, r++, "Bar width (min / max %):", flow(minWidth, maxWidth));
        row(grid, r++, "Bar padding (px):", flow(Ui.small("top/bottom"), padV, Ui.small("left/right"), padH));
        row(grid, r++, "Line spacing (px):", lineSpacing);
        row(grid, r++, "Bottom margin (%):", bottomMargin);
        row(grid, r++, "Horizontal position:", flow(offsetX, Ui.small("left ◀ ▶ right")));
        row(grid, r++, "Maximum visible lines:", flow(maxLines, Ui.small("longer cards re-flow, then get smaller; never cut")));
        row(grid, r++, "Lyrics transition:", flow(fade, fadeMs, Ui.small("ms")));
        row(grid, r++, "Animation easing:", easing);
        row(grid, r++, "Spotify-style setup:", Ui.small("Uses the same previous/next, opacity, vertical spacing and easing as Full Lyrics."));
        row(grid, r, "", transparent);
        for (JSlider s : new JSlider[]{barOpacity, offsetX, bottomMargin, minWidth, maxWidth}) {
            s.setPreferredSize(new Dimension(160, s.getPreferredSize().height));
            s.setMinimumSize(s.getPreferredSize());
            s.setToolTipText("");
            s.addChangeListener(e -> s.setToolTipText(s.getValue() + " %"));
        }

        JButton reset = Ui.button("Reset to Defaults", "refresh");
        reset.addActionListener(e -> {
            load(ProgramLyricsStyle.defaults());
            changed();
        });

        JPanel right = Ui.transparent(new BorderLayout(0, 6));
        right.add(Ui.label("Sample (how the stream will look)", Palette::secondaryText, "small"), BorderLayout.NORTH);
        right.add(sample, BorderLayout.CENTER);

        JPanel body = Ui.transparent(new BorderLayout(16, 10));
        body.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        JPanel top = Ui.transparent(new BorderLayout());
        top.add(grid, BorderLayout.NORTH);
        body.add(top, BorderLayout.CENTER);
        body.add(right, BorderLayout.EAST);
        JPanel south = Ui.transparent(new BorderLayout(0, 6));
        south.add(flow(reset), BorderLayout.NORTH); // SAVE / APPLY are the Settings card's own buttons
        south.add(status, BorderLayout.CENTER);
        south.add(Ui.label("<html><b>Each scene chooses its own layout</b>: Edit Scene → its Lyrics layer → <b>Lyrics layout</b> "
                + "(e.g. \"Camera + Lyrics\" = Lower third, \"Lyrics\" = Full lyrics). Every Lyrics layer starts as "
                + "Full lyrics. Preview shows exactly what TAKE puts on Program, the stream, the recording and OBS; the church "
                + "projector keeps the full lyrics. The font, colors and bars set here are used by every scene with bars. "
                + "Song lyrics only; Bible verses and slides keep their own look.</html>", Palette::mutedText, "small"),
                BorderLayout.SOUTH);
        body.add(south, BorderLayout.SOUTH);

        for (JComboBox<?> c : new JComboBox<?>[]{mode, family, align, fade, easing}) {
            c.addActionListener(e -> changed());
        }
        for (JSpinner s : new JSpinner[]{size, lineSpacing, padV, padH, maxLines, corner, fadeMs, spotifySpacing}) {
            s.addChangeListener(e -> changed());
        }
        for (JSlider s : new JSlider[]{barOpacity, offsetX, bottomMargin, minWidth, maxWidth}) {
            s.addChangeListener(e -> changed());
        }
        for (JCheckBox b : new JCheckBox[]{bold, uppercase, outline, shadow, transparent, showPrevious, showNext}) {
            b.addActionListener(e -> changed());
        }
        textColor.onChange = this::changed;
        barColor.onChange = this::changed;
        newLayout.addActionListener(e -> {
            LyricsLayouts.Layout made = LyricsLayoutEditor.edit(this, current(), null);
            if (made != null) {
                LyricsLayouts.save(cfg, made);
                fillModes();
                mode.setSelectedItem(find(made.id()));
                updateStatus("Saved \"" + made.name() + "\". Use it in Edit Scene → Lyrics layer → Lyrics layout.");
            }
        });
        editLayout.addActionListener(e -> {
            if (mode.getSelectedItem() instanceof LyricsLayouts.Layout l) {
                LyricsLayouts.Layout edited = LyricsLayoutEditor.edit(this, current(), l);
                if (edited != null) {
                    LyricsLayouts.save(cfg, edited);
                    fillModes();
                    mode.setSelectedItem(find(edited.id()));
                    updateStatus("Saved \"" + edited.name() + "\". Scenes that use it show the new place at once.");
                }
            }
        });
        deleteLayout.addActionListener(e -> {
            if (mode.getSelectedItem() instanceof LyricsLayouts.Layout l && javax.swing.JOptionPane.showConfirmDialog(this,
                    "Delete the layout \"" + l.name() + "\"?\nScenes that use it show the Lower Third instead.", "Delete Layout",
                    javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) {
                LyricsLayouts.delete(cfg, l.id());
                fillModes();
                mode.setSelectedItem(Mode.LOWER_THIRD);
            }
        });
        mode.addActionListener(e -> updateLayoutButtons());
        updateLayoutButtons();
        return body;
    }

    // ------------------------------------------------------------------ my layouts

    /** The built-in bar layouts, then the saved custom layouts (the selection is kept when it still exists). */
    private void fillModes() {
        Object keep = mode.getSelectedItem();
        boolean was = loading;
        loading = true;
        try {
            mode.removeAllItems();
            for (Mode m : new Mode[]{Mode.LOWER_THIRD, Mode.CENTERED, Mode.FULL_SCREEN}) {
                mode.addItem(m);
            }
            for (LyricsLayouts.Layout l : LyricsLayouts.all()) {
                mode.addItem(l);
            }
            Object again = keep instanceof LyricsLayouts.Layout l ? find(l.id()) : keep;
            mode.setSelectedItem(again != null ? again : Mode.LOWER_THIRD);
        } finally {
            loading = was;
        }
        updateLayoutButtons();
    }

    private LyricsLayouts.Layout find(String id) {
        return LyricsLayouts.all().stream().filter(l -> l.id().equals(id)).findFirst().orElse(null);
    }

    /** The list entry for a saved style: its custom layout (same box), else its built-in layout. */
    private Object modeItem(ProgramLyricsStyle s) {
        if (s.mode() == Mode.CUSTOM) {
            return LyricsLayouts.all().stream().filter(l -> l.box().equals(s.box())).findFirst()
                    .map(l -> (Object) l).orElse(Mode.LOWER_THIRD);
        }
        return s.mode() == Mode.OFF ? Mode.LOWER_THIRD : s.mode();
    }

    private void updateLayoutButtons() {
        boolean custom = mode.getSelectedItem() instanceof LyricsLayouts.Layout;
        editLayout.setEnabled(custom);
        deleteLayout.setEnabled(custom);
        String tip = custom ? null : "Choose one of your layouts in \"Sample shows\" first";
        editLayout.setToolTipText(tip);
        deleteLayout.setToolTipText(tip);
    }

    // ------------------------------------------------------------------ values

    private void load(ProgramLyricsStyle s) {
        loading = true;
        try {
            mode.setSelectedItem(modeItem(s));
            if (((javax.swing.DefaultComboBoxModel<String>) family.getModel()).getIndexOf(s.fontFamily()) < 0) {
                family.addItem(s.fontFamily());
            }
            family.setSelectedItem(s.fontFamily());
            size.setValue(s.fontSize());
            bold.setSelected(s.bold());
            uppercase.setSelected(s.uppercase());
            textColor.set(s.textColor());
            align.setSelectedItem(s.align());
            barColor.set(s.barColor());
            barOpacity.setValue(Math.round(s.barOpacity() * 100));
            offsetX.setValue(Math.round(s.offsetX() * 100));
            bottomMargin.setValue(Math.round(s.bottomMargin() * 100));
            lineSpacing.setValue(s.lineSpacing());
            padV.setValue(s.padV());
            padH.setValue(s.padH());
            maxLines.setValue(s.maxLines());
            minWidth.setValue(Math.round(s.minBarWidth() * 100));
            maxWidth.setValue(Math.round(s.maxBarWidth() * 100));
            corner.setValue(s.cornerRadius());
            outline.setSelected(s.outline());
            shadow.setSelected(s.shadow());
            fade.setSelectedItem(s.fade());
            fadeMs.setValue(s.fadeMs());
            transparent.setSelected(s.transparentOverlay());
            showPrevious.setSelected(s.showPrevious());
            showNext.setSelected(s.showNext());
            spotifySpacing.setValue(s.spotifySpacing());
            easing.setSelectedItem(s.easing());
        } finally {
            loading = false;
        }
        sample.repaint();
        updateStatus(null);
    }

    /** The draft as shown in the controls. */
    @Override
    public void saveCard() {
        ProgramLyricsStyle.saveDraft(cfg, current());
        updateStatus("Saved. The live Program still uses its applied style; press Apply to use these.");
    }

    @Override
    public void applyCard() {
        ProgramLyricsStyle.apply(cfg, current());
        updateStatus("Applied: Program now shows these lyrics settings.");
    }

    public ProgramLyricsStyle current() {
        Fade selectedFade = (Fade) fade.getSelectedItem();
        LyricsTransitionSettings spotify = selectedFade == Fade.SPOTIFY
                ? LyricsTransitionSettings.load(cfg) : null;
        Object picked = mode.getSelectedItem();
        ProgramLyricsStyle base = new ProgramLyricsStyle(picked instanceof Mode m ? m : Mode.LOWER_THIRD, (String) family.getSelectedItem(), (Integer) size.getValue(),
                bold.isSelected(), uppercase.isSelected(), textColor.value, (TextAlign) align.getSelectedItem(), barColor.value,
                barOpacity.getValue() / 100f, offsetX.getValue() / 100f, bottomMargin.getValue() / 100f,
                (Integer) lineSpacing.getValue(), (Integer) padV.getValue(), (Integer) padH.getValue(), (Integer) maxLines.getValue(),
                minWidth.getValue() / 100f, maxWidth.getValue() / 100f, (Integer) corner.getValue(), outline.isSelected(),
                shadow.isSelected(), selectedFade, spotify == null ? (Integer) fadeMs.getValue() : spotify.durationMs(),
                transparent.isSelected(), spotify == null ? showPrevious.isSelected() : spotify.showPrevious(),
                spotify == null ? showNext.isSelected() : spotify.showNext(),
                spotify == null ? (Integer) spotifySpacing.getValue() : spotify.spacing(),
                spotify == null ? (Easing) easing.getSelectedItem() : Easing.valueOf(spotify.easing().name()));
        return picked instanceof LyricsLayouts.Layout l ? base.withBox(l.box()) : base;
    }

    private void changed() {
        if (loading) {
            return;
        }
        sample.repaint();
        updateStatus(null);
    }

    private void updateStatus(String message) {
        boolean live = current().equals(ProgramLyricsStyle.applied());
        status.setText(message != null ? message : live ? "● Program is using these settings."
                : "○ Not on Program yet: Save Settings keeps them, Apply to Program puts them on the stream.");
        status.setForeground(live ? Palette.live() : Palette.warning());
    }

    // ------------------------------------------------------------------ sample picture

    /** A stand-in camera picture with the sample lyrics drawn by the real lower-third painter. */
    private final class Sample extends JComponent {
        Sample() {
            setPreferredSize(new Dimension(360, 203));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth();
            int h = Math.min(getHeight(), w * 9 / 16);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.clipRect(0, 0, w, h); // the 16:9 picture only
            g.setPaint(new GradientPaint(0, 0, new Color(0x1B2A4A), w, h, new Color(0x0A1020)));
            g.fillRect(0, 0, w, h);
            // a singer-like silhouette so the bars are seen over a "camera"
            g.setColor(new Color(0x3B4A66));
            g.fillOval(w * 40 / 100, h * 12 / 100, w * 16 / 100, h * 30 / 100);
            g.fillRoundRect(w * 30 / 100, h * 40 / 100, w * 36 / 100, h, w / 10, w / 10);
            ProgramLyricsStyle s = current();
            if (s.active()) {
                LyricsContext sampleContext = new LyricsContext(-1L,
                        java.util.List.of("You alone are worthy", SAMPLE, "We live for You"), 1,
                        LyricsTransitionSettings.load(cfg).withMode(LyricsTransitionSettings.Mode.SPOTIFY));
                LowerThirdLyrics.paint(g, w, h, SAMPLE, s, sampleContext);
            } else {
                g.setColor(new Color(255, 255, 255, 200));
                g.drawString("Off: Program shows the Preview Style lyrics", 10, h - 12);
            }
            g.setColor(new Color(0x334155));
            g.drawRect(0, 0, w - 1, h - 1);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class ColorButton extends JButton {
        String value = "#FFFFFF";
        Runnable onChange = () -> { };

        ColorButton(String title) {
            super(" ");
            setToolTipText(title);
            setPreferredSize(new Dimension(44, 26));
            addActionListener(e -> {
                Color c = JColorChooser.showDialog(this, title, Colors.parse(value, Color.WHITE));
                if (c != null) {
                    set(String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
                    onChange.run();
                }
            });
        }

        void set(String hex) {
            value = hex;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Colors.parse(value, Color.WHITE));
            g.fillRoundRect(6, 5, getWidth() - 12, getHeight() - 10, 4, 4);
            g.setColor(Color.GRAY);
            g.drawRoundRect(6, 5, getWidth() - 12, getHeight() - 10, 4, 4);
        }
    }

    private static JSlider percent(int min, int max) {
        JSlider s = new JSlider(min, max, min);
        s.setPreferredSize(new Dimension(110, s.getPreferredSize().height));
        s.setOpaque(false);
        return s;
    }

    private static JPanel flow(JComponent... items) {
        JPanel p = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                p.add(javax.swing.Box.createHorizontalStrut(8));
            }
            p.add(items[i]);
        }
        return p;
    }

    private static void row(JPanel grid, int row, String label, JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 12);
        grid.add(Ui.small(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.insets = new Insets(3, 0, 3, 0);
        grid.add(control, c);
    }
}
