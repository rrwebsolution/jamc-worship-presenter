package com.jamc.worship.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvSyncTest {

    private static final int RATE = 48000;
    private static final long MS = 1_000_000L;

    @Test
    void delayChangesOnlyAtQuietMomentsInSmallSteps() {
        long[] target = {300};
        SyncShift s = new SyncShift(RATE, () -> target[0]);
        assertEquals(0, s.change(false, 1 * MS)); // speaking: waits
        long step = s.change(true, 2 * MS);
        assertEquals(RATE * SyncShift.STEP_MS / 1000, step);
        s.applied(step);
        assertEquals(step, s.frames());
    }

    @Test
    void forcedWhenItNeverGoesQuiet() {
        SyncShift s = new SyncShift(RATE, () -> 300);
        assertEquals(0, s.change(false, 1 * MS));
        assertEquals(RATE * SyncShift.STEP_MS / 1000, s.change(false, (2 + SyncShift.FORCE_AFTER_MS) * MS));
    }

    @Test
    void comesBackEarlierWhenThePictureCatchesUp() {
        long[] target = {100};
        SyncShift s = new SyncShift(RATE, () -> target[0]);
        for (int i = 0; i < 10; i++) {
            s.applied(s.change(true, i * MS));
        }
        // within the dead band of 100 ms
        org.junit.jupiter.api.Assertions.assertTrue(s.frames() > RATE * (100 - SyncShift.DEADBAND_MS) / 1000);
        target[0] = 0;
        long step = s.change(true, 20 * MS);
        assertEquals(-RATE * SyncShift.STEP_MS / 1000, step);
    }

    @Test
    void smallDifferencesAreLeftAlone() {
        SyncShift s = new SyncShift(RATE, () -> SyncShift.DEADBAND_MS - 1);
        assertEquals(0, s.change(true, MS));
    }

    @Test
    void lagIsTheFreshestPictureOfTheSecondAndRepeatsAreIgnored() {
        long t = 10_000 * MS;
        AvSync.endFrame(t); // starts a window
        AvSync.beginFrame();
        AvSync.noteFrame(t + 100 * MS);
        AvSync.endFrame(t + 400 * MS); // 300 ms old
        AvSync.beginFrame();
        AvSync.noteFrame(t + 500 * MS);
        AvSync.endFrame(t + 650 * MS); // 150 ms old
        AvSync.beginFrame();
        AvSync.noteFrame(t + 500 * MS); // the same picture again, now 450 ms old: not lag
        AvSync.endFrame(t + 950 * MS);
        AvSync.beginFrame();
        AvSync.endFrame(t + 1_001 * MS); // no picture: ends the second
        assertEquals(150, AvSync.pictureLagMs());
        assertEquals(AvSync.MIC_EXTRA_MS + 150, AvSync.micDelayMs());
    }
}
