package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.ContentKind;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideDeck;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.Theme;
import com.jamc.worship.service.SlideDeckService;
import com.jamc.worship.service.ThemeService;
import com.jamc.worship.service.ValidationException;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Center editor for a custom slide deck: large live preview, text and caption fields, and full
 * per-slide styling over the deck's theme. Drives the bottom thumbnail strip in editable mode
 * (add, duplicate, delete, drag to reorder).
 *
 * <p>Changes autosave shortly after the operator stops typing. Only one save runs at a time and
 * each save writes a snapshot taken on the event thread, so a slow disk can never interleave
 * or lose edits.
 */
public class SlideEditorPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(SlideEditorPanel.class);

    /** Theme choice in the deck's theme combo; a null id means "use the default theme". */
    private record ThemeChoice(Long id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** Lets the library list show the new name / slide count after a save. */
    public interface SummaryListener {
        void deckSaved(long id, String name, int slideCount);
    }

    private final SlideDeckService deckService;
    private final ThemeService themeService;
    private final SlideThumbnailStrip strip;

    private final JLabel nameLabel = Ui.label(" ", Palette::text, "h2");
    private final JLabel saveState = Ui.small(" ");
    private final JComboBox<ThemeChoice> themeCombo = new JComboBox<>();
    private final SlidePreviewCanvas preview = new SlidePreviewCanvas();
    private final JTextField labelField = new JTextField();
    private final JTextArea textArea = new JTextArea();
    private final JTextField captionField = new JTextField();
    private final StyleForm form;
    private final JLabel overrideInfo = Ui.small(" ");
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private final Timer autosave;

    private SlideDeck deck;
    private List<Slide> slides = new ArrayList<>();
    private List<Theme> themes = List.of();
    private Theme defaultTheme;
    private SlideStyle base = SlideStyle.fallback();
    private String savedName;
    private int index = -1;
    private boolean attached;
    private boolean loadingFields;
    private boolean dirty;
    private boolean saving;
    private boolean pendingSave;
    private long openSeq;

    private Consumer<String> statusHandler = s -> { };
    private SummaryListener summaryListener = (id, name, count) -> { };
    private Runnable themesChanged = () -> { };
    private Consumer<Slide> currentSlideListener = s -> { };

    public SlideEditorPanel(SlideDeckService deckService, ThemeService themeService,
                            StyleForm.ImageImporter importer, SlideThumbnailStrip strip) {
        super(new BorderLayout());
        this.deckService = deckService;
        this.themeService = themeService;
        this.strip = strip;
        this.form = new StyleForm(importer);
        setOpaque(false);

        body.add(buildEditor(), "editor");
        JLabel empty = Ui.label("<html><center>Select a slide deck on the left,<br>or click + to create one.</center></html>",
                Palette::mutedText, null);
        empty.setHorizontalAlignment(JLabel.CENTER);
        body.add(empty, "empty");
        cards.show(body, "empty");
        add(body, BorderLayout.CENTER);

        autosave = new Timer(800, e -> save());
        autosave.setRepeats(false);

        strip.addSelectionListener(i -> {
            if (attached) {
                showSlide(i);
            }
        });
        wireFields();
    }

    // ------------------------------------------------------------------ layout

    private JComponent buildEditor() {
        JButton rename = Ui.toolButton("edit", "Rename deck");
        rename.addActionListener(e -> rename());
        themeCombo.setPrototypeDisplayValue(new ThemeChoice(null, "Default theme (Worship Dark)"));
        themeCombo.setToolTipText("Theme used by every slide in this deck");
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(saveState);
        right.add(Ui.small("Theme"));
        right.add(themeCombo);
        JPanel titleRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        titleRow.add(nameLabel);
        titleRow.add(rename);
        JPanel header = Ui.transparent(new BorderLayout());
        header.add(titleRow, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(10, 12, 8, 12)));

        preview.setBadge("SLIDE", Palette::mutedText);
        preview.setEmptyMessage("Empty slide");
        JPanel previewHolder = Ui.transparent(new BorderLayout());
        previewHolder.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
        previewHolder.add(preview);
        previewHolder.setMinimumSize(new Dimension(200, 140));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Text", buildTextTab());
        tabs.addTab("Style", buildStyleTab());
        tabs.setMinimumSize(new Dimension(200, 150));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, previewHolder, tabs);
        split.setResizeWeight(0.55);
        split.setBorder(null);
        split.setContinuousLayout(true);

        JPanel editor = Ui.transparent(new BorderLayout());
        editor.add(header, BorderLayout.NORTH);
        editor.add(split, BorderLayout.CENTER);
        return editor;
    }

    private JComponent buildTextTab() {
        JPanel p = Ui.panel(new GridBagLayout(), Palette::panelBackground);
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 6, 10);
        p.add(Ui.label("Label", Palette::secondaryText, "small"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 6, 0);
        labelField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Shown under the thumbnail, e.g. Welcome");
        p.add(labelField, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(4, 0, 6, 10);
        p.add(Ui.label("Text", Palette::secondaryText, "small"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 6, 0);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(textArea.getFont().deriveFont(14f));
        textArea.setMargin(new Insets(6, 8, 6, 8));
        JScrollPane textScroll = new JScrollPane(textArea);
        textScroll.setPreferredSize(new Dimension(200, 80));
        p.add(textScroll, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 10);
        p.add(Ui.label("Caption", Palette::secondaryText, "small"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 0, 0);
        captionField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Optional smaller line, e.g. a date or reference");
        p.add(captionField, c);
        return p;
    }

    private JComponent buildStyleTab() {
        JPanel p = Ui.panel(new BorderLayout(0, 10), Palette::panelBackground);
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        p.add(form, BorderLayout.CENTER);

        JButton reset = Ui.button("Reset to Theme", null);
        reset.setToolTipText("Remove this slide's own styling so it follows the deck theme again");
        reset.addActionListener(e -> resetStyle());
        JButton saveAsTheme = Ui.button("Save as Theme…", "palette");
        saveAsTheme.setToolTipText("Create a reusable theme from this slide's look");
        saveAsTheme.addActionListener(e -> saveAsTheme());
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(reset);
        buttons.add(javax.swing.Box.createHorizontalStrut(8));
        buttons.add(saveAsTheme);
        JPanel top = Ui.transparent(new BorderLayout(0, 6));
        top.add(buttons, BorderLayout.NORTH);
        top.add(overrideInfo, BorderLayout.SOUTH);
        p.add(top, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(p);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void wireFields() {
        textArea.getDocument().addDocumentListener(onChange(() -> editCurrent(s -> s.setText(textArea.getText()))));
        labelField.getDocument().addDocumentListener(onChange(() -> editCurrent(s -> s.setLabel(labelField.getText()))));
        captionField.getDocument().addDocumentListener(onChange(() -> editCurrent(
                s -> s.setCaption(captionField.getText().isBlank() ? null : captionField.getText()))));
        form.addChangeListener(() -> editCurrent(s -> {
            StyleOverrides o = StyleOverrides.diff(base, form.getStyle());
            s.setOverrides(o.isEmpty() ? null : o);
        }));
        themeCombo.addActionListener(e -> {
            if (loadingFields || deck == null) {
                return;
            }
            ThemeChoice choice = (ThemeChoice) themeCombo.getSelectedItem();
            deck.setThemeId(choice == null ? null : choice.id());
            base = resolveBase();
            strip.setStyle(base);
            showSlide(index);
            markDirty();
        });
    }

    // ------------------------------------------------------------------ public API

    public void setStatusHandler(Consumer<String> statusHandler) {
        this.statusHandler = statusHandler;
    }

    public void setSummaryListener(SummaryListener summaryListener) {
        this.summaryListener = summaryListener;
    }

    /** Called after "Save as Theme" creates a theme. */
    public void setThemesChangedHandler(Runnable themesChanged) {
        this.themesChanged = themesChanged;
    }

    /** Notified whenever the selected slide (or its look) changes, e.g. to update the Preview monitor. */
    public void setCurrentSlideListener(Consumer<Slide> listener) {
        this.currentSlideListener = listener;
    }

    public Slide currentSlide() {
        return index >= 0 && index < slides.size() ? slides.get(index) : null;
    }

    public SlideStyle baseStyle() {
        return base;
    }

    /** Name of the open deck, or null. */
    public String deckName() {
        return deck == null ? null : deck.getName();
    }

    /** Opens a deck (saving the current one first). Null shows the empty state. */
    public void open(Long deckId) {
        save();
        long seq = ++openSeq;
        if (deckId == null) {
            deck = null;
            slides = new ArrayList<>();
            index = -1;
            cards.show(body, "empty");
            pushToStrip(0);
            return;
        }
        if (deck != null && deck.getId().equals(deckId)) {
            return;
        }
        BackgroundTask.run("Open slide deck", () -> new Object[]{deckService.load(deckId).orElse(null),
                themeService.list(), themeService.defaultTheme()}, r -> {
            if (seq != openSeq) {
                return;
            }
            SlideDeck loaded = (SlideDeck) r[0];
            @SuppressWarnings("unchecked")
            List<Theme> loadedThemes = (List<Theme>) r[1];
            if (loaded == null) {
                open(null);
                return;
            }
            deck = loaded;
            savedName = loaded.getName();
            slides = new ArrayList<>(loaded.getSlides());
            if (slides.isEmpty()) {
                slides.add(new Slide("Slide 1", "", null));
            }
            deck.setSlides(slides);
            themes = loadedThemes;
            defaultTheme = (Theme) r[2];
            dirty = false;
            fillThemeCombo();
            base = resolveBase();
            nameLabel.setText(deck.getName());
            saveState.setText("Saved");
            cards.show(body, "editor");
            index = -1;
            pushToStrip(0);
        }, err -> Dialogs.showError(this, "Could not open the slide deck.", err));
    }

    /** Forgets a deck that is about to be deleted, without saving it. */
    public void discard(long deckId) {
        if (deck != null && deck.getId() == deckId) {
            autosave.stop();
            dirty = false;
            pendingSave = false;
            deck = null;
            slides = new ArrayList<>();
            index = -1;
            cards.show(body, "empty");
            pushToStrip(0);
        }
    }

    /** Makes the bottom strip show and edit this deck's slides. */
    public void attach() {
        attached = true;
        strip.setTitle("Deck Slides");
        strip.setEmptyMessage("Select or create a slide deck.");
        strip.setEditHandler(new StripHandler());
        pushToStrip(Math.max(index, 0));
    }

    public void detach() {
        attached = false;
        save();
        strip.setEditHandler(null);
    }

    /** Re-reads themes (after a theme was created, edited or deleted) and restyles the open deck. */
    public void reloadThemes() {
        if (deck == null) {
            return;
        }
        BackgroundTask.run("Reload themes", () -> new Object[]{themeService.list(), themeService.defaultTheme()}, r -> {
            @SuppressWarnings("unchecked")
            List<Theme> t = (List<Theme>) r[0];
            themes = t;
            defaultTheme = (Theme) r[1];
            if (deck == null) {
                return;
            }
            if (deck.getThemeId() != null && themes.stream().noneMatch(x -> x.getId().equals(deck.getThemeId()))) {
                deck.setThemeId(null); // theme was deleted: the database already set it to null
            }
            fillThemeCombo();
            base = resolveBase();
            if (attached) {
                strip.setStyle(base);
            }
            showSlide(index);
        }, err -> log.warn("Could not reload themes", err));
    }

    /** Saves now if there are unsaved changes (asynchronously). */
    public void save() {
        autosave.stop();
        if (deck == null || !dirty) {
            return;
        }
        if (saving) {
            pendingSave = true;
            return;
        }
        SlideDeck snapshot = new SlideDeck();
        snapshot.setId(deck.getId());
        snapshot.setName(deck.getName());
        snapshot.setThemeId(deck.getThemeId());
        snapshot.setSlides(slides.stream().map(Slide::copy).toList());
        long deckId = deck.getId();
        saving = true;
        dirty = false;
        saveState.setText("Saving…");
        BackgroundTask.run("Save slide deck", () -> deckService.save(snapshot), saved -> {
            saving = false;
            boolean sameDeck = deck != null && deck.getId() == deckId;
            if (sameDeck) {
                savedName = saved.getName();
                saveState.setText(dirty ? "Unsaved changes" : "Saved");
            }
            summaryListener.deckSaved(deckId, saved.getName(), saved.getSlides().size());
            if (pendingSave || (sameDeck && dirty)) {
                pendingSave = false;
                save(); // edits made while saving; save() is a no-op if nothing is dirty
            }
        }, err -> {
            saving = false;
            boolean sameDeck = deck != null && deck.getId() == deckId;
            if (!sameDeck) {
                log.warn("Save of closed deck #{} failed", deckId, err);
                return;
            }
            dirty = true;
            saveState.setText("Not saved");
            if (err instanceof ValidationException && !Objects.equals(deck.getName(), savedName)) {
                deck.setName(savedName);
                nameLabel.setText(savedName);
            }
            Dialogs.showError(this, "The slide deck could not be saved.", err);
        });
    }

    /** Saves synchronously; used while the application is closing. */
    public void saveBlocking() {
        autosave.stop();
        if (deck != null && dirty) {
            SlideDeck snapshot = new SlideDeck();
            snapshot.setId(deck.getId());
            snapshot.setName(deck.getName());
            snapshot.setThemeId(deck.getThemeId());
            snapshot.setSlides(slides.stream().map(Slide::copy).toList());
            deckService.save(snapshot);
            dirty = false;
        }
    }

    // ------------------------------------------------------------------ editing

    private void showSlide(int i) {
        index = i;
        Slide s = currentSlide();
        loadingFields = true;
        try {
            boolean has = s != null;
            labelField.setEnabled(has);
            textArea.setEnabled(has);
            captionField.setEnabled(has);
            form.setEnabled(has);
            labelField.setText(has ? nz(s.getLabel()) : "");
            textArea.setText(has ? s.getText() : "");
            textArea.setCaretPosition(0);
            captionField.setText(has ? nz(s.getCaption()) : "");
            form.setStyle(has ? s.styleOver(base) : base);
        } finally {
            loadingFields = false;
        }
        updateOverrideInfo();
        preview.setContent(s, base);
        preview.setBadge(s == null ? "SLIDE" : "SLIDE " + (i + 1) + " OF " + slides.size(), Palette::mutedText);
        currentSlideListener.accept(s);
    }

    private void editCurrent(Consumer<Slide> change) {
        Slide s = currentSlide();
        if (loadingFields || s == null) {
            return;
        }
        change.accept(s);
        preview.setContent(s, base);
        strip.refreshSlide(index);
        updateOverrideInfo();
        currentSlideListener.accept(s);
        markDirty();
    }

    private void markDirty() {
        dirty = true;
        saveState.setText("Unsaved changes");
        autosave.restart();
    }

    private void resetStyle() {
        Slide s = currentSlide();
        if (s == null || s.getOverrides() == null) {
            return;
        }
        s.setOverrides(null);
        showSlide(index);
        strip.refreshSlide(index);
        markDirty();
    }

    private void saveAsTheme() {
        Slide s = currentSlide();
        if (s == null) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Name for the new theme:", "Save as Theme",
                JOptionPane.PLAIN_MESSAGE, null, null, deck.getName() + " Theme");
        if (name == null || name.toString().isBlank()) {
            return;
        }
        SlideStyle style = s.styleOver(base);
        BackgroundTask.run("Save as theme", () -> themeService.save(new Theme(null, name.toString(), false, style)), t -> {
            statusHandler.accept("Created theme \"" + t.getName() + "\"");
            themesChanged.run();
            reloadThemes();
        }, err -> Dialogs.showError(this, "Could not create the theme.", err));
    }

    private void rename() {
        if (deck == null) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Deck name:", "Rename Slide Deck",
                JOptionPane.PLAIN_MESSAGE, null, null, deck.getName());
        if (name == null || name.toString().isBlank() || name.toString().strip().equals(deck.getName())) {
            return;
        }
        deck.setName(name.toString().strip());
        nameLabel.setText(deck.getName());
        markDirty();
        save();
    }

    private void updateOverrideInfo() {
        Slide s = currentSlide();
        boolean custom = s != null && s.getOverrides() != null && !s.getOverrides().isEmpty();
        overrideInfo.setText(custom ? "This slide has its own styling. Other slides follow the deck theme."
                : "This slide follows the deck theme. Any change here applies to this slide only.");
    }

    private void pushToStrip(int select) {
        if (attached) {
            strip.setTitle(deck == null ? "Deck Slides" : deck.getName());
            strip.setSlides(slides, base, select);
            if (slides.isEmpty()) {
                showSlide(-1);
            }
        }
    }

    private void fillThemeCombo() {
        loadingFields = true;
        try {
            themeCombo.removeAllItems();
            String def = defaultTheme == null ? "" : " (" + defaultTheme.getName() + ")";
            themeCombo.addItem(new ThemeChoice(null, "Default theme" + def));
            ThemeChoice selected = null;
            for (Theme t : themes) {
                ThemeChoice c = new ThemeChoice(t.getId(), t.getName());
                themeCombo.addItem(c);
                if (t.getId().equals(deck.getThemeId())) {
                    selected = c;
                }
            }
            themeCombo.setSelectedIndex(0);
            if (selected != null) {
                themeCombo.setSelectedItem(selected);
            }
        } finally {
            loadingFields = false;
        }
    }

    private SlideStyle resolveBase() {
        if (deck != null && deck.getThemeId() != null) {
            for (Theme t : themes) {
                if (t.getId().equals(deck.getThemeId())) {
                    return themeService.styleFor(ContentKind.CUSTOM_SLIDES, t);
                }
            }
        }
        return defaultTheme == null ? SlideStyle.fallback() : themeService.styleFor(ContentKind.CUSTOM_SLIDES, defaultTheme);
    }

    private final class StripHandler implements SlideThumbnailStrip.EditHandler {
        @Override
        public void add(int afterIndex) {
            if (deck == null) {
                return;
            }
            int at = Math.clamp(afterIndex + 1, 0, slides.size());
            slides.add(at, new Slide("Slide " + (slides.size() + 1), "", null));
            pushToStrip(at);
            markDirty();
            SwingUtilities.invokeLater(textArea::requestFocusInWindow);
        }

        @Override
        public void duplicate(int i) {
            if (i < 0 || i >= slides.size()) {
                return;
            }
            slides.add(i + 1, slides.get(i).copy());
            pushToStrip(i + 1);
            markDirty();
        }

        @Override
        public void delete(int i) {
            if (i < 0 || i >= slides.size() || slides.size() <= 1) {
                return;
            }
            Slide s = slides.get(i);
            if (!s.getText().isBlank() && !Dialogs.confirmDestructive(SlideEditorPanel.this, "Delete slide",
                    "Delete slide " + (i + 1) + (s.getLabel() == null ? "" : " (" + s.getLabel() + ")") + "?", "Delete")) {
                return;
            }
            slides.remove(i);
            pushToStrip(Math.min(i, slides.size() - 1));
            markDirty();
        }

        @Override
        public void move(int from, int to) {
            if (from < 0 || from >= slides.size() || to < 0 || to >= slides.size() || from == to) {
                return;
            }
            slides.add(to, slides.remove(from));
            pushToStrip(to);
            markDirty();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }
}
