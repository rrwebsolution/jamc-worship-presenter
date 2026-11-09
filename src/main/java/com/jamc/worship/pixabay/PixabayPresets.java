package com.jamc.worship.pixabay;

import java.util.List;

/**
 * LiveCat's praise and worship search presets (not Pixabay categories): each is a list of search words; the first
 * is searched when the preset is chosen, the others are one click away.
 */
public enum PixabayPresets {

    FAST_PRAISE("Fast Praise", List.of("energetic abstract lights", "colorful motion background", "blue light waves",
            "concert lights", "celebration particles")),
    SLOW_WORSHIP("Slow Worship", List.of("peaceful clouds", "heavenly light", "soft blue particles",
            "gentle flowing waves", "cinematic sky")),
    ANNIVERSARY("Anniversary Worship", List.of("gold particles", "blue gold abstract", "celebration lights",
            "cinematic glowing background", "elegant motion")),
    NATURE("Nature Worship", List.of("sunrise mountains", "peaceful ocean", "moving clouds", "forest sunlight",
            "waterfalls")),
    CUSTOM("Custom Search", List.of());

    private final String label;
    private final List<String> keywords;

    PixabayPresets(String label, List<String> keywords) {
        this.label = label;
        this.keywords = keywords;
    }

    public String label() {
        return label;
    }

    public List<String> keywords() {
        return keywords;
    }

    @Override
    public String toString() {
        return label;
    }
}
