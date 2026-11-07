package com.jamc.worship.stream;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixerChannelsTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void reset() {
        MixerChannels.install(null);
    }

    private static StreamSettings settings(String mic) {
        return new StreamSettings("FACEBOOK", null, "JAMC Tagoloan", 1920, 1080, 30, 6000, 160,
                StreamSettings.EncoderChoice.AUTO, mic, 80, false, null, 60, false, 100, false, null, 100, false);
    }

    @Test
    void nothingSetLeavesTheSoundAlone() {
        MixerChannels.install(null);
        assertEquals("", MixerChannels.get().chain(MixerChannels.MIC));
        String cmd = String.join(" ", FFmpegStreamEncoder.command("ffmpeg", settings("Mic"), "libx264",
                FFmpegStreamEncoder.Target.RECORDING, "x.mp4"));
        assertTrue(cmd.contains("[1:a]volume@mic="), cmd);
        assertTrue(cmd.contains("[a1]anull[aout]"), cmd);
    }

    @Test
    void filtersMonoBalanceAndDelayBecomeTheChannelsChainInOrder() {
        MixerChannels.Channel c = new MixerChannels.Channel();
        c.filters.add(AudioFilter.of(AudioFilter.Type.NOISE_SUPPRESSION));
        AudioFilter off = AudioFilter.of(AudioFilter.Type.GAIN);
        off.enabled = false;
        c.filters.add(off);
        c.filters.add(AudioFilter.of(AudioFilter.Type.LIMITER));
        c.mono = true;
        c.balance = 50;
        c.syncMs = 120;
        String chain = MixerChannels.chain(c);
        assertEquals("afftdn=nr=20:nf=-50,alimiter=limit=0.5012:release=60:level=0,aformat=channel_layouts=stereo,"
                + "pan=stereo|c0=0.25*c0+0.25*c1|c1=0.5*c0+0.5*c1,adelay=delays=120:all=1", chain);
        assertFalse(chain.contains("volume="), "a switched-off filter is left out");
    }

    @Test
    void eachFilterTypeGivesItsFfmpegFilter() {
        assertEquals("agate=threshold=0.01:range=0.001:attack=25:release=150",
                AudioFilter.of(AudioFilter.Type.NOISE_GATE).ffmpeg());
        AudioFilter gain = AudioFilter.of(AudioFilter.Type.GAIN);
        gain.params.put("gain", 6.0);
        assertEquals("volume=6dB", gain.ffmpeg());
        assertEquals("acompressor=threshold=0.1259:ratio=4:attack=6:release=60:makeup=1",
                AudioFilter.of(AudioFilter.Type.COMPRESSOR).ffmpeg());
        AudioFilter tooHigh = AudioFilter.of(AudioFilter.Type.NOISE_SUPPRESSION);
        tooHigh.params.put("reduction", 500.0);
        assertEquals("afftdn=nr=60:nf=-50", tooHigh.ffmpeg(), "kept inside its range");
    }

    @Test
    void theEncoderPutsChannelFiltersBeforeTheLevelAndMasterFiltersAfterTheMix() throws Exception {
        AppConfig config = new AppConfig(new AppPaths(tempDir.resolve("home")));
        MixerChannels.install(config);
        MixerChannels.get().channel(MixerChannels.MIC).filters.add(AudioFilter.of(AudioFilter.Type.NOISE_SUPPRESSION));
        MixerChannels.get().channel(MixerChannels.MASTER).filters.add(AudioFilter.of(AudioFilter.Type.LIMITER));
        MixerChannels.get().changed();
        String cmd = String.join(" ", FFmpegStreamEncoder.command("ffmpeg", settings("Mic"), "libx264",
                FFmpegStreamEncoder.Target.RECORDING, "x.mp4"));
        assertTrue(cmd.contains("[1:a]afftdn=nr=20:nf=-50,volume@mic="), "the level stays live after the filters: " + cmd);
        assertTrue(cmd.contains("[a1]anull[mixed];[mixed]alimiter="), cmd);
        assertTrue(cmd.contains("[aout]"), cmd);
    }

    @Test
    void settingsAreKeptAcrossRestarts() throws Exception {
        AppPaths paths = new AppPaths(tempDir.resolve("home"));
        AppConfig config = new AppConfig(paths);
        MixerChannels.install(config);
        MixerChannels.Channel c = MixerChannels.get().channel(MixerChannels.input("Microphone (Iriun Webcam)"));
        c.name = "PULPIT MIC";
        c.hidden = true;
        c.pinned = true;
        c.locked = true;
        c.filters.add(AudioFilter.of(AudioFilter.Type.NOISE_GATE));
        MixerChannels.get().options().horizontal = true;
        MixerChannels.get().options().showHidden = true;
        MixerChannels.get().changed();

        AppConfig again = new AppConfig(paths);
        again.load();
        MixerChannels.install(again);
        MixerChannels.Channel back = MixerChannels.get().channel(MixerChannels.input("Microphone (Iriun Webcam)"));
        assertEquals("PULPIT MIC", back.name);
        assertTrue(back.hidden && back.pinned && back.locked);
        assertEquals(AudioFilter.Type.NOISE_GATE, back.filters.getFirst().type);
        assertTrue(MixerChannels.get().options().horizontal);
        assertTrue(MixerChannels.get().options().showHidden);
        assertFalse(MixerChannels.get().options().showInactive, "unchecked by default");
    }
}
