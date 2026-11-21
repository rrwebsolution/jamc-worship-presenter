package com.jamc.worship.stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncTestTest {

    /** Claps at 1.0, 2.1, 3.0 s in the picture; the sound hears them {@code lateMs} later, over room noise and movement. */
    private static SyncTest.Result run(int lateMs) {
        double fps = 30;
        int frames = 8 * 30;
        double[] vT = new double[frames];
        double[] vDif = new double[frames];
        double[] claps = {1.0, 2.1, 3.0};
        java.util.Random rnd = new java.util.Random(4);
        for (int i = 0; i < frames; i++) {
            vT[i] = i / fps;
            vDif[i] = 2 + rnd.nextDouble() * 1.5; // a person moving a little
            for (double c : claps) {
                if (Math.abs(vT[i] - c) < 0.5 / fps) {
                    vDif[i] = 18;
                }
            }
        }
        int chunks = 800;
        double[] aT = new double[chunks];
        double[] aAmp = new double[chunks];
        for (int i = 0; i < chunks; i++) {
            aT[i] = i * 0.01;
            aAmp[i] = 0.01 + rnd.nextDouble() * 0.02; // room
            for (double c : claps) {
                double heard = c + lateMs / 1000.0;
                if (aT[i] >= heard - 0.005 && aT[i] < heard + 0.005) {
                    aAmp[i] = 0.9;
                } else if (aT[i] >= heard && aT[i] < heard + 0.06) {
                    aAmp[i] = Math.max(aAmp[i], 0.3); // the clap's ring
                }
            }
        }
        return SyncTest.estimate(aT, aAmp, vT, vDif);
    }

    @Test
    void voiceLateIsMeasured() {
        SyncTest.Result r = run(450);
        assertTrue(r.ok(), r.problem());
        assertEquals(3, r.claps());
        assertEquals(3, r.matched());
        assertTrue(Math.abs(r.audioLateMs() - 450) <= 35, "measured " + r.audioLateMs());
    }

    @Test
    void voiceEarlyIsMeasured() {
        SyncTest.Result r = run(-200);
        assertTrue(r.ok(), r.problem());
        assertTrue(Math.abs(r.audioLateMs() + 200) <= 35, "measured " + r.audioLateMs());
    }

    @Test
    void inSyncIsAboutZero() {
        SyncTest.Result r = run(0);
        assertTrue(r.ok(), r.problem());
        assertTrue(Math.abs(r.audioLateMs()) <= 35, "measured " + r.audioLateMs());
    }

    @Test
    void silenceSaysNoClap() {
        double[] aT = new double[600];
        double[] aAmp = new double[600];
        double[] vT = new double[100];
        double[] vDif = new double[100];
        for (int i = 0; i < 600; i++) {
            aT[i] = i * 0.01;
        }
        for (int i = 0; i < 100; i++) {
            vT[i] = i / 30.0;
        }
        SyncTest.Result r = SyncTest.estimate(aT, aAmp, vT, vDif);
        assertFalse(r.ok());
        assertTrue(r.problem().startsWith("No clap"));
    }

    @Test
    void newOffsetTakesOffTheLateness() {
        assertEquals(20, SyncTest.newSyncMs(450, 430));
        assertEquals(0, SyncTest.newSyncMs(100, 300)); // never below 0
        assertEquals(650, SyncTest.newSyncMs(450, -200)); // voice early: wait more
    }

    @Test
    void parsesFFmpegLines() {
        SyncTest.Series s = SyncTest.parse(List.of(
                "[Parsed_ametadata_3 @ 0000] frame:0    pts:0       pts_time:0",
                "[Parsed_ametadata_3 @ 0000] lavfi.astats.Overall.Peak_level=-inf",
                "[Parsed_ametadata_3 @ 0000] frame:1    pts:80      pts_time:0.01",
                "[Parsed_ametadata_3 @ 0000] lavfi.astats.Overall.Peak_level=-0.583525"));
        assertEquals(2, s.times().length);
        assertEquals(-120, s.values()[0]);
        assertEquals(-0.583525, s.values()[1], 1e-9);
    }

    /** End to end with FFmpeg: a flash in the picture and a click 300 ms later in the sound. */
    @Test
    void measuresARealFile(@TempDir Path dir) throws Exception {
        Path ffmpeg = FFmpeg.locate(null);
        Assumptions.assumeTrue(ffmpeg != null, "FFmpeg not installed");
        Path file = dir.resolve("late300.mp4");
        Process p = new ProcessBuilder(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=0x303030:s=640x360:r=30:d=6,drawbox=x=200:y=100:w=240:h=160:c=white:t=fill"
                        + ":enable='lt(mod(t+0.001,1),0.034)*gte(t,0.9)'",
                "-f", "lavfi", "-i", "aevalsrc='0.02*(random(0)-0.5)+if(lt(mod(t-0.3,1),0.004)*gte(t,1.2),"
                        + "0.9*sin(2*PI*2000*t),0)':s=48000:d=6",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", file.toString()))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertEquals(0, p.waitFor());
        SyncTest.Result r = SyncTest.measure(ffmpeg, file);
        assertTrue(r.ok(), r.problem());
        assertTrue(r.matched() >= 3, "matched " + r.matched());
        assertTrue(Math.abs(r.audioLateMs() - 300) <= 40, "measured " + r.audioLateMs());
    }
}
