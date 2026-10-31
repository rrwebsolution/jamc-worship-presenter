package com.jamc.worship.model;

import java.util.Locale;
import java.util.Optional;

/** Kinds of song sections. {@link #CUSTOM} carries a free-form label such as "Tag" or "Vamp". */
public enum SectionType {
    VERSE("Verse", "V", true),
    PRE_CHORUS("Pre-Chorus", "PC", true),
    CHORUS("Chorus", "C", true),
    BRIDGE("Bridge", "B", true),
    INTRO("Intro", "I", false),
    OUTRO("Outro", "O", false),
    ENDING("Ending", "E", false),
    CUSTOM("Custom", "*", false);

    private final String displayName;
    private final String shortCode;
    private final boolean numbered;

    SectionType(String displayName, String shortCode, boolean numbered) {
        this.displayName = displayName;
        this.shortCode = shortCode;
        this.numbered = numbered;
    }

    public String displayName() {
        return displayName;
    }

    public String shortCode() {
        return shortCode;
    }

    /** Whether multiple sections of this type are usually distinguished by number ("Verse 2"). */
    public boolean numbered() {
        return numbered;
    }

    /** Matches a header keyword such as "verse", "pre chorus", "prechorus" or "chorus". */
    public static Optional<SectionType> fromKeyword(String keyword) {
        String k = keyword.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        return Optional.ofNullable(switch (k) {
            case "verse", "v" -> VERSE;
            case "prechorus", "pc", "pre" -> PRE_CHORUS;
            case "chorus", "c", "refrain" -> CHORUS;
            case "bridge", "b" -> BRIDGE;
            case "intro" -> INTRO;
            case "outro" -> OUTRO;
            case "ending", "end" -> ENDING;
            default -> null;
        });
    }

    public static SectionType fromDatabase(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return CUSTOM;
        }
    }
}
