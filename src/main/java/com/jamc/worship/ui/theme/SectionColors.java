package com.jamc.worship.ui.theme;

import com.jamc.worship.model.SectionType;
import com.jamc.worship.model.Slide;

import java.awt.Color;
import java.util.Locale;

/**
 * One color per kind of song section, so an operator sees at a glance where the verse, the chorus and the bridge
 * are: the section badge on the Lyrics & Slides cards and on the slide cards below the monitors.
 * Bible verses and slide decks keep the normal accent color.
 */
public final class SectionColors {

    private static final Color VERSE = new Color(0x3B82F6);      // blue
    private static final Color PRE_CHORUS = new Color(0xA855F7); // purple
    private static final Color CHORUS = new Color(0xF97316);     // orange (red is LIVE)
    private static final Color BRIDGE = new Color(0x22C55E);     // green
    private static final Color INTRO = new Color(0x14B8A6);      // teal
    private static final Color ENDING = new Color(0xEAB308);     // yellow (Outro, Ending)
    private static final Color OTHER = new Color(0xEC4899);      // pink (Tag, Vamp, other custom names)

    private SectionColors() {
    }

    /** The badge color of a slide: its song section's color, or the accent for anything that is not a song card. */
    public static Color of(Slide slide) {
        if (slide == null || (slide.getSectionKey() == null && slide.getSongId() == null)) {
            return Palette.accent();
        }
        return of(slide.displayLabel());
    }

    /** The color for a song section label such as "Verse 2", "Pre-Chorus", "CHORUS" or "Tag". */
    public static Color of(String label) {
        SectionType type = typeOf(label);
        return switch (type) {
            case VERSE -> VERSE;
            case PRE_CHORUS -> PRE_CHORUS;
            case CHORUS -> CHORUS;
            case BRIDGE -> BRIDGE;
            case INTRO -> INTRO;
            case OUTRO, ENDING -> ENDING;
            case CUSTOM -> OTHER;
        };
    }

    /** The kind of section a label names: "Verse 2" → VERSE, "Pre Chorus 1" → PRE_CHORUS, "Tag" → CUSTOM. */
    static SectionType typeOf(String label) {
        if (label == null) {
            return SectionType.CUSTOM;
        }
        // drop a "(1/2)" part and the number: "Verse 2 (1/2)" → "verse", "Chorus 1b" → "chorus"
        String words = label.replaceAll("\\(.*\\)", "").replaceAll("[0-9]+[a-zA-Z]?\\s*$", "").strip()
                .toLowerCase(Locale.ROOT);
        return SectionType.fromKeyword(words).orElse(SectionType.CUSTOM);
    }
}
