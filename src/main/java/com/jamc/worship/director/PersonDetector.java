package com.jamc.worship.director;

import java.awt.image.BufferedImage;
import java.util.List;

/** Finds the people in one camera picture. Implementations run off the Swing thread. */
public interface PersonDetector extends AutoCloseable {

    /** One person: where (normalized to the picture) and how sure the model is (0..1). */
    record Detection(Box box, float score) {
    }

    /**
     * The people in the picture. {@code input} is the small analysis copy made by {@link #prepare}; the boxes are
     * normalized to the original camera frame.
     */
    List<Detection> detect(Prepared input) throws Exception;

    /**
     * Copies a camera frame into the detector's small input picture (letterboxed). Cheap; may run on the Swing
     * thread so the camera's own buffer is only read there. {@code reuse} is the previous copy, filled again when
     * it fits (no new allocation per frame).
     */
    Prepared prepare(BufferedImage frame, Prepared reuse);

    /** Short name for the UI, e.g. "YOLOX-nano · CPU". */
    String describe();

    @Override
    void close();

    /** The analysis copy of one frame and how it maps back to the frame. */
    record Prepared(BufferedImage image, double scale, int frameWidth, int frameHeight) {
    }

    /** A detector that never finds anyone: Manual mode and zone-based shots still work. */
    PersonDetector NONE = new PersonDetector() {
        @Override
        public List<Detection> detect(Prepared input) {
            return List.of();
        }

        @Override
        public Prepared prepare(BufferedImage frame, Prepared reuse) {
            return new Prepared(null, 1, frame == null ? 0 : frame.getWidth(), frame == null ? 0 : frame.getHeight());
        }

        @Override
        public String describe() {
            return "No AI model";
        }

        @Override
        public void close() {
        }
    };
}
