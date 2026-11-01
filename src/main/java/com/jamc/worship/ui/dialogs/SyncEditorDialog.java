package com.jamc.worship.ui.dialogs;

import com.jamc.worship.media.AudioPlayer;
import com.jamc.worship.media.MediaEngine;
import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.media.Stopwatch;
import com.jamc.worship.media.Transport;
import com.jamc.worship.media.VideoPlayback;
import com.jamc.worship.media.VideoPlayer;
import com.jamc.worship.model.LyricsSync;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.Song;
import com.jamc.worship.service.LyricsFormatter;
import com.jamc.worship.service.SlideGenerator;
import com.jamc.worship.service.SongService;
import com.jamc.worship.service.TimedLyricsParser;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.components.TransportControls;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.youtube.YouTubePlayer;
import com.jamc.worship.youtube.YouTubeRef;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SYNC LYRICS: times each lyrics card against a reference — a YouTube video, a local audio or video file,
 * a timing file (LRC / SRT / VTT) or a manual timeline — with TAP (Space), typed timestamps, small
 * adjustments, SHIFT ALL and TEST SYNC (CURRENT / NEXT). YouTube is only one of the references.
 *
 * <p>The reference plays only in this editor. Presenting the song never shows or plays it: lyrics are
 * always text through the Lyrics / Bible scene source.
 */
public final class SyncEditorDialog extends JDialog {

    /** What the times are measured against. {@code source}: YouTube id or file; null for a manual timeline. */
    public record Reference(LyricsSync.Type type, String source, String label) {
    }

    private static final Pattern TIME = Pattern.compile("^(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,3}))?$|^(\\d+)(?:[.,](\\d{1,3}))?$");

    private final Song song;
    private final SongService songs;
    private final Reference reference;
    private final List<Slide> cards;
    private final Map<String, Long> times = new LinkedHashMap<>();
    private final Transport transport;
    private final VideoPlayback video;
    private final Runnable release;
    private final CardModel model = new CardModel();
    private final JTable table = new JTable(model);
    private final JLabel current = Ui.label(" ", Palette::text, "h3");
    private final JLabel next = Ui.label(" ", Palette::secondaryText, null);
    private final JLabel status = Ui.label(" ", Palette::secondaryText, "small");
    private final JToggleButton test = new JToggleButton("TEST SYNC");
    private final Timer follow = new Timer(100, e -> followTimeline());
    private String currentKey;
    private boolean saved;

