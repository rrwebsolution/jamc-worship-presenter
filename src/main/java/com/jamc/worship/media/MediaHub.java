package com.jamc.worship.media;

import com.jamc.worship.camera.CameraManager;
import com.jamc.worship.render.LiveFrames;
import com.jamc.worship.youtube.YouTubeEngine;
import com.jamc.worship.youtube.YouTubePlayer;
import com.jamc.worship.youtube.YouTubeRef;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Owns the application's live media: the Program video player, background audio, looping
 * background/scene videos and cameras. Players are created lazily so nothing touches VLC until
 * media is actually used. EDT-confined apart from frame delivery.
 */
public final class MediaHub implements LiveFrames {

    private final VideoPool pool;
    private final CameraManager cameras;
    private final AudioRouting routing;
    private final SceneVideos sceneVideos;
    private final CameraAudio cameraAudio;
    private VideoPlayer programVideo;
    /** Program player for YouTube videos taken live (the embedded YouTube player, never VLC). */
    private YouTubePlayer programYouTube;
    /** Whichever of the two is showing the taken video now, or null. */
    private VideoPlayback activeProgram;
    private AudioPlayer audio;

    public MediaHub(Function<String, Path> resolver, CameraManager cameras, AudioRouting routing) {
        this.pool = new VideoPool(resolver);
        this.cameras = cameras;
        this.routing = routing;
        this.sceneVideos = new SceneVideos(resolver, routing);
        this.cameraAudio = new CameraAudio(cameras, routing);
        // only what the operator plays is heard: scene videos go quiet while a Program video or the audio player plays
        sceneVideos.setOperatorMediaPlaying(() -> (activeProgram != null && activeProgram.isPlaying()) || (audio != null && audio.isPlaying()));
        routing.addListener(this::applyRouting);
    }

    public AudioRouting routing() {
        return routing;
    }

    /** Players (with sound) for the video sources of the Program and Preview scenes. */
    public SceneVideos sceneVideos() {
        return sceneVideos;
    }

    /**
     * True while a video or music is heard on Program: the taken (fullscreen) video, the audio player, or a scene
     * video with its sound on. Recordings then mute the room microphones.
     */
    public boolean programMediaHeard() {
        VideoPlayback p = activeProgram;
        if (p != null && p.isPlaying() && !p.isMuted() && routing.mediaGain() > 0) {
            return true;
        }
        if (audio != null && audio.isPlaying() && !audio.isMuted() && routing.mediaGain() > 0) {
            return true;
        }
        return sceneVideos.anyHeard();
    }

    /** Sound of the Program scene's cameras that have AUDIO switched on. */
    public CameraAudio cameraAudio() {
        return cameraAudio;
    }

    /**
     * Puts the Program video and the audio player on the Program output through the mixer
     * ({@code baseVolume} is the Program video's own level from Settings).
     */
    public void applyRouting() {
        if (programYouTube != null) {
            // YouTube's sound goes to the Windows default device; only its volume and mute follow the mixer
            programYouTube.setVolume(routing.programVolume(programVideoVolume));
            programYouTube.setMuted(programVideoMuted || routing.mediaGain() <= 0);
        }
        if (!available()) {
            return;
        }
        if (programVideo != null) {
            programVideo.setOutputDevice(routing.programDeviceInUse());
            programVideo.setVolume(routing.programVolume(programVideoVolume));
            // the operator's own mute is kept; the mixer can only add silence (media channel at 0)
            programVideo.setMuted(programVideoMuted || routing.mediaGain() <= 0);
        }
        if (audio != null) {
            audio.setOutputDevice(routing.programDeviceInUse());
            audio.setGain(routing.mediaGain());
        }
    }

    private int programVideoVolume = 100;
    private boolean programVideoMuted;

    /** The Program video's own volume (Settings → Media), before the mixer. */
    public void setProgramVideoVolume(int v) {
        programVideoVolume = Math.clamp(v, 0, 150);
        applyRouting();
    }

    public int programVideoVolume() {
        return programVideoVolume;
    }

    /** The operator muted the Program video (MUTE): it stays muted through mixer changes and new videos. */
    public void setProgramVideoMuted(boolean muted) {
        programVideoMuted = muted;
        applyRouting();
    }

    public boolean programVideoMuted() {
        return programVideoMuted;
    }

    /**
     * Controls for the Program video whose volume and mute go through the hub (so the mixer and routing
     * keep them) instead of straight to the player.
     */
    public Transport programVideoTransport() {
        VideoPlayback p = activeProgram != null ? activeProgram : programVideoPlayer();
        return new Transport() {
            @Override public void playOrResume() { p.playOrResume(); }
            @Override public void pause() { p.pause(); }
            @Override public void stop() { p.stop(); }
            @Override public void seek(float position) { p.seek(position); }
            @Override public void setVolume(int volume) { setProgramVideoVolume(volume); }
            @Override public int volume() { return programVideoVolume; }
            @Override public void setMuted(boolean muted) { setProgramVideoMuted(muted); }
            @Override public boolean isMuted() { return programVideoMuted; }
            @Override public void setLoop(boolean loop) { p.setLoop(loop); }
            @Override public boolean isLoop() { return p.isLoop(); }
            @Override public boolean isPlaying() { return p.isPlaying(); }
            @Override public long time() { return p.time(); }
            @Override public long length() { return p.length(); }
            @Override public boolean hasMedia() { return p.hasMedia(); }
            @Override public void addStateListener(Runnable listener) { p.addStateListener(listener); }
        };
    }

