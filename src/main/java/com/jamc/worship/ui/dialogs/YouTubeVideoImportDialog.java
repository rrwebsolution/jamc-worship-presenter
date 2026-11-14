package com.jamc.worship.ui.dialogs;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.components.TransportControls;
import com.jamc.worship.ui.components.YouTubeMonitor;
import com.jamc.worship.ui.components.YouTubeSearchPanel;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.youtube.YouTubeDownloader;
import com.jamc.worship.youtube.YouTubePlayer;
import com.jamc.worship.youtube.YouTubeVideo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Library → Videos → Import from YouTube ("YOUTUBE VIDEO LIBRARY"): search YouTube, select a video,
 * see its VIDEO DETAILS, and ADD TO VIDEO LIBRARY. A selected video plays here at once (AUTOPLAY); when it
 * ends, YouTube's own up-next videos follow (its Mix), unless LOOP is on; it keeps playing while the dialog is
 * minimized. ADD TO VIDEO LIBRARY only references the YouTube video; it plays through the embedded YouTube player.
 * DOWNLOAD saves it on this computer as an .mp4 (yt-dlp) and adds it to Library → Videos → Local, so it plays
 * offline. A download carries on if this window is closed.
 * This never creates lyrics.
 */
public final class YouTubeVideoImportDialog extends JDialog {

    private final MediaService media;
    private final YouTubeMonitor monitor = new YouTubeMonitor("YOUTUBE");
    private final TransportControls transport = new TransportControls(true);
    private final JLabel title = Ui.label(" ", Palette::text, "h3");
    private final JLabel channel = Ui.secondary(" ");
    private final JLabel duration = Ui.secondary(" ");
    private final JLabel message = Ui.label(" ", Palette::secondaryText, "small");
    private final JButton preview = Ui.button("PLAY / PREVIEW", "play");
    private final JButton add = Ui.primaryButton("ADD TO VIDEO LIBRARY", "plus");
    private final JButton download = Ui.button("DOWNLOAD", "arrow-down");
    private final YouTubeDownloader downloader;
    /** Videos being downloaded now (by id), with their latest progress line; survives closing this window. */
    private static final java.util.Map<String, String> DOWNLOADING = new java.util.concurrent.ConcurrentHashMap<>();
    private final CardLayout cards = new CardLayout();
    private final JPanel detail = Ui.transparent(cards);
    private final YouTubeSearchPanel search;
    private YouTubePlayer player;
    private YouTubeVideo video;
    private MediaItem added;
    /** LOOP as the operator left it: kept for the next video (a looping video repeats and never goes to the next). */
    private boolean loop;

