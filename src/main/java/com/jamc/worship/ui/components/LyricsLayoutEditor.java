package com.jamc.worship.ui.components;

import com.jamc.worship.config.LyricsBox;
import com.jamc.worship.config.LyricsLayouts;
import com.jamc.worship.config.ProgramLyricsStyle;
import com.jamc.worship.output.LowerThirdLyrics;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * New / Edit layout: the operator drags and resizes the lyrics box on a 16:9 picture, names it and chooses what is
 * drawn behind the words. The sample words are drawn by the real lyrics painter with the Lyrics Appearance look, so
 * the picture is what the stream will show.
 */
final class LyricsLayoutEditor extends JDialog {

    private static final String SAMPLE = "Worthy of ev'ry breath\nWe could ever breathe";

    private final ProgramLyricsStyle look;
    private final JTextField name = new JTextField(22);
    private final JComboBox<LyricsBox.VAlign> valign = new JComboBox<>(LyricsBox.VAlign.values());
    private final JComboBox<LyricsBox.Background> background = new JComboBox<>(LyricsBox.Background.values());
    private final Canvas canvas = new Canvas();
    private LyricsBox box;
    private LyricsLayouts.Layout result;

    private LyricsLayoutEditor(Window owner, ProgramLyricsStyle look, LyricsLayouts.Layout start) {
        super(owner, start == null ? "New Lyrics Layout" : "Edit Lyrics Layout", ModalityType.APPLICATION_MODAL);
        this.look = look;
        this.box = start == null ? LyricsBox.starter() : start.box();
        name.setText(start == null ? "My layout" : start.name());
        valign.setSelectedItem(box.valign());
        background.setSelectedItem(box.background());
        valign.addActionListener(e -> update());
        background.addActionListener(e -> update());

        JPanel top = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.add(Ui.small("Name:"));
        top.add(name);
        top.add(Ui.small("Lines sit at:"));
        top.add(valign);
        top.add(Ui.small("Behind the words:"));
        top.add(background);

        JPanel quick = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        quick.add(Ui.small("Quick place:"));
        quick.add(place("Bottom", 0.1f, 0.68f, 0.8f, 0.26f));
        quick.add(place("Top", 0.1f, 0.05f, 0.8f, 0.26f));
        quick.add(place("Left side", 0.04f, 0.2f, 0.4f, 0.6f));
        quick.add(place("Right side", 0.56f, 0.2f, 0.4f, 0.6f));
        quick.add(place("Center", 0.15f, 0.3f, 0.7f, 0.4f));
        quick.add(place("Corner", 0.6f, 0.7f, 0.37f, 0.25f));

        JButton cancel = Ui.button("CANCEL", null);
        cancel.addActionListener(e -> dispose());
        JButton save = Ui.primaryButton("SAVE LAYOUT", null);
        save.addActionListener(e -> {
            String n = name.getText().strip();
            LyricsLayouts.Layout base = start == null ? LyricsLayouts.create(n, box) : start;
            result = new LyricsLayouts.Layout(base.id(), n, box);
            dispose();
        });
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(save);

        JPanel north = Ui.transparent(new BorderLayout(0, 8));
        north.add(top, BorderLayout.NORTH);
        north.add(quick, BorderLayout.SOUTH);
        JPanel south = Ui.transparent(new BorderLayout(0, 6));
        south.add(Ui.label("Drag the box to move it, drag its edges or corners to resize it, or draw a new box. "
                + "Font, colors, padding and maximum lines come from Lyrics Appearance; long lyrics get smaller to fit "
                + "the box.", Palette::mutedText, "small"), BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(north, BorderLayout.NORTH);
        root.add(canvas, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(save);
        pack();
        setMinimumSize(new Dimension(640, 520));
        setLocationRelativeTo(owner);
    }

    /** Opens the editor; the saved layout, or null when cancelled. {@code start} null makes a new one. */
    static LyricsLayouts.Layout edit(Component parent, ProgramLyricsStyle look, LyricsLayouts.Layout start) {
        Window owner = parent == null ? null : javax.swing.SwingUtilities.getWindowAncestor(parent);
        LyricsLayoutEditor d = new LyricsLayoutEditor(owner, look, start);
        d.setVisible(true);
        return d.result;
    }

    private JButton place(String label, float x, float y, float w, float h) {
        JButton b = Ui.button(label, null);
        b.addActionListener(e -> {
            box = box.at(x, y, w, h);
            canvas.repaint();
        });
        return b;
    }

    private void update() {
        box = box.with((LyricsBox.VAlign) valign.getSelectedItem(), (LyricsBox.Background) background.getSelectedItem());
        canvas.repaint();
    }

    /** The 16:9 picture with the box and its sample lyrics. */
    private final class Canvas extends JComponent {
        private static final int HANDLE = 8;
        /** What the drag changes: move, an edge/corner (N, S, E, W bits), or a new box. */
        private int drag;
        private static final int MOVE = 16;
        private static final int NEW = 32;
        private float sx;
        private float sy;
        private LyricsBox startBox;

        Canvas() {
            setPreferredSize(new Dimension(720, 405));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    Rectangle pic = picture();
                    if (!pic.contains(e.getPoint())) {
                        drag = 0;
                        return;
                    }
                    sx = nx(e.getX());
                    sy = ny(e.getY());
                    startBox = box;
                    drag = hit(e.getX(), e.getY());
                    if (drag == 0) {
                        drag = NEW;
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (drag == 0) {
                        return;
                    }
                    float x = nx(e.getX());
                    float y = ny(e.getY());
                    box = dragged(startBox, drag, sx, sy, x, y);
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(cursorFor(hit(e.getX(), e.getY()))));
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        /** The 16:9 picture inside the component. */
        Rectangle picture() {
            int w = getWidth();
            int h = getHeight();
            int pw = Math.min(w, h * 16 / 9);
            int ph = pw * 9 / 16;
            return new Rectangle((w - pw) / 2, (h - ph) / 2, pw, ph);
        }

        private float nx(int x) {
            Rectangle p = picture();
            return Math.clamp((x - p.x) / (float) p.width, 0f, 1f);
        }

        private float ny(int y) {
            Rectangle p = picture();
            return Math.clamp((y - p.y) / (float) p.height, 0f, 1f);
        }

        private Rectangle boxRect() {
            Rectangle p = picture();
            return new Rectangle(p.x + Math.round(box.x() * p.width), p.y + Math.round(box.y() * p.height),
                    Math.round(box.w() * p.width), Math.round(box.h() * p.height));
        }

        /** Which edges (N=1, S=2, W=4, E=8) or MOVE the point grabs; 0 = outside the box. */
        private int hit(int x, int y) {
            Rectangle r = boxRect();
            Rectangle grow = new Rectangle(r);
            grow.grow(HANDLE, HANDLE);
            if (!grow.contains(x, y)) {
                return 0;
            }
            int edges = 0;
            if (Math.abs(y - r.y) <= HANDLE) {
                edges |= 1;
            } else if (Math.abs(y - (r.y + r.height)) <= HANDLE) {
                edges |= 2;
            }
            if (Math.abs(x - r.x) <= HANDLE) {
                edges |= 4;
            } else if (Math.abs(x - (r.x + r.width)) <= HANDLE) {
                edges |= 8;
            }
            return edges != 0 ? edges : r.contains(x, y) ? MOVE : 0;
        }

        private int cursorFor(int h) {
            return switch (h) {
                case 1 -> Cursor.N_RESIZE_CURSOR;
                case 2 -> Cursor.S_RESIZE_CURSOR;
                case 4 -> Cursor.W_RESIZE_CURSOR;
                case 8 -> Cursor.E_RESIZE_CURSOR;
                case 5 -> Cursor.NW_RESIZE_CURSOR;
                case 9 -> Cursor.NE_RESIZE_CURSOR;
                case 6 -> Cursor.SW_RESIZE_CURSOR;
                case 10 -> Cursor.SE_RESIZE_CURSOR;
                case MOVE -> Cursor.MOVE_CURSOR;
                default -> Cursor.CROSSHAIR_CURSOR;
            };
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Rectangle p = picture();
                g.translate(p.x, p.y);
                g.clipRect(0, 0, p.width, p.height);
                int w = p.width;
                int h = p.height;
                g.setPaint(new GradientPaint(0, 0, new Color(0x1B2A4A), w, h, new Color(0x0A1020)));
                g.fillRect(0, 0, w, h);
                g.setColor(new Color(0x3B4A66)); // a singer-like silhouette, as in the Lyrics Appearance sample
                g.fillOval(w * 40 / 100, h * 12 / 100, w * 16 / 100, h * 30 / 100);
                g.fillRoundRect(w * 30 / 100, h * 40 / 100, w * 36 / 100, h, w / 10, w / 10);
                LowerThirdLyrics.paint(g, w, h, SAMPLE, look.withBox(box));
                // the box being edited, with its handles
                Rectangle r = boxRect();
                r.translate(-p.x, -p.y);
                g.setColor(new Color(0x38BDF8));
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{6f, 4f}, 0f));
                g.drawRect(r.x, r.y, r.width, r.height);
                g.setStroke(new BasicStroke(1f));
                for (int[] c : new int[][]{{r.x, r.y}, {r.x + r.width, r.y}, {r.x, r.y + r.height},
                        {r.x + r.width, r.y + r.height}, {r.x + r.width / 2, r.y}, {r.x + r.width / 2, r.y + r.height},
                        {r.x, r.y + r.height / 2}, {r.x + r.width, r.y + r.height / 2}}) {
                    g.setColor(Color.WHITE);
                    g.fillRect(c[0] - 4, c[1] - 4, 8, 8);
                    g.setColor(new Color(0x0284C7));
                    g.drawRect(c[0] - 4, c[1] - 4, 8, 8);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** The box after dragging from (sx, sy) to (x, y); all in fractions of the picture. */
    static LyricsBox dragged(LyricsBox b, int drag, float sx, float sy, float x, float y) {
        float dx = x - sx;
        float dy = y - sy;
        if (drag == 16) { // move: keep the size, stay on the picture
            return b.at(Math.clamp(b.x() + dx, 0f, 1f - b.w()), Math.clamp(b.y() + dy, 0f, 1f - b.h()), b.w(), b.h());
        }
        if (drag == 32) { // a new box drawn from the press point
            return b.at(Math.min(sx, x), Math.min(sy, y), Math.max(LyricsBox.MIN, Math.abs(x - sx)),
                    Math.max(LyricsBox.MIN, Math.abs(y - sy)));
        }
        float left = b.x();
        float top = b.y();
        float right = b.x() + b.w();
        float bottom = b.y() + b.h();
        if ((drag & 1) != 0) {
            top = Math.clamp(top + dy, 0f, bottom - LyricsBox.MIN);
        }
        if ((drag & 2) != 0) {
            bottom = Math.clamp(bottom + dy, top + LyricsBox.MIN, 1f);
        }
        if ((drag & 4) != 0) {
            left = Math.clamp(left + dx, 0f, right - LyricsBox.MIN);
        }
        if ((drag & 8) != 0) {
            right = Math.clamp(right + dx, left + LyricsBox.MIN, 1f);
        }
        return b.at(left, top, right - left, bottom - top);
    }
}
