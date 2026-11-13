package com.jamc.worship.ui.dialogs;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.media.MediaEngine;
import com.jamc.worship.media.VideoPlayer;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.pixabay.PixabayClient;
import com.jamc.worship.pixabay.PixabayPresets;
import com.jamc.worship.pixabay.PixabayThumbnails;
import com.jamc.worship.pixabay.PixabayVideo;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;
import com.formdev.flatlaf.FlatClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Library → Videos / Backgrounds → "Pixabay Worship Backgrounds…": browse Pixabay videos with LiveCat's worship
 * presets, through the official Pixabay API only.
 *
 * <ul>
 *   <li>The cards are STILL pictures (Pixabay's poster of each video); nothing plays when the window opens.</li>
 *   <li>Rest the mouse on a picture for 0.2 s and that video plays there, muted and looping, from Pixabay's smallest
 *       version; move away and the picture comes back. One preview at a time, through one shared VLC player.</li>
 *   <li>USE AS BACKGROUND adds the video to Library → Backgrounds and opens it in MEDIA PREVIEW, ready for
 *       TAKE AS BACKGROUND. ADD TO VIDEOS adds it to Library → Videos. Preview and Program are never changed here.</li>
 *   <li>Nothing is downloaded: the library keeps Pixabay's address, and the video streams when it is shown
 *       (the Pixabay API documentation allows videos to be embedded directly). Every card credits the creator and
 *       Pixabay.</li>
 * </ul>
 */
public final class PixabayBrowserDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(PixabayBrowserDialog.class);
    private static final int HOVER_DELAY_MS = 200;
    private static final int CARD_MIN_WIDTH = 250;

    /**
     * What the operator chose: the last item added, and whether it should open in Backgrounds, or in Videos → Local
     * (a downloaded video).
     */
    public record Result(MediaItem item, boolean background, boolean local) {
        public Result(MediaItem item, boolean background) {
            this(item, background, false);
        }
    }

    private final MediaService media;
    private final AppConfig config;
    private final PixabayClient client;
    private final JTextField query = new JTextField(24);
    private final JPanel keywords = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final Grid grid = new Grid();
    private final JPanel status = Ui.transparent(new BorderLayout());
    private final JButton loadMore = Ui.button("Load More", "chevron-down");
    private final HoverPreview hover = new HoverPreview();
    private final List<Card> cards = new ArrayList<>();
    private final Runnable postersArrived = this::repaintCards;
    private PixabayPresets preset = PixabayPresets.SLOW_WORSHIP;
    private String currentQuery;
    private int page;
    private int searchSeq;
    private Result result;

    private PixabayBrowserDialog(Window owner, MediaService media, AppConfig config) {
        super(owner, "Praise & Worship Backgrounds", ModalityType.APPLICATION_MODAL);
        this.media = media;
        this.config = config;
        this.client = new PixabayClient(() -> config.get(PixabayClient.KEY_API_KEY, null),
                config.paths().dataDir().resolve("pixabay").resolve("cache"));
        PixabayThumbnails.install(config.paths().dataDir().resolve("pixabay").resolve("posters"));
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                hover.release();
                PixabayThumbnails.removeListener(postersArrived);
            }

            @Override
            public void windowIconified(WindowEvent e) {
                hover.stop(); // minimized: no preview keeps streaming
            }
        });
        PixabayThumbnails.addListener(postersArrived);

        JPanel root = Ui.panel(new BorderLayout(), Palette::panelBackground);
        root.add(buildHeader(), BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> hover.stop()); // a scrolled-away card stops playing
        JPanel center = Ui.transparent(new BorderLayout());
        center.add(status, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        loadMore.addActionListener(e -> search(currentQuery, page + 1));
        loadMore.setVisible(false);
        JPanel more = Ui.transparent(new FlowLayout(FlowLayout.CENTER, 0, 8));
        more.add(loadMore);
        center.add(more, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        JButton done = Ui.button("Done", null);
        done.addActionListener(e -> dispose());
        JLabel credit = Ui.label("Videos from Pixabay (pixabay.com) · streamed when shown, never downloaded · "
                + "free under the Pixabay Content License", Palette::mutedText, "small");
        JPanel footer = Ui.transparent(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 16, 6, 12)));
        footer.add(credit, BorderLayout.CENTER);
        footer.add(done, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose, JComponent.WHEN_IN_FOCUSED_WINDOW);

        setMinimumSize(new Dimension(900, 600));
        setSize(1180, 760);
        setLocationRelativeTo(owner);
        choosePreset(preset);
    }

    /** Shows the browser; returns what was added last (null when nothing was). */
    public static Result showDialog(Window owner, MediaService media, AppConfig config) {
        PixabayBrowserDialog d = new PixabayBrowserDialog(owner, media, config);
        com.jamc.worship.ui.SoftModal.showAndWait(d); // minimizing it lets the app be used meanwhile
        return d.result;
    }

    // ------------------------------------------------------------------ header and searching

    private JComponent buildHeader() {
        JPanel presets = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ButtonGroup group = new ButtonGroup();
        for (PixabayPresets p : PixabayPresets.values()) {
            JToggleButton b = new JToggleButton(p.label());
            b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            b.setSelected(p == preset);
            b.setFocusable(false);
            b.addActionListener(e -> choosePreset(p));
            group.add(b);
            presets.add(b);
        }
        query.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search Pixabay videos…");
        query.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        query.addActionListener(e -> searchTyped());
        JButton go = Ui.primaryButton("Search", "search");
        go.addActionListener(e -> searchTyped());
        JButton refresh = Ui.button("Refresh", "refresh");
        refresh.setToolTipText("Search again (Pixabay answers are kept for 24 hours, as Pixabay asks)");
        refresh.addActionListener(e -> search(currentQuery, 1));

        JPanel searchRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        searchRow.add(query);
        searchRow.add(go);
        searchRow.add(refresh);

        JPanel title = Ui.transparent(new BorderLayout());
        title.add(Ui.label("Praise & Worship Backgrounds", Palette::text, "h3"), BorderLayout.WEST);
        title.add(searchRow, BorderLayout.EAST);

        JPanel head = Ui.transparent(new java.awt.GridLayout(0, 1, 0, 6));
        head.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 16)));
        head.add(title);
        head.add(Ui.small("Rest the mouse on a picture to preview it. Nothing plays until you do, and Preview / Program are not changed here."));
        head.add(presets);
        head.add(keywords);
        return head;
    }

    private void choosePreset(PixabayPresets p) {
        preset = p;
        keywords.removeAll();
        if (p == PixabayPresets.CUSTOM) {
            keywords.add(Ui.small("Type your own words above, then Search."));
            query.requestFocusInWindow();
        } else {
            ButtonGroup group = new ButtonGroup();
            boolean first = true;
            for (String k : p.keywords()) {
                JToggleButton chip = new JToggleButton(k);
                chip.putClientProperty(FlatClientProperties.STYLE, "arc: 999; margin: 2,10,2,10; font: -1");
                chip.setFocusable(false);
                chip.setSelected(first);
                chip.addActionListener(e -> {
                    query.setText(k);
                    search(k, 1);
                });
                group.add(chip);
                keywords.add(chip);
                first = false;
            }
            query.setText(p.keywords().getFirst());
            search(p.keywords().getFirst(), 1);
        }
        keywords.revalidate();
        keywords.repaint();
    }

    private void searchTyped() {
        String q = query.getText().strip();
        if (!q.isEmpty()) {
            search(q, 1);
        }
    }

    private void search(String q, int pageNo) {
        if (q == null || q.isBlank()) {
            return;
        }
        if (!client.hasApiKey()) {
            showNoKey();
            return;
        }
        int seq = ++searchSeq;
        currentQuery = q;
        if (pageNo == 1) {
            hover.stop();
            cards.clear();
            grid.removeAll();
            for (int i = 0; i < 8; i++) {
                grid.add(new Card(null)); // loading skeletons
            }
            showStatus(null, false);
        }
        loadMore.setEnabled(false);
        loadMore.setText("Loading…");
        grid.revalidate();
        grid.repaint();
        BackgroundTask.run("Search Pixabay", () -> client.search(q, pageNo), found -> {
            if (seq != searchSeq) {
                return; // a newer search started meanwhile
            }
            if (pageNo == 1) {
                grid.removeAll();
            }
            for (PixabayVideo v : found.videos()) {
                Card c = new Card(v);
                cards.add(c);
                grid.add(c);
            }
            page = found.page();
            loadMore.setText("Load More");
            loadMore.setEnabled(true);
            loadMore.setVisible(found.hasMore() && !found.videos().isEmpty());
            showStatus(cards.isEmpty() ? "No Pixabay videos found for \"" + q + "\". Try other words or another preset." : null, false);
            grid.revalidate();
            grid.repaint();
        }, err -> {
            if (seq != searchSeq) {
                return;
            }
            log.info("Pixabay search failed: {}", err.getMessage());
            if (pageNo == 1) {
                grid.removeAll();
                grid.revalidate();
                grid.repaint();
            }
            loadMore.setText("Load More");
            loadMore.setEnabled(true);
            if (err instanceof PixabayClient.PixabayException pe && (pe.problem() == PixabayClient.Problem.NO_KEY
                    || pe.problem() == PixabayClient.Problem.INVALID_KEY)) {
                showNoKey();
                return;
            }
            showStatus(err instanceof PixabayClient.PixabayException ? err.getMessage()
                    : "Pixabay could not be searched: " + err.getMessage(), true);
        });
    }

    /** A message above the grid (empty result or an error); {@code retry} adds a RETRY button. */
    private void showStatus(String message, boolean retry) {
        status.removeAll();
        if (message != null) {
            JPanel box = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
            box.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
            JLabel l = Ui.label(message, retry ? Palette::warning : Palette::secondaryText, null);
            box.add(l);
            if (retry) {
                JButton again = Ui.button("Retry", "refresh");
                again.addActionListener(e -> search(currentQuery, cards.isEmpty() ? 1 : page + 1));
                box.add(again);
            }
            status.add(box, BorderLayout.CENTER);
        }
        status.revalidate();
        status.repaint();
    }

    private void showNoKey() {
        status.removeAll();
        JPanel box = Ui.transparent(new BorderLayout(0, 8));
        box.setBorder(BorderFactory.createEmptyBorder(24, 24, 12, 24));
        box.add(Ui.label("Pixabay needs the church's free API key", Palette::text, "h4"), BorderLayout.NORTH);
        box.add(Ui.label("<html>Sign in at pixabay.com, open pixabay.com/api/docs and copy \"Your API key\". It is saved "
                + "on this computer only and is never shown again. You can also set it in Settings → Integrations → Pixabay.</html>",
                Palette::secondaryText, null), BorderLayout.CENTER);
        JButton set = Ui.primaryButton("Set API Key…", null);
        set.addActionListener(e -> askForKey(this, config, () -> {
            status.removeAll();
            status.revalidate();
            choosePreset(preset);
        }));
        JPanel row = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(set);
        box.add(row, BorderLayout.SOUTH);
        status.add(box, BorderLayout.CENTER);
        status.revalidate();
        status.repaint();
        loadMore.setVisible(false);
    }

    /**
     * Asks for a Pixabay key, checks it with one small request off the Swing thread and saves it only when Pixabay
     * accepts it; then {@code onSaved} runs. The key is never logged or shown again.
     */
    public static void askForKey(java.awt.Component parent, AppConfig config, Runnable onSaved) {
        JPasswordField field = new JPasswordField(36);
        JPanel p = Ui.transparent(new BorderLayout(0, 6));
        p.add(new JLabel("Pixabay API key (pixabay.com/api/docs → \"Your API key\"):"), BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(parent, p, "Pixabay API key", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION || field.getPassword().length == 0) {
            return;
        }
        String key = new String(field.getPassword()).strip();
        parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        BackgroundTask.run("Check Pixabay key", () -> {
            new PixabayClient(() -> key, null).validate(key);
            return true;
        }, ok -> {
            parent.setCursor(Cursor.getDefaultCursor());
            config.set(PixabayClient.KEY_API_KEY, key);
            config.save();
            onSaved.run();
        }, err -> {
            parent.setCursor(Cursor.getDefaultCursor());
            Dialogs.showInfo(parent, "Pixabay key not saved", err.getMessage());
        });
    }

    // ------------------------------------------------------------------ actions

    private void addVideo(PixabayVideo v, boolean background) {
        hover.stop();
        BackgroundTask.run("Add Pixabay video", () -> media.addPixabay(v, background), item -> {
            result = new Result(item, background);
            if (background) {
                dispose(); // Library → Backgrounds opens it in MEDIA PREVIEW, ready for TAKE AS BACKGROUND
            } else {
                showStatus("\"" + item.getName() + "\" is in Library → Videos (streams from Pixabay, nothing downloaded).", false);
            }
        }, err -> Dialogs.showError(this, "Could not add the Pixabay video.", err));
    }

    /**
     * Download: saves the video on this computer for good and puts it in Library → Videos → Local, as a video file
     * (user, 2026-11-13: "a download goes straight to Local"). It plays offline and loops smoothly, with no data used
     * again. The window stays open; the download carries on if it is closed, and the list then updates by itself.
     */
    private void downloadVideo(PixabayVideo v, JButton button) {
        hover.stop();
        PixabayVideo.Rendition r = v.playback();
        if (r == null) {
            return;
        }
        button.setEnabled(false);
        button.setText("Downloading…");
        showStatus("Downloading \"" + v.title() + "\" (" + sizeText(v) + ")… You can keep browsing.", false);
        com.jamc.worship.media.OnlineMedia.download(r.url(), ok -> {
            java.nio.file.Path file = ok ? com.jamc.worship.media.OnlineMedia.copyFile(r.url()) : null;
            if (file == null) {
                showDownloadState(button, v);
                if (isDisplayable()) {
                    showStatus("Could not download \"" + v.title() + "\". Check the internet and press Download again.", false);
                }
                return;
            }
            BackgroundTask.run("Add downloaded video", () -> media.addDownloaded(file, v.title(), v.user(),
                    r.width() > 0 ? r.width() : null, r.height() > 0 ? r.height() : null,
                    v.durationSec() > 0 ? v.durationSec() * 1000L : null), item -> {
                result = new Result(item, false, true); // Videos → Local opens on it when this window closes
                showDownloadState(button, v);
                if (isDisplayable()) {
                    showStatus("\"" + item.getName() + "\" is downloaded: Library → Videos → Local. "
                            + "It plays offline, with no data used again.", false);
                }
            }, err -> {
                showDownloadState(button, v);
                Dialogs.showError(this, "The video was downloaded but could not be added to the library.", err);
            });
        });
    }

    /** Download, Downloading… or ✓ Saved, by whether this video's copy is on this computer. */
    private static void showDownloadState(JButton button, PixabayVideo v) {
        String url = v.playback() == null ? null : v.playback().url();
        if (url != null && com.jamc.worship.media.OnlineMedia.hasCopy(url)) {
            button.setText("✓ Saved (plays offline)");
            button.setToolTipText("Downloaded: in Library → Videos → Local, plays offline. Press to make sure it is listed there");
            button.setEnabled(true);
        } else if (url != null && com.jamc.worship.media.OnlineMedia.isDownloading(url)) {
            button.setText("Downloading…");
            button.setEnabled(false);
        } else {
            button.setText("Download");
            button.setToolTipText("Downloads it (" + sizeText(v) + ") into Library → Videos → Local: "
                    + "plays offline and loops smoothly, no data used again");
            button.setEnabled(url != null);
        }
    }

    private static String sizeText(PixabayVideo v) {
        long size = v.playback() == null ? 0 : v.playback().size();
        return size <= 0 ? "size unknown" : size >= 1024 * 1024 ? (size / (1024 * 1024)) + " MB" : (size / 1024) + " KB";
    }

    private void showDetails(PixabayVideo v) {
        hover.stop();
        PixabayVideo.Rendition r = v.playback();
        String html = "<html><b>" + esc(v.title()) + "</b><br><br>"
                + "Creator: " + esc(v.user()) + " (credit: " + esc(v.credit()) + ")<br>"
                + "Length: " + (v.durationText().isEmpty() ? "unknown" : v.durationText()) + "<br>"
                + "Plays at: " + (r == null ? "-" : r.width() + "×" + r.height()) + "<br>"
                + "Tags: " + esc(v.tags()) + "<br>"
                + "Pixabay id: " + v.id() + "<br><br>"
                + "Streams from Pixabay when it is shown; nothing is downloaded.<br>Needs the internet during the service.</html>";
        Object[] options = {"Use as Background", "Add to Videos", "Open on Pixabay", "Close"};
        int choice = JOptionPane.showOptionDialog(this, html, "Pixabay video", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        switch (choice) {
            case 0 -> addVideo(v, true);
            case 1 -> addVideo(v, false);
            case 2 -> {
                try {
                    if (v.pageUrl() != null && v.pageUrl().startsWith("https://pixabay.com/")) {
                        java.awt.Desktop.getDesktop().browse(java.net.URI.create(v.pageUrl()));
                    }
                } catch (Exception e) {
                    Dialogs.showInfo(this, "Pixabay", v.pageUrl());
                }
            }
            default -> {
            }
        }
    }

    private void repaintCards() {
        for (Card c : cards) {
            c.picture.repaint();
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ grid of cards

    /** 3 or 4 columns (more on a wide window), as wide as the window; it never scrolls sideways. */
    private final class Grid extends JPanel implements Scrollable {
        Grid() {
            super(new GridLayout(0, 4, 12, 12));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    int cols = Math.clamp((getWidth() - 32) / CARD_MIN_WIDTH, 3, 5);
                    GridLayout l = (GridLayout) getLayout();
                    if (l.getColumns() != cols) {
                        l.setColumns(cols);
                        revalidate();
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height - 40;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** One video: its still poster (or the hover preview), length, size, creator and the two actions. */
    private final class Card extends JPanel {
        final PixabayVideo video;
        final JComponent picture;

        Card(PixabayVideo video) {
            super(new BorderLayout(0, 6));
            this.video = video;
            setOpaque(true);
            setBackground(Palette.secondaryBackground());
            setBorder(BorderFactory.createCompoundBorder(Ui.line(true, true, true, true),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)));
            picture = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    paintPicture((Graphics2D) g, getWidth(), getHeight());
                }
            };
            picture.setPreferredSize(new Dimension(CARD_MIN_WIDTH, CARD_MIN_WIDTH * 9 / 16));
            add(picture, BorderLayout.CENTER);
            if (video == null) {
                JLabel loading = Ui.label(" ", Palette::mutedText, "small");
                add(loading, BorderLayout.SOUTH);
                return;
            }
            picture.setToolTipText("Rest the mouse here to preview · click for details");
            picture.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            picture.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover.hover(Card.this);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover.leave(Card.this);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    showDetails(video);
                }
            });

            JLabel name = Ui.label(video.title(), Palette::text, "semibold");
            JLabel by = Ui.label(video.credit(), Palette::secondaryText, "small");
            JButton use = Ui.primaryButton("Use as Background", null);
            use.setToolTipText("Adds it to Library → Backgrounds and opens it in MEDIA PREVIEW; then TAKE AS BACKGROUND puts it live");
            use.addActionListener(e -> addVideo(video, true));
            JButton toVideos = Ui.button("Add to Videos", "plus");
            toVideos.setToolTipText("Adds it to Library → Videos (streams from Pixabay; nothing is downloaded)");
            toVideos.addActionListener(e -> addVideo(video, false));
            JButton download = Ui.button("Download", "arrow-down");
            showDownloadState(download, video);
            download.addActionListener(e -> downloadVideo(video, download));
            JPanel text = Ui.transparent(new GridLayout(0, 1, 0, 1));
            text.add(name);
            text.add(by);
            JPanel buttons = Ui.transparent(new GridLayout(3, 1, 0, 4)); // stacked: every label always readable
            buttons.add(use);
            buttons.add(toVideos);
            buttons.add(download);
            JPanel south = Ui.transparent(new BorderLayout(0, 6));
            south.add(text, BorderLayout.NORTH);
            south.add(buttons, BorderLayout.SOUTH);
            add(south, BorderLayout.SOUTH);
        }

        private void paintPicture(Graphics2D g, int w, int h) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 16:9 box, centered
            int bw = w;
            int bh = Math.round(w * 9 / 16f);
            if (bh > h) {
                bh = h;
                bw = Math.round(h * 16 / 9f);
            }
            int bx = (w - bw) / 2;
            int by = (h - bh) / 2;
            g.setColor(new Color(0x0B1220));
            g.fillRect(bx, by, bw, bh);
            if (video == null) {
                paintSkeleton(g, bx, by, bw, bh);
                return;
            }
            BufferedImage frame = hover.frameFor(this);
            BufferedImage img = frame != null ? frame : PixabayThumbnails.get(video.poster());
            if (img != null) {
                // keep the picture's own shape inside the box (cover: no bars for 16:9 videos)
                double s = Math.max(bw / (double) img.getWidth(), bh / (double) img.getHeight());
                int iw = (int) Math.ceil(img.getWidth() * s);
                int ih = (int) Math.ceil(img.getHeight() * s);
                Graphics2D clip = (Graphics2D) g.create();
                clip.clipRect(bx, by, bw, bh);
                clip.drawImage(img, bx + (bw - iw) / 2, by + (bh - ih) / 2, iw, ih, null);
                clip.dispose();
            } else {
                paintSkeleton(g, bx, by, bw, bh);
            }
            boolean hovered = hover.isHovered(this);
            // corner badges: preview state (top left), length and size (bottom)
            g.setFont(getFont().deriveFont(Font.BOLD, 11f));
            String state = hovered ? (frame != null ? "● PREVIEW" : "LOADING…") : "▶ HOVER";
            badge(g, state, bx + 6, by + 6, hovered ? new Color(0xDC2626) : new Color(0, 0, 0, 150));
            String len = video.durationText();
            if (!len.isEmpty()) {
                int tw = g.getFontMetrics().stringWidth(len) + 10;
                badge(g, len, bx + bw - tw - 6, by + bh - 22, new Color(0, 0, 0, 170));
            }
            String res = video.resolutionText();
            if (!res.isEmpty()) {
                badge(g, res, bx + 6, by + bh - 22, new Color(0, 0, 0, 170));
            }
            if (hovered) {
                g.setColor(Palette.accent());
                g.setStroke(new BasicStroke(2f));
                g.drawRect(bx + 1, by + 1, bw - 3, bh - 3);
            }
        }

        private void paintSkeleton(Graphics2D g, int x, int y, int w, int h) {
            // a soft moving band while the picture or the results load
            long t = System.currentTimeMillis() % 1400;
            int band = (int) (x - w / 2 + (w * 2L) * t / 1400);
            g.setPaint(new java.awt.GradientPaint(band - w / 3f, 0, new Color(0x111A2C), band, 0, new Color(0x1E293B), true));
            g.fillRect(x, y, w, h);
            if (video == null) {
                picture.repaint(120); // keep the skeleton moving while results load
            }
        }

        private void badge(Graphics2D g, String text, int x, int y, Color bg) {
            int tw = g.getFontMetrics().stringWidth(text);
            g.setColor(bg);
            g.fillRoundRect(x, y, tw + 10, 16, 6, 6);
            g.setColor(Color.WHITE);
            g.drawString(text, x + 5, y + 12);
        }
    }

    // ------------------------------------------------------------------ hover preview (one player for every card)

    /**
     * The one hover preview: after the mouse rests 0.2 s on a picture, Pixabay's smallest version of that video plays
     * there, muted and looping; leaving stops it and the poster comes back. One VLC player serves every card and is
     * released when the window closes, so search results never create players.
     */
    private final class HoverPreview {
        private final Timer delay = new Timer(HOVER_DELAY_MS, e -> start());
        private final Timer repaint = new Timer(33, e -> repaintHovered());
        private VideoPlayer player;
        private Card hovered;
        private Card playing;
        private long firstFrame;
        private boolean unavailable;

        HoverPreview() {
            delay.setRepeats(false);
        }

        void hover(Card c) {
            hovered = c;
            delay.restart();
            c.picture.repaint();
        }

        void leave(Card c) {
            if (hovered == c) {
                hovered = null;
                stop();
            }
            c.picture.repaint();
        }

        boolean isHovered(Card c) {
            return hovered == c;
        }

        /** The preview's picture for this card, or null (not hovered, or no frame of THIS video yet). */
        BufferedImage frameFor(Card c) {
            if (c != playing || player == null || player.frameCount() <= firstFrame) {
                return null;
            }
            return player.currentFrame();
        }

        private void start() {
            Card c = hovered;
            if (c == null || c.video == null || !c.isShowing() || unavailable) {
                return;
            }
            PixabayVideo.Rendition r = c.video.hover();
            if (r == null) {
                return;
            }
            try {
                if (player == null) {
                    if (!MediaEngine.get().isAvailable()) {
                        unavailable = true;
                        c.picture.setToolTipText("Hover previews need VLC: " + MediaEngine.get().problem());
                        return;
                    }
                    player = new VideoPlayer();
                }
                firstFrame = player.frameCount(); // frames of the previous preview never show on this card
                playing = c;
                player.play(r.url(), true, true, ":network-caching=800", ":no-audio");
                repaint.start();
            } catch (RuntimeException e) {
                log.warn("Hover preview could not start", e);
                unavailable = true;
            }
        }

        void stop() {
            delay.stop();
            repaint.stop();
            Card was = playing;
            playing = null;
            if (player != null) {
                player.stop();
            }
            if (was != null) {
                was.picture.repaint();
            }
        }

        private void repaintHovered() {
            if (playing != null) {
                playing.picture.repaint();
            }
        }

        void release() {
            stop();
            if (player != null) {
                player.release();
                player = null;
            }
        }
    }

    /** Test hook: the cards shown (not loading skeletons). */
    int cardCount() {
        return cards.size();
    }
}
