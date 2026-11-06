package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import com.jamc.worship.output.DisplayManager;
import com.jamc.worship.output.OutputCanvas;
import com.jamc.worship.output.OutputService;
import com.jamc.worship.output.ProgramOutput;
import com.jamc.worship.output.Transition;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * Bottom-right production area: Preview monitor, TAKE + transition, Program monitor, live
 * switches (Black / Blank / Lyrics / Background / Auto-take / Projector). Scenes stay in the engine
 * (Program is composed through the live scene) but are not shown in the normal worship UI.
 */
public class LiveDock extends JPanel {

    /** Operator actions the dock forwards to the main window. */
    public interface Actions {
        void take();

        void toggleOutput();

        void transitionChanged(Transition transition, int durationMs);

        void autoTakeChanged(boolean autoTake);

        /** Separate Preview layout mode: Program switches to the scene shown in Preview (content stays). */
        default void takeScene() {
        }
    }

    private final TransportControls programTransport = new TransportControls(true);
    private final JPanel programMediaRow = Ui.transparent(new BorderLayout(6, 0));
    private final JLabel programMediaLabel = Ui.label("PROGRAM MEDIA", Palette::secondaryText, "small");
    /** Preview audio: off by default so a video in Preview is never heard in the church; on = the monitor device. */
    private final JToggleButton previewMonitorButton = new JToggleButton("PREVIEW AUDIO: OFF",
            com.jamc.worship.ui.theme.Icons.get("audio", com.jamc.worship.ui.theme.Icons.SMALL));
    private java.util.function.Consumer<Boolean> monitorListener = on -> { };
    private final JButton hideLowerThird = new JButton("Hide Lower Third");

    private final ProgramOutput program;
    private final Actions actions;
    private final PreviewMonitor preview;
    private final ProgramMonitor programMonitor;
    private final JButton takeButton = new JButton("TAKE →");
    private final JButton takeSceneButton = new JButton("TAKE SCENE →");
    private String previewNote;
    private boolean previewNoteScene;
    private final JComboBox<Transition> transitionCombo = new JComboBox<>(Transition.values());
    private final JComboBox<String> durationCombo = new JComboBox<>(new String[]{"0.3 s", "0.5 s", "1 s", "2 s"});
    private final JToggleButton blackButton = liveToggle("Black", "Black screen: the projector shows only black (B)");
    private final JToggleButton blankButton = liveToggle("Blank",
            "Blank: hide the text but keep the background. Clears on the next TAKE (W)");
    private final JToggleButton lyricsButton = liveToggle("Clear",
            "Clear: hide the lyrics on every slide until pressed again (L)");
    private final JToggleButton backgroundButton = liveToggle("Background",
            "Show or hide backgrounds on every slide until switched back");
    private final JToggleButton autoTakeButton = new JToggleButton("Auto-take");
    private final JButton projectorButton = new JButton("Projector: Off", Icons.get("monitor", Icons.SMALL));
    private boolean syncing;
    private OutputService router;
    private DisplayManager displays;
    private java.util.function.Supplier<Component> dialogParent = () -> this;
    private final JLabel previewRoutes = Ui.small("");
    private final JLabel programRoutes = Ui.small("");
    private final JToggleButton projectorPreview = new JToggleButton("PREVIEW");
    private final JToggleButton projectorProgram = new JToggleButton("PROGRAM");
    private boolean syncingRoute;

