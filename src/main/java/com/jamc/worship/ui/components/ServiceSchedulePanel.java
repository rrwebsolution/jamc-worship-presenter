package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.Service;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.service.ServicePlanService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The open service's rundown. Every change is saved automatically a moment later (one save at a
 * time, each from a snapshot), so a crash or power cut never loses the order of service.
 * Selecting an item presents it: its slides appear in the strip, ready to go live.
 */
public class ServiceSchedulePanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(ServiceSchedulePanel.class);
    private static final DataFlavor INDEX_FLAVOR = new DataFlavor(Integer.class, "Schedule index");

    private final ServicePlanService plans;
    private final DefaultListModel<ServiceItem> model = new DefaultListModel<>();
    private final JList<ServiceItem> list = new JList<>(model);
    private final JLabel title = Ui.label("Service Schedule", Palette::text, "h4");
    private final JLabel subtitle = Ui.small(" ");
    private final JLabel saveState = Ui.label(" ", Palette::mutedText, "small");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private final JButton upButton = Ui.toolButton("arrow-up", "Move up (Alt+Up)");
    private final JButton downButton = Ui.toolButton("arrow-down", "Move down (Alt+Down)");
    private final JButton removeButton = Ui.toolButton("trash", "Remove from schedule (Delete)");
    private final JButton duplicateButton = Ui.toolButton("copy", "Duplicate item (Ctrl+D)");
    private final Timer autosave;

    private Service service;
    private boolean dirty;
    private boolean saving;
    private boolean pendingSave;
    private Consumer<String> statusHandler = s -> { };
    private Consumer<ServiceItem> activationHandler = i -> { };
    private Consumer<ServiceItem> removalHandler = i -> { };
    /** True while the selection moves after a removal: the neighbour is selected but not presented. */
    private boolean suppressActivation;
    private Runnable openServicesHandler = () -> { };
    private Consumer<Service> serviceChangedHandler = s -> { };
    private Supplier<List<ServiceItem>> lowerThirdChoices = List::of;

    public ServiceSchedulePanel(ServicePlanService plans) {
        super(new BorderLayout());
        this.plans = plans;
        setOpaque(false);

        JButton menu = Ui.toolButton("sliders", "Service: save, save as, rename, open another service");
        menu.addActionListener(e -> showServiceMenu(menu));
        JButton add = Ui.toolButton("plus", "Add an item");
        add.addActionListener(e -> showAddMenu(add));
        JPanel head = Ui.header(title, subtitle, add, menu);
        add(head, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ItemRenderer());
        list.setFixedCellHeight(44);
        list.setOpaque(false);
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new ScheduleTransferHandler());
        list.addListSelectionListener(e -> {
            updateButtons();
            if (!e.getValueIsAdjusting() && !suppressActivation && list.getSelectedValue() != null) {
                activationHandler.accept(list.getSelectedValue());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            /** The row was already selected when pressed: a click shows it again (the selection does not change). */
            private int pressedSelected = -1;

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && i >= 0) {
                    editItem(i);
                } else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 && i >= 0 && i == pressedSelected) {
                    activationHandler.accept(list.getModel().getElementAt(i));
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                pressedSelected = i >= 0 && list.isSelectedIndex(i) ? i : -1;
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove", this::removeSelected, WHEN_FOCUSED);
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK), "up", () -> move(-1), WHEN_FOCUSED);
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK), "down", () -> move(1), WHEN_FOCUSED);
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK), "dup", this::duplicateSelected, WHEN_FOCUSED);
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename", () -> editItem(list.getSelectedIndex()), WHEN_FOCUSED);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);

        JLabel empty = Ui.label("<html><center>The schedule is empty.<br><br>Drag songs, slides, Bible passages or "
                + "media here,<br>or use <b>Add to Service</b>, or click <b>+</b>.</center></html>", Palette::mutedText, null);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setVerticalAlignment(SwingConstants.TOP);
        empty.setBorder(BorderFactory.createEmptyBorder(40, 12, 0, 12));
        empty.setTransferHandler(list.getTransferHandler());

        body.add(scroll, "list");
        body.add(empty, "empty");
        body.setBorder(Ui.line(true, false, false, false));
        add(body, BorderLayout.CENTER);

        upButton.addActionListener(e -> move(-1));
        downButton.addActionListener(e -> move(1));
        removeButton.addActionListener(e -> removeSelected());
        duplicateButton.addActionListener(e -> duplicateSelected());
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        buttons.add(upButton);
        buttons.add(downButton);
        buttons.add(duplicateButton);
        buttons.add(removeButton);
        JPanel footer = Ui.transparent(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 6, 6, 10)));
        footer.add(buttons, BorderLayout.WEST);
        footer.add(saveState, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        autosave = new Timer(1000, e -> save());
        autosave.setRepeats(false);
        refreshView();
    }

    // ------------------------------------------------------------------ public API

    public void setStatusHandler(Consumer<String> statusHandler) {
        this.statusHandler = statusHandler;
    }

    /** Called when the operator selects an item, to present it. */
    public void setActivationHandler(Consumer<ServiceItem> handler) {
        this.activationHandler = handler;
    }

    /** Called after an item was removed from the schedule (e.g. so the workspace stops showing it). */
    public void setRemovalHandler(Consumer<ServiceItem> handler) {
        this.removalHandler = handler;
    }

    public void setOpenServicesHandler(Runnable handler) {
        this.openServicesHandler = handler;
    }

    /** Called after the open service changes (opened, renamed) — e.g. to remember it for next start. */
    public void setServiceChangedHandler(Consumer<Service> handler) {
        this.serviceChangedHandler = handler;
    }

    /** Supplies lower thirds for the Add menu. */
    public void setLowerThirdChoices(Supplier<List<ServiceItem>> choices) {
        this.lowerThirdChoices = choices;
    }

    public Service current() {
        return service;
    }

    /** Shows a loaded service (saving the previous one first). */
    public void open(Service s) {
        save();
        service = s;
        model.clear();
        model.addAll(s.getItems());
        dirty = false;
        updateHeader();
        saveState.setText("Saved");
        refreshView();
        serviceChangedHandler.accept(s);
    }

    public void addItem(ServiceItem item) {
        int at = list.getSelectedIndex() >= 0 ? list.getSelectedIndex() + 1 : model.size();
        insertItem(at, item);
    }

    public int selectedIndex() {
        return list.getSelectedIndex();
    }

    public void selectIndex(int index) {
        if (index >= 0 && index < model.size()) {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
    }

    public void selectNext() {
        int i = list.getSelectedIndex();
        if (i < model.size() - 1) {
            list.setSelectedIndex(i + 1);
            list.ensureIndexIsVisible(i + 1);
        }
    }

    public void selectPrevious() {
        int i = list.getSelectedIndex();
        if (i > 0) {
            list.setSelectedIndex(i - 1);
            list.ensureIndexIsVisible(i - 1);
        }
    }

    /** Saves now if there are unsaved changes (asynchronously, one save at a time). */
    public void save() {
        autosave.stop();
        if (service == null || !dirty) {
            return;
        }
        if (saving) {
            pendingSave = true;
            return;
        }
        Service snapshot = snapshot();
        saving = true;
        dirty = false;
        saveState.setText("Saving…");
        BackgroundTask.run("Save service", () -> plans.save(snapshot), saved -> {
            saving = false;
            if (service != null && saved.getId().equals(service.getId())) {
                service.setUpdatedAt(saved.getUpdatedAt());
                saveState.setText(dirty ? "Unsaved changes" : "Saved");
            }
            if (pendingSave || dirty) {
                pendingSave = false;
                save();
            }
        }, err -> {
            saving = false;
            dirty = true;
            saveState.setText("Not saved");
            Dialogs.showError(this, "The service could not be saved.", err);
        });
    }

    /** Synchronous save used while the application closes. */
    public void saveBlocking() {
        autosave.stop();
        if (service != null && dirty) {
            plans.save(snapshot());
            dirty = false;
        }
    }

    // ------------------------------------------------------------------ editing

    private Service snapshot() {
        Service s = new Service();
        s.setId(service.getId());
        s.setName(service.getName());
        s.setPlaylist(service.getPlaylist());
        s.setDate(service.getDate());
        s.setTemplate(service.isTemplate());
        s.setNotes(service.getNotes());
        List<ServiceItem> items = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            items.add(model.get(i).copy());
        }
        s.setItems(items);
        return s;
    }

    private void changed() {
        if (service == null) {
            return;
        }
        dirty = true;
        saveState.setText("Unsaved changes");
        autosave.restart();
        refreshView();
    }

    private void insertItem(int index, ServiceItem item) {
        if (service == null) {
            statusHandler.accept("Open or create a service first (Service page).");
            return;
        }
        int at = Math.clamp(index, 0, model.size());
        model.add(at, item);
        list.setSelectedIndex(at);
        list.ensureIndexIsVisible(at);
        changed();
        statusHandler.accept("Added \"" + item.getTitle() + "\" to the service");
    }

    private void removeSelected() {
        int i = list.getSelectedIndex();
        if (i < 0) {
            return;
        }
        ServiceItem removed = model.get(i);
        if (!Dialogs.confirmDestructive(this, "Remove item", "Remove \"" + removed.getTitle()
                + "\" from this service?\n(The song or media stays in the library.)", "Remove")) {
            return;
        }
        suppressActivation = true; // removing never presents the next item; the workspace returns to the library
        try {
            model.remove(i);
            if (!model.isEmpty()) {
                list.setSelectedIndex(Math.min(i, model.size() - 1));
            }
        } finally {
            suppressActivation = false;
        }
        changed();
        removalHandler.accept(removed);
        statusHandler.accept("Removed \"" + removed.getTitle() + "\" from the service");
    }

    private void duplicateSelected() {
        int i = list.getSelectedIndex();
        if (i >= 0) {
            insertItem(i + 1, model.get(i).copy());
        }
    }

    private void move(int delta) {
        int i = list.getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= model.size()) {
            return;
        }
        ServiceItem item = model.remove(i);
        model.add(j, item);
        list.setSelectedIndex(j);
        list.ensureIndexIsVisible(j);
        changed();
    }

    /** Rename an item, or edit the text of an announcement / sermon item. */
    private void editItem(int i) {
        if (i < 0 || i >= model.size()) {
            return;
        }
        ServiceItem item = model.get(i);
        if (item.getType() == ServiceItem.Type.ANNOUNCEMENT || item.getType() == ServiceItem.Type.SERMON) {
            ServiceItem edited = textItemDialog(item.getType(), item.getTitle(), item.getPayload());
            if (edited != null) {
                item.setTitle(edited.getTitle());
                item.setPayload(edited.getPayload());
                list.repaint();
                changed();
                activationHandler.accept(item);
            }
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Name shown in the schedule:", "Rename Item",
                JOptionPane.PLAIN_MESSAGE, null, null, item.getTitle());
        if (name != null && !name.toString().isBlank()) {
            item.setTitle(name.toString().strip());
            list.repaint();
            changed();
        }
    }

    private void renameService() {
        if (service == null) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Service name:", "Rename Service",
                JOptionPane.PLAIN_MESSAGE, null, null, service.getName());
        if (name != null && !name.toString().isBlank()) {
            service.setName(name.toString().strip());
            updateHeader();
            changed();
            serviceChangedHandler.accept(service);
        }
    }

    /** Saves now even without changes (the schedule also saves itself a moment after every change). */
    private void saveNow() {
        if (service != null) {
            dirty = true;
            save();
            statusHandler.accept("Service \"" + service.getName() + "\" saved.");
        }
    }

    /** Saves the schedule as a new service under another name and opens the copy. */
    private void saveAsNew() {
        if (service == null) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Name for the new service:", "Save Service As",
                JOptionPane.PLAIN_MESSAGE, null, null, service.getName() + " (Copy)");
        if (name == null || name.toString().isBlank()) {
            return;
        }
        save(); // keep the original as it is now
        Service copy = snapshot().copyWithoutId();
        copy.setName(name.toString().strip());
        copy.setTemplate(false);
        BackgroundTask.run("Save service as", () -> plans.save(copy), saved -> {
            open(saved);
            statusHandler.accept("Saved as \"" + saved.getName() + "\"");
        }, err -> Dialogs.showError(this, "Could not save the service.", err));
    }

    private void saveAsTemplate() {
        if (service == null) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Template name:", "Save as Template",
                JOptionPane.PLAIN_MESSAGE, null, null, service.getPlaylist() == null ? "Service Template" : service.getPlaylist());
        if (name == null || name.toString().isBlank()) {
            return;
        }
        Service snapshot = snapshot();
        BackgroundTask.run("Save template", () -> plans.saveAsTemplate(snapshot, name.toString()),
                t -> statusHandler.accept("Saved template \"" + t.getName() + "\""),
                err -> Dialogs.showError(this, "Could not save the template.", err));
    }

    private void showServiceMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Save", "copy", this::saveNow)).setEnabled(service != null);
        menu.add(item("Save As…", "copy", this::saveAsNew)).setEnabled(service != null);
        menu.add(item("Rename Service…", "edit", this::renameService)).setEnabled(service != null);
        menu.add(item("Save as Template…", "layers", this::saveAsTemplate)).setEnabled(service != null);
        menu.addSeparator();
        menu.add(item("Open…  /  New Service…", "folder", openServicesHandler));
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void showAddMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Announcement…", "slides", () -> addText(ServiceItem.Type.ANNOUNCEMENT)));
        menu.add(item("Sermon Notes…", "slides", () -> addText(ServiceItem.Type.SERMON)));
        menu.add(item("Blank Screen", "monitor", () -> insertItemAtSelection(new ServiceItem(ServiceItem.Type.BLANK, "Blank", null))));
        List<ServiceItem> lts = lowerThirdChoices.get();
        if (!lts.isEmpty()) {
            JMenuItem header = new JMenuItem("Lower Thirds:");
            header.setEnabled(false);
            menu.addSeparator();
            menu.add(header);
            for (ServiceItem lt : lts) {
                menu.add(item("   " + lt.getTitle(), "lower-third", () -> insertItemAtSelection(lt.copy())));
            }
        }
        menu.addSeparator();
        JMenuItem hint = new JMenuItem("Songs, Bible, slides and media: drag them here or use Add to Service");
        hint.setEnabled(false);
        menu.add(hint);
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void insertItemAtSelection(ServiceItem item) {
        addItem(item);
    }

    private void addText(ServiceItem.Type type) {
        ServiceItem item = textItemDialog(type, type == ServiceItem.Type.SERMON ? "Sermon" : "Announcements", "");
        if (item != null) {
            addItem(item);
        }
    }

    private ServiceItem textItemDialog(ServiceItem.Type type, String title, String text) {
        javax.swing.JTextField titleField = new javax.swing.JTextField(title);
        JTextArea area = new JTextArea(text == null ? "" : text, 10, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(titleField, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(480, 220));
        p.add(sp, BorderLayout.CENTER);
        p.add(new JLabel("A blank line starts a new slide."), BorderLayout.SOUTH);
        int ok = JOptionPane.showConfirmDialog(this, p, type.displayName(), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return null;
        }
        return ServicePlanService.textItem(type, titleField.getText().isBlank() ? type.displayName() : titleField.getText().strip(),
                area.getText());
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int i = list.locationToIndex(e.getPoint());
        if (i >= 0) {
            list.setSelectedIndex(i);
        }
        JPopupMenu menu = new JPopupMenu();
        boolean has = i >= 0;
        ServiceItem it = has ? model.get(i) : null;
        boolean text = has && (it.getType() == ServiceItem.Type.ANNOUNCEMENT || it.getType() == ServiceItem.Type.SERMON);
        menu.add(item(text ? "Edit Text…" : "Rename…", "edit", () -> editItem(i))).setEnabled(has);
        menu.add(tagMenu(it)).setEnabled(has);
        menu.add(item("Duplicate", "copy", this::duplicateSelected)).setEnabled(has);
        menu.add(item("Move Up", "arrow-up", () -> move(-1))).setEnabled(has && i > 0);
        menu.add(item("Move Down", "arrow-down", () -> move(1))).setEnabled(has && i < model.size() - 1);
        menu.addSeparator();
        menu.add(item("Remove…", "trash", this::removeSelected)).setEnabled(has);
        menu.show(list, e.getX(), e.getY());
    }

    /** Tag ▸ Fast / Slow / ... / Custom… / No Tag: a short label shown on the item's row. */
    private JMenu tagMenu(ServiceItem it) {
        JMenu menu = new JMenu("Tag");
        menu.setIcon(Icons.get("tag", Icons.SMALL));
        if (it == null) {
            return menu;
        }
        List<String> choices = new ArrayList<>(ServiceItem.COMMON_TAGS);
        if (it.getTag() != null && choices.stream().noneMatch(t -> t.equalsIgnoreCase(it.getTag()))) {
            choices.add(it.getTag());
        }
        for (String t : choices) {
            JCheckBoxMenuItem choice = new JCheckBoxMenuItem(t, t.equalsIgnoreCase(String.valueOf(it.getTag())));
            choice.addActionListener(e -> setTag(it, t));
            menu.add(choice);
        }
        menu.addSeparator();
        JMenuItem custom = new JMenuItem("Custom…");
        custom.addActionListener(e -> {
            Object v = JOptionPane.showInputDialog(this, "Tag for \"" + it.getTitle() + "\" (e.g. Fast, Slow, Special Number):",
                    "Tag", JOptionPane.PLAIN_MESSAGE, null, null, it.getTag() == null ? "" : it.getTag());
            if (v != null) {
                setTag(it, v.toString().length() > 24 ? v.toString().strip().substring(0, 24) : v.toString());
            }
        });
        menu.add(custom);
        JMenuItem none = new JMenuItem("No Tag");
        none.setEnabled(it.getTag() != null);
        none.addActionListener(e -> setTag(it, null));
        menu.add(none);
        return menu;
    }

    private void setTag(ServiceItem it, String tag) {
        it.setTag(tag);
        list.repaint();
        changed();
    }

    /** A steady color per tag: Fast warm, Slow cool, others from the tag's text. */
    static Color tagColor(String tag) {
        return switch (tag.toLowerCase(java.util.Locale.ROOT)) {
            case "fast" -> new Color(0xF97316);
            case "slow" -> new Color(0x3B82F6);
            case "opening" -> new Color(0x16A34A);
            case "worship" -> new Color(0x8B5CF6);
            case "offering" -> new Color(0xEAB308);
            case "closing" -> new Color(0xEF4444);
            default -> Color.getHSBColor((tag.toLowerCase(java.util.Locale.ROOT).hashCode() & 0xFFFF) / 65536f, 0.55f, 0.85f);
        };
    }

    private static JMenuItem item(String text, String icon, Runnable action) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        i.addActionListener(e -> action.run());
        return i;
    }

    private void updateHeader() {
        title.setText(service == null ? "Service Schedule" : service.getName());
        title.setToolTipText(title.getText());
        String sub = service == null ? "No service open"
                : (service.getPlaylist() == null ? "Service" : service.getPlaylist()) + (service.isTemplate() ? " · template" : "");
        subtitle.setText(sub);
    }

    private void refreshView() {
        cards.show(body, model.isEmpty() ? "empty" : "list");
        updateButtons();
    }

    private void updateButtons() {
        int i = list.getSelectedIndex();
        upButton.setEnabled(i > 0);
        downButton.setEnabled(i >= 0 && i < model.size() - 1);
        removeButton.setEnabled(i >= 0);
        duplicateButton.setEnabled(i >= 0);
    }

    /** Accepts items dragged from the libraries and reorders items dragged within the schedule. */
    private final class ScheduleTransferHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            int index = list.getSelectedIndex();
            if (index < 0) {
                return null;
            }
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{INDEX_FLAVOR};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return INDEX_FLAVOR.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor flavor) {
                    return index;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDrop() && (support.isDataFlavorSupported(ServiceItemTransfer.FLAVOR)
                    || support.isDataFlavorSupported(INDEX_FLAVOR));
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            int dropIndex = model.size();
            if (support.getComponent() == list && support.getDropLocation() instanceof JList.DropLocation loc) {
                dropIndex = loc.getIndex() < 0 ? model.size() : loc.getIndex();
            }
            try {
                if (support.isDataFlavorSupported(INDEX_FLAVOR)) {
                    int from = (Integer) support.getTransferable().getTransferData(INDEX_FLAVOR);
                    if (from == dropIndex || from + 1 == dropIndex) {
                        return false;
                    }
                    ServiceItem item = model.remove(from);
                    int to = from < dropIndex ? dropIndex - 1 : dropIndex;
                    model.add(to, item);
                    list.setSelectedIndex(to);
                    changed();
                    return true;
                }
                ServiceItem item = (ServiceItem) support.getTransferable().getTransferData(ServiceItemTransfer.FLAVOR);
                insertItem(dropIndex, item);
                return true;
            } catch (UnsupportedFlavorException | IOException e) {
                log.debug("Drop failed", e);
                return false;
            }
        }
    }

    /** Numbered row with a type icon, title, type label and (when set) a colored tag chip. */
    private static final class ItemRenderer extends JPanel implements ListCellRenderer<ServiceItem> {
        private final JLabel number = new JLabel();
        private final JLabel title = new JLabel();
        private final JLabel type = new JLabel();
        private final TagChip tag = new TagChip();

        ItemRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
            number.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
            number.setHorizontalAlignment(SwingConstants.RIGHT);
            number.setPreferredSize(new Dimension(18, 10));
            title.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
            type.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            JPanel text = new JPanel(new BorderLayout(0, 1));
            text.setOpaque(false);
            text.add(title, BorderLayout.NORTH);
            text.add(type, BorderLayout.SOUTH);
            add(number, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            JPanel tagHolder = new JPanel(new java.awt.GridBagLayout()); // keeps the chip vertically centered
            tagHolder.setOpaque(false);
            tagHolder.add(tag);
            add(tagHolder, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ServiceItem> list, ServiceItem item, int index,
                                                      boolean selected, boolean focused) {
            number.setText(String.valueOf(index + 1));
            title.setText(item.getTitle());
            Color iconColor = selected ? Color.WHITE : Palette.accent();
            type.setIcon(Icons.get(item.getType().icon(), 12, () -> iconColor));
            type.setText(item.getType().displayName());
            Color fg = selected ? list.getSelectionForeground() : Palette.text();
            number.setForeground(selected ? fg : Palette.mutedText());
            title.setForeground(fg);
            type.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
            tag.setTag(item.getTag());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setOpaque(selected);
            return this;
        }
    }

    /** A small rounded label in the tag's color. */
    private static final class TagChip extends JLabel {
        private Color color = Color.GRAY;

        TagChip() {
            setForeground(Color.WHITE);
            putClientProperty(FlatClientProperties.STYLE, "font: bold -2");
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        }

        void setTag(String tag) {
            setVisible(tag != null);
            setText(tag == null ? "" : tag.toUpperCase(java.util.Locale.ROOT));
            if (tag != null) {
                color = tagColor(tag);
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
