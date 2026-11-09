package com.jamc.worship.media;

/**
 * Library media that streams from the internet instead of a file (Pixabay videos): the stored path is the {@code https}
 * address itself, which VLC plays directly. Such a path is never turned into a file path (on Windows it is not a valid
 * one) and is never "missing" on disk; when the network fails, the player reports an error instead.
 */
public final class OnlineMedia {

    private OnlineMedia() {
    }

    /** True for an {@code http(s)://} address. */
    public static boolean isUrl(String path) {
        return path != null && (path.startsWith("https://") || path.startsWith("http://"));
    }
}
