package com.jamc.worship.media;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A timeline without media, for Manual Tap Sync: it runs like a player (play, pause, stop, seek) so lyrics
 * can be timed against a live band or a count-in, with nothing to play.
 */
public final class Stopwatch implements Transport {

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long base;
    private long startedAt = -1;
    private long length;

    /** @param lengthMs how long the timeline is (the seek bar's range) */
    public Stopwatch(long lengthMs) {
        this.length = Math.max(60_000, lengthMs);
    }

    @Override
    public void playOrResume() {
        if (startedAt < 0) {
            startedAt = System.currentTimeMillis();
            fire();
        }
    }

    @Override
    public void pause() {
        if (startedAt >= 0) {
            base = time();
            startedAt = -1;
            fire();
        }
    }

    @Override
    public void stop() {
        startedAt = -1;
        base = 0;
        fire();
    }

    @Override
    public void seek(float position) {
        seekTime((long) (Math.clamp(position, 0f, 1f) * length));
    }

    public void seekTime(long ms) {
        base = Math.max(0, ms);
        if (startedAt >= 0) {
            startedAt = System.currentTimeMillis();
        }
        fire();
    }

    @Override public void setVolume(int volume) { }
    @Override public int volume() { return 100; }
    @Override public void setMuted(boolean muted) { }
    @Override public boolean isMuted() { return false; }
    @Override public void setLoop(boolean loop) { }
    @Override public boolean isLoop() { return false; }

    @Override
    public boolean isPlaying() {
        return startedAt >= 0;
    }

    @Override
    public long time() {
        long t = startedAt < 0 ? base : base + System.currentTimeMillis() - startedAt;
        if (t > length - 30_000) {
            length = t + 120_000; // the timeline grows while it runs
        }
        return t;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public boolean hasMedia() {
        return true;
    }

    @Override
    public void addStateListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
    }
}
