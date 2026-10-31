package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Song;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

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

    /** Name of the theme in use, shown in the info line. */
    public void setThemeName(String themeName) {
        this.themeName = themeName;
        info.setText(infoText);
        if (themeName != null) {
            info.setText(infoText + "    Theme: " + themeName);
        }
    }
}
