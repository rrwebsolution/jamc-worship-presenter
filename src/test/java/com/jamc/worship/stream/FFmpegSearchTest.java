package com.jamc.worship.stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FFmpegSearchTest {

    @Test
    void aBadPathEntryIsSkippedNotFatal() {
        assertNull(FFmpeg.pathOrNull("bad\0path"));
        assertNotNull(FFmpeg.pathOrNull("ffmpeg"));
    }

    @Test
    void aHangingProgramIsEndedInTime() {
        Path ping = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "System32", "ping.exe");
        Assumptions.assumeTrue(Files.isRegularFile(ping), "Windows only");
        long start = System.nanoTime();
        String out = FFmpeg.run(ping, 1, "-n", "30", "127.0.0.1");
        assertTrue((System.nanoTime() - start) / 1_000_000_000L < 6, "ended after about a second");
        assertTrue(out.contains("127.0.0.1"), "what it printed so far is kept");
    }

    @Test
    void aMissingEncoderIsNotReportedAsMissingFFmpeg() {
        assertEquals(StreamManager.ffmpegMissingMessage(), StreamManager.ffmpegProblem(null));
        assertEquals(StreamManager.ffmpegMissingMessage(), StreamManager.ffmpegProblem(FFmpeg.Capabilities.none()));
        FFmpeg.Capabilities noEncoder = new FFmpeg.Capabilities(Path.of("ffmpeg"), "9", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        assertTrue(StreamManager.ffmpegProblem(noEncoder).startsWith("FFmpeg was found"));
    }
}