    private SyncEditorDialog(Window owner, Song song, SongService songs, Reference reference, Map<String, Long> initial) {
        super(owner, "Sync Lyrics: " + song.getTitle(), ModalityType.APPLICATION_MODAL);
        this.song = song;
        this.songs = songs;
        this.reference = reference;
        this.cards = SlideGenerator.distinctCards(song);
        if (initial != null) {
            times.putAll(initial);
        } else if (song.getSync() != null) {
            for (Slide c : cards) {
                Long t = song.getSync().startOf(c.getCardKey());
                if (t != null) {
                    times.put(c.getCardKey(), t);
                }
            }
        }

        // the reference player: YouTube (embedded player), local video (VLC), local audio (VLC) or a stopwatch
        switch (reference.type()) {
            case YOUTUBE -> {
                YouTubePlayer p = new YouTubePlayer();
                p.cue(YouTubeRef.path(reference.source()), false);
                transport = p;
                video = p;
                release = p::release;
            }
            case LOCAL_VIDEO -> {
                VideoPlayer p = new VideoPlayer();
                p.cue(reference.source(), false);
                transport = p;
                video = p;
                release = p::release;
            }
            case LOCAL_AUDIO -> {
                AudioPlayer p = new AudioPlayer();
                transport = new LazyAudio(p, reference.source());
                video = null;
                release = p::release;
            }
            default -> {
                long last = times.values().stream().mapToLong(Long::longValue).max().orElse(0);
                transport = new Stopwatch(last + 120_000);
                video = null;
                release = () -> { };
            }
        }

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                follow.stop();
                release.run();
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildPlayer(), buildCards());
        split.setResizeWeight(0.5);
        split.setBorder(null);
        JButton cancel = Ui.button("Cancel", null);
        cancel.setFocusable(false);
        cancel.addActionListener(e -> dispose());
        JButton save = Ui.primaryButton("Save Sync", null);
        save.setFocusable(false);
        save.addActionListener(e -> save());
        JPanel footer = Ui.transparent(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(8, 16, 8, 12)));
        footer.add(status, BorderLayout.CENTER);
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(save);
        footer.add(buttons, BorderLayout.EAST);

        JPanel root = Ui.panel(new BorderLayout(), Palette::panelBackground);
        JPanel header = Ui.header(Ui.label("Sync Lyrics — " + song.getTitle(), Palette::text, "h3"),
                Ui.small("Reference: " + reference.label() + ". It plays only here; presenting the song shows TEXT lyrics only."));
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 16)));
        root.add(header, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);

        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "tap", this::tap, JComponent.WHEN_IN_FOCUSED_WINDOW);
        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose, JComponent.WHEN_IN_FOCUSED_WINDOW);
        follow.start();
        updateStatus();
        setMinimumSize(new Dimension(1000, 600));
        setSize(1220, 720);
        setLocationRelativeTo(owner);
    }

    /**
     * Asks which reference to sync with (YouTube, local audio, local video, timing file, manual timeline) and
     * opens the editor. Returns true when new times were saved. {@code song} must have its sections loaded.
     */
    public static boolean sync(Window owner, Song song, SongService songs, String youtubeVideoId) {
        if (song.getSections().isEmpty()) {
            Dialogs.showInfo(owner, "Sync Lyrics", "\"" + song.getTitle() + "\" has no lyrics to sync yet.");
            return false;
        }
        String[] options = {"YouTube", "Local Audio", "Local Video", "Timing File (LRC/SRT/VTT)", "Manual Timeline", "Cancel"};
        int choice = JOptionPane.showOptionDialog(owner, "<html><body style='width:420px'><b>Sync lyrics with…</b><br><br>"
                        + "Times are kept per lyrics card. YouTube is optional: a local recording, a timing file or a "
                        + "manual timeline (tap along with the band) work just as well.</body></html>",
                "Sync Lyrics: " + song.getTitle(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        Reference ref;
        Map<String, Long> initial = null;
        switch (choice) {
            case 0 -> {
                String id = youtubeVideoId;
                if (id == null || id.isBlank()) {
                    Object link = JOptionPane.showInputDialog(owner, "Paste the YouTube link of this song:", "Sync with YouTube",
                            JOptionPane.PLAIN_MESSAGE, null, null, "");
                    id = link == null ? null : YouTubeRef.parse(link.toString()).orElse(null);
                    if (link != null && id == null) {
                        Dialogs.showInfo(owner, "Sync with YouTube", "That is not a YouTube link.");
                    }
                }
                if (id == null) {
                    return false;
                }
                ref = new Reference(LyricsSync.Type.YOUTUBE, id, "YouTube video " + id);
            }
            case 1, 2 -> {
                if (!MediaEngine.get().isAvailable()) {
                    Dialogs.showInfo(owner, "Sync Lyrics", MediaEngine.get().problem());
                    return false;
                }
                MediaKind kind = choice == 1 ? MediaKind.AUDIO : MediaKind.VIDEO;
                File f = chooseFile(owner, kind == MediaKind.AUDIO ? "Audio files" : "Video files", kind.extensions().toArray(String[]::new));
                if (f == null) {
                    return false;
                }
                ref = new Reference(kind == MediaKind.AUDIO ? LyricsSync.Type.LOCAL_AUDIO : LyricsSync.Type.LOCAL_VIDEO,
                        f.getAbsolutePath(), f.getName());
            }
            case 3 -> {
                File f = chooseFile(owner, "Timed lyrics (*.lrc, *.srt, *.vtt)", "lrc", "srt", "vtt");
                if (f == null) {
                    return false;
                }
                try {
                    TimedLyricsParser.Result r = TimedLyricsParser.parse(f.getName(), Files.readString(f.toPath(), StandardCharsets.UTF_8));
                    initial = LyricsFormatter.cardTimes(cardTexts(song), r.cues());
                } catch (Exception e) {
                    Dialogs.showError(owner, "Could not read " + f.getName() + ".", e);
                    return false;
                }
                if (initial.isEmpty()) {
                    Dialogs.showInfo(owner, "Sync Lyrics", "None of the lines in " + f.getName() + " match these lyrics.");
                    return false;
                }
                ref = new Reference(LyricsSync.Type.FILE, f.getAbsolutePath(), f.getName() + " (test with the manual timeline)");
            }
            case 4 -> ref = new Reference(LyricsSync.Type.MANUAL, null, "Manual timeline (stopwatch)");
            default -> {
                return false;
            }
        }
        SyncEditorDialog d = new SyncEditorDialog(owner, song, songs, ref, initial);
        com.jamc.worship.ui.SoftModal.showAndWait(d); // minimizing it lets the app be used meanwhile
        return d.saved;
    }

    /** Opens the editor directly on a reference (e.g. after importing timed lyrics: review, test, save). */
    public static boolean edit(Window owner, Song song, SongService songs, Reference ref) {
        SyncEditorDialog d = new SyncEditorDialog(owner, song, songs, ref, null);
        com.jamc.worship.ui.SoftModal.showAndWait(d); // minimizing it lets the app be used meanwhile
        return d.saved;
    }

    /** Card key → card text, written order. */
    public static Map<String, String> cardTexts(Song song) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Slide c : SlideGenerator.distinctCards(song)) {
            m.put(c.getCardKey(), c.getText());
        }
        return m;
    }

    private static File chooseFile(Window owner, String what, String... ext) {
        JFileChooser chooser = new JFileChooser();
        FileNameExtensionFilter filter = new FileNameExtensionFilter(what, ext);
        chooser.addChoosableFileFilter(filter);
        chooser.setFileFilter(filter);
        return com.jamc.worship.ui.NativeFileDialogs.showOpenDialog(chooser, owner) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    // ------------------------------------------------------------------ layout

    private JComponent buildPlayer() {
        JComponent screen = new Screen();
        screen.setPreferredSize(new Dimension(520, 292));
        TransportControls controls = new TransportControls(true);
        controls.bind(transport);
        JButton back = small("−5s", () -> seekBy(-5000));
        JButton fwd = small("+5s", () -> seekBy(5000));
        JButton play = small("PLAY / PAUSE", () -> {
            if (transport.isPlaying()) {
                transport.pause();
            } else {
                transport.playOrResume();
            }
        });
        JPanel jump = Ui.transparent(new FlowLayout(FlowLayout.CENTER, 6, 0));
        jump.add(back);
        jump.add(play);
        jump.add(fwd);

        JPanel nowNext = Ui.transparent(new GridLayout(0, 1, 0, 2));
        nowNext.add(Ui.label("CURRENT", Palette::live, "semibold"));
        nowNext.add(current);
        nowNext.add(Ui.label("NEXT", Palette::secondaryText, "semibold"));
        nowNext.add(next);

        JPanel south = Ui.transparent(new BorderLayout(0, 8));
        south.add(controls, BorderLayout.NORTH);
        south.add(jump, BorderLayout.CENTER);
        south.add(nowNext, BorderLayout.SOUTH);
        JPanel p = Ui.transparent(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 10));
        p.add(screen, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildCards() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(30);
        table.putClientProperty("JTable.autoStartsEdit", Boolean.FALSE); // Space taps; double-click edits a time
        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                boolean now = row < cards.size() && cards.get(row).getCardKey().equals(currentKey);
                c.setFont(c.getFont().deriveFont(now ? Font.BOLD : Font.PLAIN));
                if (now && !sel) {
                    c.setForeground(Palette.live());
                } else if (!sel) {
                    c.setForeground(Palette.text());
                }
                return c;
            }
        });
        if (!cards.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }

        JButton start = Ui.primaryButton("START TAP SYNC", "play");
        start.setFocusable(false);
        start.setToolTipText("Plays from the beginning; press SPACE as each card should appear");
        start.addActionListener(e -> {
            seekTo(0);
            transport.playOrResume();
            table.setRowSelectionInterval(0, 0);
            table.requestFocusInWindow();
        });
        JButton tap = Ui.button("TAP  (Space)", null);
        tap.setFocusable(false);
        tap.addActionListener(e -> tap());
        JButton clear = small("Clear Time", () -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                times.remove(cards.get(r).getCardKey());
                changed();
            }
        });
        JButton jumpTo = small("Jump To", () -> {
            int r = table.getSelectedRow();
            Long t = r < 0 ? null : times.get(cards.get(r).getCardKey());
            if (t != null) {
                seekTo(t);
                transport.playOrResume();
            }
        });
        test.setFocusable(false);
        test.setToolTipText("Plays the reference and highlights the card that should show (CURRENT / NEXT)");
        test.addActionListener(e -> {
            if (test.isSelected()) {
                transport.playOrResume();
            }
        });

        JPanel row1 = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row1.add(start);
        row1.add(tap);
        row1.add(test);
        JPanel row2 = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.add(Ui.small("Selected card:"));
        for (long d : new long[]{-500, -100, 100, 500}) {
            row2.add(small(label(d), () -> nudge(d)));
        }
        row2.add(jumpTo);
        row2.add(clear);
        JPanel row3 = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row3.add(Ui.small("Shift all:"));
        for (long d : new long[]{-500, -100, 100, 500}) {
            row3.add(small(label(d), () -> shiftAll(d)));
        }
        JPanel tools = Ui.transparent(new GridLayout(0, 1, 0, 6));
        tools.add(row1);
        tools.add(row2);
        tools.add(row3);
        tools.add(Ui.small("Double-click a time to type it (e.g. 00:15.200)."));

        JPanel p = Ui.transparent(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 10, 14, 16));
        p.add(Ui.label("Lyrics cards", Palette::secondaryText, "semibold"), BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        p.add(tools, BorderLayout.SOUTH);
        return p;
    }

    private static String label(long d) {
        return (d > 0 ? "+" : "−") + Math.abs(d) / 1000.0 + "s";
    }

    private static JButton small(String text, Runnable action) {
        JButton b = Ui.button(text, null);
        b.setFocusable(false); // Space must tap, never press a focused button
        b.addActionListener(e -> action.run());
        return b;
    }

    // ------------------------------------------------------------------ actions

    /** TAP: the selected card starts now; selection moves to the next card. */
    private void tap() {
        if (table.isEditing()) {
            return;
        }
        int r = table.getSelectedRow();
        if (r < 0) {
            return;
        }
        times.put(cards.get(r).getCardKey(), Math.max(0, transport.time()));
        changed();
        if (r + 1 < cards.size()) {
            table.setRowSelectionInterval(r + 1, r + 1);
            table.scrollRectToVisible(table.getCellRect(r + 1, 0, true));
        }
    }

    private void nudge(long delta) {
        int r = table.getSelectedRow();
        if (r >= 0) {
            times.computeIfPresent(cards.get(r).getCardKey(), (k, v) -> Math.max(0, v + delta));
            changed();
        }
    }

    private void shiftAll(long delta) {
        times.replaceAll((k, v) -> Math.max(0, v + delta));
        changed();
    }

    private void seekBy(long delta) {
        seekTo(Math.max(0, transport.time() + delta));
    }

    private void seekTo(long ms) {
        if (transport instanceof VideoPlayback v) {
            v.seekTime(ms);
        } else if (transport instanceof Stopwatch s) {
            s.seekTime(ms);
        } else if (transport.length() > 0) {
            transport.seek(Math.clamp(ms / (float) transport.length(), 0f, 1f));
        }
    }

    private void changed() {
        model.fireTableDataChanged();
        updateStatus();
    }

    private void updateStatus() {
        long timed = cards.stream().filter(c -> times.containsKey(c.getCardKey())).count();
        status.setText(timed + " of " + cards.size() + " cards timed"
                + (timed == 0 ? " · Not Synced" : timed < cards.size() ? " · Partially Synced" : " · ✓ Synced"));
    }

    /** CURRENT / NEXT from the timeline; with TEST SYNC on, the selection follows too. */
    private void followTimeline() {
        long t = transport.time();
        String key = null;
        long best = -1;
        for (Map.Entry<String, Long> e : times.entrySet()) {
            if (e.getValue() <= t && e.getValue() > best) {
                best = e.getValue();
                key = e.getKey();
            }
        }
        String nextKey = null;
        long nextT = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : times.entrySet()) {
            if (e.getValue() > t && e.getValue() < nextT) {
                nextT = e.getValue();
                nextKey = e.getKey();
            }
        }
        current.setText(html(textOf(key), "(before the first timed card)"));
        next.setText(html(textOf(nextKey), "—"));
        if (!java.util.Objects.equals(key, currentKey)) {
            currentKey = key;
            table.repaint();
            if (test.isSelected() && key != null) {
                for (int i = 0; i < cards.size(); i++) {
                    if (cards.get(i).getCardKey().equals(key)) {
                        table.setRowSelectionInterval(i, i);
                        table.scrollRectToVisible(table.getCellRect(i, 0, true));
                    }
                }
            }
        }
    }

    private String textOf(String key) {
        if (key == null) {
            return null;
        }
        return cards.stream().filter(c -> c.getCardKey().equals(key)).map(c -> c.getText().toUpperCase())
                .findFirst().orElse(null);
    }

    private static String html(String text, String empty) {
        if (text == null) {
            return empty;
        }
        return "<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>";
    }

    private void save() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        LyricsSync sync = new LyricsSync();
        sync.type = reference.type();
        sync.reference = reference.source();
        for (Slide c : cards) {
            Long t = times.get(c.getCardKey());
            if (t != null) {
                sync.cards.put(c.getCardKey(), t);
            }
        }
        sync.total = cards.size();
        BackgroundTask.run("Save sync", () -> {
            songs.setSync(song.getId(), sync.cards.isEmpty() ? null : sync);
            return true;
        }, ok -> {
            saved = true;
            dispose();
        }, err -> Dialogs.showError(this, "Could not save the sync times.", err));
    }

    static String format(long ms) {
        long m = ms / 60_000;
        long s = (ms / 1000) % 60;
        return String.format("%02d:%02d.%03d", m, s, ms % 1000);
    }

    /** "1:02:03.4", "02:03.450", "15.2" → ms, or null. */
    static Long parse(String text) {
        Matcher m = TIME.matcher(text == null ? "" : text.strip());
        if (!m.matches()) {
            return null;
        }
        if (m.group(5) != null) {
            return Long.parseLong(m.group(5)) * 1000 + frac(m.group(6));
        }
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        return h * 3_600_000 + Long.parseLong(m.group(2)) * 60_000 + Long.parseLong(m.group(3)) * 1000 + frac(m.group(4));
    }

    private static long frac(String f) {
        if (f == null) {
            return 0;
        }
        return Long.parseLong((f + "00").substring(0, 3));
    }

    // ------------------------------------------------------------------ parts

    /** The reference's picture (video), or the CURRENT card in large letters (audio, manual timeline). */
    private final class Screen extends JComponent {
        private final Runnable repaint = this::repaint;

        @Override
        public void addNotify() {
            super.addNotify();
            MediaFrames.addListener(repaint);
            follow.addActionListener(e -> {
                if (video == null) {
                    repaint();
                }
            });
        }

        @Override
        public void removeNotify() {
            MediaFrames.removeListener(repaint);
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = Math.min(getHeight(), (int) Math.round(w * 9 / 16.0));
            w = (int) Math.round(h * 16 / 9.0);
            int x = (getWidth() - w) / 2;
            int y = (getHeight() - h) / 2;
            g2.setColor(Color.BLACK);
            g2.fillRect(x, y, w, h);
            BufferedImage frame = video == null ? null : video.currentFrame();
            if (frame != null) {
                double s = Math.min(w / (double) frame.getWidth(), h / (double) frame.getHeight());
                int iw = (int) (frame.getWidth() * s);
                int ih = (int) (frame.getHeight() * s);
                g2.drawImage(frame, x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, null);
            } else {
                String text = textOf(currentKey);
                g2.setColor(text == null ? Palette.mutedText() : Color.WHITE);
                g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(14f, h / 12f)));
                String[] lines = (text == null ? (video == null ? reference.label() + "  ·  " + TransportControls.format(transport.time())
                        : "Loading…") : text).split("\n");
                var fm = g2.getFontMetrics();
                int total = lines.length * fm.getHeight();
                for (int i = 0; i < lines.length; i++) {
                    g2.drawString(lines[i], x + (w - fm.stringWidth(lines[i])) / 2, y + (h - total) / 2 + i * fm.getHeight() + fm.getAscent());
                }
            }
            g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(x + 6, y + 6, 150, 18, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString("SYNC REFERENCE ONLY", x + 14, y + 19);
            g2.setColor(Palette.border());
            g2.drawRect(x, y, w - 1, h - 1);
            g2.dispose();
        }
    }

    private final class CardModel extends AbstractTableModel {
        private final String[] columns = {"Card", "Lyrics", "Starts at"};

        @Override public int getRowCount() { return cards.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }
        @Override public boolean isCellEditable(int row, int col) { return col == 2; }

        @Override
        public Object getValueAt(int row, int col) {
            Slide c = cards.get(row);
            return switch (col) {
                case 0 -> c.getLabel();
                case 1 -> c.getText().replace('\n', ' ').replace("  ", " ");
                default -> {
                    Long t = times.get(c.getCardKey());
                    yield t == null ? "" : format(t);
                }
            };
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            String text = value == null ? "" : value.toString().strip();
            if (text.isEmpty()) {
                times.remove(cards.get(row).getCardKey());
            } else {
                Long ms = parse(text);
                if (ms == null) {
                    java.awt.Toolkit.getDefaultToolkit().beep();
                    return;
                }
                times.put(cards.get(row).getCardKey(), ms);
            }
            updateStatus();
        }
    }

    /** The local audio file is opened on the first PLAY (VLC has no paused start for audio). */
    private static final class LazyAudio implements Transport {
        private final AudioPlayer player;
        private final String file;

        LazyAudio(AudioPlayer player, String file) {
            this.player = player;
            this.file = file;
        }

        @Override
        public void playOrResume() {
            if (!player.hasMedia()) {
                player.play(file, new File(file).getName());
            } else {
                player.playOrResume();
            }
        }

        @Override public void pause() { player.pause(); }
        @Override public void stop() { player.stop(); }
        @Override public void seek(float position) {
            if (!player.hasMedia()) {
                playOrResume();
            }
            player.seek(position);
        }
        @Override public void setVolume(int volume) { player.setVolume(volume); }
        @Override public int volume() { return player.volume(); }
        @Override public void setMuted(boolean muted) { player.setMuted(muted); }
        @Override public boolean isMuted() { return player.isMuted(); }
        @Override public void setLoop(boolean loop) { player.setLoop(loop); }
        @Override public boolean isLoop() { return player.isLoop(); }
        @Override public boolean isPlaying() { return player.isPlaying(); }
        @Override public long time() { return player.time(); }
        @Override public long length() { return player.length(); }
        @Override public boolean hasMedia() { return true; }
        @Override public void addStateListener(Runnable listener) { player.addStateListener(listener); }
    }
}
