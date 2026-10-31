package com.jamc.worship.model;

/**
 * Kinds of presented text that each keep their own global typography profile, so for example
 * capital-letter song lyrics never turn Bible verses into capitals.
 *
 * <p>Lower thirds keep the font chosen in each lower third, and PowerPoint slides are shown as
 * the pictures PowerPoint drew, so neither has a profile here.
 */
public enum ContentKind {
    LYRICS("Song Lyrics", "lyrics"),
    BIBLE("Bible", "bible"),
    ANNOUNCEMENTS("Announcements & Sermon Notes", "announcements"),
    CUSTOM_SLIDES("Custom Slides", "customSlides");

    private final String label;
    private final String key;

    ContentKind(String label, String key) {
        this.label = label;
        this.key = key;
    }

    /** Prefix of this profile's keys in settings.properties ({@code lyrics.fontFamily}...). */
    public String key() {
        return key;
    }

    @Override
    public String toString() {
        return label;
    }
}
