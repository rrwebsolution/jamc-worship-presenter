package com.jamc.worship.ui.components;

import com.jamc.worship.camera.CameraManager;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.media.MediaEngine;
import com.jamc.worship.model.FitMode;
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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
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
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * Library → Camera: the connected cameras (a list for the library column) and the camera workspace
 * (device, resolution, frame rate, a live preview, aspect / mirror / fit, SEND TO PREVIEW). The preview
 * here only inspects the camera; nothing reaches Preview or Program until SEND TO PREVIEW.
 */
public class CameraWorkspacePanel extends JPanel {

    /** What SEND TO PREVIEW does (puts the Camera scene in Preview with these options). */
    public interface Actions {
        void sendToPreview(String camera, FitMode fit, boolean mirror);
    }

    private static final String DEFAULT = "Auto (best available)";
    private static final String DEFAULT_FPS = "Camera default";
    private static final String[] SIZES = {DEFAULT, "1920x1080", "1280x720", "3840x2160", "640x480"};
    private static final Object[] RATES = {DEFAULT_FPS, 30, 60, 25, 24};
    private static final String K_DEVICE = "camera.workspace.device";
    private static final String K_MIRROR = "camera.workspace.mirror";
    private static final String K_FIT = "camera.workspace.fit";

    private final CameraManager cameras;
    private final AppConfig config;
    private final DefaultListModel<String> deviceModel = new DefaultListModel<>();
    private final JList<String> deviceList = new JList<>(deviceModel);
    private final JPanel libraryPanel = Ui.panel(new BorderLayout(), Palette::panelBackground);
    private final JLabel count = Ui.small(" ");
    private final JButton detectButton = Ui.toolButton("refresh", "Detect cameras again");
    private final JComboBox<String> device = new JComboBox<>();
    private final JComboBox<String> size = new JComboBox<>(SIZES);
    private final JComboBox<Object> fps = new JComboBox<>(RATES);
    private final JCheckBox keepAspect = new JCheckBox("Maintain aspect ratio", true);
    private final JCheckBox mirror = new JCheckBox("Mirror");
    private final JComboBox<FitMode> fit = new JComboBox<>(new FitMode[]{FitMode.FILL, FitMode.FIT});
    private final JButton sendToPreview = Ui.primaryButton("SEND TO PREVIEW", "monitor");
    private final JLabel title = Ui.label("No camera", Palette::text, "h3");
    private final Monitor monitor = new Monitor();
    private final JLabel liveSize = Ui.label("–", Palette::text, "semibold");
    private final Timer frames = new Timer(66, e -> tick());
    private final CardLayout listCards = new CardLayout();
    private final JPanel listHolder = Ui.transparent(listCards);
    private Consumer<String> statusHandler = s -> { };
    private boolean loading;

