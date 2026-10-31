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
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
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
