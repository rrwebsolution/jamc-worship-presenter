package com.jamc.worship.ui.components;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Vertical list of what can be presented: lyrics cards (the words, with the section as a small label)
 * or rendered page thumbnails (PowerPoint, custom slides), scrolling vertically.
 *
 * <p>Read-only for songs (slides come from sections). In editable mode (custom slide decks) it
 * offers add / duplicate / delete, a context menu, and drag-and-drop reordering.
 */
public class SlideThumbnailStrip extends JPanel {

    private static final int THUMB_W = 176;
    private static final int THUMB_H = 99;
    /** The widest page thumbnail: big enough to read, small enough that several pages stay in view. */
    private static final int MAX_W = 360;
    private static final int GAP = 10;
    private static final int DRAG_THRESHOLD = 6;

    /** Edit callbacks for editable mode. Indices refer to the current slide list. */
    public interface EditHandler {
        void add(int afterIndex);

        void duplicate(int index);

        void delete(int index);

        void move(int from, int to);
    }

    private final TilesPanel tiles = new TilesPanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel body;
    private final JLabel titleLabel = Ui.label("Slides", null, "semibold");
    private final JLabel countLabel = Ui.small("");
    private final JLabel emptyLabel = Ui.label("Select a song to see its slides here.", Palette::mutedText, null);
    private final JButton addButton = Ui.toolButton("plus", "Add slide after the selected one");
    private final JButton duplicateButton = Ui.toolButton("copy", "Duplicate slide");
    private final JButton deleteButton = Ui.toolButton("trash", "Delete slide");
    private final JPanel editButtons = Ui.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
    private final List<Tile> tileList = new ArrayList<>();
    private final List<IntConsumer> selectionListeners = new ArrayList<>();
    private final List<IntConsumer> activationListeners = new ArrayList<>();
    private List<Slide> slides = List.of();
    private SlideStyle style;
    private int selectedIndex = -1;
    private int liveIndex = -1;
    /**
     * True (lyrics, Bible...): the selected slide is the one in Preview. False (presentations): a click only
     * selects (white ring, like a scene card); the slide in Preview is {@link #previewIndex}, sent by double-click.
     */
    private java.util.function.BooleanSupplier selectionIsPreview = () -> true;
    private int previewIndex = -1;
    private int hoverIndex = -1;
    private String sourceKey;
    private final List<Runnable> slidesChangedListeners = new ArrayList<>();
    private final List<Runnable> liveListeners = new ArrayList<>();
    private boolean editable;
    private EditHandler editHandler;
    /** Lyrics (songs, Bible): cards show the words themselves; otherwise rendered slide thumbnails. */
    private boolean lyricsCards;
    private java.util.function.BooleanSupplier useTextCase = () -> false;
    private String unit = "slide";
    private final JLabel hintLabel = new JLabel("");
    private final JPanel headerActions = Ui.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
    private int tileW = THUMB_W;
    private int tileH = THUMB_H;

