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
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * SCENES: the saved output layouts (Worship, Camera, Camera + Lyrics, Bible...) as cards with a small
 * live thumbnail. A click puts a scene in Preview (Program changes on TAKE), or switches Program at
 * once in Direct mode. Layers are edited only through right-click, Edit Layout, so the normal screen
 * stays simple.
 */
public class ScenesDock extends JPanel {

    /** What the cards ask of the main window. */
    public interface Host {
        void sceneClicked(Scene scene);

        void sendToProgram(Scene scene);

        void editLayout(Scene scene);

        void addScene();

        void renameScene(Scene scene);

        void duplicateScene(Scene scene);

        void setDefaultScene(Scene scene);

        void deleteScene(Scene scene);

        Scene programScene();

        Scene previewScene();

        Long defaultSceneId();

        boolean separateLayouts();

        void setSeparateLayouts(boolean separate);

        /** A card was clicked (selected only): TAKE puts the selected scene live. */
        default void sceneSelected(Scene scene) {
        }

        /** Right-click → Add to Service: the scene becomes an item in the service schedule. */
        default void addToService(Scene scene) {
        }

        /** Cards were dragged into a new order: save it (Preview and Program do not change). */
        default void reorderScenes(List<Scene> order) {
        }

        /** Right-click → Display on Projector: this scene on Program (what the projector shows) at once. */
        default void showOnProjector(Scene scene) {
            sendToProgram(scene);
        }

        /** Projectors and TVs the scene can be sent to (the operator's own screen is left out). */
        default List<Screen> projectorScreens() {
            return List.of();
        }

        /** Display on Projector → one screen ({@link #ALL_SCREENS} = every projector and TV). */
        default void showOnProjector(Scene scene, String screenId) {
            showOnProjector(scene);
        }
    }

    /** A projector or TV in the Display on Projector menu; {@code showing} = it already shows Program. */
    public record Screen(String id, String label, boolean showing) {
    }

    public static final String ALL_SCREENS = "*";

    /**
     * Display on Projector: a submenu listing every connected projector and TV (even just one), with
     * "All Projectors / TVs" on top.
     */
    static JMenuItem projectorMenu(Host host, Scene scene) {
        List<Screen> screens = host.projectorScreens();
        JMenu menu = new JMenu("Display on Projector");
        menu.setIcon(Icons.get("monitor", Icons.SMALL));
        if (screens.isEmpty()) {
            JMenuItem none = new JMenuItem("No projector or TV connected");
            none.setEnabled(false);
            menu.add(none);
            JMenuItem window = new JMenuItem("Show in a Window");
            window.addActionListener(e -> host.showOnProjector(scene));
            menu.add(window);
            return menu;
        }
        JMenuItem all = new JMenuItem("All Projectors / TVs (" + screens.size() + ")");
        all.addActionListener(e -> host.showOnProjector(scene, ALL_SCREENS));
        menu.add(all);
        menu.addSeparator();
        for (Screen s : screens) {
            JMenuItem i = new JMenuItem(s.label() + (s.showing() ? "   ● ON" : ""));
            i.addActionListener(e -> host.showOnProjector(scene, s.id()));
            menu.add(i);
        }
        return menu;
    }

    private static final int THUMB_W = 100;
    // private static final int THUMB_H = 68;

    private final Host host;
    private final JPanel grid = Ui.transparent(new GridLayout(0, 2, 6, 6));
    private final JCheckBox separate = new JCheckBox("Separate Layouts");
    private final Compositor compositor = new Compositor(Rendering.renderer(), true);
    private final LiveContent sample = LiveContent.snapshot(new Slide("Sample", "Amazing grace\nHow sweet the sound", null),
            SlideStyle.fallback().withTextTransform(TextTransform.UPPERCASE), "Sample");

