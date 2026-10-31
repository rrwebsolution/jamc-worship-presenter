package com.jamc.worship.stream;

import java.io.IOException;

/**
 * An encoder that turns Program frames and audio into a recording or a livestream. FFmpeg does the
 * work today ({@link FFmpegStreamEncoder}); another engine could be added behind this interface.
 */
public interface StreamEncoder {

    /** Health figures for the operator. */
    record Stats(long frames, double fps, double bitrateKbps, long droppedFrames, long elapsedMs) {
    }

    /** Called from encoder threads (not the Swing thread). */
    interface Listener {
        void stats(Stats stats);

        /** The encoder stopped; {@code error} is null after a normal stop. */
        void ended(String error);
    }

    void start() throws IOException;

    void stop();

    boolean isRunning();
}
