package com.jamc.worship.media;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Broadcasts "a new video or camera frame arrived" to everything that paints live media. Frames
 * arrive on VLC threads; listeners are called on the event dispatch thread, coalesced so a busy
 * UI never builds up a queue of repaints.
 */
public final class MediaFrames {

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean PENDING = new AtomicBoolean();

    private MediaFrames() {
    }

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** Called from any thread when a frame changed. */
    public static void frameArrived() {
        if (PENDING.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                PENDING.set(false);
                LISTENERS.forEach(Runnable::run);
            });
        }
    }
}
