package com.jamc.worship.director;

/**
 * The link between the Auto Director and the compositor: when the scene's camera source (the Auto Director's main
 * camera) is drawn on Preview or Program, the compositor asks which camera and which cut-out to show. Camera frames are
 * shared (one capture session per camera); each surface only has its own choice. The scene source's sound stays the
 * scene camera's own whatever camera the picture comes from. Nothing installed, or the director off = the camera is
 * drawn as before.
 */
public final class VirtualCameras {

    /**
     * What to show now. {@code camera} is the physical camera to draw (null = the scene source's own). While a fade
     * runs, {@code from} / {@code fromCamera} is the old picture under the new one.
     */
    public record Framing(CropRect crop, CropRect from, float fadeIn, String camera, String fromCamera) {
        public static Framing of(CropRect crop) {
            return new Framing(crop, null, 1f, null, null);
        }

        public static Framing of(CropRect crop, String camera) {
            return new Framing(crop, null, 1f, camera, null);
        }
    }

    /** Answers for one camera source on one surface; null = draw the camera as usual. */
    public interface Source {
        /**
         * @param director the source's own choice ({@code SourceConfig.autoDirector}): TRUE always, FALSE never,
         *                 null = when the source shows the Auto Director's main camera
         */
        Framing framing(String camera, Boolean director, boolean preview);

        /** The Auto Director's main camera (for a new "Auto Director" scene source), or null. */
        default String mainCamera() {
            return null;
        }
    }

    /** The Auto Director's main camera, or null when there is no director. */
    public static String mainCamera() {
        Source s = source;
        return s == null ? null : s.mainCamera();
    }

    private static volatile Source source;

    private VirtualCameras() {
    }

    public static void install(Source s) {
        source = s;
    }

    public static Framing framing(String camera, Boolean director, boolean preview) {
        Source s = source;
        return s == null || Boolean.FALSE.equals(director) ? null : s.framing(camera, director, preview);
    }
}
