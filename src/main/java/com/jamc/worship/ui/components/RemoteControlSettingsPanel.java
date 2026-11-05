package com.jamc.worship.ui.components;

import com.jamc.worship.remote.LocalNetwork;
import com.jamc.worship.remote.RemoteDevice;
import com.jamc.worship.remote.RemoteManager;
import com.jamc.worship.remote.RemoteQrCode;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.SoftModal;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Settings → Remote Control. The desktop decides what each remote may do: the server and its QR code, approving new
 * devices, the devices and their permissions, what new devices start with, and locking or disabling all remotes.
 */
public class RemoteControlSettingsPanel extends JPanel {

    private static final DateTimeFormatter LAST_SEEN = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault());

    private final RemoteManager remote;
    private final JRadioButton on = new JRadioButton("ON");
    private final JRadioButton off = new JRadioButton("OFF");
    private Supplier<Color> statusColor = Palette::mutedText;
    private final JLabel status = Ui.label(" ", () -> statusColor.get(), "semibold");
    private final JLabel networkName = Ui.small(" ");
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(RemoteManager.DEFAULT_PORT, RemoteManager.MIN_PORT, RemoteManager.MAX_PORT, 1));
    private final JButton applyPort = Ui.button("Apply Port", null);
    private final JComboBox<LocalNetwork.Address> network = new JComboBox<>();
    private final JPanel networkRow;
    private final JTextField address = new JTextField(26);
    private final JButton copy = Ui.button("Copy", "copy");
    private final JButton newCode = Ui.button("Regenerate Pairing QR", "refresh");
    private final JButton stop = Ui.button("Stop Remote Server", "x");
    private final QrView qr = new QrView();
    private final JLabel connectedCount = Ui.small(" ");
    private final JCheckBox allowNew = new JCheckBox("Allow New Devices");
    private final JPanel pendingList = Ui.transparent(new GridLayout(0, 1, 0, 8));
    private final JButton disable = Ui.button("Disable All Remotes", "x");
    private final JButton lock = Ui.button("Lock Remote Commands", "lock");
    private Supplier<Color> lockColor = Palette::secondaryText;
    private final JLabel lockNote = Ui.label(" ", () -> lockColor.get(), "small");
    private final JPanel deviceList = Ui.transparent(new GridLayout(0, 1, 0, 8));
    private final JLabel noDevices = Ui.small("No devices yet. Scan the QR code with a phone on the same Wi-Fi or network.");
    private final JLabel defaultAccess = Ui.label(" ", Palette::text, "semibold");
    private final Map<String, DeviceRow> rows = new HashMap<>();
    /** The NEW REMOTE DEVICE windows now showing, by request. */
    private final Map<String, JDialog> requestWindows = new HashMap<>();
    private List<LocalNetwork.Address> shownAddresses = List.of();
    private List<RemoteManager.PairingRequest> shownRequests = List.of();
    private boolean syncing;
    private boolean busy;

    public RemoteControlSettingsPanel(RemoteManager remote) {
        super(new GridBagLayout());
        this.remote = remote;
        setOpaque(false);

        ButtonGroup group = new ButtonGroup();
        group.add(on);
        group.add(off);
        on.addActionListener(e -> setEnabledInBackground(true));
        off.addActionListener(e -> setEnabledInBackground(false));
        stop.addActionListener(e -> setEnabledInBackground(false));

        port.setEditor(new JSpinner.NumberEditor(port, "#"));
        port.setToolTipText("Default: " + RemoteManager.DEFAULT_PORT);
        port.addChangeListener(e -> syncApplyPort());
        applyPort.addActionListener(e -> applyPort());

        network.setToolTipText("This PC is on more than one network. Choose the one the phones use.");
        network.addActionListener(e -> {
            if (!syncing && network.getSelectedItem() instanceof LocalNetwork.Address a) {
                remote.selectAddress(a);
            }
        });
        networkRow = row(Ui.small("Use network:"), network);

        address.setEditable(false);
        address.putClientProperty("JTextField.selectAllOnFocusPolicy", "always");
        copy.setToolTipText("Copy the link in the QR code");
        copy.addActionListener(e -> {
            String text = remote.pairingLink();
            if (text != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            }
        });
        newCode.setToolTipText("The code shown now stops letting new devices join. Trusted devices keep working.");
        newCode.addActionListener(e -> remote.newPairingToken());
        allowNew.setOpaque(false);
        allowNew.setToolTipText("Off: the QR code stops working for new devices. Trusted devices can still connect.");
        allowNew.addActionListener(e -> remote.setAllowNewDevices(allowNew.isSelected()));
        lock.setToolTipText("Devices stay connected and can watch, but every command is refused.");
        lock.addActionListener(e -> remote.setLocked(!remote.locked()));
        disable.setToolTipText("Disconnects every remote and accepts none until you turn this off. The presentation carries on.");
        disable.addActionListener(e -> remote.setDisabled(!remote.disabled()));

        JPanel controls = Ui.transparent(new GridBagLayout());
        GridBagConstraints r = rowConstraints();
        controls.add(row(Ui.small("Remote Web Control:"), on, off), r);
        r.gridy++;
        controls.add(row(Ui.small("Status:"), status), r);
        r.gridy++;
        controls.add(row(Ui.small("Network:"), networkName), r);
        r.gridy++;
        controls.add(networkRow, r);
        r.gridy++;
        controls.add(row(Ui.small("Port:"), port, applyPort), r);
        r.gridy++;
        controls.add(row(Ui.small("Remote Address:"), address, copy), r);
        r.gridy++;
        controls.add(row(Ui.small("Connected Devices:"), connectedCount), r);
        r.gridy++;
        controls.add(row(newCode, stop), r);

        JPanel top = Ui.transparent(new BorderLayout(20, 0));
        top.add(controls, BorderLayout.WEST);
        // right beside the controls, not at the far edge: the page can be wider than the window
        JPanel qrColumn = Ui.transparent(new BorderLayout(0, 6));
        qrColumn.add(qr, BorderLayout.CENTER);
        JLabel scan = Ui.small("Scan to open LiveCat Remote");
        scan.setHorizontalAlignment(JLabel.CENTER);
        qrColumn.add(scan, BorderLayout.SOUTH);
        JPanel qrHolder = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        qrHolder.add(qrColumn);
        top.add(qrHolder, BorderLayout.CENTER);

        JPanel pairing = Ui.transparent(new BorderLayout(0, 6));
        pairing.add(heading("Pairing"), BorderLayout.NORTH);
        pairing.add(row(allowNew), BorderLayout.CENTER);
        pairing.add(pendingList, BorderLayout.SOUTH);

        JPanel safety = Ui.transparent(new BorderLayout(0, 6));
        safety.add(heading("Emergency"), BorderLayout.NORTH);
        safety.add(row(disable, lock), BorderLayout.CENTER);
        safety.add(row(lockNote), BorderLayout.SOUTH);

        JPanel devices = Ui.transparent(new BorderLayout(0, 6));
        devices.add(heading("Connected Devices"), BorderLayout.NORTH);
        devices.add(deviceList, BorderLayout.CENTER);

        JButton changeDefault = Ui.button("Change…", "sliders");
        changeDefault.addActionListener(e -> changeDefaultAccess());
        JPanel defaults = Ui.transparent(new BorderLayout(0, 6));
        defaults.add(heading("Default New Device Access"), BorderLayout.NORTH);
        defaults.add(row(defaultAccess, changeDefault), BorderLayout.CENTER);

        JPanel notes = Ui.transparent(new GridLayout(0, 1, 0, 2));
        notes.add(Ui.label("Scene editing, settings and all API and stream keys stay on this computer, whatever a device may do.",
                Palette::mutedText, "small"));
        notes.add(Ui.label("If a phone cannot connect, allow this app through Windows Firewall on private networks.",
                Palette::mutedText, "small"));

        GridBagConstraints g = rowConstraints();
        g.insets = new Insets(0, 0, 14, 0);
        for (JComponent section : new JComponent[]{top, pairing, safety, devices, defaults, notes}) {
            add(section, g);
            g.gridy++;
        }

        remote.addListener(() -> SwingUtilities.invokeLater(this::sync));
        sync();
        refresh();
    }

    private static JLabel heading(String text) {
        return Ui.label(text, Palette::text, "semibold");
    }

    /** Looks at the network again (the PC may have moved to another Wi-Fi while the server was off). */
    public void refresh() {
        BackgroundTask.run("Detect the network address", () -> {
            remote.refreshNetwork();
            return true;
        }, ok -> sync(), err -> { });
    }

    private void setEnabledInBackground(boolean enabled) {
        busy = true;
        (enabled ? on : off).setSelected(true);
        sync();
        BackgroundTask.run("Remote server", () -> {
            remote.setEnabled(enabled);
            return true;
        }, ok -> {
            busy = false;
            sync();
        }, err -> {
            busy = false;
            sync();
            Dialogs.showError(this, "The remote server could not be changed.", err);
        });
    }

    private void applyPort() {
        int value = ((Number) port.getValue()).intValue();
        busy = true;
        sync();
        BackgroundTask.run("Remote server port", () -> {
            remote.setPort(value);
            return true;
        }, ok -> {
            busy = false;
            sync();
        }, err -> {
            busy = false;
            sync();
            Dialogs.showError(this, "The port could not be changed.", err);
        });
    }

    private void syncApplyPort() {
        applyPort.setEnabled(!busy && ((Number) port.getValue()).intValue() != remote.port());
    }

    /** Shows the manager's state. Only what changed is touched, so a device joining never interrupts a click here. */
    private void sync() {
        syncing = true;
        try {
            boolean running = remote.running();
            if (!busy) { // while switching, keep showing what the operator just clicked
                (remote.enabled() ? on : off).setSelected(true);
            }
            on.setEnabled(!busy);
            off.setEnabled(!busy);
            stop.setEnabled(!busy && running);
            String problem = remote.problem();
            if (running) {
                statusColor = Palette::live;
                status.setText("● RUNNING");
            } else if (problem != null) {
                statusColor = Palette::danger;
                status.setText("● STOPPED: " + problem);
            } else {
                statusColor = Palette::mutedText;
                status.setText("● STOPPED");
            }
            status.repaint();

            if (!port.getEditor().getComponent(0).hasFocus() && !applyPort.isEnabled()) {
                port.setValue(remote.port());
            }
            syncApplyPort();

            List<LocalNetwork.Address> found = remote.addresses();
            if (!found.equals(shownAddresses)) {
                shownAddresses = found;
                network.removeAllItems();
                found.forEach(network::addItem);
            }
            LocalNetwork.Address selected = remote.selectedAddress();
            network.setSelectedItem(selected);
            networkRow.setVisible(found.size() > 1);
            networkName.setText(selected == null ? "Not connected" : selected.adapter());

            String url = remote.remoteAddress();
            address.setText(url == null ? "No network found" : url);
            address.setToolTipText(url == null ? "Connect this PC to the church Wi-Fi or network" : null);
            copy.setEnabled(url != null);
            qr.show(running ? remote.pairingLink() : null, url == null ? "No network" : "Server stopped");
            newCode.setEnabled(running);
            allowNew.setSelected(remote.allowNewDevices());

            boolean locked = remote.locked();
            boolean disabled = remote.disabled();
            lock.setText(locked ? "Unlock Remote Commands" : "Lock Remote Commands");
            disable.setText(disabled ? "Enable Remotes Again" : "Disable All Remotes");
            lockNote.setText(disabled ? "All remotes are disabled: none can connect. The presentation carries on."
                    : locked ? "Locked: devices stay connected and can watch, but every command is refused."
                    : "Remotes can use what each device is allowed below.");
            lockColor = disabled || locked ? Palette::danger : Palette::secondaryText;

            defaultAccess.setText(RemotePermissionEditor.summary(remote.defaultPermissions()));
            List<RemoteManager.DeviceView> views = remote.devices();
            connectedCount.setText(String.valueOf(views.stream().filter(RemoteManager.DeviceView::session).count()));
            syncDevices(views);
            syncRequests(remote.pairingRequests());
        } finally {
            syncing = false;
        }
        revalidate();
        repaint();
    }

    private void syncDevices(List<RemoteManager.DeviceView> views) {
        List<JComponent> order = new ArrayList<>();
        Map<String, DeviceRow> keep = new HashMap<>();
        for (RemoteManager.DeviceView v : views) {
            DeviceRow row = rows.computeIfAbsent(v.device().id(), DeviceRow::new);
            row.update(v);
            keep.put(v.device().id(), row);
            order.add(row);
        }
        rows.keySet().retainAll(keep.keySet());
        if (order.isEmpty()) {
            order.add(noDevices);
        }
        if (!order.equals(List.of(deviceList.getComponents()))) {
            deviceList.removeAll();
            order.forEach(deviceList::add);
        }
    }

    /** Waiting devices show here, and each in a window of its own so the operator sees them from any page. */
    private void syncRequests(List<RemoteManager.PairingRequest> requests) {
        if (requests.equals(shownRequests)) {
            return;
        }
        shownRequests = requests;
        pendingList.removeAll();
        List<String> ids = new ArrayList<>();
        for (RemoteManager.PairingRequest request : requests) {
            ids.add(request.id());
            pendingList.add(requestRow(request));
            requestWindows.computeIfAbsent(request.id(), id -> requestWindow(request));
        }
        requestWindows.entrySet().removeIf(e -> {
            if (!ids.contains(e.getKey())) {
                e.getValue().dispose();
                return true;
            }
            return false;
        });
    }

    private JPanel requestRow(RemoteManager.PairingRequest request) {
        JPanel row = Ui.panel(new BorderLayout(10, 0), Palette::panelBackground);
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Palette.warning()),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        row.add(Ui.label(request.name() + "  ·  " + request.address() + "  ·  waiting for approval", Palette::text, null), BorderLayout.CENTER);
        row.add(requestButtons(request), BorderLayout.EAST);
        return row;
    }

    private JPanel requestButtons(RemoteManager.PairingRequest request) {
        JButton configure = Ui.button("Configure Access", "sliders");
        configure.addActionListener(e -> configureRequest(request));
        JButton deny = Ui.button("Deny", "x");
        deny.addActionListener(e -> remote.deny(request.id()));
        JButton allow = Ui.primaryButton("Allow", null);
        allow.addActionListener(e -> remote.approve(request.id(), remote.defaultPermissions(), null));
        return row(configure, deny, allow);
    }

    /**
     * NEW REMOTE DEVICE. It never takes the keyboard and never blocks the app: the operator may be mid-service with
     * Space on TAKE, and a stranger scanning the code must not be able to interrupt that.
     */
    private JDialog requestWindow(RemoteManager.PairingRequest request) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog d = new JDialog(owner, "New Remote Device", java.awt.Dialog.ModalityType.MODELESS);
        d.setAutoRequestFocus(false);
        d.setFocusableWindowState(false);
        d.setAlwaysOnTop(owner == null);
        d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        JPanel text = Ui.transparent(new GridLayout(0, 1, 0, 4));
        text.add(Ui.label("NEW REMOTE DEVICE", Palette::warning, "semibold"));
        text.add(Ui.label(request.name(), Palette::text, "h4"));
        text.add(Ui.small("from " + request.address()));
        text.add(Ui.small("A device wants to control this application."));
        text.add(Ui.small("Default access: " + RemotePermissionEditor.summary(remote.defaultPermissions())));
        p.add(text, BorderLayout.CENTER);
        p.add(requestButtons(request), BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        if (owner != null && owner.isShowing()) {
            d.setLocation(owner.getX() + owner.getWidth() - d.getWidth() - 24, owner.getY() + owner.getHeight() - d.getHeight() - 48);
            d.setVisible(true);
        }
        return d;
    }

    /** CONFIGURE ACCESS: only Lyrics is ticked until the operator ticks more. */
    private void configureRequest(RemoteManager.PairingRequest request) {
        JTextField name = new JTextField(request.name(), 24);
        RemotePermissionEditor editor = new RemotePermissionEditor(remote.defaultPermissions());
        if (dialog("Remote Device Permissions", name, editor, null, "Save & Allow")) {
            remote.approve(request.id(), editor.permissions(), name.getText());
        }
    }

    private void changeDefaultAccess() {
        RemotePermissionEditor editor = new RemotePermissionEditor(remote.defaultPermissions());
        if (dialog("Default New Device Access", null, editor, null, "Save")) {
            remote.setDefaultPermissions(editor.permissions());
        }
    }

    /** MANAGE: the device's details, permissions, Disconnect and Forget. Permissions apply when saved, at once. */
    private void manage(String id) {
        RemoteManager.DeviceView view = remote.devices().stream().filter(v -> v.device().id().equals(id)).findFirst().orElse(null);
        if (view == null) {
            return;
        }
        RemoteDevice device = view.device();
        JTextField name = new JTextField(device.name(), 24);
        RemotePermissionEditor editor = new RemotePermissionEditor(device.permissions());
        JPanel info = Ui.transparent(new GridLayout(0, 1, 0, 2));
        info.add(Ui.small("Connection: " + (view.online() ? "Connected" : view.session() ? "Not responding" : "Not connected")));
        info.add(Ui.small("Address: " + device.address()));
        info.add(Ui.small("Last connected: " + LAST_SEEN.format(device.lastSeen())));
        JDialog[] holder = new JDialog[1];
        JButton disconnect = Ui.button("Disconnect", "x");
        disconnect.setEnabled(view.session());
        disconnect.addActionListener(e -> {
            remote.disconnect(id);
            holder[0].dispose();
        });
        JButton forget = Ui.button("Forget Device", "trash");
        forget.addActionListener(e -> {
            if (Dialogs.confirmDestructive(holder[0], "Forget device", "Forget \"" + device.name() + "\"?\n\n"
                    + "Its trusted authorization and permissions are removed.\n"
                    + "To use the remote again it must pair again, and it starts with the default access.", "Forget Device")) {
                remote.forget(id);
                holder[0].dispose();
            }
        });
        JPanel extra = Ui.transparent(new BorderLayout(0, 8));
        extra.add(info, BorderLayout.NORTH);
        extra.add(row(disconnect, forget), BorderLayout.SOUTH);
        if (dialog("Manage Device", name, editor, new Object[]{extra, holder}, "Save")) {
            remote.rename(id, name.getText());
            remote.setPermissions(id, editor.permissions());
        }
    }

    /** @param extra optional {component above the actions, a holder that receives the dialog} */
    private boolean dialog(String title, JTextField name, RemotePermissionEditor editor, Object[] extra, String saveLabel) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        JPanel center = Ui.transparent(new BorderLayout(0, 10));
        if (name != null) {
            center.add(row(Ui.small("Device Name:"), name), BorderLayout.NORTH);
        }
        center.add(editor, BorderLayout.CENTER);
        if (extra != null) {
            center.add((JComponent) extra[0], BorderLayout.SOUTH);
            ((JDialog[]) extra[1])[0] = d;
        }
        body.add(center, BorderLayout.CENTER);
        boolean[] saved = {false};
        JButton cancel = Ui.button("Cancel", null);
        cancel.addActionListener(e -> d.dispose());
        JButton save = Ui.primaryButton(saveLabel, null);
        save.addActionListener(e -> {
            if (name != null && name.getText().isBlank()) {
                JOptionPane.showMessageDialog(d, "Enter a device name.", title, JOptionPane.WARNING_MESSAGE);
                return;
            }
            saved[0] = true;
            d.dispose();
        });
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(save);
        body.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(body);
        d.pack();
        d.setLocationRelativeTo(this);
        SoftModal.showAndWait(d);
        return saved[0];
    }

    private static GridBagConstraints rowConstraints() {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 6, 0);
        return g;
    }

    private static JPanel row(JComponent... items) {
        JPanel p = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (JComponent i : items) {
            p.add(i);
        }
        return p;
    }

    /** One device: name, state, what it may do, MANAGE and DISCONNECT. */
    private final class DeviceRow extends JPanel {
        private final JLabel name = Ui.label(" ", Palette::text, "semibold");
        private Supplier<Color> stateColor = Palette::mutedText;
        private final JLabel state = Ui.label(" ", () -> stateColor.get(), "small");
        private final JLabel access = Ui.small(" ");
        private final JLabel detail = Ui.small(" ");
        private final JButton disconnect = Ui.button("Disconnect", "x");

        DeviceRow(String id) {
            super(new BorderLayout(10, 4));
            setOpaque(true);
            setBorder(BorderFactory.createCompoundBorder(Ui.line(true, true, true, true),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            JPanel head = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            head.add(name);
            head.add(state);
            head.add(access);
            JPanel text = Ui.transparent(new GridLayout(0, 1, 0, 4));
            text.add(head);
            text.add(row(detail));
            JButton manage = Ui.button("Manage", "sliders");
            manage.addActionListener(e -> manage(id));
            disconnect.setToolTipText("End this connection. The device stays trusted and can connect again.");
            disconnect.addActionListener(e -> remote.disconnect(id));
            add(text, BorderLayout.CENTER);
            add(row(manage, disconnect), BorderLayout.EAST);
        }

        @Override
        public Color getBackground() {
            return Palette.panelBackground();
        }

        void update(RemoteManager.DeviceView v) {
            RemoteDevice d = v.device();
            name.setText(d.name());
            if (v.online()) {
                stateColor = Palette::live;
                state.setText("● CONNECTED");
            } else if (v.session()) {
                stateColor = Palette::warning;
                state.setText("● NOT RESPONDING");
            } else {
                stateColor = Palette::mutedText;
                state.setText("○ NOT CONNECTED");
            }
            access.setText(RemotePermissionEditor.summary(d.permissions()));
            detail.setText(d.address() + "  ·  last connected " + LAST_SEEN.format(d.lastSeen()));
            disconnect.setEnabled(v.session());
        }
    }

    /** The QR code, sharp at any display scale, or why there is none. */
    private static final class QrView extends JComponent {
        private static final int SIZE = 196;
        private String text;
        private BufferedImage image;
        private String emptyText = "";

        QrView() {
            setPreferredSize(new Dimension(SIZE, SIZE));
            setMinimumSize(new Dimension(SIZE, SIZE));
        }

        void show(String newText, String whenEmpty) {
            emptyText = whenEmpty;
            if (newText == null ? text != null : !newText.equals(text)) {
                text = newText;
                image = newText == null ? null : RemoteQrCode.create(newText);
                setToolTipText(newText == null ? null : "Scan with a phone on the same network to open the remote");
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth();
            int h = getHeight();
            int side = Math.min(w, h);
            int x = (w - side) / 2;
            int y = (h - side) / 2;
            if (image == null) {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(Palette.border());
                g2.drawRoundRect(x, y, side - 1, side - 1, 8, 8);
                g2.setColor(Palette.mutedText());
                g2.setFont(getFont() == null ? g2.getFont() : getFont());
                int tw = g2.getFontMetrics().stringWidth(emptyText);
                g2.drawString(emptyText, x + (side - tw) / 2, y + side / 2);
            } else {
                // whole device pixels per module, or the scanner sees blurred edges
                double scale = g2.getTransform().getScaleX();
                int devicePx = (int) Math.floor(side * scale / image.getWidth());
                int drawn = (int) Math.round(Math.max(1, devicePx) * image.getWidth() / scale);
                int dx = x + (side - drawn) / 2;
                int dy = y + (side - drawn) / 2;
                g2.setColor(Color.WHITE);
                g2.fillRect(x, y, side, side);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(image, dx, dy, drawn, drawn, null);
            }
            g2.dispose();
        }
    }
}
