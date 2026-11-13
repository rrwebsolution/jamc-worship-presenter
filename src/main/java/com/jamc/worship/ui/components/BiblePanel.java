package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.bible.BibleBooks;
import com.jamc.worship.bible.BiblePassage;
import com.jamc.worship.bible.BibleProvider;
import com.jamc.worship.bible.BibleVersion;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.service.BibleService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.util.Json;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Bible browser.
 * <pre>
 *  Version: [ NIV ▼ ]   [KJV] [NLT] [ESV] [NIV] [APSD]
 *  [ John 3:16-18 or words to search ]
 *  Book: [ John ▼ ]  Chapter: [ 3 ▼ ]  ◀ ▶
 *  verses (select one, or Shift-click a range)
 *  ◀ ▶ verse        Preview   Live   Add to Service
 * </pre>
 * Switching version keeps the book, chapter and selected verses whenever the new version has them.
 */
public class BiblePanel extends JPanel {

    /** Operator actions that leave the panel. */
    public interface Actions {
        void preview();

        void live();

        void addToService(ServiceItem item);

        void manageVersions();
    }

    private final BibleService bible;
    private final JComboBox<BibleVersion> versionCombo = new JComboBox<>();
    private final JPanel quickButtons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final Map<String, JToggleButton> quick = new LinkedHashMap<>();
    private final JTextField lookup = new JTextField();
    private final JComboBox<BibleBooks.Book> bookCombo = new JComboBox<>(BibleBooks.all().toArray(BibleBooks.Book[]::new));
    private final JComboBox<Integer> chapterCombo = new JComboBox<>();
    private final DefaultListModel<BibleProvider.Verse> verseModel = new DefaultListModel<>();
    private final JList<BibleProvider.Verse> verseList = new JList<>(verseModel);
    private final DefaultListModel<BibleProvider.Verse> resultModel = new DefaultListModel<>();
    private final JList<BibleProvider.Verse> resultList = new JList<>(resultModel);
    private final JLabel status = Ui.small(" ");
    private final JLabel message = Ui.label(" ", Palette::warning, "small");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private Consumer<BiblePassage> passageListener = p -> { };
    private Consumer<String> statusHandler = s -> { };
    private Actions actions;
    private BibleVersion version;
    private List<BibleVersion> versions = List.of();
    private Map<String, String> problems = Map.of();
    private boolean loading;
    /** The operator chose these verses (click, keys, NEXT, search), rather than the panel opening or restoring them. */
    private boolean picked;
    private boolean verseChanged;
    private boolean installed;
    private BiblePassage pending;
    private long loadSeq;

