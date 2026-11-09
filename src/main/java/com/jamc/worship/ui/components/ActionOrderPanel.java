package com.jamc.worship.ui.components;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ORDER window: one rounded card per slide of whatever the slide strip shows (a song's lyrics, Bible verses,
 * a presentation, a custom slide deck, a service text item), with the slide's own words (or a page picture).
 *
 * <p>Each card has a drag handle, its number, the section ("VERSE 1 · 1/2"), the words, its state (green SHOWN for
 * slides already presented, red LIVE for the slide on Program, blue PREVIEW), and, where the content supports them,
 * EDIT and ⋮ (more actions). The LIVE card has a red border and a soft red background.
 *
 * <p>It shares the strip's slides and selection, so every click, NEXT / PREVIOUS (also from the keyboard and LiveCat
 * Remote) and TAKE behave exactly as in the strip: lyrics follow the Separate Lyrics setting, other content goes to
 * Preview first. Dragging a card (or ▲ / ▼) moves its whole section; songs keep the order as their arrangement,
 * presentations for this showing, custom decks in the deck itself.
 */
public class ActionOrderPanel extends JPanel {

    /** What the cards ask of the window that owns the strip. Slide indices refer to the strip. */
    public interface Host {
        void select(int slideIndex);

        void take(int slideIndex);

        /** Whether a double-click sends a slide live (Settings); otherwise it only previews. */
        boolean doubleClickTakes();

        /** Whether the order of this content can be changed here. */
        boolean canEditOrder();

        /** Applies a new sequence of entries; duplicated entries contain copied slides. */
        void applyOrder(List<List<Slide>> entries);

        boolean canResetOrder();

        void resetOrder();

        /** Settings, Interface: cards show the presentation letter case instead of the words as typed. */
        default boolean cardsUseTextCase() {
            return false;
        }

        /** Moves the entry (section) starting at slide {@code fromSlide}; {@code entries} is the order afterwards. */
        default void moveEntry(int fromSlide, int toSlide, List<List<Slide>> entries) {
            applyOrder(entries);
        }

        /** Duplicates the entry starting at {@code slide}; {@code entries} is the order afterwards. */
        default void duplicateEntry(int slide, List<List<Slide>> entries) {
            applyOrder(entries);
        }

        /** Removes the entry starting at {@code slide}; {@code entries} is the order afterwards. */
        default void removeEntry(int slide, List<List<Slide>> entries) {
            applyOrder(entries);
        }

        /** Whether the card's EDIT button is shown (the content has an editor). */
        default boolean canEditSlide(int slideIndex) {
            return false;
        }

        default void editSlide(int slideIndex) {
        }

        /** Whether ADD SLIDE is shown (custom slide decks). */
        default boolean canAddSlide() {
            return false;
        }

        default void addSlide(int afterIndex) {
        }
    }

    /** Consecutive strip slides that belong together (one section occurrence, one verse). */
    public record Group(int start, int count, String label) {
        int end() {
            return start + count - 1;
        }

        boolean contains(int index) {
            return index >= start && index <= end();
        }
    }

    private enum State { DONE, LIVE, PREVIEW, NEXT }

    private static final Pattern PART = Pattern.compile("^(.*) \\((\\d+)/(\\d+)\\)$");
    private static final int MAX_LINES = 3;
    private static final int THUMB_W = 112;
    private static final int THUMB_H = 63;
    /** Card layout (px): outer gap, left gutter (handle + number), right column (state), action buttons. */
    private static final int GAP = 6;
    private static final int GUTTER = 64;
    private static final int STATE_W = 92;
    private static final int ACTION_W = 34;
    private static final int ARC = 14;

