package com.jamc.worship.background;

import com.jamc.worship.model.FitMode;

/**
 * A snapshot of the one application-wide background that Preview and Program both draw under their
 * content. It is independent of scenes: taking a background never changes the Preview or Program
 * scene, and switching scenes never clears the background.
 *
 * @param type             what the background is drawn from
 * @param mediaPath        stored media path, or a {@code color:} / {@code gradient:} value; null when none
 * @param active           switched on and drawn under content that exposes it
 * @param playing          a video background that is currently running
 * @param playbackPosition position of a video background in ms, or -1
 * @param overlayOpacity   0..0.9 darkening drawn over the background
 */
public record GlobalBackgroundState(Type type, String mediaPath, boolean active, boolean playing, boolean loop, boolean muted,
                                    int volume, long playbackPosition, double overlayOpacity, FitMode fitMode) {

    public enum Type { NONE, VIDEO, IMAGE, COLOR, GRADIENT }

    public static Type typeOf(String mediaPath) {
        if (mediaPath == null) {
            return Type.NONE;
        }
        if (mediaPath.startsWith(GlobalBackgroundManager.COLOR_PREFIX)) {
            return Type.COLOR;
        }
        if (mediaPath.startsWith(GlobalBackgroundManager.GRADIENT_PREFIX)) {
            return Type.GRADIENT;
        }
        return GlobalBackgroundManager.isImage(mediaPath) ? Type.IMAGE : Type.VIDEO;
    }
}
