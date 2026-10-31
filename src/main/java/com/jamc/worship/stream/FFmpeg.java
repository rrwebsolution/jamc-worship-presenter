package com.jamc.worship.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds FFmpeg and asks it what it can do (H.264 encoders that really work on this computer, audio
 * capture devices). FFmpeg is a separate program the church installs (e.g. {@code winget install
 * Gyan.FFmpeg}); nothing here encodes video itself. Blocking: call from background threads.
 */
public final class FFmpeg {

    private static final Logger log = LoggerFactory.getLogger(FFmpeg.class);

    /** Hardware H.264 encoders, best first: NVIDIA, Intel Quick Sync, AMD, Windows Media Foundation. */
    public static final List<String> HARDWARE_ENCODERS = List.of("h264_nvenc", "h264_qsv", "h264_amf", "h264_mf");
    public static final String SOFTWARE_ENCODER = "libx264";

    /** What the installed FFmpeg offers. {@code workingHardware} were test-encoded successfully. */
    public record Capabilities(Path executable, String version, boolean software, List<String> workingHardware,
                               List<String> audioDevices, List<String> videoDevices) {

        public boolean available() {
            return executable != null;
        }

        /** The encoder for a choice, falling back to software when no hardware encoder works (and the other way). */
        public String encoderFor(StreamSettings.EncoderChoice choice) {
            String hw = workingHardware.isEmpty() ? null : workingHardware.getFirst();
            return switch (choice) {
                case SOFTWARE -> software ? SOFTWARE_ENCODER : hw;
                case HARDWARE, AUTO -> hw != null ? hw : software ? SOFTWARE_ENCODER : null;
            };
        }

        public static Capabilities none() {
            return new Capabilities(null, null, false, List.of(), List.of(), List.of());
        }
    }

    private FFmpeg() {
    }

    /**
     * Looks for ffmpeg.exe: the configured path, PATH, the winget install folders (found even when this
     * app was started before FFmpeg was installed), then common folders.
     */
    public static Path locate(String configured) {
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.strip());
            candidates.add(Files.isDirectory(p) ? p.resolve(exe()) : p);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    candidates.add(Path.of(dir.strip().replace("\"", "")).resolve(exe()));
                }
            }
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            candidates.add(Path.of(local, "Microsoft", "WinGet", "Links", exe()));
            Path packages = Path.of(local, "Microsoft", "WinGet", "Packages");
            if (Files.isDirectory(packages)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(packages, "*FFmpeg*")) {
                    for (Path pkg : ds) {
                        try (DirectoryStream<Path> inner = Files.newDirectoryStream(pkg)) {
                            for (Path build : inner) {
                                candidates.add(build.resolve("bin").resolve(exe()));
                            }
                        }
                    }
                } catch (IOException e) {
                    log.debug("Could not search winget packages", e);
                }
            }
        }
        candidates.add(Path.of("C:\\ffmpeg\\bin", exe()));
        candidates.add(Path.of("C:\\Program Files\\ffmpeg\\bin", exe()));
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }

    /** Version, encoders that actually work here (a short test encode), and capture devices. */
    public static Capabilities probe(Path exe) {
        if (exe == null) {
            return Capabilities.none();
        }
        String version = firstLine(run(exe, 10, "-hide_banner", "-version"));
        String encoders = run(exe, 10, "-hide_banner", "-encoders");
        boolean software = encoders.contains(" " + SOFTWARE_ENCODER + " ");
        List<String> hardware = new ArrayList<>();
        for (String enc : HARDWARE_ENCODERS) {
            if (encoders.contains(" " + enc + " ") && testEncode(exe, enc)) {
                hardware.add(enc);
            }
        }
        String devices = run(exe, 10, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
        Capabilities caps = new Capabilities(exe, version, software, List.copyOf(hardware),
                parseDevices(devices, "audio"), parseDevices(devices, "video"));
        log.info("FFmpeg {}: software={}, hardware={}, audio devices={}", version, software, hardware, caps.audioDevices().size());
        return caps;
    }

    /** DirectShow device names of a type ("audio" / "video") from {@code -list_devices} output (old and new formats). */
    static List<String> parseDevices(String output, String type) {
        Set<String> out = new LinkedHashSet<>();
        Pattern tagged = Pattern.compile("\"([^\"]+)\"\\s+\\((audio|video|none)[^)]*\\)");
        Matcher m = tagged.matcher(output);
        while (m.find()) {
            if (m.group(2).equals(type)) {
                out.add(m.group(1));
            }
        }
        if (out.isEmpty()) { // older FFmpeg: sections "DirectShow video devices" / "DirectShow audio devices"
            String section = null;
            for (String line : output.split("\\R")) {
                if (line.contains("DirectShow video devices")) {
                    section = "video";
                } else if (line.contains("DirectShow audio devices")) {
                    section = "audio";
                } else if (type.equals(section) && !line.contains("Alternative name")) {
                    Matcher q = Pattern.compile("\\]\\s+\"([^\"]+)\"").matcher(line);
                    if (q.find()) {
                        out.add(q.group(1));
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean testEncode(Path exe, String encoder) {
        Process p = null;
        try {
            p = new ProcessBuilder(exe.toString(), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                    "color=c=black:s=640x360:r=30:d=0.5", "-c:v", encoder, "-f", "null", "-")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static String run(Path exe, int seconds, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(List.of(args));
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(seconds, TimeUnit.SECONDS);
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not run FFmpeg: {}", e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static String firstLine(String s) {
        String line = s.lines().findFirst().orElse("").strip();
        return line.startsWith("ffmpeg version ") ? line.substring("ffmpeg version ".length()).split(" ")[0] : line;
    }

    private static String exe() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "ffmpeg.exe" : "ffmpeg";
    }
}
