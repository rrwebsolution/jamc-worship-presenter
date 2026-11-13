package com.jamc.worship.ui;

import com.jamc.worship.model.FontWeight;
import com.jamc.worship.render.Fonts;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared font controls: Add Font… and a THICKNESS list that shows which weights a font really has. */
public final class FontChoices {

    private FontChoices() {
    }

    /**
     * Add Font…: picks TTF / OTF files (several weights of one font at once), adds them to the app and
     * hands the families added to {@code done}.
     */
    public static void addFonts(Component parent, Consumer<List<String>> done) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Add fonts (select every weight you want, e.g. Light, SemiBold, Bold, Black)");
        chooser.setMultiSelectionEnabled(true);
        FileNameExtensionFilter filter = new FileNameExtensionFilter("Font files (*.ttf, *.otf, *.ttc)", "ttf", "otf", "ttc");
        chooser.addChoosableFileFilter(filter);
        chooser.setFileFilter(filter);
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] files = chooser.getSelectedFiles();
        BackgroundTask.run("Add fonts", () -> Fonts.importFonts(Arrays.stream(files).map(File::toPath).toList()), families -> {
            Dialogs.showInfo(parent, "Fonts added", "Added: " + String.join(", ", families)
                    + "\n\nChoose it under FONT. Each weight file you added (Light, SemiBold, Black…) is a THICKNESS choice.");
            done.accept(families);
        }, err -> Dialogs.showError(parent, "The fonts could not be added.", err));
    }

    /**
     * Refills a font list (e.g. after fonts were added), keeping the selection. {@code before} / {@code after}
     * let the owner ignore the change events this fires.
     */
    public static void refill(JComboBox<String> combo, Runnable before, Runnable after) {
        SwingUtilities.invokeLater(() -> {
            Object keep = combo.getSelectedItem();
            before.run();
            try {
                combo.removeAllItems();
                for (String f : Fonts.pickerFamilies(keep == null ? null : keep.toString())) {
                    combo.addItem(f);
                }
                if (keep != null) {
                    combo.setSelectedItem(keep);
                }
            } finally {
                after.run();
            }
        });
    }

    /**
     * The FONT list shows every name in its own typeface (symbol fonts, which cannot draw their own name,
     * stay in the normal font) and its tooltip says how many fonts the computer has. When the list is shown
     * again it picks up fonts installed in Windows meanwhile.
     */
    /**
     * Every row of the font list the same size, so Swing never measures the whole list. Without it, selecting a font
     * (Settings is built at startup) measured each of the hundreds of names in its own font, loading every font from
     * Windows: the app froze at startup (HangWatchdog: over 3 s in TypographyEditor.selectFamily, 2026-11-13). Now only
     * the rows on screen are drawn, when the list is opened.
     */
    static void fixedRows(JComboBox<String> combo) {
        if (!(combo.getUI().getAccessibleChild(combo, 0) instanceof javax.swing.plaf.basic.ComboPopup popup)) {
            return;
        }
        JList<?> list = popup.getList();
        java.awt.Font big = combo.getFont().deriveFont(combo.getFont().getSize2D() + 2f);
        java.awt.FontMetrics fm = combo.getFontMetrics(big);
        list.setFixedCellHeight(fm.getHeight() + 8); // room for tall script fonts, as the renderer gives them
        list.setFixedCellWidth(Math.max(fm.stringWidth("Segoe UI Semibold Wide Condensed") + 24, combo.getPreferredSize().width));
    }

    public static void familyRenderer(JComboBox<String> combo) {
        java.util.Map<String, java.awt.Font> faces = new java.util.HashMap<>();
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof String name && index >= 0) {
                    java.awt.Font base = list.getFont();
                    java.awt.Font face = faces.computeIfAbsent(name, n -> {
                        java.awt.Font f = new java.awt.Font(n, java.awt.Font.PLAIN, 1).deriveFont(base.getSize2D() + 2f);
                        return f.canDisplayUpTo(n) == -1 ? f : null;
                    });
                    if (face != null) {
                        setFont(face);
                    }
                }
                return this;
            }
        });
        fixedRows(combo);
        combo.addPropertyChangeListener("UI", e -> fixedRows(combo)); // light / dark theme: a new list, sized again
        Runnable count = () -> combo.setToolTipText(combo.getItemCount() + " fonts on this computer (every installed font). "
                + "Each name is shown in its own font.");
        count.run();
        combo.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && combo.isShowing()) {
                // fonts installed in Windows while the app runs: the Fonts listener refills the list
                BackgroundTask.run("Find fonts", Fonts::loadWindowsFonts, added -> count.run(), err -> { });
            }
        });
        Fonts.addListener(() -> SwingUtilities.invokeLater(count));
    }

    /**
     * Shows each THICKNESS with a mark when the chosen font has no face of that weight ("≈ Bold": drawn with the
     * nearest weight it has). Add the font's other weight files to get the real thing.
     */
    public static void weightRenderer(JComboBox<FontWeight> combo, Supplier<String> family) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                String text = String.valueOf(value);
                if (value instanceof FontWeight w && !Fonts.realWeights(family.get()).contains(w)) {
                    text += "  ≈ " + (w.isBold() ? "Bold" : "Regular");
                }
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
        combo.setToolTipText("How thick the lyrics are. \"≈\" = this font has no such weight installed; Add Font… its other weights.");
    }
}
