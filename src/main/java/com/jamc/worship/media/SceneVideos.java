package com.jamc.worship.media;

import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.youtube.YouTubeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Players for the VIDEO and AUDIO sources of scenes: picture <i>and</i> sound.
 *
 * <ul>
 *   <li><b>Program</b>: every video source of the Program scene has its own player, started (with
 *       sound, on the Program output, through the media mixer) when the scene goes to Program and
 *       released when it leaves. Content changes (songs, verses) never touch it.</li>
 *   <li><b>Preview</b>: videos shown only in Preview (or in scene thumbnails and the editor) play
 *       silently; with the operator's MONITOR on, the Preview scene's videos are heard on the monitor
 *       device only. So the church never hears the same video twice.</li>
 *   <li>On TAKE the running Preview player is handed to Program (no reload, no second decoder): it
 *       starts again from the beginning, or continues, as the source or Settings say.</li>
 * </ul>
 * VLC plays picture and sound from one pipeline, so they stay in sync. Confined to the EDT.
 */
public final class SceneVideos {

    private static final Logger log = LoggerFactory.getLogger(SceneVideos.class);
    private static final long PREVIEW_IDLE_MS = 5_000;
    private static final long EDITOR_IDLE_MS = 60_000;
    private static final int MAX_PREVIEW = 4;

    /** A scene video that is playing. */
    public static final class Slot {
        final String key;
        final String path;
        VideoPlayback player;
        SourceConfig config;
        String name;
        long lastUsed = System.currentTimeMillis();
        /** Live changes from the Program Media controls (kept while the scene stays on Program); null = the scene's value. */
        Integer liveVolume;
        Boolean liveMuted;
        Boolean liveLoop;
        /** Where the operator's mute / volume / loop for this source is remembered (MediaStates). */
        String stateKey;
        /** VLC's audio counters at the last sound check: decoded blocks, buffers played on the speakers. */
        int lastDecoded = -1;
        int lastPlayed = -1;
        /** Sound checks in a row that found sound decoded but none played. */
        int silentChecks;
        /** Times the sound output was reopened for this video. */
        int reroutes;
        /** The last sound state logged ("heard on …" / "silent (why)"). */
        String soundState;

        Slot(String key, String path, VideoPlayback player, SourceConfig config, String name) {
            this.key = key;
            this.path = path;
            this.player = player;
            this.config = config;
            this.name = name;
        }

        public VideoPlayback player() { return player; }
        public String name() { return name; }
        public SourceConfig config() { return config; }
        public String key() { return key; }
    }

    private final Function<String, Path> resolver;
    private final AudioRouting routing;
    private final Map<String, Slot> program = new LinkedHashMap<>();
    private final Map<String, Slot> preview = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Set<String> previewScene = Set.of();
    /** True while the operator plays a video or audio of their own: scene videos then go quiet. */
    private java.util.function.BooleanSupplier operatorMediaPlaying = () -> false;

    public SceneVideos(Function<String, Path> resolver, AudioRouting routing) {
        this.resolver = resolver;
        this.routing = routing;
        new Timer(2_000, e -> sweep()).start();
        routing.addListener(this::applyAudio);
    }

    /**
     * Identifies one source's video across the copies of a scene (Preview, Program, editor) and across saves
     * (the database gives sources new ids on every save, so the id would restart a live video after an edit).
     */
    public static String key(Source s) {
        return s.getConfig().path + "#" + s.getName();
    }

    private MediaStates states;
    private Long programSceneId;

    /** Where the operator's mute / volume / loop for each scene source on Program is remembered. */
    public void setStates(MediaStates states) {
        this.states = states;
    }

