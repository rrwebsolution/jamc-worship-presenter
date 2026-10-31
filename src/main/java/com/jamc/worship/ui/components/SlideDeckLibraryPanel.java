package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.SlideDeck;
import com.jamc.worship.service.SlideDeckService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** List of custom slide decks with create / duplicate / delete / add-to-service. */
public class SlideDeckLibraryPanel extends JPanel {

    private final SlideDeckService deckService;
    private final DefaultListModel<SlideDeck> model = new DefaultListModel<>();
    private final JList<SlideDeck> list = new JList<>(model);
    private final JTextField searchField = new JTextField();
    private final JLabel countLabel = Ui.small(" ");
    private final JLabel emptyLabel = Ui.label("", Palette::mutedText, null);
    private final CardLayout cards = new CardLayout();
    private final JPanel holder = Ui.transparent(cards);
    private final JButton duplicateButton = Ui.toolButton("copy", "Duplicate deck");
    private final JButton deleteButton = Ui.toolButton("trash", "Delete deck (Delete)");
    private final JButton addToServiceButton = Ui.primaryButton("Add to Service", "list-plus");
    private final List<Consumer<SlideDeck>> selectionListeners = new ArrayList<>();
    private Consumer<SlideDeck> addToServiceHandler = d -> { };
    private Runnable importPowerPointHandler = () -> { };
    private LongConsumer beforeDelete = id -> { };
    private Consumer<String> statusHandler = s -> { };
    private long seq;
    private boolean suppress;

