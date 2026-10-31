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
import java.util.Optional;

/**
 * Library → Videos → Import from YouTube ("YOUTUBE VIDEO LIBRARY"): search YouTube, select a video,
 * see its VIDEO DETAILS, PLAY / PREVIEW it here, and ADD TO VIDEO LIBRARY. The library item only
 * references the YouTube video (nothing is downloaded); it plays through the embedded YouTube player.
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
    private final CardLayout cards = new CardLayout();
    private final JPanel detail = Ui.transparent(cards);
    private YouTubePlayer player;
    private YouTubeVideo video;
    private MediaItem added;

    private YouTubeVideoImportDialog(Window owner, MediaService media, AppConfig config) {
        super(owner, "YouTube Video Library", ModalityType.APPLICATION_MODAL);
        this.media = media;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                releasePlayer();
            }
        });

        YouTubeSearchPanel search = new YouTubeSearchPanel(config, "worship background video", this::select);
        search.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 12));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, search, buildDetail());
        split.setResizeWeight(0.45);
        split.setBorder(null);

        JPanel root = Ui.panel(new BorderLayout(), Palette::panelBackground);
        JPanel header = Ui.header(Ui.label("Import from YouTube", Palette::text, "h3"),
                Ui.small("Adds a YouTube video to Library → Videos as a reference. Nothing is downloaded; it plays from YouTube and needs the internet."));
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
        d.setVisible(true);
        return Optional.ofNullable(d.added);
    }

    private JComponent buildDetail() {
        JLabel empty = Ui.label("<html><center>VIDEO DETAILS<br><br>Search YouTube and select a video<br>to see it here.</center></html>",
                Palette::mutedText, null);
        empty.setHorizontalAlignment(JLabel.CENTER);

        preview.setToolTipText("Play the video here in the embedded YouTube player (Preview and Program are not touched)");
        preview.addActionListener(e -> togglePreview());
        add.setToolTipText("Add a reference to this YouTube video to Library → Videos");
        add.addActionListener(e -> addToLibrary());

        JLabel kind = Ui.label("YouTube Video", Palette::danger, "semibold");
        JPanel info = Ui.transparent(new GridLayout(0, 1, 0, 2));
        info.add(kind);
        info.add(title);
        info.add(channel);
        info.add(duration);

        JPanel buttons = Ui.transparent(new GridLayout(1, 2, 8, 0));
        buttons.add(preview);
        buttons.add(add);
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
        title.setText("<html>" + escape(v.title()) + "</html>");
        channel.setText(v.channel().isBlank() ? " " : "Channel: " + v.channel());
        duration.setText(v.durationMs() == null ? "Duration: shown once it plays" : "Duration: " + TransportControls.format(v.durationMs()));
        preview.setText("PLAY / PREVIEW");
        add.setEnabled(true);
        message.setForeground(Palette.secondaryText());
        message.setText(" ");
        monitor.show(v, null);
        transport.setVisible(false);
        cards.show(detail, "video");
        BackgroundTask.run("Check library", () -> media.findYouTube(v.id()), existing -> {
            if (existing.isPresent() && v == video) {
                message.setText("Already in Library → Videos as \"" + existing.get().getName() + "\".");
            }
        }, null);
    }

    private void togglePreview() {
        if (video == null) {
            return;
        }
        if (player == null) {
            player = new YouTubePlayer();
            player.play(video.path(), false, false);
            transport.bind(player);
            transport.setVisible(true);
            monitor.show(video, player);
            preview.setText("STOP PREVIEW");
            revalidate();
        } else {
            releasePlayer();
            monitor.show(video, null);
            preview.setText("PLAY / PREVIEW");
        }
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

    private void releasePlayer() {
        transport.bind(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
