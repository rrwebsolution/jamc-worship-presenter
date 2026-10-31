package com.jamc.worship.ui.components;

import com.jamc.worship.background.BackgroundPresets;
import com.jamc.worship.background.GlobalBackgroundManager;
import com.jamc.worship.media.AudioPlayer;
import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.media.MediaHub;
import com.jamc.worship.media.VideoPlayer;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.render.Rendering;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Icons;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The one MEDIA PREVIEW monitor for the media libraries (videos, images, audio, backgrounds).
 * Selecting a library item shows it here for inspection only: Preview, Program, the projector,
 * the livestream and the scenes are untouched until the operator picks an action.
 *
 * <ul>
 *   <li>SEND TO PREVIEW puts it on the main Preview (TAKE then sends it to Program);</li>
 *   <li>ADD TO SERVICE adds it to the schedule and nothing else;</li>
 *   <li>OUTPUT AS chooses a special role, such as TAKE AS BACKGROUND, which changes only the shared
 *       global background under Preview and Program and never a scene.</li>
 * </ul>
 * The global background's own controls (pause, restart, change, off) sit underneath.
 */
public class MediaOutputPanel extends JPanel {

    /** Actions forwarded to the main window. */
    public interface Actions {
        /** Puts the item on the main PREVIEW (not Program). */
        void sendToPreview(MediaItem item);

        void playAudio(MediaItem item);

        void addToService(ServiceItem item);

        void toggleFavorite(MediaItem item);
    }

    private final MediaService media;
    private final MediaHub hub;
    private final GlobalBackgroundManager background;
    private final GlobalBackgroundPanel backgroundControls;
    private final Actions actions;
    private final JLabel title = Ui.label(" ", Palette::text, "h3");
    private final JLabel meta = Ui.secondary(" ");
    private final JLabel warning = Ui.label(" ", Palette::danger, "small");
    private final Monitor monitor = new Monitor();
    private final TransportControls transport = new TransportControls(false);
    private final JButton sendToPreview = Ui.primaryButton("SEND TO PREVIEW", "monitor");
    private final JButton addToService = Ui.button("ADD TO SERVICE", "list-plus");
    private final JButton outputAs = Ui.button("OUTPUT AS", "chevron-down");
    private final JButton takeAsBackground = Ui.button("TAKE AS BACKGROUND", "landscape");
    private final JButton favorite = Ui.button("ADD TO FAVORITES", "star");
    private final JButton playAudio = Ui.primaryButton("PLAY AS AUDIO", "play");
    /** The applicable actions, two per row so they fit the narrow panel (rebuilt per media kind). */
    private final JPanel actionRow = Ui.transparent(new java.awt.GridLayout(0, 2, 6, 6));
    private final CardLayout cards = new CardLayout();
    private final JPanel body = Ui.transparent(cards);
    private VideoPlayer previewPlayer;
    /** Inspection player for YouTube items: the embedded YouTube player (YouTube is never played by VLC). */
    private com.jamc.worship.youtube.YouTubePlayer youtubePlayer;
    private MediaItem item;
    private boolean exists;

