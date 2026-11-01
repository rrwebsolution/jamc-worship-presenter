package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.service.ValidationException;
import com.jamc.worship.ui.BrandingManager;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Settings → Branding: the application name, an optional short name and the app icon / logo, with a
 * live preview. Nothing changes until SAVE CHANGES; CANCEL goes back to what is saved. Branding is the
 * app's identity only: songs, Bible, media, scenes and services are never touched.
 */
public class BrandingSettingsPanel extends JPanel {

    private final BrandingManager branding;
    private final JTextField name = new JTextField(28);
    private final JTextField shortName = new JTextField(18);
    private final LogoView logoView = new LogoView(96);
    private final LogoView previewLogo = new LogoView(32);
    private final JLabel previewName = Ui.label("", Palette::text, "h4");
    private final JLabel logoNote = Ui.small("");
    /** A newly chosen logo (shown, not saved yet), or null. */
    private Path pendingLogo;
    private BufferedImage pendingImage;
    /** The operator chose the built-in icon again (applied on save). */
    private boolean pendingDefaultLogo;

    public BrandingSettingsPanel(BrandingManager branding) {
        super(new GridBagLayout());
        this.branding = branding;
        setOpaque(false);
        name.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, BrandingManager.DEFAULT_NAME);
        shortName.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, BrandingManager.DEFAULT_SHORT_NAME);
        shortName.setToolTipText("Used where space is short, e.g. the projector window title");

        JButton change = Ui.button("CHANGE ICON", "image");
        change.addActionListener(e -> chooseLogo());
        JButton defaultIcon = Ui.button("USE DEFAULT ICON", "refresh");
        defaultIcon.addActionListener(e -> {
            pendingLogo = null;
            pendingImage = null;
            pendingDefaultLogo = true;
            updatePreview();
        });
        JButton reset = Ui.button("RESET DEFAULT", "refresh");
        reset.addActionListener(e -> resetDefault());
        JButton cancel = Ui.button("CANCEL", null);
        cancel.addActionListener(e -> load());
        JButton save = Ui.primaryButton("SAVE CHANGES", null);
        save.addActionListener(e -> save());

        DocumentListener live = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updatePreview(); }
            @Override public void removeUpdate(DocumentEvent e) { updatePreview(); }
            @Override public void changedUpdate(DocumentEvent e) { updatePreview(); }
        };
        name.getDocument().addDocumentListener(live);

        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, 8, 12);
        g.gridy = 0;
        add(Ui.small("Application name"), g);
        g.gridx = 1;
        add(name, g);
        g.gridy++;
        g.gridx = 0;
        add(Ui.small("Short name (optional)"), g);
        g.gridx = 1;
        add(shortName, g);
        g.gridy++;
        g.gridx = 0;
        g.anchor = GridBagConstraints.NORTHWEST;
        add(Ui.small("Application icon / logo"), g);
        g.gridx = 1;
        JPanel logoRow = Ui.transparent(new BorderLayout(12, 0));
        logoRow.add(logoView, BorderLayout.WEST);
        JPanel logoButtons = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 6));
        logoButtons.add(change);
        logoButtons.add(defaultIcon);
        logoButtons.add(logoNote);
        logoRow.add(logoButtons, BorderLayout.CENTER);
        add(logoRow, g);
        g.gridy++;
        g.gridx = 0;
        add(Ui.small("Preview"), g);
        g.gridx = 1;
        JPanel preview = Ui.panel(new FlowLayout(FlowLayout.LEFT, 10, 8), Palette::windowBackground);
        preview.setBorder(BorderFactory.createLineBorder(Palette.border()));
        preview.add(previewLogo);
        preview.add(previewName);
        add(preview, g);
        g.gridy++;
        g.gridx = 0;
        g.gridwidth = 2;
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(reset);
        buttons.add(javax.swing.Box.createHorizontalStrut(8));
        buttons.add(cancel);
        buttons.add(javax.swing.Box.createHorizontalStrut(8));
        buttons.add(save);
        add(buttons, g);
        g.gridy++;
        add(Ui.label("<html>The name and icon show in the header, the window title and icon, About and messages. "
                + "The installed program's desktop shortcut keeps its original icon.</html>", Palette::mutedText, "small"), g);
        // keeps everything on the left of the section
        g.gridx = 2;
        g.gridy = 0;
        g.gridwidth = 1;
        g.weightx = 1;
        add(javax.swing.Box.createHorizontalGlue(), g);
        branding.addListener(() -> javax.swing.SwingUtilities.invokeLater(this::load)); // saved or reset elsewhere
        load();
    }

    /** Shows what is saved (also CANCEL). */
    private void load() {
        name.setText(branding.applicationName());
        shortName.setText(branding.shortName());
        pendingLogo = null;
        pendingImage = null;
        pendingDefaultLogo = false;
        updatePreview();
    }

    private BufferedImage shownLogo() {
        if (pendingImage != null) {
            return pendingImage;
        }
        return pendingDefaultLogo ? null : branding.customLogo();
    }

    private void updatePreview() {
        BufferedImage img = shownLogo();
        logoView.setImage(img);
        previewLogo.setImage(img);
        String n = name.getText().isBlank() ? BrandingManager.DEFAULT_NAME : name.getText().strip();
        previewName.setText(n);
        logoNote.setText(pendingLogo != null ? "New: " + pendingLogo.getFileName() + " (not saved yet)"
                : pendingDefaultLogo ? "Default icon (not saved yet)" : img == null ? "Default icon" : "Custom logo");
    }

    /** CHANGE ICON: pick a PNG / JPG, see it, then USE THIS ICON or CANCEL. */
    private void chooseLogo() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Change Application Icon");
        chooser.setFileFilter(new FileNameExtensionFilter("Images (PNG, JPG)", "png", "jpg", "jpeg"));
        if (com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        BufferedImage img;
        try {
            img = BrandingManager.readImage(file);
        } catch (Exception e) {
            Dialogs.showError(this, "This image cannot be used as the icon.", e);
            return;
        }
        JPanel confirm = new JPanel(new BorderLayout(0, 10));
        confirm.add(Ui.label("Selected: " + file.getFileName(), Palette::text, null), BorderLayout.NORTH);
        LogoView big = new LogoView(220);
        big.setImage(img);
        confirm.add(big, BorderLayout.CENTER);
        Object[] options = {"USE THIS ICON", "CANCEL"};
        int choice = JOptionPane.showOptionDialog(this, confirm, "Change Application Icon", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            pendingLogo = file;
            pendingImage = img;
            pendingDefaultLogo = false;
            updatePreview();
        }
    }

    private void save() {
        try {
            branding.save(name.getText(), shortName.getText(), pendingLogo, pendingDefaultLogo);
            load();
        } catch (ValidationException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Branding", JOptionPane.WARNING_MESSAGE);
        } catch (Exception e) {
            Dialogs.showError(this, "The branding could not be saved.", e);
        }
    }

    private void resetDefault() {
        Object[] options = {"RESET", "CANCEL"};
        int choice = JOptionPane.showOptionDialog(this, "Reset application branding to default?", "Reset Branding",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (choice != 0) {
            return;
        }
        try {
            branding.resetToDefault();
            load();
        } catch (Exception e) {
            Dialogs.showError(this, "The branding could not be reset.", e);
        }
    }

    /** A logo drawn without distortion in a square; the built-in icon when there is no custom logo. */
    private static final class LogoView extends JComponent {
        private final int size;
        private BufferedImage image;

        LogoView(int size) {
            this.size = size;
            setPreferredSize(new Dimension(size, size));
        }

        void setImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            double scale = g2.getTransform().getScaleX();
            int px = (int) Math.ceil(size * scale);
            BufferedImage shown = image == null ? BrandingManager.defaultIcon(px) : BrandingManager.fit(image, px);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.drawImage(shown, 0, 0, size, size, null);
            g2.dispose();
        }
    }
}