    /** A source arriving on Program gets the mute / volume / loop the operator left it with (this scene). */
    private void restore(Slot slot) {
        slot.stateKey = "scene:" + programSceneId + ":" + slot.key;
        if (states != null && states.has(slot.stateKey)) {
            MediaStates.State st = states.get(slot.stateKey, null);
            // VOLUME and MUTE changed in Program Media last while the video is on Program: brought live again it has
            // its own sound at the scene source's volume (a remembered mute made a scene's video silent "sometimes")
            slot.liveVolume = null;
            slot.liveMuted = null;
            slot.liveLoop = st.loop();
        }
    }

    private void remember(Slot slot) {
        if (states != null && slot.stateKey != null) {
            boolean muted = slot.liveMuted != null ? slot.liveMuted : Boolean.TRUE.equals(slot.config.muted); // not "audio off"
            states.put(slot.stateKey, new MediaStates.State(muted, effectiveVolume(slot), effectiveLoop(slot)));
        }
    }

    static boolean effectiveLoop(Slot s) {
        return s.liveLoop != null ? s.liveLoop : loops(s.config);
    }

    static boolean loops(SourceConfig c) {
        return c.loop == null || c.loop; // older "Video (looping)" sources keep looping
    }

    /**
     * Current picture of a scene's video source: the Program player when this source is on Program,
     * else a silent Preview player (started on first request).
     */
    public BufferedImage frame(Source s) {
        SourceConfig c = s.getConfig();
        if (c.path == null || !VideoPlayback.available(c.path)) {
            return null;
        }
        String k = key(s);
        Slot p = program.get(k);
        if (p != null) {
            p.lastUsed = System.currentTimeMillis();
            return p.player.currentFrame();
        }
        Slot v = preview.get(k);
        if (v == null) {
            String mrl = mrl(c.path);
            if (mrl == null) {
                return null;
            }
            if (preview.size() >= MAX_PREVIEW) {
                evictOldestPreview();
            }
            try {
                VideoPlayback player = VideoPlayback.create(c.path);
                v = new Slot(k, c.path, player, c, s.getName());
                applyPreviewAudio(v); // muted unless the operator monitors this Preview scene
                player.play(mrl, loops(c), !hearPreview(v));
                preview.put(k, v);
            } catch (RuntimeException e) {
                log.warn("Could not start scene video {}", c.path, e);
                return null;
            }
        }
        v.config = c;
        v.lastUsed = System.currentTimeMillis();
        return v.player.currentFrame();
    }

    /** Scene editor players: its own (never Preview's or Program's), silent, PAUSED until the operator presses PLAY. */
    private final Map<String, Slot> editor = new HashMap<>();

    /**
     * The scene editor's picture of a video source: its own player, loaded paused (the first picture, or the still
     * while VLC has none yet). PLAY / PAUSE in the editor ({@link #setEditorPlaying}) never touches Preview or Program.
     */
    public BufferedImage editorFrame(Source s) {
        Slot v = editorSlot(s);
        if (v == null) {
            return null;
        }
        v.lastUsed = System.currentTimeMillis();
        BufferedImage f = v.player.currentFrame();
        if (f == null && !YouTubeRef.is(s.getConfig().path)) {
            Path file = resolver.apply(s.getConfig().path);
            f = file == null ? null : VideoStills.get(file); // paused before VLC drew a picture: the still
        }
        return f;
    }

    /** True while the editor's player of this source plays (false = paused, the default). */
    public boolean editorPlaying(Source s) {
        Slot v = editor.get(key(s));
        return v != null && v.player.isPlaying();
    }

    /** PLAY / PAUSE in the scene editor: only the editor's own player of this source. */
    public void setEditorPlaying(Source s, boolean play) {
        Slot v = editorSlot(s);
        if (v == null) {
            return;
        }
        v.lastUsed = System.currentTimeMillis();
        if (play) {
            v.player.setMuted(true); // the editor is silent: the church never hears a video being arranged
            v.player.playOrResume();
        } else {
            v.player.pause();
        }
    }

    /** The scene editor left the screen (another page): every editor video pauses where it is. */
    public void pauseEditor() {
        for (Slot v : editor.values()) {
            if (v.player.isPlaying()) {
                v.player.pause();
            }
        }
    }

