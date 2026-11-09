package com.jamc.worship.output;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;

/**
 * An immutable snapshot of what was sent to Program. The slide is copied when taken, so editing
 * a song or deck afterwards never changes what the congregation currently sees.
 *
 * @param title     what the content belongs to (song title, deck, video name) for status displays
 * @param videoPath absolute path of a video taken live, or null for slides and images
 * @param lyrics    a lyrics card's place in its song plus the lyrics transition to use (Smooth Fade or
 *                  Spotify-style), or null: the content then changes with Program's own transition
 */
public record LiveContent(Slide slide, SlideStyle style, String title, String videoPath, LyricsContext lyrics) {

    public LiveContent(Slide slide, SlideStyle style, String title, String videoPath) {
        this(slide, style, title, videoPath, null);
    }

    public static LiveContent snapshot(Slide slide, SlideStyle style, String title) {
        return new LiveContent(slide.copy(), style, title, null, null);
    }

    public static LiveContent snapshot(Slide slide, SlideStyle style, String title, LyricsContext lyrics) {
        return new LiveContent(slide.copy(), style, title, null, lyrics);
    }

    /** A video played fullscreen on Program. */
    public static LiveContent video(String absolutePath, String title) {
        return new LiveContent(new Slide(title, "", null), SlideStyle.fallback(), title, absolutePath, null);
    }

    public boolean isVideo() {
        return videoPath != null;
    }

    /** True when this lyrics card is shown Spotify-style (previous and next cards dimmed around it). */
    public boolean spotifyLyrics() {
        return lyrics != null && lyrics.spotify() && !isVideo();
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