    public CameraWorkspacePanel(CameraManager cameras, AppConfig config, Actions actions) {
        super(new BorderLayout());
        this.cameras = cameras;
        this.config = config;
        setOpaque(false);
        buildLibraryPanel();

        JLabel badge = Ui.label("CAMERA", Palette::secondaryText, "semibold");
        JPanel heading = Ui.transparent(new BorderLayout(0, 2));
        heading.add(badge, BorderLayout.NORTH);
        heading.add(title, BorderLayout.CENTER);
        heading.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(10, 14, 8, 12)));
        add(heading, BorderLayout.NORTH);

        JPanel view = Ui.transparent(new BorderLayout(12, 0));
        view.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        view.add(monitor, BorderLayout.CENTER);
        view.add(buildControls(), BorderLayout.EAST);
        add(view, BorderLayout.CENTER);

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
        keepAspect.addActionListener(e -> {
            fit.setEnabled(keepAspect.isSelected());
            saveOptions();
        });
        mirror.addActionListener(e -> saveOptions());
        fit.addActionListener(e -> {
            if (!loading) saveOptions();
        });
        detectButton.addActionListener(e -> detect());
        sendToPreview.setToolTipText("Put the Camera scene with this camera in the main PREVIEW. Program stays until TAKE.");
        sendToPreview.addActionListener(e -> {
            String cam = selectedCamera();
            if (cam != null) {
                actions.sendToPreview(cam, currentFit(), mirror.isSelected());
            }
        });

        loading = true;
        try {
            mirror.setSelected(config.getBoolean(K_MIRROR, false));
            String f = config.get(K_FIT, "FILL");
            keepAspect.setSelected(!"STRETCH".equals(f));
            fit.setSelectedItem("FIT".equals(f) ? FitMode.FIT : FitMode.FILL);
            fit.setEnabled(keepAspect.isSelected());
        } finally {
            loading = false;
        }
        setDevices(cameras.cameras());
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    /** The camera list shown in the library column while Library → Camera is selected. */
    public JComponent libraryPanel() {
        return libraryPanel;
    }

    /** Library → Camera was opened: refresh the device list (the preview starts when it is on screen). */
    public void activate() {
        setDevices(cameras.cameras());
        if (cameras.cameras().isEmpty()) {
            detect();
        }
    }

    /** Next preview frame, and the size the camera is really sending. */
    private void tick() {
        monitor.repaint();
        String cam = selectedCamera();
        String now = cam == null || !monitor.isShowing() ? null : cameras.liveSize(cam);
        liveSize.setText(cam == null ? "–" : now == null ? "starting…" : now);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        frames.start();
    }

    @Override
    public void removeNotify() {
        frames.stop();
        super.removeNotify();
    }

    private void buildLibraryPanel() {
        libraryPanel.add(Ui.header(Ui.label("Camera", Palette::text, "h3"), count, detectButton), BorderLayout.NORTH);
        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.setFixedCellHeight(40);
        deviceList.setOpaque(false);
        deviceList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(l, v, i, sel, false);
                lbl.setIcon(Icons.get("video", Icons.MEDIUM, () -> sel ? Color.WHITE : Palette.secondaryText()));
                lbl.setIconTextGap(10);
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
                lbl.setOpaque(sel);
                return lbl;
            }
        });
        JScrollPane scroll = new JScrollPane(deviceList);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        JLabel empty = Ui.label("<html><center>No camera detected.<br>Connect a webcam or HDMI capture device,<br>"
                + "then click ↻ to detect again.</center></html>", Palette::mutedText, null);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setVerticalAlignment(SwingConstants.TOP);
        empty.setBorder(BorderFactory.createEmptyBorder(40, 16, 0, 16));
        listHolder.add(scroll, "list");
        listHolder.add(empty, "empty");
        listHolder.setBorder(Ui.line(true, false, false, false));
        libraryPanel.add(listHolder, BorderLayout.CENTER);
    }

    private JComponent buildControls() {
        JPanel p = Ui.transparent(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        row(p, g, "Device", device);
        size.setToolTipText("Capture resolution (changing it restarts the camera feed)");
        row(p, g, "Resolution", size);
        fps.setToolTipText("Frames per second (changing it restarts the camera feed)");
        row(p, g, "FPS", fps);
        liveSize.setToolTipText("The size the camera is actually sending now. Auto picks the best size this camera supports.");
        row(p, g, "Live size", liveSize);
        row(p, g, "", keepAspect);
        row(p, g, "", mirror);
        row(p, g, "Fit", fit);
        JPanel send = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        send.add(sendToPreview);
        g.insets = new Insets(10, 0, 0, 0);
        row(p, g, "", send);
        g.weighty = 1;
        p.add(Ui.transparent(new BorderLayout()), g);
        p.setPreferredSize(new java.awt.Dimension(250, 10));
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

    private void setDevices(List<String> names) {
        loading = true;
        try {
            String keep = selectedCamera() != null ? selectedCamera() : config.get(K_DEVICE, null);
            deviceModel.clear();
            deviceModel.addAll(names);
            device.removeAllItems();
            names.forEach(device::addItem);
            String pick = keep != null && names.contains(keep) ? keep : names.isEmpty() ? null : names.getFirst();
            device.setSelectedItem(pick);
            deviceList.setSelectedValue(pick, true);
            count.setText(names.size() + (names.size() == 1 ? " camera" : " cameras"));
            listCards.show(listHolder, names.isEmpty() ? "empty" : "list");
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
        title.setText(cam == null ? "No camera" : cam);
        sendToPreview.setEnabled(cam != null && MediaEngine.get().isAvailable());
        loading = true;
        try {
            deviceList.setSelectedValue(cam, true);
            String[] f = cam == null ? null : config.get(formatKey(cam), "").split("@", -1);
            size.setSelectedItem(f != null && f.length == 2 && !f[0].isBlank() ? f[0] : DEFAULT);
            Object rate = DEFAULT_FPS;
            if (f != null && f.length == 2 && !f[1].isBlank()) {
                try {
                    rate = Integer.parseInt(f[1]);
                } catch (NumberFormatException ignored) {
                    // default rate
                }
            }
            fps.setSelectedItem(rate);
        } finally {
            loading = false;
        }
        if (cam != null) {
            config.set(K_DEVICE, cam);
            config.save();
            applyFormat(cam);
        }
        monitor.repaint();
    }

    private void formatChanged() {
        String cam = selectedCamera();
        if (cam == null) {
            return;
        }
        String s = DEFAULT.equals(size.getSelectedItem()) ? "" : (String) size.getSelectedItem();
        String r = fps.getSelectedItem() instanceof Integer i ? String.valueOf(i) : "";
        config.set(formatKey(cam), s + "@" + r);
        config.save();
        applyFormat(cam);
        statusHandler.accept("\"" + cam + "\": " + (s.isEmpty() ? "default resolution" : s) + ", "
                + (r.isEmpty() ? "default frame rate" : r + " fps") + ".");
    }

    private void applyFormat(String cam) {
        String s = DEFAULT.equals(size.getSelectedItem()) ? null : (String) size.getSelectedItem();
        int r = fps.getSelectedItem() instanceof Integer i ? i : 0;
        cameras.setFormat(cam, s, r);
    }

    private static String formatKey(String cam) {
        return "camera.format." + Integer.toHexString(cam.hashCode());
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

    /** The live camera picture with the chosen fit and mirror, 16:9. */
    private final class Monitor extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = (int) Math.round(w * 9 / 16.0);
            if (h > getHeight()) {
                h = getHeight();
                w = (int) Math.round(h * 16 / 9.0);
            }
            Rectangle r = new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
            g2.setColor(Color.BLACK);
            g2.fillRect(r.x, r.y, r.width, r.height);
            String cam = selectedCamera();
            BufferedImage img = cam != null && isShowing() ? cameras.frame(cam) : null;
            if (img != null) {
                Graphics2D c = (Graphics2D) g2.create();
                c.clipRect(r.x, r.y, r.width, r.height);
                if (mirror.isSelected()) {
                    c.translate(r.x * 2 + r.width, 0);
                    c.scale(-1, 1);
                }
                com.jamc.worship.render.SlideRenderer.drawFitted(c, img, r, currentFit());
                c.dispose();
            } else {
                g2.setColor(Palette.mutedText());
                g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
                String msg = cam == null ? "No camera detected" : !MediaEngine.get().isAvailable() ? "The camera preview needs VLC"
                        : "Starting camera…";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, r.x + (r.width - fm.stringWidth(msg)) / 2, r.y + r.height / 2);
            }
            g2.setFont(getFont().deriveFont(Font.BOLD, 9.5f));
            FontMetrics fm = g2.getFontMetrics();
            String badge = "CAMERA PREVIEW";
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(r.x + 6, r.y + 6, fm.stringWidth(badge) + 12, fm.getHeight() + 2, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString(badge, r.x + 12, r.y + 7 + fm.getAscent());
            g2.setColor(Palette.border());
            g2.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            g2.dispose();
        }
    }
}
