package com.jamc.worship.ui.components;

import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.output.Compositor;
import com.jamc.worship.output.FrameSpec;
import com.jamc.worship.output.LiveContent;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * The workspace for a scene item from the service schedule: a large view of the scene's layout (with sample
 * lyrics), and buttons that send it to Preview or put it live. Showing it changes neither Preview nor Program.
 */
public class SceneWorkspacePanel extends JPanel {

    private final JLabel title = Ui.label(" ", Palette::text, "h2");
    private final JLabel meta = Ui.secondary(" ");
    private final Compositor compositor = new Compositor(Rendering.renderer(), true);

    {
        compositor.setStillVideos(true); // the scene's videos show a still picture here, never a playing one
        com.jamc.worship.media.VideoStills.addListener(this::repaint);
        com.jamc.worship.youtube.YouTubeLibrary.addListener(() -> javax.swing.SwingUtilities.invokeLater(this::repaint));
    }
    private final LiveContent sample = LiveContent.snapshot(new Slide("Sample", "Amazing grace\nHow sweet the sound", null),
            SlideStyle.fallback().withTextTransform(TextTransform.UPPERCASE), "Sample");
    private final View view = new View();
    private Scene scene;
    private LiveContent content;

    public SceneWorkspacePanel(Consumer<Scene> toPreview, Consumer<Scene> toProgram) {
        super(new BorderLayout());
        setOpaque(false);
        JButton preview = Ui.button("Send to Preview", "monitor");
        preview.setToolTipText("Preview shows this scene; TAKE puts it live");
        preview.addActionListener(e -> {
            if (scene != null) toPreview.accept(scene);
        });
        JButton live = Ui.primaryButton("Take Scene Live", "play");
        live.setToolTipText("Program (the output) switches to this scene now");
        live.addActionListener(e -> {
            if (scene != null) toProgram.accept(scene);
        });
        JPanel header = Ui.header(title, meta, preview, live);
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 12)));
        JPanel holder = Ui.transparent(new BorderLayout());
        holder.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        holder.add(view);
        add(header, BorderLayout.NORTH);
        add(holder, BorderLayout.CENTER);
        view.setToolTipText("Double-click: Send to Preview");
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && scene != null) {
                    toPreview.accept(scene);
                }
            }
        });
        // camera sources in the scene stay current
        new Timer(1000, e -> {
            if (scene != null && isShowing()) {
                view.repaint();
            }
        }).start();
    }

    /**
     * What the view shows inside the scene, e.g. the presentation slide clicked in the strip (null: sample lyrics).
     * Only this view changes: Preview and Program do not.
     */
    public void setContent(LiveContent content) {
        this.content = content;
        view.repaint();
    }

    /** Shows this scene (null clears the view). */
    public void setScene(Scene scene) {
        if (scene == null || this.scene == null || !java.util.Objects.equals(scene.getId(), this.scene.getId())) {
            content = null; // another scene: back to the sample lyrics until a slide is chosen
        }
        this.scene = scene;
        title.setText(scene == null ? " " : scene.getName());
        meta.setText(scene == null ? " " : "Scene  ·  " + scene.getSources().size()
                + (scene.getSources().size() == 1 ? " layer" : " layers") + "  ·  double-click the item in the schedule to rename");
        view.repaint();
    }

    public Scene scene() {
        return scene;
    }

    /** A picture of the scene's layout (with sample lyrics), e.g. for its card in the slide strip. */
    public java.awt.image.BufferedImage render(Scene s, int w, int h) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            compositor.paint(g, w, h, new FrameSpec(sample, true, true, false, s), null, false);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** The scene's layout at 16:9, as large as the workspace allows. */
    private final class View extends JComponent {
        @Override
        protected void paintComponent(Graphics g0) {
            if (scene == null) {
                return;
            }
            int w = getWidth();
            int h = (int) Math.round(w * 9 / 16.0);
            if (h > getHeight()) {
                h = getHeight();
                w = (int) Math.round(h * 16 / 9.0);
            }
            if (w <= 0 || h <= 0) {
                return;
            }
            int x = (getWidth() - w) / 2;
            Graphics2D g = (Graphics2D) g0.create(x, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            compositor.paint(g, w, h, new FrameSpec(content != null ? content : sample, true, true, false, scene), null, false);
            g.dispose();
            Graphics2D b = (Graphics2D) g0.create();
            b.setColor(Palette.border());
            b.drawRect(x, 0, w - 1, h - 1);
            b.dispose();
        }
    }
}
