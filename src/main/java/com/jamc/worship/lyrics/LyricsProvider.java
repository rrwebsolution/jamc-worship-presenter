package com.jamc.worship.lyrics;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * A source of lyrics. Providers are independent: each says what it can do ({@link Capability}) and
 * whether it is set up ({@link Status}), so the app never assumes every API works the same way.
 *
 * <p>Implementations must only use authorized APIs, licensed content, or the church's own files: never
 * scrape lyrics websites or work around a provider's restrictions. A licensed worship lyrics provider can be
 * added later by implementing this interface and registering it in {@link LyricsProviders}.
 */
public interface LyricsProvider {

    enum Capability {
        SEARCH("Can search"),
        PLAIN_LYRICS("Plain lyrics"),
        SYNCED_LYRICS("Synced lyrics"),
        METADATA("Metadata"),
        STORE_OFFLINE("Can store offline");

        private final String label;

        Capability(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    enum Status {
        CONNECTED("● Connected"), NOT_CONFIGURED("○ Not configured"), DISABLED("○ Off");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** How trustworthy a provider's lyrics are for public projection (result priority, spec order). */
    enum Trust {
        /** Licensed / authorized lyrics (e.g. a licensed worship lyrics service). */
        LICENSED,
        /** The church's own files or library. */
        CHURCH,
        /** A public lyrics API whose content the church must still be licensed to project. */
        COMMUNITY
    }

    String id();

    String name();

    /** One line for Settings: what it is and what the church is responsible for. */
    String description();

    Set<Capability> capabilities();

    Status status();

    Trust trust();

    /** Songs matching the words. An empty list means "nothing found" (not an error). */
    List<LyricsResult> search(String query) throws IOException;

    /** The full lyrics of a search result (plain and, if the provider has them, timed). */
    LyricsResult fetch(LyricsResult result) throws IOException;

    default boolean canStoreOffline() {
        return capabilities().contains(Capability.STORE_OFFLINE);
    }
}
