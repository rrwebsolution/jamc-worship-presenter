package com.jamc.worship.ui.dialogs;

import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.output.Slideshows;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * CREATE IMAGE SLIDESHOW / SLIDESHOW IMAGES: the images (picked from the image library, several at
 * once), their order (drag or Move Up / Down), and how the slideshow plays. Images are referenced by
 * library id; nothing is copied.
 */
public final class SlideshowEditorDialog extends JDialog {

    /** One image in the slideshow; {@code item} is null when the library item is gone. */
    private record Entry(Long id, MediaItem item) {
        String name() {
            return item == null ? "Missing image (library item " + id + ")" : item.getName();
        }

        boolean missing(MediaService media) {
            return item == null || !media.exists(item);
        }
    }

    private final MediaService media;
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    private final JTextField name = new JTextField(24);
    private final JSpinner seconds = new JSpinner(new SpinnerNumberModel(Slideshows.DEFAULT_SECONDS, 1, 300, 1));
    private final JComboBox<Slideshows.Transition> transition = new JComboBox<>(Slideshows.Transition.values());
    private final JSpinner transitionMs = new JSpinner(new SpinnerNumberModel(Slideshows.DEFAULT_TRANSITION_MS, 0, 5000, 100));
    private final JCheckBox autoplay = new JCheckBox("Auto Play");
    private final JCheckBox loop = new JCheckBox("Loop");
    private final JCheckBox shuffle = new JCheckBox("Shuffle");
    private final JComboBox<String> onProgram = new JComboBox<>(new String[]{"Program shows the Preview image", "Program starts from the first image"});
    private final JLabel countLabel = Ui.small("");
    private final JLabel previewLabel = Ui.small(" ");
    private BufferedImage previewImage;
    private boolean saved;

    private SlideshowEditorDialog(Window owner, MediaService media, String title, String sourceName, SourceConfig c,
                                  String saveText) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.media = media;
        JPanel root = Ui.panel(new BorderLayout(14, 10), Palette::panelBackground);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.add(Ui.label(title.toUpperCase(), Palette::text, "h3"), BorderLayout.NORTH);

