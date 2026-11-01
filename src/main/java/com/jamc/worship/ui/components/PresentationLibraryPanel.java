package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.Presentation;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.service.PresentationService;
import com.jamc.worship.service.PresentationService.PresentationSlide;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * The presentation library: imported PowerPoint files with their first slide, slide count and
 * file status. Imports run in the background with a progress bar, and each slide is passed on as
 * soon as it is rendered so the operator sees the thumbnails appear.
 */
public class PresentationLibraryPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(PresentationLibraryPanel.class);

    /** Import progress, delivered on the EDT. */
    public interface ImportListener {
        void importStarted(String name, int slideCount, String renderer, Long refreshingId);

        void importSlide(PresentationSlide slide);

        /** {@code p} is null when the import failed or was cancelled. */
        void importEnded(Presentation p, boolean refreshed);
    }

    private final PresentationService service;
    private final DefaultListModel<Presentation> model = new DefaultListModel<>();
    private final JList<Presentation> list = new JList<>(model);
    private final JTextField searchField = new JTextField();
    private final JLabel countLabel = Ui.small(" ");
    private final JLabel emptyLabel = Ui.label("", Palette::mutedText, null);
    private final CardLayout cards = new CardLayout();
    private final JPanel holder = Ui.transparent(cards);
    private final JButton importButton = Ui.primaryButton("Import PowerPoint", "import");
    private final JButton renameButton = Ui.toolButton("edit", "Rename library entry");
    private final JButton refreshButton = Ui.toolButton("refresh", "Refresh: render the slides again from the PowerPoint file");
    private final JButton removeButton = Ui.toolButton("trash", "Remove from library (the PowerPoint file is kept)");
    private final JButton addToServiceButton = Ui.button("Add to Service", "list-plus");

    private final JPanel progressPanel = Ui.transparent(new BorderLayout(0, 4));
    private final JLabel progressTitle = Ui.label(" ", Palette::text, "semibold");
    private final JLabel progressDetail = Ui.small(" ");
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton cancelButton = Ui.button("Cancel", null);
    private final Timer hideProgress = new Timer(5000, e -> progressPanel.setVisible(false));

    private final Map<Long, PresentationService.SourceState> states = new HashMap<>();
    private final List<Consumer<Presentation>> selectionListeners = new ArrayList<>();
    private Consumer<ServiceItem> addToServiceHandler = i -> { };
    private Consumer<String> statusHandler = s -> { };
    private ImportListener importListener;
    private ImportWorker running;
    private long seq;
    private boolean suppress;

    public PresentationLibraryPanel(PresentationService service) {
        super(new BorderLayout());
        this.service = service;
        setOpaque(false);

        JPanel top = Ui.transparent(new BorderLayout());
        top.add(Ui.header(Ui.label("Presentations", Palette::text, "h3"), countLabel), BorderLayout.NORTH);
        JPanel controls = Ui.transparent(new BorderLayout(0, 8));
        controls.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        importButton.setToolTipText("Import a PowerPoint file (.pptx, .ppt) — or drop files onto this list");
        importButton.addActionListener(e -> importPowerPoint());
        controls.add(importButton, BorderLayout.NORTH);
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search presentations…");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("search", Icons.SMALL));
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        controls.add(searchField, BorderLayout.CENTER);
        top.add(controls, BorderLayout.CENTER);
        top.add(buildProgress(), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        Timer debounce = new Timer(200, e -> refresh(selectedId()));
        debounce.setRepeats(false);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.setFixedCellHeight(58);
        list.setOpaque(false);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !suppress) {
                updateButtons();
                selectionListeners.forEach(l -> l.accept(list.getSelectedValue()));
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    list.setSelectedIndex(i);
                    showMenu(e);
                }
            }
        });
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove", this::removeSelected, WHEN_FOCUSED);
        Ui.bindKey(list, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename", this::renameSelected, WHEN_FOCUSED);
        Rendering.thumbnails().addChangeListener(list::repaint);
        TransferHandler transfer = new LibraryTransfer();
        list.setDragEnabled(true);
        list.setTransferHandler(transfer);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setVerticalAlignment(SwingConstants.TOP);
        emptyLabel.setBorder(BorderFactory.createEmptyBorder(40, 16, 0, 16));
        emptyLabel.setTransferHandler(transfer);
        holder.add(scroll, "list");
        holder.add(emptyLabel, "empty");
        holder.setBorder(Ui.line(true, false, false, false));
        add(holder, BorderLayout.CENTER);

        renameButton.addActionListener(e -> renameSelected());
        refreshButton.addActionListener(e -> refreshSelected(null));
        removeButton.addActionListener(e -> removeSelected());
        addToServiceButton.addActionListener(e -> {
            Presentation p = list.getSelectedValue();
            if (p != null) addToServiceHandler.accept(serviceItem(p));
        });
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(renameButton);
        left.add(refreshButton);
        left.add(removeButton);
        JPanel bar = Ui.transparent(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 6, 6, 8)));
        bar.add(left, BorderLayout.WEST);
        bar.add(addToServiceButton, BorderLayout.EAST);
        add(bar, BorderLayout.SOUTH);
        updateButtons();
    }

    private JComponent buildProgress() {
        progressBar.setStringPainted(true);
        progressBar.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        JPanel text = Ui.transparent(new BorderLayout(0, 2));
        text.add(progressTitle, BorderLayout.NORTH);
        text.add(progressDetail, BorderLayout.SOUTH);
        JPanel row = Ui.transparent(new BorderLayout(8, 0));
        row.add(progressBar, BorderLayout.CENTER);
        row.add(cancelButton, BorderLayout.EAST);
        cancelButton.addActionListener(e -> {
            if (running != null) {
                running.cancelRequested = true;
                progressDetail.setText("Cancelling…");
            }
        });
        progressPanel.add(text, BorderLayout.NORTH);
        progressPanel.add(row, BorderLayout.SOUTH);
        progressPanel.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(8, 10, 10, 10)));
        progressPanel.setVisible(false);
        hideProgress.setRepeats(false);
        return progressPanel;
    }

    public static ServiceItem serviceItem(Presentation p) {
        return new ServiceItem(ServiceItem.Type.PRESENTATION, p.name(), p.id());
    }

    public void addSelectionListener(Consumer<Presentation> l) {
        selectionListeners.add(l);
    }

    public void setImportListener(ImportListener l) {
        importListener = l;
    }

    public void setAddToServiceHandler(Consumer<ServiceItem> h) {
        addToServiceHandler = h;
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    public Presentation selected() {
        return list.getSelectedValue();
    }

    public boolean isImporting() {
        return running != null;
    }

    public PresentationService.SourceState state(Presentation p) {
        return p == null ? null : states.get(p.id());
    }

    /** Reloads the list and selects {@code selectId} (or keeps the current selection), notifying listeners. */
    public void refresh(Long selectId) {
        long s = ++seq;
        String text = searchField.getText();
        BackgroundTask.run("Load presentations", () -> {
            List<Presentation> found = service.list(text);
            Map<Long, PresentationService.SourceState> st = new HashMap<>();
            for (Presentation p : found) {
                st.put(p.id(), service.sourceState(p));
            }
            return new Object[]{found, st};
        }, r -> {
            if (s != seq) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Presentation> found = (List<Presentation>) r[0];
            @SuppressWarnings("unchecked")
            Map<Long, PresentationService.SourceState> st = (Map<Long, PresentationService.SourceState>) r[1];
            states.clear();
            states.putAll(st);
            Long previous = selectedId();
            suppress = true;
            try {
                model.clear();
                model.addAll(found);
                Long want = selectId != null ? selectId : previous;
                if (want == null && !found.isEmpty()) {
                    want = found.getFirst().id(); // open something right away instead of an empty view
                }
                for (int i = 0; i < found.size(); i++) {
                    if (Objects.equals(found.get(i).id(), want)) {
                        list.setSelectedIndex(i);
                        list.ensureIndexIsVisible(i);
                    }
                }
            } finally {
                suppress = false;
            }
            countLabel.setText(found.size() + (found.size() == 1 ? " presentation" : " presentations"));
            emptyLabel.setText(text.isBlank()
                    ? "<html><center>No presentations yet.<br><br>Click <b>Import PowerPoint</b>, or drop<br>"
                    + ".pptx / .ppt files here.</center></html>"
                    : "<html><center>No presentations match your search.</center></html>");
            cards.show(holder, found.isEmpty() ? "empty" : "list");
            updateButtons();
            Presentation sel = list.getSelectedValue();
            if (!Objects.equals(sel == null ? null : sel.id(), previous) || selectId != null) {
                selectionListeners.forEach(l -> l.accept(sel));
            }
        }, err -> Dialogs.showError(this, "Could not load the presentations.", err));
    }

    /** Selects a presentation by id (e.g. from the service schedule), clearing the search if needed. */
    public void select(long id) {
        if (!searchField.getText().isBlank()) {
            searchField.setText("");
        }
        refresh(id);
    }

    // ------------------------------------------------------------------ import

    /** Opens a file chooser and imports the chosen PowerPoint files one after another. */
    public void importPowerPoint() {
        if (running != null) {
            statusHandler.accept("An import is already running.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import PowerPoint");
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(new FileNameExtensionFilter("PowerPoint presentations (*.pptx, *.ppt)",
                "pptx", "ppt", "pptm", "ppsx", "pps"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY); // straight to the file dialog (no file / folder question)
        if (com.jamc.worship.ui.NativeFileDialogs.showDialog(chooser, SwingUtilities.getWindowAncestor(this), "Import") != JFileChooser.APPROVE_OPTION) {
            return;
        }
        List<Path> files = new ArrayList<>();
        for (File f : chooser.getSelectedFiles()) {
            files.add(f.toPath());
        }
        importAll(files);
    }

    /** Imports files in order; a file that is already in the library can be refreshed instead. */
    public void importAll(List<Path> files) {
        if (running != null || files.isEmpty()) {
            return;
        }
        List<Path> queue = new ArrayList<>(files);
        Path next = queue.removeFirst();
        if (!service.canImport(next)) {
            Dialogs.showInfo(this, "Import PowerPoint", "\"" + next.getFileName() + "\" cannot be imported.\n\n"
                    + "Choose a PowerPoint file (.pptx or .ppt), or a folder of slides exported as PNG/JPG images.");
            importAll(queue);
            return;
        }
        Presentation existing = service.findBySource(next).orElse(null);
        if (existing != null) {
            int choice = JOptionPane.showOptionDialog(this, "\"" + next.getFileName() + "\" is already in the library as \""
                            + existing.name() + "\".\n\nRefresh it from the file, or import a second copy?", "Import PowerPoint",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new Object[]{"Refresh Existing", "Import Copy", "Skip"}, "Refresh Existing");
            if (choice == 0) {
                start(new ImportWorker(next, existing, queue));
            } else if (choice == 1) {
                start(new ImportWorker(next, null, queue));
            } else {
                importAll(queue);
            }
            return;
        }
        start(new ImportWorker(next, null, queue));
    }

    /** Renders the selected presentation again (from {@code newSource} if the file was moved). */
    public void refreshSelected(Path newSource) {
        Presentation p = list.getSelectedValue();
        if (p == null || running != null) {
            return;
        }
        if (newSource == null && state(p) == PresentationService.SourceState.MISSING) {
            locateSelected();
            return;
        }
        start(new ImportWorker(newSource != null ? newSource : Path.of(p.sourcePath()), p, List.of()));
    }

    /** Asks where the moved or renamed PowerPoint file is now, then refreshes from it. */
    public void locateSelected() {
        Presentation p = list.getSelectedValue();
        if (p == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser(Path.of(p.sourcePath()).getParent() == null ? null
                : Path.of(p.sourcePath()).getParent().toFile());
        chooser.setDialogTitle("Locate \"" + p.sourceFileName() + "\"");
        chooser.setFileFilter(new FileNameExtensionFilter("PowerPoint presentations (*.pptx, *.ppt)",
                "pptx", "ppt", "pptm", "ppsx", "pps"));
        // a presentation imported from a folder of slide images is located as a folder; a PowerPoint file as a file
        boolean powerPoint = p.sourceFileName().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(pptx|ppt|pptm|ppsx|pps)$");
        chooser.setFileSelectionMode(powerPoint ? JFileChooser.FILES_ONLY : JFileChooser.DIRECTORIES_ONLY);
        if (com.jamc.worship.ui.NativeFileDialogs.showDialog(chooser, SwingUtilities.getWindowAncestor(this), "Use This File") == JFileChooser.APPROVE_OPTION) {
            refreshSelected(chooser.getSelectedFile().toPath());
        }
    }

    private void start(ImportWorker worker) {
        running = worker;
        hideProgress.stop();
        progressTitle.setText((worker.existing != null ? "Refreshing " : "Importing ") + worker.source.getFileName());
        progressDetail.setText("Opening the presentation…");
        progressBar.setIndeterminate(true);
        progressBar.setString("");
        cancelButton.setVisible(true);
        cancelButton.setEnabled(true);
        progressPanel.setVisible(true);
        updateButtons();
        worker.execute();
    }

    private record Started(String name, int slideCount, String renderer) {
    }

    private final class ImportWorker extends SwingWorker<Presentation, Object> {
        final Path source;
        final Presentation existing;
        final List<Path> queue;
        volatile boolean cancelRequested;
        int total;
        int done;

        ImportWorker(Path source, Presentation existing, List<Path> queue) {
            this.source = source;
            this.existing = existing;
            this.queue = queue;
        }

        @Override
        protected Presentation doInBackground() throws Exception {
            PresentationService.Progress progress = new PresentationService.Progress() {
                @Override
                public void started(String name, int slideCount, String renderer) {
                    publish(new Started(name, slideCount, renderer));
                }

                @Override
                public void slideReady(PresentationSlide slide) {
                    publish(slide);
                }

                @Override
                public boolean isCancelled() {
                    return cancelRequested;
                }
            };
            return existing != null ? service.refresh(existing.id(), source, progress) : service.importFile(source, progress);
        }

        @Override
        protected void process(List<Object> chunks) {
            for (Object c : chunks) {
                if (c instanceof Started start) {
                    total = start.slideCount();
                    done = 0;
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(total);
                    progressBar.setValue(0);
                    progressBar.setString("0 / " + total);
                    progressDetail.setText("Rendering slides with " + start.renderer() + "…");
                    if (importListener != null) {
                        importListener.importStarted(existing != null ? existing.name() : start.name(), total,
                                start.renderer(), existing == null ? null : existing.id());
                    }
                } else if (c instanceof PresentationSlide slide) {
                    done = slide.number();
                    progressBar.setValue(done);
                    progressBar.setString(done + " / " + total);
                    if (importListener != null) {
                        importListener.importSlide(slide);
                    }
                }
            }
        }

        @Override
        protected void done() {
            running = null;
            cancelButton.setVisible(false);
            progressBar.setIndeterminate(false);
            Presentation result = null;
            try {
                result = get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof CancellationException) {
                    progressTitle.setText("Import cancelled");
                    progressDetail.setText(source.getFileName().toString());
                } else {
                    log.warn("Import of {} failed", source, cause);
                    progressTitle.setText("Import failed");
                    progressDetail.setText(source.getFileName().toString());
                    Dialogs.showError(PresentationLibraryPanel.this, "Could not import \"" + source.getFileName() + "\".", cause);
                }
            }
            if (result != null) {
                progressTitle.setText("✓ Presentation Ready");
                progressDetail.setText(result.slideCountText() + (existing != null ? " refreshed" : " imported")
                        + " · " + result.renderer());
                progressBar.setValue(progressBar.getMaximum());
                statusHandler.accept("\"" + result.name() + "\" is ready: " + result.slideCountText()
                        + (result.warningCount() > 0 ? " (" + result.warningCount() + " with notes — see the presentation)" : ""));
            }
            hideProgress.restart();
            if (importListener != null) {
                importListener.importEnded(result, existing != null);
            }
            if (result != null) {
                if (!searchField.getText().isBlank() && existing == null) {
                    searchField.setText("");
                }
                refresh(result.id());
            } else {
                updateButtons();
            }
            if (!queue.isEmpty()) {
                SwingUtilities.invokeLater(() -> importAll(queue));
            }
        }
    }

    // ------------------------------------------------------------------ library actions

    private void renameSelected() {
        Presentation p = list.getSelectedValue();
        if (p == null) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Name in the library (the file is not renamed):", "Rename",
                JOptionPane.PLAIN_MESSAGE, null, null, p.name());
        if (name == null || name.toString().isBlank() || name.toString().strip().equals(p.name())) {
            return;
        }
        BackgroundTask.run("Rename presentation", () -> service.rename(p.id(), name.toString()), renamed -> {
            statusHandler.accept("Renamed to \"" + renamed.name() + "\"");
            refresh(renamed.id());
        }, err -> Dialogs.showError(this, "Could not rename the presentation.", err));
    }

    private void removeSelected() {
        Presentation p = list.getSelectedValue();
        if (p == null || running != null || !Dialogs.confirmDestructive(this, "Remove from library",
                "Remove \"" + p.name() + "\" from the library?\n\nIts rendered slides are deleted. The PowerPoint file itself\n"
                        + "is NOT deleted:\n" + p.sourcePath(), "Remove")) {
            return;
        }
        BackgroundTask.run("Remove presentation", () -> {
            service.remove(p.id());
            return true;
        }, ok -> {
            statusHandler.accept("Removed \"" + p.name() + "\" from the library. The file was kept.");
            list.clearSelection();
            refresh(null);
            selectionListeners.forEach(l -> l.accept(null));
        }, err -> Dialogs.showError(this, "Could not remove the presentation.", err));
    }

    private void showInFolder() {
        Presentation p = list.getSelectedValue();
        if (p == null) {
            return;
        }
        try {
            new ProcessBuilder("explorer.exe", "/select,", p.sourcePath()).start();
        } catch (Exception e) {
            Dialogs.showError(this, "Could not open the folder.", e);
        }
    }

    private void showMenu(MouseEvent e) {
        Presentation p = list.getSelectedValue();
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Open", "presentation", () -> selectionListeners.forEach(l -> l.accept(p))));
        menu.add(menuItem("Add to Service", "list-plus", () -> addToServiceHandler.accept(serviceItem(p))));
        menu.addSeparator();
        menu.add(menuItem("Rename…", "edit", this::renameSelected));
        JMenuItem refresh = menuItem("Refresh / Re-import", "refresh", () -> refreshSelected(null));
        refresh.setEnabled(running == null);
        menu.add(refresh);
        menu.add(menuItem("Locate File…", "folder", this::locateSelected));
        JMenuItem show = menuItem("Show File in Explorer", "folder", this::showInFolder);
        show.setEnabled(state(p) != PresentationService.SourceState.MISSING);
        menu.add(show);
        menu.addSeparator();
        menu.add(menuItem("Remove from Library", "trash", this::removeSelected));
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private static JMenuItem menuItem(String text, String icon, Runnable action) {
        JMenuItem item = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        item.addActionListener(ev -> action.run());
        return item;
    }

    private void updateButtons() {
        boolean has = list.getSelectedValue() != null;
        Ui.setEnabled(has, renameButton, addToServiceButton);
        Ui.setEnabled(has && running == null, refreshButton, removeButton);
        importButton.setEnabled(running == null);
    }

    private Long selectedId() {
        Presentation p = list.getSelectedValue();
        return p == null ? null : p.id();
    }

    /** Drag presentations to the schedule; drop PowerPoint files from Explorer to import them. */
    private final class LibraryTransfer extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            Presentation p = list.getSelectedValue();
            return p == null ? null : new ServiceItemTransfer(serviceItem(p));
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return running == null && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            try {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> paths = files.stream().map(File::toPath).toList();
                SwingUtilities.invokeLater(() -> importAll(paths));
                return true;
            } catch (Exception e) {
                log.warn("Drop failed", e);
                return false;
            }
        }
    }

    /** Row: first-slide thumbnail, name, "file.pptx · 24 slides", and a file-status note. */
    private final class RowRenderer extends JPanel implements ListCellRenderer<Presentation> {
        private final JLabel name = new JLabel();
        private final JLabel info = new JLabel();
        private final Thumb thumb = new Thumb();

        RowRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 12));
            name.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
            info.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            JPanel text = new JPanel(new BorderLayout(0, 2));
            text.setOpaque(false);
            text.add(name, BorderLayout.NORTH);
            text.add(info, BorderLayout.SOUTH);
            add(thumb, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Presentation> l, Presentation p, int index,
                                                      boolean selected, boolean focused) {
            name.setText(p.name());
            PresentationService.SourceState st = states.get(p.id());
            String note = st == PresentationService.SourceState.MISSING ? "  ·  file missing"
                    : st == PresentationService.SourceState.CHANGED ? "  ·  file changed" : "";
            String file = p.sourceFileName().equals(p.name()) ? "" : p.sourceFileName() + "  ·  ";
            info.setText(file + p.slideCountText() + note);
            name.setForeground(selected ? l.getSelectionForeground() : Palette.text());
            info.setForeground(selected ? new Color(255, 255, 255, 200)
                    : note.isEmpty() ? Palette.secondaryText() : Palette.warning());
            thumb.image = Rendering.thumbnails().get(service.thumbnail(p, 1).toString());
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            setOpaque(selected);
            return this;
        }
    }

    private static final class Thumb extends JComponent {
        BufferedImage image;

        Thumb() {
            setPreferredSize(new Dimension(80, 45));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int y = (getHeight() - 45) / 2;
            if (image != null) {
                g2.drawImage(image, 0, y, 80, 45, null);
            } else {
                g2.setColor(Palette.monitorBackground());
                g2.fillRect(0, y, 80, 45);
            }
            g2.setColor(Palette.border());
            g2.drawRect(0, y, 79, 44);
            g2.dispose();
        }
    }
}
