package com.jamc.worship.ui.components;

import com.jamc.worship.stream.AudioLevels;
import com.jamc.worship.stream.StreamManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Feeds the {@link LevelMeter}s of a mixer. Inputs are listened to only while the mixer is on screen,
 * each input once even when two meters show it. The channel's volume and mute are applied, so the wave
 * shows what goes into the mix. An input that is unplugged is tried again every few seconds.
 */
final class MixerMeters {

    /** Shows a channel level: the wave ({@link LevelMeter}) or the vertical bar ({@link VuMeter}). */
    interface Meter {
        /** The newest level, 0..1 peak amplitude. */
        void push(float level);

        /** Shows {@code text} instead of the level (null = the level). */
        void setMessage(String text);
    }

    private static final Logger log = LoggerFactory.getLogger(MixerMeters.class);

    /** A channel: its meter, which input it hears (null = none) and its gain (0 = muted, 1 = 100 %). */
    private record Channel(Meter meter, Supplier<String> input, DoubleSupplier gain) {
    }

    private final StreamManager stream;
    private final List<Channel> channels = new ArrayList<>();
    private final List<Meter> masters = new ArrayList<>();
    private DoubleSupplier masterGain = () -> 1;
    private final Map<String, AudioLevels.Source> open = new HashMap<>();
    private final Map<String, String> failed = new HashMap<>();
    private final Map<String, Long> retryAt = new HashMap<>();
    private final java.util.Set<String> opening = new java.util.HashSet<>();
    private final Timer timer = new Timer(50, e -> tick());
    private boolean running;

    MixerMeters(StreamManager stream) {
        this.stream = stream;
    }

    void add(Meter meter, Supplier<String> input, DoubleSupplier gain) {
        channels.add(new Channel(meter, input, gain));
    }

    /** The MASTER meter: the loudest channel, times the master gain. */
    void addMaster(Meter meter, DoubleSupplier gain) {
        masters.add(meter);
        masterGain = gain;
    }

    /** Forgets the meters (the mixer rebuilds its rows); listening continues for inputs still used. */
    void clearMeters() {
        channels.clear();
        masters.clear();
    }

    /** Listens while {@code panel} is on screen. */
    void attach(JComponent panel) {
        panel.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (panel.isShowing()) start(); else stop();
            }
        });
    }

    void start() {
        if (!running) {
            running = true;
            timer.start();
        }
    }

    void stop() {
        running = false;
        timer.stop();
        open.values().forEach(AudioLevels.Source::close);
        open.clear();
        failed.clear();
        retryAt.clear();
    }

    private void tick() {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        Map<String, Float> levels = new HashMap<>();
        float loudest = 0;
        for (Channel c : channels) {
            String in = c.input().get();
            if (in == null) {
                c.meter().setMessage("no input chosen");
                continue;
            }
            wanted.add(in);
            AudioLevels.Source src = open.get(in);
            if (src == null) {
                ensureOpen(in);
                c.meter().setMessage(failed.containsKey(in) ? failed.get(in) : "starting…");
                continue;
            }
            float raw = levels.computeIfAbsent(in, k -> src.level());
            float v = (float) Math.min(1, raw * Math.max(0, c.gain().getAsDouble()));
            c.meter().setMessage(null);
            c.meter().push(v);
            loudest = Math.max(loudest, v);
        }
        for (Meter m : masters) {
            m.setMessage(null);
            m.push((float) Math.min(1, loudest * Math.max(0, masterGain.getAsDouble())));
        }
        // inputs no meter uses any more
        for (String in : new ArrayList<>(open.keySet())) {
            if (!wanted.contains(in)) {
                open.remove(in).close();
            }
        }
    }

    private void ensureOpen(String input) {
        long now = System.currentTimeMillis();
        if (opening.contains(input) || now < retryAt.getOrDefault(input, 0L)) {
            return;
        }
        opening.add(input);
        Thread.ofVirtual().name("level-open").start(() -> {
            AudioLevels.Source src = null;
            String problem = null;
            try {
                src = stream.openLevel(input);
            } catch (Exception e) {
                problem = e.getMessage() != null && e.getMessage().contains("not connected") ? "not connected" : "cannot listen";
                log.debug("Level meter for {}: {}", input, e.getMessage());
            }
            AudioLevels.Source opened = src;
            String why = problem;
            javax.swing.SwingUtilities.invokeLater(() -> {
                opening.remove(input);
                if (opened == null) {
                    failed.put(input, why);
                    retryAt.put(input, System.currentTimeMillis() + 5000);
                } else if (!running || open.containsKey(input)) {
                    opened.close();
                } else {
                    failed.remove(input);
                    open.put(input, opened);
                }
            });
        });
    }
}
