package com.jamc.worship.stream;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MacDevicesTest {

    static final String LIST = """
            [AVFoundation indev @ 0x7f8] AVFoundation video devices:
            [AVFoundation indev @ 0x7f8] [0] FaceTime HD Camera
            [AVFoundation indev @ 0x7f8] [1] Iriun Webcam
            [AVFoundation indev @ 0x7f8] [2] Capture screen 0
            [AVFoundation indev @ 0x7f8] AVFoundation audio devices:
            [AVFoundation indev @ 0x7f8] [0] MacBook Pro Microphone
            [AVFoundation indev @ 0x7f8] [1] BlackHole 2ch
            : Input/output error
            """;

    @Test
    void camerasAndMicrophonesAreReadButNotTheScreens() {
        assertEquals(List.of("FaceTime HD Camera", "Iriun Webcam"), FFmpeg.parseAvFoundation(LIST, "video"));
        assertEquals(List.of("MacBook Pro Microphone", "BlackHole 2ch"), FFmpeg.parseAvFoundation(LIST, "audio"));
    }
}
