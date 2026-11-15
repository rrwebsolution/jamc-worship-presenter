package com.jamc.worship.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Turns a finished recording into a normal MP4 that every player opens.
 *
 * <p>Recordings are written as fragmented MP4 so a crash or power cut still leaves a playable file. Windows Media
 * Player and some editors do not play that format well (no length, will not start or seek), so once the recording
 * has stopped the file is copied into a regular MP4 with its index at the front ({@code -c copy}: no re-encoding, no
 * quality change, about a second). The original is replaced only when that worked; otherwise it is kept as it was.
 */
public final class RecordingFinisher {

    private static final Logger log = LoggerFactory.getLogger(RecordingFinisher.class);

    private RecordingFinisher() {
    }

    /** Converts {@code file} in place on a background thread; {@code done} runs afterwards (on that thread). */
    public static void finishLater(Path ffmpeg, Path file, Runnable done) {
        Thread.ofVirtual().name("recording-finish").start(() -> {
            try {
                finish(ffmpeg, file);
            } finally {
                if (done != null) {
                    done.run();
                }
            }
        });
    }

    /** True when the file is now a regular MP4; false leaves the original untouched. */
    public static boolean finish(Path ffmpeg, Path file) {
        if (ffmpeg == null || file == null || !Files.isRegularFile(file)) {
            return false;
        }
        String name = file.getFileName().toString();
        Path tmp = file.resolveSibling(name.replaceAll("(?i)\\.mp4$", "") + ".finishing.mp4");
        try {
            Process p = new ProcessBuilder(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
                    "-i", file.toString(), "-map", "0", "-c", "copy", "-movflags", "+faststart", tmp.toString()))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                log.warn("Recording {} kept as recorded: making a normal MP4 took too long", name);
                return false;
            }
            if (p.exitValue() != 0 || !Files.isRegularFile(tmp) || Files.size(tmp) == 0) {
                log.warn("Recording {} kept as recorded: FFmpeg could not make a normal MP4 (exit {})", name, p.exitValue());
                return false;
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Recording {} finished as a normal MP4", name);
            return true;
        } catch (Exception e) {
            log.warn("Recording {} kept as recorded: {}", name, e.toString());
            return false;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // a leftover .finishing.mp4 is harmless
            }
        }
    }
}