    public boolean available() {
        return MediaEngine.get().isAvailable();
    }

    public String problem() {
        return MediaEngine.get().problem();
    }

    /** The player used for videos taken live. Throws if VLC is unavailable. */
    public VideoPlayer programVideoPlayer() {
        if (programVideo == null) {
            programVideo = new VideoPlayer();
            programVideo.setOutputDevice(routing.programDeviceInUse());
            programVideo.addStateListener(() -> javax.swing.SwingUtilities.invokeLater(sceneVideos::applyAudio));
        }
        return programVideo;
    }

    /**
     * The Program player for a taken video: the embedded YouTube player for {@code youtube:} references,
     * VLC for files. The other one is stopped, so only one taken video plays at a time.
     */
    public VideoPlayback programPlayer(String path) {
        VideoPlayback next;
        if (YouTubeRef.is(path)) {
            if (programYouTube == null) {
                programYouTube = new YouTubePlayer(1920, 1080);
                programYouTube.addStateListener(sceneVideos::applyAudio);
            }
            next = programYouTube;
        } else {
            next = programVideoPlayer();
        }
        if (activeProgram != null && activeProgram != next) {
            activeProgram.stop();
        }
        activeProgram = next;
        applyRouting();
        return next;
    }

    /**
     * TAKE of a video already playing in Preview: that same player becomes the Program player, so the video goes on from
     * where it is instead of opening again from the start. Null when Preview is not playing it.
     */
    public VideoPlayback adoptPreviewVideo(String path) {
        VideoPlayback running = pool.take(path);
        if (running == null) {
            return null;
        }
        if (running.hasError()) {
            running.release();
            return null;
        }
        if (activeProgram != null) {
            activeProgram.stop();
        }
        if (running instanceof YouTubePlayer youtube) {
            if (programYouTube != null) {
                programYouTube.release();
            }
            programYouTube = youtube;
            youtube.addStateListener(sceneVideos::applyAudio);
        } else if (running instanceof VideoPlayer vlc) {
            if (programVideo != null) {
                programVideo.release();
            }
            programVideo = vlc;
            vlc.addStateListener(() -> javax.swing.SwingUtilities.invokeLater(sceneVideos::applyAudio));
        }
        activeProgram = running;
        applyRouting();
        return running;
    }

    /** The player showing the taken video, or null. */
    public VideoPlayback activeProgramPlayer() {
        return activeProgram;
    }

    /** Stops the taken video (Program moved to other content). */
    public void stopProgramVideo() {
        if (activeProgram != null) {
            activeProgram.stop();
        }
    }

    public boolean hasProgramVideo() {
        return activeProgram != null;
    }

    /** The background audio player. Throws if VLC is unavailable. */
    public AudioPlayer audio() {
        if (audio == null) {
            audio = new AudioPlayer();
            audio.addStateListener(() -> javax.swing.SwingUtilities.invokeLater(sceneVideos::applyAudio));
            audio.setOutputDevice(routing.programDeviceInUse());
            audio.setGain(routing.mediaGain());
        }
        return audio;
    }

    public CameraManager cameras() {
        return cameras;
    }

    @Override
    public BufferedImage video(String path) {
        return pool.frame(path);
    }

    @Override
    public BufferedImage previewVideo(String path) {
        BufferedImage frame = pool.frame(path);
        VideoPlayback p = pool.player(path);
        boolean hear = routing.previewMonitor();
        if (p != null && p.isMuted() == hear) {
            // PREVIEW AUDIO: the operator listens on the monitor device; the church never hears Preview
            p.setOutputDevice(routing.monitorDeviceInUse());
            p.setVolume(100);
            p.setMuted(!hear);
        }
        return frame;
    }

    @Override
    public BufferedImage videoPoster(String path) {
        return pool.poster(path);
    }

    @Override
    public BufferedImage programVideo() {
        return activeProgram == null ? null : activeProgram.currentFrame();
    }

    @Override
    public String programVideoPath() {
        return activeProgram == null || !activeProgram.hasMedia() ? null : activeProgram.mrl();
    }

    @Override
    public BufferedImage sceneVideo(com.jamc.worship.model.Source source) {
        return sceneVideos.frame(source);
    }

    @Override
    public BufferedImage camera(String name) {
        return cameras.frame(name);
    }

    @Override
    public com.jamc.worship.camera.CameraStatus cameraStatus(String name) {
        return cameras.status(name);
    }

    /** Stops everything and releases VLC. Call once on exit. */
    public void shutdown() {
        pool.stopAll();
        sceneVideos.shutdown();
        cameraAudio.shutdown();
        cameras.stopAll();
        if (programYouTube != null) {
            programYouTube.release();
        }
        MediaEngine.get().shutdown();
        YouTubeEngine.get().shutdown();
    }
}
