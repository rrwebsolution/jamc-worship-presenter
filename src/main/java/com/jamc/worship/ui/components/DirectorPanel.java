package com.jamc.worship.ui.components;

import com.jamc.worship.camera.CameraManager;
import com.jamc.worship.camera.CameraStatus;
import com.jamc.worship.director.AutoDirector;
import com.jamc.worship.director.Box;
import com.jamc.worship.director.CropRect;
import com.jamc.worship.director.PersonTracker;
import com.jamc.worship.director.Role;
import com.jamc.worship.director.ShotTransition;
import com.jamc.worship.director.StageZone;
import com.jamc.worship.director.VirtualShot;
import com.jamc.worship.ui.Shortcuts;
import com.jamc.worship.ui.SoftModal;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Control Room → Auto Director: the AI Smart Auto Director's workspace.
 *
 * <ul>
 *     <li>Top: on/off, operating mode, camera / AI / tracking status, who controls Program, PAUSE / RESUME AI.</li>
 *     <li>Center: the whole camera picture with the people the AI sees, the stage zones, and the PREVIEW (blue) and
 *     LIVE (green) cut-outs. Click a person = a shot of that person in Preview; drag the blue frame = pan; Ctrl + wheel =
 *     zoom. Beside it: Program / Preview details, TAKE, Reject / Next recommendation, digital zoom and pan.</li>
 *     <li>Bottom: the virtual shots (keys 1–5), each with a live thumbnail of its cut-out.</li>
 * </ul>
 * The existing Preview / Program monitors and TAKE above this page stay in charge: this page only chooses the
 * camera's cut-out. {@link #libraryPanel()} is the side panel (settings, priorities, performers, zones).
 */
public final class DirectorPanel extends JPanel {

    /** What the page needs from the main window. */
    public interface Host {
        /** The app's TAKE (the waiting virtual shot, and any other content in Preview). */
        void take();

        /** The TAKE key's name, e.g. "Space". */
        String takeKeyName();

        /** Whether Program's scene shows the director's camera (otherwise the shots are not seen live). */
        boolean programShowsCamera(String camera);

        /** A short message in the status bar. */
        void status(String message);

        /** The recordings (a shot can be recorded into its own file); null when there is none. */
        default com.jamc.worship.stream.StreamManager streams() {
            return null;
        }

        /** The waiting virtual shot changed (the main TAKE button follows). */
        default void shotStateChanged() {
        }
    }

    private static final Color PREVIEW = new Color(0x3B82F6);
    private static final Color LIVE = new Color(0x22C55E);
    private static final Color PAUSED = new Color(0xF59E0B);
    private static final String AUTO_CAMERA = "Automatic (Iriun first)";

    private final AutoDirector director;
    private final CameraManager cameras;
    private final Host host;

    // top
    private final ToggleSwitch onSwitch = new ToggleSwitch("Auto Director", false);
    private final JToggleButton manualBtn = modeButton("MANUAL");
    private final JToggleButton assistedBtn = modeButton("AI ASSISTED");
    private final JToggleButton autoBtn = modeButton("FULL AUTO");
    private final JLabel cameraChip = chip();
    private final JLabel aiChip = chip();
    private final JLabel trackingChip = chip();
    private final JLabel ownerChip = chip();
    private final JButton pauseResume = Ui.button("PAUSE AI", "pause");
    private final JLabel banner = Ui.label(" ", () -> Color.BLACK, "semibold");
    private final JLabel toast = Ui.label(" ", Palette::accent, "semibold");

    // center
    private final SourceView source = new SourceView();
    private final JLabel programInfo = Ui.label("–", Palette::text, "semibold");
    private final JLabel programDetail = Ui.label(" ", Palette::secondaryText, "small");
    private final JLabel previewInfo = Ui.label("–", Palette::text, "semibold");
    private final JLabel previewDetail = Ui.label(" ", Palette::secondaryText, "small");
    private final JButton take = Ui.primaryButton("TAKE", "play");
    private final JButton reject = Ui.button("REJECT", "x");
    private final JButton next = Ui.button("NEXT", "chevron-right");
    private final JButton cancel = Ui.button("CANCEL PREVIEW", "x");
    private final JSlider zoom = new JSlider(100, 200, 100);
    private final JLabel zoomLabel = Ui.label("1.0×", Palette::text, "semibold");
    private final JLabel qualityWarning = Ui.label(" ", Palette::warning, "small");
    private final JToggleButton tracking = new JToggleButton("TRACKING");
    private final JToggleButton adjustLive = new JToggleButton("ADJUST LIVE");
    private final JButton resetWide = Ui.button("RESET WIDE", "refresh");
    private final JButton saveFraming = Ui.button("SAVE FRAMING", "check-circle");
    private final JButton saveAsNew = Ui.button("SAVE AS NEW SHOT", "plus");
    private final JLabel noCameraWarning = Ui.label(" ", Palette::warning, "small");
    // SHOT PREVIEW: the shot clicked on this page, seen here first; SEND TO PREVIEW puts it in Preview
    private VirtualShot picked;
    private CropRect pickedShown;
    private final ShotMonitor shotMonitor = new ShotMonitor();
    /** Off: the big picture shows the clicked shot. On: the whole camera picture. */
    private final JToggleButton fullCamera = new JToggleButton("FULL CAMERA");
    /** What the big picture shows now; the small SHOT PREVIEW shows exactly the same (one framing for both). */
    private BufferedImage viewFrame;
    private CropRect viewCrop = CropRect.WIDE;
    private final JLabel pickedName = Ui.label("Click a shot below", Palette::text, "semibold");
    private final JButton sendToPreview = Ui.primaryButton("SEND TO PREVIEW", "monitor");
    // SHOT RECORDING: the shot in Shot Preview into its own file (e.g. only the preacher), beside the Program recording
    private final JButton recordShot = Ui.button("● RECORD THIS SHOT", null);
    private final JLabel recChip = chip();
    private VirtualShot recordedShot;
    private CropRect recShown;
    private boolean streamsWatched;
    private boolean recSeen;