    /**
     * @param previewState what TAKE will put on Program: content and scene, composed exactly like Program
     */
    public LiveDock(ProgramOutput program, ProgramOutput previewState, Actions actions) {
        super(new GridBagLayout());
        this.program = program;
        this.preview = new PreviewMonitor(previewState);
        this.actions = actions;
        this.programMonitor = new ProgramMonitor(program);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 10));


        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 6, 8);

        c.gridx = 0;
        c.weightx = 1;
        add(withDisplayRow(monitor(preview), OutputService.Source.PREVIEW), c);

        c.gridx = 1;
        c.weightx = 0;
        add(buildTakeColumn(), c);

        c.gridx = 2;
        c.weightx = 1;
        add(withDisplayRow(monitor(programMonitor), OutputService.Source.PROGRAM), c);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 3;
        c.gridheight = 1;
        c.weighty = 0;
        c.insets = new Insets(4, 0, 0, 8);
        programMediaRow.add(programMediaLabel, BorderLayout.WEST);
        programMediaLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (mediaTitle != null && !firstWords(mediaTitle, TITLE_WORDS).equals(mediaTitle.strip())) {
                    mediaTitleExpanded = !mediaTitleExpanded;
                    showProgramMediaLabel();
                }
            }
        });
        programMediaRow.add(programTransport, BorderLayout.CENTER);
        programMediaRow.setVisible(false);
        add(programMediaRow, c);

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 3;
        c.gridheight = 1;
        c.weighty = 0;
        c.insets = new Insets(0, 0, 0, 8);
        add(buildControlBar(), c);
        c.gridheight = 1;

        program.addListener(this::syncFromProgram);
        syncFromProgram();
    }

    /** Shows TAKE SCENE (separate Preview layout mode only). */
    public void setTakeSceneVisible(boolean visible) {
        takeSceneButton.setVisible(visible);
        revalidate();
    }

    /**
     * What TAKE will do, shown on the Preview monitor ("NEXT: Camera + Lyrics · Scene change" or
     * "NEXT: Amazing Grace – Verse 2 · Content change"); null hides it.
     */
    public void setPreviewNote(String note, boolean sceneChange) {
        previewNote = note;
        previewNoteScene = sceneChange;
        preview.repaint();
    }

    /** Enables TAKE when Preview holds something that is not on Program yet. */
    public void setPreviewReady(boolean ready) {
        takeButton.setEnabled(ready);
        preview.repaint();
    }

    /** @param programOnDisplay false shows "NOT ON A DISPLAY" on the Program monitor */
    public void setOutputState(boolean open, String description, boolean programOnDisplay) {
        projectorButton.setText("Projector: " + description);
        projectorButton.putClientProperty(FlatClientProperties.STYLE, open
                ? "background: #16A34A; foreground: #FFFFFF; font: bold"
                : "font: bold");
        programMonitor.outputOpen = programOnDisplay;
        programMonitor.repaint();
    }

    /** Loads saved transition settings without notifying {@link Actions}. */
    public void setTransition(Transition transition, int durationMs) {
        syncing = true;
        try {
            transitionCombo.setSelectedItem(transition);
            int index = 1;
            for (int i = 0; i < ProgramOutput.DURATIONS_MS.length; i++) {
                if (ProgramOutput.DURATIONS_MS[i] == durationMs) {
                    index = i;
                }
            }
            durationCombo.setSelectedIndex(index);
            durationCombo.setEnabled(transition != Transition.CUT);
        } finally {
            syncing = false;
        }
    }

    public void setAutoTake(boolean on) {
        autoTakeButton.setSelected(on);
        updateTakeTooltip();
    }

    public boolean isAutoTake() {
        return autoTakeButton.isSelected();
    }

    // ------------------------------------------------------------------ layout

    private static JComponent monitor(JComponent canvas) {
        canvas.setPreferredSize(new Dimension(240, 135));
        canvas.setMinimumSize(new Dimension(128, 72));
        return canvas;
    }

    /**
     * Connects the output router: the DISPLAY buttons and right-click menus on Preview and Program, the
     * projector quick switch and the routing status.
     */
    public void setOutputRouter(OutputService router, DisplayManager displays, java.util.function.Supplier<Component> parent) {
        this.router = router;
        this.displays = displays;
        this.dialogParent = parent;
        router.addListener(this::syncRouting);
        syncRouting();
    }

    private JComponent withDisplayRow(JComponent monitor, OutputService.Source source) {
        JButton button = new JButton("DISPLAY ▾", Icons.get("monitor", Icons.SMALL));
        button.setToolTipText("Show " + source + " on a projector, TV or window (or right-click the monitor)");
        button.putClientProperty(FlatClientProperties.STYLE, "font: bold -1; margin: 2,8,2,8");
        button.setFocusable(false);
        button.addActionListener(e -> showRouteMenu(source, button, 0, button.getHeight()));
        monitor.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    showRouteMenu(source, monitor, e.getX(), e.getY());
                }
            }
        });
        JPanel row = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(button);
        row.add(source == OutputService.Source.PREVIEW ? previewRoutes : programRoutes);
        if (source == OutputService.Source.PREVIEW) {
            previewMonitorButton.setFocusable(false);
            previewMonitorButton.putClientProperty(FlatClientProperties.STYLE, "font: bold -2; margin: 2,8,2,8");
            previewMonitorButton.setToolTipText("Listen to the Preview scene's videos on the monitor device (e.g. headphones). "
                    + "Off: Preview is silent, so the church only hears Program.");
            previewMonitorButton.addActionListener(e -> {
                syncMonitorButton();
                monitorListener.accept(previewMonitorButton.isSelected());
            });
            row.add(previewMonitorButton);
        }
        boolean program = source == OutputService.Source.PROGRAM;
        JPanel south = Ui.transparent(new BorderLayout(0, 4));
        south.add(imageNav(program), BorderLayout.NORTH);
        south.add(row, BorderLayout.SOUTH);
        JPanel p = Ui.transparent(new BorderLayout(0, 4));
        p.add(monitor, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    // ------------------------------------------------------------------ image slideshow under the monitors

    private final JPanel previewImageNav = Ui.transparent(new BorderLayout(6, 0));
    private final JPanel programImageNav = Ui.transparent(new BorderLayout(6, 0));
    private final JLabel previewImageLabel = Ui.label(" ", Palette::text, "semibold");
    private final JLabel programImageLabel = Ui.label(" ", Palette::text, "semibold");
    /** (on Program?, -1 previous / +1 next). */
    private java.util.function.BiConsumer<Boolean, Integer> imageStep = (program, delta) -> { };

    /** ◀ IMAGE 2 / 5 ▶ under a monitor; hidden until its scene has an image slideshow. */
    private JComponent imageNav(boolean program) {
        JPanel bar = program ? programImageNav : previewImageNav;
        JLabel label = program ? programImageLabel : previewImageLabel;
        String where = program ? "Program (live now)" : "Preview (TAKE puts it on Program)";
        JButton prev = new JButton("PREV", Icons.get("chevron-left", Icons.SMALL));
        prev.setToolTipText("Previous image on " + where);
        JButton next = new JButton("NEXT", Icons.get("chevron-right", Icons.SMALL));
        next.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);
        next.setToolTipText("Next image on " + where);
        for (JButton b : new JButton[]{prev, next}) {
            b.putClientProperty(FlatClientProperties.STYLE, "font: bold -1; margin: 3,10,3,10");
            b.setFocusable(false);
            b.getAccessibleContext().setAccessibleName(b.getToolTipText());
        }
        prev.addActionListener(e -> imageStep.accept(program, -1));
        next.addActionListener(e -> imageStep.accept(program, 1));
        label.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        bar.add(prev, BorderLayout.WEST);
        bar.add(label, BorderLayout.CENTER);
        bar.add(next, BorderLayout.EAST);
        bar.setVisible(false);
        return bar;
    }

    /** What PREV / NEXT under the Preview and Program monitors do. */
    public void setImageStep(java.util.function.BiConsumer<Boolean, Integer> step) {
        this.imageStep = step;
    }

    /** Shows "IMAGE 2 / 5" with PREV / NEXT under a monitor, or hides them ({@code text} null). */
    public void showImageNav(boolean program, String text) {
        JPanel bar = program ? programImageNav : previewImageNav;
        (program ? programImageLabel : previewImageLabel).setText(text == null ? " " : text);
        if (bar.isVisible() != (text != null)) {
            bar.setVisible(text != null);
            revalidate();
        }
    }

    /** "DISPLAY PREVIEW ON": every connected display, a window, or stop. */
    private void showRouteMenu(OutputService.Source source, Component anchor, int x, int y) {
        if (router == null) {
            return;
        }
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        JLabel title = Ui.caption("DISPLAY " + source.name() + " ON");
        title.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        menu.add(title);
        java.util.Map<String, OutputService.RouteInfo> byTarget = new java.util.HashMap<>();
        router.routes().forEach(r -> byTarget.put(r.target(), r));
        for (DisplayManager.Display d : displays.displays()) {
            OutputService.RouteInfo r = byTarget.get(d.id());
            String suffix = r != null && r.source() != source ? "   (showing " + r.source() + ")"
                    : d.primary() ? "   (main screen)" : "";
            javax.swing.JCheckBoxMenuItem item = new javax.swing.JCheckBoxMenuItem(d.title() + "  ·  "
                    + d.pixelWidth() + "×" + d.pixelHeight() + suffix, r != null && r.source() == source);
            item.addActionListener(e -> router.route(source, d.id(), dialogParent.get()));
            menu.add(item);
        }
        javax.swing.JCheckBoxMenuItem windowed = new javax.swing.JCheckBoxMenuItem("Windowed Output",
                byTarget.containsKey("window:" + source.name()));
        windowed.addActionListener(e -> router.route(source, null, dialogParent.get()));
        menu.add(windowed);
        menu.addSeparator();
        javax.swing.JMenuItem stop = new javax.swing.JMenuItem("Stop Display Output");
        stop.setEnabled(router.routes().stream().anyMatch(r -> r.source() == source));
        stop.addActionListener(e -> router.stop(source));
        menu.add(stop);
        menu.show(anchor, x, y);
    }

    private void switchProjector(OutputService.Source source) {
        if (!syncingRoute && router != null) {
            router.setProjectorSource(source, dialogParent.get());
        }
    }

    /** Shows where Preview and Program go ("→ Display 2"), and the projector quick switch. */
    private void syncRouting() {
        for (OutputService.Source s : OutputService.Source.values()) {
            JLabel label = s == OutputService.Source.PREVIEW ? previewRoutes : programRoutes;
            java.util.List<String> places = router.routes().stream().filter(r -> r.source() == s)
                    .map(r -> r.showing() ? r.place() : r.place() + " (waiting)").toList();
            label.setText(places.isEmpty() ? "not on a display" : "→ " + String.join(", ", places));
            label.setForeground(places.isEmpty() ? Palette.mutedText()
                    : s == OutputService.Source.PROGRAM ? Palette.live() : Palette.accent());
        }
        syncingRoute = true;
        try {
            (router.projectorSource() == OutputService.Source.PREVIEW ? projectorPreview : projectorProgram).setSelected(true);
        } finally {
            syncingRoute = false;
        }
    }

    private JComponent buildTakeColumn() {
        JPanel col = Ui.transparent(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 6, 0);

        takeButton.putClientProperty(FlatClientProperties.STYLE,
                "font: bold +6; margin: 18,16,18,16; arc: 12; background: #16A34A; foreground: #FFFFFF;"
                        + " hoverBackground: #1DB954; pressedBackground: #12813A; disabledBackground: #1C2740");
        takeButton.setEnabled(false);
        takeButton.setFocusable(false);
        takeButton.addActionListener(e -> actions.take());
        updateTakeTooltip();
        c.gridy = 0;
        col.add(takeButton, c);

        transitionCombo.setToolTipText("Transition used when Program changes");
        transitionCombo.addActionListener(e -> transitionChanged());
        takeSceneButton.putClientProperty(FlatClientProperties.STYLE,
                "font: bold -1; margin: 6,8,6,8; arc: 10; background: #B45309; foreground: #FFFFFF");
        takeSceneButton.setToolTipText("Program switches to the scene shown in Preview; the live lyrics stay");
        takeSceneButton.setFocusable(false);
        takeSceneButton.setVisible(false);
        takeSceneButton.addActionListener(e -> actions.takeScene());
        c.gridy = 3;
        col.add(takeSceneButton, c);
        c.gridy = 1;
        col.add(transitionCombo, c);


        durationCombo.setToolTipText("Transition duration");
        durationCombo.addActionListener(e -> transitionChanged());
        c.gridy = 2;
        col.add(durationCombo, c);

        col.setPreferredSize(new Dimension(128, 10));
        col.setMinimumSize(new Dimension(110, 10));
        return col;
    }

    private JComponent buildControlBar() {
        blackButton.addActionListener(e -> program.toggleBlack());
        blankButton.addActionListener(e -> program.toggleBlank());
        lyricsButton.addActionListener(e -> program.toggleLyrics());
        backgroundButton.addActionListener(e -> program.toggleBackground());
        autoTakeButton.setToolTipText("Skips Preview: choosing a slide sends it straight to Program and the projector. Normally off.");
        autoTakeButton.putClientProperty(FlatClientProperties.STYLE, "font: -1; margin: 3,8,3,8");
        autoTakeButton.setFocusable(false);
        autoTakeButton.addActionListener(e -> {
            updateTakeTooltip();
            actions.autoTakeChanged(autoTakeButton.isSelected());
        });
        projectorButton.setToolTipText("Open or close the projector output (F5). Choose the display on the Output page.");
        projectorButton.setFocusable(false);
        projectorButton.putClientProperty(FlatClientProperties.STYLE, "font: bold");
        projectorButton.addActionListener(e -> actions.toggleOutput());

        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(blackButton);
        left.add(blankButton);
        left.add(lyricsButton);
        left.add(backgroundButton);
        left.add(javax.swing.Box.createHorizontalStrut(6));
        left.add(autoTakeButton);
        hideLowerThird.putClientProperty(FlatClientProperties.STYLE,
                "font: bold -1; margin: 3,9,3,9; background: #EF4444; foreground: #FFFFFF");
        hideLowerThird.setFocusable(false);
        hideLowerThird.setVisible(false);
        hideLowerThird.addActionListener(e -> program.hideLowerThird());
        left.add(hideLowerThird);
        // quick switch: what the projector / TV shows
        javax.swing.ButtonGroup sourceGroup = new javax.swing.ButtonGroup();
        JPanel quick = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        for (JToggleButton b : new JToggleButton[]{projectorPreview, projectorProgram}) {
            b.putClientProperty(FlatClientProperties.STYLE, "font: bold -2; margin: 3,7,3,7");
            b.setFocusable(false);
            sourceGroup.add(b);
            quick.add(b);
        }
        projectorProgram.setSelected(true);
        projectorPreview.setToolTipText("The projector / TV shows Preview (e.g. while Program goes to the livestream)");
        projectorProgram.setToolTipText("The projector / TV shows Program (normal church presentation)");
        projectorPreview.addActionListener(e -> switchProjector(OutputService.Source.PREVIEW));
        projectorProgram.addActionListener(e -> switchProjector(OutputService.Source.PROGRAM));
        JPanel right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(projectorButton);
        right.add(quick);
        JPanel bar = Ui.transparent(new java.awt.BorderLayout());
        bar.add(left, java.awt.BorderLayout.WEST);
        bar.add(right, java.awt.BorderLayout.EAST);
        return bar;
    }

    /** Shows the Program video transport while a video is live (null hides it). */
    public void bindProgramVideo(com.jamc.worship.media.Transport transport) {
        bindProgramMedia(transport, null, null);
    }

    /**
     * PROGRAM MEDIA: play / pause / stop / seek, volume and mute for the video playing on Program (a
     * fullscreen video or a scene's video source). {@code note} is e.g. "No audio track".
     */
    public void bindProgramMedia(com.jamc.worship.media.Transport transport, String title, String note) {
        programTransport.bind(transport);
        programMediaRow.setVisible(transport != null);
        bindProgramMediaLabel(title, note);
    }

    /** Updates the PROGRAM MEDIA title and note without rebinding the controls. */
    public void bindProgramMediaLabel(String title, String note) {
        if (!java.util.Objects.equals(title, mediaTitle)) {
            mediaTitleExpanded = false; // a new video starts folded
        }
        mediaTitle = title;
        mediaNote = note;
        showProgramMediaLabel();
    }

    /**
     * Long names (YouTube titles) show their first three words and "▸ more" so the play / pause / stop /
     * volume controls keep their room; clicking the title unfolds the full name (wrapped), "▾ less" folds it.
     */
    private void showProgramMediaLabel() {
        String title = mediaTitle;
        boolean long_ = title != null && !firstWords(title, TITLE_WORDS).equals(title.strip());
        String shown = title == null ? "" : mediaTitleExpanded || !long_ ? escape(title.strip())
                : escape(firstWords(title, TITLE_WORDS)) + "…";
        String toggle = !long_ ? "" : " <font color='#60A5FA'>" + (mediaTitleExpanded ? "▾ less" : "▸ more") + "</font>";
        String width = mediaTitleExpanded ? "<body style='width:240px'>" : "";
        programMediaLabel.setText("<html>" + width + "<b>PROGRAM MEDIA</b>" + (title == null ? "" : "<br>" + shown + toggle)
                + (mediaNote == null ? "" : "<br><font color='#F59E0B'>" + escape(mediaNote) + "</font>") + "</html>");
        programMediaLabel.setToolTipText(long_ ? (mediaTitleExpanded ? "Click to fold the title" : "Click to see the full title") : null);
        programMediaLabel.setCursor(long_ ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) : null);
        revalidate();
        repaint();
    }

    private static final int TITLE_WORDS = 3;
    private String mediaTitle;
    private String mediaNote;
    private boolean mediaTitleExpanded;

    /** The first {@code n} words of {@code s}. */
    static String firstWords(String s, int n) {
        String[] words = s.strip().split("\\s+");
        return words.length <= n ? s.strip() : String.join(" ", java.util.Arrays.copyOf(words, n));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    /** Called with true / false when the operator turns Preview audio monitoring on / off. */
    public void setMonitorListener(java.util.function.Consumer<Boolean> l) {
        monitorListener = l;
    }

    /** A recording or the livestream takes this app's sound: Preview stays silent so it is not recorded. */
    public void setPreviewAudioBlocked(boolean blocked) {
        previewMonitorButton.setEnabled(!blocked);
        previewMonitorButton.setToolTipText(blocked
                ? "Off while recording or streaming, so only Program's sound is recorded. It comes back when you stop."
                : "Listen to the Preview scene's videos on the monitor device (e.g. headphones). "
                + "Off: Preview is silent, so the church only hears Program.");
        syncMonitorButton();
    }

    private void syncMonitorButton() {
        boolean on = previewMonitorButton.isSelected() && previewMonitorButton.isEnabled();
        previewMonitorButton.setText(on ? "MONITOR ON" : "PREVIEW AUDIO: OFF");
        previewMonitorButton.putClientProperty(FlatClientProperties.STYLE, on
                ? "font: bold -2; margin: 2,8,2,8; selectedBackground: #1677FF; selectedForeground: #FFFFFF"
                : "font: bold -2; margin: 2,8,2,8");
    }

    // ------------------------------------------------------------------ behaviour

    private void transitionChanged() {
        Transition t = (Transition) transitionCombo.getSelectedItem();
        durationCombo.setEnabled(t != Transition.CUT);
        if (!syncing) {
            actions.transitionChanged(t, ProgramOutput.DURATIONS_MS[Math.max(0, durationCombo.getSelectedIndex())]);
        }
    }

    private void syncFromProgram() {
        blackButton.setSelected(program.isBlack());
        blankButton.setSelected(program.isBlank());
        lyricsButton.setSelected(program.isLyricsHidden());
        backgroundButton.setSelected(program.isBackgroundHidden());
        lyricsButton.setText("Clear");
        backgroundButton.setText(program.isBackgroundHidden() ? "Background Off" : "Background");
        hideLowerThird.setVisible(program.isLowerThirdOn());
    }

    private void updateTakeTooltip() {
        takeButton.setToolTipText(autoTakeButton.isSelected()
                ? "Auto-take is on: slides go live as you select them. TAKE re-sends Preview (Space)"
                : "Send Preview to Program (Space)");
    }

    private static JToggleButton liveToggle(String text, String tooltip) {
        JToggleButton b = new JToggleButton(text);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.putClientProperty(FlatClientProperties.STYLE,
                "font: bold -1; margin: 3,9,3,9; selectedBackground: #EF4444; selectedForeground: #FFFFFF");
        return b;
    }

    /** Preview monitor: the full composition TAKE will put live (content, scene layers, previewed background). */
    private final class PreviewMonitor extends OutputCanvas {
        @Override
        protected boolean operatorMonitor() {
            return true;
        }

        PreviewMonitor(ProgramOutput previewState) {
            super(previewState, false, true);
            setOpaque(false);
        }

        @Override
        protected void paintOverlay(Graphics2D g, Rectangle r) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            ProgramOutput p = program();
            ProgramMonitor.pill(g, this, "▶ PREVIEW · NEXT", Palette.accent(), r.x + 6, r.y + 6, false);
            FontMetrics lf = g.getFontMetrics(getFont().deriveFont(Font.BOLD, 9f));
            int bottom = r.y + r.height - lf.getHeight() - 8;
            if (previewNote != null) {
                // a long YouTube / file name must stay inside the monitor
                String note = ProgramMonitor.fit(previewNote, lf, r.width - 24);
                ProgramMonitor.pill(g, this, note, previewNoteScene ? new java.awt.Color(0xB45309) : Palette.accent(),
                        r.x + 6, bottom, true);
                bottom -= lf.getHeight() + 8;
            }
            if (p.isLyricsHidden()) {
                ProgramMonitor.pill(g, this, "LYRICS CLEARED", Palette.danger(), r.x + 6, bottom, true);
            }
            if (p.content() == null) {
                g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
                FontMetrics fm = g.getFontMetrics();
                String s = "Select a slide to prepare it here";
                g.setColor(Palette.mutedText());
                g.drawString(s, r.x + (r.width - fm.stringWidth(s)) / 2, r.y + r.height / 2);
            } else if (r.width > 180) {
                ProgramMonitor.title(g, this, p, r);
            }
            g.setColor(Palette.accent());
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
        }
    }

    /** Program monitor: the real Program picture plus operator-only status overlays. */
    private static final class ProgramMonitor extends OutputCanvas {
        @Override
        protected boolean operatorMonitor() {
            return true;
        }

        boolean outputOpen;

        ProgramMonitor(ProgramOutput program) {
            super(program);
            setOpaque(false);
        }

        @Override
        protected void paintOverlay(Graphics2D g, Rectangle r) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            ProgramOutput p = program();
            // Program is what the audience sees (projector and OBS window): LIVE whenever it shows something
            boolean live = p.isShowingContent();
            Color badge = live ? Palette.live() : Palette.mutedText();
            String label = live ? "● LIVE · PROGRAM" : "PROGRAM";
            pill(g, this, label, badge, r.x + 6, r.y + 6, false);
            if (p.isBlack()) {
                // the audience sees black; say so plainly and name what returns when Black is switched off
                g.setColor(new Color(0, 0, 0, 170));
                g.fillRect(r.x, r.y, r.width, r.height);
                Font big = getFont().deriveFont(Font.BOLD, Math.max(11f, r.height / 11f));
                g.setFont(big);
                FontMetrics bm = g.getFontMetrics();
                String s = "BLACK OUTPUT ACTIVE";
                g.setColor(Palette.danger());
                g.drawString(s, r.x + (r.width - bm.stringWidth(s)) / 2, r.y + r.height / 2);
                if (p.content() != null) {
                    g.setFont(getFont().deriveFont(Font.PLAIN, 10f));
                    FontMetrics sm = g.getFontMetrics();
                    String under = "Returns to: " + p.content().slide().getLabel();
                    g.setColor(new Color(255, 255, 255, 190));
                    g.drawString(under, r.x + (r.width - sm.stringWidth(under)) / 2, r.y + r.height / 2 + sm.getHeight() + 4);
                }
                pill(g, this, label, badge, r.x + 6, r.y + 6, false);
            }

            List<String> flags = new ArrayList<>();
            if (p.isBlack()) flags.add("BLACK");
            if (p.isBlank()) flags.add("BLANK");
            if (p.isLyricsHidden()) flags.add("LYRICS CLEARED");
            if (p.isBackgroundHidden()) flags.add("BG OFF");
            if (!outputOpen) flags.add("NOT ON A DISPLAY");
            int x = r.x + 6;
            Font f = getFont().deriveFont(Font.BOLD, 9f);
            FontMetrics fm = g.getFontMetrics(f);
            for (String flag : flags) {
                pill(g, this, flag, Palette.danger(), x, r.y + r.height - fm.getHeight() - 8, true);
                x += fm.stringWidth(flag) + 18;
            }
            if (r.width > 180) {
                title(g, this, p, r);
            }
            g.setColor(live ? Palette.live() : Palette.border());
            g.setStroke(new BasicStroke(live ? 2f : 1f));
            g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
        }

        /** "Song · Verse 1" in the top right corner. */
        static void title(Graphics2D g, JComponent c, ProgramOutput p, Rectangle r) {
            if (p.content() == null || p.content().title() == null) {
                return;
            }
            g.setFont(c.getFont().deriveFont(Font.PLAIN, 10f));
            FontMetrics tfm = g.getFontMetrics();
            String title = fit(p.content().title() + " · " + p.content().slide().getLabel(), tfm, r.width / 2);
            g.setColor(new Color(255, 255, 255, 170));
            g.drawString(title, r.x + r.width - tfm.stringWidth(title) - 8, r.y + 6 + tfm.getAscent() + 2);
        }

        /** {@code text} cut with "…" so it is at most {@code width} pixels wide. */
        static String fit(String text, FontMetrics fm, int width) {
            if (fm.stringWidth(text) <= width) {
                return text;
            }
            String t = text;
            while (t.length() > 1 && fm.stringWidth(t + "…") > width) {
                t = t.substring(0, t.length() - 1);
            }
            return t.stripTrailing() + "…";
        }

        static void pill(Graphics2D g, JComponent c, String text, Color color, int x, int y, boolean small) {
            Font f = c.getFont().deriveFont(Font.BOLD, small ? 9f : 10f);
            FontMetrics fm = g.getFontMetrics(f);
            int w = fm.stringWidth(text) + 12;
            int h = fm.getHeight() + 2;
            g.setColor(color);
            g.fillRoundRect(x, y, w, h, h, h);
            g.setColor(Color.WHITE);
            g.setFont(f);
            g.drawString(text, x + 6, y + 1 + fm.getAscent());
        }
    }
}
