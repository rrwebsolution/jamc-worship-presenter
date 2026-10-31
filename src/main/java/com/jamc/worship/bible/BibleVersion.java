package com.jamc.worship.bible;

import java.util.Objects;

/**
 * A Bible version the operator can choose (KJV, NLT, ESV, NIV, APSD, ...), and where its text
 * comes from. The text itself is never part of the application: it is read from a local file the
 * church has imported (or the bundled public-domain KJV) or from a licensed API.
 *
 * @param providerConfig provider-specific value, e.g. the API.Bible "bible id" for this version
 */
public record BibleVersion(Long id, String abbreviation, String name, String language, Provider provider,
                           String localFile, boolean enabled, String providerConfig, int sortOrder) {

    /** Where a version's text comes from. */
    public enum Provider {
        PUBLIC_DOMAIN("Public domain (bundled)", false),
        LOCAL_FILE("Imported file on this computer", false),
        ESV_API("ESV API (api.esv.org, your API key)", true),
        NLT_API("NLT API (api.nlt.to, your API key)", true),
        API_BIBLE("API.Bible (scripture.api.bible, your API key)", true),
        NONE("Not set up", false);

        private final String label;
        private final boolean online;

        Provider(String label, boolean online) {
            this.label = label;
            this.online = online;
        }

        public boolean online() {
            return online;
        }

        public static Provider parse(String v) {
            try {
                return v == null ? NONE : valueOf(v);
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public BibleVersion {
        Objects.requireNonNull(abbreviation);
        provider = provider == null ? Provider.NONE : provider;
    }

    public BibleVersion withProvider(Provider p, String file, String config, boolean on) {
        return new BibleVersion(id, abbreviation, name, language, p, file, on, config, sortOrder);
    }

    public BibleVersion withEnabled(boolean on) {
        return new BibleVersion(id, abbreviation, name, language, provider, localFile, on, providerConfig, sortOrder);
    }

    @Override
    public String toString() {
        return abbreviation + " — " + name;
    }
}