    public SlideDeckLibraryPanel(SlideDeckService deckService) {
        super(new BorderLayout());
        this.deckService = deckService;
        setOpaque(false);

        JButton addButton = Ui.toolButton("plus", "New slide deck");
        addButton.addActionListener(e -> createDeck());
        JButton importButton = Ui.toolButton("import", "Import PowerPoint (.pptx, .ppt) into Presentations");
        importButton.addActionListener(e -> importPowerPointHandler.run());
        JPanel top = Ui.transparent(new BorderLayout());
        top.add(Ui.header(Ui.label("Slides", Palette::text, "h3"), countLabel, importButton, addButton), BorderLayout.NORTH);
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search decks and slide text…");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("search", Icons.SMALL));
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        JPanel searchRow = Ui.transparent(new BorderLayout());
        searchRow.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        searchRow.add(searchField);
        top.add(searchRow, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        Timer debounce = new Timer(200, e -> refresh(selectedId()));
        debounce.setRepeats(false);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DeckRenderer());
        list.setFixedCellHeight(50);
        list.setOpaque(false);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !suppress) {
                updateButtons();
                selectionListeners.forEach(l -> l.accept(list.getSelectedValue()));
            }
        });
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelected, WHEN_FOCUSED);
        list.setDragEnabled(true);
        list.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(javax.swing.JComponent c) {
                return COPY;
            }

            @Override
            protected java.awt.datatransfer.Transferable createTransferable(javax.swing.JComponent c) {
                SlideDeck d = list.getSelectedValue();
                return d == null ? null : new ServiceItemTransfer(
                        new com.jamc.worship.model.ServiceItem(com.jamc.worship.model.ServiceItem.Type.SLIDE, d.getName(), d.getId()));
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setVerticalAlignment(SwingConstants.TOP);
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(40, 16, 0, 16));
        holder.add(scroll, "list");
        holder.add(emptyLabel, "empty");
        holder.setBorder(Ui.line(true, false, false, false));
        add(holder, BorderLayout.CENTER);

        duplicateButton.addActionListener(e -> duplicateSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        addToServiceButton.addActionListener(e -> {
            SlideDeck d = list.getSelectedValue();
            if (d != null) addToServiceHandler.accept(d);
        });
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(duplicateButton);
        left.add(deleteButton);
        JPanel bar = Ui.transparent(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 6, 6, 8)));
        bar.add(left, BorderLayout.WEST);
        bar.add(addToServiceButton, BorderLayout.EAST);
        add(bar, BorderLayout.SOUTH);
        updateButtons();
    }

    public void addSelectionListener(Consumer<SlideDeck> l) {
        selectionListeners.add(l);
    }

    /** Called by the Import PowerPoint button (presentations live in their own library). */
    public void setImportPowerPointHandler(Runnable h) {
        importPowerPointHandler = h;
    }

    public void setAddToServiceHandler(Consumer<SlideDeck> h) {
        addToServiceHandler = h;
    }

    /** Called with the deck id before it is deleted, so an open editor can close it. */
    public void setBeforeDelete(LongConsumer beforeDelete) {
        this.beforeDelete = beforeDelete;
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    public SlideDeck selectedDeck() {
        return list.getSelectedValue();
    }

    public void refresh(Long selectId) {
        long s = ++seq;
        String text = searchField.getText();
        BackgroundTask.run("Load slide decks", () -> deckService.search(text), decks -> {
            if (s != seq) {
                return;
            }
            Long previous = selectedId();
            suppress = true;
            try {
                model.clear();
                model.addAll(decks);
                Long want = selectId != null ? selectId : previous;
                for (int i = 0; i < decks.size(); i++) {
                    if (Objects.equals(decks.get(i).getId(), want)) {
                        list.setSelectedIndex(i);
                        list.ensureIndexIsVisible(i);
                    }
                }
            } finally {
                suppress = false;
            }
            countLabel.setText(decks.size() + (decks.size() == 1 ? " deck" : " decks"));
            emptyLabel.setText(text.isBlank()
                    ? "<html><center>No slide decks yet.<br>Click + to create one.</center></html>"
                    : "<html><center>No decks match your search.</center></html>");
            cards.show(holder, decks.isEmpty() ? "empty" : "list");
            updateButtons();
            SlideDeck sel = list.getSelectedValue();
            if (!Objects.equals(sel == null ? null : sel.getId(), previous) || selectId != null) {
                selectionListeners.forEach(l -> l.accept(sel));
            }
        }, err -> Dialogs.showError(this, "Could not load slide decks.", err));
    }

    /** Updates the slide count / name shown for one deck without reloading the list. */
    public void updateSummary(long id, String name, int slideCount) {
        for (int i = 0; i < model.size(); i++) {
            SlideDeck d = model.get(i);
            if (d.getId() == id) {
                d.setName(name);
                d.setSlideCount(slideCount);
                list.repaint(list.getCellBounds(i, i));
            }
        }
    }

    public void createDeck() {
        Object name = JOptionPane.showInputDialog(this, "Name for the new slide deck:", "New Slide Deck",
                JOptionPane.PLAIN_MESSAGE, null, null, "Announcements");
        if (name == null || name.toString().isBlank()) {
            return;
        }
        BackgroundTask.run("Create slide deck", () -> deckService.create(name.toString(), null), deck -> {
            statusHandler.accept("Created slide deck \"" + deck.getName() + "\"");
            if (!searchField.getText().isBlank()) {
                searchField.setText("");
            }
            refresh(deck.getId());
        }, err -> Dialogs.showError(this, "Could not create the slide deck.", err));
    }

    private void duplicateSelected() {
        SlideDeck d = list.getSelectedValue();
        if (d == null) {
            return;
        }
        BackgroundTask.run("Duplicate slide deck", () -> deckService.duplicate(d.getId()), copy -> {
            statusHandler.accept("Duplicated as \"" + copy.getName() + "\"");
            refresh(copy.getId());
        }, err -> Dialogs.showError(this, "Could not duplicate the slide deck.", err));
    }

    private void deleteSelected() {
        SlideDeck d = list.getSelectedValue();
        if (d == null || !Dialogs.confirmDestructive(this, "Delete slide deck",
                "Delete \"" + d.getName() + "\" and all of its slides?\n\nThis cannot be undone.", "Delete")) {
            return;
        }
        beforeDelete.accept(d.getId());
        BackgroundTask.run("Delete slide deck", () -> deckService.delete(d.getId()), ok -> {
            statusHandler.accept("Deleted slide deck \"" + d.getName() + "\"");
            list.clearSelection();
            refresh(null);
        }, err -> Dialogs.showError(this, "Could not delete the slide deck.", err));
    }

    private void updateButtons() {
        boolean has = list.getSelectedValue() != null;
        Ui.setEnabled(has, duplicateButton, deleteButton, addToServiceButton);
    }

    private Long selectedId() {
        SlideDeck d = list.getSelectedValue();
        return d == null ? null : d.getId();
    }

    private static final class DeckRenderer extends JPanel implements ListCellRenderer<SlideDeck> {
        private final JLabel name = new JLabel();
        private final JLabel info = new JLabel();

        DeckRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
            name.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
            info.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            JPanel text = new JPanel(new BorderLayout(0, 2));
            text.setOpaque(false);
            text.add(name, BorderLayout.NORTH);
            text.add(info, BorderLayout.SOUTH);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SlideDeck> l, SlideDeck d, int index,
                                                      boolean selected, boolean focused) {
            name.setText(d.getName());
            info.setText(d.getSlideCount() + (d.getSlideCount() == 1 ? " slide" : " slides"));
            name.setForeground(selected ? l.getSelectionForeground() : Palette.text());
            info.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            setOpaque(selected);
            return this;
        }
    }
}
