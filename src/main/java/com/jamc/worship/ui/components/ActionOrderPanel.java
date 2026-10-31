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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
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
 * Vertical slide cards: one rounded card per slide of whatever the slide strip shows, with the
 * slide's actual words (lyrics, Bible text, announcement text) or, for PowerPoint, a thumbnail and
 * title. The operator recognises the next lyrics at a glance instead of remembering section names;
 * the section ("VERSE 1") and slide number are only small labels.
 *
 * <p>This is the order inside the current content. The service schedule decides which content comes
 * next; these cards decide which slide of it comes next. They share the strip's slides and selection:
 * clicking a card selects the slide and loads it into Preview only (Program changes on TAKE, or on a
 * double-click if that is enabled in Settings). Cards show LIVE, PREVIEW and already-shown states with
 * text and icons, not only colour. Sections can still be reordered, duplicated or removed (songs keep
 * that order as their arrangement).
 */
public class ActionOrderPanel extends JPanel {

    /** What the cards ask of the window that owns the strip. Slide indices refer to the strip. */
    public interface Host {
        void select(int slideIndex);

        void take(int slideIndex);

        /** Whether a double-click sends a slide live (Settings); otherwise it only previews. */
        boolean doubleClickTakes();

        /** False for content whose order is edited elsewhere (custom slide decks). */
        boolean canEditOrder();

        /** Applies a new sequence of entries; duplicated entries contain copied slides. */
        void applyOrder(List<List<Slide>> entries);

        boolean canResetOrder();

        void resetOrder();

