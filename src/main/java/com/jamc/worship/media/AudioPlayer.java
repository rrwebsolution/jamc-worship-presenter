package com.jamc.worship.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Background audio (walk-in music, instrumental beds). Independent of slides: changing slides or
 * scenes never interrupts it. Supports fade in and fade out.
 */
public final class AudioPlayer implements Transport {

    private static final Logger log = LoggerFactory.getLogger(AudioPlayer.class);

    private final MediaPlayer player;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Timer fadeTimer;
    private String path;
    private String title;
    private boolean loop;
    private int volume = 100;
    private volatile boolean released;
    /** Program mixer gain (media channel × master), applied on top of this player's own volume and fades. */
    private volatile double gain = 1.0;
    private volatile String outputDevice;
    /** The device last routed to (re-routing to the same device restarts the sound: an audible drop). */
    private volatile String appliedDevice;

    public AudioPlayer() {
        this.player = MediaEngine.get().newAudioPlayer();
        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                if (outputDevice != null) {
                    mediaPlayer.submit(() -> route());
                }
                fire();
            }

            @Override
            public void paused(MediaPlayer mediaPlayer) {
                fire();
            }

            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                fire();
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                fire();
            }

            @Override
            public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
                fire();
            }

            @Override
            public void error(MediaPlayer mediaPlayer) {
                log.warn("VLC could not play audio {}", path);
                fire();
            }
        });
        MediaEngine.get().onShutdown(this::release);
    }

    /** Loads and plays a file. */
    public void play(String file, String title) {
        if (released) {
            return;
        }
        stopFade();
        this.path = file;
        this.title = title;
        player.controls().setRepeat(loop);
        out(volume);
        player.media().play(file, ":no-video");
        fire();
    }

    /** Mixer gain 0..1.5 (media channel × master; 0 = silent). */
    public void setGain(double g) {
        gain = Math.clamp(g, 0.0, 1.5);
        if (!released && fadeTimer == null) {
            out(volume);
        }
    }

    /** Output device (VLC id; null or "" = the Windows default). */
    public void setOutputDevice(String deviceId) {
        outputDevice = deviceId == null ? "" : deviceId;
        if (!released) {
            route();
        }
    }

    private void route() {
        try {
            String d = outputDevice;
            if (d.equals(appliedDevice) && (d.isEmpty() || d.equals(player.audio().outputDevice()))) {
                return;
            }
            player.audio().setOutputDevice(null, d);
            appliedDevice = d;
        } catch (RuntimeException e) {
            log.warn("Could not route audio to device {}", outputDevice, e);
        }
    }

    private void out(int v) {
        if (!released) {
            player.audio().setVolume((int) Math.round(Math.clamp(v, 0, 200) * gain));
        }
    }

    public String title() {
        return title;
    }

    /** The file playing (or last played), or null. */
    public String path() {
        return path;
    }

    /** Raises volume from silence to the set level over {@code ms}. */
    public void fadeIn(int ms) {
        if (path == null || released) {
            return;
        }
        int target = volume;
        out(0);
        if (!player.status().isPlaying()) {
            playOrResume();
        }
        fade(0, target, ms, null);
    }

    /** Lowers volume to silence over {@code ms}, then pauses. */
    public void fadeOut(int ms) {
        if (path == null || released || !player.status().isPlaying()) {
            return;
        }
        int start = volume; // the set level (the mixer gain is applied on top in out())
        fade(start, 0, ms, () -> {
            player.controls().setPause(true);
            out(volume);
        });
    }

    private void fade(int from, int to, int ms, Runnable done) {
        stopFade();
        long start = System.nanoTime();
        fadeTimer = new Timer(40, e -> {
            float p = Math.min(1f, (System.nanoTime() - start) / 1_000_000f / Math.max(1, ms));
            out(Math.round(from + (to - from) * p));
            if (p >= 1f) {
                stopFade();
                if (done != null) {
                    done.run();
                }
                fire();
            }
        });
        fadeTimer.start();
    }

    private void stopFade() {
        if (fadeTimer != null) {
            fadeTimer.stop();
            fadeTimer = null;
        }
    }

    @Override
    public void playOrResume() {
        if (path == null || released) {
            return;
        }
        if (player.status().length() > 0 && !player.status().isPlaying() && player.status().time() > 0) {
            player.controls().play();
        } else if (!player.status().isPlaying()) {
            play(path, title);
        }
    }

    @Override
    public void pause() {
        stopFade();
        if (!released) {
            player.controls().setPause(true);
        }
    }

    @Override
    public void stop() {
        stopFade();
        if (!released) {
            player.controls().stop();
            out(volume);
        }
        fire();
    }

    @Override
    public void seek(float position) {
        if (!released) {
            player.controls().setPosition(Math.clamp(position, 0f, 1f));
        }
    }

    @Override
    public void setVolume(int volume) {
        this.volume = Math.clamp(volume, 0, 200);
        if (!released && fadeTimer == null) {
            out(this.volume);
        }
    }

    @Override
    public int volume() {
        return volume;
    }

    @Override
    public void setMuted(boolean muted) {
        if (!released) {
            player.audio().setMute(muted);
        }
        fire();
    }

    @Override
    public boolean isMuted() {
        return !released && player.audio().isMute();
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
        return path != null;
    }

    @Override
    public void addStateListener(Runnable listener) {
        listeners.add(listener);
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        stopFade();
        try {
            player.controls().stop();
            player.release();
        } catch (Throwable e) {
            log.debug("Error releasing audio player", e);
        }
    }

    private void fire() {
        SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
    }
}