    private final SlideThumbnailStrip strip;
    private final Host host;
    /** When the list last selected a card for the host (to tell a selecting click from a re-click). */
    private long lastSelectionMillis;
    private final DefaultListModel<Slide> model = new DefaultListModel<>();
    private final JList<Slide> list = new JList<>(model);
    private final JLabel countLabel = new JLabel("");
    private final JLabel clickHint = new JLabel("");
    private final JLabel titleLabel = Ui.label("Lyrics & Slides", Palette::text, "h3");
    private final JLabel subtitleLabel = Ui.label("Order", Palette::secondaryText, "small");
    private final KindBadge kindBadge = new KindBadge();
    private final JButton moveUpButton = Ui.toolButton("arrow-up", "Move the selected section up");
    private final JButton moveDownButton = Ui.toolButton("arrow-down", "Move the selected section down");
    private final JButton addSlideButton = Ui.primaryButton("Add Slide", "plus");
    private final JButton previousButton = Ui.button("Previous", "chevron-left");
    private final JButton nextButton = Ui.button("Next", "chevron-right");
    private final JLabel footerInfo = Ui.label(" ", Palette::secondaryText, "small");
    private final JLabel emptyLabel = Ui.label("The slides of the selected lyrics, verses, presentation or slide deck appear here.",
            Palette::mutedText, "small");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private final JPanel headerActions;
    private final JPanel header;
    private final Set<Slide> presented = Collections.newSetFromMap(new IdentityHashMap<>());
    private List<Group> groups = List.of();
    /** The strip list {@link #groups} was computed from (the strip replaces its list on every change). */
    private List<Slide> groupedSlides;
    private int hovered = -1;
    /** Which part of the hovered card the mouse is on: 1 = EDIT, 2 = MORE, 0 = the card. */
    private int hoveredPart;
    private boolean syncing;
    private boolean collapsed;
    private Consumer<Boolean> collapseListener = c -> { };