    private Slot editorSlot(Source s) {
        SourceConfig c = s.getConfig();
        if (c.path == null || !VideoPlayback.available(c.path)) {
            return null;
        }
        String k = key(s);
        Slot v = editor.get(k);
        if (v == null) {
            String mrl = mrl(c.path);
            if (mrl == null) {
                return null;
            }
            try {
                VideoPlayback player = VideoPlayback.create(c.path);
                player.setMuted(true);
                player.cue(mrl, loops(c)); // paused: nothing moves until PLAY
                v = new Slot(k, c.path, player, c, s.getName());
                editor.put(k, v);
            } catch (RuntimeException e) {
                log.warn("Could not open scene video {} in the editor", c.path, e);
                return null;
            }
        }
        v.config = c;
        return v;
    }

    /**
     * A still picture of a scene's video source for thumbnails: never starts a player (null while it is
     * being made, for a YouTube source, or when the file is missing).
     */
    public BufferedImage still(Source s) {
        SourceConfig c = s.getConfig();
        if (c.path == null) {
            return null;
        }
        String k = key(s);
        if (YouTubeRef.is(c.path)) {
            // YouTube's own picture of the video (kept on disk once seen)
            BufferedImage t = com.jamc.worship.youtube.YouTubeLibrary.thumbnail(YouTubeRef.id(c.path), null);
            return t != null ? t : frozen.computeIfAbsent(k, x -> runningFrame(k));
        }
        Path file = resolver.apply(c.path);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        if (VideoStills.missing(file)) {
            VideoStills.offer(file, runningFrame(k)); // already playing (e.g. on Program): one frame, frozen
        }
        return VideoStills.get(file);
    }

    /** Frozen frames for YouTube thumbnails while YouTube's picture is not loaded (e.g. offline). */
    private final Map<String, BufferedImage> frozen = new HashMap<>();

    /** A copy of the current picture of a player of this source that already runs, or null (none started). */
    private BufferedImage runningFrame(String k) {
        Slot slot = program.get(k);
        if (slot == null) {
            slot = preview.get(k);
        }
        BufferedImage f = slot == null ? null : slot.player.currentFrame();
        return f == null || VideoStills.isDark(f) ? null : VideoStills.copy(f);
    }

