package com.jamc.worship.camera;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IriunProbeTest {

    private static final int SIZE = 64 * 36;

    /** n pictures of one brightness, with a small bright spot (the cat) in the middle of each. */
    private static byte[] frames(int n, int brightness, boolean moving) {
        byte[] b = new byte[n * SIZE];
        Arrays.fill(b, (byte) brightness);
        for (int f = 0; f < n; f++) {
            for (int i = 0; i < 40; i++) {
                b[f * SIZE + SIZE / 2 + i] = (byte) 200;
            }
            if (moving) {
                for (int i = 0; i < SIZE; i += 3) {
                    b[f * SIZE + i] = (byte) (brightness + (f * 7 + i) % 20);
                }
            }
        }
        return b;
    }

    @Test
    void iriunsLookingForThePhonePictureIsRecognised() {
        assertTrue(IriunProbe.looksWaiting(frames(33, 0, false), 2.0), "pure black, still, a small cat");
    }

    @Test
    void aConnectedPhoneIsKept() {
        assertFalse(IriunProbe.looksWaiting(frames(60, 120, true), 2.0), "a real picture");
        assertFalse(IriunProbe.looksWaiting(frames(60, 2, true), 2.0), "a dark room still moves");
    }

    @Test
    void aCameraInUseIsKept() {
        assertFalse(IriunProbe.looksWaiting(new byte[0], 2.0), "no pictures: busy in LiveCat, so it has a phone");
    }

    @Test
    void onlyIriunCamerasAreChecked() {
        assertTrue(IriunProbe.isIriun("Iriun Webcam #3"));
        assertFalse(IriunProbe.isIriun("Integrated Camera"));
    }
}
