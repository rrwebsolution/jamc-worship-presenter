package com.jamc.worship.stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingFinisherTest {

    @Test
    void notAnMp4IsNotFragmented(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("x.mp4");
        Files.write(f, new byte[]{1, 2, 3});
        assertFalse(RecordingFinisher.isFragmented(f));
    }

    @Test
    void oldFragmentedRecordingBecomesNormal(@TempDir Path dir) throws Exception {
        Path ffmpeg = FFmpeg.locate(null);
        Assumptions.assumeTrue(ffmpeg != null, "FFmpeg not installed");
        Path f = dir.resolve("Service.mp4");
        Process p = new ProcessBuilder(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=160x90:rate=10", "-t", "2", "-c:v", "libx264",
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", f.toString()))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0);
        assertTrue(RecordingFinisher.isFragmented(f));

        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - 600_000));
        RecordingFinisher.finishOldLater(ffmpeg, dir, null);
        long until = System.currentTimeMillis() + 30_000;
        while (RecordingFinisher.isFragmented(f) && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        assertFalse(RecordingFinisher.isFragmented(f));
        assertTrue(Files.size(f) > 1000);
        assertFalse(Files.exists(dir.resolve("Service.finishing.mp4")));
    }
}