    public BiblePanel(BibleService bible) {
        super(new BorderLayout());
        this.bible = bible;
        setOpaque(false);

        JButton manage = Ui.toolButton("sliders", "Bible Versions: set up NLT, ESV, NIV, APSD and other versions");
        manage.addActionListener(e -> {
            if (actions != null) actions.manageVersions();
        });
        JPanel top = Ui.transparent(new BorderLayout());
        top.add(Ui.header(Ui.label("Bible", Palette::text, "h3"), status, manage), BorderLayout.NORTH);

        JPanel controls = Ui.transparent(new GridBagLayout());
        controls.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 6, 6);
        controls.add(Ui.small("Version"), g);
        g.gridx = 1;
        g.weightx = 1;
        g.gridwidth = 3;
        versionCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                BibleVersion bv = (BibleVersion) v;
                String text = bv == null ? "" : bv.abbreviation() + " — " + bv.name()
                        + (problems.containsKey(bv.abbreviation()) ? "  (not set up)" : "");
                return super.getListCellRendererComponent(l, text, i, sel, f);
            }
        });
        controls.add(versionCombo, g);
        g.gridy++;
        g.gridx = 0;
        g.gridwidth = 4;
        controls.add(quickButtons, g);
        g.gridy++;
        lookup.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "John 3:16-18  or words to search");
        lookup.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("search", Icons.SMALL));
        lookup.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        lookup.addActionListener(e -> lookup());
        controls.add(lookup, g);
        g.gridy++;
        g.gridwidth = 1;
        g.weightx = 0;
        controls.add(Ui.small("Book"), g);
        g.gridx = 1;
        g.weightx = 1;
        bookCombo.setMaximumRowCount(20);
        controls.add(bookCombo, g);
        g.gridx = 2;
        g.weightx = 0;
        controls.add(Ui.small("Ch."), g);
        g.gridx = 3;
        JPanel chapterRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        chapterCombo.setPrototypeDisplayValue(150);
        chapterRow.add(chapterCombo);
        JButton prevCh = Ui.toolButton("chevron-left", "Previous chapter");
        prevCh.addActionListener(e -> stepChapter(-1));
        JButton nextCh = Ui.toolButton("chevron-right", "Next chapter");
        nextCh.addActionListener(e -> stepChapter(1));
        chapterRow.add(prevCh);
        chapterRow.add(nextCh);
        controls.add(chapterRow, g);
        g.gridy++;
        g.gridx = 0;
        g.gridwidth = 4;
        g.insets = new Insets(0, 0, 0, 0);
        controls.add(message, g);
        top.add(controls, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        verseList.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        verseList.setCellRenderer(new VerseRenderer(false));
        verseList.setToolTipText("Click a verse; Shift-click to select several consecutive verses");
        verseList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !loading) {
                picked = true;
                verseChanged = true;
                firePassage();
            }
        });
        verseList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                // a click on the verse already selected (a list announces only changes) is still a pick
                int at = verseList.locationToIndex(e.getPoint());
                if (!verseChanged && !loading && javax.swing.SwingUtilities.isLeftMouseButton(e) && at >= 0
                        && verseList.isSelectedIndex(at)) {
                    picked = true;
                    firePassage();
                }
                verseChanged = false;
            }
        });
        resultList.setCellRenderer(new VerseRenderer(true));
        resultList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && resultList.getSelectedValue() != null) {
                BibleProvider.Verse v = resultList.getSelectedValue();
                picked = true;
                goTo(new BiblePassage(code(), v.book(), v.chapter(), v.verse(), v.verse()));
            }
        });
        Ui.bindKey(lookup, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "toList", () -> {
            JList<?> l = resultModel.isEmpty() ? verseList : resultList;
            l.requestFocusInWindow();
            if (l.getSelectedIndex() < 0 && l.getModel().getSize() > 0) {
                l.setSelectedIndex(0);
            }
        }, WHEN_FOCUSED);
        verseList.setDragEnabled(true);
        verseList.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected java.awt.datatransfer.Transferable createTransferable(JComponent c) {
                BiblePassage p = selectedPassage();
                return p == null ? null : new ServiceItemTransfer(serviceItem(p));
            }
        });

        JPanel browse = Ui.transparent(new BorderLayout());
        JScrollPane vs = new JScrollPane(verseList);
        vs.setBorder(null);
        browse.add(vs);
        JPanel results = Ui.transparent(new BorderLayout());
        JButton back = Ui.button("Back to chapter", "chevron-left");
        back.addActionListener(e -> {
            resultModel.clear();
            cards.show(body, "browse");
        });
        JPanel rh = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 4));
        rh.add(back);
        results.add(rh, BorderLayout.NORTH);
        JScrollPane rs = new JScrollPane(resultList);
        rs.setBorder(null);
        results.add(rs);
        body.add(browse, "browse");
        body.add(results, "results");
        body.setBorder(Ui.line(true, false, false, false));
        add(body, BorderLayout.CENTER);

        JButton prev = Ui.toolButton("chevron-left", "Previous verse");
        prev.addActionListener(e -> step(-1));
        JButton next = Ui.toolButton("chevron-right", "Next verse");
        next.addActionListener(e -> step(1));
        JButton preview = Ui.button("Preview", "monitor");
        preview.setToolTipText("Send the selected verses to Preview");
        preview.addActionListener(e -> {
            if (actions != null && selectedPassage() != null) actions.preview();
        });
        JButton live = new JButton("Live");
        live.setToolTipText("Send the selected verses straight to Program (Live)");
        live.putClientProperty(FlatClientProperties.STYLE, "font: bold; background: #16A34A; foreground: #FFFFFF");
        live.addActionListener(e -> {
            if (actions != null && selectedPassage() != null) actions.live();
        });
        JButton add = Ui.toolButton("list-plus", "Add the selected verses to the service");
        add.addActionListener(e -> {
            BiblePassage p = selectedPassage();
            if (p != null && actions != null) actions.addToService(serviceItem(p));
        });
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(prev);
        left.add(next);
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(add);
        right.add(preview);
        right.add(live);
        JPanel bar = Ui.transparent(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 6, 6, 8)));
        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        add(bar, BorderLayout.SOUTH);

        bookCombo.addActionListener(e -> {
            if (!loading) loadChapters(1, null);
        });
        chapterCombo.addActionListener(e -> {
            if (!loading && chapterCombo.getSelectedItem() != null) loadVerses(null);
        });
        versionCombo.addActionListener(e -> {
            if (!loading && versionCombo.getSelectedItem() instanceof BibleVersion v) switchVersion(v);
        });
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    public void setPassageListener(Consumer<BiblePassage> l) {
        passageListener = l;
    }

    /** No verses selected (the ✕ on the slide cards): the Bible cards empty. */
    public void clearSelection() {
        verseList.clearSelection();
        firePassage();
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    /** Loads versions (installing the bundled KJV on first use). */
    public void activate() {
        if (installed) {
            return;
        }
        installed = true;
        status.setText("Preparing Bible…");
        reloadVersions(() -> {
            BiblePassage p = pending;
            pending = null;
            if (p != null) {
                goTo(p);
            } else {
                loadChapters(1, null);
            }
            lookup.requestFocusInWindow();
        });
    }

    /** Re-reads versions (after Bible Versions changed) and keeps the current reference. */
    public void versionsChanged() {
        BiblePassage keep = selectedPassage();
        reloadVersions(() -> {
            if (keep != null) {
                goTo(new BiblePassage(code(), keep.book(), keep.chapter(), keep.fromVerse(), keep.toVerse()));
            } else {
                loadVerses(null);
            }
        });
    }

    /** Shows a passage (e.g. from a service item), switching to its version when that version is ready. */
    public void goTo(BiblePassage p) {
        if (!installed || versions.isEmpty()) {
            pending = p;
            activate();
            return;
        }
        versions.stream().filter(v -> v.abbreviation().equalsIgnoreCase(p.translation()) && !problems.containsKey(v.abbreviation()))
                .findFirst().ifPresent(this::selectVersionQuietly);
        loading = true;
        bookCombo.setSelectedItem(BibleBooks.get(p.book()));
        loading = false;
        loadChapters(p.chapter(), p);
    }

    /** Whether the passage just announced was the operator's own choice. Asking clears it. */
    public boolean takePicked() {
        boolean was = picked;
        picked = false;
        return was;
    }

    public BiblePassage selectedPassage() {
        int[] idx = verseList.getSelectedIndices();
        if (idx.length == 0 || version == null) {
            return null;
        }
        BibleProvider.Verse a = verseModel.get(idx[0]);
        BibleProvider.Verse b = verseModel.get(idx[idx.length - 1]);
        return new BiblePassage(code(), a.book(), a.chapter(), a.verse(), b.verse());
    }

    public static ServiceItem serviceItem(BiblePassage p) {
        ServiceItem item = new ServiceItem(ServiceItem.Type.BIBLE, p.referenceWithTranslation(), null);
        item.setPayload(Json.write(p));
        return item;
    }

    /** Moves the selection one verse forward/back, crossing into the next/previous chapter. */
    public void step(int delta) {
        int i = verseList.getSelectedIndex();
        int last = verseList.getMaxSelectionIndex();
        int target = delta > 0 ? last + 1 : i - 1;
        if (i >= 0 && target >= 0 && target < verseModel.size()) {
            verseList.setSelectedIndex(target);
            verseList.ensureIndexIsVisible(target);
            return;
        }
        if (i < 0 && !verseModel.isEmpty()) {
            verseList.setSelectedIndex(0);
            return;
        }
        BibleBooks.Book book = (BibleBooks.Book) bookCombo.getSelectedItem();
        int ch = selectedChapter() + delta;
        if (book != null && ch >= 1 && ch <= BibleBooks.chapterCount(book.number())) {
            int verse = delta > 0 ? 1 : Integer.MAX_VALUE;
            loadChapters(ch, new BiblePassage(code(), book.number(), ch, verse, verse));
        }
    }

    /** Previous / next chapter, crossing into the previous / next book. */
    public void stepChapter(int delta) {
        BibleBooks.Book book = (BibleBooks.Book) bookCombo.getSelectedItem();
        if (book == null) {
            return;
        }
        int ch = selectedChapter() + delta;
        int b = book.number();
        if (ch < 1 && b > 1) {
            b--;
            ch = BibleBooks.chapterCount(b);
        } else if (ch > BibleBooks.chapterCount(b) && b < 66) {
            b++;
            ch = 1;
        }
        if (ch < 1 || ch > BibleBooks.chapterCount(b)) {
            return;
        }
        loading = true;
        bookCombo.setSelectedItem(BibleBooks.get(b));
        loading = false;
        loadChapters(ch, new BiblePassage(code(), b, ch, 1, 1));
    }

    // ------------------------------------------------------------------ versions

    private void reloadVersions(Runnable then) {
        BackgroundTask.run("Load Bible versions", () -> {
            List<BibleVersion> list = bible.ensureInstalled();
            Map<String, String> p = new java.util.HashMap<>();
            for (BibleVersion v : list) {
                String problem = bible.problem(v);
                if (problem != null) {
                    p.put(v.abbreviation(), problem);
                }
            }
            return new Object[]{list, p};
        }, r -> {
            @SuppressWarnings("unchecked")
            List<BibleVersion> list = (List<BibleVersion>) r[0];
            @SuppressWarnings("unchecked")
            Map<String, String> p = (Map<String, String>) r[1];
            versions = list;
            problems = p;
            String keep = version == null ? "KJV" : version.abbreviation();
            loading = true;
            try {
                versionCombo.removeAllItems();
                list.stream().filter(v -> v.enabled() || v.provider() == BibleVersion.Provider.NONE)
                        .forEach(versionCombo::addItem);
                Optional<BibleVersion> again = list.stream().filter(v -> v.abbreviation().equalsIgnoreCase(keep)
                        && !p.containsKey(v.abbreviation())).findFirst();
                version = again.orElse(list.stream().filter(v -> !p.containsKey(v.abbreviation())).findFirst().orElse(null));
                versionCombo.setSelectedItem(version);
            } finally {
                loading = false;
            }
            buildQuickButtons();
            long ready = list.stream().filter(v -> !p.containsKey(v.abbreviation())).count();
            status.setText(ready + (ready == 1 ? " version ready" : " versions ready"));
            then.run();
        }, err -> {
            installed = false;
            status.setText("Bible unavailable");
            Dialogs.showError(this, "The Bible could not be prepared.", err);
        });
    }

    private void buildQuickButtons() {
        quickButtons.removeAll();
        quick.clear();
        ButtonGroup group = new ButtonGroup();
        for (BibleVersion v : versions) {
            if (!v.enabled() && v.provider() != BibleVersion.Provider.NONE) {
                continue; // switched off by the operator
            }
            JToggleButton b = new JToggleButton(v.abbreviation());
            b.putClientProperty(FlatClientProperties.STYLE, "font: bold -1; margin: 2,8,2,8");
            b.setFocusable(false);
            String problem = problems.get(v.abbreviation());
            b.setToolTipText(problem == null ? v.name() + (v.provider().online() ? " (online)" : "") : problem);
            if (problem != null) {
                b.setForeground(Palette.mutedText());
            }
            b.setSelected(version != null && version.abbreviation().equalsIgnoreCase(v.abbreviation()));
            b.addActionListener(e -> switchVersion(v));
            group.add(b);
            quick.put(v.abbreviation(), b);
            quickButtons.add(b);
        }
        quickButtons.revalidate();
        quickButtons.repaint();
    }

    /** Changes version, staying on the same book, chapter and verses when the new version has them. */
    private void switchVersion(BibleVersion v) {
        String problem = problems.get(v.abbreviation());
        if (problem != null) {
            message.setText("<html>" + problem + "</html>");
            selectVersionQuietly(version);
            if (actions != null && Dialogs.confirmDestructive(this, v.abbreviation() + " is not set up", problem
                    + "\n\nOpen Bible Versions now?", "Open Bible Versions")) {
                actions.manageVersions();
            }
            return;
        }
        BiblePassage keep = selectedPassage();
        selectVersionQuietly(v);
        message.setText(" ");
        BiblePassage target = keep != null ? new BiblePassage(v.abbreviation(), keep.book(), keep.chapter(), keep.fromVerse(),
                keep.toVerse()) : null;
        loadChapters(keep != null ? keep.chapter() : selectedChapter(), target);
        statusHandler.accept("Bible version: " + v.abbreviation() + (keep != null ? ", staying at " + target.reference() : ""));
    }

    private void selectVersionQuietly(BibleVersion v) {
        version = v;
        loading = true;
        try {
            versionCombo.setSelectedItem(v);
        } finally {
            loading = false;
        }
        quick.forEach((abbr, b) -> b.setSelected(v != null && abbr.equalsIgnoreCase(v.abbreviation())));
    }

    // ------------------------------------------------------------------ loading

    private void lookup() {
        String text = lookup.getText().strip();
        BibleVersion v = version;
        if (text.isEmpty() || v == null) {
            return;
        }
        BackgroundTask.run("Bible lookup", () -> {
            Optional<BiblePassage> p = bible.resolve(v, text);
            if (p.isPresent()) {
                return (Object) p.get();
            }
            if (!bible.canSearch(v)) {
                return "Word search works for versions stored on this computer. " + v.abbreviation()
                        + " is online: type a reference such as John 3:16 instead.";
            }
            return bible.search(v, text);
        }, r -> {
            if (r instanceof BiblePassage p) {
                resultModel.clear();
                cards.show(body, "browse");
                picked = true; // a reference typed and sent
                goTo(p);
            } else if (r instanceof String msg) {
                message.setText("<html>" + msg + "</html>");
            } else {
                @SuppressWarnings("unchecked")
                List<BibleProvider.Verse> found = (List<BibleProvider.Verse>) r;
                resultModel.clear();
                resultModel.addAll(found);
                cards.show(body, "results");
                statusHandler.accept(found.isEmpty() ? "No verses found for \"" + text + "\""
                        : found.size() + (found.size() >= 200 ? "+" : "") + " verses found in " + v.abbreviation());
            }
        }, err -> message.setText("<html>" + err.getMessage() + "</html>"));
    }

    private void loadChapters(int selectChapter, BiblePassage select) {
        BibleBooks.Book book = (BibleBooks.Book) bookCombo.getSelectedItem();
        if (book == null) {
            return;
        }
        int n = BibleBooks.chapterCount(book.number());
        loading = true;
        try {
            chapterCombo.removeAllItems();
            for (int i = 1; i <= n; i++) {
                chapterCombo.addItem(i);
            }
            chapterCombo.setSelectedItem(Math.clamp(selectChapter, 1, n));
        } finally {
            loading = false;
        }
        loadVerses(select);
    }

    private void loadVerses(BiblePassage select) {
        BibleVersion v = version;
        BibleBooks.Book book = (BibleBooks.Book) bookCombo.getSelectedItem();
        Integer ch = (Integer) chapterCombo.getSelectedItem();
        if (v == null || book == null || ch == null) {
            return;
        }
        long seq = ++loadSeq;
        if (v.provider().online()) {
            status.setText("Loading " + v.abbreviation() + " " + book.name() + " " + ch + "…");
        }
        BackgroundTask.run("Load verses", () -> bible.verses(v, book.number(), ch), verses -> {
            if (seq != loadSeq) {
                return;
            }
            loading = true;
            try {
                verseModel.clear();
                verseModel.addAll(verses);
                if (select != null && !verses.isEmpty()) {
                    int from = indexOfVerse(verses, select.fromVerse(), true);
                    int to = indexOfVerse(verses, select.toVerse(), false);
                    verseList.setSelectionInterval(from, Math.max(from, to));
                    verseList.ensureIndexIsVisible(Math.max(from, to));
                    verseList.ensureIndexIsVisible(from);
                }
            } finally {
                loading = false;
            }
            message.setText(verses.isEmpty() ? v.abbreviation() + " has no text for " + book.name() + " " + ch + "." : " ");
            status.setText(v.abbreviation() + (v.provider().online() ? " (online)" : ""));
            firePassage();
        }, err -> {
            if (seq == loadSeq) {
                message.setText("<html>" + err.getMessage() + "</html>");
                status.setText(v.abbreviation());
            }
        });
    }

    /** Index of the verse number (closest available when that number is missing in this version). */
    private static int indexOfVerse(List<BibleProvider.Verse> verses, int number, boolean first) {
        for (int i = 0; i < verses.size(); i++) {
            if (verses.get(i).verse() >= number) {
                return verses.get(i).verse() == number || first ? i : Math.max(0, i - 1);
            }
        }
        return verses.size() - 1;
    }

    private void firePassage() {
        passageListener.accept(selectedPassage());
    }

    private int selectedChapter() {
        Integer c = (Integer) chapterCombo.getSelectedItem();
        return c == null ? 1 : c;
    }

    private String code() {
        return version == null ? "KJV" : version.abbreviation();
    }

    private static final class VerseRenderer extends DefaultListCellRenderer {
        private final boolean withReference;

        VerseRenderer(boolean withReference) {
            this.withReference = withReference;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
            BibleProvider.Verse v = (BibleProvider.Verse) value;
            String ref = withReference ? "<b>" + BibleBooks.get(v.book()).abbreviation() + " " + v.chapter() + ":" + v.verse()
                    + "</b> " : "<b>" + v.verse() + "</b> ";
            String text = v.text().replace("&", "&amp;").replace("<", "&lt;");
            JLabel l = (JLabel) super.getListCellRendererComponent(list, "<html><div style='width:220px'>" + ref + text
                    + "</div></html>", index, selected, false);
            l.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 8));
            return l;
        }
    }
}
