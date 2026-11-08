package com.jamc.worship.ui.components;

import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.output.Compositor;
import com.jamc.worship.output.FrameSpec;
import com.jamc.worship.output.LiveContent;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * SCENE, the homepage: every scene as a large card (live thumbnail, name, number of sources, PREVIEW /
 * LIVE). Clicking a card puts the scene in Preview and opens it here with its canvas, its sources and
 * + ADD SOURCE; TAKE (under the scene) puts it live. ← ALL SCENES goes back to the cards.
 *
 * <p>Preview · TAKE · Program are shown at the bottom of this page while it is open.
 */
public class ScenesHomePanel extends JPanel {

    private static final String HOME = "home";
    private static final String EDITOR = "editor";
    private static final int TILE_W = 220;
    private static final int TILE_H = 178;

    private final ScenesDock.Host host;
    private final java.util.function.Predicate<Scene> openEditor;
    /** Asked before ALL SCENES leaves the editor (false = stay: the operator cancelled over unsaved changes). */
    private java.util.function.BooleanSupplier leaveEditor = () -> true;
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private final JPanel grid = Ui.transparent(new GridLayout(0, 4, 12, 12));
    private final JLabel editorTitle = Ui.label("SCENE", Palette::text, "h3");
    private final JPanel liveHolder = Ui.transparent(new BorderLayout());
    private final List<Tile> tiles = new ArrayList<>();
    private final Compositor compositor = new Compositor(Rendering.renderer(), true);

    {
        compositor.setStillVideos(true); // thumbnails: a still picture of each video, never a playing one
        com.jamc.worship.media.VideoStills.addListener(this::repaint);
        com.jamc.worship.youtube.YouTubeLibrary.addListener(() -> javax.swing.SwingUtilities.invokeLater(this::repaint));
    }
    private final LiveContent sample = LiveContent.snapshot(new Slide("Sample", "Amazing grace\nHow sweet the sound", null),
            SlideStyle.fallback().withTextTransform(TextTransform.UPPERCASE), "Sample");
    private boolean editorShown;

    /**
     * @param editor     the scene editor (canvas, sources, + ADD SOURCE)
     * @param openEditor opens a scene in that editor
     */
    public ScenesHomePanel(ScenesDock.Host host, JComponent editor, java.util.function.Predicate<Scene> openEditor) {
        super(new BorderLayout());
        this.host = host;
        this.openEditor = openEditor;
        setOpaque(false);

        // ---- home: the scene cards
        JButton add = Ui.primaryButton("ADD SCENE", "plus");
        add.setToolTipText("Create a scene: blank, a copy of the current one, or from a template");
        add.addActionListener(e -> host.addScene());
        JPanel head = Ui.transparent(new BorderLayout(0, 4));
        head.add(Ui.label("SCENES", Palette::text, "h2"), BorderLayout.NORTH);
        head.add(Ui.label("Choose a scene: it goes to Preview and opens here, where + ADD SOURCE fills it with content. "
                + "TAKE puts it live.", Palette::secondaryText, null), BorderLayout.SOUTH);
        JPanel top = Ui.transparent(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));
        top.add(head, BorderLayout.CENTER);
        top.add(add, BorderLayout.EAST);

