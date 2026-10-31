package com.jamc.worship.render;

import java.awt.image.BufferedImage;

/**
 * Supplies the latest frames of live media to the renderer and compositor. Implemented by the
 * media layer; when VLC is unavailable every method returns null and live media is simply absent.
 */
public interface LiveFrames {

    /** Frame of a looping background/scene video, starting it if needed. */
    BufferedImage video(String path);

    /**
     * Frame of a video waiting in Preview as fullscreen content. Heard only on the monitor device while the
     * operator's PREVIEW AUDIO is on; otherwise silent like {@link #video}.
     */
    default BufferedImage previewVideo(String path) {
        return video(path);
    }

    /** Frame of a looping video only if it is already running (for thumbnails). */
    BufferedImage videoPoster(String path);

    /**
     * Frame of a scene's VIDEO source. Sources on Program play with sound; elsewhere they play silently.
     */
    default BufferedImage sceneVideo(com.jamc.worship.model.Source source) {
        return video(source.getConfig().path);
    }

    /** Frame of the video currently taken live as Program content. */
    BufferedImage programVideo();

    /** File of the video taken live as Program content, or null. */
    default String programVideoPath() {
        return null;
    }

    /** Frame from a camera (blank name = first camera). */
    BufferedImage camera(String name);

    LiveFrames NONE = new LiveFrames() {
        @Override
        public BufferedImage video(String path) {
            return null;
        }

        @Override
        public BufferedImage videoPoster(String path) {
            return null;
        }

        @Override
        public BufferedImage programVideo() {
            return null;
        }

        @Override
        public BufferedImage camera(String name) {
            return null;
        }
    };
}
