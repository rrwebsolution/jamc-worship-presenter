package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.FontWeight;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.render.Fonts;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Center workspace: details of the selected item and a large 16:9 preview of the selected slide.
 */
public class SongDetailPanel extends JPanel {

    private final JLabel title = Ui.label(" ", Palette::text, "h2");
    private final JLabel meta = Ui.secondary(" ");
    private final JLabel info = Ui.small(" ");
    private final SlidePreviewCanvas canvas = new SlidePreviewCanvas();
    private final JButton editButton = Ui.button("Edit Song", "edit");
    private final JButton addButton = Ui.button("Add to Service", "list-plus");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private String infoText = "";
    private String themeName;
    private Song song;
    private boolean loadingQuick;
    private Consumer<Song> quickListener = s -> { };
    private final JComboBox<String> quickFont = new JComboBox<>(Fonts.families().toArray(String[]::new));
    private final JSpinner quickSize = new JSpinner(new SpinnerNumberModel(54, SlideStyle.MIN_FONT_SIZE, SlideStyle.MAX_FONT_SIZE, 2));
    private final JComboBox<FontWeight> quickWeight = new JComboBox<>(FontWeight.values());
    private final JButton quickCase = new JButton("AA");
    private final JComboBox<TextAlign> quickAlign = new JComboBox<>(TextAlign.values());
    private static final String ALL_SONGS = "All Songs";
    private static final String THIS_SONG = "This Song Only";
    /** APPLY TO: All Songs = the global lyrics typography (Settings → Lyrics Typography); This Song Only = this song. */
    private final JComboBox<String> quickTarget = new JComboBox<>(new String[]{ALL_SONGS, THIS_SONG});
    private java.util.function.Consumer<java.util.function.UnaryOperator<com.jamc.worship.model.Typography>> globalEditor = op -> { };
    private TextTransform shownCase = TextTransform.ORIGINAL;

    /** Where All Songs changes go: the global lyrics typography (the same values as Settings → Lyrics Typography). */
    public void setGlobalTypographyEditor(
            java.util.function.Consumer<java.util.function.UnaryOperator<com.jamc.worship.model.Typography>> editor) {
        globalEditor = editor == null ? op -> { } : editor;
    }