    public MediaOutputPanel(MediaService media, MediaHub hub, GlobalBackgroundManager background,
                            GlobalBackgroundPanel backgroundControls, Actions actions) {
        super(new BorderLayout());
        this.media = media;
        this.hub = hub;
        this.background = background;
        this.backgroundControls = backgroundControls;
        this.actions = actions;
        setOpaque(false);

        sendToPreview.setToolTipText("Put this on the main PREVIEW. Program stays as it is until TAKE.");
        sendToPreview.addActionListener(e -> {
            if (item != null) actions.sendToPreview(item);
        });
        addToService.setToolTipText("Add to the service schedule (nothing goes to Preview or Program)");
        addToService.addActionListener(e -> {
            ServiceItem s = item == null ? null : MediaLibraryPanel.serviceItem(item);
            if (s != null) actions.addToService(s);
        });
        outputAs.setHorizontalTextPosition(JButton.LEFT);
        outputAs.setToolTipText("Choose a special output role for this media");
        outputAs.addActionListener(e -> showOutputAsMenu());
        takeAsBackground.setToolTipText("Make this the global background under Preview and Program. Scenes do not change.");
        takeAsBackground.addActionListener(e -> takeAsBackground());
        favorite.addActionListener(e -> {
            if (item != null) actions.toggleFavorite(item);
        });
        playAudio.setToolTipText("Play through the audio player; it keeps playing while slides change");
        playAudio.addActionListener(e -> {
            if (item != null) {
                actions.playAudio(item);
                bindAudio();
            }
        });

        JLabel badge = Ui.label("MEDIA PREVIEW", Palette::secondaryText, "semibold");
        badge.setToolTipText("Inspect library media here. This is not the main Preview.");
        JPanel heading = Ui.transparent(new BorderLayout(0, 2));
        heading.add(badge, BorderLayout.NORTH);
        heading.add(title, BorderLayout.CENTER);
        heading.add(meta, BorderLayout.SOUTH);
        heading.setBorder(BorderFactory.createCompoundBorder(Ui.line(false, false, true, false),
                BorderFactory.createEmptyBorder(10, 14, 8, 12)));


        JPanel south = Ui.transparent(new BorderLayout(0, 6));
        south.add(transport, BorderLayout.NORTH);
        south.add(warning, BorderLayout.CENTER);
        south.add(actionRow, BorderLayout.SOUTH);

        JPanel view = Ui.transparent(new BorderLayout(0, 8));
        view.setBorder(BorderFactory.createEmptyBorder(10, 14, 8, 14));
        view.add(monitor, BorderLayout.CENTER);
        view.add(south, BorderLayout.SOUTH);

        JPanel content = Ui.transparent(new BorderLayout());
        content.add(heading, BorderLayout.NORTH);
        content.add(view, BorderLayout.CENTER);

        JLabel empty = Ui.label("<html><center>MEDIA PREVIEW<br><br>Click an image, video, audio file or background<br>"
                + "in the library to inspect it here.<br>Click + in the library to import files.</center></html>", Palette::mutedText, null);
        empty.setHorizontalAlignment(JLabel.CENTER);
        body.add(content, "item");
        body.add(empty, "empty");
        add(body, BorderLayout.CENTER);

        JPanel bg = Ui.transparent(new BorderLayout());
        bg.setBorder(BorderFactory.createEmptyBorder(0, 14, 10, 14));
        bg.add(backgroundControls, BorderLayout.CENTER);
        add(bg, BorderLayout.SOUTH);

        background.addListener(this::updateBackgroundState);
        // YouTube thumbnails, ONLINE / OFFLINE checks and the player's install progress arrive later
        com.jamc.worship.youtube.YouTubeLibrary.addListener(() -> {
            updateYouTubeStatus();
            monitor.repaint();
        });
        com.jamc.worship.youtube.YouTubeEngine.get().addListener(() -> {
            updateYouTubeStatus();
            monitor.repaint();
        });
        // the audio card's position bar
        new javax.swing.Timer(500, e -> {
            if (item != null && item.getKind() == MediaKind.AUDIO && monitor.isShowing()) {
                monitor.repaint();
            }
        }).start();
        show(null);
    }

    private com.jamc.worship.media.MediaStates states;

    /** Where each file's mute / volume / loop is remembered (outside this panel, so leaving it changes nothing). */
    public void setMediaStates(com.jamc.worship.media.MediaStates states) {
        this.states = states;
    }

    private static String stateKey(MediaItem m) {
        return com.jamc.worship.media.MediaStates.mediaKey(m.getPath());
    }

