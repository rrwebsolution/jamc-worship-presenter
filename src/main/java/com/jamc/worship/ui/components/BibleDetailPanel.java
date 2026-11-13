package com.jamc.worship.ui.components;

import com.jamc.worship.bible.BiblePassage;
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

/** Center view for the Bible: the passage reference and a large preview of the selected verse slide. */
public class BibleDetailPanel extends JPanel {

    /** ✕ at the right end of the header: clears what this panel and the cards beside it show (the box stays). */
    private final javax.swing.JButton clearButton = com.jamc.worship.ui.Ui.toolButton("x",
            "Clear (nothing selected). Preview and Program are not touched.");
    private Runnable clearAction = () -> { };

    {
        clearButton.addActionListener(e -> clearAction.run());
    }

    public void setClearAction(Runnable action) {
        clearAction = action == null ? () -> { } : action;
    }

    private final JLabel title = Ui.label(" ", Palette::text, "h2");
    private final JLabel meta = Ui.secondary(" ");
    private final SlidePreviewCanvas canvas = new SlidePreviewCanvas();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);

    public BibleDetailPanel(Runnable onPrevious, Runnable onNext, Runnable onAddToService) {
        super(new BorderLayout());
        setOpaque(false);
        JButton prev = Ui.button("Previous", "chevron-left");
        prev.addActionListener(e -> onPrevious.run());
        JButton next = Ui.button("Next", "chevron-right");
        next.addActionListener(e -> onNext.run());
        JButton add = Ui.button("Add to Service", "list-plus");
        add.addActionListener(e -> onAddToService.run());
        JPanel header = Ui.header(title, meta, add, prev, next, clearButton);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 12)));
        canvas.setBadge("SELECTED VERSE", Palette::mutedText);
        JPanel holder = Ui.transparent(new BorderLayout());
        holder.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        holder.add(canvas);
        JPanel view = Ui.transparent(new BorderLayout());
        view.add(header, BorderLayout.NORTH);
        view.add(holder, BorderLayout.CENTER);
        JLabel empty = Ui.label("<html><center>Type a reference such as <b>John 3:16</b> or <b>Ps 23</b>,<br>"
                + "or choose a book and chapter, then select verses.</center></html>", Palette::mutedText, null);
        empty.setHorizontalAlignment(JLabel.CENTER);
        body.add(view, "passage");
        body.add(empty, "empty");
        add(body);
        setPassage(null, 0);
    }

    public void setPassage(BiblePassage p, int slideCount) {
        if (p == null) {
            cards.show(body, "empty");
            canvas.setContent(null, null);
            return;
        }
        title.setText(p.reference());
        int verses = p.toVerse() - p.fromVerse() + 1;
        meta.setText(p.translation() + "  ·  " + verses + (verses == 1 ? " verse" : " verses") + " on " + slideCount
                + (slideCount == 1 ? " slide" : " slides") + "  ·  Space sends the selected slide live");
        cards.show(body, "passage");
    }

    /** Generic header, for reusing this view for announcement, sermon and blank items. */
    public void setHeader(String titleText, String metaText) {
        title.setText(titleText);
        meta.setText(metaText);
        cards.show(body, "passage");
    }

    public void showSlide(Slide slide, SlideStyle style) {
        canvas.setContent(slide, style);
        canvas.setBadge(slide == null ? "SELECTED VERSE" : slide.getLabel().toUpperCase(), Palette::mutedText);
    }
}
