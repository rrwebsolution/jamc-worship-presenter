package com.jamc.worship.ui.components;

import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.ui.theme.Palette;
import com.jamc.worship.youtube.YouTubeEngine;
import com.jamc.worship.youtube.YouTubeLibrary;
import com.jamc.worship.youtube.YouTubePlayer;
import com.jamc.worship.youtube.YouTubeVideo;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A 16:9 picture of a YouTube video: the embedded player's frames while it plays, else the video's
 * thumbnail, with the player's install / start progress or error written over it.
 */
public class YouTubeMonitor extends JComponent {

    private final Runnable repaint = this::repaint;
    private YouTubeVideo video;
    private YouTubePlayer player;
    private String badge;

    public YouTubeMonitor(String badge) {
        this.badge = badge;
        setPreferredSize(new Dimension(480, 270));
        setMinimumSize(new Dimension(240, 135));
    }

    public void show(YouTubeVideo video, YouTubePlayer player) {
        this.video = video;
        this.player = player;
        repaint();
    }

    public void setBadge(String badge) {
        this.badge = badge;
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        MediaFrames.addListener(repaint);
        YouTubeLibrary.addListener(repaint);
        YouTubeEngine.get().addListener(repaint);
    }

    @Override
    public void removeNotify() {
        MediaFrames.removeListener(repaint);
        YouTubeLibrary.removeListener(repaint);
        YouTubeEngine.get().removeListener(repaint);
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
        BufferedImage frame = player == null ? null : player.currentFrame();
        if (frame != null) {
            g2.setColor(Color.BLACK);
            g2.fillRect(x, y, w, h);
            double s = Math.min(w / (double) frame.getWidth(), h / (double) frame.getHeight());
            int iw = (int) Math.ceil(frame.getWidth() * s);
            int ih = (int) Math.ceil(frame.getHeight() * s);
            g2.drawImage(frame, x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, null);
        } else {
            Graphics2D t = (Graphics2D) g2.create(x, y, w, h);
            YouTubeSearchPanel.paintThumbnail(t, this, video, w, h);
            t.dispose();
        }
        String message = message();
        if (message != null) {
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRect(x, y + h - 34, w, 34);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            g2.setColor(player != null && player.hasError() ? new Color(0xFCA5A5) : Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            String m = fit(message, fm, w - 20);
            g2.drawString(m, x + (w - fm.stringWidth(m)) / 2, y + h - 17 + fm.getAscent() / 2 - 1);
        }
        if (badge != null) {
            g2.setFont(getFont().deriveFont(Font.BOLD, 9.5f));
            FontMetrics fm = g2.getFontMetrics();
            int bw = fm.stringWidth(badge) + 12;
            g2.setColor(new Color(0xDC2626));
            g2.fillRoundRect(x + 6, y + 6, bw, fm.getHeight() + 2, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString(badge, x + 12, y + 7 + fm.getAscent());
        }
        g2.setColor(Palette.border());
        g2.drawRect(x, y, w - 1, h - 1);
        g2.dispose();
    }

    /** What to tell the operator over the picture, or null. */
    private String message() {
        if (player == null || !player.hasMedia()) {
            return null;
        }
        if (player.hasError()) {
            return player.errorMessage();
        }
        YouTubeEngine engine = YouTubeEngine.get();
        if (!engine.isReady()) {
            if (engine.state() == YouTubeEngine.State.FAILED) {
                return engine.problem();
            }
            float p = engine.progress();
            return engine.status() + (p >= 0 ? "  " + Math.round(p) + "%" : "");
        }
        return player.currentFrame() == null ? "Loading the YouTube player…" : null;
    }

    private static String fit(String s, FontMetrics fm, int max) {
        String line = s.replace('\n', ' ');
        if (fm.stringWidth(line) <= max) {
            return line;
        }
        while (line.length() > 4 && fm.stringWidth(line + "…") > max) {
            line = line.substring(0, line.length() - 1);
        }
        return line + "…";
    }
}
