package com.jamc.worship.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Asks VLC whether a video really plays, instead of trusting its extension: the file is opened in
 * a hidden, muted player and accepted once the first picture has been decoded. A file VLC cannot
 * open, a corrupt file, an audio-only file or a codec VLC lacks all end up as "unplayable" with a
 * friendly message. Blocks for up to a few seconds: never call it on the event thread.
 */
public final class MediaProbe {

    private static final Logger log = LoggerFactory.getLogger(MediaProbe.class);
    private static final long TIMEOUT_MS = 8_000;

    public static final String UNPLAYABLE_MESSAGE = "Unable to play this video format.\nPlease try another video file.";
    public static final String RECOMMENDED = "For the best performance and compatibility use MP4 (H.264 video, AAC audio), "
            + "1920x1080, 30 FPS. Other formats work too if VLC can play them.";

    public enum Status {
        /** VLC decoded a picture. */
        PLAYABLE,
        /** VLC could not show a picture. */
        UNPLAYABLE,
        /** VLC is not available here, so the file could not be checked. */
        UNVERIFIED
    }

    /** @param width,height size of the decoded picture (0 when unknown) */
    public record Result(Status status, int width, int height, String message) {
        public boolean isPlayable() {
            return status == Status.PLAYABLE;
        }
    }

    private MediaProbe() {
    }

    /**
     * A small still from a little way into the video for library thumbnails, or null if VLC cannot
     * show one. Blocks for up to a few seconds: call it off the event thread.
     */
    public static BufferedImage thumbnail(Path file, int width) {
        return thumbnail(file, width, 0.1f);
    }

    /** Like {@link #thumbnail(Path, int)}, at this position (0..1) of the video. */
    public static BufferedImage thumbnail(Path file, int width, float position) {
        MediaEngine engine = MediaEngine.get();
        if (!engine.isAvailable() || !Files.isRegularFile(file)) {
            return null;
        }
        VideoPlayer player = null;
        try {
            player = new VideoPlayer();
            player.play(file.toString(), false, true, ":no-audio");
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            BufferedImage frame = null;
            boolean seeked = false;
            while (System.currentTimeMillis() < deadline && !player.hasError()) {
                frame = player.currentFrame();
                if (frame != null && !seeked) {
                    player.seek(position); // the first frame is often black
                    seeked = true;
                    deadline = Math.min(deadline, System.currentTimeMillis() + 400);
                }
                Thread.sleep(40);
            }
            frame = player.currentFrame();
            if (frame == null) {
                return null;
            }
            int h = Math.max(1, width * frame.getHeight() / Math.max(1, frame.getWidth()));
            BufferedImage thumb = new BufferedImage(width, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = thumb.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(frame, 0, 0, width, h, null);
            g.dispose();
            return thumb;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException | LinkageError e) {
            log.warn("Video thumbnail failed for {}", file, e);
            return null;
        } finally {
            if (player != null) {
                player.release();
            }
        }
    }

    /**
     * Number of audio tracks in a video file: 0 = the file has no sound (not an error), -1 = could not
     * tell (VLC unavailable or the file could not be read). Blocks for up to a few seconds.
     */
    public static int audioTracks(Path file) {
        MediaEngine engine = MediaEngine.get();
        if (!engine.isAvailable() || !Files.isRegularFile(file)) {
            return -1;
        }
        uk.co.caprica.vlcj.media.Media media = null;
        try {
            media = engine.factory().media().newMedia(file.toString());
            media.parsing().parse((int) TIMEOUT_MS);
            long deadline = System.currentTimeMillis() + TIMEOUT_MS + 500;
            while (System.currentTimeMillis() < deadline) {
                var status = media.parsing().status();
                if (status != null && status != uk.co.caprica.vlcj.media.MediaParsedStatus.SKIPPED) {
                    break;
                }
                Thread.sleep(40);
            }
            if (media.parsing().status() != uk.co.caprica.vlcj.media.MediaParsedStatus.DONE) {
                return -1;
            }
            int audio = media.info().audioTracks().size();
            return audio == 0 && media.info().videoTracks().isEmpty() ? -1 : audio;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (RuntimeException | LinkageError e) {
            log.warn("Could not read the tracks of {}", file, e);
            return -1;
        } finally {
            if (media != null) {
                media.release();
            }
        }
    }

    public static Result probeVideo(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return new Result(Status.UNPLAYABLE, 0, 0, "The video file was not found:\n" + file);
            }
            if (Files.size(file) == 0) {
                return new Result(Status.UNPLAYABLE, 0, 0, "The video file is empty.\nPlease try another video file.");
            }
        } catch (IOException e) {
            return new Result(Status.UNPLAYABLE, 0, 0, "The video file could not be read.\nPlease try another video file.");
        }
        MediaEngine engine = MediaEngine.get();
        if (!engine.isAvailable()) {
            return new Result(Status.UNVERIFIED, 0, 0, engine.problem());
        }
        VideoPlayer player = null;
        try {
            player = new VideoPlayer();
            player.keepFullResolution(); // reports the file's real size
            player.play(file.toString(), false, true, ":no-audio");
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                BufferedImage frame = player.currentFrame();
                if (frame != null) {
                    log.info("Video check passed: {} ({}x{})", file.getFileName(), frame.getWidth(), frame.getHeight());
                    return new Result(Status.PLAYABLE, frame.getWidth(), frame.getHeight(), null);
                }
                if (player.hasError()) {
                    break;
                }
                Thread.sleep(40);
            }
            log.warn("Video check failed: VLC showed no picture for {}", file);
            return new Result(Status.UNPLAYABLE, 0, 0, UNPLAYABLE_MESSAGE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Status.UNPLAYABLE, 0, 0, UNPLAYABLE_MESSAGE);
        } catch (RuntimeException | LinkageError e) {
            log.warn("Video check crashed for {}", file, e);
            return new Result(Status.UNPLAYABLE, 0, 0, UNPLAYABLE_MESSAGE);
        } finally {
            if (player != null) {
                player.release();
            }
        }
    }
}
