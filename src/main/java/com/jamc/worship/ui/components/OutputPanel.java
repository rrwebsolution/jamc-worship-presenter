package com.jamc.worship.ui.components;

import com.jamc.worship.output.DisplayManager;
import com.jamc.worship.output.OutputService;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Output page: choose the projector display and mode, open/close the output, identify displays. */
public class OutputPanel extends JPanel {

    private final DisplayManager displays;
    private final OutputService output;
    private final Supplier<GraphicsDevice> operatorDevice;
    private final JLabel status = Ui.label(" ", Palette::text, "h4");
    private final JLabel statusDetail = Ui.small(" ");
    private final JButton openButton = Ui.primaryButton("Open Projector", "monitor");
    private final JComboBox<DisplayManager.Display> displayCombo = new JComboBox<>();
    private final JRadioButton fullscreen = new JRadioButton("Fullscreen on the chosen display (for the projector or TV)");
    private final JRadioButton windowed = new JRadioButton("In a window (for testing on one screen)");
    private final DisplayMap map = new DisplayMap();
    private boolean syncing;

    private final javax.swing.JComboBox<OutputService.Source> sourceCombo =
            new javax.swing.JComboBox<>(new OutputService.Source[]{OutputService.Source.PROGRAM, OutputService.Source.PREVIEW});

    public OutputPanel(DisplayManager displays, OutputService output, Supplier<GraphicsDevice> operatorDevice) {
        super(new BorderLayout());
        this.displays = displays;
        this.output = output;
        this.operatorDevice = operatorDevice;
        setOpaque(false);

        JPanel content = Ui.transparent(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(20, 28, 20, 28));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 18, 0);
        content.add(Ui.label("Output", Palette::text, "h2"), c);
        c.gridy++;

        openButton.addActionListener(e -> output.toggle(SwingUtilities.getWindowAncestor(this)));
        JButton identify = Ui.button("Identify Displays", "monitor");
        identify.setToolTipText("Show each display's number on the screen itself");
        identify.addActionListener(e -> output.identifyDisplays());
        JPanel statusRow = Ui.transparent(new BorderLayout(12, 0));
        JPanel statusText = Ui.transparent(new BorderLayout(0, 2));
        statusText.add(status, BorderLayout.NORTH);
        statusText.add(statusDetail, BorderLayout.SOUTH);
        statusRow.add(statusText, BorderLayout.CENTER);
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(identify);
        buttons.add(openButton);
        statusRow.add(buttons, BorderLayout.EAST);
        content.add(section("Projector Output", "Each display shows Preview or Program (your choice), never the controls. "
                + "Right-click Preview or Program, or use their DISPLAY buttons, for more displays.",
                statusRow), c);
        c.gridy++;

        JPanel displayBody = Ui.transparent(new BorderLayout(0, 12));
        map.setPreferredSize(new Dimension(400, 170));
        displayBody.add(map, BorderLayout.CENTER);
        JPanel choose = Ui.transparent(new GridBagLayout());
        GridBagConstraints d = new GridBagConstraints();
        d.gridx = 0;
        d.gridy = 0;
        d.anchor = GridBagConstraints.WEST;
        d.insets = new Insets(0, 0, 6, 10);
        choose.add(Ui.label("Show output on", Palette::secondaryText, null), d);
        d.gridx = 1;
        d.weightx = 1;
        d.fill = GridBagConstraints.HORIZONTAL;
        choose.add(displayCombo, d);
        d.gridx = 0;
        d.gridy++;
        d.gridwidth = 2;
        d.weightx = 0;
        d.fill = GridBagConstraints.NONE;
        ButtonGroup modes = new ButtonGroup();
        modes.add(fullscreen);
        modes.add(windowed);
        choose.add(fullscreen, d);
        d.gridy++;
        choose.add(windowed, d);
        d.gridy++;
        d.insets = new Insets(10, 0, 6, 10);
        JPanel sourceRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sourceRow.add(Ui.label("The projector shows", Palette::secondaryText, null));
        sourceRow.add(sourceCombo);
        sourceRow.add(Ui.small("Program = normal service.  Preview = in-room screen while Program goes to the livestream."));
        choose.add(sourceRow, d);
        displayBody.add(choose, BorderLayout.SOUTH);
        content.add(section("Display", "Click a display or pick it from the list. Changes apply immediately.",
                displayBody), c);
        c.gridy++;

        JLabel keys = Ui.label("<html>"
                + "<b>Space</b> TAKE (Preview → Program) &nbsp;·&nbsp; <b>← / →</b> previous / next slide<br>"
                + "<b>B</b> Black &nbsp;·&nbsp; <b>W</b> Blank (text off until next TAKE) &nbsp;·&nbsp; "
                + "<b>L</b> Lyrics on/off<br>"
                + "<b>F5</b> Open / close projector &nbsp;·&nbsp; <b>Esc</b> Close fullscreen projector"
                + "</html>", Palette::secondaryText, null);
        content.add(section("Live Shortcuts", "Work anywhere except while typing in a text field.", keys), c);
        c.gridy++;

        JLabel obs = Ui.label("<html>A dedicated \"JAMC Worship - OBS Output\" window with green-screen mode for OBS "
                + "Window Capture (always Program) is opened from Settings → Output & OBS. OBS is optional.</html>",
                Palette::mutedText, "small");
        content.add(section("OBS Output", "For livestreaming with OBS Studio.", obs), c);
        c.gridy++;

