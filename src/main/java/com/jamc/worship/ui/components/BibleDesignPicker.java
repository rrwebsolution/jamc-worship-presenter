package com.jamc.worship.ui.components;

import com.jamc.worship.bible.BibleDesign;
import com.jamc.worship.bible.BibleDisplay;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.TypographySettings;
import com.jamc.worship.model.ContentKind;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
import java.util.Map;

/**
 * Settings → Bible Design: a card per ready-made look, each with a small sample on the Bible theme's background.
 * A click applies it (Bible typography + reference options); the editors below stay available for fine-tuning,
 * and a hand-made change shows as "Custom".
 */
public class BibleDesignPicker extends JPanel {

    private final TypographySettings typography;
    private final AppConfig config;
    private final Runnable applied;
    private final Map<BibleDesign, SlidePreviewCanvas> samples = new EnumMap<>(BibleDesign.class);
    private final JLabel current = Ui.label(" ", Palette::secondaryText, "small");
    private SlideStyle base = SlideStyle.fallback();

    /** @param applied runs after a design was applied (the other Bible controls reload) */
    public BibleDesignPicker(TypographySettings typography, AppConfig config, Runnable applied) {
        super(new BorderLayout(0, 10));
        this.typography = typography;
        this.config = config;
        this.applied = applied;
        setOpaque(false);

        JPanel grid = Ui.transparent(new GridLayout(0, 4, 12, 12));
        for (BibleDesign d : BibleDesign.values()) {
            grid.add(card(d));
        }
        JPanel gridHolder = Ui.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        gridHolder.add(grid);
        add(current, BorderLayout.NORTH);
        add(gridHolder, BorderLayout.CENTER);
        add(Ui.label("<html>Click a design to use it for every verse. Fine-tune it below in Bible Typography and Bible "
                + "Presentation. The background comes from the Bible theme. Preview changes at once; Program on the next "
                + "TAKE.</html>", Palette::mutedText, "small"), BorderLayout.SOUTH);
        typography.addListener(this::refresh);
        refresh();
    }

    /** The background the samples are drawn on (the Bible theme's). */
    public void setBaseStyle(SlideStyle style) {
        base = style == null ? SlideStyle.fallback() : style;
        refresh();
    }

    /** Redraws the samples and marks the design that matches the saved settings. */
    public void refresh() {
        BibleDesign chosen = chosen();
        for (Map.Entry<BibleDesign, SlidePreviewCanvas> e : samples.entrySet()) {
            BibleDesign d = e.getKey();
            e.getValue().setContent(sampleSlide(d), d.typography().applyTo(base));
            e.getValue().setFrameColor(d == chosen ? Palette::accent : null);
        }
        current.setText(chosen != null ? "Current design: " + chosen.label()
                : typography.isGlobal(ContentKind.BIBLE) ? "Current design: Custom (changed by hand below)"
                : "Current design: the Bible theme's own text settings");
    }

    private BibleDesign chosen() {
        if (!typography.isGlobal(ContentKind.BIBLE)) {
            return null;
        }
        return BibleDesign.matching(typography.get(ContentKind.BIBLE),
                BibleDisplay.ReferencePosition.parse(config.get(SettingsPanel.KEY_BIBLE_REF_POSITION, null)),
                config.getBoolean(SettingsPanel.KEY_BIBLE_UPPERCASE, true),
                config.getBoolean(SettingsPanel.KEY_BIBLE_SHOW_VERSION, true));
    }

    private void apply(BibleDesign d) {
        config.set(SettingsPanel.KEY_BIBLE_REF_POSITION, d.referencePosition().name());
        config.set(SettingsPanel.KEY_BIBLE_UPPERCASE, String.valueOf(d.uppercaseReference()));
        config.set(SettingsPanel.KEY_BIBLE_SHOW_VERSION, String.valueOf(d.showVersion()));
        if (!typography.isGlobal(ContentKind.BIBLE)) {
            typography.setGlobal(ContentKind.BIBLE, true);
        }
        typography.set(ContentKind.BIBLE, d.typography()); // saves the config and tells the app (Preview rebuilds)
        refresh();
        applied.run();
    }

    private JPanel card(BibleDesign d) {
        SlidePreviewCanvas sample = new SlidePreviewCanvas();
        sample.setCompact(true);
        Dimension size = new Dimension(224, 126);
        sample.setPreferredSize(size);
        sample.setMinimumSize(size);
        samples.put(d, sample);

        JPanel card = Ui.transparent(new BorderLayout(0, 4));
        card.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        card.add(sample, BorderLayout.CENTER);
        JPanel text = Ui.transparent(new GridLayout(0, 1, 0, 0));
        text.add(Ui.label(d.label(), Palette::secondaryText, "semibold"));
        text.add(Ui.label(d.description(), Palette::mutedText, "small"));
        card.add(text, BorderLayout.SOUTH);
        card.setToolTipText("Use the " + d.label() + " design for Bible verses");
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                apply(d);
            }
        };
        card.addMouseListener(click);
        sample.addMouseListener(click);
        sample.setCursor(card.getCursor());
        sample.setToolTipText(card.getToolTipText());
        return card;
    }

    /** "JOHN 3:16" and "NIV" placed the way the design places them (as BibleSlideBuilder does). */
    private static Slide sampleSlide(BibleDesign d) {
        String ref = d.uppercaseReference() ? "JOHN 3:16" : "John 3:16";
        String ver = d.showVersion() ? "NIV" : null;
        String[] hc = BibleDisplay.headingAndCaption(d.referencePosition(), ref, ver);
        Slide s = new Slide(d.label(), "For God so loved the world, that he gave his only Son", hc[1]);
        s.setHeading(hc[0]);
        return s;
    }
}
