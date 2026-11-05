package com.jamc.worship.stream;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.output.ProgramOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Recording and livestreaming of Program. Program is the only source: the frames come from
 * {@link ProgramFrameTap}, so viewers and recordings always match the Program monitor.
 *
 * <p>Recording and streaming are independent sessions with their own encoder. Encoder failures stay
 * here: the presentation (Program, projector, songs, Bible, PowerPoint) keeps working. Public methods
 * are called on the Swing thread; slow work runs on background threads and listeners are notified on
 * the Swing thread.
 */
public final class StreamManager {

    private static final Logger log = LoggerFactory.getLogger(StreamManager.class);
    public static final String KEY_FFMPEG_PATH = "stream.ffmpegPath";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");

    public enum State { IDLE, STARTING, ACTIVE, ERROR }

    /** What a session is doing, for the panel and the top bar. */
    public record Session(State state, String detail, StreamEncoder.Stats stats, long startedMillis, Path file) {
        static Session idle() {
            return new Session(State.IDLE, null, null, 0, null);
        }

        public boolean active() {
            return state == State.ACTIVE || state == State.STARTING;
        }

        /** STOP was pressed: FFmpeg is closing the file (a moment). */
        public boolean finishing() {
            return state == State.STARTING && FINISHING.equals(detail);
        }
    }

    /** One line of the pre-stream check. */
    public record Check(String label, Level level, String detail) {
        public enum Level { OK, WARNING, PROBLEM }
    }

    private final AppConfig config;
    private final Path defaultRecordingsDir;
    static final String FINISHING = "Finishing the file…";
    /** Settings → Stream: the folder recordings are saved in (empty = the default folder). */
    public static final String KEY_RECORDINGS_DIR = "stream.recordingsDir";
    private final SecretStore secrets;
    private final Supplier<List<String>> cameras;
    private final ProgramFrameTap tap;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile FFmpeg.Capabilities capabilities;
    private boolean probing;
    private FFmpegStreamEncoder recorder;
    /** This app's own sound while recording (MEDIA = {@link StreamSettings#APP_AUDIO}). */
    private LoopbackAudio loopback;
    private final Path toolsDir;
    private Session recording = Session.idle();
    private FFmpegStreamEncoder streamer;
    private LoopbackAudio liveLoopback;
    private Session live = Session.idle();
    private int liveRetries;
    private long liveConnectedMillis;

    public StreamManager(AppConfig config, Path dataRoot, ProgramOutput program, SecretStore secrets,
                         Supplier<List<String>> cameras) {
        this.config = config;
        this.defaultRecordingsDir = dataRoot.resolve("recordings");
        this.toolsDir = dataRoot.resolve("tools");
        this.secrets = secrets;
        this.cameras = cameras;
        this.tap = new ProgramFrameTap(program);
    }

    /** True while a recording or the livestream takes this app's own sound: everything the app plays goes into it. */
    public boolean capturingAppSound() {
        return (recording.active() || live.active()) && settings().usesAppAudio();
    }

    // ------------------------------------------------------------------ microphones during videos

    /** Setting: mute MIC and AUX in recordings / the livestream while a video or music plays on Program [on]. */
    public static final String KEY_MUTE_MICS_FOR_MEDIA = "stream.muteMicsDuringMedia";
    /** Media must be silent this long before the microphones come back (no flapping between two videos). */
    private static final long MICS_BACK_AFTER_MS = 800;
    private java.util.function.BooleanSupplier programMediaPlaying = () -> false;
    private boolean micsMuted;
    private final LiveInputs liveInputs = new LiveInputs();
    private java.util.Map<String, Double> liveInputGains = java.util.Map.of();
    private long mediaLastHeard;
    private final javax.swing.Timer micWatch = new javax.swing.Timer(250, e -> updateMics());

    /** Whether a video or music is heard on Program now (fullscreen video, scene video, audio player). */
    public void setProgramMediaPlaying(java.util.function.BooleanSupplier playing) {
        programMediaPlaying = playing == null ? () -> false : playing;
        micWatch.start();
    }

    public boolean muteMicsDuringMedia() {
        return config.getBoolean(KEY_MUTE_MICS_FOR_MEDIA, true);
    }