    // bottom
    private final JPanel shotStrip = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 6));
    private final List<ShotCard> cards = new ArrayList<>();

    // side panel
    private final JPanel side = Ui.panel(new BorderLayout(), Palette::panelBackground);
    private final JRadioButton manualRadio = new JRadioButton("Manual");
    private final JRadioButton assistedRadio = new JRadioButton("AI Assisted");
    private final JRadioButton autoRadio = new JRadioButton("Full Auto");
    private final JLabel selectedChip = chip();
    private final JPanel shotPills = Ui.transparent(new GridLayout(0, 2, 8, 8));
    private final JComboBox<String> cameraCombo = new JComboBox<>();
    private final JComboBox<String> profileCombo = new JComboBox<>();
    private final JPanel priorityRows = Ui.transparent(new GridBagLayout());
    // camera setup: one camera, or multiple cameras with a camera per shot
    private final JRadioButton oneCamera = new JRadioButton("One camera (all shots from the main camera)");
    private final JRadioButton multiCamera = new JRadioButton("Multiple cameras (a camera for each shot)");
    private final JPanel shotCameraRows = Ui.transparent(new GridBagLayout());
    private final JPanel shotCameraBox = Ui.transparent(new BorderLayout(0, 4));
    private JPanel viewBar;
    private final JPanel performerRows = Ui.transparent(new GridBagLayout());
    private final JToggleButton editZones = new JToggleButton("EDIT ZONES");
    private final JComboBox<Role> zoneRole = new JComboBox<>(Role.zoneRoles());

    private final Timer ui = new Timer(100, e -> uiTick());
    private final Timer ai = new Timer(200, e -> aiTick());
    private final Timer brain = new Timer(250, e -> brainTick());
    private Timer toastTimer;
    private boolean updating;
    private List<Integer> performerIds = List.of();
    private BufferedImage lastFrame;
    private int uiTicks;

    public DirectorPanel(AutoDirector director, CameraManager cameras, Host host) {
        super(new BorderLayout());
        this.director = director;
        this.cameras = cameras;
        this.host = host;
        setOpaque(false);
        header = buildTop();
        add(header, BorderLayout.NORTH);
        shotsArea = buildBottom(); // placed right under the camera picture
        add(buildCenter(), BorderLayout.CENTER);
        buildSide();
        director.addListener(() -> SwingUtilities.invokeLater(this::refresh));
        cameras.addListener(() -> SwingUtilities.invokeLater(() -> {
            director.camerasChanged();
            fillCameras();
        }));
        ui.setCoalesce(true);
        ai.setCoalesce(true);
        brain.setCoalesce(true);
        // the AI and the decisions keep running while the operator is on another page (Full Auto during lyrics)
        ai.start();
        brain.start();
        rebuildShots();
        fillCameras();
        fillProfiles();
        refresh();
    }

    /** Shortest page before it scrolls: the camera picture, Shot Preview, all controls and the shot strip. */
    private static final int MIN_PAGE_HEIGHT = 700;
    /** The camera picture is kept small (it is for clicking people and moving the frame), leaving room for the controls. */
    static final int MAX_PICTURE_HEIGHT = 300;
    /** Title, on/off, modes, status and the "AI Paused" banner: stays on top while the page scrolls. */
    private JComponent header;
    /** The virtual shot cards (with ADD SHOT / RESTORE DEFAULTS). */
    private JComponent shotsArea;

    /**
     * This page in a scroll pane that scrolls up and down: on a small screen (or a low workspace) a scroll bar appears
     * on the right and everything can be reached; when there is room the page fills it as before.
     */
    public JComponent scrollPane() {
        remove(header); // the header stays put (sticky); only the rest scrolls
        JPanel holder = new ScrollHolder();
        holder.add(this, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(holder, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        JPanel page = Ui.transparent(new BorderLayout());
        page.add(header, BorderLayout.NORTH);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    /** As wide as the window; as tall as the window when that is enough, else its full height (and it scrolls). */
    private final class ScrollHolder extends JPanel implements javax.swing.Scrollable {
        ScrollHolder() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = DirectorPanel.this.getPreferredSize();
            return new Dimension(d.width, Math.max(MIN_PAGE_HEIGHT, d.height));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
            return Math.max(24, r.height - 48);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    /** The side panel shown in the library column while this page is open. */
    public JComponent libraryPanel() {
        return side;
    }

    /** The page is shown: start drawing the camera picture. */
    public void activate() {
        fillCameras();
        ui.start();
        refresh();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        ui.stop();
    }

    // ------------------------------------------------------------------ timers

    private void uiTick() {
        if (!isShowing()) {
            ui.stop();
            return;
        }
        String cam = viewed();
        lastFrame = director.enabled() && cam != null ? cameras.frame(cam) : null;
        // each shot card shows its own camera's picture
        java.util.Map<String, BufferedImage> now = new java.util.HashMap<>();
        if (director.enabled()) {
            for (String c : director.usedCameras()) {
                now.put(c, c.equals(cam) ? lastFrame : cameras.frame(c));
            }
        }
        cardFrames = now;
        stepView();
        source.repaint();
        shotMonitor.repaint();
        if (++uiTicks % 2 == 0) {
            for (ShotCard c : cards) {
                c.repaint();
            }
        }
        if (uiTicks % 3 == 0) {
            refreshStatus(director.status());
        }
    }

    private void brainTick() {
        director.tick();
    }

    /**
     * The AI looks at the cameras the shots use in turn (each at the analysis rate). Every used camera is also asked
     * for a frame, which keeps it open: a shot of another camera is ready at once, with no "starting" gap.
     */
    private void aiTick() {
        // no lag elsewhere: the cameras are opened and the AI looks only while this page is open, except while Full
        // Auto / AI Assisted run with "keep watching on other pages" ticked (they need to see to choose shots)
        boolean aiRunning = director.mode() != AutoDirector.Mode.MANUAL && !director.paused();
        // a shot being recorded keeps its camera open and its performer followed, on any page
        boolean needed = isShowing() || aiRunning && director.settings().aiOnOtherPages() || recordedShot != null;
        List<String> used = director.enabled() && needed ? director.usedCameras() : List.of();
        int n = Math.max(1, used.size());
        int wanted = Math.max(66, (int) Math.round(1000 / (director.settings().analysisFps() * n)));
        if (ai.getDelay() != wanted) {
            ai.setDelay(wanted);
        }
        if (used.isEmpty()) {
            return;
        }
        aiTurn = (aiTurn + 1) % used.size();
        for (int i = 0; i < used.size(); i++) {
            String cam = used.get(i);
            CameraStatus st = cameras.status(cam);
            if (st != null && st.isOffline()) {
                continue; // disconnected: the AI waits; Program keeps its picture (the camera's own offline image)
            }
            BufferedImage frame = cameras.frame(cam);
            if (i == aiTurn) {
                director.analyze(cam, frame);
            }
        }
    }

    private int aiTurn;
    private volatile java.util.Map<String, BufferedImage> cardFrames = java.util.Map.of();
    /** The camera shown in the big picture (people, zones and frames of that camera); null = the main camera. */
    private String viewedCamera;
    private final JComboBox<String> viewCombo = new JComboBox<>();

    private String viewed() {
        String main = director.camera();
        if (viewedCamera == null || !director.enabled() || !director.multiCamera()) {
            return main;
        }
        return viewedCamera;
    }

    // ------------------------------------------------------------------ building

    private JComponent buildTop() {
        JPanel top = Ui.transparent(new BorderLayout());
        top.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        JPanel row = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel title = Ui.label("AI SMART AUTO DIRECTOR", Palette::text, "h3");
        row.add(title);
        onSwitch.setFocusable(false);
        onSwitch.addActionListener(e -> {
            if (!updating) {
                director.setEnabled(onSwitch.isSelected());
                flash(onSwitch.isSelected() ? "Auto Director on: virtual shots of " + director.camera() : "Auto Director off: the camera is shown as before");
            }
        });
        row.add(onSwitch);
        ButtonGroup g = new ButtonGroup();
        for (JToggleButton b : new JToggleButton[]{manualBtn, assistedBtn, autoBtn}) {
            g.add(b);
            row.add(b);
        }
        manualBtn.addActionListener(e -> setMode(AutoDirector.Mode.MANUAL));
        assistedBtn.addActionListener(e -> setMode(AutoDirector.Mode.ASSISTED));
        autoBtn.addActionListener(e -> setMode(AutoDirector.Mode.AUTO));
        pauseResume.setFocusable(false);
        pauseResume.addActionListener(e -> {
            if (director.paused()) {
                director.resumeAi();
                flash("AI resumed");
            } else {
                director.pauseAi();
                flash("AI paused — manual control");
            }
        });
        row.add(pauseResume);
        top.add(row, BorderLayout.CENTER);
        // second row: camera, AI, tracking, who controls Program, and the last shortcut / action
        JPanel chips = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chips.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        chips.add(cameraChip);
        chips.add(aiChip);
        chips.add(trackingChip);
        chips.add(ownerChip);
        recChip.setVisible(false);
        chips.add(recChip);
        chips.add(toast);
        top.add(chips, BorderLayout.SOUTH);
        banner.setOpaque(true);
        banner.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        banner.setVisible(false);
        JPanel wrap = Ui.transparent(new BorderLayout());
        wrap.add(top, BorderLayout.CENTER);
        wrap.add(banner, BorderLayout.SOUTH);
        return wrap;
    }

    private static JToggleButton modeButton(String text) {
        JToggleButton b = new JToggleButton(text);
        b.setFocusable(false);
        b.putClientProperty(FlatClientProperties.STYLE, "font: bold; selectedBackground: #1677FF; selectedForeground: #FFFFFF");
        return b;
    }

    private static JLabel chip() {
        JLabel l = new JLabel(" ");
        l.setOpaque(true);
        l.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        l.putClientProperty(FlatClientProperties.STYLE, "arc: 999; font: bold -1");
        l.setBackground(Palette.secondaryBackground());
        l.setForeground(Palette.secondaryText());
        return l;
    }

    private JComponent buildCenter() {
        JPanel center = Ui.transparent(new BorderLayout(10, 0));
        center.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        // which camera the big picture shows (multi-camera): its people, zones and cut-outs
        viewBar = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        viewBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        viewBar.add(Ui.label("CAMERA PICTURE", Palette::secondaryText, "small"));
        viewCombo.setFocusable(false);
        viewCombo.setToolTipText("Which camera to look at here: its people, stage zones and shots");
        viewCombo.addActionListener(e -> {
            if (!updating && viewCombo.getSelectedItem() != null) {
                viewedCamera = (String) viewCombo.getSelectedItem();
                performerIds = List.of(); // that camera's people
                refresh();
            }
        });
        viewBar.add(viewCombo);
        JButton camShot = Ui.button("ADD SHOT OF THIS CAMERA", "plus");
        camShot.setFocusable(false);
        camShot.setToolTipText("A new virtual shot from the camera shown here");
        camShot.addActionListener(e -> addShot());
        viewBar.add(camShot);
        // the big picture shows the clicked shot; FULL CAMERA shows the whole camera (click people, move frames, zones)
        fullCamera.setFocusable(false);
        fullCamera.setToolTipText("Show the whole camera picture (to click a person, move the Preview frame or edit zones) instead of the clicked shot");
        fullCamera.addActionListener(e -> source.repaint());
        viewBar.add(fullCamera);
        // left: the (small) camera picture with the virtual shots right under it; right: Shot Preview and the controls
        JPanel stack = Ui.transparent(new BorderLayout());
        stack.add(source, BorderLayout.NORTH);
        JPanel shotsHolder = Ui.transparent(new BorderLayout());
        shotsHolder.add(shotsArea, BorderLayout.NORTH);
        stack.add(shotsHolder, BorderLayout.CENTER);
        JPanel picture = Ui.transparent(new BorderLayout());
        picture.add(viewBar, BorderLayout.NORTH);
        picture.add(stack, BorderLayout.CENTER);
        center.add(picture, BorderLayout.CENTER);

        // 250 px wide, as tall as its controls (the page scrolls when they do not fit)
        JPanel col = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(250, super.getPreferredSize().height);
            }
        };
        col.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 2, 0);
        // SHOT PREVIEW: this page's own look at the chosen shot; nothing reaches Preview until SEND TO PREVIEW
        col.add(tag("◉ SHOT PREVIEW", Color.WHITE), c);
        c.gridy++;
        // the same size and look as a shot card below (not stretched across the column)
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        col.add(shotMonitor, c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.gridy++;
        col.add(pickedName, c);
        c.gridy++;
        c.insets = new Insets(4, 0, 10, 0);
        sendToPreview.setFocusable(false);
        sendToPreview.addActionListener(e -> sendPickedToPreview());
        col.add(sendToPreview, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 10, 0);
        recordShot.setFocusable(false);
        recordShot.setToolTipText("<html>Records only this shot (e.g. the preacher) into its own file, following its performer,<br>"
                + "with the recording's sound. The Program recording is separate and is not changed.</html>");
        recordShot.addActionListener(e -> toggleShotRecording());
        col.add(recordShot, c);
        c.insets = new Insets(0, 0, 2, 0);
        c.gridy++;
        col.add(tag("● LIVE", LIVE), c);
        c.gridy++;
        col.add(programInfo, c);
        c.gridy++;
        col.add(programDetail, c);
        c.gridy++;
        c.insets = new Insets(8, 0, 2, 0);
        col.add(tag("▶ PREVIEW", PREVIEW), c);
        c.insets = new Insets(0, 0, 2, 0);
        c.gridy++;
        col.add(previewInfo, c);
        c.gridy++;
        col.add(previewDetail, c);
        c.gridy++;
        c.insets = new Insets(6, 0, 4, 0);
        take.setFocusable(false);
        take.addActionListener(e -> host.take());
        col.add(take, c);
        c.gridy++;
        JPanel recRow = Ui.transparent(new GridLayout(1, 2, 6, 0));
        reject.setFocusable(false);
        next.setFocusable(false);
        reject.setToolTipText("AI Assisted: not this shot (another recommendation comes)");
        next.setToolTipText("AI Assisted: show another recommendation");
        reject.addActionListener(e -> {
            director.rejectRecommendation();
            flash("Recommendation rejected");
        });
        next.addActionListener(e -> {
            director.nextRecommendation();
            flash("Next recommendation");
        });
        recRow.add(reject);
        recRow.add(next);
        c.insets = new Insets(0, 0, 4, 0);
        col.add(recRow, c);
        c.gridy++;
        cancel.setFocusable(false);
        cancel.setToolTipText("Drop the Preview shot; Preview shows Program's shot again");
        cancel.addActionListener(e -> director.cancelPreview());
        col.add(cancel, c);

        c.gridy++;
        c.insets = new Insets(12, 0, 2, 0);
        col.add(Ui.caption("DIGITAL ZOOM AND PAN"), c);
        c.gridy++;
        c.insets = new Insets(0, 0, 2, 0);
        JPanel zoomRow = Ui.transparent(new BorderLayout(6, 0));
        JButton out = Ui.toolButton("chevron-down", "Zoom out (−)");
        JButton in = Ui.toolButton("chevron-up", "Zoom in (+)");
        out.setFocusable(false);
        in.setFocusable(false);
        out.addActionListener(e -> director.zoomBy(1 / 1.1));
        in.addActionListener(e -> director.zoomBy(1.1));
        zoom.setFocusable(false);
        zoom.setOpaque(false);
        zoom.addChangeListener(e -> {
            if (!updating && zoom.getValueIsAdjusting()) {
                CropRect cur = currentAdjusted();
                director.setCrop(cur.withZoom(zoom.getValue() / 100.0), director.adjustProgram());
            }
        });
        zoomRow.add(out, BorderLayout.WEST);
        zoomRow.add(zoom, BorderLayout.CENTER);
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(zoomLabel);
        right.add(in);
        zoomRow.add(right, BorderLayout.EAST);
        col.add(zoomRow, c);
        c.gridy++;
        qualityWarning.setVisible(false);
        col.add(qualityWarning, c);
        c.gridy++;
        col.add(panPad(), c);
        c.gridy++;
        JPanel toggles = Ui.transparent(new GridLayout(1, 2, 6, 0));
        tracking.setFocusable(false);
        tracking.setToolTipText("Follow the performer (the AI) or keep a fixed cut-out");
        tracking.addActionListener(e -> {
            director.setTracking(tracking.isSelected());
            flash(tracking.isSelected() ? "Tracking on" : "Tracking off: fixed framing");
        });
        adjustLive.setFocusable(false);
        adjustLive.setToolTipText("Zoom and pan the LIVE shot instead of Preview (a manual action: Full Auto pauses)");
        adjustLive.addActionListener(e -> {
            director.setAdjustProgram(adjustLive.isSelected());
            refresh();
        });
        toggles.add(tracking);
        toggles.add(adjustLive);
        col.add(toggles, c);
        c.gridy++;
        resetWide.setFocusable(false);
        resetWide.addActionListener(e -> {
            director.resetToWide();
            flash("Preview: wide shot");
        });
        col.add(resetWide, c);
        c.gridy++;
        JPanel saveRow = Ui.transparent(new GridLayout(2, 1, 0, 4));
        saveFraming.setFocusable(false);
        saveAsNew.setFocusable(false);
        saveFraming.setToolTipText("Save the Preview framing into its shot");
        saveFraming.addActionListener(e -> {
            VirtualShot s = director.saveFraming(false, null);
            flash("Framing saved: " + s.name);
            rebuildShots();
        });
        saveAsNew.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Name of the new shot:", "Preset");
            if (name != null) {
                VirtualShot s = director.saveFraming(true, name);
                flash("New shot saved: " + s.name);
                rebuildShots();
            }
        });
        saveRow.add(saveFraming);
        saveRow.add(saveAsNew);
        col.add(saveRow, c);
        c.gridy++;
        col.add(noCameraWarning, c);
        c.gridy++;
        c.weighty = 1;
        col.add(Ui.transparent(new BorderLayout()), c);
        JScrollPane colScroll = new JScrollPane(col, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(262, col.getPreferredSize().height + 4); // the whole column: nothing cut off
            }
        };
        colScroll.setBorder(null);
        colScroll.setOpaque(false);
        colScroll.getViewport().setOpaque(false);
        center.add(colScroll, BorderLayout.EAST);
        return center;
    }

    private static JLabel tag(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        return l;
    }

    private JComponent panPad() {
        JPanel pad = Ui.transparent(new GridLayout(1, 4, 4, 0));
        String[][] dirs = {{"chevron-left", "Pan left (Alt+←)"}, {"chevron-up", "Pan up (Alt+↑)"},
                {"chevron-down", "Pan down (Alt+↓)"}, {"chevron-right", "Pan right (Alt+→)"}};
        double[][] d = {{-0.1, 0}, {0, -0.1}, {0, 0.1}, {0.1, 0}};
        for (int i = 0; i < 4; i++) {
            JButton b = Ui.toolButton(dirs[i][0], dirs[i][1]);
            b.setFocusable(false);
            double dx = d[i][0];
            double dy = d[i][1];
            b.addActionListener(e -> director.panBy(dx, dy));
            pad.add(b);
        }
        return pad;
    }

    private JComponent buildBottom() {
        JPanel bottom = Ui.transparent(new BorderLayout());
        bottom.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        JPanel head = Ui.transparent(new BorderLayout());
        JLabel shotsCaption = Ui.caption("VIRTUAL SHOTS  ·  click = Shot Preview  ·  double-click = Preview");
        shotsCaption.setToolTipText("Keys 1–5 = Preview  ·  Ctrl+1–5 = straight to LIVE  ·  right-click a shot = edit");
        shotsCaption.setMinimumSize(new Dimension(40, shotsCaption.getPreferredSize().height));
        head.add(shotsCaption, BorderLayout.CENTER);
        JPanel actions = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton add = Ui.button("ADD SHOT", "plus");
        add.setFocusable(false);
        add.addActionListener(e -> addShot());
        JButton defaults = Ui.button("RESTORE DEFAULTS", "refresh");
        defaults.setFocusable(false);
        defaults.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Replace all virtual shots of this profile with the five default shots?",
                    "Restore defaults", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                director.restoreDefaultShots();
                rebuildShots();
            }
        });
        actions.add(add);
        actions.add(defaults);
        head.add(actions, BorderLayout.EAST);
        bottom.add(head, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(shotStrip, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(10, ShotCard.H + 24));
        bottom.add(scroll, BorderLayout.CENTER);
        return bottom;
    }

    private void buildSide() {
        JPanel col = Ui.transparent(new GridBagLayout());
        col.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 6, 0);

        JPanel head = Ui.transparent(new BorderLayout());
        head.add(Ui.label("LiveCat – Smart Camera Director", Palette::text, "h4"), BorderLayout.CENTER);
        col.add(head, c);
        c.gridy++;
        col.add(Ui.label("Operating Mode", Palette::secondaryText, "semibold"), c);
        ButtonGroup modes = new ButtonGroup();
        for (JRadioButton r : new JRadioButton[]{manualRadio, assistedRadio, autoRadio}) {
            modes.add(r);
            r.setOpaque(false);
            r.setFocusable(false);
            c.gridy++;
            c.insets = new Insets(0, 0, 2, 0);
            col.add(r, c);
        }
        manualRadio.addActionListener(e -> setMode(AutoDirector.Mode.MANUAL));
        assistedRadio.addActionListener(e -> setMode(AutoDirector.Mode.ASSISTED));
        autoRadio.addActionListener(e -> setMode(AutoDirector.Mode.AUTO));

        c.gridy++;
        c.insets = new Insets(12, 0, 6, 0);
        JPanel selHead = Ui.transparent(new BorderLayout());
        selHead.add(Ui.label("Selected Virtual Shot", Palette::text, "semibold"), BorderLayout.CENTER);
        selHead.add(selectedChip, BorderLayout.EAST);
        col.add(selHead, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 6, 0);
        col.add(shotPills, c);

        section(col, c, "CAMERA AND PROFILE");
        cameraCombo.setFocusable(false);
        cameraCombo.addActionListener(e -> {
            if (!updating && cameraCombo.getSelectedItem() != null) {
                String sel = (String) cameraCombo.getSelectedItem();
                director.setCamera(AUTO_CAMERA.equals(sel) ? null : sel);
                flash("Virtual shots now come from " + director.camera());
            }
        });
        row(col, c, "Main camera (the scene's camera; its sound stays on)", cameraCombo);
        profileCombo.setFocusable(false);
        profileCombo.addActionListener(e -> {
            if (!updating && profileCombo.getSelectedItem() != null) {
                director.setProfile((String) profileCombo.getSelectedItem());
                rebuildShots();
                flash("Production profile: " + profileCombo.getSelectedItem());
            }
        });
        JButton newProfile = Ui.toolButton("plus", "New production profile (its own shots and zones)");
        newProfile.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Name of the production profile (e.g. Sunday Service, Youth Night):");
            if (name != null && !name.isBlank()) {
                director.setProfile(name.trim());
                fillProfiles();
                rebuildShots();
            }
        });
        JPanel prof = Ui.transparent(new BorderLayout(4, 0));
        prof.add(profileCombo, BorderLayout.CENTER);
        prof.add(newProfile, BorderLayout.EAST);
        row(col, c, "Profile", prof);

        section(col, c, "CAMERA SETUP");
        ButtonGroup setup = new ButtonGroup();
        for (JRadioButton r : new JRadioButton[]{oneCamera, multiCamera}) {
            setup.add(r);
            r.setOpaque(false);
            r.setFocusable(false);
            c.gridy++;
            c.insets = new Insets(0, 0, 2, 0);
            col.add(r, c);
        }
        oneCamera.addActionListener(e -> setMultiCamera(false));
        multiCamera.addActionListener(e -> setMultiCamera(true));
        shotCameraBox.add(Ui.label("Camera for each shot", Palette::secondaryText, "small"), BorderLayout.NORTH);
        shotCameraBox.add(shotCameraRows, BorderLayout.CENTER);
        c.gridy++;
        c.insets = new Insets(6, 0, 6, 0);
        col.add(shotCameraBox, c);

        var s = director.settings();
        section(col, c, "AI SETTINGS");
        row(col, c, "Analysis rate", spinner(s.analysisFps(), 0.5, 15, 0.5, v -> s.setAnalysisFps(v), "per second"));
        row(col, c, "Detection confidence", slider((int) Math.round(s.confidence() * 100), 10, 95, v -> {
            s.setConfidence(v / 100.0);
            director.applyTrackerSettings();
        }));
        row(col, c, "Tracking sensitivity", slider((int) Math.round(s.responsiveness() * 100), 5, 100, v -> {
            s.setResponsiveness(v / 100.0);
            director.applyTrackerSettings();
        }));
        row(col, c, "Follow speed", slider((int) Math.round(s.followSpeed() * 100), 5, 100, v -> s.setFollowSpeed(v / 100.0)));
        JCheckBox otherPages = new JCheckBox("<html><body style='width:190px'>Keep the AI watching on other pages during Full Auto / AI Assisted</body></html>",
                s.aiOnOtherPages());
        otherPages.setOpaque(false);
        otherPages.setFocusable(false);
        otherPages.setToolTipText("<html>Off: the cameras and the AI work only while this page is open (least load).<br>"
                + "On: Full Auto and AI Assisted keep seeing the stage while you work on lyrics or slides.</html>");
        otherPages.addActionListener(e -> {
            s.setAiOnOtherPages(otherPages.isSelected());
            flash(otherPages.isSelected() ? "Full Auto / AI Assisted keep watching on other pages"
                    : "The AI and cameras work only while the Auto Director page is open");
        });
        c.gridy++;
        col.add(otherPages, c);
        c.gridy++;
        col.add(Ui.label("<html><body style='width:200px'>In Manual the cameras and the AI work only while this page is open.</body></html>",
                Palette::mutedText, "small"), c);

        section(col, c, "FULL AUTO TIMING");
        row(col, c, "Minimum shot duration", spinner(s.minShotSeconds(), 1, 60, 1, s::setMinShotSeconds, "s"));
        row(col, c, "Back to wide every", spinner(s.wideEverySeconds(), 10, 600, 5, s::setWideEverySeconds, "s"));

        section(col, c, "TRANSITION AND ZOOM");
        JComboBox<ShotTransition> tr = new JComboBox<>(ShotTransition.values());
        tr.setFocusable(false);
        tr.setSelectedItem(s.transition());
        tr.addActionListener(e -> s.setTransition((ShotTransition) tr.getSelectedItem()));
        row(col, c, "Transition", tr);
        row(col, c, "Transition speed", spinner(s.transitionMs(), 0, 5000, 100, v -> s.setTransitionMs((int) Math.round(v)), "ms"));
        JCheckBox high = new JCheckBox("Allow zoom above 2.0× (loses detail)", s.allowHighZoom());
        high.setOpaque(false);
        high.setFocusable(false);
        high.addActionListener(e -> {
            if (high.isSelected() && JOptionPane.showConfirmDialog(this,
                    "Zooming further than 2× into a 1080p camera shows a cut-out smaller than 960×540.\n"
                            + "The picture gets visibly softer; nothing can bring the lost detail back.\n\nAllow it anyway?",
                    "Higher zoom", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                high.setSelected(false);
            }
            s.setAllowHighZoom(high.isSelected());
            refresh();
        });
        c.gridy++;
        col.add(high, c);

        section(col, c, "SHOT PRIORITIES (FULL AUTO)");
        c.gridy++;
        col.add(priorityRows, c);

        section(col, c, "PERFORMERS");
        c.gridy++;
        col.add(Ui.label("<html>The people the AI sees now. Name them or fix their role; a name stays with the person while tracked.</html>",
                Palette::mutedText, "small"), c);
        c.gridy++;
        col.add(performerRows, c);

        section(col, c, "STAGE ZONES");
        c.gridy++;
        col.add(Ui.label("<html>Where each role usually is. Turn on EDIT ZONES, choose a role, then drag a box on the camera picture. Saved per camera and profile.</html>",
                Palette::mutedText, "small"), c);
        editZones.setFocusable(false);
        editZones.addActionListener(e -> source.repaint());
        zoneRole.setFocusable(false);
        JButton resetZones = Ui.button("DEFAULT ZONES", "refresh");
        resetZones.setFocusable(false);
        resetZones.addActionListener(e -> director.setZones(viewed(), StageZone.defaults()));
        JPanel z = Ui.transparent(new GridLayout(0, 1, 0, 4));
        z.add(editZones);
        z.add(zoneRole);
        z.add(resetZones);
        c.gridy++;
        col.add(z, c);

        c.gridy++;
        c.weighty = 1;
        col.add(Ui.transparent(new BorderLayout()), c);
        side.add(FitWidthColumn.scroll(col), BorderLayout.CENTER);
    }

    private static void section(JPanel col, GridBagConstraints c, String title) {
        c.gridy++;
        c.insets = new Insets(14, 0, 4, 0);
        col.add(Ui.caption(title), c);
        c.insets = new Insets(0, 0, 6, 0);
    }

    private static void row(JPanel col, GridBagConstraints c, String label, JComponent field) {
        c.gridy++;
        c.insets = new Insets(0, 0, 2, 0);
        col.add(Ui.label(label, Palette::secondaryText, "small"), c);
        c.gridy++;
        c.insets = new Insets(0, 0, 8, 0);
        col.add(field, c);
    }

    private static JComponent spinner(double value, double min, double max, double step, Consumer<Double> save, String unit) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(Math.clamp(value, min, max), min, max, step));
        sp.addChangeListener(e -> save.accept(((Number) sp.getValue()).doubleValue()));
        JPanel p = Ui.transparent(new BorderLayout(6, 0));
        p.add(sp, BorderLayout.CENTER);
        p.add(Ui.label(unit, Palette::mutedText, "small"), BorderLayout.EAST);
        return p;
    }

    private static JComponent slider(int value, int min, int max, Consumer<Integer> save) {
        JSlider sl = new JSlider(min, max, Math.clamp(value, min, max));
        sl.setOpaque(false);
        sl.setFocusable(false);
        JLabel v = Ui.label(sl.getValue() + "%", Palette::text, "small");
        sl.addChangeListener(e -> {
            v.setText(sl.getValue() + "%");
            if (!sl.getValueIsAdjusting()) {
                save.accept(sl.getValue());
            }
        });
        JPanel p = Ui.transparent(new BorderLayout(6, 0));
        p.add(sl, BorderLayout.CENTER);
        p.add(v, BorderLayout.EAST);
        return p;
    }

    // ------------------------------------------------------------------ actions

    private void setMode(AutoDirector.Mode m) {
        if (updating) {
            return;
        }
        if (m == AutoDirector.Mode.AUTO && !host.programShowsCamera(director.camera())) {
            flash("Full Auto is on, but Program's scene has no " + director.camera() + " source: the shots are not seen live yet.");
        }
        director.setMode(m);
        if (m != AutoDirector.Mode.MANUAL) {
            flash(m.label() + " on");
        }
    }

    /** Runs an Auto Director shortcut; false = not for the director now (the key goes to its other action). */
    public boolean handle(Shortcuts.Action a) {
        boolean on = director.enabled();
        int shot = a.shotIndex();
        switch (a) {
            case DIRECTOR_PAUSE -> {
                if (!on || director.mode() == AutoDirector.Mode.MANUAL || director.paused()) {
                    return false; // Esc goes on to Close projector / dialogs
                }
                director.pauseAi();
                flash("Esc: AI paused — manual control");
                return true;
            }
            case DIRECTOR_ASSISTED -> {
                director.toggleAssisted();
                flash("AI Assisted " + (director.mode() == AutoDirector.Mode.ASSISTED ? "on" : "off"));
                return true;
            }
            case DIRECTOR_AUTO -> {
                director.toggleAuto();
                flash("Full Auto " + (director.mode() == AutoDirector.Mode.AUTO ? "on" : "off"));
                return true;
            }
            case DIRECTOR_RESUME -> {
                if (!on || director.mode() == AutoDirector.Mode.MANUAL) {
                    return false;
                }
                director.resumeAi();
                flash("AI resumed");
                return true;
            }
            default -> {
            }
        }
        if (!on) {
            return false;
        }
        List<VirtualShot> shots = director.shots();
        if (a.name().startsWith("DIRECTOR_SHOT_")) {
            if (shot >= shots.size()) {
                return false;
            }
            director.selectShot(shot);
            flash((shot + 1) + ": " + shots.get(shot).name + " → Preview");
            return true;
        }
        if (a.name().startsWith("DIRECTOR_TAKE_")) {
            if (shot >= shots.size()) {
                return false;
            }
            director.takeShot(shot);
            flash("Ctrl+" + (shot + 1) + ": " + shots.get(shot).name + " → LIVE");
            return true;
        }
        switch (a) {
            case DIRECTOR_ZOOM_IN -> director.zoomBy(1.1);
            case DIRECTOR_ZOOM_OUT -> director.zoomBy(1 / 1.1);
            case DIRECTOR_PAN_LEFT -> director.panBy(-0.1, 0);
            case DIRECTOR_PAN_RIGHT -> director.panBy(0.1, 0);
            case DIRECTOR_PAN_UP -> director.panBy(0, -0.1);
            case DIRECTOR_PAN_DOWN -> director.panBy(0, 0.1);
            case DIRECTOR_RESET_WIDE -> {
                director.resetToWide();
                flash("Home: Preview → wide shot");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** RECORD THIS SHOT: the shot in Shot Preview into its own file; again = stop. */
    private void toggleShotRecording() {
        com.jamc.worship.stream.StreamManager streams = host.streams();
        if (streams == null) {
            return;
        }
        watchStreams(streams);
        if (streams.shotRecording() != null) {
            if (!streams.shotRecording().finishing()) {
                streams.stopShotRecording();
                flash("Shot recording stopped: the file is being finished");
            }
            return;
        }
        if (picked == null) {
            flash("Click a shot first (it shows in Shot Preview), then RECORD THIS SHOT");
            return;
        }
        if (!director.enabled()) {
            director.setEnabled(true);
        }
        recordedShot = picked;
        recShown = null;
        String name = picked.name;
        streams.startShotRecording(name, this::paintRecordedShot, err -> {
            recordedShot = null;
            JOptionPane.showMessageDialog(this, err, "Shot recording", JOptionPane.WARNING_MESSAGE);
            refresh();
        });
        flash("Recording \"" + name + "\" into its own file (the Program recording is separate)");
        refresh();
    }

    /** One picture of the recorded shot: its camera, cut out and following its performer (smoothly). */
    private boolean paintRecordedShot(Graphics2D g, int w, int h) {
        VirtualShot s = recordedShot;
        if (s == null) {
            return false;
        }
        String cam = director.cameraOf(s);
        BufferedImage frame = cam == null ? null : cameras.frame(cam);
        if (frame == null) {
            return false; // no picture right now: the last one is kept
        }
        CropRect target = director.cropFor(s);
        recShown = recShown == null ? target : recShown.lerp(target, 0.12);
        double sw = frame.getWidth() * recShown.width();
        double sh = frame.getHeight() * recShown.height();
        double sx = frame.getWidth() * recShown.cx() - sw / 2;
        double sy = frame.getHeight() * recShown.cy() - sh / 2;
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.scale(w / sw, h / sh);
        g2.translate(-sx, -sy);
        g2.drawImage(frame, 0, 0, null);
        g2.dispose();
        return true;
    }

    /** The button and the REC chip follow the recording (also when it is stopped elsewhere or fails). */
    private void watchStreams(com.jamc.worship.stream.StreamManager streams) {
        if (!streamsWatched) {
            streamsWatched = true;
            streams.addListener(() -> SwingUtilities.invokeLater(this::refresh));
        }
    }

    private void refreshShotRecording() {
        com.jamc.worship.stream.StreamManager streams = host.streams();
        var rec = streams == null ? null : streams.shotRecording();
        if (rec == null) {
            if (recSeen) { // the recording ended (stopped, or failed): the AI no longer has to follow its shot
                recSeen = false;
                recordedShot = null;
            }
            recordShot.setText("● RECORD THIS SHOT");
            recordShot.putClientProperty(FlatClientProperties.STYLE, "foreground: #EF4444; font: bold");
            recordShot.setEnabled(streams != null && picked != null);
            recChip.setVisible(false);
            return;
        }
        recSeen = true;
        long secs = (System.currentTimeMillis() - rec.startedMillis()) / 1000;
        String time = String.format(Locale.ROOT, "%02d:%02d", secs / 60, secs % 60);
        recordShot.setText(rec.finishing() ? "FINISHING THE FILE…" : "■ STOP RECORDING " + rec.shot() + " · " + time);
        recordShot.putClientProperty(FlatClientProperties.STYLE, "background: #DC2626; foreground: #FFFFFF; font: bold");
        recordShot.setEnabled(!rec.finishing());
        setChip(recChip, "● REC " + rec.shot() + " · " + time, new Color(0x7F1D1D), Color.WHITE);
        recChip.setVisible(true);
    }

    /**
     * One step of what the big picture shows (the clicked shot, gliding with its performer, or the whole camera). Both
     * the big picture and the small SHOT PREVIEW draw this same picture and framing, so they always match.
     */
    private void stepView() {
        if (source.showingShot()) {
            String cam = director.cameraOf(picked);
            BufferedImage frame = cam == null ? null : cardFrames.get(cam);
            if (frame == null && cam != null) {
                frame = cameras.frame(cam);
            }
            CropRect target = director.cropFor(picked);
            pickedShown = pickedShown == null ? target : pickedShown.lerp(target, 0.35);
            viewFrame = frame;
            viewCrop = pickedShown;
        } else {
            viewFrame = lastFrame;
            viewCrop = CropRect.WIDE;
        }
    }

    /** The big picture's current picture (stepped first when it has not been yet, e.g. right after a click). */
    private void ensureView() {
        if (source.showingShot() ? pickedShown == null : viewFrame != lastFrame) {
            stepView();
        }
    }

    /** A click on a shot (card, pill, a person in the picture): only this page's SHOT PREVIEW shows it. */
    private void pick(VirtualShot s) {
        if (s == null) {
            return;
        }
        if (picked == null || !picked.id.equals(s.id)) {
            pickedShown = null; // jump to the new shot's framing
        }
        picked = s;
        flash(s.name + " in Shot Preview · SEND TO PREVIEW (or double-click) to put it in Preview");
        refresh();
    }

    /** The shot in SHOT PREVIEW goes to Preview (then TAKE puts it live). */
    private void sendPickedToPreview() {
        if (picked == null) {
            return;
        }
        if (!director.enabled()) {
            director.setEnabled(true);
        }
        director.selectShot(picked);
        flash(picked.name + " → Preview · press " + host.takeKeyName() + " (TAKE) to put it live");
    }

    /** A short confirmation on this page and in the status bar (shortcuts give visible feedback). */
    void flash(String message) {
        toast.setText(message);
        host.status(message);
        if (toastTimer != null) {
            toastTimer.stop();
        }
        toastTimer = new Timer(2500, e -> toast.setText(" "));
        toastTimer.setRepeats(false);
        toastTimer.start();
    }

    private void addShot() {
        String cam = viewed();
        boolean main = cam == null || cam.equals(director.camera());
        VirtualShot s = main ? VirtualShot.of("New Shot", Role.OTHER, 1.5, true, 2, 6)
                : VirtualShot.of(shortName(cam) + " Wide", Role.WIDE, 1.0, false, 3, 8);
        s.camera = main ? null : cam;
        if (main) {
            CropRect cur = director.status().previewCrop();
            s.setFixedCrop(cur.withZoom(Math.max(1.5, cur.zoom())));
        }
        if (editShot(s, "Add Shot")) {
            List<VirtualShot> list = new ArrayList<>(director.shots());
            list.add(s);
            director.setShots(list);
            rebuildShots();
        }
    }

    /** The shot's settings in a dialog; true when saved. */
    private boolean editShot(VirtualShot s, String title) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), title);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 4, 10);
        JTextField name = new JTextField(s.name, 20);
        String mainLabel = "Main camera (" + (director.camera() == null ? "none" : director.camera()) + ")";
        JComboBox<String> camera = new JComboBox<>();
        camera.addItem(mainLabel);
        for (String cam : cameras.cameras()) {
            if (!cam.equals(director.camera())) {
                camera.addItem(cam);
            }
        }
        if (s.camera != null && !s.camera.equals(director.camera())) {
            if (((javax.swing.DefaultComboBoxModel<String>) camera.getModel()).getIndexOf(s.camera) < 0) {
                camera.addItem(s.camera); // unplugged now: still its camera
            }
            camera.setSelectedItem(s.camera);
        }
        JComboBox<Role> role = new JComboBox<>(Role.values());
        role.setSelectedItem(s.role);
        JSpinner zoomSp = new JSpinner(new SpinnerNumberModel(Math.clamp(s.zoom, 1.0, director.settings().maxZoom()), 1.0,
                director.settings().maxZoom(), 0.1));
        JCheckBox track = new JCheckBox("Follow the performer (AI tracking)", s.tracking);
        JSpinner margin = new JSpinner(new SpinnerNumberModel((int) Math.round(s.margin * 100), 0, 60, 5));
        JComboBox<Object> trans = new JComboBox<>();
        trans.addItem("Default (" + director.settings().transition().label() + ")");
        for (ShotTransition t : ShotTransition.values()) {
            trans.addItem(t);
        }
        trans.setSelectedItem(s.transition == null ? trans.getItemAt(0) : s.transition);
        JSpinner prio = new JSpinner(new SpinnerNumberModel(Math.clamp(s.priority, 0, 5), 0, 5, 1));
        JSpinner hold = new JSpinner(new SpinnerNumberModel(Math.clamp(s.holdSeconds, 1, 120), 1, 120, 1));
        List<Object[]> rowList = new ArrayList<>();
        rowList.add(new Object[]{"Name", name});
        if (director.multiCamera()) {
            rowList.add(new Object[]{"Camera", camera}); // one-camera setup: every shot is of the main camera
        }
        Object[][] rows = {{"Role / zone", role}, {"Zoom (×)", zoomSp}, {"", track},
                {"Framing margin (%)", margin}, {"Transition", trans}, {"Priority (0 = never in Full Auto)", prio},
                {"Full Auto duration (s)", hold}};
        rowList.addAll(java.util.Arrays.asList(rows));
        for (Object[] r : rowList) {
            c.gridx = 0;
            form.add(new JLabel((String) r[0]), c);
            c.gridx = 1;
            form.add((Component) r[1], c);
            c.gridy++;
        }
        boolean[] ok = {false};
        JButton save = Ui.primaryButton("SAVE", "check-circle");
        JButton cancelBtn = Ui.button("CANCEL", null);
        save.addActionListener(e -> {
            s.name = name.getText().isBlank() ? s.name : name.getText().trim();
            s.role = (Role) role.getSelectedItem();
            if (director.multiCamera()) {
                s.camera = mainLabel.equals(camera.getSelectedItem()) ? null : (String) camera.getSelectedItem();
            }
            s.zoom = ((Number) zoomSp.getValue()).doubleValue();
            s.tracking = track.isSelected();
            s.margin = ((Number) margin.getValue()).intValue() / 100.0;
            s.transition = trans.getSelectedItem() instanceof ShotTransition t ? t : null;
            s.priority = ((Number) prio.getValue()).intValue();
            s.holdSeconds = ((Number) hold.getValue()).doubleValue();
            if (!s.tracking) {
                s.setFixedCrop(s.fixedCrop().withZoom(s.zoom));
            }
            ok[0] = true;
            d.dispose();
        });
        cancelBtn.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancelBtn);
        buttons.add(save);
        d.getContentPane().add(form, BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(save);
        d.pack();
        d.setLocationRelativeTo(this);
        SoftModal.showAndWait(d);
        return ok[0];
    }

    private void shotMenu(VirtualShot s, Component at, int x, int y) {
        JPopupMenu m = new JPopupMenu();
        m.add(item("To Preview", () -> director.selectShot(s)));
        m.add(item("TAKE now (straight to LIVE)", () -> {
            int i = director.shots().indexOf(s);
            if (i >= 0) {
                director.takeShot(i);
            }
        }));
        m.addSeparator();
        m.add(item("Edit…", () -> {
            if (editShot(s, "Edit Shot")) {
                director.setShots(director.shots());
                rebuildShots();
            }
        }));
        m.add(item("Rename…", () -> {
            String n = JOptionPane.showInputDialog(this, "Shot name:", s.name);
            if (n != null && !n.isBlank()) {
                s.name = n.trim();
                director.setShots(director.shots());
                rebuildShots();
            }
        }));
        m.add(item("Duplicate", () -> {
            List<VirtualShot> list = new ArrayList<>(director.shots());
            list.add(list.indexOf(s) + 1, s.duplicate());
            director.setShots(list);
            rebuildShots();
        }));
        m.add(item("Save Preview framing into this shot", () -> {
            director.selectShot(s);
            director.setCrop(director.status().previewCrop(), false);
            director.saveFraming(false, null);
            rebuildShots();
        }));
        m.add(item("Move left", () -> move(s, -1)));
        m.add(item("Move right", () -> move(s, 1)));
        m.addSeparator();
        JMenuItem del = item("Delete", () -> {
            List<VirtualShot> list = new ArrayList<>(director.shots());
            if (list.size() <= 1) {
                return;
            }
            if (JOptionPane.showConfirmDialog(this, "Delete the shot \"" + s.name + "\"?", "Delete shot",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                list.remove(s);
                director.setShots(list);
                rebuildShots();
            }
        });
        m.add(del);
        m.show(at, x, y);
    }

    private void move(VirtualShot s, int by) {
        List<VirtualShot> list = new ArrayList<>(director.shots());
        int i = list.indexOf(s);
        int j = i + by;
        if (i < 0 || j < 0 || j >= list.size()) {
            return;
        }
        list.remove(i);
        list.add(j, s);
        director.setShots(list);
        rebuildShots();
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> r.run());
        return i;
    }

    // ------------------------------------------------------------------ refresh

    private void rebuildShots() {
        if (picked != null && !picked.id.startsWith("person:")) {
            String id = picked.id; // the edited or reloaded copy of the shot in Shot Preview (gone when deleted)
            picked = director.shots().stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
        }
        shotStrip.removeAll();
        cards.clear();
        shotPills.removeAll();
        priorityRows.removeAll();
        List<VirtualShot> shots = director.shots();
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 4, 6);
        for (int i = 0; i < shots.size(); i++) {
            VirtualShot s = shots.get(i);
            ShotCard card = new ShotCard(s, i);
            cards.add(card);
            shotStrip.add(card);
            JToggleButton pill = new JToggleButton((i < 5 ? (i + 1) + "  " : "") + s.name);
            pill.setFocusable(false);
            pill.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 999; font: bold; selectedBackground: #EDEDED; selectedForeground: #111111");
            pill.addActionListener(e -> pick(s));
            pill.putClientProperty("shot", s);
            shotPills.add(pill);
            // priority + duration per shot
            c.gridx = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            priorityRows.add(Ui.label(s.name, Palette::text, "small"), c);
            c.gridx = 1;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            JSpinner p = new JSpinner(new SpinnerNumberModel(Math.clamp(s.priority, 0, 5), 0, 5, 1));
            p.setToolTipText("Priority 0–5 (0 = never chosen by Full Auto)");
            p.addChangeListener(e -> {
                s.priority = ((Number) p.getValue()).intValue();
                director.setShots(director.shots());
            });
            priorityRows.add(p, c);
            c.gridx = 2;
            JSpinner h = new JSpinner(new SpinnerNumberModel(Math.clamp((int) Math.round(s.holdSeconds), 1, 120), 1, 120, 1));
            h.setToolTipText("Seconds on Program in Full Auto");
            h.addChangeListener(e -> {
                s.holdSeconds = ((Number) h.getValue()).intValue();
                director.setShots(director.shots());
            });
            priorityRows.add(h, c);
            c.gridx = 3;
            priorityRows.add(Ui.label("s", Palette::mutedText, "small"), c);
            c.gridy++;
        }
        shotStrip.revalidate();
        shotStrip.repaint();
        shotPills.revalidate();
        priorityRows.revalidate();
        rebuildShotCameras();
        refresh();
    }

    private void fillCameras() {
        updating = true;
        try {
            cameraCombo.removeAllItems();
            cameraCombo.addItem(AUTO_CAMERA);
            for (String cam : cameras.cameras()) {
                cameraCombo.addItem(cam);
            }
            String chosen = director.settings().camera();
            if (chosen != null && ((javax.swing.DefaultComboBoxModel<String>) cameraCombo.getModel()).getIndexOf(chosen) < 0) {
                cameraCombo.addItem(chosen); // unplugged now: still the chosen one
            }
            cameraCombo.setSelectedItem(chosen == null ? AUTO_CAMERA : chosen);
            // the big picture can show any camera (multi-camera shots)
            viewCombo.removeAllItems();
            String main = director.camera();
            if (main != null) {
                viewCombo.addItem(main);
            }
            for (String cam : cameras.cameras()) {
                if (!cam.equals(main)) {
                    viewCombo.addItem(cam);
                }
            }
            viewCombo.setSelectedItem(viewed());
            rebuildShotCameras();
        } finally {
            updating = false;
        }
    }

    private void setMultiCamera(boolean on) {
        if (updating) {
            return;
        }
        director.setMultiCamera(on);
        if (!on) {
            viewedCamera = null;
        }
        performerIds = List.of();
        rebuildShots();
        flash(on ? "Multiple cameras: choose a camera for each shot" : "One camera: every shot comes from " + director.camera());
    }

    /** Multiple cameras: one drop-down per shot (Wide = CAM 1, Lead Singer = CAM 2…). */
    private void rebuildShotCameras() {
        shotCameraRows.removeAll();
        String main = director.camera();
        String mainLabel = "Main (" + (main == null ? "none" : shortName(main)) + ")";
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 4, 6);
        for (VirtualShot s : director.shots()) {
            c.gridx = 0;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            shotCameraRows.add(Ui.label(s.name, Palette::text, "small"), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            JComboBox<String> box = new JComboBox<>();
            box.setFocusable(false);
            box.addItem(mainLabel);
            for (String cam : cameras.cameras()) {
                if (!cam.equals(main)) {
                    box.addItem(cam);
                }
            }
            if (s.camera != null && !s.camera.equals(main)) {
                if (((javax.swing.DefaultComboBoxModel<String>) box.getModel()).getIndexOf(s.camera) < 0) {
                    box.addItem(s.camera); // unplugged now: still its camera
                }
                box.setSelectedItem(s.camera);
            }
            box.addActionListener(e -> {
                Object sel = box.getSelectedItem();
                director.setShotCamera(s, mainLabel.equals(sel) ? null : (String) sel);
                flash(s.name + " → " + sel);
                for (ShotCard card : cards) {
                    card.repaint();
                }
            });
            shotCameraRows.add(box, c);
            c.gridy++;
        }
        shotCameraRows.revalidate();
        shotCameraRows.repaint();
    }

    private void fillProfiles() {
        updating = true;
        try {
            profileCombo.removeAllItems();
            for (String p : director.settings().profiles()) {
                profileCombo.addItem(p);
            }
            profileCombo.setSelectedItem(director.settings().profile());
        } finally {
            updating = false;
        }
    }

    private CropRect currentAdjusted() {
        AutoDirector.Status st = director.status();
        return director.adjustProgram() ? st.programCrop() : st.previewCrop();
    }

    private void refresh() {
        AutoDirector.Status st = director.status();
        updating = true;
        try {
            onSwitch.setSelected(st.enabled());
            manualBtn.setSelected(st.mode() == AutoDirector.Mode.MANUAL);
            assistedBtn.setSelected(st.mode() == AutoDirector.Mode.ASSISTED);
            autoBtn.setSelected(st.mode() == AutoDirector.Mode.AUTO);
            manualRadio.setSelected(st.mode() == AutoDirector.Mode.MANUAL);
            assistedRadio.setSelected(st.mode() == AutoDirector.Mode.ASSISTED);
            autoRadio.setSelected(st.mode() == AutoDirector.Mode.AUTO);
            boolean multi = director.multiCamera();
            oneCamera.setSelected(!multi);
            multiCamera.setSelected(multi);
            shotCameraBox.setVisible(multi);
            if (viewBar != null) {
                viewBar.setVisible(multi);
            }
            boolean assisted = st.mode() == AutoDirector.Mode.ASSISTED;
            reject.setEnabled(assisted && st.previewByAi() && st.previewPending());
            next.setEnabled(assisted);
            take.setText("TAKE (" + host.takeKeyName() + ")");
            take.setEnabled(st.enabled() && st.previewPending());
            cancel.setEnabled(st.previewPending());
            tracking.setSelected(adjustedShot(st) != null && adjustedShot(st).tracking
                    && !(director.adjustProgram() ? st.programManual() : st.previewManual()));
            adjustLive.setSelected(director.adjustProgram());
            double max = director.settings().maxZoom();
            zoom.setMaximum((int) Math.round(max * 100));
            CropRect adj = director.adjustProgram() ? st.programCrop() : st.previewCrop();
            if (!zoom.getValueIsAdjusting()) {
                zoom.setValue((int) Math.round(adj.zoom() * 100));
            }
            for (Component comp : shotPills.getComponents()) {
                if (comp instanceof JToggleButton b) {
                    b.setSelected(picked != null && b.getClientProperty("shot") instanceof VirtualShot ps && ps.id.equals(picked.id));
                }
            }
        } finally {
            updating = false;
        }
        selectedChip.setText(picked == null ? "–" : picked.name);
        sendToPreview.setEnabled(st.enabled() && picked != null);
        pickedName.setText(picked == null ? "Click a shot below" : picked.name);
        refreshStatus(st);
        refreshPerformers();
        for (ShotCard c : cards) {
            c.repaint();
        }
        source.repaint();
        host.shotStateChanged();
    }

    private VirtualShot adjustedShot(AutoDirector.Status st) {
        return director.adjustProgram() ? st.programShot() : st.previewShot();
    }

    private void refreshStatus(AutoDirector.Status st) {
        refreshShotRecording();
        String cam = st.camera();
        CameraStatus cs = cam == null ? null : cameras.status(cam);
        setChip(cameraChip, cam == null ? "NO CAMERA" : shortName(cam) + " · " + (cs == null ? "STARTING" : cs.label()),
                cs == CameraStatus.READY ? new Color(0x14532D) : cs != null && cs.isOffline() ? new Color(0x7F1D1D) : Palette.secondaryBackground(),
                cs == CameraStatus.READY ? CameraStatus.READY_TEXT : Palette.text());
        String aiText = !st.enabled() ? "AI OFF" : st.aiState() + (st.aiReady() && st.analysisMs() > 0
                ? String.format(Locale.ROOT, " · %.0f ms", st.analysisMs()) : "");
        setChip(aiChip, aiText, st.aiState().startsWith("AI error") ? new Color(0x7F1D1D) : Palette.secondaryBackground(), Palette.text());
        List<PersonTracker.Person> people = director.people(viewed());
        PersonTracker.Person followed = st.programPerson();
        String track = !st.enabled() ? "TRACKING –" : people.isEmpty() ? "NO PEOPLE DETECTED"
                : people.size() + (people.size() == 1 ? " PERSON" : " PEOPLE")
                + (followed != null ? String.format(Locale.ROOT, " · LIVE %s %.0f%%", followed.label(), followed.score() * 100) : "")
                + (st.programLost() ? " · TRACKING LOST (holding)" : "");
        setChip(trackingChip, track, st.programLost() ? new Color(0x78350F) : Palette.secondaryBackground(), Palette.text());
        String owner = st.mode() == AutoDirector.Mode.MANUAL ? "CONTROL: OPERATOR"
                : st.paused() ? "CONTROL: OPERATOR (AI PAUSED)"
                : st.mode() == AutoDirector.Mode.AUTO ? "CONTROL: AI (FULL AUTO)" : "CONTROL: OPERATOR · AI SUGGESTS";
        setChip(ownerChip, owner, st.mode() == AutoDirector.Mode.AUTO && !st.paused() ? new Color(0x1E3A8A) : Palette.secondaryBackground(), Palette.text());
        boolean paused = st.paused();
        pauseResume.setVisible(st.mode() != AutoDirector.Mode.MANUAL);
        pauseResume.setText(paused ? "RESUME AI" : "PAUSE AI");
        pauseResume.putClientProperty(FlatClientProperties.STYLE, paused
                ? "background: #16A34A; foreground: #FFFFFF; font: bold +1" : "font: bold");
        banner.setVisible(paused);
        banner.setBackground(PAUSED);
        banner.setText(paused ? "⏸  " + (st.pauseReason() == null ? "AI Paused — Manual Control" : st.pauseReason())
                + "   ·   The AI will not change anything until you press RESUME AI (Ctrl+Shift+R)." : " ");

        VirtualShot pg = st.programShot();
        programInfo.setText(pg == null ? "–" : pg.name);
        programDetail.setText(detail(pg, st.programCrop(), st.programPerson(), st.programManual(),
                st.programOwner() == AutoDirector.Owner.AI ? "by AI" : "by operator"));
        VirtualShot pv = st.previewShot();
        previewInfo.setText(pv == null ? "–" : pv.name + (st.previewPending() ? "" : "  (look-ahead)"));
        String by = st.previewByAi() ? (st.mode() == AutoDirector.Mode.AUTO ? "AI: next shot" : "AI recommends"
                + (st.recommendationWhy() == null ? "" : " · " + st.recommendationWhy())) : "chosen by operator";
        previewDetail.setText(detail(pv, st.previewCrop(), st.previewPerson(), st.previewManual(), by));
        CropRect adj = director.adjustProgram() ? st.programCrop() : st.previewCrop();
        zoomLabel.setText(String.format(Locale.ROOT, "%.1f×", adj.zoom()));
        boolean soft = adj.zoom() > CropRect.QUALITY_ZOOM + 1e-6;
        qualityWarning.setVisible(soft);
        qualityWarning.setText(soft ? "Above 2×: visibly softer (no detail can be added back)" : " ");
        boolean seen = host.programShowsCamera(cam);
        noCameraWarning.setText(st.enabled() && !seen ? "<html>Program's scene has no camera source for " + cam
                + ": choose a scene with this camera to see the shots live.</html>" : " ");
    }

    private static String detail(VirtualShot s, CropRect c, PersonTracker.Person p, boolean manual, String by) {
        if (s == null) {
            return " ";
        }
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%.1f× · ", c.zoom()));
        if (manual) {
            b.append("manual framing · ");
        } else if (s.tracking) {
            b.append(p == null ? "zone framing · " : String.format(Locale.ROOT, "%s %.0f%% · ", p.label(), p.score() * 100));
        } else {
            b.append("fixed · ");
        }
        return "<html>" + b.append(by) + "</html>";
    }

    private static void setChip(JLabel chip, String text, Color bg, Color fg) {
        chip.setText(text);
        chip.setBackground(bg);
        chip.setForeground(fg);
    }

    private static String shortName(String cam) {
        return cam.length() > 22 ? cam.substring(0, 21) + "…" : cam;
    }

    private void refreshPerformers() {
        List<PersonTracker.Person> people = director.people(viewed());
        List<Integer> ids = people.stream().map(PersonTracker.Person::id).toList();
        if (ids.equals(performerIds)) {
            // labels only
            for (Component comp : performerRows.getComponents()) {
                if (comp instanceof JLabel l && l.getClientProperty("person") instanceof Integer id) {
                    people.stream().filter(p -> p.id() == id).findFirst().ifPresent(p -> l.setText(personText(p)));
                }
            }
            return;
        }
        performerIds = ids;
        performerRows.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 4, 6);
        if (people.isEmpty()) {
            performerRows.add(Ui.label(director.enabled() ? "Nobody detected yet." : "Turn the Auto Director on.", Palette::mutedText, "small"), c);
        }
        for (PersonTracker.Person p : people) {
            c.gridx = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            JLabel l = Ui.label(personText(p), Palette::text, "small");
            l.putClientProperty("person", p.id());
            performerRows.add(l, c);
            c.gridx = 1;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            JButton edit = Ui.toolButton("edit", "Name / role of this person");
            edit.addActionListener(e -> namePerson(p.id()));
            performerRows.add(edit, c);
            c.gridx = 2;
            JButton show = Ui.toolButton("monitor", "A shot of this person in Preview");
            show.addActionListener(e -> director.selectPerson(viewed(), p.id()));
            performerRows.add(show, c);
            c.gridy++;
        }
        performerRows.revalidate();
        performerRows.repaint();
    }

    private static String personText(PersonTracker.Person p) {
        return String.format(Locale.ROOT, "%s · %.0f%%%s", p.label(), p.score() * 100, p.visible() ? "" : " · lost");
    }

    private void namePerson(int id) {
        PersonTracker.Assignment as = director.tracker(viewed()).assignment(id);
        JTextField name = new JTextField(as == null || as.name() == null ? "" : as.name(), 16);
        JComboBox<Object> role = new JComboBox<>();
        role.addItem("Follow the stage zone");
        for (Role r : Role.zoneRoles()) {
            role.addItem(r);
        }
        role.setSelectedItem(as == null || as.role() == null ? role.getItemAt(0) : as.role());
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
        p.add(new JLabel("Name (e.g. Pastor Ana):"));
        p.add(name);
        p.add(new JLabel("Role:"));
        p.add(role);
        if (JOptionPane.showConfirmDialog(this, p, "Performer #" + id, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            director.tracker(viewed()).assign(id, name.getText().trim(), role.getSelectedItem() instanceof Role r ? r : null);
            performerIds = List.of();
            refresh();
        }
    }

    // ------------------------------------------------------------------ the whole camera picture

    private final class SourceView extends JComponent {
        private Point2 dragStart;
        private CropRect dragCrop;
        private boolean dragProgram;
        private Rectangle zoneDraw;
        private int hover = -1;

        record Point2(double x, double y) {
        }

        SourceView() {
            setPreferredSize(new Dimension(533, MAX_PICTURE_HEIGHT));
            setMinimumSize(new Dimension(320, 180));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    Point2 p = toNorm(e.getX(), e.getY());
                    if (p == null || !director.enabled() || showingShot()) {
                        return; // the clicked shot is shown: nothing to drag here (FULL CAMERA for that)
                    }
                    if (editZones.isSelected()) {
                        zoneDraw = new Rectangle(e.getX(), e.getY(), 0, 0);
                        dragStart = p;
                        return;
                    }
                    AutoDirector.Status st = director.status();
                    boolean onProgram = director.adjustProgram();
                    CropRect c = onProgram ? st.programCrop() : st.previewCrop();
                    boolean here = viewed() != null && viewed().equals(onProgram ? st.programCamera() : st.previewCamera());
                    if (here && c.box().contains(p.x, p.y) && personAt(p) == null) {
                        dragStart = p;
                        dragCrop = c;
                        dragProgram = onProgram;
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    Point2 p = toNorm(e.getX(), e.getY());
                    if (p == null || dragStart == null) {
                        return;
                    }
                    if (zoneDraw != null) {
                        zoneDraw.setSize(e.getX() - zoneDraw.x, e.getY() - zoneDraw.y);
                        repaint();
                        return;
                    }
                    director.setCrop(dragCrop.panBy(p.x - dragStart.x, p.y - dragStart.y), dragProgram);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (zoneDraw != null && dragStart != null) {
                        Point2 end = toNorm(e.getX(), e.getY());
                        if (end != null) {
                            Box b = Box.ofCorners(dragStart.x, dragStart.y, end.x, end.y).clamp();
                            if (b.w() > 0.02 && b.h() > 0.02) {
                                Role r = (Role) zoneRole.getSelectedItem();
                                List<StageZone> zones = new ArrayList<>(director.zones(viewed()));
                                zones.removeIf(z -> z.role() == r);
                                zones.add(new StageZone(r, b));
                                director.setZones(viewed(), zones);
                                flash("Zone saved: " + r.label());
                            }
                        }
                    }
                    zoneDraw = null;
                    dragStart = null;
                    dragCrop = null;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (showingShot()) {
                        if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                            sendPickedToPreview(); // the shot seen here goes to Preview
                        }
                        return;
                    }
                    if (editZones.isSelected() || !director.enabled() || e.getClickCount() != 1) {
                        return;
                    }
                    Point2 p = toNorm(e.getX(), e.getY());
                    PersonTracker.Person person = p == null ? null : personAt(p);
                    if (person != null) {
                        pick(director.personShot(viewed(), person.id()));
                    }
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    if (showingShot()) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        return;
                    }
                    Point2 p = toNorm(e.getX(), e.getY());
                    PersonTracker.Person person = p == null ? null : personAt(p);
                    int h = person == null ? -1 : person.id();
                    if (h != hover) {
                        hover = h;
                        setCursor(Cursor.getPredefinedCursor(h >= 0 ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR));
                        repaint();
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    if (!e.isControlDown()) {
                        // the plain wheel scrolls the page; Ctrl + wheel zooms the Preview shot
                        java.awt.Container up = SourceView.this.getParent();
                        if (up != null) {
                            up.dispatchEvent(SwingUtilities.convertMouseEvent(SourceView.this, e, up));
                        }
                        return;
                    }
                    if (director.enabled()) {
                        director.zoomBy(e.getWheelRotation() < 0 ? 1.08 : 1 / 1.08);
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        private PersonTracker.Person personAt(Point2 p) {
            PersonTracker.Person best = null;
            for (PersonTracker.Person x : director.people(viewed())) {
                if (x.box().contains(p.x, p.y) && (best == null || x.box().area() < best.box().area())) {
                    best = x;
                }
            }
            return best;
        }

        /** Where the camera picture is drawn (16:9, or the frame's own shape). */
        private Rectangle picture() {
            double aspect = lastFrame != null ? lastFrame.getWidth() / (double) lastFrame.getHeight() : 16 / 9.0;
            int w = getWidth();
            int h = Math.min(getHeight(), MAX_PICTURE_HEIGHT); // small: more room for the controls
            int pw = w;
            int ph = (int) Math.round(w / aspect);
            if (ph > h) {
                ph = h;
                pw = (int) Math.round(h * aspect);
            }
            return new Rectangle((w - pw) / 2, 0, pw, ph); // at the top, centered
        }

        private Point2 toNorm(int x, int y) {
            Rectangle r = picture();
            if (r.width <= 0 || r.height <= 0) {
                return null;
            }
            return new Point2(Math.clamp((x - r.x) / (double) r.width, 0, 1), Math.clamp((y - r.y) / (double) r.height, 0, 1));
        }

        private Rectangle toScreen(Rectangle pic, Box b) {
            return new Rectangle(pic.x + (int) Math.round(b.x() * pic.width), pic.y + (int) Math.round(b.y() * pic.height),
                    (int) Math.round(b.w() * pic.width), (int) Math.round(b.h() * pic.height));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle pic = picture();
            g.setColor(Palette.monitorBackground());
            g.fillRect(pic.x, pic.y, pic.width, pic.height);
            AutoDirector.Status st = director.status();
            if (!st.enabled()) {
                centered(g, pic, "Auto Director is off", "Turn it on (switch above) to make virtual shots from " + (st.camera() == null ? "the camera" : st.camera()));
                g.dispose();
                return;
            }
            if (showingShot()) {
                paintPickedShot(g, pic);
                g.dispose();
                return;
            }
            if (lastFrame != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(lastFrame, pic.x, pic.y, pic.width, pic.height, null);
            } else {
                CameraStatus cs = st.camera() == null ? null : cameras.status(st.camera());
                centered(g, pic, st.camera() == null ? "No camera found" : st.camera(), cs == null ? "STARTING" : cs.label());
            }
            Font small = getFont().deriveFont(Font.BOLD, 11f);
            g.setFont(small);
            // stage zones
            if (editZones.isSelected()) {
                for (StageZone z : director.zones(viewed())) {
                    Rectangle r = toScreen(pic, z.box());
                    g.setColor(new Color(255, 255, 255, 28));
                    g.fillRect(r.x, r.y, r.width, r.height);
                    g.setColor(new Color(255, 255, 255, 140));
                    g.setStroke(dashed(1.5f));
                    g.drawRect(r.x, r.y, r.width, r.height);
                    label(g, z.role().label(), r.x + 4, r.y + 4, new Color(0, 0, 0, 170), Color.WHITE);
                }
                if (zoneDraw != null) {
                    g.setColor(PREVIEW);
                    g.setStroke(new BasicStroke(2f));
                    Rectangle z = new Rectangle(zoneDraw);
                    if (z.width < 0) {
                        z.x += z.width;
                        z.width = -z.width;
                    }
                    if (z.height < 0) {
                        z.y += z.height;
                        z.height = -z.height;
                    }
                    g.drawRect(z.x, z.y, z.width, z.height);
                }
            }
            // people
            for (PersonTracker.Person p : director.people(viewed())) {
                Rectangle r = toScreen(pic, p.box());
                boolean h = p.id() == hover;
                g.setStroke(p.visible() ? new BasicStroke(h ? 2.5f : 1.5f) : dashed(1.5f));
                g.setColor(h ? Color.WHITE : new Color(255, 255, 255, p.visible() ? 170 : 90));
                g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
                label(g, String.format(Locale.ROOT, "%s %.0f%%", p.label(), p.score() * 100), r.x, r.y - 16,
                        new Color(0, 0, 0, 170), Color.WHITE);
            }
            // the two cut-outs, when they are of the camera shown here
            String cam = viewed();
            boolean pvHere = cam != null && cam.equals(st.previewCamera());
            boolean pgHere = cam != null && cam.equals(st.programCamera());
            boolean same = pvHere && pgHere && st.previewCrop().near(st.programCrop(), 0.002);
            if (pvHere && !same) {
                crop(g, pic, st.previewCrop(), PREVIEW, "PREVIEW · " + name(st.previewShot()), false);
            }
            if (pgHere) {
                crop(g, pic, st.programCrop(), LIVE, "LIVE · " + name(st.programShot()) + (same ? " (also in Preview)" : ""), true);
            }
            // shots on another camera: say where they are
            int y = pic.y + 6;
            if (!pgHere && st.programCamera() != null) {
                label(g, "LIVE is on " + st.programCamera() + " · " + name(st.programShot()), pic.x + pic.width - 6
                        - g.getFontMetrics().stringWidth("LIVE is on " + st.programCamera() + " · " + name(st.programShot())) - 10, y, LIVE, Color.WHITE);
                y += 22;
            }
            if (!pvHere && st.previewCamera() != null) {
                String t = "PREVIEW is on " + st.previewCamera() + " · " + name(st.previewShot());
                label(g, t, pic.x + pic.width - 6 - g.getFontMetrics().stringWidth(t) - 10, y, PREVIEW, Color.WHITE);
            }
            // the shot clicked below (white): where it is in the whole picture
            VirtualShot s = picked;
            if (s != null && cam != null && cam.equals(director.cameraOf(s))) {
                Rectangle r = toScreen(pic, director.cropFor(s).box());
                g.setStroke(new BasicStroke(2f));
                g.setColor(Color.WHITE);
                g.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
                label(g, "SHOT PREVIEW · " + s.name, r.x + r.width - 6 - g.getFontMetrics().stringWidth("SHOT PREVIEW · " + s.name) - 10,
                        r.y + 4, new Color(255, 255, 255, 230), Color.BLACK);
            }
            g.dispose();
        }

        /** The big picture shows the clicked shot itself (white), unless FULL CAMERA is on or zones are being edited. */
        boolean showingShot() {
            return picked != null && !fullCamera.isSelected() && !editZones.isSelected() && director.enabled();
        }

        /** The clicked shot, large: its own camera, cut out and following its performer, with a white frame. */
        private void paintPickedShot(Graphics2D g, Rectangle pic) {
            VirtualShot s = picked;
            String cam = director.cameraOf(s);
            ensureView();
            BufferedImage frame = viewFrame;
            if (frame != null) {
                Box b = viewCrop.box(); // the same framing as the small SHOT PREVIEW
                int fw = frame.getWidth();
                int fh = frame.getHeight();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(frame, pic.x, pic.y, pic.x + pic.width, pic.y + pic.height,
                        (int) Math.round(b.x() * fw), (int) Math.round(b.y() * fh),
                        (int) Math.round(b.right() * fw), (int) Math.round(b.bottom() * fh), null);
            } else {
                CameraStatus cs = cam == null ? null : cameras.status(cam);
                centered(g, pic, cam == null ? "No camera" : cam, cs == null ? "STARTING" : cs.label());
            }
            g.setFont(getFont().deriveFont(Font.BOLD, 11f));
            g.setStroke(new BasicStroke(3f));
            g.setColor(Color.WHITE);
            g.drawRect(pic.x + 1, pic.y + 1, pic.width - 3, pic.height - 3);
            label(g, "SHOT PREVIEW · " + s.name + (cam == null || cam.equals(director.camera()) ? "" : " · " + cam),
                    pic.x + 6, pic.y + 6, new Color(255, 255, 255, 230), Color.BLACK);
            label(g, "Double-click: send to Preview   ·   FULL CAMERA: the whole picture", pic.x + 6,
                    pic.y + pic.height - 24, new Color(0, 0, 0, 170), Color.WHITE);
        }

        private static String name(VirtualShot s) {
            return s == null ? "–" : s.name;
        }

        private void crop(Graphics2D g, Rectangle pic, CropRect c, Color color, String text, boolean live) {
            Rectangle r = toScreen(pic, c.box());
            // dim what is outside the live cut-out a little
            if (live && !c.isWide()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
                g2.setColor(Color.BLACK);
                java.awt.geom.Area a = new java.awt.geom.Area(pic);
                a.subtract(new java.awt.geom.Area(r));
                g2.fill(a);
                g2.dispose();
            }
            g.setStroke(new BasicStroke(live ? 3f : 2f));
            g.setColor(color);
            g.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
            label(g, text, r.x + 4, live ? r.y + r.height - 22 : r.y + 4, color, Color.WHITE);
        }

        private static Stroke dashed(float w) {
            return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f);
        }

        private static void label(Graphics2D g, String text, int x, int y, Color bg, Color fg) {
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text) + 10;
            int h = fm.getHeight() + 2;
            g.setColor(bg);
            g.fillRoundRect(x, y, w, h, 6, 6);
            g.setColor(fg);
            g.drawString(text, x + 5, y + fm.getAscent() + 1);
        }

        private void centered(Graphics2D g, Rectangle r, String title, String detail) {
            g.setFont(getFont().deriveFont(Font.BOLD, 16f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(new Color(0xCBD5E1));
            g.drawString(title, r.x + (r.width - fm.stringWidth(title)) / 2, r.y + r.height / 2 - 4);
            g.setFont(getFont().deriveFont(12f));
            fm = g.getFontMetrics();
            g.setColor(new Color(0x94A3B8));
            g.drawString(detail, r.x + (r.width - fm.stringWidth(detail)) / 2, r.y + r.height / 2 + 18);
        }
    }

    // ------------------------------------------------------------------ shot cards

    /**
     * SHOT PREVIEW: the shot clicked on this page, live from its own camera, framed as it would be (following its
     * performer). Only this page sees it: Preview and Program are not touched until SEND TO PREVIEW.
     */
    private final class ShotMonitor extends JComponent {
        ShotMonitor() {
            Dimension card = new Dimension(ShotCard.W, (ShotCard.W - 8) * 9 / 16 + 8); // a shot card's picture
            setPreferredSize(card);
            setMinimumSize(card);
            setMaximumSize(card);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Shot Preview · double-click: send to Preview");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() >= 2 && SwingUtilities.isLeftMouseButton(e)) {
                        sendPickedToPreview();
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // exactly like a shot card's picture: 4 px in, 16:9, rounded 6
            int w = ShotCard.W - 8;
            int h = w * 9 / 16;
            Rectangle r = new Rectangle(4, 4, w, h);
            g.setColor(Palette.monitorBackground());
            g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
            // a small copy of the big picture: the clicked shot, or the whole camera when that is what it shows
            VirtualShot s = picked;
            boolean shot = source.showingShot();
            ensureView();
            BufferedImage frame = director.enabled() ? viewFrame : null;
            if (frame != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.clip(new java.awt.geom.RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 6, 6));
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                Box b = viewCrop.box();
                int fw = frame.getWidth();
                int fh = frame.getHeight();
                g2.drawImage(frame, r.x, r.y, r.x + r.width, r.y + r.height,
                        (int) Math.round(b.x() * fw), (int) Math.round(b.y() * fh),
                        (int) Math.round(b.right() * fw), (int) Math.round(b.bottom() * fh), null);
                g2.dispose();
            } else {
                g.setColor(Palette.secondaryText());
                g.setFont(getFont().deriveFont(10f));
                String t = !director.enabled() ? "Auto Director is off" : s == null ? "Click a shot" : "No picture";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(t, r.x + (w - fm.stringWidth(t)) / 2, r.y + h / 2 + fm.getAscent() / 2);
            }
            g.setStroke(new BasicStroke(shot ? 3f : 1f)); // white ring while it shows the chosen shot, like the card
            g.setColor(shot ? Color.WHITE : Palette.border());
            g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
            g.dispose();
        }
    }

    private final class ShotCard extends JComponent {
        static final int W = 168;
        static final int H = 132;
        private final VirtualShot shot;
        private final int index;

        ShotCard(VirtualShot shot, int index) {
            this.shot = shot;
            this.index = index;
            setPreferredSize(new Dimension(W, H));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("<html><b>" + shot.name + "</b><br>" + shot.role.label() + " · " + String.format(Locale.ROOT, "%.1f×", shot.zoom)
                    + (shot.tracking ? " · tracking" : " · fixed") + "<br>Click: Shot Preview · Double-click: send to Preview · Right-click: edit</html>");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) {
                        shotMenu(shot, ShotCard.this, e.getX(), e.getY());
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    if (!director.enabled()) {
                        director.setEnabled(true);
                    }
                    // a click only looks at the shot in this page's SHOT PREVIEW; double-click sends it to Preview
                    pick(shot);
                    if (e.getClickCount() >= 2) {
                        sendPickedToPreview();
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            AutoDirector.Status st = director.status();
            boolean live = st.programShot() != null && st.programShot().id.equals(shot.id);
            boolean pv = st.previewShot() != null && st.previewShot().id.equals(shot.id);
            int tw = W - 8;
            int th = tw * 9 / 16;
            Rectangle t = new Rectangle(4, 4, tw, th);
            g.setColor(Palette.monitorBackground());
            g.fillRoundRect(t.x, t.y, t.width, t.height, 6, 6);
            BufferedImage frame = cardFrames.get(director.cameraOf(shot));
            if (frame != null && st.enabled()) {
                CropRect c = live ? st.programCrop() : pv ? st.previewCrop() : shotCrop(shot);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.clip(new java.awt.geom.RoundRectangle2D.Double(t.x, t.y, t.width, t.height, 6, 6));
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int fw = frame.getWidth();
                int fh = frame.getHeight();
                Box b = c.box();
                g2.drawImage(frame, t.x, t.y, t.x + t.width, t.y + t.height,
                        (int) Math.round(b.x() * fw), (int) Math.round(b.y() * fh),
                        (int) Math.round(b.right() * fw), (int) Math.round(b.bottom() * fh), null);
                g2.dispose();
            }
            boolean chosen = picked != null && picked.id.equals(shot.id);
            Color ring = live ? LIVE : pv ? PREVIEW : chosen ? Color.WHITE : Palette.border();
            g.setColor(ring);
            g.setStroke(new BasicStroke(live || pv || chosen ? 3f : 1f));
            g.drawRoundRect(t.x, t.y, t.width, t.height, 6, 6);
            Font f = getFont().deriveFont(Font.BOLD, 11f);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            if (index < 5) {
                String key = String.valueOf(index + 1);
                g.setColor(new Color(0, 0, 0, 180));
                g.fillRoundRect(t.x + 4, t.y + 4, 18, 18, 6, 6);
                g.setColor(Color.WHITE);
                g.drawString(key, t.x + 4 + (18 - fm.stringWidth(key)) / 2, t.y + 4 + 13);
            }
            if (live || pv) {
                String badge = live ? "LIVE" : "PREVIEW";
                int bw = fm.stringWidth(badge) + 10;
                g.setColor(ring);
                g.fillRoundRect(t.x + t.width - bw - 4, t.y + 4, bw, 16, 6, 6);
                g.setColor(Color.WHITE);
                g.drawString(badge, t.x + t.width - bw + 1, t.y + 16);
            }
            g.setColor(Palette.text());
            String n = shot.name;
            while (fm.stringWidth(n) > W - 8 && n.length() > 3) {
                n = n.substring(0, n.length() - 2);
            }
            g.drawString(n, 4, th + 4 + fm.getAscent() + 4);
            g.setFont(getFont().deriveFont(10f));
            g.setColor(Palette.secondaryText());
            g.drawString(String.format(Locale.ROOT, "%s%.1f× · %s%s", shot.camera == null || shot.camera.equals(director.camera())
                    ? "" : shortName(shot.camera) + " · ", shot.zoom, shot.tracking ? "tracking" : "fixed",
                    shot.priority == 0 ? " · not in auto" : ""), 4, th + 4 + fm.getHeight() + 14);
            g.dispose();
        }

        /** A shot's own framing for its thumbnail when it is on neither surface. */
        private CropRect shotCrop(VirtualShot s) {
            if (!s.tracking) {
                return s.fixedCrop();
            }
            String cam = director.cameraOf(s);
            for (PersonTracker.Person p : director.people(cam)) {
                if (s.trackId != null ? p.id() == s.trackId : p.role() == s.role) {
                    return CropRect.frame(p.box(), s.margin, Math.min(s.zoom, director.settings().maxZoom()));
                }
            }
            for (StageZone z : director.zones(cam)) {
                if (z.role() == s.role) {
                    return CropRect.frame(z.box(), 0.05, Math.min(s.zoom, director.settings().maxZoom()));
                }
            }
            return s.fixedCrop();
        }
    }
}
