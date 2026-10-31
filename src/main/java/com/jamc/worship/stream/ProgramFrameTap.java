package com.jamc.worship.stream;

import com.jamc.worship.output.OutputCanvas;
import com.jamc.worship.output.ProgramOutput;

import javax.swing.Timer;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Takes frames of the one authoritative Program picture for recording and livestreaming.
 *
 * <p>It paints an off-screen {@link OutputCanvas} on the same {@link ProgramOutput} as the Program
 * monitor and the projector, with the same code (transitions, global background, lower thirds), so
 * viewers see exactly Program. Painting happens on the Swing thread like every other output; the
 * frame bytes are copied to each sink, and encoding happens on the sinks' own threads.
 */
public final class ProgramFrameTap {

    /** Receives each frame as packed BGR bytes; must not block (copy and return). */
    public interface Sink {
        void offer(byte[] bgr);
    }

    private final ProgramOutput program;
    private final List<Sink> sinks = new CopyOnWriteArrayList<>();
    private OutputCanvas canvas;
    private BufferedImage frame;
    private Timer timer;
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
            frame = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            timer = new Timer(Math.max(1, Math.round(1000f / rate)), e -> tick());
            timer.setCoalesce(true);
            timer.start();
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

    private void tick() {
        Graphics2D g = frame.createGraphics();
        try {
            canvas.paint(g);
        } finally {
            g.dispose();
        }
        byte[] data = ((DataBufferByte) frame.getRaster().getDataBuffer()).getData();
        for (Sink s : sinks) {
            s.offer(data);
        }
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }
}