    private YouTubeVideoImportDialog(Window owner, MediaService media, AppConfig config) {
        super(owner, "YouTube Video Library", ModalityType.APPLICATION_MODAL);
        this.media = media;
        this.downloader = new YouTubeDownloader(config);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                releasePlayer();
            }
        });

        search = new YouTubeSearchPanel(config, "worship background video", this::select);
        search.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 12));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, search, buildDetail());
        split.setResizeWeight(0.45);
        split.setBorder(null);

        JPanel root = Ui.panel(new BorderLayout(), Palette::panelBackground);
        JPanel header = Ui.header(Ui.label("Import from YouTube", Palette::text, "h3"),
                Ui.small("ADD TO VIDEO LIBRARY keeps a link (plays from YouTube, needs the internet). DOWNLOAD saves it on this computer to play offline."));
        header.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(12, 16, 10, 16)));
        root.add(header, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        JButton done = Ui.button("Done", null);
        done.addActionListener(e -> dispose());
        JPanel footer = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.setBorder(Ui.line(true, false, false, false));
        footer.add(done);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        Ui.bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose, JComponent.WHEN_IN_FOCUSED_WINDOW);

        setMinimumSize(new Dimension(900, 560));
        setSize(1080, 660);
        setLocationRelativeTo(owner);
        search.focusSearch();
    }

    /** Shows the dialog; returns the last video added to the library, if any. */
    public static Optional<MediaItem> showDialog(Window owner, MediaService media, AppConfig config) {
        YouTubeVideoImportDialog d = new YouTubeVideoImportDialog(owner, media, config);
        com.jamc.worship.ui.SoftModal.showAndWait(d); // minimizing it lets the app be used meanwhile
        return Optional.ofNullable(d.added);
    }

    private JComponent buildDetail() {
        JLabel empty = Ui.label("<html><center>VIDEO DETAILS<br><br>Search YouTube and select a video<br>to see it here.</center></html>",
                Palette::mutedText, null);
        empty.setHorizontalAlignment(JLabel.CENTER);

        preview.setToolTipText("A selected video plays here by itself; then YouTube's up-next videos follow (unless LOOP is on). "
                + "Preview and Program are not touched.");
        title.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                showTitle();
            }
        });
        preview.addActionListener(e -> togglePreview());
        add.setToolTipText("Add a reference to this YouTube video to Library → Videos");
        add.addActionListener(e -> addToLibrary());
        download.setToolTipText("<html>Save this video on this computer (.mp4): it goes to Videos → Local (plays offline)<br>"
                + "and Videos → YouTube (the online link) at the same time.<br>"
                + "It then plays offline. HD (up to 1080p) when FFmpeg is installed. Only download videos you may use.</html>");
        download.addActionListener(e -> downloadVideo());

        JLabel kind = Ui.label("YouTube Video", Palette::danger, "semibold");
        JPanel info = Ui.transparent(new GridLayout(0, 1, 0, 2));
        info.add(kind);
        info.add(title);
        info.add(channel);
        info.add(duration);

        JPanel buttons = Ui.transparent(new GridLayout(1, 3, 8, 0));
        buttons.add(preview);
        buttons.add(add);
        buttons.add(download);
        JPanel south = Ui.transparent(new BorderLayout(0, 8));
        south.add(transport, BorderLayout.NORTH);
        south.add(info, BorderLayout.CENTER);
        JPanel actions = Ui.transparent(new BorderLayout(0, 6));
        actions.add(buttons, BorderLayout.NORTH);
        actions.add(message, BorderLayout.SOUTH);
        south.add(actions, BorderLayout.SOUTH);

        JPanel content = Ui.transparent(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 12, 14, 16));
        content.add(Ui.label("VIDEO DETAILS", Palette::secondaryText, "semibold"), BorderLayout.NORTH);
        content.add(monitor, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        transport.setVisible(false);

        detail.add(empty, "empty");
        detail.add(content, "video");
        cards.show(detail, "empty");
        return detail;
    }

    private void select(YouTubeVideo v) {
        releasePlayer();
        video = v;
        showTitle();
        channel.setText(v.channel().isBlank() ? " " : "Channel: " + v.channel());
        duration.setText(v.durationMs() == null ? "Duration: shown once it plays" : "Duration: " + TransportControls.format(v.durationMs()));
        preview.setText("PLAY / PREVIEW");
        add.setEnabled(true);
        message.setForeground(Palette.secondaryText());
        message.setText(" ");
        monitor.show(v, null);
        transport.setVisible(false);
        cards.show(detail, "video");
        showDownloadState();
        BackgroundTask.run("Check library", () -> media.findYouTube(v.id()), existing -> {
            if (existing.isPresent() && v == video) {
                message.setText("Already in Library → Videos as \"" + existing.get().getName() + "\".");
            }
        }, null);
        startPlayer(); // AUTOPLAY: a selected video plays at once
    }

    /** The title in VIDEO DETAILS: two lines at most, "…" after that; the whole title on hover. */
    private void showTitle() {
        if (video == null) {
            return;
        }
        int width = title.getWidth() > 0 ? title.getWidth() : 360;
        title.setText(YouTubeSearchPanel.clampLines(video.title(), title.getFontMetrics(title.getFont()), width, 2));
        title.setToolTipText(video.title());
    }

    private void togglePreview() {
        if (video == null) {
            return;
        }
        if (player == null) {
            startPlayer();
        } else {
            releasePlayer();
            monitor.show(video, null);
            preview.setText("PLAY / PREVIEW");
        }
    }

    private void startPlayer() {
        YouTubePlayer p = new YouTubePlayer();
        player = p;
        p.addFinishedListener(() -> {
            if (p == player) {
                finished(); // only the video playing now moves on
            }
        });
        p.addStateListener(() -> {
            if (p != player) {
                return;
            }
            if (p.isUpNext() && p.errorMessage() != null) {
                // YouTube's up next could not play here: the next search result instead of silence
                if (!search.selectNext()) {
                    message.setForeground(Palette.secondaryText());
                    message.setText("YouTube has no up-next video that can play here.");
                }
                return;
            }
            followNowPlaying(); // UP NEXT: the details follow the video YouTube plays now
        });
        player.play(video.path(), loop, false);
        transport.bind(player);
        transport.setVisible(true);
        monitor.show(video, player);
        preview.setText("STOP PREVIEW");
        search.setPlaying(video.id());
        revalidate();
    }

    /**
     * UP NEXT, like youtube.com: when the video ends, YouTube's own next videos follow (its Mix for that video), not
     * the search results; after that YouTube keeps moving on by itself. A video with LOOP on repeats instead (the
     * player never reports its end), so it never moves on.
     */
    private void finished() {
        if (player == null || player.isLoop() || player.isUpNext()) {
            return; // looping, or YouTube already chooses each next video
        }
        player.playUpNext();
        message.setForeground(Palette.secondaryText());
        message.setText("Up next from YouTube…");
    }

    /** The video YouTube plays now became another one (UP NEXT): VIDEO DETAILS and ADD TO VIDEO LIBRARY follow it. */
    private void followNowPlaying() {
        String id = player.nowPlayingId();
        if (id == null || id.isBlank() || video == null || id.equals(video.id())) {
            return;
        }
        String name = player.nowPlayingTitle();
        video = new YouTubeVideo(id, name == null || name.isBlank() ? "YouTube video" : name,
                player.nowPlayingChannel() == null ? "" : player.nowPlayingChannel(), null,
                "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg");
        showTitle();
        channel.setText(video.channel().isBlank() ? " " : "Channel: " + video.channel());
        duration.setText("Duration: shown once it plays");
        add.setEnabled(true);
        showDownloadState();
        message.setForeground(Palette.secondaryText());
        message.setText("Up next from YouTube.");
        monitor.show(video, player);
        search.setPlaying(id);
        YouTubeVideo v = video;
        BackgroundTask.run("Check library", () -> media.findYouTube(v.id()), existing -> {
            if (existing.isPresent() && v == video) {
                message.setText("Up next from YouTube · already in Library → Videos as \"" + existing.get().getName() + "\".");
            }
        }, null);
    }

    private void addToLibrary() {
        YouTubeVideo v = video;
        if (v == null) {
            return;
        }
        // the duration the player found, if the search did not include it
        YouTubeVideo withLength = v.durationMs() == null && player != null && player.length() > 0
                ? new YouTubeVideo(v.id(), v.title(), v.channel(), player.length(), v.thumbnailUrl()) : v;
        add.setEnabled(false);
        BackgroundTask.run("Add YouTube video", () -> media.addYouTube(withLength), item -> {
            added = item;
            message.setForeground(Palette.live());
            message.setText("✓ Added to Library → Videos (YouTube): \"" + item.getName() + "\"");
        }, err -> {
            add.setEnabled(true);
            message.setForeground(Palette.danger());
            message.setText(err.getMessage());
        });
    }

    /** DOWNLOAD, a progress line while it downloads, or ✓ DOWNLOADED when the saved copy is on this computer. */
    private void showDownloadState() {
        YouTubeVideo v = video;
        if (v == null) {
            return;
        }
        String progress = DOWNLOADING.get(v.id());
        if (progress != null) {
            download.setEnabled(false);
            download.setText(progress);
        } else if (downloader.existing(v.id()) != null) {
            download.setEnabled(true); // pressing it again only puts the saved copy back in Local (if it was removed)
            download.setText("✓ DOWNLOADED");
        } else {
            download.setEnabled(true);
            download.setText("DOWNLOAD");
        }
    }

    private void downloadVideo() {
        YouTubeVideo v = video;
        if (v == null || DOWNLOADING.putIfAbsent(v.id(), "Starting…") != null) {
            return;
        }
        showDownloadState();
        message.setForeground(Palette.secondaryText());
        message.setText("Downloading \"" + v.title() + "\"… You can keep browsing or close this window.");
        Long knownLength = v.durationMs() != null ? v.durationMs()
                : player != null && player.length() > 0 ? player.length() : null;
        BackgroundTask.run("Download YouTube video", () -> {
            Path file = downloader.existing(v.id());
            YouTubeDownloader.Result r = file != null ? new YouTubeDownloader.Result(file, null, null, null)
                    : downloader.download(v.id(), line -> {
                        DOWNLOADING.put(v.id(), line);
                        javax.swing.SwingUtilities.invokeLater(this::showDownloadState);
                    });
            Long length = r.durationMs() != null ? r.durationMs() : knownLength;
            // both at once: the YouTube link (YouTube tab, ONLINE) and the saved copy (Local tab, DOWNLOADED, offline)
            media.addYouTube(v.durationMs() == null && length != null
                    ? new YouTubeVideo(v.id(), v.title(), v.channel(), length, v.thumbnailUrl()) : v);
            return media.addDownloaded(r.file(), v.title(), v.channel(), r.width(), r.height(), length);
        }, item -> {
            DOWNLOADING.remove(v.id());
            added = item;
            showDownloadState();
            if (isDisplayable()) {
                message.setForeground(Palette.live());
                message.setText("<html>✓ Downloaded \"" + item.getName() + "\": in Videos → Local (plays offline) "
                        + "and Videos → YouTube (online).</html>");
            }
        }, err -> {
            DOWNLOADING.remove(v.id());
            showDownloadState();
            if (isDisplayable()) {
                message.setForeground(Palette.danger());
                message.setText(err.getMessage());
            }
        });
    }

    private void releasePlayer() {
        transport.bind(null);
        if (player != null) {
            loop = player.isLoop(); // the next video keeps the operator's LOOP choice
            player.release();
            player = null;
        }
        search.setPlaying(null);
    }
}
