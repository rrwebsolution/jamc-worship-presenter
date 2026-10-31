package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.Theme;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.service.ThemeService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.dialogs.ThemeEditorDialog;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Theme list with rendered previews and theme management actions. */
public class ThemeLibraryPanel extends JPanel {

    /** Selected theme plus whether it is the default. */
    public record Selection(Theme theme, boolean isDefault) {
    }

    static final Slide SAMPLE = new Slide("Sample", "Amazing grace!\nHow sweet the sound", null);

    private final ThemeService themeService;
    private final StyleForm.ImageImporter importer;
    private final DefaultListModel<Theme> model = new DefaultListModel<>();
    private final JList<Theme> list = new JList<>(model);
    private final JLabel countLabel = Ui.small(" ");
    private final JButton editButton = Ui.toolButton("edit", "Edit theme (Enter)");
    private final JButton duplicateButton = Ui.toolButton("copy", "Duplicate theme");
    private final JButton deleteButton = Ui.toolButton("trash", "Delete theme (Delete)");
    private final JButton defaultButton = Ui.button("Set as Default", "star");
    private final List<Consumer<Selection>> selectionListeners = new ArrayList<>();
    private final List<Runnable> themesChangedListeners = new ArrayList<>();
    private Consumer<String> statusHandler = s -> { };
    private Long defaultThemeId;
    private long loadSeq;

