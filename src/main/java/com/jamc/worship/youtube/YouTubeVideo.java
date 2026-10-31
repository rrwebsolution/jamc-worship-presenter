package com.jamc.worship.youtube;

/**
 * What YouTube tells us about a video (search result or lookup). Only this metadata is stored; the
 * video itself always plays from YouTube.
 *
 * @param durationMs null when YouTube did not say (e.g. a lookup without an API key)
 */
public record YouTubeVideo(String id, String title, String channel, Long durationMs, String thumbnailUrl) {

    public String path() {
        return YouTubeRef.path(id);
    }

    public String watchUrl() {
        return YouTubeRef.watchUrl(id);
    }

    /** The standard thumbnail YouTube serves for every public video. */
    public static String defaultThumbnail(String id) {
        return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
    }
}
