package com.jamc.worship.output;

import com.jamc.worship.model.Scene;

/**
 * Everything that determines one Program frame: the live content, the operator's visibility
 * switches and the active scene. Any change produces a new spec and Program transitions between
 * specs, so going to black, hiding lyrics or switching scenes fades exactly like changing slides.
 *
 * @param content    what is live, or null if nothing has been taken yet
 * @param text       lyrics/text visible
 * @param background background visible (otherwise black behind the text)
 * @param black      whole output black
 * @param scene      layer composition; null means the default (background, text, lower third)
 */
public record FrameSpec(LiveContent content, boolean text, boolean background, boolean black, Scene scene) {

    public static final FrameSpec EMPTY = new FrameSpec(null, true, true, false, null);

    /** True if anything other than black is on screen. */
    public boolean showsSomething() {
        return !black && (content != null || (scene != null && !scene.getSources().isEmpty()));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FrameSpec f && f.content == content && f.text == text && f.background == background
                && f.black == black && f.scene == scene;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(content) * 31 + System.identityHashCode(scene)
                + (text ? 1 : 0) + (background ? 2 : 0) + (black ? 4 : 0);
    }
}
