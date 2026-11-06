package com.jamc.worship.model;

/** Layer types a scene can contain. */
public enum SourceType {
    BACKGROUND("Media", "layers"),
    LYRICS("Lyrics / Slide Text", "music"),
    BIBLE("Bible Text", "book"),
    CAMERA("Camera", "video"),
    IMAGE("Image", "image"),
    CHURCH_LOGO("Church Logo", "image"),
    VIDEO("Video (looping)", "video"),
    LOWER_THIRD("Lower Third", "lower-third"),
    TEXT("Text", "edit"),
    CLOCK("Clock", "calendar"),
    /** A dark gradient that keeps lyrics readable over a camera or busy picture. */
    OVERLAY("Readability Overlay", "layers"),
    /** A presentation from the library, referenced by id (its slides are never copied into the scene). */
    PRESENTATION("Presentation", "presentation"),
    /** A plain filled box (color with opacity), e.g. a bar behind a logo or text. */
    COLOR("Color / Shape", "palette"),
    /** Sound only (music from the audio library): plays on Program with the scene, no picture. */
    AUDIO("Audio", "audio"),
    /** Live sound from a connected input (a microphone, a camera's audio, the audio tech's mixer), no picture. */
    AUDIO_INPUT("Audio Input", "audio"),
    /** Several library images as ONE source, shown one after another (auto play or Previous / Next). */
    SLIDESHOW("Image Slideshow", "slides"),
    /** A live picture of one open window on this computer (e.g. a browser, a Zoom call), found by its title. */
    WINDOW_CAPTURE("Window Capture", "monitor"),
    /** A web page (URL) drawn by an off-screen browser, live (e.g. a countdown, an online overlay). */
    BROWSER("Browser", "monitor");

    private final String label;
    private final String icon;

    SourceType(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String icon() {
        return icon;
    }

    public static SourceType parse(String v, SourceType fallback) {
        try {
            return v == null ? fallback : valueOf(v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