    /** Shows the selected library item for inspection; nothing is sent anywhere. */
    public void show(MediaItem m) {
        boolean same = m != null && item != null && Objects.equals(m.getId(), item.getId()) && Objects.equals(m.getPath(), item.getPath());
        this.item = m;
        if (m == null) {
            stopPreview();
            cards.show(body, "empty");
            transport.bind(null);
            return;
        }
        if (m.isYouTube()) {
            showYouTube(m, same);
            return;
        }
        stopYouTube();
        boolean preset = BackgroundPresets.isPreset(m);
        Path file = preset ? null : media.resolve(m);
        exists = preset || Files.isRegularFile(file);
        MediaKind kind = m.getKind();
        boolean backgroundItem = m.getCategory() == MediaCategory.BACKGROUND;
        title.setText(m.getName());
        meta.setText(describe(m, file));
        warning.setText(!exists ? "The file is missing: " + file + ". Re-import it or reconnect the drive."
                : (kind != MediaKind.IMAGE && !preset && !hub.available() ? hub.problem() : " "));

        // only the actions that apply to this kind of media
        boolean visual = kind != MediaKind.AUDIO;
        sendToPreview.setVisible(visual);
        playAudio.setVisible(!visual);
        addToService.setVisible(!backgroundItem);
        favorite.setVisible(backgroundItem);
        takeAsBackground.setVisible(backgroundItem);
        outputAs.setVisible(visual && !backgroundItem);
        favorite.setEnabled(!preset);
        favorite.setText(m.isFavorite() ? "REMOVE FROM FAVORITES" : "ADD TO FAVORITES");
        favorite.setIcon(Icons.get(m.isFavorite() ? "star-filled" : "star", Icons.SMALL));
        actionRow.removeAll();
        for (JButton b : new JButton[]{sendToPreview, playAudio, addToService, favorite, takeAsBackground, outputAs}) {
            if (b.isVisible()) {
                actionRow.add(b);
            }
        }
        Ui.setEnabled(exists, sendToPreview, addToService, outputAs, takeAsBackground);
        playAudio.setEnabled(exists && hub.available());
        updateBackgroundState();

        if (kind == MediaKind.VIDEO && exists && hub.available()) {
            transport.setVisible(true);
            if (!same || previewPlayer == null || !previewPlayer.hasMedia()) {
                try {
                    if (previewPlayer == null) {
                        previewPlayer = new VideoPlayer();
                        previewPlayer.addStateListener(this::recordDuration);
                    }
                    // this video's own mute / volume / loop from last time, set before it loads
                    var state = states == null ? null : states.get(stateKey(m), new com.jamc.worship.media.MediaStates.State(false, 100, false));
                    if (state != null) {
                        previewPlayer.setVolume(state.volume());
                        previewPlayer.setMuted(state.muted());
                    }
                    previewPlayer.cue(file.toString(), state != null && state.loop());
                } catch (RuntimeException e) {
                    Dialogs.showError(this, "The video could not be opened.", e);
                }
            }
            transport.bind(states == null ? previewPlayer : states.remember(previewPlayer, stateKey(m)));
        } else if (kind == MediaKind.AUDIO) {
            stopPreview();
            transport.setVisible(true);
            bindAudio();
        } else {
            stopPreview();
            transport.setVisible(false);
            transport.bind(null);
        }
        monitor.repaint();
        cards.show(body, "item");
        revalidate();
    }

    /**
     * A YouTube Video Library item: it opens here in the embedded YouTube player (paused until PLAY).
     * SEND TO PREVIEW → TAKE puts it on Program like any video; it is never a background.
     */
    private void showYouTube(MediaItem m, boolean same) {
        if (previewPlayer != null) {
            previewPlayer.stop(); // the local-file inspection player
        }
        exists = true; // a reference: there is no file to lose
        title.setText(m.getName());
        updateYouTubeStatus();
        var engine = com.jamc.worship.youtube.YouTubeEngine.get();

        sendToPreview.setVisible(true);
        playAudio.setVisible(false);
        addToService.setVisible(true);
        favorite.setVisible(false);
        takeAsBackground.setVisible(false);
        outputAs.setVisible(true);
        actionRow.removeAll();
        actionRow.add(sendToPreview);
        actionRow.add(addToService);
        actionRow.add(outputAs);
        Ui.setEnabled(engine.isUsable(), sendToPreview, outputAs);
        addToService.setEnabled(true);

        transport.setVisible(true);
        if (!same || youtubePlayer == null || !m.getPath().equals(youtubePlayer.mrl())) {
            if (youtubePlayer == null) {
                youtubePlayer = new com.jamc.worship.youtube.YouTubePlayer();
                youtubePlayer.addStateListener(this::recordDuration);
                youtubePlayer.addStateListener(monitor::repaint);
            }
            var state = states == null ? null : states.get(stateKey(m), new com.jamc.worship.media.MediaStates.State(false, 100, false));
            if (state != null) {
                youtubePlayer.setVolume(state.volume());
                youtubePlayer.setMuted(state.muted());
            }
            youtubePlayer.cue(m.getPath(), state != null && state.loop());
        }
        transport.bind(states == null ? youtubePlayer : states.remember(youtubePlayer, stateKey(m)));
        monitor.repaint();
        cards.show(body, "item");
        revalidate();
    }