    public SongDetailPanel(Runnable onEdit, Runnable onAddToService) {
        super(new BorderLayout());
        setOpaque(false);

        editButton.addActionListener(e -> onEdit.run());
        addButton.addActionListener(e -> onAddToService.run());

        JPanel header = Ui.header(title, meta, addButton, editButton);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 12)));

        canvas.setBadge("SELECTED SLIDE", Palette::mutedText);
        canvas.setEmptyMessage("This section has no lyrics.");
        JPanel previewHolder = Ui.transparent(new BorderLayout(0, 8));
        previewHolder.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        previewHolder.add(canvas, BorderLayout.CENTER);
        JPanel infoRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        infoRow.add(info);
        previewHolder.add(infoRow, BorderLayout.SOUTH);

        JPanel songView = Ui.transparent(new BorderLayout());
        JPanel top = Ui.transparent(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(buildQuickBar(), BorderLayout.SOUTH);
        songView.add(top, BorderLayout.NORTH);
        songView.add(previewHolder, BorderLayout.CENTER);

        JPanel emptyView = Ui.transparent(new BorderLayout());
        JLabel emptyTitle = Ui.label("Nothing selected", Palette::text, "h3");
        JLabel emptyText = Ui.label("<html><center>Choose a song from the library to preview it here.<br>"
                + "Double-click a song to edit it, or press Ctrl+N to add a new one.</center></html>",
                Palette::secondaryText, null);
        emptyTitle.setHorizontalAlignment(JLabel.CENTER);
        emptyText.setHorizontalAlignment(JLabel.CENTER);
        JPanel emptyBox = Ui.transparent(new BorderLayout(0, 8));
        emptyBox.add(emptyTitle, BorderLayout.NORTH);
        emptyBox.add(emptyText, BorderLayout.CENTER);
        JPanel centerer = Ui.transparent(new java.awt.GridBagLayout());
        centerer.add(emptyBox);
        emptyView.add(centerer, BorderLayout.CENTER);

        body.add(songView, "song");
        body.add(emptyView, "empty");
        add(body, BorderLayout.CENTER);
        setSong(null);
    }

    public void setSong(Song song) {
        this.song = song;
        if (song == null) {
            cards.show(body, "empty");
            canvas.setContent(null, null);
            return;
        }
        title.setText(song.getTitle());
        List<String> parts = new ArrayList<>();
        parts.add(song.getAuthor() == null || song.getAuthor().isBlank() ? "Unknown author" : song.getAuthor());
        if (song.getCopyright() != null && !song.getCopyright().isBlank()) {
            parts.add(song.getCopyright());
        }
        if (song.getCcliNumber() != null && !song.getCcliNumber().isBlank()) {
            parts.add("CCLI " + song.getCcliNumber());
        }
        // sync is optional: the status only says whether the cards have times
        var status = com.jamc.worship.model.LyricsSync.status(song.getSync(), com.jamc.worship.service.SlideGenerator.cardKeys(song));
        parts.add(status.label() + (song.getSync() != null && song.getSync().type != null && status != com.jamc.worship.model.LyricsSync.Status.NOT_SYNCED
                ? " (" + song.getSync().type.label() + ")" : ""));
        if (song.getSourceType() != null) {
            parts.add("Source: " + (song.getSourceProvider() != null ? song.getSourceProvider() : song.getSourceType().label()));
        }
        meta.setText(String.join("   ·   ", parts));

        int sections = song.getSections().size();
        List<String> infoParts = new ArrayList<>();
        infoParts.add(sections + (sections == 1 ? " section" : " sections"));
        if (!song.tagList().isEmpty()) {
            infoParts.add("Tags: " + String.join(", ", song.tagList()));
        }
        infoParts.add("Font: " + (song.getFontFamily() == null ? "global lyrics typography" : song.getFontFamily())
                + (song.getFontSize() == null ? "" : " " + song.getFontSize() + "pt"));
        infoText = String.join("    ", infoParts);
        setThemeName(themeName);
        info.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        cards.show(body, "song");
    }

    /** Shows one slide in the large preview. */
    public void showSlide(Slide slide, SlideStyle style) {
        canvas.setContent(slide, style);
        canvas.setBadge(slide == null || slide.getLabel() == null ? "SELECTED SLIDE" : slide.getLabel().toUpperCase(),
                Palette::mutedText);
    }

    // ------------------------------------------------------------------ quick typography

    /**
     * Called with the song after the operator changed its font, size, style, case or alignment in
     * the quick bar (its overrides are already updated); the owner saves it and refreshes Preview.
     */
    public void setQuickTypographyListener(Consumer<Song> listener) {
        quickListener = listener == null ? s -> { } : listener;
    }

    /** Shows the song's effective typography (global lyrics settings plus the song's own overrides). */
    public void showTypography(SlideStyle effective) {
        if (effective == null) {
            return;
        }
        loadingQuick = true;
        try {
            selectFont(effective.fontFamily());
            quickSize.setValue(effective.fontSize());
            quickWeight.setSelectedItem(effective.fontWeight());
            quickAlign.setSelectedItem(effective.align());
            shownCase = effective.textTransform();
            quickCase.setText(shownCase.badge());
            quickCase.setToolTipText("Letter case: " + shownCase + " (click to change)");
            boolean custom = song != null && (song.getFontFamily() != null || song.getFontSize() != null || song.getTypography() != null);
            quickTarget.setSelectedItem(custom ? THIS_SONG : ALL_SONGS);
        } finally {
            loadingQuick = false;
        }
    }

    private JComponent buildQuickBar() {
        // two rows, so the bar fits the narrower detail column of the simplified layout
        // three short rows so nothing is cut off in the narrow detail panel (THICKNESS was hidden before)
        JPanel bar = Ui.transparent(new java.awt.GridLayout(3, 1, 0, 4));
        JPanel row1 = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JPanel rowWeight = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JPanel row2 = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(row1);
        bar.add(rowWeight);
        bar.add(row2);
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        quickFont.setMaximumRowCount(18);
        quickFont.setPrototypeDisplayValue("Segoe UI Semibold");
        ((JSpinner.DefaultEditor) quickSize.getEditor()).getTextField().setColumns(3);
        quickCase.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: bold; margin: 2,8,2,8");
        quickCase.setFocusable(false);
        quickTarget.setToolTipText("All Songs: changes the lyrics typography in Settings for every song. "
                + "This Song Only: keeps a different look for this song.");
        javax.swing.JButton addFont = Ui.button("Add Font…", "plus");
        addFont.setToolTipText("Add TTF / OTF font files (each weight file, e.g. Light / SemiBold / Black, is a THICKNESS choice)");
        addFont.addActionListener(e -> com.jamc.worship.ui.FontChoices.addFonts(this, added -> {
            if (!added.isEmpty() && song != null) {
                // use the new font for this song right away (the list was refilled by the Fonts listener)
                javax.swing.SwingUtilities.invokeLater(() -> quickFont.setSelectedItem(added.getFirst()));
            }
        }));
        com.jamc.worship.render.Fonts.addListener(() -> com.jamc.worship.ui.FontChoices.refill(quickFont,
                () -> loadingQuick = true, () -> loadingQuick = false));
        com.jamc.worship.ui.FontChoices.weightRenderer(quickWeight, () -> (String) quickFont.getSelectedItem());
        row1.add(Ui.caption("FONT"));
        row1.add(quickFont);
        row1.add(addFont);
        rowWeight.add(Ui.caption("SIZE"));
        rowWeight.add(quickSize);
        rowWeight.add(Ui.caption("THICKNESS"));
        rowWeight.add(quickWeight);
        row2.add(Ui.caption("CASE"));
        row2.add(quickCase);
        row2.add(Ui.caption("ALIGN"));
        row2.add(quickAlign);
        row2.add(Ui.caption("APPLY TO"));
        row2.add(quickTarget);

        quickFont.addActionListener(e -> {
            String f = (String) quickFont.getSelectedItem();
            quickChange(o -> song.setFontFamily(f), t -> t.withFontFamily(f));
        });
        quickSize.addChangeListener(e -> {
            int s = ((Number) quickSize.getValue()).intValue();
            quickChange(o -> song.setFontSize(s), t -> t.withFontSize(s));
        });
        quickWeight.addActionListener(e -> {
            FontWeight w = (FontWeight) quickWeight.getSelectedItem();
            quickChange(o -> o.fontWeight = w, t -> t.withFontWeight(w));
        });
        quickAlign.addActionListener(e -> {
            TextAlign a = (TextAlign) quickAlign.getSelectedItem();
            quickChange(o -> o.align = a, t -> t.withAlign(a));
        });
        quickCase.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            for (TextTransform t : TextTransform.values()) {
                JMenuItem item = new javax.swing.JCheckBoxMenuItem(t.badge() + "   " + t, t == shownCase);
                item.addActionListener(ev -> quickChange(o -> o.textTransform = t, g -> g.withTextTransform(t)));
                menu.add(item);
            }
            menu.show(quickCase, 0, quickCase.getHeight());
        });
        quickTarget.addActionListener(e -> {
            if (loadingQuick || song == null) {
                return;
            }
            if (ALL_SONGS.equals(quickTarget.getSelectedItem())) {
                // this song follows the shared settings again (its own font settings are removed)
                song.setFontFamily(null);
                song.setFontSize(null);
                song.setTypography(null);
                quickListener.accept(song);
            }
        });
        return bar;
    }

    /**
     * A quick-bar change: with APPLY TO = All Songs it changes the global lyrics typography (every song, the same
     * as Settings → Lyrics Typography); with This Song Only it is saved on this song.
     */
    private void quickChange(Consumer<StyleOverrides> songEdit, java.util.function.UnaryOperator<com.jamc.worship.model.Typography> globalEdit) {
        if (loadingQuick || song == null) {
            return;
        }
        if (ALL_SONGS.equals(quickTarget.getSelectedItem())) {
            globalEditor.accept(globalEdit);
            return;
        }
        StyleOverrides o = song.getTypography() == null ? new StyleOverrides() : song.getTypography().copy();
        songEdit.accept(o);
        song.setTypography(o);
        quickListener.accept(song);
    }

    private void selectFont(String family) {
        for (int i = 0; i < quickFont.getItemCount(); i++) {
            if (quickFont.getItemAt(i).equalsIgnoreCase(family)) {
                quickFont.setSelectedIndex(i);
                return;
            }
        }
        quickFont.insertItemAt(family, 0);
        quickFont.setSelectedIndex(0);
    }

    /** Name of the theme in use, shown in the info line. */
    public void setThemeName(String themeName) {
        this.themeName = themeName;
        info.setText(infoText);
        if (themeName != null) {
            info.setText(infoText + "    Theme: " + themeName);
        }
    }
}