    public ActionOrderPanel(SlideThumbnailStrip strip, Host host) {
        super(new BorderLayout());
        this.strip = strip;
        this.host = host;
        setOpaque(false);

        // header: what this is, how many slides, CLEAR (added by the window)
        countLabel.setIcon(Icons.get("layers", Icons.SMALL, Palette::secondaryText));
        countLabel.setForeground(Palette.text());
        countLabel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Palette.border(), 1, true),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        headerActions = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerActions.add(countLabel);
        JPanel titles = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 0));
        titles.add(titleLabel);
        titles.add(subtitleLabel);
        JPanel left = Ui.transparent(new BorderLayout(12, 0));
        left.add(kindBadge, BorderLayout.WEST);
        left.add(titles, BorderLayout.CENTER);
        header = Ui.transparent(new BorderLayout(10, 0));
        header.add(left, BorderLayout.CENTER);
        header.add(headerActions, BorderLayout.EAST);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 14, 12, 12)));

        // toolbar: what a click does, move the selected section, add a slide (decks only)
        moveUpButton.addActionListener(e -> moveSelected(-1));
        moveDownButton.addActionListener(e -> moveSelected(1));
        addSlideButton.setToolTipText("Add a new slide after the selected one");
        addSlideButton.addActionListener(e -> host.addSlide(Math.max(-1, strip.getSelectedIndex())));
        JPanel tools = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        tools.add(moveUpButton);
        tools.add(moveDownButton);
        tools.add(addSlideButton);
        JPanel toolbar = Ui.transparent(new BorderLayout(8, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(8, 14, 4, 12));
        toolbar.add(clickHint, BorderLayout.CENTER);
        toolbar.add(tools, BorderLayout.EAST);

        JPanel top = Ui.transparent(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(toolbar, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        list.setCellRenderer(new CardRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new Reorder());
        // ONE click = ONE selection: a click that changes the selection is handled here (the same
        // host.select the lyrics cards use); mouseClicked only re-sends a card that was already selected
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !syncing && list.getSelectedIndex() >= 0) {
                lastSelectionMillis = System.currentTimeMillis();
                host.select(list.getSelectedIndex()); // as in the strip: Preview, or live when lyrics go live on click
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            /** When the button went down (the event's own time: the list may already have moved the selection). */
            private long pressedAt;

            @Override
            public void mousePressed(MouseEvent e) {
                pressedAt = e.getWhen();
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = rowAt(e.getPoint());
                if (i < 0 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int part = partAt(i, e.getPoint());
                if (part == 1 && host.canEditSlide(i)) {
                    host.editSlide(i);
                    return;
                }
                if (part == 2) {
                    Rectangle b = list.getCellBounds(i, i);
                    showMenu(i, list, b.x + b.width - GAP - ACTION_W, b.y + b.height / 2);
                    return;
                }
                boolean selectedByThisClick = lastSelectionMillis >= pressedAt;
                if (e.getClickCount() == 1 && !selectedByThisClick && i == list.getSelectedIndex()) {
                    host.select(i); // clicking the selected card again still counts (lyrics go live on click)
                }
                if (e.getClickCount() == 2 && host.doubleClickTakes()) {
                    host.take(i);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int i = rowAt(e.getPoint());
                int part = i < 0 ? 0 : partAt(i, e.getPoint());
                setHovered(i, part);
                list.setCursor(Cursor.getPredefinedCursor(i >= 0 && onHandle(i, e.getPoint()) && host.canEditOrder()
                        ? Cursor.MOVE_CURSOR : Cursor.HAND_CURSOR));
                list.setToolTipText(part == 1 && host.canEditSlide(i) ? "Edit" : part == 2 ? "More actions"
                        : i >= 0 && host.canEditOrder() && onHandle(i, e.getPoint()) ? "Drag to move this section" : null);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHovered(-1, 0);
            }

            private void popup(MouseEvent e) {
                int i = rowAt(e.getPoint());
                if (e.isPopupTrigger() && i >= 0) {
                    list.setSelectedIndex(i);
                    showMenu(i, e.getComponent(), e.getX(), e.getY());
                }
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        Rendering.thumbnails().addChangeListener(list::repaint);
        list.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                // card heights depend on the width (words wrap): make the list measure them again
                list.setFixedCellHeight(10);
                list.setFixedCellHeight(-1);
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        body.add(scroll, "list");
        body.add(emptyLabel, "empty");
        add(body, BorderLayout.CENTER);

        // footer: where we are, PREVIOUS / NEXT (the same as the arrow keys)
        previousButton.setToolTipText("Previous slide (Up / Page Up)");
        nextButton.setToolTipText("Next slide (Down / Page Down)");
        nextButton.setHorizontalTextPosition(SwingConstants.LEFT);
        previousButton.addActionListener(e -> strip.selectPrevious());
        nextButton.addActionListener(e -> strip.selectNext());
        JPanel nav = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        nav.add(previousButton);
        nav.add(nextButton);
        JPanel footer = Ui.transparent(new BorderLayout(8, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(8, 14, 8, 12)));
        footerInfo.setIcon(Icons.get("tag", Icons.SMALL, Palette::secondaryText));
        footer.add(footerInfo, BorderLayout.CENTER);
        footer.add(nav, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        strip.addSlidesChangedListener(this::rebuild);
        strip.addSelectionListener(i -> sync(false));
        strip.addLiveListener(() -> {
            int live = strip.getLiveIndex();
            if (live >= 0 && live < strip.getSlides().size()) {
                presented.add(strip.getSlides().get(live));
            }
            sync(true);
        });
        rebuild();
    }

    /**
     * Heading for the current content: "Lyrics – Amazing Grace" shows "Amazing Grace" big, with "Order · Lyrics"
     * under it (the part before the dash says what kind of content it is).
     */
    public void setTitle(String text) {
        String t = text == null ? "" : text;
        int dash = t.indexOf(" – ");
        String kind = dash > 0 ? t.substring(0, dash) : t;
        titleLabel.setText(dash > 0 ? t.substring(dash + 3) : t);
        subtitleLabel.setText("Order  ·  " + (kind.isBlank() ? "Slides" : kind));
        kindBadge.setKind(kind);
    }

    /** Measures and repaints every card again (after the letter case or typography changed). */
    public void refreshCards() {
        list.setFixedCellHeight(10);
        list.setFixedCellHeight(-1);
        list.repaint();
    }

    public void setClickHint(String text, boolean goesLive) {
        clickHint.setText(text);
        clickHint.setForeground(goesLive ? Palette.danger() : Palette.secondaryText());
        clickHint.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, goesLive ? "font: bold -1" : "font: -1");
    }

    /** Called with true when the operator collapses the cards (to give the space back to the strip). */
    public void setCollapseListener(Consumer<Boolean> l) {
        collapseListener = l;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean collapse, boolean notify) {
        collapsed = collapse;
        body.setVisible(!collapse);
        revalidate();
        if (notify) {
            collapseListener.accept(collapse);
        }
    }

    /** Adds a control to the header, left of the slide count (e.g. CLEAR). */
    public void addHeaderAction(JComponent action) {
        headerActions.add(action);
    }

    /** Height of the header alone, for the collapsed divider position. */
    public int headerHeight() {
        return header.getPreferredSize().height + 2;
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(320, headerHeight());
    }

    // ------------------------------------------------------------------ model

    /** Groups slides: "X (1/n)" … "X (n/n)" in a row are one entry; everything else is its own entry. */
    public static List<Group> groups(List<Slide> slides) {
        List<Group> out = new ArrayList<>();
        int start = -1;
        String base = null;
        int lastPart = 0;
        for (int i = 0; i < slides.size(); i++) {
            String label = slides.get(i).getLabel() == null ? "" : slides.get(i).getLabel();
            Matcher m = PART.matcher(label);
            if (m.matches()) {
                int part = Integer.parseInt(m.group(2));
                if (start >= 0 && part > 1 && part == lastPart + 1 && m.group(1).equals(base)) {
                    lastPart = part;
                    continue;
                }
                close(out, start, i, base);
                start = i;
                base = m.group(1);
                lastPart = part;
            } else {
                close(out, start, i, base);
                start = i;
                base = label.isBlank() ? "Slide " + (i + 1) : label;
                lastPart = 0;
            }
        }
        close(out, start, slides.size(), base);
        return out;
    }

    private static void close(List<Group> out, int start, int end, String label) {
        if (start >= 0 && end > start) {
            out.add(new Group(start, end - start, label));
        }
    }

    private void rebuild() {
        List<Slide> slides = strip.getSlides();
        groupedSlides = slides;
        groups = groups(slides);
        if (slides.stream().noneMatch(presented::contains)) {
            presented.clear(); // a different song / presentation
        }
        syncing = true;
        try {
            model.clear();
            model.addAll(slides);
        } finally {
            syncing = false;
        }
        countLabel.setText(slides.size() + (slides.size() == 1 ? " Slide" : " Slides"));
        countLabel.setVisible(!slides.isEmpty());
        addSlideButton.setVisible(host.canAddSlide());
        cards.show(body, slides.isEmpty() ? "empty" : "list");
        sync(false);
    }

    /** Follows the strip: highlights the Preview card; after a TAKE keeps the LIVE card and the next one in view. */
    private void sync(boolean liveMoved) {
        if (strip.getSlides() != groupedSlides) {
            rebuild(); // the strip fires its selection before announcing new slides
            return;
        }
        int selected = strip.getSelectedIndex();
        syncing = true;
        try {
            if (selected >= 0 && selected < model.size()) {
                list.setSelectedIndex(selected);
            } else {
                list.clearSelection();
            }
        } finally {
            syncing = false;
        }
        int live = strip.getLiveIndex();
        if (liveMoved && live >= 0) {
            list.ensureIndexIsVisible(Math.min(model.size() - 1, live + 1));
            list.ensureIndexIsVisible(live);
        } else if (selected >= 0) {
            list.ensureIndexIsVisible(selected);
        }
        boolean any = !model.isEmpty();
        previousButton.setEnabled(any && selected > 0);
        nextButton.setEnabled(any && selected < model.size() - 1);
        int g = groupIndexOf(selected);
        boolean edit = host.canEditOrder();
        moveUpButton.setVisible(edit);
        moveDownButton.setVisible(edit);
        moveUpButton.setEnabled(edit && g > 0);
        moveDownButton.setEnabled(edit && g >= 0 && g < groups.size() - 1);
        updateFooter();
        list.repaint();
    }

    /** "Current: VERSE 3 (Slide 5) · Live: Slide 4 · Total: 6 slides". */
    private void updateFooter() {
        int n = model.size();
        if (n == 0) {
            footerInfo.setText(" ");
            return;
        }
        int sel = strip.getSelectedIndex();
        int live = strip.getLiveIndex();
        StringBuilder s = new StringBuilder();
        if (sel >= 0 && sel < n) {
            s.append("Current: ").append(sectionLabel(sel)).append(" (Slide ").append(sel + 1).append(")");
        }
        if (live >= 0 && live < n && live != sel) {
            s.append(s.isEmpty() ? "" : "  ·  ").append("Live: Slide ").append(live + 1);
        }
        s.append(s.isEmpty() ? "" : "  ·  ").append("Total: ").append(n).append(n == 1 ? " slide" : " slides");
        footerInfo.setText(s.toString());
    }

    private Group groupOf(int index) {
        for (Group g : groups) {
            if (g.contains(index)) {
                return g;
            }
        }
        return null;
    }

    private int groupIndexOf(int slideIndex) {
        for (int g = 0; g < groups.size(); g++) {
            if (groups.get(g).contains(slideIndex)) {
                return g;
            }
        }
        return -1;
    }

    private int rowAt(Point p) {
        int i = list.locationToIndex(p);
        return i >= 0 && list.getCellBounds(i, i).contains(p) ? i : -1;
    }

    /** 1 = the EDIT button, 2 = MORE (⋮), 0 = anywhere else on card {@code i}. */
    private int partAt(int i, Point p) {
        Rectangle b = list.getCellBounds(i, i);
        int right = b.x + b.width - GAP;
        if (p.x >= right - ACTION_W && p.x < right) {
            return 2;
        }
        if (host.canEditSlide(i) && p.x >= right - ACTION_W * 2 && p.x < right - ACTION_W) {
            return 1;
        }
        return 0;
    }

    private boolean onHandle(int i, Point p) {
        Rectangle b = list.getCellBounds(i, i);
        return p.x < b.x + GAP + 26;
    }

    private void setHovered(int i, int part) {
        if (i != hovered || part != hoveredPart) {
            hovered = i;
            hoveredPart = part;
            list.repaint();
        }
    }

    private State state(int index) {
        if (index == strip.getLiveIndex()) {
            return State.LIVE;
        }
        if (index == strip.getSelectedIndex()) {
            return State.PREVIEW;
        }
        List<Slide> slides = strip.getSlides();
        return index < slides.size() && presented.contains(slides.get(index)) ? State.DONE : State.NEXT;
    }

    // ------------------------------------------------------------------ editing the order (whole sections)

    private List<List<Slide>> entries() {
        List<List<Slide>> out = new ArrayList<>();
        List<Slide> slides = strip.getSlides();
        for (Group g : groups) {
            out.add(new ArrayList<>(slides.subList(g.start, g.start + g.count)));
        }
        return out;
    }

    private void moveSelected(int delta) {
        int g = groupIndexOf(strip.getSelectedIndex());
        if (g >= 0) {
            moveEntry(g, g + delta);
        }
    }

    private void moveEntry(int from, int to) {
        if (!host.canEditOrder() || from == to || from < 0 || from >= groups.size() || to < 0 || to >= groups.size()) {
            return;
        }
        List<List<Slide>> e = entries();
        List<Slide> moved = e.remove(from);
        e.add(Math.clamp(to, 0, e.size()), moved);
        int toSlide = 0;
        for (int i = 0; i < to; i++) {
            toSlide += e.get(i).size();
        }
        host.moveEntry(groups.get(from).start, toSlide, e);
    }

    private void duplicateEntry(int group) {
        List<List<Slide>> e = entries();
        List<Slide> copy = new ArrayList<>();
        for (Slide s : e.get(group)) {
            copy.add(s.copy());
        }
        e.add(group + 1, copy);
        host.duplicateEntry(groups.get(group).start, e);
    }

    private void removeEntry(int group) {
        if (groups.size() <= 1) {
            return;
        }
        List<List<Slide>> e = entries();
        e.remove(group);
        host.removeEntry(groups.get(group).start, e);
    }

    private void showMenu(int slide, Component at, int x, int y) {
        int g = groupIndexOf(slide);
        if (g < 0) {
            return;
        }
        boolean edit = host.canEditOrder();
        String what = groups.get(g).count > 1 ? " \"" + groups.get(g).label + "\"" : "";
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Send to Preview", "monitor", true, () -> host.select(slide)));
        menu.add(item("Send to Live", "play", true, () -> host.take(slide)));
        if (host.canEditSlide(slide)) {
            menu.add(item("Edit…", "edit", true, () -> host.editSlide(slide)));
        }
        menu.addSeparator();
        menu.add(item("Move Up" + what, "arrow-up", edit && g > 0, () -> moveEntry(g, g - 1)));
        menu.add(item("Move Down" + what, "arrow-down", edit && g < groups.size() - 1, () -> moveEntry(g, g + 1)));
        menu.add(item("Duplicate" + what, "copy", edit, () -> duplicateEntry(g)));
        menu.add(item("Remove from Order" + what, "trash", edit && groups.size() > 1, () -> removeEntry(g)));
        if (host.canAddSlide()) {
            menu.add(item("Add Slide After", "plus", true, () -> host.addSlide(slide)));
        }
        if (host.canResetOrder()) {
            menu.addSeparator();
            menu.add(item("Reset to Written Order", "refresh", true, host::resetOrder));
        }
        menu.show(at, x, y);
    }

    private static JMenuItem item(String text, String icon, boolean enabled, Runnable action) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        i.setEnabled(enabled);
        i.addActionListener(ev -> action.run());
        return i;
    }

    /** Drag a card to move its whole section (or single slide) to a new position; a blue line shows where. */
    private final class Reorder extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return host.canEditOrder() ? MOVE : NONE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            int g = groupIndexOf(list.getSelectedIndex());
            return g < 0 ? null : new StringSelection("jamc-order:" + g);
        }

        @Override
        public boolean canImport(TransferSupport support) {
            boolean ok = support.isDrop() && host.canEditOrder() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
            list.repaint(); // the drop line follows the mouse
            return ok;
        }

        @Override
        public boolean importData(TransferSupport support) {
            try {
                String data = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                if (!data.startsWith("jamc-order:")) {
                    return false;
                }
                int from = Integer.parseInt(data.substring("jamc-order:".length()));
                int row = ((JList.DropLocation) support.getDropLocation()).getIndex();
                int to;
                if (row < 0 || row >= model.size()) {
                    to = groups.size();
                } else {
                    int g = groupIndexOf(row);
                    to = groups.get(g).start == row ? g : g + 1; // dropping inside a section goes after it
                }
                if (to > from) {
                    to--; // the entry leaves its old place first
                }
                int finalTo = Math.min(to, groups.size() - 1);
                SwingUtilities.invokeLater(() -> moveEntry(from, finalTo));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            list.repaint();
        }
    }

    // ------------------------------------------------------------------ painting

    /** The content kind's picture in the header: a rounded square with its icon (lyrics, Bible, slides...). */
    private static final class KindBadge extends JComponent {
        private String icon = "music";

        KindBadge() {
            setPreferredSize(new Dimension(52, 52));
        }

        void setKind(String kind) {
            String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
            icon = k.contains("lyric") || k.contains("song") ? "music" : k.contains("verse") || k.contains("bible") ? "book"
                    : k.contains("presentation") || k.contains("powerpoint") ? "presentation" : "slides";
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Palette.withAlpha(Palette.accent(), 60));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g.setColor(Palette.withAlpha(Palette.accent(), 140));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            int s = 26;
            Icons.get(icon, s, Palette::text).paintIcon(this, g, (getWidth() - s) / 2, (getHeight() - s) / 2);
            g.dispose();
        }
    }

    /**
     * A rounded card: drag handle and number on the left, the section pill and the words (or a page picture) in the
     * middle, the state and "Slide n" on the right, then EDIT (when the content has an editor) and ⋮.
     */
    private final class CardRenderer extends JComponent implements ListCellRenderer<Slide> {
        private Slide slide;
        private int index;

        @Override
        public Component getListCellRendererComponent(JList<? extends Slide> l, Slide value, int i, boolean selected,
                                                      boolean focused) {
            this.slide = value;
            this.index = i;
            setFont(l.getFont());
            FontMetrics fm = getFontMetrics(textFont());
            int textWidth = Math.max(120, textWidth(l.getWidth() > 0 ? l.getWidth() : 520));
            int body = value.getThumbnail() != null ? THUMB_H
                    : Math.max(1, cardLines(value, fm, textWidth).size()) * (fm.getHeight() - 1);
            setPreferredSize(new Dimension(100, GAP + 12 + 22 + body + 14 + GAP));
            return this;
        }

        private Font textFont() {
            return getFont().deriveFont(Font.PLAIN, 14f);
        }

        /** Width left for the words in a card of a list {@code listWidth} wide. */
        private int textWidth(int listWidth) {
            return listWidth - GAP * 2 - GUTTER - 22 - STATE_W - ACTION_W * 2 - 12;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (slide == null || strip.getSlides() != groupedSlides || index >= strip.getSlides().size()) {
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            State st = state(index);
            boolean live = st == State.LIVE;
            boolean preview = st == State.PREVIEW;
            int x = GAP;
            int y = GAP;
            int w = getWidth() - GAP * 2;
            int h = getHeight() - GAP * 2;

            // card: soft red for LIVE, soft blue for PREVIEW, a lighter navy when hovered
            Color fill = live ? Palette.withAlpha(Palette.danger(), 34) : preview ? Palette.withAlpha(Palette.accent(), 30)
                    : index == hovered ? Palette.hoverBackground() : Palette.secondaryBackground();
            g.setColor(fill);
            g.fillRoundRect(x, y, w, h, ARC, ARC);
            g.setColor(live ? Palette.danger() : preview ? Palette.accent() : Palette.border());
            g.setStroke(new BasicStroke(live || preview ? 2f : 1f));
            g.drawRoundRect(x, y, w - 1, h - 1, ARC, ARC);

            // drop line while a card is dragged over this one
            JList.DropLocation drop = list.getDropLocation();
            if (drop != null && drop.isInsert() && drop.getIndex() == index) {
                g.setColor(Palette.accent());
                g.fillRoundRect(x + 4, 0, w - 8, 4, 4, 4);
            }

            // left gutter: drag handle (only when the order can change) and the big number
            if (host.canEditOrder()) {
                g.setColor(Palette.mutedText());
                int hx = x + 12;
                int hy = y + h / 2 - 7;
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 2; c++) {
                        g.fillOval(hx + c * 6, hy + r * 6, 3, 3);
                    }
                }
            }
            g.setFont(getFont().deriveFont(Font.BOLD, 19f));
            FontMetrics nm = g.getFontMetrics();
            String num = String.format("%02d", index + 1);
            g.setColor(live ? Palette.danger() : st == State.DONE ? Palette.secondaryText() : Palette.text());
            g.drawString(num, x + 30, y + h / 2 + nm.getAscent() / 2 - 2);
            // accent bar: blue, red when LIVE
            int barX = x + GUTTER;
            g.setColor(live ? Palette.danger() : Palette.withAlpha(Palette.accent(), preview ? 255 : 170));
            g.fillRoundRect(barX, y + 8, 4, h - 16, 4, 4);

            // middle: section pill, then the words or a page picture
            int lx = barX + 16;
            int right = x + w - ACTION_W * 2 - STATE_W;
            Font small = getFont().deriveFont(Font.BOLD, 10.5f);
            g.setFont(small);
            FontMetrics sm = g.getFontMetrics();
            String label = fit(sectionLabel(index), sm, right - lx - 16);
            int pw = sm.stringWidth(label) + 16;
            int py = y + 10;
            g.setColor(Palette.withAlpha(Palette.accent(), 55));
            g.fillRoundRect(lx, py, pw, 20, 20, 20);
            g.setColor(Palette.withAlpha(Palette.accent(), 150));
            g.drawRoundRect(lx, py, pw - 1, 19, 20, 20);
            g.setColor(Palette.text());
            g.drawString(label, lx + 8, py + 14);

            int by = py + 26;
            Font text = textFont();
            g.setFont(text);
            FontMetrics tm = g.getFontMetrics();
            Color words = st == State.DONE ? Palette.secondaryText() : Palette.text();
            if (slide.getThumbnail() != null) {
                BufferedImage thumb = Rendering.thumbnails().get(slide.getThumbnail());
                if (thumb != null) {
                    g.drawImage(thumb, lx, by, THUMB_W, THUMB_H, null);
                } else {
                    g.setColor(Palette.monitorBackground());
                    g.fillRect(lx, by, THUMB_W, THUMB_H);
                }
                g.setColor(Palette.border());
                g.drawRect(lx, by, THUMB_W - 1, THUMB_H - 1);
                g.setColor(words);
                String title = "Slide " + (index + 1) + (hasTitle() ? " – " + slide.getLabel() : "");
                g.drawString(fit(title, tm, right - (lx + THUMB_W + 12)), lx + THUMB_W + 12, by + tm.getAscent() + 4);
            } else {
                g.setColor(words);
                int line = 0;
                for (String s : cardLines(slide, tm, right - lx - 8)) {
                    g.drawString(fit(s, tm, right - lx - 8), lx, by + tm.getAscent() + line * (tm.getHeight() - 1));
                    line++;
                }
            }

            // state column: ✓ SHOWN (green) / ● LIVE (red) / ▶ PREVIEW (blue), and "Slide n"
            int sx = right;
            String pill = live ? "● LIVE" : preview ? "▶ PREVIEW" : st == State.DONE ? "✓ SHOWN" : null;
            int midY = y + h / 2;
            g.setFont(small);
            if (pill != null) {
                int ppw = sm.stringWidth(pill) + 18;
                int ppx = sx + (STATE_W - ppw) / 2;
                Color pc = live ? Palette.danger() : preview ? Palette.accent() : Palette.live();
                g.setColor(live || preview ? pc : Palette.withAlpha(pc, 60));
                g.fillRoundRect(ppx, midY - 22, ppw, 20, 20, 20);
                if (!live && !preview) {
                    g.setColor(pc);
                    g.drawRoundRect(ppx, midY - 22, ppw - 1, 19, 20, 20);
                }
                g.setColor(live || preview ? Color.WHITE : pc);
                g.drawString(pill, ppx + 9, midY - 8);
            }
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            FontMetrics nm2 = g.getFontMetrics();
            String n = "Slide " + (index + 1);
            g.setColor(Palette.secondaryText());
            g.drawString(n, sx + (STATE_W - nm2.stringWidth(n)) / 2, pill != null ? midY + 14 : midY + nm2.getAscent() / 2);

            // actions: EDIT (only when supported) and ⋮, after a thin divider
            int ax = x + w - ACTION_W * 2;
            g.setColor(Palette.border());
            g.drawLine(ax - 2, y + 12, ax - 2, y + h - 12);
            int iconSize = 18;
            if (host.canEditSlide(index)) {
                paintAction(g, "edit", ax, midY, iconSize, hovered == index && hoveredPart == 1);
            }
            paintMore(g, ax + ACTION_W, midY, hovered == index && hoveredPart == 2);
            g.dispose();
        }

        private void paintAction(Graphics2D g, String icon, int ax, int midY, int size, boolean hot) {
            if (hot) {
                g.setColor(Palette.hoverBackground());
                g.fillRoundRect(ax + 2, midY - 15, ACTION_W - 4, 30, 8, 8);
            }
            Icons.get(icon, size, hot ? Palette::text : Palette::secondaryText)
                    .paintIcon(this, g, ax + (ACTION_W - size) / 2, midY - size / 2);
        }

        private void paintMore(Graphics2D g, int ax, int midY, boolean hot) {
            if (hot) {
                g.setColor(Palette.hoverBackground());
                g.fillRoundRect(ax + 2, midY - 15, ACTION_W - 6, 30, 8, 8);
            }
            g.setColor(hot ? Palette.text() : Palette.secondaryText());
            int cx = ax + ACTION_W / 2 - 2;
            for (int k = -1; k <= 1; k++) {
                g.fillOval(cx, midY + k * 6 - 2, 4, 4);
            }
        }

        private boolean hasTitle() {
            String l = slide.getLabel();
            return l != null && !l.isBlank() && !l.matches("Slide \\d+");
        }
    }

    /** "VERSE 1", "CHORUS · 2/2", "JOHN 3:16", or the content title. */
    private String sectionLabel(int index) {
        List<Slide> slides = strip.getSlides();
        Group gr = groupOf(index);
        String label = gr != null ? gr.label : index < slides.size() ? slides.get(index).getLabel() : null;
        if (label == null || label.isBlank()) {
            label = "Slide";
        }
        String part = gr != null && gr.count > 1 ? " · " + (index - gr.start + 1) + "/" + gr.count : "";
        return label.toUpperCase(Locale.ROOT) + part;
    }

    /** Card lines, in the presentation letter case only when Settings asks for it (as typed by default). */
    private List<String> cardLines(Slide s, FontMetrics fm, int width) {
        SlideStyle base = strip.getBaseStyle();
        TextTransform t = host.cardsUseTextCase() && base != null ? s.styleOver(base).textTransform() : TextTransform.ORIGINAL;
        return lines(s, fm, width, t);
    }

    static List<String> lines(Slide s, FontMetrics fm, int width) {
        return lines(s, fm, width, TextTransform.ORIGINAL);
    }

    /**
     * The slide's words wrapped to {@code width}, at most {@value #MAX_LINES} lines (longer slides end with "…"),
     * then any caption such as the Bible version; in {@code textCase} when Settings asks for it.
     */
    static List<String> lines(Slide s, FontMetrics fm, int width, TextTransform textCase) {
        List<String> out = new ArrayList<>();
        String text = s.getText() == null ? "" : textCase.apply(s.getText().strip());
        if (text.isEmpty()) {
            String fallback = s.getCaption() != null && !s.getCaption().isBlank() ? s.getCaption()
                    : s.getLabel() == null ? "" : s.getLabel();
            out.add(fallback);
            return out;
        }
        List<String> wrapped = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                wrapped.addAll(wrap(line.strip(), fm, width));
            }
        }
        for (String line : wrapped) {
            if (out.size() == MAX_LINES) {
                out.set(MAX_LINES - 1, out.get(MAX_LINES - 1) + " …");
                break;
            }
            out.add(line);
        }
        if (s.getCaption() != null && !s.getCaption().isBlank()) {
            out.add(s.getCaption());
        }
        return out;
    }

    static List<String> wrap(String line, FontMetrics fm, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : line.split(" +")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (current.isEmpty() || fm.stringWidth(candidate) <= width) {
                current.setLength(0);
                current.append(candidate);
            } else {
                out.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static String fit(String s, FontMetrics fm, int width) {
        if (width <= 10 || fm.stringWidth(s) <= width) {
            return s;
        }
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > width) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }
}