    /**
     * Program's scene changed (or was edited): start the videos it now has, release the ones it lost,
     * and apply each source's sound settings. Cheap and safe to call on every Program change.
     */
    public void syncProgram(Scene scene) {
        Map<String, Source> want = videos(scene);
        programSceneId = scene == null ? null : scene.getId();
        boolean changed = false;
        Iterator<Map.Entry<String, Slot>> it = program.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Slot> e = it.next();
            if (!want.containsKey(e.getKey())) {
                e.getValue().player.release(); // the sound stops the moment the scene leaves Program
                it.remove();
                changed = true;
                log.info("Scene video left Program: {}", e.getValue().name);
            }
        }
        for (Map.Entry<String, Source> e : want.entrySet()) {
            Source s = e.getValue();
            SourceConfig c = s.getConfig();
            Slot slot = program.get(e.getKey());
            if (slot == null) {
                slot = startOnProgram(e.getKey(), s);
                if (slot == null) {
                    continue;
                }
                program.put(e.getKey(), slot);
                changed = true;
            }
            slot.config = c;
            slot.name = s.getName();
            slot.player.setLoop(effectiveLoop(slot));
            applyProgramAudio(slot);
        }
        if (changed) {
            listeners.forEach(Runnable::run);
        }
    }

    private Slot startOnProgram(String key, Source s) {
        SourceConfig c = s.getConfig();
        boolean restart = c.restartOnProgram != null ? c.restartOnProgram
                : routing.takeBehavior() == AudioRouting.TakeBehavior.RESTART;
        boolean autoplay = c.autoplay == null || c.autoplay;
        Slot from = preview.remove(key);
        try {
            if (from != null) {
                // hand the running Preview player over: no reload, no black frame, no second decoder
                Slot slot = new Slot(key, c.path, from.player, c, s.getName());
                restore(slot);
                slot.player.setLoop(effectiveLoop(slot));
                applyProgramAudio(slot);
                if (restart) {
                    slot.player.seekTime(0);
                }
                if (autoplay) {
                    slot.player.playOrResume();
                } else {
                    slot.player.pause();
                }
                // the Preview player was muted on the monitor device: when VLC switches its sound output to the
                // Program device the new output can miss the unmute / volume just set, so set them again once it settled
                for (int delay : new int[]{400, 1500}) {
                    Timer t = new Timer(delay, e -> {
                        if (program.get(key) == slot) {
                            applyProgramAudio(slot);
                            if (slot.player instanceof VideoPlayer vp) {
                                vp.reapplyAudio(false);
                            }
                        }
                    });
                    t.setRepeats(false);
                    t.start();
                }
                log.info("Scene video on Program: {} ({})", s.getName(), restart ? "from the beginning" : "continuing from Preview");
                return slot;
            }
            String mrl = mrl(c.path);
            if (mrl == null || !VideoPlayback.available(c.path)) {
                log.warn("Scene video not playable: {}", c.path);
                return null;
            }
            VideoPlayback player = VideoPlayback.create(c.path);
            Slot slot = new Slot(key, c.path, player, c, s.getName());
            restore(slot);
            applyProgramAudio(slot);
            if (autoplay) {
                if (s.getType() == SourceType.AUDIO) {
                    player.play(mrl, effectiveLoop(slot), programMuted(slot), ":no-video"); // sound only
                } else {
                    player.play(mrl, effectiveLoop(slot), programMuted(slot));
                }
            } else {
                player.cue(mrl, effectiveLoop(slot));
            }
            log.info("Scene video on Program: {}", s.getName());
            return slot;
        } catch (RuntimeException e) {
            log.warn("Could not start scene video {}", c.path, e);
            return null;
        }
    }

    /** Preview's scene changed: only its videos may be heard when the operator monitors Preview. */
    public void syncPreview(Scene scene) {
        Set<String> keys = videos(scene).keySet();
        if (!keys.equals(previewScene)) {
            previewScene = Set.copyOf(keys);
            preview.values().forEach(this::applyPreviewAudio);
        }
    }

    /** Re-applies devices, volumes and mutes (the routing or mixer changed). */
    public void applyAudio() {
        program.values().forEach(this::applyProgramAudio);
        preview.values().forEach(this::applyPreviewAudio);
    }

    /** The videos playing on Program now (for the Program Media controls). */
    /** True while a Program scene video plays with its sound heard (not muted / audio off / quieted). */
    public boolean anyHeard() {
        for (Slot s : program.values()) {
            if (s.player.isPlaying() && !programMuted(s) && !Boolean.TRUE.equals(s.config.monitorOnly)) {
                return true;
            }
        }
        return false;
    }

    public List<Slot> programSlots() {
        return new ArrayList<>(program.values());
    }

    /** Scene videos on Program go quiet while this is true (the operator's own video or audio is playing). */
    public void setOperatorMediaPlaying(java.util.function.BooleanSupplier playing) {
        operatorMediaPlaying = playing;
    }

    /** Called when the set of Program videos changes. */
    public void addListener(Runnable l) {
        listeners.add(l);
    }

    /** Sets a Program source's volume / mute live (Program Media controls); the scene keeps its saved values. */

    /**
     * Program Media controls for a Program video: play / pause / stop / seek go to its player; volume and
     * mute are live changes on top of the scene's values (the saved scene is not changed).
     */
    public Transport transport(Slot slot) {
        VideoPlayback p = slot.player;
        return new Transport() {
            @Override public void playOrResume() { p.playOrResume(); }
            @Override public void pause() { p.pause(); }
            @Override public void stop() { p.stop(); }
            @Override public void seek(float position) { p.seek(position); }
            @Override public void setVolume(int volume) {
                slot.liveVolume = Math.clamp(volume, 0, 150);
                applyProgramAudio(slot);
                remember(slot);
            }
            @Override public int volume() { return effectiveVolume(slot); }
            @Override public void setMuted(boolean muted) {
                slot.liveMuted = muted;
                applyProgramAudio(slot);
                remember(slot);
            }
            @Override public boolean isMuted() { return effectiveMuted(slot); }
            @Override public void setLoop(boolean loop) {
                slot.liveLoop = loop;
                p.setLoop(loop);
                remember(slot);
            }
            @Override public boolean isLoop() { return p.isLoop(); }
            @Override public boolean isPlaying() { return p.isPlaying(); }
            @Override public long time() { return p.time(); }
            @Override public long length() { return p.length(); }
            @Override public boolean hasMedia() { return p.hasMedia(); }
            @Override public void addStateListener(Runnable listener) { p.addStateListener(listener); }
        };
    }

    public void shutdown() {
        program.values().forEach(s -> s.player.release());
        preview.values().forEach(s -> s.player.release());
        program.clear();
        preview.clear();
    }

    // ------------------------------------------------------------------ internals

    /** What to hand the player: a YouTube reference as is, a file as its full path; null if the file is missing. */
    private String mrl(String path) {
        if (YouTubeRef.is(path)) {
            return path;
        }
        Path file = resolver.apply(path);
        return Files.isRegularFile(file) ? file.toString() : null;
    }

    static boolean silent(SourceConfig c) {
        return Boolean.FALSE.equals(c.audioEnabled) || Boolean.TRUE.equals(c.muted);
    }

    private static int sourceVolume(SourceConfig c) {
        return c.volume == null ? 100 : c.volume;
    }

    private void applyProgramAudio(Slot s) {
        SourceConfig c = s.config;
        String device = Boolean.TRUE.equals(c.monitorOnly) ? routing.monitorDeviceInUse() : routing.programDeviceInUse();
        int volume = routing.programVolume(effectiveVolume(s));
        s.player.setOutputDevice(device);
        s.player.setVolume(volume);
        s.player.setMuted(programMuted(s));
        // one log line whenever this video's sound changes, saying why (for "the scene video had no sound")
        String why = soundState(s, device, volume);
        if (!why.equals(s.soundState)) {
            s.soundState = why;
            log.info("Scene video {} on Program: {}", s.name, why);
        }
    }

    /** Whether the Program scene video is heard, and if not, why. */
    private String soundState(Slot s, String device, int volume) {
        if (Boolean.FALSE.equals(s.config.audioEnabled)) {
            return "silent (Audio is switched off in the scene's video source)";
        }
        if (s.liveMuted != null ? s.liveMuted : Boolean.TRUE.equals(s.config.muted)) {
            return s.liveMuted != null ? "silent (muted in Program Media)" : "silent (Muted is on in the scene's video source)";
        }
        if (routing.mediaGain() <= 0) {
            return "silent (the MEDIA or MASTER channel is muted or at 0 in the mixer)";
        }
        if (operatorMediaPlaying.getAsBoolean()) {
            return "silent while the operator's own Program video or audio player plays";
        }
        if (monitorSilenced(s.config)) {
            return "silent (Preview monitor only, and a recording or the livestream is running)";
        }
        String where = device == null || device.isEmpty() ? "Windows default output" : routing.nameOf(device);
        return "heard on " + where + " at volume " + volume
                + (Boolean.TRUE.equals(s.config.monitorOnly) ? " (Preview monitor only)" : "");
    }

    /** The one mute state a Program player has: set before it starts, so its sound never goes on-off-on. */
    private boolean programMuted(Slot s) {
        return effectiveMuted(s) || routing.mediaGain() <= 0 || operatorMediaPlaying.getAsBoolean() || monitorSilenced(s.config);
    }

    private boolean monitorSilenced(SourceConfig c) {
        return routing.programOnly() && Boolean.TRUE.equals(c.monitorOnly);
    }

    static int effectiveVolume(Slot s) {
        return s.liveVolume != null ? s.liveVolume : sourceVolume(s.config);
    }

    static boolean effectiveMuted(Slot s) {
        return Boolean.FALSE.equals(s.config.audioEnabled) || (s.liveMuted != null ? s.liveMuted : Boolean.TRUE.equals(s.config.muted));
    }

    private boolean hearPreview(Slot s) {
        return routing.previewMonitor() && previewScene.contains(s.key) && !silent(s.config);
    }

    private void applyPreviewAudio(Slot s) {
        s.player.setOutputDevice(routing.monitorDeviceInUse());
        s.player.setVolume(AudioRouting.volume(sourceVolume(s.config), 1.0));
        s.player.setMuted(!hearPreview(s));
    }

    private static Map<String, Source> videos(Scene scene) {
        Map<String, Source> out = new LinkedHashMap<>();
        if (scene == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (Source s : scene.getSources()) {
            if (s.isVisible() && (s.getType() == SourceType.VIDEO || s.getType() == SourceType.AUDIO) && s.getConfig().path != null) {
                String k = key(s);
                if (seen.add(k)) {
                    out.put(k, s);
                }
            }
        }
        return out;
    }

    private void sweep() {
        program.values().forEach(this::checkSound);
        long now = System.currentTimeMillis();
        for (Map<String, Slot> players : List.of(preview, editor)) {
            // not drawn for a while (editor closed, source removed): released. A PAUSED editor video is not redrawn,
            // so the editor's players wait longer (its place and PLAY / PAUSE are kept while the scene is edited)
            long idle = players == editor ? EDITOR_IDLE_MS : PREVIEW_IDLE_MS;
            Iterator<Map.Entry<String, Slot>> it = players.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Slot> e = it.next();
                if (now - e.getValue().lastUsed > idle) {
                    e.getValue().player.release();
                    it.remove();
                }
            }
        }
    }

    /**
     * SOUND CHECK (every 2 s) for a Program scene video that should be heard: VLC muted although nothing asks for
     * it, or sound decoded but none reaching the speakers for two checks in a row, reopens its sound output on
     * the Program device. Videos meant to be silent (muted, audio off, the operator's own media playing, the
     * media channel at 0) are left alone.
     */
    private void checkSound(Slot s) {
        if (!(s.player instanceof VideoPlayer vp) || !vp.isPlaying() || programMuted(s)
                || Boolean.TRUE.equals(s.config.monitorOnly)) {
            s.silentChecks = 0;
            s.lastDecoded = -1;
            return;
        }
        if (vp.outputMuted()) {
            log.warn("Scene video {} was muted in VLC although it should be heard; unmuting", s.name);
            applyProgramAudio(s);
            vp.reapplyAudio(false);
        }
        int[] st = vp.audioStats();
        boolean decoding = s.lastDecoded >= 0 && st[0] > s.lastDecoded;
        boolean playing = st[1] > s.lastPlayed;
        s.silentChecks = decoding && !playing ? s.silentChecks + 1 : 0;
        s.lastDecoded = st[0];
        s.lastPlayed = st[1];
        if (s.silentChecks >= 2 && s.reroutes < 2) {
            // at most twice per video: reopening the output is itself a short dropout
            log.warn("Scene video {} decodes sound but none reaches the speakers; reopening its sound output", s.name);
            s.silentChecks = 0;
            s.reroutes++;
            vp.setOutputDevice(routing.programDeviceInUse());
            vp.reapplyAudio(true);
        }
    }

    private void evictOldestPreview() {
        preview.entrySet().stream().min((a, b) -> Long.compare(a.getValue().lastUsed, b.getValue().lastUsed))
                .ifPresent(e -> {
                    e.getValue().player.release();
                    preview.remove(e.getKey());
                });
    }
}