        c.weighty = 1;
        content.add(Ui.transparent(new BorderLayout()), c);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        displayCombo.addActionListener(e -> {
            if (!syncing && displayCombo.getSelectedItem() instanceof DisplayManager.Display sel) {
                output.setDisplay(sel.id(), SwingUtilities.getWindowAncestor(this));
            }
        });
        fullscreen.addActionListener(e -> output.setMode(OutputService.Mode.FULLSCREEN, SwingUtilities.getWindowAncestor(this)));
        windowed.addActionListener(e -> output.setMode(OutputService.Mode.WINDOWED, SwingUtilities.getWindowAncestor(this)));
        sourceCombo.addActionListener(e -> {
            if (!syncing && sourceCombo.getSelectedItem() instanceof OutputService.Source src) {
                output.setProjectorSource(src, SwingUtilities.getWindowAncestor(this));
            }
        });
        output.addListener(this::refresh);
        displays.addChangeListener(this::refresh);
        refresh();
    }

    public void refresh() {
        syncing = true;
        try {
            List<DisplayManager.Display> all = displays.displays();
            DisplayManager.Display target = output.targetDisplay();
            displayCombo.removeAllItems();
            for (DisplayManager.Display dsp : all) {
                displayCombo.addItem(dsp);
                if (dsp.id().equals(target.id())) {
                    displayCombo.setSelectedItem(dsp);
                }
            }
            (output.mode() == OutputService.Mode.FULLSCREEN ? fullscreen : windowed).setSelected(true);
            status.setText("Outputs: " + output.describe());
            sourceCombo.setSelectedItem(output.projectorSource());
            status.setForeground(output.isOpen() ? Palette.live() : Palette.text());
            boolean single = !displays.hasSecondaryDisplay();
            statusDetail.setText(single
                    ? "Only one display is connected. Connect the projector, or use windowed mode to rehearse."
                    : all.size() + " displays connected. Output goes to Display " + target.number() + ".");
            openButton.setText(output.isOpen() ? "Close Projector" : "Open Projector");
            map.repaint();
        } finally {
            syncing = false;
        }
    }

    private static JComponent section(String title, String subtitle, JComponent body) {
        JPanel p = Ui.panel(new BorderLayout(0, 12), Palette::secondaryBackground);
        p.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, true, true, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        JPanel head = Ui.transparent(new BorderLayout(0, 2));
        head.add(Ui.label(title, Palette::text, "h4"), BorderLayout.NORTH);
        head.add(Ui.small(subtitle), BorderLayout.SOUTH);
        p.add(head, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    /** Miniature of the desktop arrangement: click a display to send the output there. */
    private final class DisplayMap extends JComponent {
        DisplayMap() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    for (var entry : screenRects().entrySet()) {
                        if (entry.getValue().contains(e.getPoint())) {
                            output.setDisplay(entry.getKey().id(), SwingUtilities.getWindowAncestor(OutputPanel.this));
                            return;
                        }
                    }
                }
            });
        }

        private java.util.Map<DisplayManager.Display, Rectangle2D> screenRects() {
            List<DisplayManager.Display> all = displays.displays();
            Rectangle union = new Rectangle(all.getFirst().bounds());
            all.forEach(d -> union.add(d.bounds()));
            double scale = Math.min((getWidth() - 20) / (double) union.width, (getHeight() - 20) / (double) union.height);
            double ox = (getWidth() - union.width * scale) / 2;
            double oy = (getHeight() - union.height * scale) / 2;
            java.util.Map<DisplayManager.Display, Rectangle2D> out = new java.util.LinkedHashMap<>();
            for (DisplayManager.Display d : all) {
                Rectangle b = d.bounds();
                out.put(d, new Rectangle2D.Double(ox + (b.x - union.x) * scale + 3, oy + (b.y - union.y) * scale + 3,
                        b.width * scale - 6, b.height * scale - 6));
            }
            return out;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (displays.displays().isEmpty()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            String targetId = output.targetDisplay().id();
            GraphicsDevice operator = operatorDevice.get();
            for (var entry : screenRects().entrySet()) {
                DisplayManager.Display d = entry.getKey();
                Rectangle2D r = entry.getValue();
                boolean target = d.id().equals(targetId);
                boolean isOperator = operator != null && Objects.equals(operator.getIDstring(), d.id());
                int x = (int) r.getX();
                int y = (int) r.getY();
                int w = (int) r.getWidth();
                int h = (int) r.getHeight();
                g2.setColor(target ? Palette.withAlpha(Palette.accent(), 60) : Palette.panelBackground());
                g2.fillRoundRect(x, y, w, h, 10, 10);
                g2.setColor(target ? (output.isOpen() ? Palette.live() : Palette.accent()) : Palette.border());
                g2.setStroke(new BasicStroke(target ? 2.5f : 1f));
                g2.drawRoundRect(x, y, w, h, 10, 10);

                g2.setColor(Palette.text());
                g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(14f, h / 3.5f)));
                FontMetrics fm = g2.getFontMetrics();
                String n = String.valueOf(d.number());
                g2.drawString(n, x + (w - fm.stringWidth(n)) / 2, y + h / 2 + fm.getAscent() / 3);

                g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
                fm = g2.getFontMetrics();
                String caption = target ? (output.isOpen() ? "PROJECTOR · LIVE" : "PROJECTOR")
                        : isOperator ? "Operator screen" : d.pixelWidth() + "×" + d.pixelHeight();
                g2.setColor(target ? Color.WHITE : Palette.secondaryText());
                g2.drawString(caption, x + (w - fm.stringWidth(caption)) / 2, y + h - 8);
                if (isOperator && target) {
                    g2.setColor(Palette.warning());
                    String warn = "Operator screen";
                    g2.drawString(warn, x + (w - fm.stringWidth(warn)) / 2, y + fm.getAscent() + 6);
                }
            }
            g2.dispose();
        }
    }
}
