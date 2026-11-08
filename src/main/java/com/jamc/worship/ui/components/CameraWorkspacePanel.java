package com.jamc.worship.ui.components;

import com.jamc.worship.camera.CameraManager;
import com.jamc.worship.camera.CameraNames;
import com.jamc.worship.camera.CameraSettings;
import com.jamc.worship.camera.CameraStatus;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.media.MediaEngine;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.output.Compositor;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Library → Camera: every camera as a card (number, nickname, device, size, frame rate, status, audio), the
 * camera workspace (a live preview of one camera, or the MULTI-CAMERA VIEW of all of them) and its settings
 * (rename, resolution, frame rate, audio input, aspect / mirror / fit, SEND TO PREVIEW).
 *
 * <p>Everything here only inspects the cameras: nothing reaches Preview or Program until SEND TO PREVIEW (and
 * then TAKE). The optional SWITCH NOW control appears only with Settings → Camera → Direct Camera Switching on.
 */
public class CameraWorkspacePanel extends JPanel {

    /** What SEND TO PREVIEW (and, with Direct Camera Switching, SWITCH NOW) does, and what is live. */
    public interface Actions {
        void sendToPreview(String camera, FitMode fit, boolean mirror);

        /** Direct Camera Switching only: this camera on Program at once. */
        default void switchProgram(String camera, FitMode fit, boolean mirror) {
        }

        default Scene programScene() {
            return null;
        }

        default Scene previewScene() {
            return null;
        }
    }

    private static final String DEFAULT = "Auto (best available)";
    private static final String DEFAULT_FPS = "Camera default";
    private static final String[] COMMON_SIZES = {"1920x1080", "1280x720", "3840x2160", "640x480"};
    private static final Integer[] COMMON_RATES = {30, 60, 25, 24};
    private static final String AUTO_AUDIO = "Automatic";
    private static final String NO_AUDIO = "None (video only)";
    private static final String K_DEVICE = "camera.workspace.device";
    private static final String K_MIRROR = "camera.workspace.mirror";
    private static final String K_FIT = "camera.workspace.fit";
    private static final String K_MULTI = "camera.workspace.multiview";
    /** Cameras the operator turned off on this page, separated by '|'. */
    private static final String K_OFF = "camera.workspace.off";

    private final CameraManager cameras;
    private final CameraNames names;
    private final CameraSettings settings;
    private final AppConfig config;
    private final Actions actions;
    private final DefaultListModel<String> deviceModel = new DefaultListModel<>();
    private final JList<String> deviceList = new JList<>(deviceModel);
    private final JPanel libraryPanel = Ui.panel(new BorderLayout(), Palette::panelBackground);
    private final JLabel count = Ui.small(" ");
    private final JButton detectButton = Ui.toolButton("refresh", "Detect cameras again");
    private final JComboBox<String> device = new JComboBox<>();
    private final JComboBox<String> size = new JComboBox<>();
    private final JComboBox<Object> fps = new JComboBox<>();
    private final JLabel capsNote = Ui.label(" ", Palette::mutedText, "small");
    private final JComboBox<String> audioInput = new JComboBox<>();
    private final JCheckBox keepAspect = new JCheckBox("Maintain aspect ratio", true);
    private final JCheckBox mirror = new JCheckBox("Mirror");
    private final JComboBox<FitMode> fit = new JComboBox<>(new FitMode[]{FitMode.FILL, FitMode.FIT});
    private final JButton sendToPreview = Ui.primaryButton("SEND TO PREVIEW", "monitor");
    private final JButton switchNow = Ui.button("SWITCH NOW", "video");
    private final JButton rename = Ui.button("RENAME", "edit");
    private final JButton refresh = Ui.button("REFRESH", "refresh");
    private final JToggleButton multiToggle = new JToggleButton("MULTI-CAMERA VIEW", Icons.get("grid", Icons.SMALL));
    private final JLabel title = Ui.label("No camera", Palette::text, "h3");
    private final JLabel deviceLabel = Ui.label(" ", Palette::secondaryText, "small");
    private final JLabel bandwidth = Ui.label(" ", Palette::warning, "small");
    private final Monitor monitor = new Monitor();
    private final MultiView multiview = new MultiView();
    private final CardLayout viewCards = new CardLayout();
    private final JPanel viewHolder = Ui.transparent(viewCards);
    private final JLabel statusLabel = Ui.label("–", Palette::text, "semibold");
    private final JLabel liveSize = Ui.label("–", Palette::text, "semibold");
    private final Timer frames = new Timer(33, e -> tick());
    private final CardLayout listCards = new CardLayout();
    private final JPanel listHolder = Ui.transparent(listCards);
    private final Set<String> off = new LinkedHashSet<>();
    private Consumer<String> statusHandler = s -> { };
    private boolean loading;

