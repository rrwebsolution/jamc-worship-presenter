package com.jamc.worship.stream;

import java.util.List;

/** The livestream destinations the app knows. Add a platform by adding a provider here. */
public final class StreamingProviders {

    /**
     * Facebook Live through its "Streaming software" option: Live Producer shows a server URL and a
     * (persistent) stream key, which the church pastes here. No Facebook login happens in this app.
     */
    public static final StreamingProvider FACEBOOK = new StreamingProvider() {
        @Override
        public String id() {
            return "FACEBOOK";
        }

        @Override
        public String displayName() {
            return "Facebook Live";
        }

        @Override
        public String defaultServer() {
            return "rtmps://live-api-s.facebook.com:443/rtmp/";
        }

        @Override
        public String setupHelp() {
            return "In Facebook, open Live Producer for your Page, choose \"Streaming software\" and copy the "
                    + "Server URL and the Stream key (turn on \"Use persistent stream key\" to reuse it every Sunday).";
        }

        @Override
        public boolean asksDestinationName() {
            return true;
        }

        @Override
        public String toString() {
            return displayName();
        }
    };

    /** Any RTMP / RTMPS server (YouTube, a church media server, a restream service...). */
    public static final StreamingProvider CUSTOM_RTMP = new StreamingProvider() {
        @Override
        public String id() {
            return "CUSTOM_RTMP";
        }

        @Override
        public String displayName() {
            return "Custom RTMP";
        }

        @Override
        public String defaultServer() {
            return null;
        }

        @Override
        public String setupHelp() {
            return "Enter the RTMP or RTMPS server URL and the stream key given by your streaming platform.";
        }

        @Override
        public String toString() {
            return displayName();
        }
    };

    private StreamingProviders() {
    }

    public static List<StreamingProvider> all() {
        return List.of(FACEBOOK, CUSTOM_RTMP);
    }

    public static StreamingProvider byId(String id) {
        return all().stream().filter(p -> p.id().equals(id)).findFirst().orElse(FACEBOOK);
    }
}
