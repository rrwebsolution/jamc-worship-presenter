package com.jamc.worship.stream;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Livestream foundation: providers, secret storage, the FFmpeg command and FFmpeg output parsing. */
class StreamFoundationTest {

    private static final String KEY = "FB-1234567890-0-AbCdEfGh";

    @TempDir
    Path tempDir;

    private static StreamSettings settings(String mic, String aux, boolean micMuted) {
        return new StreamSettings("FACEBOOK", null, "JAMC Tagoloan", 1920, 1080, 30, 6000, 160,
                StreamSettings.EncoderChoice.AUTO, mic, 80, micMuted, aux, 60, false, 100, false, null, 100, false);
    }

    @Test
    void providersBuildTheIngestUrlAndExplainWhatIsMissing() {
        StreamingProvider fb = StreamingProviders.FACEBOOK;
        assertEquals("rtmps://live-api-s.facebook.com:443/rtmp/" + KEY, fb.ingestUrl(null, KEY));
        assertTrue(fb.validate(null, true).isEmpty(), "Facebook has a default server");
        assertEquals(List.of("Enter the stream key."), fb.validate(null, false));
        StreamingProvider custom = StreamingProviders.CUSTOM_RTMP;
        assertFalse(custom.validate(null, true).isEmpty(), "a custom server must be entered");
        assertFalse(custom.validate("http://example.org/live", true).isEmpty());
        assertEquals("rtmp://example.org/live/abc", custom.ingestUrl("rtmp://example.org/live", "abc"));
        assertEquals(StreamingProviders.FACEBOOK, StreamingProviders.byId("nonsense"));
    }

