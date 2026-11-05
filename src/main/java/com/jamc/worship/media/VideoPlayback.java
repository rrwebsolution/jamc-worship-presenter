package com.jamc.worship.media;

import com.jamc.worship.youtube.YouTubePlayer;
import com.jamc.worship.youtube.YouTubeRef;

import java.awt.image.BufferedImage;

/**
 * A video player whose pictures are drawn by the outputs (Preview, Program, projector, stream).
 *
 * <ul>
 *   <li>{@link VideoPlayer}: local files through VLCJ / libVLC;</li>
 *   <li>{@link YouTubePlayer}: YouTube videos through the official embedded YouTube player
 *       (a YouTube URL is never handed to VLC, and its media stream is never extracted).</li>
 * </ul>
 * Paths starting with {@code youtube:} ({@link YouTubeRef}) are YouTube videos; everything else is a file.
 */
public interface VideoPlayback extends Transport {

    /** Starts playing a file, VLC MRL or {@code youtube:ID}. Never blocks. */
    void play(String path, boolean loop, boolean muted, String... options);

    /** Loads the video paused (for previews). */
    void cue(String path, boolean loop);

    /** What was last played or cued, or null. */
    String mrl();

    /** The latest picture, or null until the first frame. */
    BufferedImage currentFrame();

    boolean hasError();

    /** Jumps to a time in ms. */
    void seekTime(long ms);

    /** Sends the sound to an output device (ignored where the player cannot choose one). */
    void setOutputDevice(String deviceId);

    /** Number of audio tracks: 0 = no sound, -1 = not known. */
    default int audioTrackCount() {
        return -1;
    }

    void addFinishedListener(Runnable listener);

    void release();

    /** A new player for this path: YouTube or VLC. Throws if the needed engine cannot start. */
    static VideoPlayback create(String path) {
        // 1080p like Program's own YouTube player: a 720p picture is enlarged pixel by pixel on the Swing thread (about
        // 100 ms a frame), which left recordings and the stream about 8 pictures a second and out of time with the sound
        return YouTubeRef.is(path) ? new YouTubePlayer(1920, 1080) : new VideoPlayer();
    }

    /** Whether a player for this path can run now (VLC loaded / YouTube player component installable). */
    static boolean available(String path) {
        return YouTubeRef.is(path) ? com.jamc.worship.youtube.YouTubeEngine.get().isUsable()
                : MediaEngine.get().isAvailable();
    }
}
