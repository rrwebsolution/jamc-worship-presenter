package com.jamc.worship.media;

import com.jamc.worship.config.AppConfig;

import javax.swing.Timer;

/**
 * The operator's MUTE, VOLUME and LOOP for each piece of media, kept outside the panels that show the
 * controls (and across restarts, in settings.properties). Opening a video or audio file again, after
 * visiting other workspaces or other files, gives it back exactly the state the operator left it in,
 * instead of the defaults.
 *
 * <p>Keys: {@code media:<stored path>} for library media (Media Preview, library audio, a library video
 * on Program) and {@code scene:<scene id>:<source>} for a scene's video / audio source on Program.
 */
public final class MediaStates {

    /** One file's or source's sound and loop. */
    public record State(boolean muted, int volume, boolean loop) {
        public State withMuted(boolean m) { return new State(m, volume, loop); }
        public State withVolume(int v) { return new State(muted, v, loop); }
        public State withLoop(boolean l) { return new State(muted, volume, l); }
    }

    private static final String PREFIX = "media.state.";

    private final AppConfig config;
    /** Volume sliders send many changes: the settings file is written once they settle. */
    private final Timer saveLater;

    public MediaStates(AppConfig config) {
        this.config = config;
        this.saveLater = new Timer(800, e -> config.save());
        saveLater.setRepeats(false);
    }

    public static String mediaKey(String storedPath) {
        return "media:" + storedPath;
    }

    /** The saved state, or {@code defaults} when the operator never changed this media. */
    public State get(String key, State defaults) {
        String v = config.get(PREFIX + key, null);
        if (v == null) {
            return defaults;
        }
        String[] p = v.split(",");
        try {
            return new State("1".equals(p[0]), Math.clamp(Integer.parseInt(p[1]), 0, 200), p.length > 2 && "1".equals(p[2]));
        } catch (RuntimeException e) {
            return defaults;
        }
    }

    public boolean has(String key) {
        return config.get(PREFIX + key, null) != null;
    }

    public void put(String key, State s) {
        config.set(PREFIX + key, (s.muted() ? "1" : "0") + "," + s.volume() + "," + (s.loop() ? "1" : "0"));
        saveLater.restart();
    }

    /** Puts a saved state on a player before it starts (one time, so the sound never goes on-off-on). */
    public static void apply(Transport t, State s) {
        t.setVolume(s.volume());
        t.setMuted(s.muted());
        t.setLoop(s.loop());
    }

    /**
     * The same controls, remembering every MUTE / VOLUME / LOOP change under {@code key}. The transport UI
     * binds to this, so its icons always show the player's real state.
     */
    public Transport remember(Transport t, String key) {
        return t == null ? null : new Remembered(t, key);
    }

    /** A transport that records its sound and loop changes. */
    public final class Remembered implements Transport {
        private final Transport delegate;
        private final String key;

        Remembered(Transport delegate, String key) {
            this.delegate = delegate;
            this.key = key;
        }

        /** The real player (e.g. so the audio player's fade buttons still apply). */
        public Transport delegate() {
            return delegate;
        }

        private void save() {
            put(key, new State(delegate.isMuted(), delegate.volume(), delegate.isLoop()));
        }

        @Override public void playOrResume() { delegate.playOrResume(); }
        @Override public void pause() { delegate.pause(); }
        @Override public void stop() { delegate.stop(); }
        @Override public void seek(float position) { delegate.seek(position); }
        @Override public void setVolume(int volume) { delegate.setVolume(volume); save(); }
        @Override public int volume() { return delegate.volume(); }
        @Override public void setMuted(boolean muted) { delegate.setMuted(muted); save(); }
        @Override public boolean isMuted() { return delegate.isMuted(); }
        @Override public void setLoop(boolean loop) { delegate.setLoop(loop); save(); }
        @Override public boolean isLoop() { return delegate.isLoop(); }
        @Override public boolean isPlaying() { return delegate.isPlaying(); }
        @Override public long time() { return delegate.time(); }
        @Override public long length() { return delegate.length(); }
        @Override public boolean hasMedia() { return delegate.hasMedia(); }
        @Override public void addStateListener(Runnable listener) { delegate.addStateListener(listener); }
    }
}
