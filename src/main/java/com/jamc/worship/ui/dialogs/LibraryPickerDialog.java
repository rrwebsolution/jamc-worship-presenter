package com.jamc.worship.ui.dialogs;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Function;

/**
 * Picks something that is already in the app's library (a presentation, image or video) so a scene
 * source can reference it by id: nothing is imported or copied again.
 *
 * @param <T> library item type
 */
public final class LibraryPickerDialog<T> extends JDialog {

    /** How the dialog shows and finds items. */
    public record Spec<T>(String title, String searchHint, String icon, String emptyText,
                          Function<String, List<T>> search, Function<T, String> name, Function<T, String> detail,
                          Function<T, BufferedImage> thumbnail, Uploader<T> upload) {

        /** A picker without UPLOAD FILE… (songs, cameras, windows…). */
        public Spec(String title, String searchHint, String icon, String emptyText,
                    Function<String, List<T>> search, Function<T, String> name, Function<T, String> detail,
                    Function<T, BufferedImage> thumbnail) {
            this(title, searchHint, icon, emptyText, search, name, detail, thumbnail, null);
        }

        /** The same picker with UPLOAD FILE… (the uploaded files go to the Library, then are chosen here). */
        public Spec<T> withUpload(Uploader<T> uploader) {
            return new Spec<>(title, searchHint, icon, emptyText, search, name, detail, thumbnail, uploader);
        }
    }

    /**
     * UPLOAD FILE…: picks files on the computer, saves them in the Library, then hands the new library items
     * to {@code done} on the event thread; always called once, with an empty list when cancelled or nothing was added.
     */
    public interface Uploader<T> {
        void upload(Component parent, boolean many, java.util.function.Consumer<List<T>> done);
    }

    private final Spec<T> spec;
    private final DefaultListModel<T> model = new DefaultListModel<>();
    private final JList<T> list = new JList<>(model);
    private final JTextField search = new JTextField();
    private final JButton add = Ui.primaryButton("ADD TO SCENE", "plus");
    private final CardLayout cards = new CardLayout();
    private final JPanel holder = Ui.transparent(cards);
    private final JLabel empty = Ui.label("", Palette::mutedText, null);
    /** Edit Videos only: the checked videos in their actual playback order. */
    private final DefaultListModel<T> sequenceModel = new DefaultListModel<>();
    private final JList<T> sequenceList = new JList<>(sequenceModel);
    private final JPanel sequencePanel = Ui.panel(new BorderLayout(0, 8), Palette::secondaryBackground);
    private final JButton sequenceUp = Ui.toolButton("arrow-up", "Play the selected video earlier");
    private final JButton sequenceDown = Ui.toolButton("arrow-down", "Play the selected video later");
    private T chosen;
    /** Multi-select (IMAGE SLIDESHOW): a click ticks or unticks an item; ADD N … returns them all. */
    private final boolean multi;
    private List<T> chosenMany = List.of();
    private long seq;