        /** Settings, Interface: cards show the presentation letter case instead of the words as typed. */
        default boolean cardsUseTextCase() {
            return false;
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

    private final SlideThumbnailStrip strip;
    private final Host host;
    /** When the list last selected a card for the host (to tell a selecting click from a re-click). */
    private long lastSelectionMillis;
    private final DefaultListModel<Slide> model = new DefaultListModel<>();
    private final JList<Slide> list = new JList<>(model);
    private final JLabel countLabel = Ui.small("");
    private final JLabel clickHint = new JLabel("");
    private final JLabel titleLabel = Ui.label("Lyrics & Slides", null, "semibold");
    private final JButton previousButton = Ui.toolButton("arrow-up", "Previous slide to Preview (Up)");
    private final JButton nextButton = Ui.toolButton("arrow-down", "Next slide to Preview (Down)");
    private final JButton collapseButton = Ui.toolButton("chevron-up", "Collapse the slide cards");
    private final JLabel emptyLabel = Ui.label("The lyrics and slides of the selected song, verses or presentation appear here.",
            Palette::mutedText, "small");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private final JPanel right;
    private final Set<Slide> presented = Collections.newSetFromMap(new IdentityHashMap<>());
    private List<Group> groups = List.of();
    /** The strip list {@link #groups} was computed from (the strip replaces its list on every change). */
    private List<Slide> groupedSlides;
    private int hovered = -1;
    private boolean syncing;
    private boolean collapsed;
    private Consumer<Boolean> collapseListener = c -> { };

    public ActionOrderPanel(SlideThumbnailStrip strip, Host host) {
        super(new BorderLayout());
        this.strip = strip;
        this.host = host;
        setOpaque(false);

        previousButton.addActionListener(e -> strip.selectPrevious());
        nextButton.addActionListener(e -> strip.selectNext());
        collapseButton.addActionListener(e -> setCollapsed(!collapsed, true));
        right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(countLabel);
        right.add(previousButton);
        right.add(nextButton);
        right.add(collapseButton);
        JPanel title = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        title.add(titleLabel);
        title.add(clickHint);
        JPanel header = Ui.header(title, null, right);
        header.setBorder(BorderFactory.createEmptyBorder(6, 12, 4, 8));
        add(header, BorderLayout.NORTH);

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
                host.select(list.getSelectedIndex()); // Preview only; Program waits for TAKE
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
                boolean selectedByThisClick = lastSelectionMillis >= pressedAt;
                if (i >= 0 && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 && !selectedByThisClick
                        && i == list.getSelectedIndex()) {
                    host.select(i); // clicking the selected card again still counts (lyrics go live on click)
                }
                if (i >= 0 && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && host.doubleClickTakes()) {
                    host.take(i);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setHovered(rowAt(e.getPoint()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHovered(-1);
            }

            private void popup(MouseEvent e) {
                int i = rowAt(e.getPoint());
                if (e.isPopupTrigger() && i >= 0) {
                    list.setSelectedIndex(i);
                    showMenu(i, e);
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
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        body.add(scroll, "list");
        body.add(emptyLabel, "empty");
        add(body, BorderLayout.CENTER);

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

    /** Heading for the current workspace: "Lyrics & Slides", "Verse Slides", "Presentation Slides"… */
    public void setTitle(String text) {
        titleLabel.setText(text);
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
        countLabel.setVisible(!collapse);
        collapseButton.setIcon(Icons.get(collapse ? "chevron-down" : "chevron-up", Icons.MEDIUM));
        collapseButton.setToolTipText(collapse ? "Show the slide cards" : "Collapse the slide cards");
        revalidate();
        if (notify) {
            collapseListener.accept(collapse);
        }
    }

    /** Adds a control to the left of the header's count and buttons (e.g. CLEAR). */
    public void addHeaderAction(JComponent action) {
        right.add(action, 0);
    }

    /** Height of the header alone, for the collapsed divider position. */
    public int headerHeight() {
        return getComponent(0).getPreferredSize().height + 2;
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, headerHeight());
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
        countLabel.setText(slides.isEmpty() ? "" : slides.size() + (slides.size() == 1 ? " slide" : " slides"));
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
        list.repaint();
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

    private void setHovered(int i) {
        if (i != hovered) {
            hovered = i;
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

    private void moveEntry(int from, int to) {
        if (!host.canEditOrder() || from == to || from < 0 || from >= groups.size()) {
            return;
        }
        List<List<Slide>> e = entries();
        List<Slide> moved = e.remove(from);
        e.add(Math.clamp(to, 0, e.size()), moved);
        host.applyOrder(e);
    }

    private void duplicateEntry(int group) {
        List<List<Slide>> e = entries();
        List<Slide> copy = new ArrayList<>();
        for (Slide s : e.get(group)) {
            copy.add(s.copy());
        }
        e.add(group + 1, copy);
        host.applyOrder(e);
    }

    private void removeEntry(int group) {
        if (groups.size() <= 1) {
            return;
        }
        List<List<Slide>> e = entries();
        e.remove(group);
        host.applyOrder(e);
    }

    private void showMenu(int slide, MouseEvent e) {
        int g = groupIndexOf(slide);
        if (g < 0) {
            return;
        }
        boolean edit = host.canEditOrder();
        String what = groups.get(g).count > 1 ? " \"" + groups.get(g).label + "\"" : "";
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Send to Preview", "monitor", true, () -> host.select(slide)));
        menu.add(item("Send to Live", "play", true, () -> host.take(slide)));
        menu.addSeparator();
        menu.add(item("Move Up" + what, "arrow-up", edit && g > 0, () -> moveEntry(g, g - 1)));
        menu.add(item("Move Down" + what, "arrow-down", edit && g < groups.size() - 1, () -> moveEntry(g, g + 1)));
        menu.add(item("Duplicate" + what, "copy", edit, () -> duplicateEntry(g)));
        menu.add(item("Remove from Order" + what, "trash", edit && groups.size() > 1, () -> removeEntry(g)));
        if (host.canResetOrder()) {
            menu.addSeparator();
            menu.add(item("Reset to Written Order", "refresh", true, host::resetOrder));
        }
        if (!edit) {
            menu.addSeparator();
            JMenuItem note = new JMenuItem("Reorder custom slides in the slide strip");
            note.setEnabled(false);
            menu.add(note);
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private static JMenuItem item(String text, String icon, boolean enabled, Runnable action) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        i.setEnabled(enabled);
        i.addActionListener(ev -> action.run());
        return i;
    }

    /** Drag a card to move its whole section (or single slide) to a new position. */
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
            return support.isDrop() && host.canEditOrder() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
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
                int finalTo = to;
                SwingUtilities.invokeLater(() -> moveEntry(from, finalTo));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ painting

    /** A rounded card: small label row, then the slide's words (or a PowerPoint thumbnail and title). */
    private final class CardRenderer extends JComponent implements ListCellRenderer<Slide> {
        private Slide slide;
        private int index;

        @Override
        public Component getListCellRendererComponent(JList<? extends Slide> l, Slide value, int i, boolean selected,
                                                      boolean focused) {
            this.slide = value;
            this.index = i;
            setFont(l.getFont());
            FontMetrics fm = getFontMetrics(getFont().deriveFont(Font.PLAIN, 12.5f));
            int textWidth = Math.max(120, (l.getWidth() > 0 ? l.getWidth() : 420) - 16 - 24);
            int body = value.getThumbnail() != null ? THUMB_H
                    : Math.max(1, cardLines(value, fm, textWidth).size()) * (fm.getHeight() - 1);
            setPreferredSize(new Dimension(100, 6 + 18 + body + 12 + 6));
            return this;
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
            int x = 8;
            int y = 3;
            int w = getWidth() - 16;
            int h = getHeight() - 6;

            Color fill = st == State.LIVE ? Palette.withAlpha(Palette.danger(), 40)
                    : st == State.PREVIEW ? Palette.withAlpha(Palette.accent(), 40)
                    : index == hovered ? Palette.hoverBackground() : Palette.secondaryBackground();
            g.setColor(fill);
            g.fillRoundRect(x, y, w, h, 12, 12);
            Color border = st == State.LIVE ? Palette.danger() : st == State.PREVIEW ? Palette.accent() : Palette.border();
            g.setColor(border);
            g.setStroke(new BasicStroke(st == State.LIVE || st == State.PREVIEW ? 2f : 1f));
            g.drawRoundRect(x, y, w - 1, h - 1, 12, 12);
            if (st == State.LIVE) {
                g.fillRoundRect(x, y + 4, 4, h - 8, 4, 4); // a bar along the edge, in addition to the colour
            }

            // label row: SECTION · state pill · SLIDE n
            int lx = x + 12;
            int ly = y + 5;
            Font small = getFont().deriveFont(Font.BOLD, 9.5f);
            g.setFont(small);
            FontMetrics sm = g.getFontMetrics();
            int right = x + w - 10;
            String number = "SLIDE " + (index + 1);
            g.setColor(Palette.mutedText());
            g.drawString(number, right - sm.stringWidth(number), ly + sm.getAscent());
            right -= sm.stringWidth(number) + 8;
            String pill = st == State.LIVE ? "● LIVE" : st == State.PREVIEW ? "▶ PREVIEW" : st == State.DONE ? "✓ SHOWN" : null;
            if (pill != null) {
                int pw = sm.stringWidth(pill) + 12;
                g.setColor(st == State.LIVE ? Palette.danger() : st == State.PREVIEW ? Palette.accent()
                        : Palette.withAlpha(Palette.live(), 150));
                g.fillRoundRect(right - pw, ly - 1, pw, sm.getHeight() + 2, 10, 10);
                g.setColor(Color.WHITE);
                g.drawString(pill, right - pw + 6, ly + sm.getAscent());
                right -= pw + 8;
            }
            g.setColor(st == State.DONE ? Palette.mutedText() : Palette.secondaryText());
            g.drawString(fit(sectionLabel(), sm, right - lx), lx, ly + sm.getAscent());

            // body: the words, or a thumbnail and title
            int by = ly + 18;
            Font text = getFont().deriveFont(Font.PLAIN, 12.5f);
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
                g.drawString(fit(title, tm, x + w - 12 - (lx + THUMB_W + 12)), lx + THUMB_W + 12, by + tm.getAscent() + 4);
            } else {
                g.setColor(words);
                int line = 0;
                for (String s : cardLines(slide, tm, x + w - 12 - lx)) {
                    g.drawString(fit(s, tm, x + w - 12 - lx), lx, by + tm.getAscent() + line * (tm.getHeight() - 1));
                    line++;
                }
            }
            g.dispose();
        }

        private boolean hasTitle() {
            String l = slide.getLabel();
            return l != null && !l.isBlank() && !l.matches("Slide \\d+");
        }

        /** "VERSE 1", "CHORUS · 2/2", "JOHN 3:16", or the content title. */
        private String sectionLabel() {
            Group gr = groupOf(index);
            String label = gr != null ? gr.label : slide.getLabel();
            if (label == null || label.isBlank()) {
                label = "Slide";
            }
            String part = gr != null && gr.count > 1 ? " · " + (index - gr.start + 1) + "/" + gr.count : "";
            return label.toUpperCase(Locale.ROOT) + part;
        }
    }

    /**
     * The slide's words wrapped to {@code width}, at most {@value #MAX_LINES} lines (longer slides end
     * with "…"), then any caption such as the Bible version.
     */
    /** Card lines, in the presentation letter case only when Settings asks for it (as typed by default). */
    private List<String> cardLines(Slide s, FontMetrics fm, int width) {
        SlideStyle base = strip.getBaseStyle();
        TextTransform t = host.cardsUseTextCase() && base != null ? s.styleOver(base).textTransform() : TextTransform.ORIGINAL;
        return lines(s, fm, width, t);
    }

    static List<String> lines(Slide s, FontMetrics fm, int width) {
        return lines(s, fm, width, TextTransform.ORIGINAL);
    }

    /** Same, with the words in {@code textCase} (the presentation letter case, when Settings asks for it). */
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
