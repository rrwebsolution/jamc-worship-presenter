package com.jamc.worship.ui.components;

import com.jamc.worship.media.VideoPlayback;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The position bar reaches the end when a video finishes (VLC then reports 0:00 / 0:00). */
class TransportControlsTest {

    /** A 5-second video that can be moved along and finished like VLC does it. */
    static final class FakeVideo implements VideoPlayback {
        long time;
        long length = 5000;
        boolean playing;
        final List<Runnable> finished = new ArrayList<>();
        final List<Runnable> state = new ArrayList<>();

        void finish() {
            playing = false;
            time = 0;   // what VLC reports once a video has ended
            length = 0;
            finished.forEach(Runnable::run);
        }

        @Override public void play(String path, boolean loop, boolean muted, String... options) { playing = true; }
        @Override public void cue(String path, boolean loop) { }
        @Override public String mrl() { return "x.mp4"; }
        @Override public BufferedImage currentFrame() { return null; }
        @Override public boolean hasError() { return false; }
        @Override public void seekTime(long ms) { time = ms; }
        @Override public void setOutputDevice(String deviceId) { }
        @Override public void addFinishedListener(Runnable listener) { finished.add(listener); }
        @Override public void release() { }
        @Override public void playOrResume() { playing = true; length = 5000; }
        @Override public void pause() { playing = false; }
        @Override public void stop() { playing = false; time = 0; }
        @Override public void seek(float position) { time = (long) (position * length); }
        @Override public void setVolume(int volume) { }
        @Override public int volume() { return 100; }
        @Override public void setMuted(boolean muted) { }
        @Override public boolean isMuted() { return false; }
        @Override public void setLoop(boolean loop) { }
        @Override public boolean isLoop() { return false; }
        @Override public boolean isPlaying() { return playing; }
        @Override public long time() { return time; }
        @Override public long length() { return length; }
        @Override public boolean hasMedia() { return true; }
        @Override public void addStateListener(Runnable listener) { state.add(listener); }
    }

    private static <T extends Component> T find(Container c, Class<T> type, int nth) {
        List<T> all = new ArrayList<>();
        collect(c, type, all);
        return all.get(nth);
    }

    private static <T extends Component> void collect(Container c, Class<T> type, List<T> out) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) {
                out.add(type.cast(k));
            }
            if (k instanceof Container cc) {
                collect(cc, type, out);
            }
        }
    }

    @Test
    void theBarGoesToTheEndWhenTheVideoFinishes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TransportControls tc = new TransportControls(true);
            FakeVideo v = new FakeVideo();
            tc.bind(v);
            JSlider seek = find(tc, JSlider.class, 0); // the position bar comes first
            JLabel time = find(tc, JLabel.class, 0);

            v.playOrResume();
            v.time = 4400; // the last moment the poll saw it
            tc.refresh();
            assertEquals(880, seek.getValue());

            v.finish();
            tc.refresh();
            assertEquals(1000, seek.getValue(), "finished: the bar is at the end");
            assertEquals("0:05 / 0:05", time.getText(), "and the time shows the full length, not 0:00 / 0:00");

            v.playOrResume(); // PLAY again from the start
            v.time = 0;
            tc.refresh();
            assertEquals(0, seek.getValue());
            assertEquals("0:00 / 0:05", time.getText());

            v.time = 3000;
            tc.refresh();
            v.stop();
            tc.refresh();
            assertEquals(0, seek.getValue(), "STOP goes back to the start");
            tc.bind(null);
        });
    }
}
