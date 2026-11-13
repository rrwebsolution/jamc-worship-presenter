package com.jamc.worship.ui.components;

import com.jamc.worship.model.Presentation;
import com.jamc.worship.model.Slide;
import com.jamc.worship.service.PresentationService;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Center view for a presentation: the selected page, large, with the operator controls
 * (previous / next / preview / take) and notes about the file (changed, missing, features that could
 * not be reproduced). The pages themselves are chosen in the one horizontal strip; the selected page is in Preview
 * (blue), the slide on Program is marked LIVE (red).
 */
public class PresentationDetailPanel extends JPanel {

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

    /** A Previous / Next arrow halfway down the side of the slide. */
    private static JPanel centeredVertically(JComponent c) {
        JPanel p = Ui.transparent(new java.awt.GridBagLayout());
        p.add(c);
        return p;
    }

    /** Operator actions; slide indices are 0-based. */
    public interface Actions {
        void select(int index);

        void take(int index);

        void previous();

        void next();

        void preview();

        void takeSelected();

        void addToService();

        void refresh();

        void locate();

        void importPowerPoint();

        void showNotes();
    }

    private static final int TILE_W = 208;
    private static final int TILE_H = 117;

    private final JLabel title = Ui.label(" ", Palette::text, "h2");
    private final JLabel meta = Ui.secondary(" ");
    private final JPanel banner = Ui.panel(new BorderLayout(10, 0), () -> Palette.withAlpha(Palette.warning(), 40));
    private final JLabel bannerText = Ui.label(" ", Palette::text, null);
    private final JButton bannerButton = Ui.button(" ", "refresh");
    private final JButton previous = Ui.button("Previous", "chevron-left");
    private final JButton next = Ui.button("Next", "chevron-right");
    private final JButton preview = Ui.button("SEND TO PREVIEW", "monitor");
    private final JButton take = Ui.primaryButton("TAKE / LIVE", "play");
    private final JButton addToService = Ui.button("ADD TO SERVICE", "list-plus");
    /** The one large monitor for the selected slide; the grid below stays as small thumbnails. */
    private final SlidePreviewCanvas selectedView = new SlidePreviewCanvas();
    private final GridPanel grid = new GridPanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private final List<GridTile> tiles = new ArrayList<>();
    private final Actions actions;
    private Runnable bannerAction = () -> { };
    private int selected = -1;
    private int live = -1;