    public SlideThumbnailStrip() {
        super(new BorderLayout());
        setOpaque(false);

        addButton.addActionListener(e -> fireAdd());
        duplicateButton.addActionListener(e -> {
            if (editHandler != null && selectedIndex >= 0) editHandler.duplicate(selectedIndex);
        });
        deleteButton.addActionListener(e -> {
            if (editHandler != null && selectedIndex >= 0) editHandler.delete(selectedIndex);
        });
        editButtons.add(addButton);
        editButtons.add(duplicateButton);
        editButtons.add(deleteButton);
        editButtons.setVisible(false);

        JPanel right = headerActions;
        hintLabel.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: bold -1");
        right.add(hintLabel);
        right.add(countLabel);
        right.add(editButtons);
        add(Ui.header(titleLabel, null, right), BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(tiles, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);

        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        body = Ui.transparent(cards);
        body.add(scroll, "tiles");
        body.add(emptyLabel, "empty");
        cards.show(body, "empty");
        add(body, BorderLayout.CENTER);
        // the cards fill the list's width and scroll vertically inside it, never widening the window
        body.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                fitTiles();
            }
        });

        setFocusable(true);
        Ui.bindKey(this, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next", this::selectNext, WHEN_FOCUSED);
        Ui.bindKey(this, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prev", this::selectPrevious, WHEN_FOCUSED);
        Ui.bindKey(this, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "down", this::selectNext, WHEN_FOCUSED);
        Ui.bindKey(this, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "up", this::selectPrevious, WHEN_FOCUSED);
        Ui.bindKey(this, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", () -> {
            if (editable && editHandler != null && selectedIndex >= 0) editHandler.delete(selectedIndex);
        }, WHEN_FOCUSED);
    }

    public void addSelectionListener(IntConsumer listener) {
        selectionListeners.add(listener);
    }

    /** Called with the slide index when a thumbnail is double-clicked (send it live). */
    public void addActivationListener(IntConsumer listener) {
        activationListeners.add(listener);
    }

    /** Called when the LIVE marker moves. */
    public void addLiveListener(Runnable listener) {
        liveListeners.add(listener);
    }

    /** Called after the slides were replaced or extended. */
    public void addSlidesChangedListener(Runnable listener) {
        slidesChangedListeners.add(listener);
    }

    /**
     * Identifies what the strip shows (e.g. "presentation:12"), so the live marker can be restored when
     * the same content is shown again. Cleared by {@link #setSlides}.
     */
    public void setSourceKey(String key) {
        this.sourceKey = key;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    /** Marks the slide that is on Program (red frame, LIVE badge), or -1 for none. */
    public void setLiveIndex(int index) {
        int i = index >= 0 && index < slides.size() ? index : -1;
        if (i != liveIndex) {
            liveIndex = i;
            updateTileStates();
            liveListeners.forEach(Runnable::run);
        }
    }

    public int getLiveIndex() {
        return liveIndex;
    }

    /**
     * Adds slides at the end without changing the selection (no selection event, so auto-take never
     * fires). Used while a presentation is still being imported.
     */
    public void appendSlides(List<Slide> more) {
        if (more.isEmpty()) {
            return;
        }
        List<Slide> all = new ArrayList<>(slides);
        all.addAll(more);
        this.slides = List.copyOf(all);
        for (int i = all.size() - more.size(); i < all.size(); i++) {
            addTile(i);
        }
        updateCount();
        cards.show(body, "tiles");
        tiles.revalidate();
        tiles.repaint();
        slidesChangedListeners.forEach(Runnable::run);
    }

    /** Turns on deck editing. Pass null to return to read-only mode. */
    public void setEditHandler(EditHandler handler) {
        this.editHandler = handler;
        this.editable = handler != null;
        editButtons.setVisible(editable);
        tiles.setCursorForTiles();
    }

    public void setTitle(String title) {
        titleLabel.setText(title);
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    /** Adds a button to the strip's header, before the slide count. */
    public void addHeaderAction(JComponent action) {
        headerActions.add(action, 0);
    }

    /**
     * Lyrics cards (the words, with the section as a small label) or rendered thumbnails (PowerPoint,
     * custom slides). {@code unit} names one card in the count ("lyric", "verse", "page", "slide").
     */
    public void setCardMode(boolean lyrics, String unit) {
        this.unit = unit;
        if (lyrics != lyricsCards) {
            lyricsCards = lyrics;
            tiles.removeAll();
            tileList.clear();
            for (int i = 0; i < slides.size(); i++) {
                addTile(i);
            }
            fitTiles();
        }
        updateCount();
    }

    /** Whether lyrics cards use the presentation letter case (Settings → typography). */
    public void setTextCase(java.util.function.BooleanSupplier useTextCase) {
        this.useTextCase = useTextCase;
    }

    /** What a click does, shown in the header ("Click = LIVE"). */
    public void setHint(String text, boolean live) {
        hintLabel.setText(text);
        hintLabel.setForeground(live ? Palette.danger() : Palette.secondaryText());
    }

    private void updateCount() {
        countLabel.setText(slides.isEmpty() ? "" : slides.size() + " " + unit + (slides.size() == 1 ? "" : "s"));
    }

    /**
     * Sizes every card to the list's width: lyrics cards use the full width (their height follows the
     * words); page thumbnails are 16:9, up to {@link #MAX_W} wide so several stay in view.
     */
    private void fitTiles() {
        int avail = body.getWidth() - 20 - 14; // list padding and room for the vertical scroll bar
        int w = avail <= 0 ? THUMB_W : lyricsCards ? Math.max(160, avail) : Math.clamp(avail, THUMB_W, MAX_W);
        int h = w * 9 / 16;
        if (w == tileW && h == tileH) {
            return;
        }
        tileW = w;
        tileH = h;
        for (Tile t : tileList) {
            t.fit();
        }
        tiles.revalidate();
        tiles.repaint();
    }

    public void setEmptyMessage(String message) {
        emptyLabel.setText(message);
    }

    /** Replaces the slides and selects {@code selectIndex} (clamped), firing a selection event. */
    public void setSlides(List<Slide> slides, SlideStyle style, int selectIndex) {
        this.slides = List.copyOf(slides);
        this.style = style;
        this.sourceKey = null;
        this.liveIndex = -1;
        this.previewIndex = -1;
        this.hoverIndex = -1;
        tiles.removeAll();
        tileList.clear();
        for (int i = 0; i < this.slides.size(); i++) {
            addTile(i);
        }
        updateCount();
        cards.show(body, slides.isEmpty() ? "empty" : "tiles");
        tiles.revalidate();
        tiles.repaint();
        selectedIndex = -1;
        updateButtons();
        if (!this.slides.isEmpty()) {
            setSelectedIndex(Math.clamp(selectIndex, 0, this.slides.size() - 1));
        } else {
            fireSelection();
        }
        slidesChangedListeners.forEach(Runnable::run);
    }

    public void setSlides(List<Slide> slides, SlideStyle style) {
        setSlides(slides, style, 0);
    }

    /** Changes the base style of all thumbnails (e.g. after a theme change) without rebuilding. */
    public void setStyle(SlideStyle style) {
        this.style = style;
        for (Tile t : tileList) {
            t.canvas.setContent(t.slide, style);
        }
    }

    /** Repaints one thumbnail after its slide was edited in place. */
    public void refreshSlide(int index) {
        if (index >= 0 && index < tileList.size()) {
            Tile t = tileList.get(index);
            t.canvas.setContent(t.slide, style);
            t.fit(); // edited words can need another line
            t.caption.repaint();
        }
    }

    public List<Slide> getSlides() {
        return slides;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public Slide getSelectedSlide() {
        return selectedIndex >= 0 && selectedIndex < slides.size() ? slides.get(selectedIndex) : null;
    }

    public SlideStyle getBaseStyle() {
        return style;
    }

    public void selectNext() {
        if (selectedIndex < slides.size() - 1) {
            setSelectedIndex(selectedIndex + 1);
        }
    }

    public void selectPrevious() {
        if (selectedIndex > 0) {
            setSelectedIndex(selectedIndex - 1);
        }
    }

    /**
     * A click on a slide: selects it, and when it is already selected announces it again (a click on
     * the selected lyrics slide must still be able to send it live).
     */
    public void select(int index) {
        if (index == selectedIndex && index >= 0) {
            fireSelection();
        } else {
            setSelectedIndex(index);
        }
    }

    public void setSelectedIndex(int index) {
        if (index < -1 || index >= slides.size() || index == selectedIndex) {
            return;
        }
        selectedIndex = index;
        updateTileStates();
        if (index >= 0) {
            Tile t = tileList.get(index);
            t.scrollRectToVisible(new Rectangle(0, 0, t.getWidth(), t.getHeight()));
        }
        updateButtons();
        fireSelection();
    }

    private void addTile(int i) {
        Tile tile = new Tile(i, slides.get(i));
        tileList.add(tile);
        tiles.add(tile);
        java.awt.Component gap = Box.createVerticalStrut(GAP);
        ((JComponent) gap).setAlignmentX(CENTER_ALIGNMENT);
        tiles.add(gap);
        tile.applyState();
    }

    /** Live slide: red frame + LIVE. Selected slide (it is what Preview shows): blue frame + PREVIEW. */
    private void updateTileStates() {
        for (Tile t : tileList) {
            t.applyState();
        }
    }

    /** Whether a click on a slide also means "this slide is in Preview" (see {@link #selectionIsPreview}). */
    public void setSelectionIsPreview(java.util.function.BooleanSupplier rule) {
        selectionIsPreview = rule == null ? () -> true : rule;
        updateTileStates();
    }

    /** Marks the slide that is in Preview when selecting and previewing are separate (-1 = none). */
    public void setPreviewIndex(int index) {
        if (index != previewIndex) {
            previewIndex = index;
            updateTileStates();
        }
    }

    /** The slide marked PREVIEW: the selected one, or the one sent by double-click when they are separate. */
    private int previewMark() {
        return selectionIsPreview.getAsBoolean() ? selectedIndex : previewIndex;
    }

    /** Selected only (not in Preview): a white ring, like a selected scene card. */
    private boolean selectedOnly(int index) {
        return !selectionIsPreview.getAsBoolean() && index >= 0 && index == selectedIndex && index != previewIndex;
    }

    private void setHover(int index) {
        if (index != hoverIndex) {
            hoverIndex = index;
            updateTileStates();
        }
    }

    private void updateButtons() {
        boolean has = selectedIndex >= 0;
        duplicateButton.setEnabled(has);
        deleteButton.setEnabled(has && slides.size() > 1);
    }

    private void fireAdd() {
        if (editHandler != null) {
            editHandler.add(selectedIndex < 0 ? slides.size() - 1 : selectedIndex);
        }
    }

    private void fireSelection() {
        for (IntConsumer l : selectionListeners) {
            l.accept(selectedIndex);
        }
    }

    private void showMenu(Tile tile, MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Add Slide After", "plus", () -> editHandler.add(tile.index)));
        menu.add(menuItem("Duplicate", "copy", () -> editHandler.duplicate(tile.index)));
        JMenuItem left = menuItem("Move Left", "chevron-left", () -> editHandler.move(tile.index, tile.index - 1));
        left.setEnabled(tile.index > 0);
        menu.add(left);
        JMenuItem right = menuItem("Move Right", "chevron-right", () -> editHandler.move(tile.index, tile.index + 1));
        right.setEnabled(tile.index < slides.size() - 1);
        menu.add(right);
        menu.addSeparator();
        JMenuItem delete = menuItem("Delete", "trash", () -> editHandler.delete(tile.index));
        delete.setEnabled(slides.size() > 1);
        menu.add(delete);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private static JMenuItem menuItem(String text, String icon, Runnable action) {
        JMenuItem item = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        item.addActionListener(ev -> action.run());
        return item;
    }

    /** Tile container that paints the drop marker while a thumbnail is dragged. */
    private final class TilesPanel extends JPanel implements javax.swing.Scrollable {
        int dropY = -1;

        TilesPanel() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(4, 10, 8, 10));
        }

        // follows the viewport's width: the list only ever scrolls vertically
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        void setCursorForTiles() {
            for (Tile t : tileList) {
                t.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
        }

        /** Insertion index (0..size) for a point in this panel's coordinates. */
        int dropIndexAt(int y) {
            for (Tile t : tileList) {
                if (y < t.getY() + t.getHeight() / 2) {
                    return t.index;
                }
            }
            return tileList.size();
        }

        int markerY(int dropIndex) {
            if (tileList.isEmpty()) {
                return 4;
            }
            if (dropIndex >= tileList.size()) {
                Tile last = tileList.getLast();
                return last.getY() + last.getHeight() + GAP / 2;
            }
            return tileList.get(dropIndex).getY() - GAP / 2;
        }

        @Override
        protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            if (dropY >= 0) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(Palette.accent());
                g2.fillRoundRect(10, dropY - 2, getWidth() - 20, 4, 4, 4);
                g2.dispose();
            }
        }
    }

    private final class Tile extends JPanel {
        final int index;
        final Slide slide;
        final SlidePreviewCanvas canvas = new SlidePreviewCanvas();
        /** The words themselves for lyrics; null for thumbnails. */
        final LyricsCard card;
        final JComponent caption;

        Tile(int index, Slide slide) {
            super(new BorderLayout(0, 4));
            this.index = index;
            this.slide = slide;
            setOpaque(false);
            canvas.setCompact(true);
            canvas.setContent(slide, style);
            if (lyricsCards) {
                card = new LyricsCard(slide);
                add(card, BorderLayout.CENTER);
                caption = card; // no "1  Verse 1" line underneath: the section is a small label on the card
            } else {
                card = null;
                add(canvas, BorderLayout.CENTER);
                caption = new NumberedCaption(index + 1, slide);
                add(caption, BorderLayout.SOUTH);
            }
            fit();
            setAlignmentX(CENTER_ALIGNMENT); // centred in the list when narrower than it
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            MouseAdapter mouse = new MouseAdapter() {
                Point pressPoint;
                boolean dragging;

                @Override
                public void mousePressed(MouseEvent e) {
                    SlideThumbnailStrip.this.requestFocusInWindow();
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        select(Tile.this.index);
                    } else {
                        setSelectedIndex(Tile.this.index);
                    }
                    pressPoint = e.getPoint();
                    dragging = false;
                    maybePopup(e);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (!editable || pressPoint == null || !SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    if (!dragging && pressPoint.distance(e.getPoint()) < DRAG_THRESHOLD) {
                        return;
                    }
                    dragging = true;
                    Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), tiles);
                    tiles.dropY = tiles.markerY(tiles.dropIndexAt(p.y));
                    tiles.repaint();
                    tiles.scrollRectToVisible(new Rectangle(0, p.y - 40, 1, 80));
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (dragging) {
                        Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), tiles);
                        int drop = tiles.dropIndexAt(p.y);
                        tiles.dropY = -1;
                        tiles.repaint();
                        dragging = false;
                        int from = Tile.this.index;
                        int to = drop > from ? drop - 1 : drop;
                        if (to != from && editHandler != null) {
                            editHandler.move(from, to);
                        }
                        return;
                    }
                    maybePopup(e);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                        activationListeners.forEach(l -> l.accept(Tile.this.index));
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    setHover(Tile.this.index);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (hoverIndex == Tile.this.index) {
                        setHover(-1);
                    }
                }

                private void maybePopup(MouseEvent e) {
                    if (e.isPopupTrigger() && editable && editHandler != null) {
                        showMenu(Tile.this, e);
                    }
                }
            };
            for (JComponent c : List.of(this, canvas, caption)) {
                c.addMouseListener(mouse);
                c.addMouseMotionListener(mouse);
            }
        }

        void fit() {
            int h = card != null ? card.heightFor(tileW) : tileH;
            Dimension view = new Dimension(tileW, h);
            (card != null ? card : canvas).setPreferredSize(view);
            (card != null ? card : canvas).setMinimumSize(view);
            Dimension size = new Dimension(tileW, card != null ? h : tileH + 22);
            setPreferredSize(size);
            setMaximumSize(size);
            revalidate();
        }

        void applyState() {
            if (index == liveIndex) {
                canvas.setFrameColor(Palette::danger);
                canvas.setBadge("LIVE", Palette::danger);
            } else if (index == previewMark()) {
                canvas.setFrameColor(Palette::accent);
                canvas.setBadge("PREVIEW", Palette::accent);
            } else if (selectedOnly(index)) {
                canvas.setFrameColor(Palette::text); // selected: double-click sends it to Preview
                canvas.setBadge(null, null);
            } else if (index == hoverIndex && !selectionIsPreview.getAsBoolean()) {
                canvas.setFrameColor(() -> Palette.withAlpha(Palette.text(), 120));
                canvas.setBadge(null, null);
            } else {
                canvas.setFrameColor(null);
                canvas.setBadge(null, null);
            }
            if (card != null) {
                card.repaint();
            }
        }
    }

    /**
     * One lyrics card: the words as the main content, the section ("VERSE 1") small and secondary, and
     * ▶ PREVIEW / ● LIVE when it is prepared or on Program.
     */
    private final class LyricsCard extends JComponent {
        private static final int PAD = 12;
        private static final float LYRICS_PT = 16f;
        private final Slide slide;

        LyricsCard(Slide slide) {
            this.slide = slide;
            setToolTipText(slide.getLabel());
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            return slide.getText();
        }

        private java.awt.Font sectionFont() {
            return getFont().deriveFont(java.awt.Font.BOLD, 10.5f);
        }

        private java.awt.Font lyricsFont() {
            return getFont().deriveFont(java.awt.Font.BOLD, LYRICS_PT);
        }

        /** The words in the presentation letter case when Settings asks for it. */
        private String text() {
            com.jamc.worship.model.TextTransform textCase = useTextCase.getAsBoolean() && style != null
                    ? slide.styleOver(style).textTransform() : com.jamc.worship.model.TextTransform.ORIGINAL;
            return slide.getText() == null || slide.getText().isBlank()
                    ? (slide.getCaption() != null ? slide.getCaption() : "") : textCase.apply(slide.getText().strip());
        }

        private List<String> lines(java.awt.FontMetrics fm, int width) {
            List<String> lines = new ArrayList<>();
            for (String line : text().split("\\R")) {
                if (!line.isBlank()) {
                    lines.addAll(ActionOrderPanel.wrap(line.strip(), fm, width));
                }
            }
            return lines;
        }

        /** Height that shows every line of the words at this width (the card grows with the verse). */
        int heightFor(int width) {
            var sfm = getFontMetrics(sectionFont());
            var fm = getFontMetrics(lyricsFont());
            int lines = Math.max(1, lines(fm, width - 2 * PAD).size());
            return PAD + sfm.getHeight() + 8 + lines * fm.getHeight() + PAD;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int index = tileList.indexOf(SwingUtilities.getAncestorOfClass(Tile.class, this));
            boolean live = index >= 0 && index == liveIndex;
            boolean preview = !live && index >= 0 && index == previewMark();
            boolean selected = !live && !preview && selectedOnly(index);
            java.awt.Color frame = live ? Palette.danger() : preview ? Palette.accent() : selected ? Palette.text() : Palette.border();
            g2.setColor(live ? Palette.withAlpha(Palette.danger(), 38) : preview ? Palette.withAlpha(Palette.accent(), 34)
                    : Palette.secondaryBackground());
            g2.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g2.setColor(frame);
            g2.setStroke(new java.awt.BasicStroke(live || preview || selected ? 2f : 1f));
            g2.drawRoundRect(1, 1, w - 3, h - 3, 12, 12);

            // section label (secondary) and state badge on one top row
            g2.setFont(sectionFont());
            var sfm = g2.getFontMetrics();
            String section = slide.getLabel() == null ? "" : slide.getLabel().toUpperCase(java.util.Locale.ROOT);
            int top = PAD + sfm.getAscent();
            String badge = live ? "● LIVE" : preview ? "▶ PREVIEW" : null;
            int badgeW = 0;
            if (badge != null) {
                badgeW = sfm.stringWidth(badge) + 12;
                g2.setColor(frame);
                g2.fillRoundRect(w - PAD - badgeW, PAD - 3, badgeW, sfm.getHeight() + 4, 8, 8);
                g2.setColor(java.awt.Color.WHITE);
                g2.drawString(badge, w - PAD - badgeW + 6, top);
            }
            g2.setColor(Palette.mutedText());
            g2.drawString(fitText(section, sfm, w - 2 * PAD - badgeW - 6), PAD, top);

            // the lyrics: the main content
            g2.setFont(lyricsFont());
            var fm = g2.getFontMetrics();
            g2.setColor(Palette.text());
            int y = PAD + sfm.getHeight() + 8 + fm.getAscent();
            for (String line : lines(fm, w - 2 * PAD)) {
                g2.drawString(fitText(line, fm, w - 2 * PAD), PAD, y);
                y += fm.getHeight();
            }
            g2.dispose();
        }

        private static String fitText(String s, java.awt.FontMetrics fm, int width) {
            if (fm.stringWidth(s) <= width) {
                return s;
            }
            while (s.length() > 1 && fm.stringWidth(s + "…") > width) {
                s = s.substring(0, s.length() - 1);
            }
            return s + "…";
        }
    }

    /** "1  Verse 1" caption under each thumbnail. Reads the label live so edits show immediately. */
    private static final class NumberedCaption extends JComponent {
        private final String number;
        private final Slide slide;

        NumberedCaption(int number, Slide slide) {
            this.number = String.valueOf(number);
            this.slide = slide;
            setPreferredSize(new Dimension(THUMB_W, 18));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont().deriveFont(java.awt.Font.BOLD, 11f));
            var fm = g2.getFontMetrics();
            int baseline = fm.getAscent() + 1;
            g2.setColor(Palette.mutedText());
            g2.drawString(number, 0, baseline);
            int x = fm.stringWidth(number) + 8;
            String label = slide.getLabel() == null || slide.getLabel().isBlank() ? "Slide" : slide.getLabel();
            while (label.length() > 1 && x + fm.stringWidth(label) > getWidth()) {
                label = label.substring(0, label.length() - 2) + "…";
            }
            g2.setColor(Palette.secondaryText());
            g2.drawString(label, x, baseline);
            g2.dispose();
        }
    }
}