    @Test
    void streamKeysAreStoredEncryptedAndShownMasked() throws Exception {
        Path file = tempDir.resolve("config/secrets.properties");
        SecretStore store = new SecretStore(file);
        store.put("stream.key.FACEBOOK", KEY);
        assertEquals(KEY, new SecretStore(file).get("stream.key.FACEBOOK"));
        String onDisk = Files.readString(file);
        assertFalse(onDisk.contains(KEY), "never plain text on disk");
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            assertTrue(store.isEncrypted());
            assertTrue(onDisk.contains("dpapi"), "stored with Windows DPAPI");
        }
        String masked = SecretStore.mask(KEY);
        assertFalse(masked.contains("AbCd"));
        assertTrue(masked.endsWith("EfGh") && masked.startsWith("•"));
        store.put("stream.key.FACEBOOK", null);
        assertNull(store.get("stream.key.FACEBOOK"));
    }

    @Test
    void programMediaAudioIsAThirdInputOfTheStreamMix() {
        StreamSettings s = settings("Microphone", "HDMI Capture", false);
        StreamSettings withMedia = new StreamSettings(s.providerId(), s.server(), s.destinationName(), s.width(), s.height(),
                s.fps(), s.videoKbps(), s.audioKbps(), s.encoder(), s.micDevice(), s.micVolume(), s.micMuted(), s.auxDevice(),
                s.auxVolume(), s.auxMuted(), s.masterVolume(), s.masterMuted(), "CABLE Output (VB-Audio Virtual Cable)", 90, false);
        String line = String.join(" ", FFmpegStreamEncoder.command("ffmpeg", withMedia, "libx264",
                FFmpegStreamEncoder.Target.STREAM, "rtmps://example/key"));
        assertTrue(line.contains("audio=CABLE Output (VB-Audio Virtual Cable)"), "video sound reaches the livestream");
        assertTrue(line.contains("[3:a]volume=0.90") && line.contains("[a1][a2][a3]amix=inputs=3"), line);

        // muted media: video sound stays out of the stream, the other inputs stay
        StreamSettings muted = new StreamSettings(s.providerId(), s.server(), s.destinationName(), s.width(), s.height(),
                s.fps(), s.videoKbps(), s.audioKbps(), s.encoder(), s.micDevice(), s.micVolume(), s.micMuted(), s.auxDevice(),
                s.auxVolume(), s.auxMuted(), s.masterVolume(), s.masterMuted(), "Stereo Mix", 100, true);
        String mutedLine = String.join(" ", FFmpegStreamEncoder.command("ffmpeg", muted, "libx264",
                FFmpegStreamEncoder.Target.STREAM, "rtmps://example/key"));
        assertFalse(mutedLine.contains("audio=Stereo Mix"));
        assertTrue(mutedLine.contains("amix=inputs=2"));
    }

    @Test
    void recordingCommandMixesTheChosenInputsIntoFragmentedMp4() {
        List<String> cmd = FFmpegStreamEncoder.command("ffmpeg", settings("Microphone (Iriun Webcam)", "Stereo Mix", false),
                "libx264", FFmpegStreamEncoder.Target.RECORDING, "C:/rec/Service.mp4");
        String line = String.join(" ", cmd);
        assertTrue(line.contains("-f rawvideo -pix_fmt bgr24 -video_size 1920x1080 -framerate 30 -i pipe:0"));
        assertTrue(cmd.contains("audio=Microphone (Iriun Webcam)"));
        assertTrue(cmd.contains("audio=Stereo Mix"));
        assertTrue(line.contains("[1:a]volume=0.80") && line.contains("[2:a]volume=0.60") && line.contains("amix=inputs=2"));
        assertTrue(line.contains("-c:v libx264") && line.contains("-b:v 6000k") && line.contains("-c:a aac -b:a 160k"));
        assertTrue(line.contains("+frag_keyframe+empty_moov") && cmd.getLast().equals("C:/rec/Service.mp4"));
        assertFalse(cmd.contains("-shortest"), "STOP sends q instead: -shortest held back live audio inputs");

        // this app's own sound (Windows loopback) as the MEDIA input: raw PCM from a local port, at the media volume
        StreamSettings appSound = new StreamSettings("facebook", null, null, 1920, 1080, 30, 6000, 160,
                StreamSettings.EncoderChoice.AUTO, null, 80, false, null, 60, false, 100, false, StreamSettings.APP_AUDIO, 90, false);
        String withApp = String.join(" ", FFmpegStreamEncoder.command("ffmpeg", appSound, "libx264", FFmpegStreamEncoder.Target.RECORDING,
                "C:/rec/Service.mp4", new LoopbackAudio.Input("tcp://127.0.0.1:1234", "f32le", 48000, 2, "Speakers")));
        assertTrue(withApp.contains("-f f32le -ar 48000 -ac 2 -i tcp://127.0.0.1:1234"), withApp);
        assertTrue(withApp.contains("[1:a]volume=0.90"), withApp);
        assertFalse(String.join(" ", FFmpegStreamEncoder.command("ffmpeg", appSound, "libx264",
                FFmpegStreamEncoder.Target.RECORDING, "x.mp4")).contains("tcp://"), "no capture running: no app sound input");

        List<String> silent = FFmpegStreamEncoder.command("ffmpeg", settings("Mic", null, true), "h264_nvenc",
                FFmpegStreamEncoder.Target.RECORDING, "x.mp4");
        assertTrue(String.join(" ", silent).contains("anullsrc"), "a muted mic gives a silent track, not none");
        assertTrue(silent.contains("h264_nvenc"));
    }

    @Test
    void streamDestinationNeverAppearsInLoggedText() {
        String url = StreamingProviders.FACEBOOK.ingestUrl(null, KEY);
        FFmpegStreamEncoder enc = new FFmpegStreamEncoder(Path.of("ffmpeg"), settings(null, null, false), "libx264",
                FFmpegStreamEncoder.Target.STREAM, url, new StreamEncoder.Listener() {
            @Override
            public void stats(StreamEncoder.Stats stats) {
            }

            @Override
            public void ended(String error) {
            }
        });
        List<String> cmd = FFmpegStreamEncoder.command("ffmpeg", settings(null, null, false), "libx264",
                FFmpegStreamEncoder.Target.STREAM, url);
        assertEquals(url, cmd.getLast());
        assertTrue(cmd.contains("flv"));
        String logged = enc.redact(String.join(" ", cmd) + " | [flv] Failed to connect to " + url);
        assertFalse(logged.contains(KEY));
        assertTrue(logged.contains("rtmps://live-api-s.facebook.com:443/rtmp/•••"));
    }

    @Test
    void ffmpegOutputIsParsed() {
        String modern = """
                [in#0 @ 000001] "Integrated Camera" (video)
                [in#0 @ 000001]   Alternative name "@device_pnp_\\\\?\\usb#vid"
                [in#0 @ 000001] "Microphone (Iriun Webcam)" (audio)
                [in#0 @ 000001] "Microphone Array (Intel Smart Sound)" (audio)
                """;
        assertEquals(List.of("Microphone (Iriun Webcam)", "Microphone Array (Intel Smart Sound)"), FFmpeg.parseDevices(modern, "audio"));
        assertEquals(List.of("Integrated Camera"), FFmpeg.parseDevices(modern, "video"));
        String old = """
                [dshow @ 01] DirectShow video devices (some may be both video and audio devices)
                [dshow @ 01]  "Integrated Camera"
                [dshow @ 01] DirectShow audio devices
                [dshow @ 01]  "Stereo Mix (Realtek)"
                """;
        assertEquals(List.of("Stereo Mix (Realtek)"), FFmpeg.parseDevices(old, "audio"));

        FFmpegStreamEncoder.Progress p = new FFmpegStreamEncoder.Progress();
        for (String l : List.of("frame=900", "fps=30.01", "bitrate=5812.3kbits/s", "drop_frames=2")) {
            assertFalse(p.accept(l));
        }
        assertTrue(p.accept("progress=continue"));
        assertEquals(900, p.frame);
        assertEquals(5812.3, p.bitrateKbps, 0.01);
        assertEquals(2, p.dropFrames);
    }

    @Test
    void encoderChoiceFallsBackGracefully() {
        FFmpeg.Capabilities swOnly = new FFmpeg.Capabilities(Path.of("ffmpeg"), "7.1", true, List.of(), List.of(), List.of());
        assertEquals("libx264", swOnly.encoderFor(StreamSettings.EncoderChoice.HARDWARE), "no GPU encoder: software");
        FFmpeg.Capabilities nvidia = new FFmpeg.Capabilities(Path.of("ffmpeg"), "7.1", true, List.of("h264_nvenc"), List.of(), List.of());
        assertEquals("h264_nvenc", nvidia.encoderFor(StreamSettings.EncoderChoice.AUTO));
        assertEquals("libx264", nvidia.encoderFor(StreamSettings.EncoderChoice.SOFTWARE));
        assertNull(FFmpeg.Capabilities.none().encoderFor(StreamSettings.EncoderChoice.AUTO));
    }

    @Test
    void theFrameTapDeliversTheProgramPicture() throws Exception {
        com.jamc.worship.render.Rendering.install(new com.jamc.worship.render.SlideRenderer(new com.jamc.worship.render.ImageCache(null)));
        com.jamc.worship.output.ProgramOutput program = new com.jamc.worship.output.ProgramOutput();
        program.setTransition(com.jamc.worship.output.Transition.CUT, 0);
        com.jamc.worship.model.SlideStyle blue = new com.jamc.worship.model.SlideStyle(com.jamc.worship.model.BackgroundType.COLOR,
                "#0000FF", "#0000FF", 0, "Dialog", 72, true, false, "#FFFFFF", com.jamc.worship.model.TextAlign.CENTER,
                com.jamc.worship.model.TextPosition.MIDDLE, 1.1, false, false, "#000000", 4);
        ProgramFrameTap tap = new ProgramFrameTap(program);
        java.util.concurrent.CompletableFuture<byte[]> first = new java.util.concurrent.CompletableFuture<>();
        ProgramFrameTap.Sink sink = bgr -> first.complete(bgr.clone());
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            program.take(com.jamc.worship.output.LiveContent.snapshot(new com.jamc.worship.model.Slide("V", "", null), blue, "t"));
            tap.addSink(sink, 320, 180, 30);
        });
        byte[] frame = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        javax.swing.SwingUtilities.invokeAndWait(() -> tap.removeSink(sink));
        assertEquals(320 * 180 * 3, frame.length, "packed BGR, as FFmpeg reads it");
        int corner = 0; // first pixel: B, G, R
        assertEquals(255, frame[corner] & 0xFF, "blue background from Program");
        assertEquals(0, frame[corner + 2] & 0xFF);
        assertFalse(tap.isRunning(), "no sinks: the tap stops painting");
    }

    @Test
    void settingsAndPresetsAreRemembered() {
        AppPaths paths = new AppPaths(tempDir.resolve("home"));
        paths.ensureDirectories();
        AppConfig config = new AppConfig(paths);
        StreamSettings s = StreamSettings.load(config);
        assertEquals(StreamSettings.Preset.P1080_30, s.preset(), "1080p 30 FPS by default");
        assertEquals(StreamingProviders.FACEBOOK, s.provider());
        s.withPreset(StreamSettings.Preset.P720_30).save(config);
        AppConfig again = new AppConfig(paths);
        again.load();
        StreamSettings back = StreamSettings.load(again);
        assertEquals(StreamSettings.Preset.P720_30, back.preset());
        assertEquals(4000, back.videoKbps());
        assertFalse(Files.exists(paths.settingsFile()) && readQuietly(paths.settingsFile()).contains("stream.key"),
                "the stream key is never in settings.properties");
    }

    @Test
    void pastedKeysBecomeAWorkingIngestUrl() {
        AppPaths paths = new AppPaths(tempDir.resolve("live"));
        paths.ensureDirectories();
        StreamManager m = new StreamManager(new AppConfig(paths), paths.root(), new com.jamc.worship.output.ProgramOutput(),
                new SecretStore(tempDir.resolve("live/secrets.properties")), List::of);
        StreamSettings s = settings(null, null, false);
        assertNull(m.ingestUrl(s), "no key yet");
        m.setStreamKey(StreamingProviders.FACEBOOK, "  " + KEY + "\r\n");
        assertEquals("rtmps://live-api-s.facebook.com:443/rtmp/" + KEY, m.ingestUrl(s), "spaces / line breaks from copying removed");
        m.setStreamKey(StreamingProviders.FACEBOOK, "rtmps://live-api-s.facebook.com:443/rtmp/" + KEY + "?s_bl=1&a=b");
        assertEquals("rtmps://live-api-s.facebook.com:443/rtmp/" + KEY + "?s_bl=1&a=b", m.ingestUrl(s),
                "server + key pasted together is used as it is");

        List<String> cmd = FFmpegStreamEncoder.command("ffmpeg", s, "libx264", FFmpegStreamEncoder.Target.STREAM, "rtmps://x/k");
        assertEquals(List.of("-flvflags", "no_duration_filesize", "-f", "flv", "rtmps://x/k"), cmd.subList(cmd.size() - 5, cmd.size()));
        assertTrue(StreamManager.explainStreamError("rtmps://x/•••: I/O error").contains("stream key"));
    }

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return "";
        }
    }
}
