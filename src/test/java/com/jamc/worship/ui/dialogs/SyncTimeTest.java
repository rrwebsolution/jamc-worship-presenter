package com.jamc.worship.ui.dialogs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SyncTimeTest {

    @Test
    void typedTimestamps() {
        assertEquals(15_200L, SyncEditorDialog.parse("00:15.200"));
        assertEquals(15_200L, SyncEditorDialog.parse("0:15.2"));
        assertEquals(83_500L, SyncEditorDialog.parse("1:23.5"));
        assertEquals(3_723_040L, SyncEditorDialog.parse("1:02:03.04"));
        assertEquals(15_200L, SyncEditorDialog.parse("15.2"));
        assertNull(SyncEditorDialog.parse("soon"));
        assertEquals("00:15.200", SyncEditorDialog.format(15_200));
        assertEquals("01:23.500", SyncEditorDialog.format(83_500));
    }
}
