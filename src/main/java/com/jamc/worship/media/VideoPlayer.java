package com.jamc.worship.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A VLC player that decodes video into an in-memory image instead of a window.
 *
 * <p>Every output surface (projector, Program monitor, OBS window) paints {@link #currentFrame()},
 * so one decode feeds all of them and they stay frame-accurate with each other. Frames are triple
 * buffered: VLC writes the back image while painters read the front one, and the image that was front
 * a moment ago rests one frame before VLC writes into it again. With two images, VLC wrote the next
 * frame into the image a painter had just picked up, and cameras showed torn, mixed pictures.
 */
public final class VideoPlayer implements VideoPlayback {

    private static final Logger log = LoggerFactory.getLogger(VideoPlayer.class);

    private final EmbeddedMediaPlayer player;
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> finishedListeners = new CopyOnWriteArrayList<>();
    private volatile BufferedImage front;
    private BufferedImage back;
    /** The previous front: not written until the next frame is done. */
    private BufferedImage spare;
    /** For the smoothness log: when the last frame arrived, and the longest wait between two frames since asked. */
    private volatile long lastFrameNanos;
    private volatile long maxGapNanos;
    private int[] backPixels;
    /** VLC's buffer can be taller than the picture: a 1920x1080 video may arrive as 1920x1090, with padding below. */
    private int bufferWidth;
    private int bufferHeight;
    /** The real picture inside the buffer; null until VLC has told it. */
    private volatile Dimension visible;
    /** The media {@link #visible} was measured for. */
    private String visibleFor;
    /** False (the default): pictures bigger than 1920x1080 are scaled down by VLC; true keeps the source size. */
    private volatile boolean fullResolution;
    /** Every output is laid out in a 1920x1080 frame; bigger pictures only cost copying and scaling time. */
    static final int MAX_WIDTH = 1920;
    static final int MAX_HEIGHT = 1080;
    private volatile String mrl;
    private volatile boolean loop;
    private volatile boolean released;
    private volatile boolean errored;
    /** Frames received so far (a camera that stops counting has stalled or been unplugged). */
    private volatile long frameCount;
    /** Audio settings re-applied when playback starts (VLC can drop values set before the media opens). */
    private volatile String outputDevice;
    /** The device last routed to (re-routing to the same device would restart the sound). */
    private volatile String appliedDevice;
    private volatile int wantVolume = -1;
    private volatile Boolean wantMute;
    private final Runnable releaser = this::release;

    public VideoPlayer() {
        MediaEngine engine = MediaEngine.get();
        this.player = engine.newVideoPlayer();
        CallbackVideoSurface surface = engine.factory().videoSurfaces().newVideoSurface(new Formats(), new Renderer(), true);
        player.videoSurface().set(surface);
        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                errored = false;
                // never call back into libVLC on its own event thread
                mediaPlayer.submit(VideoPlayer.this::applyAudio);
                fireState();
            }

            @Override
            public void paused(MediaPlayer mediaPlayer) {
                fireState();
            }

            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                fireState();
            }

            @Override
            public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
                fireState();
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                SwingUtilities.invokeLater(() -> finishedListeners.forEach(Runnable::run));
                fireState();
            }

            @Override
            public void error(MediaPlayer mediaPlayer) {
                errored = true;
                log.warn("VLC could not play {}", mrl);
                fireState();
            }
        });
        engine.onShutdown(releaser);
    }

    /** Starts playing a file path or VLC MRL (e.g. {@code dshow://}). Never blocks. */
    public void play(String mrlOrPath, boolean loop, boolean muted, String... options) {
        if (released) {
            return;
        }
        this.mrl = mrlOrPath;
        this.loop = loop;
        player.controls().setRepeat(loop);
        wantMute = muted;
        player.audio().setMute(muted);
        // a saved Pixabay address may have a lighter version to open; mrl() stays the stored address
        if (!player.media().play(OnlineMedia.playable(mrlOrPath), options)) {
            errored = true;
            log.warn("Could not open {}", mrlOrPath);
        }
        fireState();
    }

    /** Loads media paused on its first frame (for previews). */
    public void cue(String path, boolean loop) {
        if (released) {
            return;
        }
        this.mrl = path;
        this.loop = loop;
        player.controls().setRepeat(loop);
        player.media().startPaused(OnlineMedia.playable(path));
        fireState();
    }

    public String mrl() {
        return mrl;
    }

    public BufferedImage currentFrame() {
        return front;
    }

    /**
     * The picture to keep from a VLC buffer. Without the padding, a frame the size of the output is copied in
     * under a millisecond; with it, every frame is resampled (over 60 ms for 1080p) and slightly squeezed.
     * Null while VLC does not know the size yet; the whole buffer when {@code video} is not simply a smaller
     * picture in it (e.g. a rotated video).
     */
    static Dimension visibleSize(Dimension video, int bufferWidth, int bufferHeight) {
        if (video == null || video.width <= 0 || video.height <= 0) {
            return null;
        }
        boolean padded = video.width <= bufferWidth && video.height <= bufferHeight
                && video.width >= bufferWidth * 0.8 && video.height >= bufferHeight * 0.8;
        return padded ? new Dimension(video) : new Dimension(bufferWidth, bufferHeight);
    }

    /**
     * The frame buffer VLC is asked for. A source bigger than 1920x1080 (a 2.7K or 4K file, e.g. a Pixabay "medium" of
     * 2730x1440 at 60 fps) is scaled down by VLC, keeping its shape: copying and drawing such frames filled the Swing
     * thread, and Program stuttered. With {@code full} (video checks that report the file's real size) nothing is scaled.
     */
    static Dimension bufferSize(int sourceWidth, int sourceHeight, boolean full) {
        int w = Math.max(2, sourceWidth);
        int h = Math.max(2, sourceHeight);
        // a 1080p buffer is often a little bigger (1920x1088, 1920x1090): those padding rows are cut off, never scaled
        if (!full && (w > MAX_WIDTH + 16 || h > MAX_HEIGHT + 16)) {
            double s = Math.min(MAX_WIDTH / (double) w, MAX_HEIGHT / (double) h);
            w = Math.max(2, (int) Math.round(w * s) & ~1);
            h = Math.max(2, (int) Math.round(h * s) & ~1);
        }
        return new Dimension(w, h);
    }

    /** Keeps the source's own size (no scaling down), for checks that report a file's resolution. Call before play. */
    public void keepFullResolution() {
        fullResolution = true;
    }

    public boolean hasError() {
        return errored;
    }

    /** The longest time between two frames (ms) since the last call; for the smoothness log. */
    public long takeMaxGapMillis() {
        long gap = maxGapNanos;
        maxGapNanos = 0;
        return gap / 1_000_000;
    }

    /** How many frames have arrived since playback started. */
    public long frameCount() {
        return frameCount;
    }

    @Override
    public void playOrResume() {
        if (mrl == null || released) {
            return;
        }
        if (player.status().length() > 0 && !player.status().isPlaying()) {
            player.controls().play();
        } else if (!player.status().isPlaying()) {
            play(mrl, loop, player.audio().isMute());
        }
    }

    @Override
    public void pause() {
        if (!released) {
            player.controls().setPause(true);
        }
    }

    @Override
    public void stop() {
        if (!released) {
            player.controls().stop();
            front = null;
            MediaFrames.frameArrived();
        }
    }

    @Override
    public void seek(float position) {
        if (!released) {
            player.controls().setPosition(Math.clamp(position, 0f, 1f));
        }
    }

    @Override
    public void setVolume(int volume) {
        wantVolume = Math.clamp(volume, 0, 200);
        if (!released) {
            player.audio().setVolume(wantVolume);
        }
    }

    /**
     * Sends this player's sound to an output device (a VLC device id; null or "" = the Windows default).
     * Takes effect now and again whenever playback (re)starts.
     */
    public void setOutputDevice(String deviceId) {
        outputDevice = deviceId == null ? "" : deviceId;
        if (!released) {
            applyDevice();
        }
    }

    /** Number of audio tracks: 0 = the file has no sound, -1 = not known yet (still opening). */
    public int audioTrackCount() {
        if (released || mrl == null || front == null) {
            return -1;
        }
        try {
            // VLC lists a "Disable" entry (id -1) next to the real tracks
            return (int) player.audio().trackDescriptions().stream().filter(t -> t.id() != -1).count();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * VLC's audio counters for the playing file: {decoded audio blocks, buffers played on the output device,
     * buffers lost}. Played staying at 0 while decoded grows means the sound is not reaching any speaker.
     */
    public int[] audioStats() {
        if (released || mrl == null) {
            return new int[]{0, 0, 0};
        }
        try {
            var s = player.media().info().statistics();
            return s == null ? new int[]{0, 0, 0} : new int[]{s.decodedAudio(), s.audioBuffersPlayed(), s.audioBuffersLost()};
        } catch (RuntimeException e) {
            return new int[]{0, 0, 0};
        }
    }

    /** Jumps to a time in ms (e.g. 0 to start a taken video from the beginning). */
    public void seekTime(long ms) {
        if (!released) {
            player.controls().setTime(Math.max(0, ms));
        }
    }

    /**
     * Routes the sound to {@link #outputDevice} only when it is not already there: setting the device
     * restarts VLC's audio output even for the same device, which is heard as the sound dropping out and
     * coming back (at the start of a video, and on every Program change).
     */
    private void applyDevice() {
        String d = outputDevice;
        if (d == null) {
            return;
        }
        try {
            if (d.equals(appliedDevice) && (d.isEmpty() || d.equals(player.audio().outputDevice()))) {
                return;
            }
            player.audio().setOutputDevice(null, d);
            appliedDevice = d;
        } catch (RuntimeException e) {
            log.warn("Could not route audio to device {}", d, e);
        }
    }

    private void applyAudio() {
        if (released) {
            return;
        }
        applyDevice();
        if (wantVolume >= 0) {
            player.audio().setVolume(wantVolume);
        }
        if (wantMute != null) {
            player.audio().setMute(wantMute);
        }
    }

    /**
     * Sets volume and mute again on the running sound output; with {@code reRoute} the output device too (VLC then
     * reopens the sound output). For sound that was lost, e.g. after a device switch while playing.
     */
    public void reapplyAudio(boolean reRoute) {
        if (released) {
            return;
        }
        if (reRoute) {
            appliedDevice = null;
        }
        applyAudio();
    }

    /** What VLC itself says about this player's mute (the app's own wish is {@link #isMuted()}). */
    public boolean outputMuted() {
        try {
            return !released && player.audio().isMute();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public int volume() {
        // what this app set (and applies when playback starts); VLC reports 0 before its sound output opens
        return released ? 0 : wantVolume >= 0 ? wantVolume : Math.max(0, player.audio().volume());
    }

    @Override
    public void setMuted(boolean muted) {
        wantMute = muted;
        if (!released) {
            player.audio().setMute(muted);
        }
    }

    @Override
    public boolean isMuted() {
        return !released && (wantMute != null ? wantMute : player.audio().isMute());
    }

    @Override
    public void setLoop(boolean loop) {
        this.loop = loop;
        if (!released) {
            player.controls().setRepeat(loop);
        }
    }

    @Override
    public boolean isLoop() {
        return loop;
    }

    @Override
    public boolean isPlaying() {
        return !released && player.status().isPlaying();
    }

    @Override
    public long time() {
        return released ? 0 : Math.max(0, player.status().time());
    }

    @Override
    public long length() {
        return released ? 0 : Math.max(0, player.status().length());
    }

    @Override
    public boolean hasMedia() {
        return mrl != null;
    }

    @Override
    public void addStateListener(Runnable listener) {
        stateListeners.add(listener);
    }

    public void addFinishedListener(Runnable listener) {
        finishedListeners.add(listener);
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        try {
            player.controls().stop();
            player.release();
        } catch (Throwable e) {
            log.debug("Error releasing video player", e);
        }
        MediaEngine.get().forget(releaser);
        front = null;
        back = null;
        spare = null;
    }

    private void fireState() {
        SwingUtilities.invokeLater(() -> stateListeners.forEach(Runnable::run));
    }

    private final class Formats implements BufferFormatCallback {
        @Override
        public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
            Dimension size = bufferSize(sourceWidth, sourceHeight, fullResolution);
            int w = size.width;
            int h = size.height;
            synchronized (VideoPlayer.this) {
                // a looping video asks again for the same size at every restart: keep the known picture size, or the
                // first frames after each loop show the padding rows and the picture jumps (seen as a blink)
                boolean same = w == bufferWidth && h == bufferHeight && java.util.Objects.equals(mrl, visibleFor);
                bufferWidth = w;
                bufferHeight = h;
                visibleFor = mrl;
                if (!same) {
                    visible = null;
                }
                spare = null;
                back = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                backPixels = ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
            }
            return new RV32BufferFormat(w, h);
        }

        @Override
        public void allocatedBuffers(ByteBuffer[] buffers) {
            // VLC owns the native buffers; frames are copied out in display()
        }
    }

    private final class Renderer implements RenderCallback {
        @Override
        public void display(MediaPlayer mediaPlayer, ByteBuffer[] nativeBuffers, BufferFormat bufferFormat) {
            synchronized (VideoPlayer.this) {
                if (back == null || nativeBuffers.length == 0) {
                    return;
                }
                Dimension picture = visible;
                if (picture == null) {
                    if (frameCount % 15 == 0) { // never call back into libVLC on its own thread
                        int bw = bufferWidth;
                        int bh = bufferHeight;
                        mediaPlayer.submit(() -> {
                            Dimension size = visibleSize(mediaPlayer.video().videoDimension(), bw, bh);
                            synchronized (VideoPlayer.this) {
                                if (bw == bufferWidth && bh == bufferHeight) {
                                    visible = size;
                                }
                            }
                        });
                    }
                    picture = new Dimension(bufferWidth, bufferHeight);
                }
                if (back.getWidth() != picture.width || back.getHeight() != picture.height) {
                    back = new BufferedImage(picture.width, picture.height, BufferedImage.TYPE_INT_RGB);
                    backPixels = ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
                }
                // RV32 is BGRA in memory; read as little-endian ints it is 0xAARRGGBB, which TYPE_INT_RGB expects.
                IntBuffer pixels = nativeBuffers[0].order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                if (picture.width == bufferWidth) {
                    pixels.get(backPixels, 0, Math.min(backPixels.length, pixels.remaining()));
                } else {
                    for (int y = 0; y < picture.height; y++) {
                        pixels.position(y * bufferWidth);
                        pixels.get(backPixels, y * picture.width, picture.width);
                    }
                }
                BufferedImage done = back;
                BufferedImage previous = front;
                front = done;
                // the next frame goes into the image that left the screen one frame ago, never the one just replaced
                back = sameSize(spare, done) ? spare : new BufferedImage(done.getWidth(), done.getHeight(), BufferedImage.TYPE_INT_RGB);
                spare = sameSize(previous, done) ? previous : null;
                backPixels = ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
                frameCount++;
                long now = System.nanoTime();
                if (lastFrameNanos != 0) {
                    maxGapNanos = Math.max(maxGapNanos, now - lastFrameNanos);
                }
                lastFrameNanos = now;
            }
            MediaFrames.frameArrived();
        }
    }

    private static boolean sameSize(BufferedImage a, BufferedImage b) {
        return a != null && a != b && a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight();
    }
}
