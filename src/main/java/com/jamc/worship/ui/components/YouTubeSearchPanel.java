package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.youtube.YouTubeClient;
import com.jamc.worship.youtube.YouTubeLibrary;
import com.jamc.worship.youtube.YouTubeRef;
import com.jamc.worship.youtube.YouTubeVideo;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Search YouTube" box with results (thumbnail, title, channel, duration, SELECT), shared by
 * Lyrics → Import from YouTube and Videos → Import from YouTube. It only finds and selects a video;
 * what happens next (IMPORT LYRICS or ADD TO VIDEO LIBRARY) belongs to the dialog using it.
 *
 * <p>Typing words searches through the YouTube Data API (the church's own API key); pasting a YouTube
 * link always works, also without a key.
 */
public class YouTubeSearchPanel extends JPanel {

    private final AppConfig config;
    private final JTextField query = new JTextField();
    private final DefaultListModel<YouTubeVideo> model = new DefaultListModel<>();
    private final JList<YouTubeVideo> results = new JList<>(model);
    private final JLabel status = Ui.label(" ", Palette::secondaryText, "small");
    private final JButton searchButton = Ui.primaryButton("Search", "search");
    private final Runnable thumbnailsArrived = results::repaint;
    private long seq;

    /**
     * @param placeholder example search shown in the empty box
     * @param onSelect    called on the event thread when the operator selects a result
     */
    public YouTubeSearchPanel(AppConfig config, String placeholder, Consumer<YouTubeVideo> onSelect) {
        super(new BorderLayout(0, 8));
        this.config = config;
        setOpaque(false);

        query.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder + "   (or paste a YouTube link)");
        query.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("youtube", Icons.SMALL));
        query.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchButton.addActionListener(e -> search());
        Ui.bindKey(query, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "search", this::search, WHEN_FOCUSED);
        JButton keyButton = Ui.button("YouTube API Key…", null);
        keyButton.setToolTipText("The church's YouTube Data API v3 key, used to search YouTube");
        keyButton.addActionListener(e -> editApiKey());

        JPanel row = Ui.transparent(new BorderLayout(6, 0));
        row.add(query, BorderLayout.CENTER);
        row.add(searchButton, BorderLayout.EAST);
        JPanel statusRow = Ui.transparent(new BorderLayout(6, 0));
        statusRow.add(status, BorderLayout.CENTER);
        statusRow.add(keyButton, BorderLayout.EAST);
        JPanel top = Ui.transparent(new BorderLayout(0, 6));
        top.add(Ui.label("Search YouTube", Palette::text, "semibold"), BorderLayout.NORTH);
        top.add(row, BorderLayout.CENTER);
        top.add(statusRow, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setCellRenderer(new ResultRenderer());
        results.setFixedCellHeight(78);
        results.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && results.getSelectedValue() != null) {
                onSelect.accept(results.getSelectedValue());
            }
        });
        JScrollPane scroll = new JScrollPane(results);
        scroll.setBorder(Ui.line(true, true, true, true));
        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(430, 520));
        showHint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        YouTubeLibrary.addListener(thumbnailsArrived);
    }

    @Override
    public void removeNotify() {
        YouTubeLibrary.removeListener(thumbnailsArrived);
        super.removeNotify();
    }

    public void focusSearch() {
        query.requestFocusInWindow();
    }

    /** Runs a search for these words (used to start with the song title). */
    public void search(String words) {
        query.setText(words);
        search();
    }

    private void showHint() {
        YouTubeClient c = YouTubeLibrary.client();
        status.setForeground(Palette.secondaryText());
        status.setText(c.hasApiKey() ? "Type words and press Search, or paste a YouTube link."
                : "Paste a YouTube link. To search by words, add the church's YouTube API key.");
    }

    private void search() {
        String text = query.getText().strip();
        if (text.isEmpty()) {
            showHint();
            return;
        }
        long s = ++seq;
        YouTubeClient c = YouTubeLibrary.client();
        boolean link = YouTubeRef.parse(text).isPresent();
        if (!link && !c.hasApiKey()) {
            status.setForeground(Palette.warning());
            status.setText("Searching by words needs the YouTube API key. You can paste a YouTube link instead.");
            return;
        }
        status.setForeground(Palette.secondaryText());
        status.setText(link ? "Looking up the video…" : "Searching YouTube…");
        searchButton.setEnabled(false);
        BackgroundTask.run("Search YouTube", () -> link ? c.lookup(text).map(List::of).orElse(List.of()) : c.search(text, 15),
                found -> {
                    if (s != seq) {
                        return;
                    }
                    searchButton.setEnabled(true);
                    model.clear();
                    model.addAll(found);
                    status.setForeground(Palette.secondaryText());
                    status.setText(found.isEmpty() ? "No embeddable videos found." : found.size() + (found.size() == 1 ? " result" : " results")
                            + ". Click one to select it.");
                    if (found.size() == 1 && link) {
                        results.setSelectedIndex(0);
                    }
                }, err -> {
                    if (s != seq) {
                        return;
                    }
                    searchButton.setEnabled(true);
                    status.setForeground(Palette.danger());
                    status.setText("<html>" + escape(err.getMessage() == null ? err.toString() : err.getMessage()).replace("\n", "<br>") + "</html>");
                });
    }

    private void editApiKey() {
        JPasswordField field = new JPasswordField(config.get(YouTubeClient.KEY_API_KEY, ""), 32);
        JLabel help = new JLabel("<html><body style='width:380px'>Searching YouTube uses the <b>YouTube Data API v3</b> with "
                + "the church's own API key (create one free in the Google Cloud console: APIs &amp; Services → "
                + "enable \"YouTube Data API v3\" → Credentials → Create API key).<br><br>"
                + "The key is stored only on this computer. Pasting YouTube links works without a key.</body></html>");
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.add(help, BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(this, p, "YouTube API Key", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r == JOptionPane.OK_OPTION) {
            String key = new String(field.getPassword()).strip();
            config.set(YouTubeClient.KEY_API_KEY, key.isEmpty() ? null : key);
            config.save();
            showHint();
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Thumbnail, title, "channel · duration" and a SELECT pill. */
    private static final class ResultRenderer extends JPanel implements ListCellRenderer<YouTubeVideo> {
        private final JLabel title = new JLabel();
        private final JLabel info = new JLabel();
        private final JLabel select = new JLabel("SELECT", SwingConstants.CENTER);
        private YouTubeVideo video;

        ResultRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 10));
            JComponent thumb = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    paintThumbnail((Graphics2D) g, this, video, getWidth(), getHeight());
                }
            };
            thumb.setPreferredSize(new Dimension(112, 63));
            title.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
            info.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            select.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            select.setOpaque(true);
            select.setPreferredSize(new Dimension(58, 24));
            JPanel text = new JPanel(new BorderLayout(0, 4));
            text.setOpaque(false);
            text.add(title, BorderLayout.NORTH);
            text.add(info, BorderLayout.CENTER);
            JPanel east = new JPanel(new java.awt.GridBagLayout());
            east.setOpaque(false);
            east.add(select);
            add(thumb, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(east, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends YouTubeVideo> list, YouTubeVideo v, int index,
                                                      boolean selected, boolean focused) {
            video = v;
            title.setText("<html>" + escape(v.title()) + "</html>");
            info.setText(v.channel() + (v.durationMs() == null ? "" : "  ·  " + TransportControls.format(v.durationMs())));
            title.setForeground(selected ? list.getSelectionForeground() : Palette.text());
            info.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
            select.setText(selected ? "SELECTED" : "SELECT");
            select.setBackground(selected ? Palette.live() : Palette.accent());
            select.setForeground(Color.WHITE);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setOpaque(selected);
            return this;
        }
    }

    /** A YouTube thumbnail letterboxed on black, or a placeholder while it loads. */
    static void paintThumbnail(Graphics2D g, Component c, YouTubeVideo v, int w, int h) {
        g = (Graphics2D) g.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        BufferedImage img = v == null ? null : YouTubeLibrary.thumbnail(v.id(), v.thumbnailUrl());
        if (img != null) {
            double s = Math.max(w / (double) img.getWidth(), h / (double) img.getHeight()); // YouTube thumbs carry black bars
            int iw = (int) Math.ceil(img.getWidth() * s);
            int ih = (int) Math.ceil(img.getHeight() * s);
            g.clipRect(0, 0, w, h);
            g.drawImage(img, (w - iw) / 2, (h - ih) / 2, iw, ih, null);
        } else {
            int size = Math.min(26, h / 2);
            Icons.get("youtube", size, Palette::secondaryText).paintIcon(c, g, (w - size) / 2, (h - size) / 2);
        }
        if (v != null && v.durationMs() != null) {
            String d = TransportControls.format(v.durationMs());
            g.setFont(c.getFont().deriveFont(Font.BOLD, 10f));
            FontMetrics fm = g.getFontMetrics();
            int bw = fm.stringWidth(d) + 8;
            g.setColor(new Color(0, 0, 0, 200));
            g.fillRoundRect(w - bw - 3, h - fm.getHeight() - 3, bw, fm.getHeight(), 4, 4);
            g.setColor(Color.WHITE);
            g.drawString(d, w - bw + 1, h - 3 - fm.getHeight() + fm.getAscent());
        }
        g.dispose();
    }
}
