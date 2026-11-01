package com.jamc.worship.stream;

import com.jamc.worship.config.AppConfig;

/**
 * Video, audio and destination settings for recording and livestreaming, kept in settings.properties
 * ({@code stream.*}). The stream key is not here: it lives in the {@link SecretStore}.
 *
 * @param micDevice   DirectShow name of the microphone, or null for none
 * @param auxDevice   DirectShow name of a second input (camera / capture card audio, or a loopback such
 *                    as "Stereo Mix" for video and music playback), or null for none
 * @param micVolume   0..150 (%)
 * @param auxVolume   0..150 (%)
 * @param masterVolume 0..150 (%)
 * @param mediaDevice {@link #APP_AUDIO}, or a DirectShow loopback that carries this app's video / music playback (e.g. "Stereo Mix" or
 *                    "CABLE Output"), so Program media audio reaches the stream and recordings; null = not included
 */
public record StreamSettings(
        String providerId,
        String server,
        String destinationName,
        int width,
        int height,
        int fps,
        int videoKbps,
        int audioKbps,
        EncoderChoice encoder,
        String micDevice,
        int micVolume,
        boolean micMuted,
        String auxDevice,
        int auxVolume,
        boolean auxMuted,
        int masterVolume,
        boolean masterMuted,
        String mediaDevice,
        int mediaVolume,
        boolean mediaMuted) {

    /**
     * MEDIA input that is not a DirectShow device: what this app plays on its Program output (videos, scene
     * videos, music), captured with Windows loopback. No "Stereo Mix" or VB-Cable needed. The default.
     */
    public static final String APP_AUDIO = "This app (Program sound)";

    /** True when the MEDIA input is this app's own Program sound. */
    public boolean usesAppAudio() {
        return APP_AUDIO.equals(mediaDevice) && !mediaMuted;
    }

    /** Which H.264 encoder to use. AUTO prefers a working hardware encoder, else software. */
    public enum EncoderChoice {
        AUTO("Auto (hardware if available)"), HARDWARE("Hardware encoder"), SOFTWARE("Software (x264)");

        private final String label;

        EncoderChoice(String label) {
            this.label = label;
        }

        public static EncoderChoice parse(String v) {
            try {
                return v == null ? AUTO : valueOf(v);
            } catch (IllegalArgumentException e) {
                return AUTO;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One-click quality presets for volunteers; Advanced settings can fine-tune bitrates. */
    public enum Preset {
        P720_30("720p 30 FPS", 1280, 720, 30, 4000),
        P1080_30("1080p 30 FPS", 1920, 1080, 30, 6000),
        P1080_60("1080p 60 FPS", 1920, 1080, 60, 9000);

        public final String label;
        public final int width;
        public final int height;
        public final int fps;
        public final int videoKbps;

        Preset(String label, int width, int height, int fps, int videoKbps) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.videoKbps = videoKbps;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public StreamSettings {
        width = Math.clamp(width, 320, 3840) & ~1;
        height = Math.clamp(height, 180, 2160) & ~1;
        fps = Math.clamp(fps, 10, 60);
        videoKbps = Math.clamp(videoKbps, 500, 20000);
        audioKbps = Math.clamp(audioKbps, 64, 320);
        encoder = encoder == null ? EncoderChoice.AUTO : encoder;
        micVolume = Math.clamp(micVolume, 0, 150);
        auxVolume = Math.clamp(auxVolume, 0, 150);
        masterVolume = Math.clamp(masterVolume, 0, 150);
        mediaVolume = Math.clamp(mediaVolume, 0, 150);
    }

    public StreamingProvider provider() {
        return StreamingProviders.byId(providerId);
    }

    /** The preset matching the size and frame rate, or null for custom values. */
    public Preset preset() {
        for (Preset p : Preset.values()) {
            if (p.width == width && p.height == height && p.fps == fps) {
                return p;
            }
        }
        return null;
    }

    public StreamSettings withPreset(Preset p) {
        return new StreamSettings(providerId, server, destinationName, p.width, p.height, p.fps, p.videoKbps, audioKbps,
                encoder, micDevice, micVolume, micMuted, auxDevice, auxVolume, auxMuted, masterVolume, masterMuted,
                mediaDevice, mediaVolume, mediaMuted);
    }

    public StreamSettings withEncoder(EncoderChoice e) {
        return new StreamSettings(providerId, server, destinationName, width, height, fps, videoKbps, audioKbps,
                e, micDevice, micVolume, micMuted, auxDevice, auxVolume, auxMuted, masterVolume, masterMuted,
                mediaDevice, mediaVolume, mediaMuted);
    }

    public static StreamSettings load(AppConfig c) {
        return new StreamSettings(
                c.get("stream.provider", StreamingProviders.FACEBOOK.id()),
                c.get("stream.server", null),
                c.get("stream.destinationName", null),
                parse(c.get("stream.width", null), 1920),
                parse(c.get("stream.height", null), 1080),
                parse(c.get("stream.fps", null), 30),
                parse(c.get("stream.videoKbps", null), 6000),
                parse(c.get("stream.audioKbps", null), 160),
                EncoderChoice.parse(c.get("stream.encoder", null)),
                blankToNull(c.get("stream.micDevice", null)),
                parse(c.get("stream.micVolume", null), 80),
                c.getBoolean("stream.micMuted", false),
                blankToNull(c.get("stream.auxDevice", null)),
                parse(c.get("stream.auxVolume", null), 60),
                c.getBoolean("stream.auxMuted", false),
                parse(c.get("stream.masterVolume", null), 100),
                c.getBoolean("stream.masterMuted", false),
                blankToNull(c.get("stream.mediaDevice", APP_AUDIO)), // until chosen: this app's own sound
                parse(c.get("stream.mediaVolume", null), 100),
                c.getBoolean("stream.mediaMuted", false));
    }

    public void save(AppConfig c) {
        c.set("stream.provider", providerId);
        c.set("stream.server", blankToNull(server));
        c.set("stream.destinationName", blankToNull(destinationName));
        c.set("stream.width", String.valueOf(width));
        c.set("stream.height", String.valueOf(height));
        c.set("stream.fps", String.valueOf(fps));
        c.set("stream.videoKbps", String.valueOf(videoKbps));
        c.set("stream.audioKbps", String.valueOf(audioKbps));
        c.set("stream.encoder", encoder.name());
        c.set("stream.micDevice", micDevice);
        c.set("stream.micVolume", String.valueOf(micVolume));
        c.set("stream.micMuted", String.valueOf(micMuted));
        c.set("stream.auxDevice", auxDevice);
        c.set("stream.auxVolume", String.valueOf(auxVolume));
        c.set("stream.auxMuted", String.valueOf(auxMuted));
        c.set("stream.masterVolume", String.valueOf(masterVolume));
        c.set("stream.masterMuted", String.valueOf(masterMuted));
        c.set("stream.mediaDevice", mediaDevice == null ? "" : mediaDevice); // "" = none, chosen on purpose
        c.set("stream.mediaVolume", String.valueOf(mediaVolume));
        c.set("stream.mediaMuted", String.valueOf(mediaMuted));
        c.save();
    }

    private static int parse(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