    public void setMuteMicsDuringMedia(boolean on) {
        config.set(KEY_MUTE_MICS_FOR_MEDIA, String.valueOf(on));
        config.save();
        updateMics();
    }

    /** True while the microphones are muted in the recording / stream because media plays on Program. */
    public boolean micsMutedForMedia() {
        return micsMuted;
    }

    /**
     * While a video or music plays on Program, the room microphones (MIC, AUX) are muted in the recording and the
     * livestream, so people talking are not mixed into the video's sound; they come back when it stops.
     */
    private void updateMics() {
        long now = System.currentTimeMillis();
        boolean encoding = recorder != null || streamer != null;
        boolean media = encoding && muteMicsDuringMedia() && programMediaPlaying.getAsBoolean();
        if (media) {
            mediaLastHeard = now;
        }
        boolean want = media || (micsMuted && encoding && muteMicsDuringMedia() && now - mediaLastHeard < MICS_BACK_AFTER_MS);
        if (want == micsMuted) {
            return;
        }
        micsMuted = want;
        applyMics();
        log.info(want ? "Microphones muted in the recording / stream: a video or music plays on Program"
                : "Microphones back in the recording / stream");
        fire();
    }

    /** MASTER silences everything while the other channels' waves keep moving, so it is easily missed until playback. */
    private static void warnIfMasterMuted(StreamSettings s, Consumer<String> onError, String what) {
        if (s.masterMuted()) {
            onError.accept("MASTER is muted in the Mixer, so this " + what + " has no sound. Press MIXER and unmute MASTER.");
        }
    }

    private void applyMics() {
        for (FFmpegStreamEncoder enc : new FFmpegStreamEncoder[]{recorder, streamer}) {
            if (enc != null) {
                sendMics(enc);
            }
        }
    }

    private void sendMics(FFmpegStreamEncoder enc) {
        StreamSettings s = settings();
        enc.setMicsMuted(micsMuted || isLiveInput(liveInputGains, s.micDevice()), micsMuted || isLiveInput(liveInputGains, s.auxDevice()));
    }

    /** {@code device} is null when the channel has no input chosen. */
    static boolean isLiveInput(java.util.Map<String, Double> gains, String device) {
        return device != null && gains.containsKey(device);
    }

    /**
     * The Program scene's live sound inputs and their gains. A device that is also the mixer's MIC or AUX is heard
     * through the input only: both together would be the same sound twice, which is an echo.
     */
    public void setLiveInputs(java.util.Map<String, Double> gains) {
        liveInputGains = java.util.Map.copyOf(gains);
        liveInputs.set(gains);
        applyMics();
    }

    private void mixLiveInputs(LoopbackAudio audio, java.nio.file.Path ffmpeg) {
        audio.mixWith(liveInputs.attach(ffmpeg));
    }

    /** A new encoder starts with the microphones at their levels: sends the current state once FFmpeg runs. */
    private void micsForNewEncoder(FFmpegStreamEncoder enc) {
        for (int delay : new int[]{1500, 5000}) {
            javax.swing.Timer t = new javax.swing.Timer(delay, e -> {
                if (enc == recorder || enc == streamer) {
                    sendMics(enc);
                }
            });
            t.setRepeats(false);
            t.start();
        }
    }

