package com.jamc.worship.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-source settings stored as JSON in {@code sources.config_json}. The rectangle is a fraction
 * of the 1920x1080 frame (0..1), so scenes are resolution independent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourceConfig {

    public double x;
    public double y;
    public double w = 1;
    public double h = 1;
    public FitMode fit = FitMode.FILL;
    /** Image or video file (IMAGE, CHURCH_LOGO, VIDEO). */
    public String path;
    /** Static text (TEXT) or time pattern (CLOCK, e.g. "h:mm a"). */
    public String text;
    public String color;
    public Integer fontSize;
    public String fontFamily;
    /** Camera device name (CAMERA). */
    public String camera;
    /** AUDIO_INPUT: the connected sound input it plays (a microphone, capture audio, a mixer). */
    public String audioInput;
    public Double opacity;
    /** LYRICS / BIBLE: where the text sits (e.g. BOTTOM over a camera), overriding the theme; null = theme. */
    public TextPosition textPosition;
    /** LYRICS / BIBLE: text size relative to the theme (0.6 = smaller lyrics over a camera); null = 1. */
    public Double textScale;
    /** IMAGE / VIDEO picked from the media library: the library item's id ({@link #path} is its stored file). */
    public Long mediaId;
    /** PRESENTATION: the library presentation (FIXED mode, or the last one chosen in DYNAMIC mode). */
    public Long presentationId;
    /** PRESENTATION: 0-based slide shown while no slide of this presentation is in Preview / on Program. */
    public Integer slideIndex;
    /** PRESENTATION: DYNAMIC (default) shows whichever presentation slide is sent; FIXED always this presentation. */
    public PresentationMode presentationMode;
    // ---- VIDEO sources: picture and sound (null = the default in brackets)
    /** Show the picture [on]; off plays the sound only. */
    public Boolean videoEnabled;
    /**
     * CAMERA: play the camera's own microphone / HDMI sound on Program [off: camera audio is switched on explicitly,
     * so several camera mics never echo]. VIDEO / AUDIO: play the file's sound on Program [on]. It goes quiet by itself while the operator plays another video or
     * audio (a Program video, the audio player), so only what was played is heard; Preview stays silent.
     */
    public Boolean audioEnabled;
    /** 0..150 % [100]. */
    public Integer volume;
    /** Silence this source while keeping its picture [off]. */
    public Boolean muted;
    /** Start over at the end [on for older scenes, off for videos added from the library]. */
    public Boolean loop;
    /** Start playing when the scene goes to Program [on]; off waits for PLAY in Program Media. */
    public Boolean autoplay;
    /** When the scene goes to Program: true = from the beginning, false = continue from Preview [the Settings choice]. */
    public Boolean restartOnProgram;
    /** Send this source's sound to the Preview monitor device instead of the Program output [off]. */
    public Boolean monitorOnly;
    /** WINDOW_CAPTURE: the captured window's title (when it changes, the same program's window is used). */
    public String windowTitle;
    /** WINDOW_CAPTURE: the program of the captured window, e.g. "chrome.exe". */
    public String windowExe;
    /** BROWSER: the web page shown (http / https, or a local file). */
    public String url;
    /** BROWSER: the page size in pixels [1920 × 1080]. */
    public Integer pageWidth;
    public Integer pageHeight;
    /** LYRICS picked from the song library: the song it belongs to (nothing is copied). */
    public Long songId;
    /**
     * LYRICS: true = SPECIFIC, only {@link #songId}'s lyrics show in this box (another song sent keeps this one's
     * last slide); null = dynamic, whatever lyrics are sent.
     */
    public Boolean fixedSong;
    /** Added from the service schedule: the service item it refers to. */
    public Long serviceItemId;
    /** Locked layers cannot be moved or resized on the canvas [off]. */
    public Boolean locked;
    /** CAMERA: flip the picture left to right; null = off. */
    public Boolean mirror;
    /** CAMERA: part of the picture cut off at each edge, as a fraction of its width / height (0..0.45); null = 0. */
    public Double cropLeft;
    public Double cropTop;
    public Double cropRight;
    public Double cropBottom;
    /** CAMERA: clockwise rotation in degrees (0, 90, 180, 270); null = 0. */
    public Integer rotation;
    /** CAMERA: what the box shows while the camera is disconnected or failed: SLATE ["Camera Offline"], BLACK or IMAGE. */
    public String offline;
    /** CAMERA: the image shown while offline when {@link #offline} is IMAGE (e.g. the church logo). */
    public String offlineImage;
    /** Keep the box at the 16:9 shape while resizing; null = on for PRESENTATION, off for other sources. */
    public Boolean keepAspect;
    /** A permanent id for this layer (source ids change on every save): keeps a running slideshow in place. */
    public String uid;
    /** SLIDESHOW: the image library items, in show order (referenced by id; the files are never copied). */
    public java.util.List<Long> imageIds;
    /** SLIDESHOW: seconds each image stays [5]. */
    public Integer slideSeconds;
    /** SLIDESHOW: CUT, FADE or CROSSFADE between images [FADE]. */
    public String slideTransition;
    /** SLIDESHOW: transition length in ms [500]. */
    public Integer slideTransitionMs;
    /** SLIDESHOW: random order each round [off]. */
    public Boolean shuffle;
    /** SLIDESHOW: "black" fills the box behind the image; otherwise transparent. */
    public String slideBackground;

    /** How a scene's Presentation source chooses what it shows. */
    public enum PresentationMode {
        /** Any presentation slide sent to Preview / TAKE appears in this box (e.g. a reusable Sermon scene). */
        DYNAMIC,
        /** Always the chosen presentation; its slide follows the operator when that presentation is sent. */
        FIXED;

        @Override
        public String toString() {
            return this == DYNAMIC ? "Dynamic (any presentation sent)" : "Specific file only";
        }
    }

    @JsonIgnore
    public boolean isDynamicPresentation() {
        return presentationMode != PresentationMode.FIXED;
    }

    /** LYRICS fixed to one library song. */
    @JsonIgnore
    public boolean isFixedSong() {
        return songId != null && Boolean.TRUE.equals(fixedSong);
    }

    public SourceConfig copy() {
        SourceConfig c = new SourceConfig();
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        c.fit = fit;
        c.path = path;
        c.text = text;
        c.color = color;
        c.fontSize = fontSize;
        c.fontFamily = fontFamily;
        c.camera = camera;
        c.windowTitle = windowTitle;
        c.windowExe = windowExe;
        c.url = url;
        c.pageWidth = pageWidth;
        c.pageHeight = pageHeight;
        c.audioInput = audioInput;
        c.opacity = opacity;
        c.textPosition = textPosition;
        c.textScale = textScale;
        c.mediaId = mediaId;
        c.presentationId = presentationId;
        c.slideIndex = slideIndex;
        c.presentationMode = presentationMode;
        c.keepAspect = keepAspect;
        c.mirror = mirror;
        c.cropLeft = cropLeft;
        c.cropTop = cropTop;
        c.cropRight = cropRight;
        c.cropBottom = cropBottom;
        c.rotation = rotation;
        c.offline = offline;
        c.offlineImage = offlineImage;
        c.songId = songId;
        c.fixedSong = fixedSong;
        c.serviceItemId = serviceItemId;
        c.locked = locked;
        c.videoEnabled = videoEnabled;
        c.audioEnabled = audioEnabled;
        c.volume = volume;
        c.muted = muted;
        c.loop = loop;
        c.autoplay = autoplay;
        c.restartOnProgram = restartOnProgram;
        c.monitorOnly = monitorOnly;
        c.uid = uid;
        c.imageIds = imageIds == null ? null : new java.util.ArrayList<>(imageIds);
        c.slideSeconds = slideSeconds;
        c.slideTransition = slideTransition;
        c.slideTransitionMs = slideTransitionMs;
        c.shuffle = shuffle;
        c.slideBackground = slideBackground;
        return c;
    }
}