    public PresentationDetailPanel(Actions actions) {
        super(new BorderLayout());
        this.actions = actions;
        setOpaque(false);

        previous.addActionListener(e -> actions.previous());
        next.addActionListener(e -> actions.next());
        preview.addActionListener(e -> actions.preview());
        take.addActionListener(e -> actions.takeSelected());
        take.setToolTipText("Send the selected slide to Program / projector (Space)");
        addToService.addActionListener(e -> actions.addToService());
        addToService.setToolTipText("Add this presentation to the service schedule");
        previous.setToolTipText("Previous slide (Left arrow)");
        next.setToolTipText("Next slide (Right arrow)");
        preview.setToolTipText("Send the selected slide to the main PREVIEW (Program stays until TAKE)");
        selectedView.setBadge("SELECTED SLIDE", Palette::secondaryText);
        selectedView.setEmptyMessage("Click a slide thumbnail");
        selectedView.setPreferredSize(new Dimension(320, 180));

        // SEND TO PREVIEW · TAKE / LIVE · ADD TO SERVICE side by side in one row (equal widths that shrink with the
        // column); Previous / Next sit left and right of the slide instead
        JPanel buttons = Ui.transparent(new java.awt.GridLayout(1, 3, 6, 0));
        for (JComponent c : List.of(preview, take, addToService)) {
            buttons.add(c);
        }
        for (JButton b : List.of(previous, next)) {
            b.setText(null); // just the arrow beside the slide
            b.setIcon(com.jamc.worship.ui.theme.Icons.get(b == previous ? "chevron-left" : "chevron-right",
                    com.jamc.worship.ui.theme.Icons.MEDIUM));
            b.setMargin(new java.awt.Insets(18, 6, 18, 6));
        }
        JPanel titles = Ui.transparent(new BorderLayout(0, 2));
        titles.add(title, BorderLayout.NORTH);
        titles.add(meta, BorderLayout.SOUTH);
        JPanel header = Ui.transparent(new BorderLayout(0, 10));
        JPanel titleRow = Ui.transparent(new BorderLayout(8, 0));
        titleRow.add(titles, BorderLayout.CENTER);
        JPanel clearBox = Ui.transparent(new BorderLayout());
        clearBox.add(clearButton, BorderLayout.NORTH);
        titleRow.add(clearBox, BorderLayout.EAST);
        header.add(titleRow, BorderLayout.NORTH);
        header.add(buttons, BorderLayout.SOUTH);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 12)));

        banner.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(8, 16, 8, 12)));
        banner.add(bannerText, BorderLayout.CENTER);
        banner.add(bannerButton, BorderLayout.EAST);
        bannerButton.addActionListener(e -> bannerAction.run());
        banner.setVisible(false);

        JPanel north = Ui.transparent(new BorderLayout());
        north.add(header, BorderLayout.NORTH);
        north.add(banner, BorderLayout.SOUTH);

        // only the selected page, large: the pages themselves are the one horizontal strip beside this panel
        // (no second slide grid here)
        JPanel monitor = Ui.transparent(new BorderLayout(8, 0));
        monitor.setBorder(BorderFactory.createEmptyBorder(10, 8, 12, 8));
        monitor.add(selectedView, BorderLayout.CENTER);
        monitor.add(centeredVertically(previous), BorderLayout.WEST); // ‹ left of the slide
        monitor.add(centeredVertically(next), BorderLayout.EAST);     // › right of the slide

        JPanel passage = Ui.transparent(new BorderLayout());
        passage.add(north, BorderLayout.NORTH);
        passage.add(monitor, BorderLayout.CENTER);

        JPanel empty = Ui.transparent(new java.awt.GridBagLayout());
        JPanel emptyContent = Ui.transparent(new BorderLayout(0, 14));
        JLabel emptyText = Ui.label("<html><center>Import a PowerPoint presentation to show its slides here.<br>"
                + "Then click a slide to preview it, double-click (or TAKE) to send it live.</center></html>",
                Palette::mutedText, null);
        emptyText.setHorizontalAlignment(SwingConstants.CENTER);
        JButton importButton = Ui.primaryButton("Import PowerPoint", "import");
        importButton.addActionListener(e -> actions.importPowerPoint());
        JPanel buttonRow = Ui.transparent(new FlowLayout(FlowLayout.CENTER));
        buttonRow.add(importButton);
        emptyContent.add(emptyText, BorderLayout.CENTER);
        emptyContent.add(buttonRow, BorderLayout.SOUTH);
        empty.add(emptyContent);

        body.add(passage, "presentation");
        body.add(empty, "empty");
        add(body);
        cards.show(body, "empty");
        updateButtons();
    }

    /** Shows the presentation's slides; {@code state} drives the file banner. */
    public void setPresentation(Presentation p, List<Slide> slides, PresentationService.SourceState state, boolean cached) {
        if (p == null) {
            cards.show(body, "empty");
            setSlides(List.of());
            return;
        }
        title.setText(p.name());
        String file = p.sourceFileName().equals(p.name()) ? "" : p.sourceFileName() + "  ·  ";
        meta.setText(file + p.slideCountText() + "  ·  rendered with " + p.renderer()
                + "  ·  ← / → to move, Space to take live");
        if (!cached) {
            showBanner("The rendered slides are missing (for example after restoring a backup). Refresh to render them again.",
                    "Refresh Presentation", "refresh", state == PresentationService.SourceState.MISSING ? actions::locate : actions::refresh);
        } else if (state == PresentationService.SourceState.CHANGED) {
            showBanner("The PowerPoint file has changed since it was imported.", "Refresh Presentation", "refresh", actions::refresh);
        } else if (state == PresentationService.SourceState.MISSING) {
            showBanner("<html>The original file was not found: " + escape(p.sourcePath())
                    + "<br>The imported slides still work.</html>", "Locate File…", "folder", actions::locate);
        } else if (p.warningCount() > 0) {
            showBanner(p.warningCount() + (p.warningCount() == 1 ? " slide uses" : " slides use")
                    + " PowerPoint features that may look different here (animations, fonts, video…).", "Details", "search",
                    actions::showNotes);
        } else {
            banner.setVisible(false);
        }
        setSlides(slides);
        cards.show(body, "presentation");
    }

    /** Header while an import is running; slides are added with {@link #appendSlide}. */
    public void showImporting(String name, int slideCount, String renderer) {
        title.setText(name);
        meta.setText("Importing " + slideCount + (slideCount == 1 ? " slide" : " slides") + " with " + renderer + "…");
        banner.setVisible(false);
        setSlides(List.of());
        cards.show(body, "presentation");
    }

    public void appendSlide(Slide slide) {
        GridTile t = new GridTile(tiles.size(), slide);
        tiles.add(t);
        grid.add(t);
        grid.revalidate();
        grid.repaint();
        updateButtons();
    }

    private void setSlides(List<Slide> slides) {
        grid.removeAll();
        tiles.clear();
        selected = -1;
        live = -1;
        selectedView.setContent(null, null);
        for (Slide s : slides) {
            GridTile t = new GridTile(tiles.size(), s);
            tiles.add(t);
            grid.add(t);
        }
        grid.revalidate();
        grid.repaint();
        updateButtons();
    }

    public void setSelectedIndex(int index) {
        selected = index;
        tiles.forEach(GridTile::applyState);
        if (index >= 0 && index < tiles.size()) {
            GridTile t = tiles.get(index);
            t.scrollRectToVisible(new Rectangle(0, 0, t.getWidth(), t.getHeight()));
            selectedView.setContent(t.canvas.getSlide(), com.jamc.worship.model.SlideStyle.fallback());
        } else {
            selectedView.setContent(null, null);
        }
        updateButtons();
    }

    public void setLiveIndex(int index) {
        if (index != live) {
            live = index;
            tiles.forEach(GridTile::applyState);
        }
    }

    private void showBanner(String text, String button, String icon, Runnable action) {
        bannerText.setText(text);
        bannerButton.setText(button);
        bannerButton.setIcon(com.jamc.worship.ui.theme.Icons.get(icon, com.jamc.worship.ui.theme.Icons.SMALL));
        bannerAction = action;
        banner.setVisible(true);
    }

    private void updateButtons() {
        boolean any = !tiles.isEmpty();
        previous.setEnabled(any && selected > 0);
        next.setEnabled(any && selected < tiles.size() - 1);
        preview.setEnabled(any && selected >= 0);
        take.setEnabled(any && selected >= 0);
        addToService.setEnabled(any);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** One slide in the grid: thumbnail with state frame/badge, and "12  Title" underneath. */
    private final class GridTile extends JPanel {
        final int index;
        final SlidePreviewCanvas canvas = new SlidePreviewCanvas();

        GridTile(int index, Slide slide) {
            super(new BorderLayout(0, 4));
            this.index = index;
            setOpaque(false);
            canvas.setCompact(true);
            canvas.setContent(slide, com.jamc.worship.model.SlideStyle.fallback());
            canvas.setPreferredSize(new Dimension(TILE_W, TILE_H));
            add(canvas, BorderLayout.CENTER);
            add(new Caption(index + 1, slide.getLabel()), BorderLayout.SOUTH);
            setPreferredSize(new Dimension(TILE_W, TILE_H + 22));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        actions.select(GridTile.this.index);
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                        actions.take(GridTile.this.index);
                    }
                }
            };
            addMouseListener(mouse);
            canvas.addMouseListener(mouse);
            applyState();
        }

        void applyState() {
            if (index == live) {
                canvas.setFrameColor(Palette::danger);
                canvas.setBadge("LIVE", Palette::danger);
            } else if (index == selected) {
                canvas.setFrameColor(Palette::accent);
                canvas.setBadge("PREVIEW", Palette::accent);
            } else {
                canvas.setFrameColor(null);
                canvas.setBadge(null, null);
            }
        }
    }

    private static final class Caption extends JComponent {
        private final String number;
        private final String label;

        Caption(int number, String label) {
            this.number = String.valueOf(number);
            this.label = label == null || label.isBlank() ? "Slide " + number : label;
            setPreferredSize(new Dimension(TILE_W, 18));
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
            String text = label;
            while (text.length() > 1 && x + fm.stringWidth(text) > getWidth()) {
                text = text.substring(0, text.length() - 2) + "…";
            }
            g2.setColor(Palette.secondaryText());
            g2.drawString(text, x, baseline);
            g2.dispose();
        }
    }

    /** Wrapping grid that fills the viewport width and scrolls vertically. */
    private static final class GridPanel extends JPanel implements Scrollable {
        GridPanel() {
            super(new WrapGrid(14, 14));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(24, visibleRect.height - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Lays out equally sized children left to right, wrapping to the next row. */
    private static final class WrapGrid implements LayoutManager {
        private final int hgap;
        private final int vgap;

        WrapGrid(int hgap, int vgap) {
            this.hgap = hgap;
            this.vgap = vgap;
        }

        private int columns(Container parent, Dimension cell) {
            Insets in = parent.getInsets();
            int width = parent.getWidth() > 0 ? parent.getWidth() : 900;
            int avail = Math.max(cell.width, width - in.left - in.right);
            return Math.max(1, (avail + hgap) / (cell.width + hgap));
        }

        private static Dimension cell(Container parent) {
            return parent.getComponentCount() == 0 ? new Dimension(TILE_W, TILE_H + 22)
                    : parent.getComponent(0).getPreferredSize();
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Dimension cell = cell(parent);
            Insets in = parent.getInsets();
            int n = parent.getComponentCount();
            int cols = columns(parent, cell);
            int rows = (n + cols - 1) / cols;
            int w = cols * cell.width + Math.max(0, cols - 1) * hgap + in.left + in.right;
            int h = rows * cell.height + Math.max(0, rows - 1) * vgap + in.top + in.bottom;
            return new Dimension(w, h);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Dimension cell = cell(parent);
            Insets in = parent.getInsets();
            return new Dimension(cell.width + in.left + in.right, cell.height + in.top + in.bottom);
        }

        @Override
        public void layoutContainer(Container parent) {
            Dimension cell = cell(parent);
            Insets in = parent.getInsets();
            int cols = columns(parent, cell);
            for (int i = 0; i < parent.getComponentCount(); i++) {
                Component c = parent.getComponent(i);
                int row = i / cols;
                int col = i % cols;
                c.setBounds(in.left + col * (cell.width + hgap), in.top + row * (cell.height + vgap), cell.width, cell.height);
            }
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }
    }
}
