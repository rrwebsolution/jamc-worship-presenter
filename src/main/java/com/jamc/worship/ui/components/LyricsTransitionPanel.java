package com.jamc.worship.ui.components;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.LyricsTransitionSettings;
import com.jamc.worship.config.LyricsTransitionSettings.Easing;
import com.jamc.worship.config.LyricsTransitionSettings.Mode;
import com.jamc.worship.config.TypographySettings;
import com.jamc.worship.model.ContentKind;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.output.LiveContent;
import com.jamc.worship.output.LyricsContext;
import com.jamc.worship.output.OutputCanvas;
import com.jamc.worship.output.ProgramOutput;
import com.jamc.worship.output.Transition;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.ui.Ui;
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
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings → Lyrics Transition: None / Smooth Fade / Spotify-Style Lyrics and the Spotify options, saved at once.
 *
 * <p>PREVIEW ANIMATION plays sample lyrics through its own Program engine (a private {@link ProgramOutput} and the
 * same {@link OutputCanvas} the projector uses), so it shows exactly what the projector will do and never touches
 * Preview or Program. Changes apply to the next lyrics card shown; what is live stays as it was sent.
 */
public final class LyricsTransitionPanel extends JPanel {

    private static final List<String> SAMPLE = List.of(
            "Amazing grace, how sweet the sound\nThat saved a wretch like me",
            "I once was lost, but now am found\nWas blind, but now I see",
            "'Twas grace that taught my heart to fear\nAnd grace my fears relieved",
            "How precious did that grace appear\nThe hour I first believed",
            "My chains are gone, I've been set free\nMy God, my Savior has ransomed me");

    private final AppConfig cfg;
    private final TypographySettings typo;
    private final JComboBox<Mode> mode = new JComboBox<>(Mode.values());
    private final JSpinner duration = new JSpinner(new SpinnerNumberModel(600, LyricsTransitionSettings.MIN_DURATION,
            LyricsTransitionSettings.MAX_DURATION, 50));
    private final JComboBox<Easing> easing = new JComboBox<>(Easing.values());
    private final JSpinner fontSize = new JSpinner(new SpinnerNumberModel(84, LyricsTransitionSettings.MIN_FONT,
            LyricsTransitionSettings.MAX_FONT, 2));
    private final JSlider dim = new JSlider(0, 80, 30);
    private final JLabel dimValue = Ui.small("30 %");
    private final JSpinner spacing = new JSpinner(new SpinnerNumberModel(56, 0, LyricsTransitionSettings.MAX_SPACING, 4));
    private final JButton highlight = Ui.button("#FFFFFF", "palette");
    private final JComboBox<TextAlign> align = new JComboBox<>(TextAlign.values());
    private final JCheckBox showPrevious = new JCheckBox("Show previous lyrics");
    private final JCheckBox showNext = new JCheckBox("Show next lyrics");
    /** Labels and controls shown for Smooth Fade and Spotify-style / for Spotify-style only. */
    private final List<JComponent> timingRows = new ArrayList<>();
    private final List<JComponent> spotifyRows = new ArrayList<>();
    private String highlightColor = "#FFFFFF";
    private boolean loading;

    // PREVIEW ANIMATION: its own engine, never Program
    private final ProgramOutput demo = new ProgramOutput();
    private final JPanel demoHolder = Ui.transparent(new BorderLayout(0, 6));
    private final JButton previewButton = Ui.primaryButton("Preview Animation", "play");
    private final Timer autoNext = new Timer(1900, e -> step(1));
    private OutputCanvas demoCanvas;
    private int demoIndex;

