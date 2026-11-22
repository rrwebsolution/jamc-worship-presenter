package com.jamc.worship.camera;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectCameraTest {

    @Test
    void iriunIsReadAsYuy2WithLowDelay() {
        String cmd = String.join(" ", DirectCamera.command("ffmpeg", "Iriun Webcam", new CameraManager.Format("1280x720", 30)));
        assertTrue(cmd.contains("-fflags nobuffer"), cmd);
        assertTrue(cmd.contains("-video_size 1280x720 -framerate 30"), cmd);
        assertTrue(cmd.contains("-pixel_format yuyv422"), "true colors on Iriun: " + cmd);
        assertTrue(cmd.contains("-rtbufsize 2027520"), "1 picture waiting at most, no long camera delay: " + cmd);
        assertTrue(cmd.contains("-fps_mode vfr"), "no invented copies after a hiccup: " + cmd);
        assertTrue(cmd.endsWith("-i video=Iriun Webcam -an -vf " + DirectCamera.UP_TO_1080 + " -fps_mode vfr -f rawvideo -pix_fmt bgra pipe:1"), cmd);
    }

    @Test
    void otherCamerasAndTheDriverDefaultAskForNothingSpecial() {
        List<String> c = DirectCamera.command("ffmpeg", "Integrated Camera", new CameraManager.Format(null, 0));
        assertFalse(c.contains("-video_size"));
        assertFalse(c.contains("-pixel_format"));
    }

    @Test
    void aMacCameraIsReadThroughAvFoundationAt30() {
        String cmd = String.join(" ", DirectCamera.macCommand("ffmpeg", "FaceTime HD Camera", new CameraManager.Format(null, 0)));
        assertTrue(cmd.contains("-f avfoundation -framerate 30"), cmd);
        assertTrue(cmd.endsWith("-i FaceTime HD Camera:none -an -vf " + DirectCamera.UP_TO_1080 + " -fps_mode vfr -f rawvideo -pix_fmt bgra pipe:1"), cmd);
    }

    @Test
    void thePictureSizeIsReadFromFFmpegsOutputLine() {
        assertArrayEquals(new int[]{1280, 720}, DirectCamera.outputSize(
                "  Stream #0:0: Video: rawvideo (BGRA / 0x41524742), bgra(pc, gbr/unknown/unknown, progressive), 1280x720, q=2-31, 884736 kb/s, 30 fps"));
        assertNull(DirectCamera.outputSize("  Stream #0:0: Video: rawvideo (YUY2), yuyv422"));
    }

    @Test
    void cameraWaitingRoomHoldsOnlyAFewPictures() {
        assertEquals(1280L * 720 * 2 * 11 / 10, DirectCamera.realTimeBuffer("1280x720", false));
        assertEquals(1920L * 1080 * 2 * 11 / 10, DirectCamera.realTimeBuffer("1920x1080", false));
        assertEquals(1920L * 1080 * 2 * 11 / 10, DirectCamera.realTimeBuffer(null, false));
        assertEquals(1L << 20, DirectCamera.realTimeBuffer("320x240", false));
        assertEquals(64L << 20, DirectCamera.realTimeBuffer("7680x4320", false));
        assertEquals(1920L * 1080 / 2, DirectCamera.realTimeBuffer("1920x1080", true));
    }

    @Test
    void recognisesCompressedCameraPictures() {
        assertTrue(DirectCamera.compressedInput(
                "  Stream #0:0: Video: mjpeg (Baseline) (MJPG / 0x47504A4D), yuvj422p(pc, bt470bg/unknown/unknown), 1920x1080, 30 fps"));
        assertFalse(DirectCamera.compressedInput(
                "  Stream #0:0: Video: rawvideo (YUY2 / 0x32595559), yuyv422, 1280x720, 30 fps, 30 tbr"));
    }
}