        JPanel holder = new WidthTracking();
        holder.add(grid, BorderLayout.NORTH);
        holder.setBorder(BorderFactory.createEmptyBorder(0, 20, 16, 20));
        JScrollPane scroll = new JScrollPane(holder);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int cols = Math.max(1, (scroll.getViewport().getWidth() - 40) / (TILE_W + 12));
                if (((GridLayout) grid.getLayout()).getColumns() != cols) {
                    grid.setLayout(new GridLayout(0, cols, 12, 12));
                }
                grid.revalidate(); // the tiles' height follows their width (Tile.getPreferredSize)
            }
        });
        JPanel home = Ui.transparent(new BorderLayout());
        home.add(top, BorderLayout.NORTH);
        home.add(scroll, BorderLayout.CENTER);
        JPanel bar = Ui.transparent(new BorderLayout());
        bar.add(actions, BorderLayout.WEST);
        actions.setPreferredSize(new Dimension(480, actions.getPreferredSize().height));
        home.add(bar, BorderLayout.SOUTH);

        // ---- one scene open: its canvas and sources
        JButton back = Ui.button("ALL SCENES", "chevron-left");
        back.setToolTipText("Back to all scenes");
        back.addActionListener(e -> {
            if (leaveEditor.getAsBoolean()) {
                showHome();
            }
        });
        JPanel editorHead = Ui.transparent(new BorderLayout(12, 0));
        editorHead.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        editorHead.add(back, BorderLayout.WEST);
        editorHead.add(editorTitle, BorderLayout.CENTER);
        JPanel editorPage = Ui.transparent(new BorderLayout());
        editorPage.add(editorHead, BorderLayout.NORTH);
        editorPage.add(editor, BorderLayout.CENTER);

        body.add(home, HOME);
        body.add(editorPage, EDITOR);
        liveHolder.setBorder(Ui.line(true, false, false, false));
        liveHolder.setPreferredSize(new Dimension(10, liveHeight));
        // PREVIEW · TAKE · PROGRAM under the scenes: drag the divider to make them taller or shorter (remembered)
        body.setMinimumSize(new Dimension(0, 160));
        liveHolder.setMinimumSize(new Dimension(0, 150));
        liveSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, body, liveHolder);
        liveSplit.setResizeWeight(1.0); // a bigger window gives the room to the scenes; the monitors keep their height
        liveSplit.setContinuousLayout(true);
        liveSplit.setBorder(null);
        liveSplit.setToolTipText(null);
        add(liveSplit, BorderLayout.CENTER);
        liveSplit.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                placeDivider(); // the saved height, once the page has a size (and when the window changes size)
            }
        });
        liveSplit.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (!placing && liveSplit.getHeight() > 0) {
                liveHeight = Math.max(150, liveSplit.getHeight() - liveSplit.getDividerLocation() - liveSplit.getDividerSize());
                liveHeightSaver.accept(liveHeight);
            }
        });

        new Timer(1500, e -> {
            if (isShowing() && !editorShown) {
                grid.repaint(); // camera thumbnails stay current
            }
        }).start();
    }

    /** Where Preview · TAKE · Program are placed while this page is open. */
    public JPanel liveHolder() {
        return liveHolder;
    }

    private javax.swing.JSplitPane liveSplit;
    /** Height of the Preview · TAKE · Program area (the operator drags the divider; remembered). */
    private int liveHeight = 300;
    private java.util.function.IntConsumer liveHeightSaver = h -> { };
    private boolean placing;

    /** The remembered height of Preview · Program on this page, and where a new one is saved. */
    public void setLiveHeight(int height, java.util.function.IntConsumer saver) {
        liveHeight = Math.max(150, height);
        liveHeightSaver = saver == null ? h -> { } : saver;
        placeDivider();
    }

    private void placeDivider() {
        if (liveSplit == null || liveSplit.getHeight() <= 0) {
            return;
        }
        // never so tall that the scenes disappear, never smaller than the monitors need
        int max = Math.max(150, liveSplit.getHeight() - liveSplit.getDividerSize() - 160);
        int h = Math.clamp(liveHeight, 150, max);
        placing = true;
        try {
            liveSplit.setDividerLocation(liveSplit.getHeight() - liveSplit.getDividerSize() - h);
        } finally {
            placing = false;
        }
    }

    /** A click on a tile only selects it; these buttons send the selected scene to Preview or Program. */
    private final SceneActionBar actions = new SceneActionBar(s -> host().sceneClicked(s), s -> host().sendToProgram(s),
            this::showEditor);

    private ScenesDock.Host host() {
        return host;
    }

    private void select(Scene s) {
        actions.setScene(s);
        grid.repaint();
        host().sceneSelected(s);
    }

    public void setScenes(List<Scene> scenes) {
        grid.removeAll();
        tiles.clear();
        Scene keep = null;
        for (Scene s : scenes) {
            Tile t = new Tile(s);
            SceneCardDrag.install(t, grid, c -> c instanceof Tile tile ? tile.scene : null, host::reorderScenes);
            tiles.add(t);
            grid.add(t);
            if (actions.scene() != null && Objects.equals(actions.scene().getId(), s.getId())) {
                keep = s;
            }
        }
        actions.setScene(keep);
        grid.revalidate();
        grid.repaint();
    }

    /** Repaints the PREVIEW / LIVE markers. */
    public void sync() {
        grid.repaint();
    }

    public void showHome() {
        editorShown = false;
        cards.show(body, HOME);
    }

    /** Opens one scene in the editor (from a card or Edit Scene). */
    public void showEditor(Scene scene) {
        if (!openEditor.test(scene)) {
            return; // another scene has unsaved changes and the operator stayed on it
        }
        editorShown = true;
        editorTitle.setText("SCENE: " + scene.getName().toUpperCase(Locale.ROOT));
        cards.show(body, EDITOR);
    }

    /** Asked before ALL SCENES closes the editor; false keeps it open. */
    public void setLeaveEditor(java.util.function.BooleanSupplier check) {
        leaveEditor = check == null ? () -> true : check;
    }

    private static String icon(Scene s) {
        String n = s.getName().toLowerCase(Locale.ROOT);
        if (n.contains("camera")) return "video";
        if (n.contains("bible")) return "book";
        if (n.contains("sermon") || n.contains("presentation")) return "presentation";
        if (n.contains("video")) return "video";
        if (n.contains("logo") || n.contains("welcome")) return "image";
        if (n.contains("blank")) return "monitor";
        if (n.contains("announce")) return "list";
        return s.has(SourceType.LYRICS) ? "music" : "grid";
    }

    /** A scene card: thumbnail, icon and NAME, "4 sources", and PREVIEW / LIVE in words. */
    private final class Tile extends JComponent {
        private final Scene scene;

        /** The 16:9 thumbnail at the column's width plus room for the name and "N sources" below it. */
        @Override
        public Dimension getPreferredSize() {
            int cols = ((GridLayout) grid.getLayout()).getColumns();
            int w = grid.getWidth() > 0 ? (grid.getWidth() - (cols - 1) * 12) / cols : TILE_W;
            int th = (int) Math.round((w - 16) * 9 / 16.0);
            return new Dimension(Math.max(w, TILE_W), Math.max(TILE_H, th + TILE_H - 115));
        }

        Tile(Scene scene) {
            this.scene = scene;
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            setToolTipText("<html><b>" + scene.getName() + "</b><br>Click: select (Preview and Program stay)<br>"
                    + "Double-click or PREVIEW: Send to Preview · LIVE: Take Scene Live · EDIT: its sources<br>"
                    + "Drag: change the order · Right-click: Display on Projector, Duplicate, Rename…</html>");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                        select(scene);
                        menu(e);
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        select(scene);
                        if (e.getClickCount() == 2) {
                            host.sceneClicked(scene); // double-click: Send to Preview
                        }
                    }
                }
            });
        }

        private void menu(MouseEvent e) {
            JPopupMenu m = new JPopupMenu();
            m.add(item("Send to Preview", "monitor", () -> host.sceneClicked(scene)));
            m.add(item("Take Scene Live", "play", () -> host.sendToProgram(scene)));
            m.add(ScenesDock.projectorMenu(host, scene));
            m.add(item("Add to Service", "list", () -> host.addToService(scene)));
            m.addSeparator();
            m.add(item("Edit Scene…", "layers", () -> showEditor(scene)));
            m.add(item("Duplicate", "copy", () -> host.duplicateScene(scene)));
            m.add(item("Rename…", "edit", () -> host.renameScene(scene)));
            m.add(item("Set as Default", "star", () -> host.setDefaultScene(scene)));
            m.addSeparator();
            JMenuItem del = item("Delete", "trash", () -> host.deleteScene(scene));
            m.add(del);
            m.show(this, e.getX(), e.getY());
        }

        private JMenuItem item(String text, String iconName, Runnable action) {
            JMenuItem i = new JMenuItem(text, Icons.get(iconName, Icons.SMALL));
            i.addActionListener(ev -> action.run());
            return i;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Scene live = host.programScene();
            Scene next = host.previewScene();
            boolean isLive = live != null && Objects.equals(live.getId(), scene.getId());
            boolean isNext = next != null && Objects.equals(next.getId(), scene.getId());
            int w = getWidth();
            int h = getHeight();
            g.setColor(Palette.secondaryBackground());
            g.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);

            int tw = w - 16;
            int th = (int) Math.round(tw * 9 / 16.0);
            Graphics2D t = (Graphics2D) g.create(8, 8, tw, th);
            compositor.paint(t, tw, th, new FrameSpec(sample, true, true, false, scene), null, false);
            t.dispose();

            g.setColor(isLive ? Palette.live() : isNext ? Palette.accent() : Palette.border());
            g.setStroke(new BasicStroke(isLive || isNext ? 3f : 1f));
            g.drawRoundRect(1, 1, w - 3, h - 3, 14, 14);
            if (actions.scene() != null && Objects.equals(actions.scene().getId(), scene.getId())) {
                // selected (not yet in Preview or Program): a second, inner ring in the text color
                int in = isLive || isNext ? 5 : 1;
                g.setColor(Palette.text());
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(in, in, w - 2 * in - 1, h - 2 * in - 1, 12, 12);
            }

            int y = th + 14;
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            FontMetrics fm = g.getFontMetrics();
            Icons.get(icon(scene), 15, () -> isLive ? Palette.live() : Palette.secondaryText())
                    .paintIcon(this, g, 10, y + (fm.getHeight() - 15) / 2);
            g.setColor(Palette.text());
            String name = scene.getName().toUpperCase(Locale.ROOT);
            while (fm.stringWidth(name) > w - 40 && name.length() > 3) {
                name = name.substring(0, name.length() - 2);
            }
            if (!name.equals(scene.getName().toUpperCase(Locale.ROOT))) {
                name = name + "…";
            }
            g.drawString(name, 32, y + fm.getAscent());
            int n = scene.getSources().size();
            g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            g.setColor(Palette.secondaryText());
            g.drawString(n + (n == 1 ? " source" : " sources"), 32, y + fm.getHeight() + g.getFontMetrics().getAscent());

            g.setFont(getFont().deriveFont(Font.BOLD, 9.5f));
            FontMetrics bm = g.getFontMetrics();
            int bx = 14;
            if (isLive) {
                bx = badge(g, bm, "● LIVE", Palette.live(), bx);
            }
            if (isNext) {
                badge(g, bm, "▶ PREVIEW", Palette.accent(), bx);
            }
            if (Objects.equals(host.defaultSceneId(), scene.getId())) {
                g.setFont(getFont().deriveFont(Font.BOLD, 13f));
                g.setColor(new Color(0xF59E0B));
                g.drawString("★", w - 24, y + fm.getAscent());
            }
            g.dispose();
        }

        private int badge(Graphics2D g, FontMetrics bm, String text, Color color, int x) {
            int bw = bm.stringWidth(text) + 12;
            g.setColor(color);
            g.fillRoundRect(x, 14, bw, bm.getHeight() + 4, 10, 10);
            g.setColor(Color.WHITE);
            g.drawString(text, x + 6, 16 + bm.getAscent());
            return x + bw + 4;
        }
    }

    /** Scroll view as wide as the page, so the card columns follow the window width. */
    private static final class WidthTracking extends JPanel implements Scrollable {
        WidthTracking() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