    public CameraWorkspacePanel(CameraManager cameras, CameraNames names, CameraSettings settings, AppConfig config,
                                Actions actions) {
        super(new BorderLayout());
        this.cameras = cameras;
        this.names = names;
        this.settings = settings;
        this.config = config;
        this.actions = actions;
        setOpaque(false);
        buildLibraryPanel();

        JLabel badge = Ui.label("CAMERA", Palette::secondaryText, "semibold");
        JPanel titles = Ui.transparent(new BorderLayout(0, 2));
        titles.add(badge, BorderLayout.NORTH);
        titles.add(title, BorderLayout.CENTER);
        titles.add(deviceLabel, BorderLayout.SOUTH);
        JPanel tools = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        tools.add(refresh);
        tools.add(rename);
        tools.add(multiToggle);
        JPanel heading = Ui.transparent(new BorderLayout(12, 0));
        heading.add(titles, BorderLayout.CENTER);
        heading.add(tools, BorderLayout.EAST);
        heading.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(10, 14, 8, 12)));
        add(heading, BorderLayout.NORTH);

        viewHolder.add(monitor, "single");
        viewHolder.add(multiview, "multi");
        JPanel center = Ui.transparent(new BorderLayout(0, 6));
        bandwidth.setVisible(false);
        center.add(bandwidth, BorderLayout.NORTH);
        center.add(viewHolder, BorderLayout.CENTER);
        JPanel view = Ui.transparent(new BorderLayout(12, 0));
        view.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        view.add(center, BorderLayout.CENTER);
        JScrollPane controls = new JScrollPane(buildControls());
        controls.setBorder(null);
        controls.setOpaque(false);
        controls.getViewport().setOpaque(false);
        controls.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        controls.setPreferredSize(new Dimension(270, 10));
        view.add(controls, BorderLayout.EAST);
        add(view, BorderLayout.CENTER);

        javax.swing.DefaultListCellRenderer deviceRenderer = new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                return super.getListCellRendererComponent(l, v == null ? "" : names.full(v.toString()), i, sel, f);
            }
        };
        device.setRenderer(deviceRenderer);
        deviceList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !loading && deviceList.getSelectedValue() != null) {
                device.setSelectedItem(deviceList.getSelectedValue());
            }
        });
        device.addActionListener(e -> {
            if (!loading) deviceChanged();
        });
        size.addActionListener(e -> {
            if (!loading) formatChanged();
        });
        fps.addActionListener(e -> {
            if (!loading) formatChanged();
        });
        audioInput.addActionListener(e -> {
            if (!loading) audioChanged();
        });
        keepAspect.addActionListener(e -> {
            fit.setEnabled(keepAspect.isSelected());
            saveOptions();
        });
        mirror.addActionListener(e -> saveOptions());
        fit.addActionListener(e -> {
            if (!loading) saveOptions();
        });
        detectButton.addActionListener(e -> detect());
        rename.setToolTipText("Give this camera a name of your own (e.g. PREACHER CAMERA)");
        rename.addActionListener(e -> renameCamera(selectedCamera()));
        refresh.setToolTipText("The picture is missing or stuck: close this camera and open it again. Other cameras keep running.");
        refresh.addActionListener(e -> refreshCamera(selectedCamera()));
        multiToggle.setToolTipText("Show every camera at once (click: configure, double-click: send to Preview)");
        multiToggle.addActionListener(e -> {
            config.set(K_MULTI, String.valueOf(multiToggle.isSelected()));
            config.save();
            showView();
        });
        sendToPreview.setToolTipText("Put the Camera scene with this camera in the main PREVIEW. Program stays until TAKE.");
        sendToPreview.addActionListener(e -> preview(selectedCamera()));
        switchNow.setToolTipText("Direct Camera Switching: put this camera on Program at once, without Preview / TAKE");
        switchNow.addActionListener(e -> switchProgram(selectedCamera()));

        loading = true;
        try {
            mirror.setSelected(config.getBoolean(K_MIRROR, false));
            String f = config.get(K_FIT, "FILL");
            keepAspect.setSelected(!"STRETCH".equals(f));
            fit.setSelectedItem("FIT".equals(f) ? FitMode.FIT : FitMode.FILL);
            fit.setEnabled(keepAspect.isSelected());
            multiToggle.setSelected(config.getBoolean(K_MULTI, false));
            for (String cam : config.get(K_OFF, "").split("\\|")) {
                if (!cam.isBlank()) {
                    off.add(cam);
                }
            }
        } finally {
            loading = false;
        }
        // each camera opens at its saved resolution / frame rate from the start, wherever it is first shown
        for (String cam : names.remembered()) {
            String[] fmt = savedFormat(cam);
            cameras.setFormat(cam, fmt[0], parseRate(fmt[1]));
        }
        cameras.addListener(this::camerasChanged);
        names.addListener(() -> {
            deviceList.repaint();
            device.repaint();
            updateTitle();
            multiview.repaint();
        });
        showView();
        setDevices(cameras.cameras());
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    /** The camera list shown in the library column while Library → Camera is selected. */
    public JComponent libraryPanel() {
        return libraryPanel;
    }

    /**
     * Library → Camera was opened: the last list shows at once, and the cameras are detected again so every
     * connected one (virtual cameras such as Iriun #2..#4 or DroidCam included) is there to pick.
     */
    public void activate() {
        setDevices(cameras.cameras());
        detect();
    }

    /** Camera list, statuses or audio inputs changed (hot plug, a camera started or failed). */
    private void camerasChanged() {
        List<String> all = allDevices(cameras.cameras());
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < deviceModel.size(); i++) {
            shown.add(deviceModel.get(i));
        }
        if (!all.equals(shown)) {
            setDevices(cameras.cameras());
        } else {
            deviceList.repaint();
            multiview.repaint();
            updateStatus();
        }
    }

    /** Next preview frame (at the Settings → Camera multiview rate), and what the camera is really sending. */
    private void tick() {
        boolean showing = isShowing();
        cameras.setWatching(showing); // newly plugged cameras appear by themselves while this page is open
        // the page shows each camera's state, not its picture (right-click → View Camera… opens it): twice a second
        if (frames.getDelay() != STATE_REFRESH_MS) {
            frames.setDelay(STATE_REFRESH_MS);
        }
        if (!showing) {
            return;
        }
        if (multiToggle.isSelected()) {
            multiview.repaint();
        } else {
            monitor.repaint();
        }
        // cameras that are on stay connected while this page is open (their picture is not drawn), so the
        // state is true: INITIALIZING while connecting, READY once pictures arrive, ERROR when it fails
        for (String cam : cameras.cameras()) {
            if (!off.contains(cam)) {
                cameras.frame(cam);
            }
        }
        updateStatus();
        deviceList.repaint();
    }

    private static final int STATE_REFRESH_MS = 500;

    @Override
    public void addNotify() {
        super.addNotify();
        frames.start();
    }

    @Override
    public void removeNotify() {
        frames.stop();
        cameras.setWatching(false);
        super.removeNotify();
    }

    // ------------------------------------------------------------------ layout

    private void buildLibraryPanel() {
        libraryPanel.add(Ui.header(Ui.label("Camera", Palette::text, "h3"), count, detectButton), BorderLayout.NORTH);
        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.setFixedCellHeight(70);
        deviceList.setOpaque(false);
        deviceList.setCellRenderer(new CameraCard());
        deviceList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                menu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                menu(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = deviceList.locationToIndex(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && i >= 0) {
                    preview(deviceModel.get(i)); // double-click: Preview (never Program)
                }
            }

            private void menu(MouseEvent e) {
                int i = deviceList.locationToIndex(e.getPoint());
                if (e.isPopupTrigger() && i >= 0) {
                    deviceList.setSelectedIndex(i);
                    cameraMenu(deviceModel.get(i)).show(deviceList, e.getX(), e.getY());
                }
            }
        });
        JScrollPane scroll = new JScrollPane(deviceList);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        JLabel empty = Ui.label("<html><center>No camera detected.<br>Connect a webcam or HDMI capture device,<br>"
                + "then click the refresh button to detect again.<br><br>"
                + (com.jamc.worship.camera.Ndi.isInstalled()
                        ? "NDI® sources on this network (phones, PCs) show up here as \"NDI: …\"."
                        : "Phones over WiFi: install the free NDI Tools (ndi.video)<br>to use NDI® cameras.")
                + "<br><small>NDI® is a registered trademark of Vizrt NDI AB.</small></center></html>",
                Palette::mutedText, null);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setVerticalAlignment(SwingConstants.TOP);
        empty.setBorder(BorderFactory.createEmptyBorder(40, 16, 0, 16));
        listHolder.add(scroll, "list");
        listHolder.add(empty, "empty");
        listHolder.setBorder(Ui.line(true, false, false, false));
        libraryPanel.add(listHolder, BorderLayout.CENTER);
    }

    private JPopupMenu cameraMenu(String cam) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem view = item("View Camera…", "video", () -> viewCamera(cam));
        view.setEnabled(canView(cam));
        view.setToolTipText(canView(cam) ? "See this camera's live picture in a window"
                : isOff(cam) ? "Turn the camera on first" : "The camera has no picture (" + statusOf(cam).label() + ")");
        m.add(view);
        m.addSeparator();
        m.add(item("Send to Preview", "monitor", () -> preview(cam)));
        if (settings.directSwitch()) {
            m.add(item("Switch Program Now (direct)", "video", () -> switchProgram(cam)));
        }
        m.addSeparator();
        m.add(item("Configure", "sliders", () -> {
            device.setSelectedItem(cam);
            multiToggle.setSelected(false);
            config.set(K_MULTI, "false");
            config.save();
            showView();
        }));
        if (isOff(cam)) {
            m.add(item("Turn Camera On", "video", () -> setOff(cam, false)));
        } else if (!inUse(cam)) {
            m.add(item("Turn Camera Off", "x", () -> setOff(cam, true)));
        }
        m.add(item("Refresh Camera", "refresh", () -> refreshCamera(cam)));
        m.add(item("Rename…", "edit", () -> renameCamera(cam)));
        if (cameras.status(cam) == CameraStatus.DISCONNECTED) {
            m.addSeparator();
            m.add(item("Forget This Camera", "trash", () -> {
                names.forget(cam);
                setDevices(cameras.cameras());
            }));
        }
        return m;
    }

    /** View Camera… works for a camera that is on and not failed or unplugged. */
    private boolean canView(String cam) {
        return cam != null && !isOff(cam) && !statusOf(cam).isOffline();
    }

    /**
     * Right-click → View Camera…: the camera's live picture in its own window (the page itself shows the state
     * only). The picture is drawn only while the window is open; closing it stops that.
     */
    private void viewCamera(String cam) {
        if (!canView(cam)) {
            return;
        }
        cameras.frame(cam); // connect at once
        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        javax.swing.JDialog dialog = new javax.swing.JDialog(owner, names.label(cam) + " – Camera");
        JComponent view = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, getWidth(), getHeight());
                Rectangle r = fit16x9(0, 0, getWidth(), getHeight());
                r.x = (getWidth() - r.width) / 2;
                r.y = (getHeight() - r.height) / 2;
                BufferedImage img = isOff(cam) ? null : cameras.frame(cam);
                if (img != null) {
                    SourceConfig c = new SourceConfig();
                    c.fit = cam.equals(selectedCamera()) ? currentFit() : FitMode.FIT;
                    c.mirror = cam.equals(selectedCamera()) && mirror.isSelected() ? Boolean.TRUE : null;
                    Compositor.drawCamera(g2, img, r, c, 1f);
                } else {
                    stateCard(g2, r, cam, isOff(cam), 1f); // connecting, failed or turned off meanwhile
                }
                CameraStatus st = statusOf(cam);
                boolean off = isOff(cam);
                badgeRight(g2, r.x + r.width - 8, r.y + 8, off ? "OFF" : st.label(), off ? OFF_COLOR : st.color(),
                        off ? Color.WHITE : st.textColor());
                g2.dispose();
            }
        };
        view.setPreferredSize(new Dimension(960, 540));
        dialog.getContentPane().setBackground(Color.BLACK);
        dialog.getContentPane().add(view, BorderLayout.CENTER);
        // the live picture at the camera preview rate, only while this window is open
        Timer t = new Timer(Math.max(15, 1000 / Math.max(1, settings.previewFps())), e -> view.repaint());
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                t.stop();
            }
        });
        dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        t.start();
        try {
            com.jamc.worship.ui.SoftModal.showAndWait(dialog);
        } finally {
            t.stop();
        }
    }

    private static JMenuItem item(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon, Icons.SMALL));
        i.addActionListener(e -> r.run());
        return i;
    }

    private JComponent buildControls() {
        JPanel p = Ui.transparent(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        row(p, g, "Device", device);
        statusLabel.setToolTipText("READY, INITIALIZING, DISCONNECTED or ERROR (hover for the reason), plus PREVIEW / LIVE "
                + "when a Preview or Program scene uses this camera");
        row(p, g, "Status", statusLabel);
        size.setToolTipText("Capture resolution (changing it restarts this camera's feed)");
        row(p, g, "Resolution", size);
        fps.setToolTipText("Frames per second (changing it restarts this camera's feed)");
        row(p, g, "FPS", fps);
        row(p, g, "", capsNote);
        liveSize.setToolTipText("What the camera is actually sending now. Auto picks the best size this camera supports.");
        row(p, g, "Live", liveSize);
        audioInput.setToolTipText("The microphone / HDMI audio that belongs to this camera. Camera audio is heard only "
                + "where a scene's camera source switches AUDIO on (off by default).");
        row(p, g, "Audio input", audioInput);
        row(p, g, "", keepAspect);
        row(p, g, "", mirror);
        row(p, g, "Fit", fit);
        JPanel send = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 6));
        send.add(sendToPreview);
        g.insets = new Insets(10, 0, 0, 0);
        row(p, g, "", send);
        JPanel direct = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        direct.add(switchNow);
        row(p, g, "", direct);
        g.weighty = 1;
        p.add(Ui.transparent(new BorderLayout()), g);
        return p;
    }

    private static void row(JPanel p, GridBagConstraints g, String label, JComponent c) {
        g.gridx = 0;
        g.weightx = 0;
        Insets top = g.insets == null || g.insets.top == 0 ? new Insets(0, 0, 8, 10) : new Insets(g.insets.top, 0, 8, 10);
        g.insets = top;
        p.add(Ui.label(label, Palette::secondaryText, "small"), g);
        g.gridx = 1;
        g.weightx = 1;
        g.insets = new Insets(top.top, 0, 8, 0);
        p.add(c, g);
        g.gridy++;
        g.insets = new Insets(0, 0, 8, 0);
    }

    private void showView() {
        viewCards.show(viewHolder, multiToggle.isSelected() ? "multi" : "single");
        viewHolder.repaint();
    }

    // ------------------------------------------------------------------ devices

    private void detect() {
        detectButton.setEnabled(false);
        count.setText("Detecting…");
        BackgroundTask.run("Detect cameras", cameras::detect, found -> {
            detectButton.setEnabled(true);
            setDevices(found);
            statusHandler.accept(found.isEmpty() ? "No camera detected." : found.size() + (found.size() == 1 ? " camera" : " cameras") + " found.");
        }, err -> {
            detectButton.setEnabled(true);
            setDevices(cameras.cameras());
        });
    }

    /** Connected cameras, then remembered ones that are unplugged now (they show DISCONNECTED), by camera number. */
    private List<String> allDevices(List<String> connected) {
        Set<String> all = new LinkedHashSet<>(connected);
        all.addAll(names.remembered());
        List<String> out = new ArrayList<>(all);
        out.forEach(names::number); // a new camera gets the next number
        out.sort((a, b) -> Integer.compare(names.number(a), names.number(b)));
        return out;
    }

    private void setDevices(List<String> connected) {
        List<String> all = allDevices(connected);
        loading = true;
        try {
            String keep = selectedCamera() != null ? selectedCamera() : config.get(K_DEVICE, null);
            deviceModel.clear();
            deviceModel.addAll(all);
            device.removeAllItems();
            all.forEach(device::addItem);
            String pick = keep != null && all.contains(keep) ? keep
                    : connected.isEmpty() ? (all.isEmpty() ? null : all.getFirst()) : connected.getFirst();
            device.setSelectedItem(pick);
            deviceList.setSelectedValue(pick, true);
            count.setText(connected.size() + (connected.size() == 1 ? " camera" : " cameras"));
            listCards.show(listHolder, all.isEmpty() ? "empty" : "list");
            refillAudioInputs();
        } finally {
            loading = false;
        }
        deviceChanged();
    }

    private String selectedCamera() {
        Object v = device.getSelectedItem();
        return v == null ? null : v.toString();
    }

    private void deviceChanged() {
        String cam = selectedCamera();
        updateTitle();
        sendToPreview.setEnabled(cam != null && MediaEngine.get().isAvailable());
        refresh.setEnabled(cam != null);
        loading = true;
        try {
            deviceList.setSelectedValue(cam, true);
            refillFormats(cam, cam == null ? null : cameras.capabilities(cam));
            selectAudioInput(cam);
        } finally {
            loading = false;
        }
        if (cam != null) {
            config.set(K_DEVICE, cam);
            config.save();
            applyFormat(cam);
            cameras.queryCapabilities(cam, caps -> {
                if (Objects.equals(cam, selectedCamera())) {
                    loading = true;
                    try {
                        refillFormats(cam, caps);
                    } finally {
                        loading = false;
                    }
                }
            });
        }
        updateStatus();
        monitor.repaint();
    }

    private void updateTitle() {
        String cam = selectedCamera();
        title.setText(cam == null ? "No camera" : names.full(cam));
        deviceLabel.setText(cam == null ? " " : cam);
    }

    /**
     * Resolution and frame rate choices: only what the camera reports (through FFmpeg) when that is known, so an
     * unsupported combination is never offered; otherwise the common sizes, with a note.
     */
    private void refillFormats(String cam, CameraManager.Capabilities caps) {
        String[] saved = cam == null ? new String[]{"", ""} : savedFormat(cam);
        size.removeAllItems();
        size.addItem(DEFAULT);
        List<String> sizes = caps != null ? caps.sizes() : List.of(COMMON_SIZES);
        sizes.forEach(size::addItem);
        if (saved[0] != null && !saved[0].isBlank() && !sizes.contains(saved[0])) {
            size.addItem(saved[0]);
        }
        size.setSelectedItem(saved[0] == null || saved[0].isBlank() ? DEFAULT : saved[0]);
        fps.removeAllItems();
        fps.addItem(DEFAULT_FPS);
        List<Integer> rates = caps != null ? caps.rates() : List.of(COMMON_RATES);
        rates.forEach(fps::addItem);
        int rate = parseRate(saved[1]);
        if (rate > 0 && !rates.contains(rate)) {
            fps.addItem(rate);
        }
        fps.setSelectedItem(rate > 0 ? (Object) rate : DEFAULT_FPS);
        capsNote.setText(cam == null ? " " : caps != null ? "Formats reported by the camera."
                : "<html>Common formats (the camera's own list needs FFmpeg). A format the camera lacks falls back "
                + "to the best it has.</html>");
    }

    private void refillAudioInputs() {
        audioInput.removeAllItems();
        audioInput.addItem(AUTO_AUDIO);
        audioInput.addItem(NO_AUDIO);
        cameras.audioInputs().forEach(audioInput::addItem);
    }

    private void selectAudioInput(String cam) {
        String chosen = cam == null ? null : names.audioInput(cam);
        if (chosen == null) {
            audioInput.setSelectedItem(AUTO_AUDIO);
        } else if (chosen.isBlank()) {
            audioInput.setSelectedItem(NO_AUDIO);
        } else {
            if (((javax.swing.DefaultComboBoxModel<String>) audioInput.getModel()).getIndexOf(chosen) < 0) {
                audioInput.addItem(chosen); // unplugged now: keep showing the choice
            }
            audioInput.setSelectedItem(chosen);
        }
        audioInput.setEnabled(cam != null);
    }

    private void audioChanged() {
        String cam = selectedCamera();
        Object v = audioInput.getSelectedItem();
        if (cam == null || v == null) {
            return;
        }
        String choice = AUTO_AUDIO.equals(v) ? null : NO_AUDIO.equals(v) ? "" : v.toString();
        names.setAudioInput(cam, choice);
        String now = cameras.audioDevice(cam);
        statusHandler.accept(names.full(cam) + ": audio input " + (now == null ? "none" : now) + ".");
        updateStatus();
    }

    private void formatChanged() {
        String cam = selectedCamera();
        if (cam == null) {
            return;
        }
        String s = DEFAULT.equals(size.getSelectedItem()) || size.getSelectedItem() == null ? "" : (String) size.getSelectedItem();
        String r = fps.getSelectedItem() instanceof Integer i ? String.valueOf(i) : "";
        config.set(formatKey(cam), s + "@" + r);
        config.save();
        applyFormat(cam);
        statusHandler.accept(names.full(cam) + ": " + (s.isEmpty() ? "best resolution" : s) + ", "
                + (r.isEmpty() ? "default frame rate" : r + " fps") + ".");
    }

    private void applyFormat(String cam) {
        String[] f = savedFormat(cam);
        cameras.setFormat(cam, f[0] == null || f[0].isBlank() ? null : f[0], parseRate(f[1]));
    }

    private String[] savedFormat(String cam) {
        String[] f = config.get(formatKey(cam), "").split("@", -1);
        return f.length == 2 ? f : new String[]{"", ""};
    }

    private static int parseRate(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatKey(String cam) {
        return "camera.format." + Integer.toHexString(cam.hashCode());
    }

    private void refreshCamera(String cam) {
        if (cam != null) {
            cameras.refresh(cam);
            statusHandler.accept("Refreshing " + names.label(cam) + "…");
        }
    }

    private void renameCamera(String cam) {
        if (cam == null) {
            return;
        }
        String now = names.nickname(cam);
        Object name = JOptionPane.showInputDialog(this, "Name for " + names.tag(cam) + " (" + cam + "):\n"
                + "Leave empty to use the device name.", "Rename Camera", JOptionPane.PLAIN_MESSAGE, null, null,
                now == null ? "" : now);
        if (name == null) {
            return;
        }
        names.setNickname(cam, name.toString());
        statusHandler.accept(names.tag(cam) + " is now \"" + names.label(cam) + "\".");
    }

    private void preview(String cam) {
        if (cam != null) {
            actions.sendToPreview(cam, currentFit(), mirror.isSelected());
        }
    }

    private void switchProgram(String cam) {
        if (cam != null && settings.directSwitch()) {
            actions.switchProgram(cam, currentFit(), mirror.isSelected());
        }
    }

    private FitMode currentFit() {
        return keepAspect.isSelected() ? (FitMode) fit.getSelectedItem() : FitMode.STRETCH;
    }

    private void saveOptions() {
        config.set(K_MIRROR, String.valueOf(mirror.isSelected()));
        config.set(K_FIT, currentFit().name());
        config.save();
        monitor.repaint();
    }

    // ------------------------------------------------------------------ on / off

    /**
     * True when the operator turned this camera off here: its tile shows no picture and its feed is closed. A camera
     * used by Program or Preview is never shown as off (the app keeps it running).
     */
    private boolean isOff(String cam) {
        return cam != null && off.contains(cam) && !inUse(cam);
    }

    /** On Program or Preview: the app keeps this camera open, so it cannot be turned off here. */
    private boolean inUse(String cam) {
        CameraStatus st = statusOf(cam);
        return st == CameraStatus.LIVE || st == CameraStatus.PREVIEW;
    }

    private void setOff(String cam, boolean turnOff) {
        if (cam == null) {
            return;
        }
        if (turnOff) {
            if (inUse(cam)) {
                return; // on Program / Preview: the app keeps it running
            }
            off.add(cam);
            cameras.close(cam);
        } else {
            off.remove(cam);
            cameras.frame(cam); // connect now: INITIALIZING until its pictures arrive, then READY
        }
        config.set(K_OFF, String.join("|", off));
        config.save();
        statusHandler.accept(names.label(cam) + (turnOff ? " turned off." : " turned on."));
        deviceList.repaint();
        multiview.repaint();
        monitor.repaint();
        updateStatus();
    }

    // ------------------------------------------------------------------ status

    /** READY / INITIALIZING / DISCONNECTED / ERROR from the camera itself; LIVE / PREVIEW when a scene uses it. */
    private CameraStatus statusOf(String cam) {
        CameraStatus device = cameras.status(cam);
        if (device.isOffline()) {
            return device;
        }
        if (device == CameraStatus.READY && !cameras.connected(cam)) {
            return CameraStatus.INITIALIZING; // detected, but READY only once its pictures really arrive
        }
        if (uses(actions.programScene(), cam)) {
            return CameraStatus.LIVE;
        }
        if (uses(actions.previewScene(), cam)) {
            return CameraStatus.PREVIEW;
        }
        return device;
    }

    private boolean uses(Scene scene, String cam) {
        if (scene == null || cam == null) {
            return false;
        }
        String first = cameras.cameras().isEmpty() ? null : cameras.cameras().getFirst();
        for (Source s : scene.getSources()) {
            if (s.isVisible() && s.getType() == SourceType.CAMERA) {
                String c = s.getConfig().camera == null || s.getConfig().camera.isBlank() ? first : s.getConfig().camera;
                if (cam.equals(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String formatText(String cam) {
        String live = cameras.liveSize(cam);
        int rate = cameras.liveFps(cam);
        return live == null ? null : live + (rate > 0 ? " · " + rate + " FPS" : "");
    }

    private void updateStatus() {
        String cam = selectedCamera();
        boolean direct = settings.directSwitch();
        switchNow.setVisible(direct);
        switchNow.setEnabled(cam != null && direct);
        if (cam == null) {
            statusLabel.setText("–");
            liveSize.setText("–");
        } else {
            CameraStatus st = statusOf(cam);
            statusLabel.setText(st.label());
            statusLabel.setForeground(st.isOffline() ? Palette.warning() : st == CameraStatus.LIVE ? Palette.live()
                    : st == CameraStatus.READY ? CameraStatus.READY_TEXT : Palette.text());
            statusLabel.setToolTipText(cameras.problem(cam));
            String f = formatText(cam);
            liveSize.setText(f == null ? (st.isOffline() ? "–" : "starting…") : f);
            if (isOff(cam)) {
                statusLabel.setText("OFF");
                statusLabel.setForeground(Palette.mutedText());
                statusLabel.setToolTipText("Turned off on this page. Hover its picture and click the power button to turn it on.");
                liveSize.setText("–");
            }
        }
        // one line above the picture when a camera fails because the USB controller is full
        String warn = null;
        for (int i = 0; i < deviceModel.size() && warn == null; i++) {
            if (cameras.bandwidthWarning(deviceModel.get(i))) {
                warn = cameras.problem(deviceModel.get(i));
            }
        }
        bandwidth.setText(warn == null ? " " : "<html><b>" + warn.replaceFirst(":", ":</b>") + "</html>");
        bandwidth.setVisible(warn != null);
    }

    // ------------------------------------------------------------------ painting

    /** A camera card in the library column: CAM n, nickname, device, size · FPS, audio, status badge. */
    private final class CameraCard extends JComponent implements ListCellRenderer<String> {
        private String cam;
        private boolean selected;

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            cam = value;
            selected = isSelected;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            if (selected) {
                g2.setColor(Palette.accent());
                g2.fillRoundRect(4, 3, w - 8, h - 6, 10, 10);
            }
            Font base = getFont() == null ? new Font("Segoe UI", Font.PLAIN, 12) : getFont();
            Color text = selected ? Color.WHITE : Palette.text();
            Color sub = selected ? new Color(255, 255, 255, 190) : Palette.secondaryText();
            // CAM n tag
            String tag = names.tag(cam);
            g2.setFont(base.deriveFont(Font.BOLD, 10f));
            FontMetrics tm = g2.getFontMetrics();
            int tagW = tm.stringWidth(tag == null ? "CAM" : tag) + 10;
            g2.setColor(selected ? new Color(255, 255, 255, 50) : Palette.secondaryBackground());
            g2.fillRoundRect(12, 10, tagW, 18, 8, 8);
            g2.setColor(text);
            g2.drawString(tag == null ? "CAM" : tag, 17, 10 + (18 + tm.getAscent() - tm.getDescent()) / 2);
            // status badge on the right
            CameraStatus st = statusOf(cam);
            boolean isOff = isOff(cam);
            String stText = isOff ? "OFF" : st.label();
            g2.setFont(base.deriveFont(Font.BOLD, 9.5f));
            FontMetrics bm = g2.getFontMetrics();
            int bw = bm.stringWidth(stText) + 12;
            int bx = w - bw - 12;
            g2.setColor(isOff ? OFF_COLOR : st.color());
            g2.fillRoundRect(bx, 10, bw, 18, 8, 8);
            g2.setColor(isOff ? Color.WHITE : st.textColor());
            g2.drawString(stText, bx + 6, 10 + (18 + bm.getAscent() - bm.getDescent()) / 2);
            // nickname, device, format · audio
            int x = 12 + tagW + 8;
            g2.setFont(base.deriveFont(Font.BOLD, 12.5f));
            g2.setColor(text);
            g2.drawString(clip(g2, names.label(cam).toUpperCase(java.util.Locale.ROOT), bx - x - 6), x, 24);
            g2.setFont(base.deriveFont(Font.PLAIN, 11f));
            g2.setColor(sub);
            g2.drawString(clip(g2, cam, w - 24), 12, 44);
            String fmt = formatText(cam);
            String audio = cameras.audioDevice(cam) == null ? "NO AUDIO" : "AUDIO";
            String running = isOff ? "Turned off" : fmt == null ? (st.isOffline() ? "–" : "Not running") : fmt;
            g2.drawString(clip(g2, running + "  ·  " + audio, w - 24), 12, 60);
            g2.dispose();
        }

        private String clip(Graphics2D g, String s, int width) {
            FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(s) <= width) {
                return s;
            }
            String t = s;
            while (t.length() > 1 && fm.stringWidth(t + "…") > width) {
                t = t.substring(0, t.length() - 1);
            }
            return t + "…";
        }
    }

    /** The live camera picture with the chosen fit and mirror, 16:9. */
    private final class Monitor extends JComponent {
        private boolean hover;
        private Rectangle powerButton;

        Monitor() {
            setToolTipText(""); // tooltips come from getToolTipText(MouseEvent)
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    String cam = selectedCamera();
                    boolean in = cam != null && fit16x9(0, 0, getWidth(), getHeight()).contains(e.getPoint());
                    if (in != hover) {
                        hover = in;
                        repaint();
                    }
                    setCursor(powerButton != null && hover && powerButton.contains(e.getPoint())
                            ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) : null);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    setCursor(null);
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    String cam = selectedCamera();
                    if (cam != null && (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e))) {
                        cameraMenu(cam).show(Monitor.this, e.getX(), e.getY()); // View Camera…, Turn On / Off…
                    } else if (SwingUtilities.isLeftMouseButton(e) && hover && powerButton != null && powerButton.contains(e.getPoint())) {
                        setOff(cam, !isOff(cam));
                    } else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && cam != null && canView(cam)) {
                        viewCamera(cam); // double-click: the live picture in a window
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            String cam = selectedCamera();
            if (hover && powerButton != null && powerButton.contains(e.getPoint())) {
                return isOff(cam) ? "Turn this camera on" : "Turn this camera off";
            }
            return cam == null ? null : "Right-click: View Camera… (its live picture in a window)";
        }

        @Override
        protected void paintComponent(Graphics g) {
            com.jamc.worship.output.Smoothness.drawn("Camera page");
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle r = fit16x9(0, 0, getWidth(), getHeight());
            g2.setColor(Color.BLACK);
            g2.fillRect(r.x, r.y, r.width, r.height);
            String cam = selectedCamera();
            boolean isOff = isOff(cam);
            // the camera's name and state; its live picture opens in a window (right-click → View Camera…)
            if (cam == null) {
                centered(g2, r, "No camera detected", 14f, Palette.mutedText());
            } else {
                stateCard(g2, r, cam, isOff, 1f);
            }
            badge(g2, r.x + 6, r.y + 6, "CAMERA", new Color(0, 0, 0, 170));
            powerButton = null;
            if (cam != null) {
                CameraStatus st = statusOf(cam);
                Rectangle b = badgeRight(g2, r.x + r.width - 6, r.y + 6, isOff ? "OFF" : st.label(), isOff ? OFF_COLOR : st.color(),
                        isOff ? Color.WHITE : st.textColor());
                if (hover && !inUse(cam)) {
                    powerButton = paintPower(g2, b.x - 6, r.y + 6, isOff);
                }
            }
            g2.setColor(Palette.border());
            g2.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            g2.dispose();
        }
    }

    /**
     * MULTI-CAMERA VIEW: every camera live in a grid sized to the camera count (1, 2, 4, 6, 8...). Click a tile to
     * configure that camera; double-click sends it to Preview; with Direct Camera Switching on, its SWITCH NOW
     * pill puts it on Program at once. Cameras not in Preview or Program refresh at half the multiview rate.
     */
    private final class MultiView extends JComponent {
        private final List<Rectangle> tiles = new ArrayList<>();
        private final List<Rectangle> switchPills = new ArrayList<>();
        private final List<Rectangle> powerButtons = new ArrayList<>();
        private List<String> shown = List.of();
        private int hover = -1;

        MultiView() {
            setToolTipText(""); // tooltips come from getToolTipText(MouseEvent)
            MouseAdapter hovering = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int i = tileAt(e.getX(), e.getY());
                    if (i != hover) {
                        int old = hover;
                        hover = i;
                        if (old >= 0 && old < tiles.size()) {
                            repaint(tiles.get(old));
                        }
                        if (i >= 0) {
                            repaint(tiles.get(i));
                        }
                    }
                    setCursor(powerAt(e.getPoint()) >= 0
                            ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) : null);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (hover >= 0 && hover < tiles.size()) {
                        repaint(tiles.get(hover));
                    }
                    hover = -1;
                    setCursor(null);
                }
            };
            addMouseMotionListener(hovering);
            addMouseListener(hovering);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int i = tileAt(e.getX(), e.getY());
                    if (i < 0) {
                        return;
                    }
                    String cam = shown.get(i);
                    if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                        cameraMenu(cam).show(MultiView.this, e.getX(), e.getY());
                        return;
                    }
                    if (powerAt(e.getPoint()) == i) {
                        setOff(cam, !isOff(cam)); // the hover power button: only this camera, nothing else changes
                        return;
                    }
                    if (settings.directSwitch() && i < switchPills.size() && switchPills.get(i) != null
                            && switchPills.get(i).contains(e.getPoint())) {
                        switchProgram(cam);
                        return;
                    }
                    device.setSelectedItem(cam);
                    if (e.getClickCount() == 2) {
                        preview(cam); // double-click: Preview, never Program
                    }
                }
            });
        }

        private int tileAt(int x, int y) {
            for (int i = 0; i < tiles.size(); i++) {
                if (tiles.get(i).contains(x, y)) {
                    return i;
                }
            }
            return -1;
        }

        /** The tile whose (visible) power button is under this point, or -1. */
        private int powerAt(java.awt.Point p) {
            for (int i = 0; i < powerButtons.size(); i++) {
                Rectangle b = powerButtons.get(i);
                if (b != null && i == hover && b.contains(p)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = powerAt(e.getPoint());
            if (i >= 0 && i < shown.size()) {
                return isOff(shown.get(i)) ? "Turn this camera on" : "Turn this camera off";
            }
            return tileAt(e.getX(), e.getY()) >= 0 ? "Right-click: View Camera… (its live picture in a window)" : null;
        }

        @Override
        protected void paintComponent(Graphics g) {
            com.jamc.worship.output.Smoothness.drawn("Camera page multiview");
            Graphics2D g2 = (Graphics2D) g.create();
            Object interpolation = switch (settings.quality()) {
                case LOW -> RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
                case MEDIUM -> RenderingHints.VALUE_INTERPOLATION_BILINEAR;
                case HIGH -> RenderingHints.VALUE_INTERPOLATION_BICUBIC;
            };
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            shown = new ArrayList<>();
            for (int i = 0; i < deviceModel.size(); i++) {
                shown.add(deviceModel.get(i));
            }
            tiles.clear();
            switchPills.clear();
            powerButtons.clear();
            if (shown.isEmpty()) {
                centered(g2, new Rectangle(0, 0, getWidth(), getHeight()), "No camera detected", 14f, Palette.mutedText());
                g2.dispose();
                return;
            }
            int n = shown.size();
            int cols = n == 1 ? 1 : n <= 4 ? 2 : n <= 6 ? 3 : 4;
            int rows = (n + cols - 1) / cols;
            int gap = 8;
            int cw = (getWidth() - gap * (cols - 1)) / cols;
            int ch = (getHeight() - gap * (rows - 1)) / rows;
            Rectangle clip = g2.getClipBounds();
            for (int i = 0; i < n; i++) {
                int cx = (i % cols) * (cw + gap);
                int cy = (i / cols) * (ch + gap);
                Rectangle r = fit16x9(cx, cy, cw, ch);
                tiles.add(r);
                switchPills.add(null);
                // the power button shows only while the mouse is over this tile (never on a camera in Program / Preview)
                boolean power = i == hover && !inUse(shown.get(i));
                powerButtons.add(power ? powerBounds(r.x + r.width - 6, r.y + 6) : null);                if (clip == null || clip.intersects(r)) {
                    paintTile(g2, r, shown.get(i), i);
                }
            }
            g2.dispose();
        }

        private void paintTile(Graphics2D g2, Rectangle r, String cam, int index) {
            g2.setColor(Color.BLACK);
            g2.fillRect(r.x, r.y, r.width, r.height);
            CameraStatus st = statusOf(cam);
            boolean isOff = isOff(cam);
            // the state (READY in green); the live picture opens in a window (right-click → View Camera…)
            stateCard(g2, r, cam, isOff, 0.8f);
            String tag = names.tag(cam);
            badge(g2, r.x + 6, r.y + 6, (tag == null ? "CAM" : tag) + "  " + names.label(cam).toUpperCase(java.util.Locale.ROOT),
                    new Color(0, 0, 0, 170));
            int pillY = r.y + r.height - 26;
            badge(g2, r.x + 6, pillY, isOff ? "OFF" : st.label(), isOff ? OFF_COLOR : st.color(), isOff ? Color.WHITE : st.textColor());
            if (index < powerButtons.size() && powerButtons.get(index) != null) {
                paintPower(g2, r.x + r.width - 6, r.y + 6, isOff);
            }            if (settings.directSwitch() && !isOff && !st.isOffline() && st != CameraStatus.LIVE) {
                Rectangle pill = badgeRight(g2, r.x + r.width - 6, pillY, "SWITCH NOW", new Color(0x7C3AED));
                switchPills.set(index, pill);
            }
            boolean sel = cam.equals(selectedCamera());
            Color border = st == CameraStatus.LIVE ? Palette.live() : st == CameraStatus.PREVIEW ? CameraStatus.PREVIEW.color()
                    : sel ? Palette.accent() : Palette.border();
            g2.setColor(border);
            g2.setStroke(new java.awt.BasicStroke(st == CameraStatus.LIVE || st == CameraStatus.PREVIEW || sel ? 2.5f : 1f));
            g2.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            g2.setStroke(new java.awt.BasicStroke(1f));
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    private static final Color OFF_COLOR = new Color(0x334155);
    private static final int POWER_W = 80;
    private static final int POWER_H = 24;

    /** Where the hover on/off button goes, its right edge at {@code right}. */
    private static Rectangle powerBounds(int right, int y) {
        return new Rectangle(right - POWER_W, y, POWER_W, POWER_H);
    }

    /**
     * The hover on/off text button (right edge at {@code right}): red "TURN OFF", or green "TURN ON" when the camera
     * is off. Returns its bounds for the click test.
     */
    private Rectangle paintPower(Graphics2D g2, int right, int y, boolean turnOn) {
        Rectangle b = powerBounds(right, y);
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(b.x + 1, b.y + 2, b.width, b.height, 10, 10);
        g.setColor(turnOn ? new Color(0x16A34A) : new Color(0xDC2626));
        g.fillRoundRect(b.x, b.y, b.width, b.height, 10, 10);
        g.setColor(new Color(255, 255, 255, 120));
        g.drawRoundRect(b.x, b.y, b.width - 1, b.height - 1, 10, 10);
        String text = turnOn ? "TURN ON" : "TURN OFF";
        g.setFont((getFont() == null ? new Font("Segoe UI", Font.PLAIN, 12) : getFont()).deriveFont(Font.BOLD, 10.5f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Color.WHITE);
        g.drawString(text, b.x + (b.width - fm.stringWidth(text)) / 2, b.y + (b.height + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
        return b;
    }

    private static Rectangle fit16x9(int x, int y, int availW, int availH) {
        int w = availW;
        int h = (int) Math.round(w * 9 / 16.0);
        if (h > availH) {
            h = availH;
            w = (int) Math.round(h * 16 / 9.0);
        }
        return new Rectangle(x + (availW - w) / 2, y + (availH - h) / 2, Math.max(1, w), Math.max(1, h));
    }

    /** A camera's state in place of its picture: the nickname, and the state large (READY in bright green). */
    private void stateCard(Graphics2D g2, Rectangle r, String cam, boolean isOff, float scale) {
        g2.setPaint(new java.awt.GradientPaint(r.x, r.y, new Color(0x1E293B), r.x, r.y + r.height, new Color(0x0F172A)));
        g2.fillRect(r.x, r.y, r.width, r.height);
        CameraStatus st = statusOf(cam);
        String state = isOff ? "OFF" : st.label();
        Color color = isOff ? Palette.mutedText() : st == CameraStatus.READY ? CameraStatus.READY_TEXT
                : st == CameraStatus.LIVE ? Palette.live() : st == CameraStatus.PREVIEW ? new Color(0x60A5FA)
                : st == CameraStatus.INITIALIZING ? new Color(0xFBBF24) : new Color(0xF87171);
        Font base = getFont() == null ? new Font("Segoe UI", Font.PLAIN, 12) : getFont();
        float big = Math.max(14f, Math.min(40f, r.height / 7f)) * scale;
        g2.setFont(base.deriveFont(Font.BOLD, big));
        FontMetrics sm = g2.getFontMetrics();
        g2.setFont(base.deriveFont(Font.PLAIN, Math.max(11f, big * 0.45f)));
        FontMetrics nm = g2.getFontMetrics();
        String name = names.label(cam);
        int top = r.y + (r.height - sm.getHeight() - nm.getHeight()) / 2;
        g2.setColor(new Color(0x94A3B8));
        g2.drawString(name, r.x + (r.width - nm.stringWidth(name)) / 2, top + nm.getAscent());
        g2.setFont(base.deriveFont(Font.BOLD, big));
        g2.setColor(color);
        String label = "● " + state;
        g2.drawString(label, r.x + (r.width - sm.stringWidth(label)) / 2, top + nm.getHeight() + sm.getAscent());
        String why = isOff ? null : st.isOffline() ? cameras.problem(cam)
                : st == CameraStatus.INITIALIZING && !MediaEngine.get().isAvailable() ? "The camera needs VLC"
                : st == CameraStatus.INITIALIZING ? "Connecting to the camera…" : null;
        if (why != null) {
            g2.setFont(base.deriveFont(Font.PLAIN, 11f));
            FontMetrics wm = g2.getFontMetrics();
            g2.setColor(new Color(0x94A3B8));
            g2.drawString(why, r.x + Math.max(6, (r.width - wm.stringWidth(why)) / 2), top + nm.getHeight() + sm.getHeight() + wm.getAscent() + 4);
        }
    }

    private void centered(Graphics2D g2, Rectangle r, String msg, float size, Color color) {
        g2.setColor(color);
        g2.setFont((getFont() == null ? new Font("Segoe UI", Font.PLAIN, 12) : getFont()).deriveFont(Font.PLAIN, size));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(msg, r.x + (r.width - fm.stringWidth(msg)) / 2, r.y + r.height / 2);
    }

    private Rectangle badge(Graphics2D g2, int x, int y, String text, Color fill) {
        return badge(g2, x, y, text, fill, Color.WHITE);
    }

    private Rectangle badge(Graphics2D g2, int x, int y, String text, Color fill, Color textColor) {
        g2.setFont((getFont() == null ? new Font("Segoe UI", Font.PLAIN, 12) : getFont()).deriveFont(Font.BOLD, 9.5f));
        FontMetrics fm = g2.getFontMetrics();
        Rectangle r = new Rectangle(x, y, fm.stringWidth(text) + 12, fm.getHeight() + 4);
        g2.setColor(fill);
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g2.setColor(textColor);
        g2.drawString(text, x + 6, y + 2 + fm.getAscent());
        return r;
    }

    private Rectangle badgeRight(Graphics2D g2, int right, int y, String text, Color fill) {
        return badgeRight(g2, right, y, text, fill, Color.WHITE);
    }

    private Rectangle badgeRight(Graphics2D g2, int right, int y, String text, Color fill, Color textColor) {
        g2.setFont((getFont() == null ? new Font("Segoe UI", Font.PLAIN, 12) : getFont()).deriveFont(Font.BOLD, 9.5f));
        int w = g2.getFontMetrics().stringWidth(text) + 12;
        return badge(g2, right - w, y, text, fill, textColor);
    }
}
