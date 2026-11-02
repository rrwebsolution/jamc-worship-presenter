package com.jamc.worship.ui.components;

import com.jamc.worship.remote.RemotePermission;
import com.jamc.worship.remote.RemoteRole;
import com.jamc.worship.ui.Ui;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** A role preset and the permissions by group. The role follows the checkboxes, so it always tells the truth. */
final class RemotePermissionEditor extends JPanel {

    private final JComboBox<RemoteRole> role = new JComboBox<>(RemoteRole.values());
    private final Map<RemotePermission, JCheckBox> boxes = new EnumMap<>(RemotePermission.class);
    private boolean syncing;

    RemotePermissionEditor(Set<RemotePermission> permissions) {
        super(new GridBagLayout());
        setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.insets = new Insets(0, 0, 6, 0);
        JPanel roleRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        roleRow.add(Ui.small("Role:"));
        roleRow.add(role);
        add(roleRow, g);
        for (RemotePermission.Group group : RemotePermission.Group.values()) {
            g.gridy++;
            add(Ui.caption(group.name()), g);
            g.gridy++;
            JPanel row = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            for (RemotePermission p : RemotePermission.values()) {
                if (p.group() == group) {
                    JCheckBox box = new JCheckBox(p.label());
                    box.setOpaque(false);
                    box.addActionListener(e -> syncRole());
                    boxes.put(p, box);
                    row.add(box);
                }
            }
            add(row, g);
        }
        role.addActionListener(e -> {
            if (!syncing && role.getSelectedItem() instanceof RemoteRole r && r != RemoteRole.CUSTOM) {
                show(r.permissions());
            }
        });
        show(permissions);
    }

    private void show(Set<RemotePermission> permissions) {
        boxes.forEach((p, box) -> box.setSelected(permissions.contains(p)));
        syncRole();
    }

    private void syncRole() {
        syncing = true;
        role.setSelectedItem(RemoteRole.of(permissions()));
        syncing = false;
    }

    Set<RemotePermission> permissions() {
        EnumSet<RemotePermission> chosen = EnumSet.noneOf(RemotePermission.class);
        boxes.forEach((p, box) -> {
            if (box.isSelected()) {
                chosen.add(p);
            }
        });
        return chosen;
    }

    JCheckBox box(RemotePermission p) {
        return boxes.get(p);
    }

    /** "Lyrics Only", "Pastor", or the permissions themselves for a custom set. */
    static String summary(Set<RemotePermission> permissions) {
        RemoteRole r = RemoteRole.of(permissions);
        if (r != RemoteRole.CUSTOM) {
            return r.label();
        }
        if (permissions.isEmpty()) {
            return "No access";
        }
        StringBuilder sb = new StringBuilder();
        for (RemotePermission p : permissions) {
            sb.append(sb.isEmpty() ? "" : ", ").append(p.label());
        }
        return sb.toString();
    }
}
