package com.jamc.worship.ui.components;

import com.jamc.worship.model.Scene;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.function.Consumer;

/**
 * Under the scene cards: a click on a card only SELECTS it (Preview and Program stay as they are);
 * these buttons act on the selected scene. PREVIEW puts it in Preview (TAKE puts it live), LIVE puts it on
 * Program at once, EDIT opens its sources.
 */
final class SceneActionBar extends JPanel {

    private final JLabel selectedLabel = Ui.label("Click a scene to select it", Palette::secondaryText, "small");
    private final JButton preview = Ui.button("Preview", "monitor");
    private final JButton live = Ui.button("Live", "play");
    private final JButton edit = Ui.button("Edit", "layers");
    private Scene scene;

    SceneActionBar(Consumer<Scene> onPreview, Consumer<Scene> onLive, Consumer<Scene> onEdit) {
        super(new BorderLayout(0, 4));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        preview.setToolTipText("Send the selected scene to Preview (TAKE puts it live). Double-click a card does the same.");
        live.setToolTipText("Put the selected scene on Program now");
        edit.setToolTipText("Edit the selected scene's sources");
        preview.addActionListener(e -> { if (scene != null) onPreview.accept(scene); });
        live.addActionListener(e -> { if (scene != null) onLive.accept(scene); });
        edit.addActionListener(e -> { if (scene != null) onEdit.accept(scene); });
        JPanel buttons = Ui.transparent(new GridLayout(1, 3, 6, 0));
        buttons.add(preview);
        buttons.add(live);
        buttons.add(edit);
        add(selectedLabel, BorderLayout.NORTH);
        add(buttons, BorderLayout.CENTER);
        setScene(null);
    }

    /** The selected scene, or null: the buttons work only with a selection. */
    void setScene(Scene s) {
        scene = s;
        selectedLabel.setText(s == null ? "Click a scene to select it" : "Selected: " + s.getName());
        preview.setEnabled(s != null);
        live.setEnabled(s != null);
        edit.setEnabled(s != null);
    }

    Scene scene() {
        return scene;
    }
}
