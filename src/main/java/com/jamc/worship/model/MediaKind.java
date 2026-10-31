package com.jamc.worship.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The physical type of a media file, guessed from its extension.
 *
 * <p>For video the list only decides what file choosers show and how imports are classified; whether a
 * video really plays is decided by VLC (see {@code MediaProbe}), so files with other extensions can
 * still be used when VLC can decode them.
 */
public enum MediaKind {
    IMAGE(Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp")),
    VIDEO(Set.of("mp4", "m4v", "mov", "qt", "mkv", "avi", "webm", "wmv", "asf", "mpg", "mpeg", "mpe", "m2v", "m1v",
            "vob", "ts", "mts", "m2ts", "m2t", "flv", "f4v", "3gp", "3g2", "ogv", "ogm", "mxf", "divx", "dv", "rm",
            "rmvb", "nut", "y4m", "h264", "hevc")),
    AUDIO(Set.of("mp3", "wav", "aac", "m4a", "ogg", "flac", "wma"));

    /** The video formats most churches use; shown first in file choosers. */
    public static final List<String> COMMON_VIDEO = List.of("mp4", "mov", "mkv", "avi", "webm", "wmv", "m4v", "mpg",
            "mpeg", "ts", "mts", "m2ts", "flv");

    private final Set<String> extensions;

    MediaKind(Set<String> extensions) {
        this.extensions = extensions;
    }

    public Set<String> extensions() {
        return extensions;
    }

    public static Optional<MediaKind> of(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (MediaKind k : values()) {
            if (k.extensions.contains(ext)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    public static MediaKind parse(String v, MediaKind fallback) {
        try {
            return v == null ? fallback : valueOf(v);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
