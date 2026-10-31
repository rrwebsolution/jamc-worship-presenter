package com.jamc.worship.output;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;

/**
 * An immutable snapshot of what was sent to Program. The slide is copied when taken, so editing
 * a song or deck afterwards never changes what the congregation currently sees.
 *
 * @param title     what the content belongs to (song title, deck, video name) for status displays
 * @param videoPath absolute path of a video taken live, or null for slides and images
 */
public record LiveContent(Slide slide, SlideStyle style, String title, String videoPath) {

    public static LiveContent snapshot(Slide slide, SlideStyle style, String title) {
        return new LiveContent(slide.copy(), style, title, null);
    }

    /** A video played fullscreen on Program. */
    public static LiveContent video(String absolutePath, String title) {
        return new LiveContent(new Slide(title, "", null), SlideStyle.fallback(), title, absolutePath);
    }

    public boolean isVideo() {
        return videoPath != null;
    }

    /** Equality is identity: taking the same slide twice is two separate takes. */
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
