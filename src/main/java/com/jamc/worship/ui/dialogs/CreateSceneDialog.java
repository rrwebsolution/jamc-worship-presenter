package com.jamc.worship.ui.dialogs;

import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CREATE SCENE: a name, and what to start from: a blank scene, a copy of the scene in Preview, or one
 * of the standard templates (Worship, Camera, Camera + Lyrics, Presentation, Video).
 */
public final class CreateSceneDialog extends JDialog {

    public enum Start { BLANK, DUPLICATE_CURRENT, TEMPLATE }

    /** The operator's choice; {@code templateScene} is the standard scene to copy for TEMPLATE. */
    public record Request(String name, Start start, String templateScene) {
    }

    /** Template label → the standard scene it copies. */
    public static final Map<String, String> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("Worship", "Worship");
        TEMPLATES.put("Camera", "Camera");
        TEMPLATES.put("Camera + Lyrics", "Camera + Lyrics");
        TEMPLATES.put("Presentation", "Sermon");
        TEMPLATES.put("Camera + Presentation", "Camera + Presentation");
        TEMPLATES.put("Video", "Fullscreen Video");
    }

    private final JTextField name = new JTextField("New Scene", 22);
    private final JRadioButton blank = new JRadioButton("Blank scene", true);
    private final JRadioButton duplicate = new JRadioButton("Duplicate current scene");
    private final JRadioButton template = new JRadioButton("Template");
    private final JComboBox<String> templates = new JComboBox<>(TEMPLATES.keySet().toArray(String[]::new));
    private Request result;

    private CreateSceneDialog(Component parent, String currentScene) {
        super(SwingUtilities.getWindowAncestor(parent), "Create Scene", ModalityType.APPLICATION_MODAL);
        JPanel root = Ui.panel(new BorderLayout(0, 12), Palette::panelBackground);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.add(Ui.label("CREATE SCENE", Palette::text, "h3"), BorderLayout.NORTH);

        ButtonGroup group = new ButtonGroup();
        for (JRadioButton r : new JRadioButton[]{blank, duplicate, template}) {
            r.setOpaque(false);
            group.add(r);
            r.addActionListener(e -> templates.setEnabled(template.isSelected()));
        }
        duplicate.setEnabled(currentScene != null);
        duplicate.setText(currentScene == null ? "Duplicate current scene" : "Duplicate current scene (" + currentScene + ")");
        templates.setEnabled(false);

        JPanel form = Ui.transparent(new GridLayout(0, 1, 0, 6));
        form.add(row(Ui.small("Name:"), name));
        form.add(Ui.label("Start from:", Palette::secondaryText, "semibold"));
        form.add(blank);
        form.add(duplicate);
        form.add(row(template, templates));
        root.add(form, BorderLayout.CENTER);

        JButton cancel = Ui.button("CANCEL", null);
        cancel.addActionListener(e -> dispose());
        JButton create = Ui.primaryButton("CREATE", "plus");
        create.addActionListener(e -> {
            String n = name.getText().strip();
            if (n.isEmpty()) {
                name.requestFocusInWindow();
                return;
            }
            Start s = blank.isSelected() ? Start.BLANK : duplicate.isSelected() ? Start.DUPLICATE_CURRENT : Start.TEMPLATE;
            result = new Request(n, s, s == Start.TEMPLATE ? TEMPLATES.get((String) templates.getSelectedItem()) : null);
            dispose();
        });
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(create);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(create);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(parent);
        name.selectAll();
    }

    private static JPanel row(JComponent a, JComponent b) {
        JPanel p = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.add(a);
        p.add(b);
        return p;
    }

    /** Shows the dialog; null when cancelled. */
    public static Request show(Component parent, String currentScene) {
        CreateSceneDialog d = new CreateSceneDialog(parent, currentScene);
        com.jamc.worship.ui.SoftModal.showAndWait(d); // minimizing it lets the app be used meanwhile
        return d.result;
    }
}