    private LibraryPickerDialog(Window owner, Spec<T> spec, boolean multi) {
        super(owner, spec.title(), ModalityType.APPLICATION_MODAL);
        this.spec = spec;
        this.multi = multi;
        JPanel root = Ui.panel(new BorderLayout(0, 10), Palette::panelBackground);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel heading = Ui.label(spec.title().toUpperCase(), Palette::text, "h3");
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, spec.searchHint());
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("search", Icons.SMALL));
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        JPanel top = Ui.transparent(new BorderLayout(0, 8));
        top.add(heading, BorderLayout.NORTH);
        top.add(search, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        if (multi) {
            // a click ticks or unticks one item (no Ctrl needed); Shift still selects a range
            list.setSelectionModel(new javax.swing.DefaultListSelectionModel() {
                @Override
                public void setSelectionInterval(int a, int b) {
                    if (a == b && isSelectedIndex(a)) {
                        removeSelectionInterval(a, b);
                    } else {
                        addSelectionInterval(a, b);
                    }
                }
            });
        } else {
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        }
        list.setCellRenderer(new Row());
        list.setFixedCellHeight(64);
        list.addListSelectionListener(e -> updateAddButton());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!multi && e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    accept();
                }
            }
        });
        Rendering.thumbnails().addChangeListener(list::repaint);
        Rendering.renderer().images().addChangeListener(list::repaint);
        // video stills and YouTube pictures arrive later: the rows show them once ready (removed when it closes)
        Runnable repaintRows = list::repaint;
        com.jamc.worship.media.VideoStills.addListener(repaintRows);
        com.jamc.worship.youtube.YouTubeLibrary.addListener(repaintRows);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                com.jamc.worship.media.VideoStills.removeListener(repaintRows);
                com.jamc.worship.youtube.YouTubeLibrary.removeListener(repaintRows);
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(Ui.line(true, true, true, true));
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        holder.add(scroll, "list");
        holder.add(empty, "empty");
        root.add(holder, BorderLayout.CENTER);

        if (multi) {
            buildSequencePanel();
            sequencePanel.setVisible(false); // enabled by pickMany(..., "videos", ...), not for image pickers
            root.add(sequencePanel, BorderLayout.EAST);
        }

        JButton cancel = Ui.button("CANCEL", null);
        cancel.addActionListener(e -> dispose());
        add.addActionListener(e -> accept());
        add.setEnabled(false);
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(add);
        JPanel south = Ui.transparent(new BorderLayout());
        south.add(buttons, BorderLayout.EAST);
        if (spec.upload() != null) {
            // UPLOAD FILE…: straight from the computer; saved in the Library and chosen here, no trip to the Library
            JButton upload = Ui.button(multi ? "UPLOAD FILES…" : "UPLOAD FILE…", "import");
            upload.setToolTipText("Choose " + (multi ? "files" : "a file") + " on this computer. "
                    + (multi ? "They are" : "It is") + " saved in the Library by itself and added here.");
            upload.addActionListener(e -> {
                upload.setEnabled(false);
                upload.setText("UPLOADING…"); // a PowerPoint's slides are rendered first: this can take a moment
                spec.upload().upload(this, multi, items -> {
                    upload.setEnabled(true);
                    upload.setText(multi ? "UPLOAD FILES…" : "UPLOAD FILE…");
                    uploaded(items);
                });
            });
            JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
            left.add(upload);
            south.add(left, BorderLayout.WEST);
        }
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(add);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        Timer debounce = new Timer(200, e -> load());
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        setSize(520, 520);
        setLocationRelativeTo(owner);
        load();
    }

    /** Shows the picker and returns the chosen library item, or null when cancelled. */
    public static <T> T pick(Component parent, Spec<T> spec) {
        LibraryPickerDialog<T> d = new LibraryPickerDialog<>(SwingUtilities.getWindowAncestor(parent), spec, false);
        com.jamc.worship.ui.SoftModal.showAndWait(d); // minimizing it lets the app be used meanwhile
        return d.chosen;
    }

    /**
     * Shows the picker with ticks: the operator ticks any number of items (also across searches) and
     * ADD N … returns them in the order they were ticked; empty when cancelled.
     */
    public static <T> List<T> pickMany(Component parent, Spec<T> spec, String noun) {
        return pickMany(parent, spec, noun, List.of());
    }

    /**
     * As {@link #pickMany(Component, Spec, String)}, with {@code already} ticked when it opens (e.g. the videos already
     * in a playlist): untick to remove, tick others to add. The result keeps their order, new ticks after them.
     */
    public static <T> List<T> pickMany(Component parent, Spec<T> spec, String noun, List<T> already) {
        return pickMany(parent, spec, noun, already, false, false);
    }

    /** Scene editor variant: the primary action reflects whether the source is new or already exists. */
    public static <T> List<T> pickManyForScene(Component parent, Spec<T> spec, String noun, List<T> already,
                                                boolean sourceExists) {
        return pickMany(parent, spec, noun, already, true, sourceExists);
    }

    private static <T> List<T> pickMany(Component parent, Spec<T> spec, String noun, List<T> already,
                                         boolean sceneSourceAction, boolean sourceExists) {
        LibraryPickerDialog<T> d = new LibraryPickerDialog<>(SwingUtilities.getWindowAncestor(parent), spec, true);
        d.ticked.addAll(already);
        d.noun = noun;
        d.sceneSourceAction = sceneSourceAction;
        d.updatingSceneSource = sourceExists;
        d.enableSequence("videos".equalsIgnoreCase(noun));
        d.updateAddButton();
        com.jamc.worship.ui.SoftModal.showAndWait(d); // minimizing it lets the app be used meanwhile
        return d.chosenMany;
    }

    /** Ticked items in tick order (kept while the search changes). */
    private final java.util.LinkedHashSet<T> ticked = new java.util.LinkedHashSet<>();
    private boolean restoring;
    private String noun = "items";
    private boolean sceneSourceAction;
    private boolean updatingSceneSource;

    /** Adds the Edit Videos play-order column without changing other multi-pickers. */
    private void enableSequence(boolean enabled) {
        sequencePanel.setVisible(enabled);
        if (enabled) {
            setSize(820, 560);
            setLocationRelativeTo(getOwner());
            syncSequence();
        }
    }

    private void buildSequencePanel() {
        sequencePanel.setPreferredSize(new Dimension(270, 10));
        sequencePanel.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, true, true, true),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        JPanel heading = Ui.transparent(new BorderLayout(6, 2));
        heading.add(Ui.label("PLAY ORDER", Palette::text, "semibold"), BorderLayout.NORTH);
        heading.add(Ui.small("First to last"), BorderLayout.SOUTH);
        sequencePanel.add(heading, BorderLayout.NORTH);

        sequenceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sequenceList.setFixedCellHeight(42);
        sequenceList.setCellRenderer((l, item, index, selected, focus) -> {
            JLabel label = Ui.label((index + 1) + ".  " + spec.name().apply(item),
                    selected ? () -> l.getSelectionForeground() : Palette::text, "semibold");
            label.setOpaque(true);
            label.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            label.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            return label;
        });
        sequenceList.addListSelectionListener(e -> updateSequenceButtons());
        JScrollPane sequenceScroll = new JScrollPane(sequenceList);
        sequenceScroll.setBorder(Ui.line(true, true, true, true));
        sequencePanel.add(sequenceScroll, BorderLayout.CENTER);

        sequenceUp.addActionListener(e -> moveSequence(-1));
        sequenceDown.addActionListener(e -> moveSequence(1));
        JPanel controls = Ui.transparent(new FlowLayout(FlowLayout.CENTER, 6, 0));
        controls.add(sequenceUp);
        controls.add(sequenceDown);
        sequencePanel.add(controls, BorderLayout.SOUTH);
        updateSequenceButtons();
    }

    private void syncSequence() {
        T selected = sequenceList.getSelectedValue();
        sequenceModel.clear();
        sequenceModel.addAll(ticked);
        if (selected != null) {
            sequenceList.setSelectedValue(selected, true);
        }
        updateSequenceButtons();
    }

    /** Reorders the LinkedHashSet so accept() returns the exact visible play sequence. */
    private void moveSequence(int delta) {
        int from = sequenceList.getSelectedIndex();
        int to = from + delta;
        if (from < 0 || to < 0 || to >= sequenceModel.size()) {
            return;
        }
        java.util.ArrayList<T> ordered = new java.util.ArrayList<>(ticked);
        java.util.Collections.swap(ordered, from, to);
        ticked.clear();
        ticked.addAll(ordered);
        syncSequence();
        sequenceList.setSelectedIndex(to);
    }

    private void updateSequenceButtons() {
        int index = sequenceList.getSelectedIndex();
        sequenceUp.setEnabled(index > 0);
        sequenceDown.setEnabled(index >= 0 && index < sequenceModel.size() - 1);
    }

    private void updateAddButton() {
        if (!multi) {
            add.setEnabled(list.getSelectedValue() != null);
            return;
        }
        if (!restoring) {
            for (int i = 0; i < model.size(); i++) {
                if (list.isSelectedIndex(i)) {
                    ticked.add(model.get(i));
                } else {
                    ticked.remove(model.get(i));
                }
            }
        }
        if (sceneSourceAction) {
            add.setText(updatingSceneSource ? "UPDATE SCENE" : "ADD TO SCENE");
        } else {
            add.setText(ticked.isEmpty() ? "ADD " + noun.toUpperCase() : "ADD " + ticked.size() + " "
                    + (ticked.size() == 1 ? noun.substring(0, noun.length() - 1) : noun).toUpperCase());
        }
        add.setEnabled(!ticked.isEmpty());
        if (sequencePanel.isVisible()) {
            syncSequence();
        }
    }

    private void accept() {
        if (multi) {
            chosenMany = List.copyOf(ticked);
            if (!chosenMany.isEmpty()) {
                dispose();
            }
            return;
        }
        chosen = list.getSelectedValue();
        if (chosen != null) {
            dispose();
        }
    }

    /**
     * Files were uploaded into the Library: one file is chosen at once (ADD TO SCENE); several are ticked and the
     * list shows them, so ADD N … adds them with any others ticked.
     */
    private void uploaded(List<T> items) {
        if (!isDisplayable() || items.isEmpty()) {
            return; // the picker was closed meanwhile
        }
        if (!multi) {
            chosen = items.getFirst();
            dispose();
            return;
        }
        ticked.addAll(items);
        search.setText(""); // the new files show in the full list
        load();
    }

    private void load() {
        long s = ++seq;
        String text = search.getText();
        BackgroundTask.run("Search library", () -> spec.search().apply(text), items -> {
            if (s != seq) {
                return;
            }
            restoring = true; // a new search keeps what was ticked before
            try {
                model.clear();
                model.addAll(items);
                if (multi) {
                    for (int i = 0; i < model.size(); i++) {
                        if (ticked.contains(model.get(i))) {
                            list.addSelectionInterval(i, i);
                        }
                    }
                } else if (!items.isEmpty()) {
                    list.setSelectedIndex(0);
                }
            } finally {
                restoring = false;
            }
            updateAddButton();
            empty.setText("<html><center>" + (text.isBlank() ? spec.emptyText() : "Nothing matches your search.") + "</center></html>");
            cards.show(holder, items.isEmpty() ? "empty" : "list");
        }, err -> Dialogs.showError(this, "Could not search the library.", err));
    }

    /** Thumbnail, name and detail ("24 slides", "1920×1080"). */
    private final class Row extends JPanel implements ListCellRenderer<T> {
        private final JLabel name = Ui.label(" ", Palette::text, "semibold");
        private final JLabel detail = Ui.small(" ");
        /** Multi-select: shows whether the item is ticked. */
        private final javax.swing.JCheckBox tick = new javax.swing.JCheckBox();
        private BufferedImage thumb;

        Row() {
            super(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            JComponent picture = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.setColor(Color.BLACK);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    if (thumb != null) {
                        double s = Math.min(getWidth() / (double) thumb.getWidth(), getHeight() / (double) thumb.getHeight());
                        int w = (int) (thumb.getWidth() * s);
                        int h = (int) (thumb.getHeight() * s);
                        g2.drawImage(thumb, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
                    } else {
                        Icons.get(spec.icon(), 20, Palette::secondaryText).paintIcon(this, g2, (getWidth() - 20) / 2, (getHeight() - 20) / 2);
                    }
                    g2.dispose();
                }
            };
            picture.setPreferredSize(new Dimension(92, 52));
            JPanel text = Ui.transparent(new BorderLayout(0, 2));
            text.add(name, BorderLayout.NORTH);
            text.add(detail, BorderLayout.SOUTH);
            JPanel west = Ui.transparent(new BorderLayout(8, 0));
            tick.setOpaque(false);
            tick.setVisible(multi);
            west.add(tick, BorderLayout.WEST);
            west.add(picture, BorderLayout.CENTER);
            add(west, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends T> l, T item, int index, boolean selected, boolean focus) {
            name.setText(spec.name().apply(item));
            detail.setText(spec.detail().apply(item));
            thumb = spec.thumbnail().apply(item);
            tick.setSelected(selected);
            name.setForeground(selected ? l.getSelectionForeground() : Palette.text());
            detail.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            setOpaque(true);
            return this;
        }
    }
}