    /** "YouTube video · channel · duration · ONLINE" and any warning; again whenever a status check or the engine changes. */
    private void updateYouTubeStatus() {
        MediaItem m = item;
        if (m == null || !m.isYouTube()) {
            return;
        }
        var availability = com.jamc.worship.youtube.YouTubeLibrary.availability(m.getYoutubeVideoId());
        StringBuilder s = new StringBuilder("YouTube video");
        if (m.getChannelName() != null && !m.getChannelName().isBlank()) {
            s.append("  ·  ").append(m.getChannelName());
        }
        if (m.getDurationMs() != null) {
            s.append("  ·  ").append(TransportControls.format(m.getDurationMs()));
        }
        s.append("  ·  ").append(availability.label()).append("  ·  plays from YouTube (needs internet)");
        meta.setText(s.toString());
        var engine = com.jamc.worship.youtube.YouTubeEngine.get();
        warning.setText(!engine.isUsable() ? engine.problem()
                : availability == com.jamc.worship.youtube.YouTubeClient.Availability.OFFLINE
                ? "OFFLINE: YouTube cannot be reached. Local videos and saved lyrics still work."
                : availability == com.jamc.worship.youtube.YouTubeClient.Availability.UNAVAILABLE
                ? "UNAVAILABLE: YouTube no longer plays this video (removed, private or not embeddable)." : " ");
        Ui.setEnabled(engine.isUsable(), sendToPreview, outputAs);
    }

    private void stopYouTube() {
        if (youtubePlayer != null && youtubePlayer.hasMedia()) {
            youtubePlayer.stop();
        }
    }

    /** Stops the inspection player (e.g. when leaving the media libraries). The global background is not affected. */
    public void stopPreview() {
        if (previewPlayer != null) {
            previewPlayer.stop();
        }
        stopYouTube();
    }

    private static String describe(MediaItem m, Path file) {
        if (BackgroundPresets.isPreset(m)) {
            return BackgroundPresets.describe(m) + "  ·  built-in background";
        }
        StringBuilder s = new StringBuilder(m.getKind().name().charAt(0) + m.getKind().name().substring(1).toLowerCase());
        s.append("  ·  ").append(m.getCategory());
        if (m.getWidth() != null) {
            s.append("  ·  ").append(m.getWidth()).append("×").append(m.getHeight());
        }
        if (m.getDurationMs() != null) {
            s.append("  ·  ").append(TransportControls.format(m.getDurationMs()));
        }
        if (m.getFileSize() != null) {
            long mb = m.getFileSize() / 1024 / 1024;
            s.append("  ·  ").append(mb > 0 ? mb + " MB" : m.getFileSize() / 1024 + " KB");
        }
        s.append("  ·  ").append(m.isCopied() ? "stored in library" : "linked: " + file);
        return s.toString();
    }

