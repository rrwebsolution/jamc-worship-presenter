package com.jamc.worship.ui.components;

import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Left navigation: library categories and service playlists. */
public class Sidebar extends JPanel {

    public enum Category {
        // the Library: reusable presentation content
        SONGS("Lyrics", "music", true),
        BIBLE("Bible", "book", true),
        PRESENTATIONS("Presentations", "presentation", true),
        IMAGES("Images", "image", true),
        VIDEOS("Videos", "video", true),
        AUDIO("Audio", "audio", true),
        CAMERA("Camera", "video", true),
        // "More": tools a volunteer rarely needs during a service
        SLIDES("Custom Slides", "slides", false),
        THEMES("Themes", "palette", false),
        LOWER_THIRDS("Lower Thirds", "lower-third", false),
        FAVORITES("Favorite Lyrics", "star", false),
        MEDIA("All Media", "folder", false);

        private final String title;
        private final String icon;
        private final boolean library;

        Category(String title, String icon, boolean library) {
            this.title = title;
            this.icon = icon;
            this.library = library;
        }

        public String title() { return title; }
        public String icon() { return icon; }
        /** True for the main Library list, false for the "More" list. */
        public boolean library() { return library; }
    }

    public static final List<String> PLAYLISTS = List.of("Sunday Service", "Midweek", "Youth Service", "Special Events");

    private final JList<Category> categories = new JList<>(java.util.Arrays.stream(Category.values()).filter(Category::library).toArray(Category[]::new));
    private final JList<Category> more = new JList<>(java.util.Arrays.stream(Category.values()).filter(c -> !c.library()).toArray(Category[]::new));
    private final JList<String> playlists = new JList<>(PLAYLISTS.toArray(String[]::new));
    private final List<Consumer<Category>> categoryListeners = new ArrayList<>();
    private final List<Consumer<String>> playlistListeners = new ArrayList<>();
    private final List<Consumer<Category>> reselectListeners = new ArrayList<>();
    private boolean quiet;

    public Sidebar() {
        super(new BorderLayout());
        setOpaque(false);
        setMinimumSize(new Dimension(150, 0));

        JPanel content = Ui.transparent(null);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

        content.add(captionRow("Library"));
        configureList(categories, true);
        categories.setCellRenderer(new NavRenderer<>(c -> c.icon(), Category::title, c -> false));
        content.add(categories);

        content.add(javax.swing.Box.createVerticalStrut(14));
        content.add(captionRow("More"));
        configureList(more, false);
        more.setCellRenderer(new NavRenderer<>(c -> c.icon(), Category::title, c -> true));
        content.add(more);

        content.add(javax.swing.Box.createVerticalStrut(14));
        content.add(captionRow("Services"));
        configureList(playlists, false);
        playlists.setCellRenderer(new NavRenderer<String>(p -> "calendar", p -> p, p -> false));
        content.add(playlists);
        content.add(javax.swing.Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        for (JList<Category> list : List.of(categories, more)) {
            JList<Category> other = list == categories ? more : categories;
            list.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                    other.clearSelection();
                    if (!quiet) {
                        categoryListeners.forEach(l -> l.accept(list.getSelectedValue()));
                    }
                }
            });
            // a click on the category that is already selected changes no selection: report it separately
            list.addMouseListener(new java.awt.event.MouseAdapter() {
                private int pressedSelected = -1;

                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    int i = list.locationToIndex(e.getPoint());
                    pressedSelected = i >= 0 && list.isSelectedIndex(i) ? i : -1;
                }

                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    int i = list.locationToIndex(e.getPoint());
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e) && i >= 0 && i == pressedSelected) {
                        Category c = list.getModel().getElementAt(i);
                        reselectListeners.forEach(l -> l.accept(c));
                    }
                }
            });
        }
        playlists.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && playlists.getSelectedValue() != null) {
                playlistListeners.forEach(l -> l.accept(playlists.getSelectedValue()));
            }
        });
    }

    public void addCategoryListener(Consumer<Category> listener) {
        categoryListeners.add(listener);
    }

    /** Called when the operator clicks the category that is already selected. */
    public void addCategoryReselectListener(Consumer<Category> listener) {
        reselectListeners.add(listener);
    }

    public void addPlaylistListener(Consumer<String> listener) {
        playlistListeners.add(listener);
    }

    public void selectCategory(Category category) {
        (category.library() ? categories : more).setSelectedValue(category, true);
    }

    /** Marks this category as selected without telling the listeners (the caller shows its library itself). */
    public void selectCategoryQuietly(Category category) {
        quiet = true;
        try {
            selectCategory(category);
        } finally {
            quiet = false;
        }
    }

    public Category selectedCategory() {
        return categories.getSelectedValue() != null ? categories.getSelectedValue() : more.getSelectedValue();
    }

    public void selectPlaylist(String playlist) {
        playlists.setSelectedValue(playlist, true);
    }

    private static JComponent captionRow(String text) {
        JPanel row = Ui.transparent(new BorderLayout());
        row.setBorder(BorderFactory.createEmptyBorder(4, 16, 6, 8));
        row.add(Ui.caption(text), BorderLayout.WEST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return row;
    }

    private static void configureList(JList<?> list, boolean primary) {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        list.setFixedCellHeight(primary ? 32 : 30);
        list.setAlignmentX(LEFT_ALIGNMENT);
        list.setVisibleRowCount(list.getModel().getSize());
        list.setMaximumSize(new Dimension(Integer.MAX_VALUE, list.getPreferredSize().height));
    }

    private static final class NavRenderer<T> extends DefaultListCellRenderer {
        private final java.util.function.Function<T, String> icon;
        private final java.util.function.Function<T, String> text;
        private final java.util.function.Predicate<T> upcoming;

        NavRenderer(java.util.function.Function<T, String> icon, java.util.function.Function<T, String> text,
                    java.util.function.Predicate<T> upcoming) {
            this.icon = icon;
            this.text = text;
            this.upcoming = upcoming;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, false);
            T item = (T) value;
            l.setText(text.apply(item));
            Color iconColor = selected ? Color.WHITE : (upcoming.test(item) ? Palette.mutedText() : Palette.secondaryText());
            l.setIcon(Icons.get(icon.apply(item), Icons.MEDIUM, () -> iconColor));
            l.setIconTextGap(10);
            l.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 8));
            l.setOpaque(false); // the selected item's background is painted below (rounded, accent color)
            this.selected = selected;
            if (selected) {
                l.setForeground(Color.WHITE);
                l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
            } else {
                l.setForeground(upcoming.test(item) ? Palette.secondaryText() : Palette.text());
            }
            return l;
        }

        private boolean selected;

        /** The clicked item stands out: a rounded accent background, as in the top navigation. */
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            if (selected) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Palette.accent());
                g2.fillRoundRect(6, 2, getWidth() - 12, getHeight() - 4, 8, 8);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
