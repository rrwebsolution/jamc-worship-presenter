package com.jamc.worship.ui.components;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridLayout;

/** Center view for the Themes category: large previews of the selected theme. */
public class ThemeDetailPanel extends JPanel {

    private static final Slide LYRICS = new Slide("Lyrics", "Amazing grace! How sweet the sound\n"
            + "That saved a wretch like me!\nI once was lost, but now am found;\nWas blind, but now I see.", null);
    /** Placeholder only: Bible text is never stored in code; real verses come from the Bible module. */
    private static final Slide SCRIPTURE = scriptureSample();

    private static Slide scriptureSample() {
        Slide s = new Slide("Scripture", "A Bible verse appears here, in this theme, with the reference above it "
                + "and the version below it.", "VERSION");
        s.setHeading("BOOK 1:1");
        return s;
    }

    private final JLabel title = Ui.label(" ", Palette::text, "h2");
    private final JLabel meta = Ui.secondary(" ");
    private final SlidePreviewCanvas lyrics = new SlidePreviewCanvas();
    private final SlidePreviewCanvas scripture = new SlidePreviewCanvas();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);

    public ThemeDetailPanel(Runnable onEdit, Runnable onDuplicate) {
        super(new BorderLayout());
        setOpaque(false);

        JButton edit = Ui.button("Edit Theme", "edit");
        edit.addActionListener(e -> onEdit.run());
        JButton duplicate = Ui.button("Duplicate", "copy");
        duplicate.addActionListener(e -> onDuplicate.run());
        JPanel header = Ui.header(title, meta, duplicate, edit);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 12)));

        lyrics.setBadge("LYRICS", Palette::mutedText);
        scripture.setBadge("SCRIPTURE", Palette::mutedText);
        JPanel previews = Ui.transparent(new GridLayout(2, 1, 0, 12));
        previews.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        previews.add(lyrics);
        previews.add(scripture);

        JPanel view = Ui.transparent(new BorderLayout());
        view.add(header, BorderLayout.NORTH);
        view.add(previews, BorderLayout.CENTER);

        JLabel empty = Ui.label("Select a theme to preview it.", Palette::mutedText, null);
        empty.setHorizontalAlignment(JLabel.CENTER);
        body.add(view, "theme");
        body.add(empty, "empty");
        add(body, BorderLayout.CENTER);
        setSelection(null);
    }

    public void setSelection(ThemeLibraryPanel.Selection selection) {
        if (selection == null) {
            cards.show(body, "empty");
            return;
        }
        SlideStyle s = selection.theme().getStyle();
        title.setText(selection.theme().getName() + (selection.isDefault() ? "  ★" : ""));
        meta.setText((selection.isDefault() ? "Default theme  ·  " : "")
                + (selection.theme().isBuiltin() ? "Built-in  ·  " : "")
                + s.fontFamily() + " " + s.fontSize() + " " + s.fontWeight() + (s.italic() ? " italic" : "")
                + (s.textTransform() == com.jamc.worship.model.TextTransform.ORIGINAL ? "" : "  ·  " + s.textTransform())
                + "  ·  " + s.align() + " / " + s.position()
                + (selection.theme().isTypographyOverride() ? "  ·  own text settings" : "  ·  text follows global typography when on")
                + "  ·  " + s.backgroundType());
        lyrics.setContent(LYRICS, s);
        scripture.setContent(SCRIPTURE, s);
        cards.show(body, "theme");
    }
}
