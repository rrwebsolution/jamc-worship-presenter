package com.jamc.worship.stream;

import com.jamc.worship.media.MediaFrames;
import com.jamc.worship.output.OutputCanvas;
import com.jamc.worship.output.ProgramOutput;

import javax.swing.Timer;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Takes frames of the one authoritative Program picture for recording and livestreaming.
 *
 * <p>It paints an off-screen {@link OutputCanvas} on the same {@link ProgramOutput} as the Program
 * monitor and the projector, with the same code (transitions, global background, lower thirds), so
 * viewers see exactly Program. Painting happens on the Swing thread like every other output; the
 * frame bytes are copied to each sink, and encoding happens on the sinks' own threads.
 *
 * <p>A frame is taken as soon as the picture changes (a new video or camera frame, a transition step), not on a
 * clock of its own: a second 30 Hz clock next to the video's drifts against it and repeats and skips frames in
 * long runs, which is what makes a recording stutter. While nothing moves, a slow timer keeps frames coming.
 */
public final class ProgramFrameTap {

    /** Receives each frame as 0xRRGGBB pixels; must not block (copy and return). */
    public interface Sink {
        void offer(int[] rgb);
    }

    private final ProgramOutput program;
    private final List<Sink> sinks = new CopyOnWriteArrayList<>();
    private OutputCanvas canvas;
    private BufferedImage frame;
    private Timer timer;
    private final Runnable pictureChanged = this::pictureChanged;
    private long minGapNanos;
    private long idleGapNanos;
    private long lastTick;
    private int width;
    private int height;
    private int fps;

    public ProgramFrameTap(ProgramOutput program) {
        this.program = program;
    }

    /** Adds a sink; the tap runs at the given size and rate while it has sinks. Swing thread only. */
    public void addSink(Sink sink, int w, int h, int rate) {
        if (timer == null || w != width || h != height || rate != fps) {
            stopTimer();
            width = w;
            height = h;
            fps = rate;
            canvas = new OutputCanvas(program);
            canvas.setSize(w, h);
            // the same pixel type as video frames, so 1080p is copied, not converted pixel by pixel on the Swing thread
            frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            minGapNanos = 700_000_000L / rate; // two videos at different moments still give about the chosen rate
            idleGapNanos = 1_500_000_000L / rate; // longer than a frame of the slowest usual video (24 fps at 30)
            lastTick = 0;
            timer = new Timer(10, e -> {
                if (System.nanoTime() - lastTick >= idleGapNanos) {
                    tick();
                }
            });
            timer.setCoalesce(true);
            timer.start();
            OutputCanvas.setRecording(true);
            MediaFrames.addListener(pictureChanged);
            program.addListener(pictureChanged);
        }
        sinks.add(sink);
    }

    public void removeSink(Sink sink) {
        sinks.remove(sink);
        if (sinks.isEmpty()) {
            stopTimer();
        }
    }

    public boolean isRunning() {
        return timer != null;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int fps() {
        return fps;
    }

    private void pictureChanged() {
        if (timer != null && System.nanoTime() - lastTick >= minGapNanos) {
            tick();
        }
    }

    private void tick() {
        lastTick = System.nanoTime();
        Graphics2D g = frame.createGraphics();
        try {
            canvas.paint(g);
        } finally {
            g.dispose();
        }
        int[] data = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        for (Sink s : sinks) {
            s.offer(data);
        }
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
            timer = null;
            OutputCanvas.setRecording(false);
            MediaFrames.removeListener(pictureChanged);
            program.removeListener(pictureChanged);
        }
    }
}
