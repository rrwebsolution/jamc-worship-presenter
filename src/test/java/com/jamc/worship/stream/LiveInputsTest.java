package com.jamc.worship.stream;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveInputsTest {

    @Test
    void readingWaitsForTheLeadThenFollowsTheInput() {
        assertEquals(-1, LiveInputs.next(-1, LiveInputs.LEAD_FRAMES - 1));
        assertEquals(0, LiveInputs.next(-1, LiveInputs.LEAD_FRAMES));
        assertEquals(500, LiveInputs.next(500, 500 + LiveInputs.LEAD_FRAMES));
    }

    @Test
    void fallingTooFarBehindJumpsBackToTheLead() {
        long written = 100_000;
        assertEquals(written - LiveInputs.LEAD_FRAMES, LiveInputs.next(written - LiveInputs.MAX_BEHIND_FRAMES - 1, written));
    }

    @Test
    void reconnectingStartsFastAndAHealthyStreamStartsCountingAgain() {
        assertEquals(1000, StreamManager.reconnectDelayMillis(1));
        assertEquals(2000, StreamManager.reconnectDelayMillis(2));
        assertEquals(8000, StreamManager.reconnectDelayMillis(StreamManager.MAX_RETRIES));
        assertEquals(0, StreamManager.retriesAfter(4, 60_000));
        assertEquals(4, StreamManager.retriesAfter(4, 5_000));
    }

    @Test
    void anInvalidSoundSampleBecomesSilenceAndTheOthersStay() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putFloat(0.5f).putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(-0.25f);
        LoopbackAudio.silenceInvalid(b.array(), 16);
        assertEquals(0.5f, b.getFloat(0));
        assertEquals(0f, b.getFloat(4));
        assertEquals(0f, b.getFloat(8));
        assertEquals(-0.25f, b.getFloat(12));
    }

    @Test
    void appSoundFarAheadOfTheClockIsDropped() {
        assertTrue(LoopbackAudio.keepsTime(48_000, 480, 48_000));
        assertTrue(LoopbackAudio.keepsTime(48_000, 1_920, 48_000), "40 ms ahead is the limit");
        assertFalse(LoopbackAudio.keepsTime(48_000, 1_921, 48_000));
    }

    @Test
    void aChannelWithoutAnInputIsNeverALiveInput() {
        assertTrue(StreamManager.isLiveInput(java.util.Map.of("Mixer", 1.0), "Mixer"));
        assertFalse(StreamManager.isLiveInput(java.util.Map.of("Mixer", 1.0), null));
    }

    @Test
    void aMicrophoneCanBeMutedAloneInTheRunningEncoder() {
        assertTrue(FFmpegStreamEncoder.micCommand("aux", 0).startsWith("cvolume@aux -1 volume 0.00"));
        assertEquals(List.of("cvolume@mic -1 volume 0.80\n"), List.of(FFmpegStreamEncoder.micCommand("mic", 0.8)));
    }
}