    public ScenesDock(Host host) {
        super(new BorderLayout(0, 6));
        this.host = host;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JButton add = Ui.button("Add Scene", "plus");
        add.setToolTipText("Create a scene: blank, a copy of the current one, or from a template");
        add.addActionListener(e -> host.addScene());
        JPanel head = Ui.transparent(new BorderLayout());
        head.add(Ui.label("SCENES", Palette::secondaryText, "semibold"), BorderLayout.WEST);
        head.add(add, BorderLayout.EAST);

        // the cards' height follows the column width (see SceneCard.getPreferredSize)
        grid.addComponentListener(new java.awt.event.ComponentAdapter() {
            private int laidOutWidth = -1;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (grid.getWidth() != laidOutWidth) {
                    laidOutWidth = grid.getWidth();
                    grid.revalidate();
                }
            }
        });
        JPanel holder = new WidthTrackingPanel();
        holder.add(grid, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(20);

        separate.setToolTipText("<html>For livestreams: Preview (e.g. the TV in church) keeps its own scene, such as Worship,<br>"
                + "while Program (Facebook) keeps another, such as Camera + Lyrics. The same lyrics go to both;<br>"
                + "TAKE changes only the content. Change Program's scene with TAKE SCENE.</html>");
        separate.addActionListener(e -> host.setSeparateLayouts(separate.isSelected()));

        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        JPanel south = Ui.transparent(new BorderLayout(0, 6));
        south.add(actions, BorderLayout.NORTH);
        south.add(separate, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        // camera thumbnails stay current
        new Timer(1500, e -> {
            if (isShowing()) {
                grid.repaint();
            }
        }).start();
    }

    /** A click on a card only selects it; these buttons send the selected scene to Preview or Program. */
    private final SceneActionBar actions = new SceneActionBar(s -> host().sceneClicked(s), s -> host().sendToProgram(s),
            s -> host().editLayout(s));

    private Host host() {
        return host;
    }

    /** Settings → Studio Mode: off, there is no Preview, so PREVIEW is hidden and a card click puts the scene live. */
    public void setStudioMode(boolean on) {
        studioMode = on;
        actions.setStudioMode(on);
        separate.setVisible(on); // a Preview layout of its own needs Preview on screen; the tick is kept meanwhile
        revalidate();
        repaint();
    }

    private boolean studioMode = true;

    private void select(Scene s) {
        actions.setScene(s);
        grid.repaint();
        host().sceneSelected(s);
    }

    public void setScenes(List<Scene> scenes) {
        grid.removeAll();
        Scene keep = null;
        for (Scene s : scenes) {
            SceneCard card = new SceneCard(s);
            SceneCardDrag.install(card, grid, c -> c instanceof SceneCard sc ? sc.scene : null, host::reorderScenes);
            grid.add(card);
            if (actions.scene() != null && Objects.equals(actions.scene().getId(), s.getId())) {
                keep = s; // the selection survives a reload (rename, edit, another scene deleted)
            }
        }
        actions.setScene(keep);
        grid.revalidate();
        grid.repaint();
        sync();
    }

    /** Repaints the LIVE / PREVIEW markers and the layout option. */
    public void sync() {
        separate.setSelected(host.separateLayouts());
        grid.repaint();
    }

    /** Scroll view that always fits the column width, so the two card columns never get cut off. */
    private static final class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {
        WidthTrackingPanel() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 20;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return r.height;
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

    private static String icon(Scene s) {
        String n = s.getName().toLowerCase(Locale.ROOT);
        if (n.contains("lyric") && n.contains("camera")) return "video";
        if (n.contains("camera")) return "video";
        if (n.contains("bible")) return "book";
        if (n.contains("sermon") || n.contains("presentation")) return "presentation";
        if (n.contains("video")) return "video";
        if (n.contains("logo") || n.contains("welcome")) return "image";
        if (n.contains("blank")) return "monitor";
        if (n.contains("announce")) return "list";
        return s.has(SourceType.LYRICS) ? "music" : "grid";
    }

    /**
     * One scene in the sidebar: a small live thumbnail, the name, and plain-text status labels
     * ("▶ PREVIEW", "● LIVE", both when Preview and Program use the same scene), so the state never
     * depends on color alone.
     */
    private final class SceneCard extends JComponent {
        private final Scene scene;

        SceneCard(Scene scene) {
            this.scene = scene;

            setToolTipText("<html><b>" + scene.getName() + "</b><br>Click: select (Preview and Program stay)<br>"
                    + "Double-click or PREVIEW: Send to Preview · LIVE: Take Scene Live<br>"
                    + "Drag: change the order · Right-click: Display on Projector, Edit Scene, Duplicate, Rename…</html>");
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

        /**
         * Tall enough for the 16:9 thumbnail at the column's width plus the name below it (a wide dock used
         * to push the name off the bottom of the card).
         */
        @Override
        public Dimension getPreferredSize() {
            int w = grid.getWidth() > 0 ? (grid.getWidth() - 6) / 2 : THUMB_W + 12;
            int th = (int) Math.round((w - 8) * 9 / 16.0);
            return new Dimension(w, th + 32);
        }

        private void menu(MouseEvent e) {
            JPopupMenu m = new JPopupMenu();
            m.add(item("Send to Preview", "monitor", () -> host.sceneClicked(scene)));
            m.add(item("Take Scene Live", "play", () -> host.sendToProgram(scene)));
            m.add(projectorMenu(host, scene));
            m.add(item("Add to Service", "list", () -> host.addToService(scene)));
            m.addSeparator();
            m.add(item("Edit Scene…", "layers", () -> host.editLayout(scene)));
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
            if (!studioMode && isLive) {
                isNext = false; // no Preview on screen: the live scene is only LIVE
            }
            int w = getWidth();
            int tw = w - 8;
            int th = (int) Math.round(tw * 9 / 16.0);

            g.setColor(Palette.secondaryBackground());
            g.fillRoundRect(0, 0, w - 1, getHeight() - 1, 10, 10);
            // live thumbnail of the layout with sample lyrics
            Graphics2D t = (Graphics2D) g.create(4, 4, tw, th);
            compositor.paint(t, tw, th, new FrameSpec(sample, true, true, false, scene), null, false);
            t.dispose();

            // Studio Mode off: the scene waiting for the TAKE key is white (not live yet), not Preview blue
            Color edge = isLive ? Palette.live() : isNext ? (studioMode ? Palette.accent() : Palette.text()) : Palette.border();
            g.setColor(edge);
            g.setStroke(new BasicStroke(isLive || isNext ? 2.5f : 1f));
            g.drawRoundRect(1, 1, w - 3, getHeight() - 3, 10, 10);
            boolean isSelected = actions.scene() != null && Objects.equals(actions.scene().getId(), scene.getId());
            if (isSelected) {
                // selected (not yet in Preview or Program): a second, inner ring in the text color
                g.setColor(Palette.text());
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(isLive || isNext ? 4 : 1, isLive || isNext ? 4 : 1,
                        w - (isLive || isNext ? 9 : 3), getHeight() - (isLive || isNext ? 9 : 3), 8, 8);
            }

            int y = th + 8;
            g.setFont(getFont().deriveFont(Font.BOLD, 11.5f));
            FontMetrics fm = g.getFontMetrics();
            var ic = Icons.get(icon(scene), 13, () -> isLive ? Palette.live() : Palette.secondaryText());
            ic.paintIcon(this, g, 6, y + (fm.getHeight() - 13) / 2);
            g.setColor(Palette.text());
            String name = scene.getName();
            int maxW = w - 30;
            while (fm.stringWidth(name) > maxW && name.length() > 3) {
                name = name.substring(0, name.length() - 2);
            }
            if (!name.equals(scene.getName())) {
                name = name + "…";
            }
            g.drawString(name, 24, y + fm.getAscent());

            // status in words as well as color; both when Preview and Program use the same scene
            g.setFont(getFont().deriveFont(Font.BOLD, 9f));
            FontMetrics bm = g.getFontMetrics();
            int bx = 8;
            if (isLive) {
                bx = badge(g, bm, "● LIVE", Palette.live(), bx, 8);
            }
            if (isNext && studioMode) {
                badge(g, bm, "▶ PREVIEW", Palette.accent(), bx, 8);
            } else if (isNext) {
                badge(g, bm, "▶ NEXT · TAKE KEY", Color.WHITE, new Color(0x0F172A), bx, 8);
            }
            if (Objects.equals(host.defaultSceneId(), scene.getId())) {
                g.setColor(new Color(0xF59E0B));
                g.drawString("★", tw - 6, 9 + bm.getAscent() + 2);
            }
            g.dispose();
        }

        private int badge(Graphics2D g, FontMetrics bm, String text, Color color, int x, int y) {
            return badge(g, bm, text, color, Color.WHITE, x, y);
        }

        private int badge(Graphics2D g, FontMetrics bm, String text, Color color, Color textColor, int x, int y) {
            int bw = bm.stringWidth(text) + 10;
            g.setColor(color);
            g.fillRoundRect(x, y, bw, bm.getHeight() + 2, 8, 8);
            g.setColor(textColor);
            g.drawString(text, x + 5, y + 1 + bm.getAscent());
            return x + bw + 4;
        }
    }
}
