package com.jamc.worship.ui.theme;

import com.jamc.worship.model.SectionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SectionColorsTest {

    @Test
    void labelsAreReadAsTheirKindOfSection() {
        assertEquals(SectionType.VERSE, SectionColors.typeOf("Verse 2"));
        assertEquals(SectionType.VERSE, SectionColors.typeOf("VERSE 1 (1/2)"));
        assertEquals(SectionType.PRE_CHORUS, SectionColors.typeOf("Pre-Chorus"));
        assertEquals(SectionType.PRE_CHORUS, SectionColors.typeOf("Pre Chorus 2"));
        assertEquals(SectionType.CHORUS, SectionColors.typeOf("Chorus"));
        assertEquals(SectionType.BRIDGE, SectionColors.typeOf("Bridge"));
        assertEquals(SectionType.ENDING, SectionColors.typeOf("Ending"));
        assertEquals(SectionType.CUSTOM, SectionColors.typeOf("Tag"));
    }

    @Test
    void verseChorusAndBridgeLookDifferent() {
        assertNotEquals(SectionColors.of("Verse 1"), SectionColors.of("Chorus"));
        assertNotEquals(SectionColors.of("Chorus"), SectionColors.of("Bridge"));
        assertNotEquals(SectionColors.of("Verse 1"), SectionColors.of("Pre-Chorus"));
        assertEquals(SectionColors.of("Verse 1"), SectionColors.of("Verse 3"));
    }
}
