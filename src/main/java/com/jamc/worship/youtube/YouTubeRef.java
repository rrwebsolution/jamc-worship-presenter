package com.jamc.worship.youtube;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * YouTube video references. A YouTube video in the media library, a scene's Video source or a taken
 * Program video is stored as the path {@code youtube:VIDEO_ID}; nothing is downloaded.
 */
public final class YouTubeRef {

    public static final String PREFIX = "youtube:";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private YouTubeRef() {
    }

    public static boolean is(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    /** The video id of a {@code youtube:} path, or null. */
    public static String id(String path) {
        return is(path) ? path.substring(PREFIX.length()) : null;
    }

    public static String path(String videoId) {
        return PREFIX + videoId;
    }

    public static String watchUrl(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    public static boolean isVideoId(String s) {
        return s != null && ID.matcher(s).matches();
    }

    /**
     * The video id in a pasted link or id: {@code youtube.com/watch?v=}, {@code youtu.be/},
     * {@code /embed/}, {@code /shorts/}, {@code /live/}, {@code music.youtube.com}, or a bare 11-character id.
     */
    public static Optional<String> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.strip();
        if (isVideoId(s)) {
            return Optional.of(s);
        }
        if (!s.contains("://")) {
            s = "https://" + s;
        }
        try {
            URI uri = URI.create(s);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.equals("youtu.be") || host.endsWith(".youtu.be")) {
                return first(path.substring(1));
            }
            if (!host.equals("youtube.com") && !host.endsWith(".youtube.com") && !host.endsWith("youtube-nocookie.com")) {
                return Optional.empty();
            }
            String query = uri.getRawQuery();
            if (query != null) {
                for (String p : query.split("&")) {
                    if (p.startsWith("v=")) {
                        return first(p.substring(2));
                    }
                }
            }
            for (String prefix : new String[]{"/embed/", "/shorts/", "/live/", "/v/"}) {
                if (path.startsWith(prefix)) {
                    return first(path.substring(prefix.length()));
                }
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<String> first(String s) {
        String id = s.length() >= 11 ? s.substring(0, 11) : s;
        return isVideoId(id) ? Optional.of(id) : Optional.empty();
    }
}