    public LyricsTransitionPanel(AppConfig cfg, TypographySettings typo) {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        this.cfg = cfg;
        this.typo = typo;
        load(LyricsTransitionSettings.load(cfg));

        // one grid, so every control starts at the same place; rows hide with the transition type
        JPanel options = Ui.transparent(new GridBagLayout());
        int r = 0;
        addRow(options, r++, "Transition type:", mode, null);
        addRow(options, r++, "Transition duration (ms):", flowRow(duration, Ui.small("200 – 1500")), timingRows);
        addRow(options, r++, "Animation easing:", easing, timingRows);
        addRow(options, r++, "Current lyrics font size:", flowRow(fontSize, Ui.small("px on a 1080p screen")), spotifyRows);
        addRow(options, r++, "Previous / next opacity:", flowRow(dim, dimValue), spotifyRows);
        addRow(options, r++, "Vertical spacing:", flowRow(spacing, Ui.small("px")), spotifyRows);
        addRow(options, r++, "Highlight color (current lyrics):", highlight, spotifyRows);
        addRow(options, r++, "Text alignment:", align, spotifyRows);
        addRow(options, r, "", flowRow(showPrevious, showNext), spotifyRows);

        JPanel south = Ui.transparent(new BorderLayout(0, 8));
        south.add(flowRow(previewButton, Ui.small("Plays sample lyrics here only. Preview and Program are not changed.")),
                BorderLayout.NORTH);
        south.add(demoHolder, BorderLayout.CENTER);
        south.add(Ui.label("<html>Font, weight, letter case and shadow come from Lyrics Typography above. "
                + "Changes apply to the next lyrics card shown; the card that is live stays as it was sent. "
                + "Bible verses, announcements and videos keep the Program transition.</html>",
                Palette::mutedText, "small"), BorderLayout.SOUTH);

        add(options, BorderLayout.NORTH);
        JPanel pinned = Ui.transparent(new BorderLayout()); // stays at its own height, at the top
        pinned.add(south, BorderLayout.NORTH);
        add(pinned, BorderLayout.CENTER);

        mode.addActionListener(e -> changed());
        easing.addActionListener(e -> changed());
        align.addActionListener(e -> changed());
        duration.addChangeListener(e -> changed());
        fontSize.addChangeListener(e -> changed());
        spacing.addChangeListener(e -> changed());
        dim.addChangeListener(e -> {
            dimValue.setText(dim.getValue() + " %");
            if (!dim.getValueIsAdjusting()) {
                changed();
            }
        });
        showPrevious.addActionListener(e -> changed());
        showNext.addActionListener(e -> changed());
        highlight.addActionListener(e -> {
            Color picked = JColorChooser.showDialog(this, "Highlight color", Colors.parse(highlightColor, Color.WHITE));
            if (picked != null) {
                highlightColor = String.format("#%02X%02X%02X", picked.getRed(), picked.getGreen(), picked.getBlue());
                showHighlight();
                changed();
            }
        });
        previewButton.addActionListener(e -> toggleDemo());
        updateVisibility();
    }

    // ------------------------------------------------------------------ settings

    private void load(LyricsTransitionSettings s) {
        loading = true;
        try {
            mode.setSelectedItem(s.mode());
            duration.setValue(s.durationMs());
            easing.setSelectedItem(s.easing());
            fontSize.setValue(s.fontSize());
            dim.setValue(Math.round(s.dimOpacity() * 100));
            dimValue.setText(dim.getValue() + " %");
            spacing.setValue(s.spacing());
            highlightColor = s.highlightColor();
            showHighlight();
            align.setSelectedItem(s.align());
            showPrevious.setSelected(s.showPrevious());
            showNext.setSelected(s.showNext());
        } finally {
            loading = false;
        }
    }

    /** The settings as shown in the controls. */
    public LyricsTransitionSettings current() {
        return new LyricsTransitionSettings((Mode) mode.getSelectedItem(), (Integer) duration.getValue(),
                (Integer) fontSize.getValue(), dim.getValue() / 100f, (Integer) spacing.getValue(), highlightColor,
                (TextAlign) align.getSelectedItem(), showPrevious.isSelected(), showNext.isSelected(),
                (Easing) easing.getSelectedItem());
    }

    private void changed() {
        if (loading) {
            return;
        }
        current().save(cfg);
        updateVisibility();
        if (demoCanvas != null) {
            demo.updateContent(sampleCard(demoIndex)); // the sample shows the new look at once (never Program)
        }
    }

    private void updateVisibility() {
        Mode m = (Mode) mode.getSelectedItem();
        timingRows.forEach(c -> c.setVisible(m != Mode.NONE));
        spotifyRows.forEach(c -> c.setVisible(m == Mode.SPOTIFY));
        revalidate();
        repaint();
    }