        // ---- images and their order
        list.setCellRenderer(new Row());
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setFixedCellHeight(52);
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new Reorder());
        list.addListSelectionListener(e -> showSelected());
        Rendering.renderer().images().addChangeListener(list::repaint);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(380, 330));
        JPanel left = Ui.transparent(new BorderLayout(0, 6));
        JPanel listHead = Ui.transparent(new BorderLayout());
        listHead.add(Ui.label("SLIDESHOW ORDER", Palette::secondaryText, "semibold"), BorderLayout.WEST);
        listHead.add(countLabel, BorderLayout.EAST);
        left.add(listHead, BorderLayout.NORTH);
        left.add(scroll, BorderLayout.CENTER);
        JPanel listButtons = Ui.transparent(new GridLayout(2, 4, 4, 4));
        listButtons.add(button("ADD IMAGES", "plus", "Pick several images at once from the image library", this::addImages));
        listButtons.add(button("UP", "arrow-up", "Move the selected images up", () -> move(-1)));
        listButtons.add(button("DOWN", "arrow-down", "Move the selected images down", () -> move(1)));
        listButtons.add(button("DUPLICATE", "copy", "Show the selected image twice", this::duplicate));
        listButtons.add(button("REPLACE", "image", "Replace the selected image with another one", this::replace));
        listButtons.add(button("REMOVE", "trash", "Remove the selected images", this::remove));
        listButtons.add(button("REMOVE MISSING", "x", "Remove images that are no longer in the library", this::removeMissing));
        listButtons.add(button("CLEAR", "x", "Remove every image", () -> { model.clear(); changed(); }));
        left.add(listButtons, BorderLayout.SOUTH);
        root.add(left, BorderLayout.CENTER);

        // ---- the selected image and the slideshow settings
        JComponent picture = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (previewImage != null) {
                    double s = Math.min(getWidth() / (double) previewImage.getWidth(), getHeight() / (double) previewImage.getHeight());
                    int w = (int) (previewImage.getWidth() * s);
                    int h = (int) (previewImage.getHeight() * s);
                    g2.drawImage(previewImage, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
                }
                g2.dispose();
            }
        };
        picture.setPreferredSize(new Dimension(288, 162));
        Rendering.renderer().images().addChangeListener(this::showSelected);

        JPanel settings = Ui.transparent(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, 6, 8);
        g.gridy = 0;
        g.gridwidth = 2;
        settings.add(picture, g);
        g.gridy++;
        settings.add(previewLabel, g);
        g.gridwidth = 1;
        field(settings, g, "Name", name);
        field(settings, g, "Auto Play every (s)", seconds);
        field(settings, g, "Transition", transition);
        field(settings, g, "Transition (ms)", transitionMs);
        JPanel boxes = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        boxes.add(autoplay);
        boxes.add(loop);
        boxes.add(shuffle);
        field(settings, g, "Playback", boxes);
        field(settings, g, "Scene TAKE", onProgram);
        transition.addActionListener(e -> transitionMs.setEnabled(transition.getSelectedItem() != Slideshows.Transition.CUT));
        root.add(settings, BorderLayout.EAST);

        JButton cancel = Ui.button("CANCEL", null);
        cancel.addActionListener(e -> dispose());
        JButton save = Ui.primaryButton(saveText, "plus");
        save.addActionListener(e -> {
            if (model.isEmpty()) {
                previewLabel.setText("Add at least one image.");
                previewLabel.setForeground(Palette.warning());
                return;
            }
            saved = true;
            dispose();
        });
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(save);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);

        // ---- current values
        name.setText(sourceName);
        if (c.imageIds != null) {
            for (Long id : c.imageIds) {
                model.addElement(new Entry(id, id == null ? null : media.load(id).orElse(null)));
            }
        }
        seconds.setValue(c.slideSeconds == null ? Slideshows.DEFAULT_SECONDS : Math.clamp(c.slideSeconds, 1, 300));
        transition.setSelectedItem(Slideshows.Transition.of(c));
        transitionMs.setValue(c.slideTransitionMs == null ? Slideshows.DEFAULT_TRANSITION_MS : c.slideTransitionMs);
        autoplay.setSelected(Boolean.TRUE.equals(c.autoplay)); // manual (Previous / Next) unless turned on
        loop.setSelected(c.loop == null || c.loop);
        shuffle.setSelected(Boolean.TRUE.equals(c.shuffle));
        onProgram.setSelectedIndex(Boolean.TRUE.equals(c.restartOnProgram) ? 1 : 0);
        autoplay.setToolTipText("Off (default): Previous / Next. On: Preview advances by itself; Program changes only at TAKE.");
        changed();
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the editor. On save, writes the images and settings into {@code c} and returns the name;
     * returns null when cancelled ({@code c} unchanged).
     */
    public static String edit(Component parent, MediaService media, String title, String sourceName, SourceConfig c,
                              String saveText) {
        SlideshowEditorDialog d = new SlideshowEditorDialog(SwingUtilities.getWindowAncestor(parent), media, title,
                sourceName, c, saveText);
        if (c.imageIds == null || c.imageIds.isEmpty()) {
            SwingUtilities.invokeLater(d::addImages); // a new slideshow starts with the image picker
        }
        d.setVisible(true);
        if (!d.saved) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < d.model.size(); i++) {
            ids.add(d.model.get(i).id());
        }
        c.imageIds = ids;
        int s = ((Number) d.seconds.getValue()).intValue();
        c.slideSeconds = s == Slideshows.DEFAULT_SECONDS ? null : s;
        Slideshows.Transition t = (Slideshows.Transition) d.transition.getSelectedItem();
        c.slideTransition = t == Slideshows.Transition.FADE ? null : t.name();
        int ms = ((Number) d.transitionMs.getValue()).intValue();
        c.slideTransitionMs = ms == Slideshows.DEFAULT_TRANSITION_MS ? null : ms;
        c.autoplay = d.autoplay.isSelected() ? Boolean.TRUE : null;
        c.loop = d.loop.isSelected() ? null : Boolean.FALSE;
        c.shuffle = d.shuffle.isSelected() ? Boolean.TRUE : null;
        c.restartOnProgram = d.onProgram.getSelectedIndex() == 1 ? Boolean.TRUE : null;
        String n = d.name.getText().strip();
        return n.isEmpty() ? sourceName : n;
    }

    // ------------------------------------------------------------------ list actions

    private void addImages() {
        List<MediaItem> picked = pickImages(this, true);
        if (picked.isEmpty()) {
            return;
        }
        int at = list.getMaxSelectionIndex() < 0 ? model.size() : list.getMaxSelectionIndex() + 1;
        for (MediaItem m : picked) {
            model.add(at++, new Entry(m.getId(), m));
        }
        list.setSelectionInterval(at - picked.size(), at - 1);
        changed();
    }

    private void replace() {
        int i = list.getSelectedIndex();
        if (i < 0) {
            return;
        }
        List<MediaItem> picked = pickImages(this, false);
        if (!picked.isEmpty()) {
            model.set(i, new Entry(picked.getFirst().getId(), picked.getFirst()));
            list.setSelectedIndex(i);
            changed();
        }
    }

    private void move(int delta) {
        int[] sel = list.getSelectedIndices();
        if (sel.length == 0 || sel[0] + delta < 0 || sel[sel.length - 1] + delta >= model.size()) {
            return;
        }
        List<Entry> moving = new ArrayList<>();
        for (int i = sel.length - 1; i >= 0; i--) {
            moving.addFirst(model.remove(sel[i]));
        }
        int at = sel[0] + delta;
        for (Entry e : moving) {
            model.add(at++, e);
        }
        list.setSelectionInterval(sel[0] + delta, sel[0] + delta + moving.size() - 1);
        changed();
    }

    private void duplicate() {
        int i = list.getSelectedIndex();
        if (i >= 0) {
            model.add(i + 1, model.get(i));
            list.setSelectedIndex(i + 1);
            changed();
        }
    }

    private void remove() {
        int[] sel = list.getSelectedIndices();
        for (int i = sel.length - 1; i >= 0; i--) {
            model.remove(sel[i]);
        }
        changed();
    }

    private void removeMissing() {
        for (int i = model.size() - 1; i >= 0; i--) {
            if (model.get(i).missing(media)) {
                model.remove(i);
            }
        }
        changed();
    }

    private void changed() {
        int missing = 0;
        for (int i = 0; i < model.size(); i++) {
            missing += model.get(i).missing(media) ? 1 : 0;
        }
        countLabel.setText(model.size() + (model.size() == 1 ? " image" : " images")
                + (missing > 0 ? "  ·  ⚠ " + missing + " missing (skipped)" : ""));
        countLabel.setForeground(missing > 0 ? Palette.warning() : Palette.secondaryText());
        list.repaint();
    }

    private void showSelected() {
        Entry e = list.getSelectedValue();
        previewImage = e == null || e.item() == null ? null : Rendering.renderer().images().get(e.item().getPath());
        previewLabel.setForeground(Palette.secondaryText());
        previewLabel.setText(e == null ? " " : e.name());
        repaint();
    }

    /** The image library with ticks (several) or one image (REPLACE). */
    private List<MediaItem> pickImages(Component parent, boolean many) {
        List<MediaCategory> cats = List.of(MediaCategory.IMAGE, MediaCategory.BACKGROUND);
        LibraryPickerDialog.Spec<MediaItem> spec = new LibraryPickerDialog.Spec<>(
                many ? "Add Images" : "Replace Image", "Search images…", "image",
                "No images in the library yet.<br>Import them in Library → Images first.",
                text -> media.find(cats, text, false).stream().filter(m -> m.getKind() == MediaKind.IMAGE).toList(),
                MediaItem::getName,
                m -> (m.getWidth() == null ? "image" : m.getWidth() + "×" + m.getHeight()),
                m -> Rendering.renderer().images().get(m.getPath()));
        if (many) {
            return LibraryPickerDialog.pickMany(parent, spec, "images");
        }
        MediaItem one = LibraryPickerDialog.pick(parent, spec);
        return one == null ? List.of() : List.of(one);
    }

    private static JButton button(String text, String icon, String tip, Runnable action) {
        JButton b = Ui.button(text, icon);
        b.setToolTipText(tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static void field(JPanel p, GridBagConstraints g, String label, JComponent c) {
        g.gridy++;
        g.gridx = 0;
        p.add(Ui.small(label), g);
        g.gridx = 1;
        p.add(c, g);
    }

    /** "☰ 3. Welcome.jpg" with a thumbnail; missing images in the warning colour. */
    private final class Row extends JPanel implements ListCellRenderer<Entry> {
        private final JLabel text = Ui.label(" ", Palette::text, null);
        private BufferedImage thumb;

        Row() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            JLabel handle = new JLabel(Icons.get("list", Icons.SMALL, Palette::mutedText));
            JComponent picture = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(Color.BLACK);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    if (thumb != null) {
                        double s = Math.min(getWidth() / (double) thumb.getWidth(), getHeight() / (double) thumb.getHeight());
                        int w = (int) (thumb.getWidth() * s);
                        int h = (int) (thumb.getHeight() * s);
                        g.drawImage(thumb, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
                    }
                }
            };
            picture.setPreferredSize(new Dimension(70, 40));
            JPanel west = Ui.transparent(new BorderLayout(6, 0));
            west.add(handle, BorderLayout.WEST);
            west.add(picture, BorderLayout.CENTER);
            add(west, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> l, Entry e, int index, boolean selected, boolean focus) {
            boolean missing = e.missing(media);
            thumb = missing ? null : Rendering.renderer().images().get(e.item().getPath());
            text.setText((index + 1) + ".  " + e.name() + (missing && e.item() != null ? "  (file missing)" : ""));
            text.setForeground(missing ? Palette.warning() : selected ? l.getSelectionForeground() : Palette.text());
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            setOpaque(true);
            return this;
        }
    }

    /** Drag-and-drop reordering inside the list. */
    private final class Reorder extends TransferHandler {
        private int[] dragged;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            dragged = list.getSelectedIndices();
            return new StringSelection("slideshow");
        }

        @Override
        public boolean canImport(TransferSupport s) {
            return dragged != null && s.isDrop() && s.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport s) {
            if (!canImport(s)) {
                return false;
            }
            int at = ((JList.DropLocation) s.getDropLocation()).getIndex();
            List<Entry> moving = new ArrayList<>();
            for (int i = dragged.length - 1; i >= 0; i--) {
                if (dragged[i] < at) {
                    at--;
                }
                moving.addFirst(model.remove(dragged[i]));
            }
            for (Entry e : moving) {
                model.add(at++, e);
            }
            list.setSelectionInterval(at - moving.size(), at - 1);
            dragged = null;
            changed();
            return true;
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            dragged = null;
        }
    }
}
