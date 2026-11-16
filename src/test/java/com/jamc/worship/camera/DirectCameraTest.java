package com.jamc.worship.camera;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertTrue(cmd.endsWith("-i video=Iriun Webcam -an -f rawvideo -pix_fmt bgra pipe:1"), cmd);
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
        assertTrue(cmd.endsWith("-i FaceTime HD Camera:none -an -f rawvideo -pix_fmt bgra pipe:1"), cmd);
    }

    @Test
    void thePictureSizeIsReadFromFFmpegsOutputLine() {
        assertArrayEquals(new int[]{1280, 720}, DirectCamera.outputSize(
                "  Stream #0:0: Video: rawvideo (BGRA / 0x41524742), bgra(pc, gbr/unknown/unknown, progressive), 1280x720, q=2-31, 884736 kb/s, 30 fps"));
        assertNull(DirectCamera.outputSize("  Stream #0:0: Video: rawvideo (YUY2), yuyv422"));
    }
}