    private void showHighlight() {
        highlight.setText(highlightColor);
        Color c = Colors.parse(highlightColor, Color.WHITE);
        highlight.setIcon(new javax.swing.Icon() {
            @Override
            public void paintIcon(java.awt.Component comp, java.awt.Graphics g, int x, int y) {
                g.setColor(c);
                g.fillRoundRect(x, y, 14, 14, 4, 4);
                g.setColor(Color.GRAY);
                g.drawRoundRect(x, y, 14, 14, 4, 4);
            }

            @Override
            public int getIconWidth() {
                return 15;
            }

            @Override
            public int getIconHeight() {
                return 15;
            }
        });
    }

    // ------------------------------------------------------------------ PREVIEW ANIMATION

    private void toggleDemo() {
        if (demoCanvas != null) {
            stopDemo();
            return;
        }
        demoCanvas = new OutputCanvas(demo) {
            @Override
            protected boolean operatorMonitor() {
                return true;
            }
        };
        demoCanvas.setPreferredSize(new Dimension(560, 315));
        demoCanvas.setBorder(BorderFactory.createLineBorder(new Color(0x334155)));
        JButton prev = Ui.button("Previous", "chevron-left");
        JButton next = Ui.button("Next", "chevron-right");
        JCheckBox auto = new JCheckBox("Auto play", true);
        prev.addActionListener(e -> step(-1));
        next.addActionListener(e -> step(1));
        auto.addActionListener(e -> {
            if (auto.isSelected()) {
                autoNext.restart();
            } else {
                autoNext.stop();
            }
        });
        JPanel canvasRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        canvasRow.add(demoCanvas);
        demoHolder.add(canvasRow, BorderLayout.CENTER);
        demoHolder.add(flowRow(prev, next, auto, Ui.small("Try pressing Next quickly: the lyrics never jump.")),
                BorderLayout.SOUTH);
        demo.setTransition(Transition.FADE, 500); // what "None" falls back to in this sample
        Scene scene = new Scene(-1L, "Lyrics transition sample");
        scene.getSources().add(new Source("Lyrics", SourceType.LYRICS));
        Source bg = new Source("Background", SourceType.COLOR);
        bg.getConfig().color = "#14213D";
        scene.getSources().add(bg);
        demoIndex = 0;
        demo.take(sampleCard(0), scene);
        autoNext.restart();
        previewButton.setText("Stop Preview");
        revalidate();
        repaint();
    }

    private void stopDemo() {
        autoNext.stop();
        demoHolder.removeAll();
        demoCanvas = null; // removed from the window: it stops listening for frames
        previewButton.setText("Preview Animation");
        revalidate();
        repaint();
    }

    private void step(int delta) {
        if (demoCanvas == null || !isShowing()) {
            if (demoCanvas != null && !isShowing()) {
                autoNext.stop(); // Settings was left: the sample stops
            }
            return;
        }
        demoIndex = Math.floorMod(demoIndex + delta, SAMPLE.size());
        demo.take(sampleCard(demoIndex));
    }

    private LiveContent sampleCard(int index) {
        LyricsTransitionSettings s = current();
        SlideStyle style = typo == null ? SlideStyle.fallback() : typo.apply(ContentKind.LYRICS, SlideStyle.fallback(), false);
        Slide slide = new Slide("Sample", SAMPLE.get(index), null);
        slide.setSongId(-1L);
        LyricsContext ctx = s.mode() == Mode.NONE ? null : new LyricsContext(-1L, new ArrayList<>(SAMPLE), index, s);
        return LiveContent.snapshot(slide, style, "Sample", ctx);
    }

    @Override
    public void removeNotify() {
        autoNext.stop();
        super.removeNotify();
    }

    // ------------------------------------------------------------------ layout helpers

    /** Controls in a row, the first flush left (so they line up with the plain controls above and below). */
    private static JPanel flowRow(JComponent... items) {
        JPanel p = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                p.add(javax.swing.Box.createHorizontalStrut(10));
            }
            p.add(items[i]);
        }
        return p;
    }

    /** A label and its control in the grid; {@code group} (if any) remembers both so the row hides with it. */
    private static void addRow(JPanel grid, int row, String label, JComponent control, List<JComponent> group) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 12);
        JLabel l = Ui.small(label);
        grid.add(l, c);
        c.gridx = 1;
        c.weightx = 1;
        c.insets = new Insets(3, 0, 3, 0);
        grid.add(control, c);
        if (group != null) {
            group.add(l);
            group.add(control);
        }
    }
}