    /**
     * Starts listening to an input for a mixer meter: a microphone / capture input by name, or
     * {@link StreamSettings#APP_AUDIO} for this app's own sound. Blocking: call off the Swing thread.
     */
    public AudioLevels.Source openLevel(String device) throws IOException {
        return StreamSettings.APP_AUDIO.equals(device) ? AudioLevels.appSound(toolsDir)
                : AudioLevels.device(device);
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    /** Where recordings are saved: the folder chosen in the Stream settings, else the default folder. */
    public Path recordingsDir() {
        String chosen = config.get(KEY_RECORDINGS_DIR, null);
        if (chosen != null && !chosen.isBlank()) {
            try {
                return Path.of(chosen);
            } catch (java.nio.file.InvalidPathException e) {
                log.warn("Recordings folder setting is not a valid path: {}", chosen);
            }
        }
        return defaultRecordingsDir;
    }

    public Path defaultRecordingsDir() {
        return defaultRecordingsDir;
    }

    /** Saves recordings in {@code folder} from the next recording on (null = the default folder). */
    public void setRecordingsDir(Path folder) {
        config.set(KEY_RECORDINGS_DIR, folder == null ? "" : folder.toAbsolutePath().toString());
        config.save();
        fire();
    }

    public SecretStore secrets() {
        return secrets;
    }

    // ------------------------------------------------------------------ settings and key

    public StreamSettings settings() {
        return StreamSettings.load(config);
    }

    public void saveSettings(StreamSettings s) {
        s.save(config);
        fire();
    }

    private static String keyName(StreamingProvider p) {
        return "stream.key." + p.id();
    }

    public boolean hasStreamKey(StreamingProvider p) {
        return secrets.has(keyName(p));
    }

    /** The stored key, for "Show" only. */
    public String streamKey(StreamingProvider p) {
        return secrets.get(keyName(p));
    }

    public void setStreamKey(StreamingProvider p, String key) {
        secrets.put(keyName(p), key);
        log.info("Stream key for {} {}", p.displayName(), key == null || key.isBlank() ? "removed" : "updated"); // never the key
        fire();
    }

    // ------------------------------------------------------------------ FFmpeg

    /** Last probe result (null until the first probe finished). */
    public FFmpeg.Capabilities capabilities() {
        return capabilities;
    }

    public boolean isProbing() {
        return probing;
    }

    /** Looks for FFmpeg and tests its encoders and devices in the background. */
    public void refreshCapabilities() {
        if (probing) {
            return;
        }
        probing = true;
        fire();
        String configured = config.get(KEY_FFMPEG_PATH, null);
        Thread.ofVirtual().name("ffmpeg-probe").start(() -> {
            FFmpeg.Capabilities caps;
            try {
                caps = FFmpeg.probe(FFmpeg.locate(configured));
            } catch (RuntimeException e) {
                log.warn("FFmpeg probe failed", e);
                caps = FFmpeg.Capabilities.none();
            }
            FFmpeg.Capabilities result = caps;
            SwingUtilities.invokeLater(() -> {
                capabilities = result;
                probing = false;
                fire();
            });
        });
    }

    public void setFFmpegPath(String path) {
        config.set(KEY_FFMPEG_PATH, path);
        config.save();
        refreshCapabilities();
    }

    // ------------------------------------------------------------------ recording

    public Session recording() {
        return recording;
    }

    /** Starts recording Program to recordings/. Problems are reported through {@code onError}. */
    public void startRecording(Consumer<String> onError) {
        if (recording.active()) {
            return;
        }
        FFmpeg.Capabilities caps = capabilities;
        StreamSettings s = settings();
        String encoder = caps == null ? null : caps.encoderFor(s.encoder());
        if (caps == null || !caps.available() || encoder == null) {
            onError.accept(ffmpegMissingMessage());
            return;
        }
        Path folder = recordingsDir();
        Path file = folder.resolve("Service " + LocalDateTime.now().format(FILE_TIME) + ".mp4");
        recording = new Session(State.STARTING, "Starting…", null, System.currentTimeMillis(), file);
        fire();
        FFmpegStreamEncoder enc = new FFmpegStreamEncoder(caps.executable(), s, encoder, FFmpegStreamEncoder.Target.RECORDING,
                file.toString(), new StreamEncoder.Listener() {
            @Override
            public void stats(StreamEncoder.Stats stats) {
                SwingUtilities.invokeLater(() -> {
                    if (recorder != null && recording.active()) {
                        recording = new Session(State.ACTIVE, null, stats, recording.startedMillis(), recording.file());
                        fire();
                    }
                });
            }

            @Override
            public void ended(String error) {
                SwingUtilities.invokeLater(() -> recordingEnded(error, onError));
            }
        });
        recorder = enc;
        warnIfMasterMuted(s, onError, "recording");
        Thread.ofVirtual().name("recording-start").start(() -> {
            try {
                Files.createDirectories(folder);
                if (s.usesAppAudio()) {
                    try {
                        LoopbackAudio a = LoopbackAudio.start(toolsDir);
                        mixLiveInputs(a, caps.executable());
                        enc.setAppAudio(a.input());
                        SwingUtilities.invokeLater(() -> {
                            if (recorder == enc) loopback = a; else a.close();
                        });
                    } catch (IOException e) {
                        log.warn("Recording without this app's sound: {}", e.getMessage());
                        SwingUtilities.invokeLater(() -> onError.accept("The sound of videos and music in this app is not "
                                + "recorded: " + e.getMessage()));
                    }
                }
                enc.start();
                SwingUtilities.invokeLater(() -> {
                    if (recorder == enc) {
                        micsForNewEncoder(enc);
                        tap.addSink(enc, s.width(), s.height(), s.fps());
                        log.info("Recording Program to {} ({}x{} {} fps, {})", file.getFileName(), s.width(), s.height(), s.fps(), encoder);
                    }
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> recordingEnded("FFmpeg could not be started: " + e.getMessage(), onError));
            }
        });
    }

    public void stopRecording() {
        FFmpegStreamEncoder enc = recorder;
        if (enc == null) {
            return;
        }
        tap.removeSink(enc);
        enc.stop();
        recording = new Session(State.STARTING, FINISHING, recording.stats(), recording.startedMillis(), recording.file());
        fire();
    }

    private void recordingEnded(String error, Consumer<String> onError) {
        if (loopback != null) {
            loopback.close();
            loopback = null;
        }
        if (recorder != null) {
            tap.removeSink(recorder);
        }
        recorder = null;
        Path file = recording.file();
        recording = error == null ? Session.idle() : new Session(State.ERROR, error, null, 0, file);
        if (error != null) {
            onError.accept("Recording stopped: " + error);
        } else if (file != null) {
            log.info("Recording saved: {}", file.getFileName());
        }
        fire();
    }

    // ------------------------------------------------------------------ livestream

    public Session live() {
        return live;
    }

    /**
     * Sends Program to the configured platform (e.g. Facebook Live). The confirmation happens in the panel
     * before this is called. Problems are reported through {@code onError}; a stream that drops after it
     * was running reconnects by itself a few times.
     */
    public void startStream(Consumer<String> onError) {
        if (live.active()) {
            return;
        }
        FFmpeg.Capabilities caps = capabilities;
        StreamSettings s = settings();
        String encoder = caps == null ? null : caps.encoderFor(s.encoder());
        if (caps == null || !caps.available() || encoder == null) {
            onError.accept(ffmpegMissingMessage());
            return;
        }
        String url = ingestUrl(s);
        if (url == null) {
            onError.accept("Enter the stream key first (Stream page → Destination → Change…).");
            return;
        }
        liveRetries = 0;
        live = new Session(State.STARTING, "Connecting…", null, System.currentTimeMillis(), null);
        fire();
        launchStream(s, caps, encoder, url, onError);
    }

    private void launchStream(StreamSettings s, FFmpeg.Capabilities caps, String encoder, String url, Consumer<String> onError) {
        long started = live.startedMillis();
        FFmpegStreamEncoder enc = new FFmpegStreamEncoder(caps.executable(), s, encoder, FFmpegStreamEncoder.Target.STREAM,
                url, new StreamEncoder.Listener() {
            @Override
            public void stats(StreamEncoder.Stats stats) {
                SwingUtilities.invokeLater(() -> {
                    if (streamer != null && live.active() && !live.finishing() && stats.frames() > 0) {
                        live = new Session(State.ACTIVE, null, stats, started, null);
                        liveConnectedMillis = liveConnectedMillis == 0 ? System.currentTimeMillis() : liveConnectedMillis;
                        fire();
                    }
                });
            }

            @Override
            public void ended(String error) {
                SwingUtilities.invokeLater(() -> streamEnded(error, s, caps, encoder, url, onError));
            }
        });
        streamer = enc;
        warnIfMasterMuted(s, onError, "livestream");
        liveConnectedMillis = 0;
        Thread.ofVirtual().name("stream-start").start(() -> {
            try {
                if (s.usesAppAudio()) {
                    try {
                        LoopbackAudio a = LoopbackAudio.start(toolsDir);
                        mixLiveInputs(a, caps.executable());
                        enc.setAppAudio(a.input());
                        SwingUtilities.invokeLater(() -> {
                            if (streamer == enc) liveLoopback = a; else a.close();
                        });
                    } catch (IOException e) {
                        log.warn("Streaming without this app's sound: {}", e.getMessage());
                        SwingUtilities.invokeLater(() -> onError.accept("The sound of videos and music in this app is not "
                                + "in the livestream: " + e.getMessage()));
                    }
                }
                enc.start();
                SwingUtilities.invokeLater(() -> {
                    if (streamer == enc) {
                        micsForNewEncoder(enc);
                        tap.addSink(enc, s.width(), s.height(), s.fps());
                        log.info("Streaming Program to {} ({}x{} {} fps, {} kbps, {})", s.provider().displayName(),
                                s.width(), s.height(), s.fps(), s.videoKbps(), encoder);
                    }
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> streamEnded("FFmpeg could not be started: " + e.getMessage(), s, caps, encoder, url, onError));
            }
        });
    }

    public void stopStream() {
        FFmpegStreamEncoder enc = streamer;
        if (enc == null) {
            return;
        }
        tap.removeSink(enc);
        enc.stop();
        live = new Session(State.STARTING, FINISHING, live.stats(), live.startedMillis(), null);
        fire();
    }

    /** Tries in a row before the livestream gives up: with {@link #reconnectDelayMillis} a little over a minute. */
    static final int MAX_RETRIES = 10;
    /** Connected this long: the stream was healthy, so a later drop starts counting again (a service lasts for hours). */
    private static final long STABLE_MILLIS = 60_000;

    static int retriesAfter(int retries, long connectedMillis) {
        return connectedMillis >= STABLE_MILLIS ? 0 : retries;
    }

    /** 1 s, 2 s, 4 s, then every 8 s: back on air fast after a short drop, without hammering a platform that is down. */
    static int reconnectDelayMillis(int attempt) {
        return 1000 << Math.clamp(attempt - 1, 0, 3);
    }

    private void streamEnded(String error, StreamSettings s, FFmpeg.Capabilities caps, String encoder, String url,
                             Consumer<String> onError) {
        if (liveLoopback != null) {
            liveLoopback.close();
            liveLoopback = null;
        }
        if (streamer != null) {
            tap.removeSink(streamer);
        }
        streamer = null;
        long connectedMillis = liveConnectedMillis > 0 ? System.currentTimeMillis() - liveConnectedMillis : 0;
        boolean wasRunning = connectedMillis > 15_000;
        liveRetries = retriesAfter(liveRetries, connectedMillis);
        // a try that fails while reconnecting (the internet is still out) tries again too
        if (error != null && (wasRunning || liveRetries > 0) && liveRetries < MAX_RETRIES) {
            // the internet or the platform dropped a running stream: reconnect instead of ending the service's stream
            liveRetries++;
            log.warn("Livestream dropped after {} s; reconnecting ({}/{})", connectedMillis / 1000, liveRetries, MAX_RETRIES);
            live = new Session(State.STARTING, "Reconnecting (" + liveRetries + "/" + MAX_RETRIES + ")…", null, live.startedMillis(), null);
            fire();
            javax.swing.Timer t = new javax.swing.Timer(reconnectDelayMillis(liveRetries), e -> {
                if (live.active() && !live.finishing() && streamer == null) {
                    launchStream(s, caps, encoder, url, onError);
                }
            });
            t.setRepeats(false);
            t.start();
            return;
        }
        live = error == null ? Session.idle() : new Session(State.ERROR, explainStreamError(error), null, 0, null);
        if (error != null) {
            onError.accept("The livestream stopped: " + explainStreamError(error));
        } else {
            log.info("Livestream ended");
        }
        fire();
    }

    /** The FFmpeg ingest URL: server + key. A key pasted together with the server is used as it is. Never logged. */
    String ingestUrl(StreamSettings s) {
        String key = streamKey(s.provider());
        if (key == null) {
            return null;
        }
        key = key.replaceAll("\\s+", ""); // copied keys often carry a line break or spaces
        if (key.isEmpty()) {
            return null;
        }
        if (key.startsWith("rtmp://") || key.startsWith("rtmps://")) {
            return key;
        }
        return s.provider().ingestUrl(s.server(), key);
    }

    /** FFmpeg's messages in words the operator can act on. */
    static String explainStreamError(String error) {
        String e = error.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("i/o error") || e.contains("error number -10053") || e.contains("connection reset")
                || e.contains("end of file") || e.contains("broken pipe")) {
            return "Facebook (or the server) closed the connection. Check that the stream key is the current one "
                    + "(copy it again from Live Producer, or turn on \"Use persistent stream key\"), and that the live video "
                    + "is not already ended. (" + error + ")";
        }
        if (e.contains("failed to resolve") || e.contains("timed out") || e.contains("connection refused")
                || e.contains("network is unreachable")) {
            return "Cannot reach the streaming server. Check the internet connection and the Server URL. (" + error + ")";
        }
        if (e.contains("tls") || e.contains("ssl") || e.contains("schannel")) {
            return "The secure connection (RTMPS) failed. Check the computer's date and time and the Server URL. (" + error + ")";
        }
        return error;
    }

    // ------------------------------------------------------------------ livestream checks

    /**
     * Everything START STREAM needs, with what to do about anything missing. Blocking (checks the
     * internet connection): call off the Swing thread.
     */
    public List<Check> preStreamCheck(StreamSettings s, FFmpeg.Capabilities caps) {
        List<Check> out = new ArrayList<>();
        List<String> cams = cameras.get();
        out.add(cams.isEmpty()
                ? new Check("Camera", Check.Level.WARNING, "No camera detected: viewers see the presentation only.")
                : new Check("Camera detected", Check.Level.OK, String.join(", ", cams)));
        boolean micOk = s.micDevice() != null && caps != null && caps.audioDevices().contains(s.micDevice());
        out.add(micOk ? new Check("Microphone detected", Check.Level.OK, s.micDevice())
                : new Check("Microphone", Check.Level.WARNING, s.micDevice() == null
                ? "No microphone chosen in the Audio Mixer: the stream will be silent."
                : "\"" + s.micDevice() + "\" is not connected."));
        out.add(new Check("Program output ready", Check.Level.OK, "Viewers see exactly the Program monitor."));
        String encoder = caps == null ? null : caps.encoderFor(s.encoder());
        out.add(encoder != null
                ? new Check("Encoder available", Check.Level.OK, "FFmpeg " + caps.version() + " · " + encoder)
                : new Check("Encoder", Check.Level.PROBLEM, ffmpegMissingMessage()));
        List<String> problems = s.provider().validate(s.server(), hasStreamKey(s.provider()));
        out.add(problems.isEmpty()
                ? new Check("Stream configuration available", Check.Level.OK, s.provider().displayName())
                : new Check("Stream configuration", Check.Level.PROBLEM, String.join(" ", problems)));
        String reach = problems.isEmpty() ? testConnection(s) : "skipped";
        out.add(reach == null
                ? new Check("Internet available", Check.Level.OK, "The streaming server answers.")
                : new Check("Internet", Check.Level.PROBLEM, reach.equals("skipped") ? "Complete the stream configuration first." : reach));
        return out;
    }

    /**
     * Checks that the streaming server can be reached (a network connection only: the stream key is
     * not sent, so nothing goes live). Returns null when it answers, else what is wrong. Blocking.
     */
    public String testConnection(StreamSettings s) {
        String server = s.server() == null || s.server().isBlank() ? s.provider().defaultServer() : s.server();
        if (server == null) {
            return "Enter the server URL.";
        }
        try {
            URI uri = URI.create(server.strip());
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : "rtmps".equalsIgnoreCase(uri.getScheme()) ? 443 : 1935;
            if (host == null) {
                return "The server URL is not valid.";
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5000);
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "The server URL is not valid.";
        } catch (IOException e) {
            return "Cannot reach the streaming server. Check the internet connection. (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Stops everything (application exit). */
    public void shutdown() {
        micWatch.stop();
        if (recorder != null) {
            tap.removeSink(recorder);
            recorder.stop();
        }
        if (streamer != null) {
            tap.removeSink(streamer);
            streamer.stop();
        }
    }

    public static String ffmpegMissingMessage() {
        return "FFmpeg is not installed. Open a Command Prompt, run: winget install Gyan.FFmpeg  "
                + "then click Refresh (or Locate FFmpeg… if you installed it somewhere else).";
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }
}
