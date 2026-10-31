package com.jamc.worship.stream;

import java.util.ArrayList;
import java.util.List;

/**
 * A livestream destination (Facebook Live, a custom RTMP server...). Providers only describe where
 * the encoded Program goes; they never log in, scrape or bypass a platform's security. The church
 * copies the server and stream key from the platform's own live-video tools.
 *
 * <p>Platforms change their requirements; keeping each one behind this interface means an update
 * touches one class, not the presentation system.
 */
public interface StreamingProvider {

    /** Stable id used in settings and for the stored stream key ("FACEBOOK", "CUSTOM_RTMP"). */
    String id();

    String displayName();

    /** Server shown when the operator has not entered one (null = must be entered). */
    String defaultServer();

    /** Short help on where the server and stream key come from. */
    String setupHelp();

    /** Whether the destination name (e.g. the Facebook Page) is asked for the start confirmation. */
    default boolean asksDestinationName() {
        return false;
    }

    /** Problems that stop a stream from starting; empty when the configuration is complete. */
    default List<String> validate(String server, boolean hasKey) {
        List<String> problems = new ArrayList<>();
        String s = server == null || server.isBlank() ? defaultServer() : server.strip();
        if (s == null || s.isBlank()) {
            problems.add("Enter the server URL.");
        } else if (!(s.startsWith("rtmp://") || s.startsWith("rtmps://"))) {
            problems.add("The server URL must start with rtmp:// or rtmps://.");
        }
        if (!hasKey) {
            problems.add("Enter the stream key.");
        }
        return problems;
    }

    /** The full ingest URL for FFmpeg: server plus stream key. Sensitive: never log or show it. */
    default String ingestUrl(String server, String streamKey) {
        String s = server == null || server.isBlank() ? defaultServer() : server.strip();
        return s.endsWith("/") ? s + streamKey : s + "/" + streamKey;
    }
}