    public ThemeLibraryPanel(ThemeService themeService, StyleForm.ImageImporter importer) {
        super(new BorderLayout());
        this.themeService = themeService;
        this.importer = importer;
        setOpaque(false);

        JButton addButton = Ui.toolButton("plus", "New theme");
        addButton.addActionListener(e -> createTheme());
        add(Ui.header(Ui.label("Themes", Palette::text, "h3"), countLabel, addButton), BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ThemeCellRenderer());
        list.setFixedCellHeight(78);
        list.setOpaque(false);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
                fireSelection();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    editSelected();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "edit", this::editSelected, WHEN_FOCUSED);
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelected, WHEN_FOCUSED);
        Rendering.renderer().images().addChangeListener(list::repaint);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(Ui.line(true, false, false, false));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        editButton.addActionListener(e -> editSelected());
        duplicateButton.addActionListener(e -> duplicateSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        defaultButton.addActionListener(e -> setDefaultSelected());
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(editButton);
        left.add(duplicateButton);
        left.add(deleteButton);
        JPanel bar = Ui.transparent(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 6, 6, 8)));
        bar.add(left, BorderLayout.WEST);
        bar.add(defaultButton, BorderLayout.EAST);
        add(bar, BorderLayout.SOUTH);
        updateButtons();
    }

    public void addSelectionListener(Consumer<Selection> listener) {
        selectionListeners.add(listener);
    }

    /** Called after any theme is created, changed or deleted, so open songs and decks can restyle. */
    public void addThemesChangedListener(Runnable listener) {
        themesChangedListeners.add(listener);
    }

    public void setStatusHandler(Consumer<String> statusHandler) {
        this.statusHandler = statusHandler;
    }

    public Theme selectedTheme() {
        return list.getSelectedValue();
    }

    public void refresh(Long selectId) {
        long seq = ++loadSeq;
        Long keep = selectId != null ? selectId : selectedId();
        BackgroundTask.run("Load themes", () -> new Object[]{themeService.list(), themeService.defaultThemeId()}, r -> {
            if (seq != loadSeq) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Theme> themes = (List<Theme>) r[0];
            defaultThemeId = (Long) r[1];
            model.clear();
            model.addAll(themes);
            countLabel.setText(themes.size() + (themes.size() == 1 ? " theme" : " themes"));
            int index = 0;
            for (int i = 0; i < themes.size(); i++) {
                if (Objects.equals(themes.get(i).getId(), keep)) {
                    index = i;
                }
            }
            if (!themes.isEmpty()) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
            }
            updateButtons();
            fireSelection();
        }, err -> Dialogs.showError(this, "Could not load themes.", err));
    }

    public void createTheme() {
        Theme base = list.getSelectedValue();
        BackgroundTask.run("Suggest theme name", () -> themeService.uniqueName("New Theme"), name -> {
            var saved = ThemeEditorDialog.create(SwingUtilities.getWindowAncestor(this), themeService, name,
                    base == null ? com.jamc.worship.model.SlideStyle.fallback() : base.getStyle(), importer);
            saved.ifPresent(t -> afterChange("Created theme \"" + t.getName() + "\"", t.getId()));
        }, err -> Dialogs.showError(this, "Could not create a theme.", err));
    }

    public void editSelected() {
        Theme theme = list.getSelectedValue();
        if (theme == null) {
            return;
        }
        ThemeEditorDialog.edit(SwingUtilities.getWindowAncestor(this), themeService, theme, importer)
                .ifPresent(t -> afterChange("Saved theme \"" + t.getName() + "\"", t.getId()));
    }

    public void duplicateSelected() {
        Theme theme = list.getSelectedValue();
        if (theme == null) {
            return;
        }
        BackgroundTask.run("Duplicate theme", () -> themeService.duplicate(theme.getId()),
                t -> afterChange("Duplicated as \"" + t.getName() + "\"", t.getId()),
                err -> Dialogs.showError(this, "Could not duplicate the theme.", err));
    }

    public void deleteSelected() {
        Theme theme = list.getSelectedValue();
        if (theme == null) {
            return;
        }
        BackgroundTask.run("Count theme usage", () -> themeService.usageCount(theme.getId()), uses -> {
            String usage = uses == 0 ? "" : "\n\n" + uses + (uses == 1 ? " song or deck uses" : " songs and decks use")
                    + " this theme. They will switch to the default theme.";
            if (!Dialogs.confirmDestructive(this, "Delete theme", "Delete the theme \"" + theme.getName() + "\"?"
                    + usage + "\n\nThis cannot be undone.", "Delete")) {
                return;
            }
            BackgroundTask.run("Delete theme", () -> {
                themeService.delete(theme.getId());
                return true;
            }, ok -> afterChange("Deleted theme \"" + theme.getName() + "\"", null),
                    err -> Dialogs.showError(this, "Could not delete the theme.", err));
        }, err -> Dialogs.showError(this, "Could not delete the theme.", err));
    }

    public void setDefaultSelected() {
        Theme theme = list.getSelectedValue();
        if (theme == null) {
            return;
        }
        BackgroundTask.run("Set default theme", () -> {
            themeService.setDefault(theme.getId());
            return true;
        }, ok -> afterChange("\"" + theme.getName() + "\" is now the default theme", theme.getId()),
                err -> Dialogs.showError(this, "Could not change the default theme.", err));
    }

    private void afterChange(String status, Long selectId) {
        statusHandler.accept(status);
        refresh(selectId);
        themesChangedListeners.forEach(Runnable::run);
    }

    private void fireSelection() {
        Theme t = list.getSelectedValue();
        Selection s = t == null ? null : new Selection(t, Objects.equals(t.getId(), defaultThemeId));
        selectionListeners.forEach(l -> l.accept(s));
    }

    private void updateButtons() {
        Theme t = list.getSelectedValue();
        boolean has = t != null;
        boolean isDefault = has && Objects.equals(t.getId(), defaultThemeId);
        Ui.setEnabled(has, editButton, duplicateButton);
        deleteButton.setEnabled(has && !t.isBuiltin() && !isDefault);
        deleteButton.setToolTipText(!has ? "Delete theme" : t.isBuiltin() ? "Built-in themes cannot be deleted"
                : isDefault ? "The default theme cannot be deleted" : "Delete theme (Delete)");
        defaultButton.setEnabled(has && !isDefault);
        defaultButton.setText(isDefault ? "Default Theme" : "Set as Default");
    }

    private Long selectedId() {
        Theme t = list.getSelectedValue();
        return t == null ? null : t.getId();
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = list.locationToIndex(e.getPoint());
        if (row < 0 || !list.getCellBounds(row, row).contains(e.getPoint())) {
            return;
        }
        list.setSelectedIndex(row);
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Edit…", "edit", this::editSelected, true));
        menu.add(item("Duplicate", "copy", this::duplicateSelected, true));
        menu.add(item("Set as Default", "star", this::setDefaultSelected, defaultButton.isEnabled()));
        menu.addSeparator();
        menu.add(item("Delete…", "trash", this::deleteSelected, deleteButton.isEnabled()));
        menu.show(list, e.getX(), e.getY());
    }

    private static JMenuItem item(String text, String icon, Runnable action, boolean enabled) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        i.addActionListener(e -> action.run());
        i.setEnabled(enabled);
        return i;
    }

    /** Row with a small rendered sample, theme name and badges. */
    private final class ThemeCellRenderer extends JPanel implements ListCellRenderer<Theme> {
        private final JLabel name = new JLabel();
        private final JLabel badges = new JLabel();
        private Theme theme;

        ThemeCellRenderer() {
            super(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
            JComponent thumb = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    if (theme == null) {
                        return;
                    }
                    Graphics2D g2 = (Graphics2D) g.create();
                    SlideRenderer.applyQualityHints(g2);
                    Rendering.renderer().paint(g2, new Rectangle(0, 0, getWidth(), getHeight()), SAMPLE, theme.getStyle());
                    g2.setColor(Palette.border());
                    g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                    g2.dispose();
                }
            };
            thumb.setPreferredSize(new Dimension(112, 63));
            name.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
            badges.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            JPanel text = new JPanel(new BorderLayout(0, 3));
            text.setOpaque(false);
            text.add(name, BorderLayout.NORTH);
            text.add(badges, BorderLayout.CENTER);
            add(thumb, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Theme> l, Theme t, int index, boolean selected,
                                                      boolean focused) {
            theme = t;
            name.setText(t.getName());
            List<String> tags = new ArrayList<>();
            if (Objects.equals(t.getId(), defaultThemeId)) {
                tags.add("★ Default");
            }
            if (t.isBuiltin()) {
                tags.add("Built-in");
            }
            tags.add(t.getStyle().fontFamily() + " " + t.getStyle().fontSize());
            badges.setText(String.join("  ·  ", tags));
            Color fg = selected ? l.getSelectionForeground() : Palette.text();
            name.setForeground(fg);
            badges.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            setOpaque(selected);
            return this;
        }
    }
}