    /** OUTPUT AS ▼: only the roles this kind of media supports. */
    private void showOutputAsMenu() {
        MediaItem m = item;
        if (m == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        boolean video = m.getKind() == MediaKind.VIDEO;
        JMenuItem fullscreen = new JMenuItem(video ? "Fullscreen Video" : "Fullscreen Image", Icons.get(video ? "video" : "image", Icons.SMALL));
        fullscreen.setToolTipText("Fills the whole screen; goes to the main Preview first, TAKE puts it on Program");
        fullscreen.addActionListener(e -> actions.sendToPreview(m));
        JMenuItem bg = new JMenuItem("TAKE AS BACKGROUND", Icons.get("landscape", Icons.SMALL));
        bg.setToolTipText(m.isYouTube() ? "YouTube videos play as video content only (fullscreen or in a scene's Video source)"
                : "Shared background under Preview and Program; scenes and lyrics stay as they are");
        bg.setEnabled(!m.isYouTube() && (video ? hub.available() : true));
        bg.addActionListener(e -> takeAsBackground());
        menu.add(fullscreen);
        menu.add(bg);
        menu.show(outputAs, 0, outputAs.getHeight());
    }

    private void takeAsBackground() {
        MediaItem m = item;
        if (m == null || m.getKind() == MediaKind.AUDIO || m.isYouTube()) {
            return;
        }
        backgroundControls.takeAsBackground(m.getPath(), m.getName());
    }

    /** The audio player's controls, when it is playing this file. */
    private void bindAudio() {
        MediaItem m = item;
        if (m == null || !hub.available()) {
            transport.bind(null);
            return;
        }
        AudioPlayer audio = hub.audio();
        boolean ours = audio.hasMedia() && Objects.equals(audio.title(), m.getName());
        transport.bind(!ours ? null : states == null ? audio : states.remember(audio, stateKey(m)));
        monitor.repaint();
    }

    private void updateBackgroundState() {
        MediaItem m = item;
        boolean current = m != null && Objects.equals(m.getPath(), background.path()) && background.isActive();
        takeAsBackground.setText(current ? "✓ ACTIVE BACKGROUND" : "TAKE AS BACKGROUND");
        monitor.repaint();
    }

    private void recordDuration() {
        MediaItem m = item;
        com.jamc.worship.media.VideoPlayback p = m != null && m.isYouTube() ? youtubePlayer : previewPlayer;
        if (m != null && m.getId() != null && m.getId() > 0 && p != null && m.getDurationMs() == null
                && p.length() > 0 && (!m.isYouTube() || m.getPath().equals(p.mrl()))) {
            long len = p.length();
            m.setDurationMs(len);
            com.jamc.worship.ui.BackgroundTask.run("Store duration", () -> {
                media.recordDuration(m.getId(), len);
                return true;
            }, null, null);
        }
    }

    /** The single 16:9 inspection monitor: image, video frame, color / gradient swatch or an audio card. */
    private final class Monitor extends JComponent {
        private final Runnable frames = this::repaint;

        @Override
        public void addNotify() {
            super.addNotify();
            MediaFrames.addListener(frames);
            Rendering.renderer().images().addChangeListener(frames);
        }

        @Override
        public void removeNotify() {
            MediaFrames.removeListener(frames);
            Rendering.renderer().images().removeChangeListener(frames);
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = (int) Math.round(w * 9 / 16.0);
            if (h > getHeight()) {
                h = getHeight();
                w = (int) Math.round(h * 16 / 9.0);
            }
            int x = (getWidth() - w) / 2;
            int y = (getHeight() - h) / 2;
            g2.setColor(Color.BLACK);
            g2.fillRect(x, y, w, h);
            MediaItem m = item;
            BufferedImage img = null;
            boolean fill = false;
            if (m != null && BackgroundPresets.isPreset(m)) {
                img = GlobalBackgroundManager.solidImage(m.getPath());
                fill = true;
            } else if (m != null && m.getKind() == MediaKind.IMAGE) {
                img = Rendering.renderer().images().get(m.getPath());
            } else if (m != null && m.isYouTube()) {
                // the embedded YouTube player's picture; its thumbnail until the first frame
                img = youtubePlayer == null || !m.getPath().equals(youtubePlayer.mrl()) ? null : youtubePlayer.currentFrame();
                if (img == null) {
                    img = com.jamc.worship.youtube.YouTubeLibrary.thumbnail(m.getYoutubeVideoId(), m.getThumbnailUrl());
                    fill = img != null; // YouTube thumbnails carry their own black bars
                }
            } else if (m != null && m.getKind() == MediaKind.VIDEO) {
                img = previewPlayer == null ? null : previewPlayer.currentFrame();
                if (img == null && exists) {
                    img = VideoThumbnails.get(media.resolve(m));
                }
            }
            if (img != null) {
                double s = fill ? Math.max(w / (double) img.getWidth(), h / (double) img.getHeight())
                        : Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
                int iw = (int) Math.ceil(img.getWidth() * s);
                int ih = (int) Math.ceil(img.getHeight() * s);
                Graphics2D clip = (Graphics2D) g2.create();
                clip.clipRect(x, y, w, h);
                clip.drawImage(img, x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, null);
                clip.dispose();
            } else if (m != null && m.getKind() == MediaKind.AUDIO) {
                paintAudioCard(g2, m, x, y, w, h);
            } else if (m != null) {
                g2.setColor(Palette.mutedText());
                g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
                String msg = !exists ? "File missing" : hub.available() || m.getKind() == MediaKind.IMAGE ? "Loading…" : "Video preview needs VLC";
                g2.drawString(msg, x + (w - g2.getFontMetrics().stringWidth(msg)) / 2, y + h / 2);
            }
            paintBadge(g2, x, y, m);
            if (m != null && m.isYouTube()) {
                paintYouTubeStatus(g2, m, x, y, w, h);
            }
            g2.setColor(Palette.border());
            g2.drawRect(x, y, w - 1, h - 1);
            g2.dispose();
        }

        /** "YOUTUBE" and ONLINE / OFFLINE on the picture, plus the player's progress or error underneath. */
        private void paintYouTubeStatus(Graphics2D g2, MediaItem m, int x, int y, int w, int h) {
            Graphics2D b = (Graphics2D) g2.create(x, y, w, h);
            MediaLibraryPanel.paintYouTubeBadges(b, w, h, com.jamc.worship.youtube.YouTubeLibrary.availability(m.getYoutubeVideoId()));
            b.dispose();
            var engine = com.jamc.worship.youtube.YouTubeEngine.get();
            var p = youtubePlayer;
            String msg = null;
            if (p != null && p.hasError()) {
                msg = p.errorMessage();
            } else if (!engine.isReady() && engine.state() != com.jamc.worship.youtube.YouTubeEngine.State.NOT_STARTED) {
                msg = engine.state() == com.jamc.worship.youtube.YouTubeEngine.State.FAILED ? engine.problem()
                        : engine.status() + (engine.progress() >= 0 ? "  " + Math.round(engine.progress()) + "%" : "");
            }
            if (msg != null) {
                g2.setFont(getFont().deriveFont(Font.PLAIN, 11.5f));
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(new Color(0, 0, 0, 175));
                g2.fillRect(x, y + h - fm.getHeight() - 22, w, fm.getHeight() + 8);
                g2.setColor(p != null && p.hasError() ? new Color(0xFCA5A5) : Color.WHITE);
                String line = msg.replace('\n', ' ');
                if (fm.stringWidth(line) > w - 16) {
                    while (line.length() > 4 && fm.stringWidth(line + "…") > w - 16) {
                        line = line.substring(0, line.length() - 1);
                    }
                    line += "…";
                }
                g2.drawString(line, x + 8, y + h - 18 - fm.getDescent());
            }
        }

        private void paintAudioCard(Graphics2D g2, MediaItem m, int x, int y, int w, int h) {
            g2.setColor(new Color(0x0B1220));
            g2.fillRect(x, y, w, h);
            g2.setColor(Palette.secondaryText());
            g2.setFont(getFont().deriveFont(Font.PLAIN, Math.max(24f, h / 5f)));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString("♪", x + (w - fm.stringWidth("♪")) / 2, y + h / 2 - fm.getDescent());
            g2.setColor(Color.WHITE);
            g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(13f, h / 14f)));
            fm = g2.getFontMetrics();
            String name = m.getName();
            g2.drawString(name, x + (w - fm.stringWidth(name)) / 2, y + h / 2 + fm.getAscent() + 6);
            AudioPlayer audio = hub.available() ? hub.audio() : null;
            boolean ours = audio != null && audio.hasMedia() && Objects.equals(audio.title(), m.getName());
            int barY = y + h * 3 / 4;
            int barX = x + w / 8;
            int barW = w * 3 / 4;
            g2.setColor(Palette.withAlpha(Color.WHITE, 50));
            g2.fillRoundRect(barX, barY, barW, 4, 4, 4);
            long len = ours ? audio.length() : m.getDurationMs() == null ? -1 : m.getDurationMs();
            long pos = ours ? audio.time() : 0;
            if (ours && len > 0) {
                g2.setColor(Palette.accent());
                g2.fillRoundRect(barX, barY, (int) (barW * Math.min(1.0, pos / (double) len)), 4, 4, 4);
            }
            g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            g2.setColor(Palette.secondaryText());
            fm = g2.getFontMetrics();
            g2.drawString(TransportControls.format(pos), barX, barY + 6 + fm.getAscent());
            String total = len > 0 ? TransportControls.format(len) : "–:–";
            g2.drawString(total, barX + barW - fm.stringWidth(total), barY + 6 + fm.getAscent());
        }

        /** "MEDIA PREVIEW" (never mistaken for the main Preview), plus "BACKGROUND ACTIVE" when this is the live background. */
        private void paintBadge(Graphics2D g2, int x, int y, MediaItem m) {
            g2.setFont(getFont().deriveFont(Font.BOLD, 9.5f));
            FontMetrics fm = g2.getFontMetrics();
            String text = "MEDIA PREVIEW";
            int bw = fm.stringWidth(text) + 12;
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(x + 6, y + 6, bw, fm.getHeight() + 2, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString(text, x + 12, y + 7 + fm.getAscent());
            if (m != null && Objects.equals(m.getPath(), background.path()) && background.isActive()) {
                String bg = "● BACKGROUND ACTIVE";
                int w2 = fm.stringWidth(bg) + 12;
                g2.setColor(Palette.live());
                g2.fillRoundRect(x + 12 + bw, y + 6, w2, fm.getHeight() + 2, 10, 10);
                g2.setColor(Color.WHITE);
                g2.drawString(bg, x + 18 + bw, y + 7 + fm.getAscent());
            }
        }
    }
}
