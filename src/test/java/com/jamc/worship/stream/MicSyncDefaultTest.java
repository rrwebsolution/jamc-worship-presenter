package com.jamc.worship.stream;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicSyncDefaultTest {

    @Test
    void microphoneWaitsForTheCameraByDefault(@TempDir Path dir) {
        AppPaths paths = new AppPaths(dir);
        paths.ensureDirectories();
        MixerChannels m = new MixerChannels(new AppConfig(paths));
        assertEquals(MixerChannels.DEFAULT_MIC_SYNC_MS, m.channel(MixerChannels.MIC).syncMs);
        assertTrue(m.chain(MixerChannels.MIC).contains("adelay=delays=" + MixerChannels.DEFAULT_MIC_SYNC_MS));
    }

    @Test
    void aChoiceOfTheOperatorIsKept(@TempDir Path dir) {
        AppPaths paths = new AppPaths(dir);
        paths.ensureDirectories();
        MixerChannels first = new MixerChannels(new AppConfig(paths));
        first.channel(MixerChannels.MIC).syncMs = 0; // the operator sets it back to 0
        first.changed();
        AppConfig reread = new AppConfig(paths);
        reread.load();
        MixerChannels again = new MixerChannels(reread);
        assertEquals(0, again.channel(MixerChannels.MIC).syncMs);
    }

    @Test
    void anOffsetAlreadySetIsNotChanged(@TempDir Path dir) {
        AppPaths paths = new AppPaths(dir);
        paths.ensureDirectories();
        AppConfig config = new AppConfig(paths);
        config.set(MixerChannels.KEY, "{\"channels\":{\"mic\":{\"syncMs\":300}}}");
        config.save();
        AppConfig reread = new AppConfig(paths);
        reread.load();
        MixerChannels m = new MixerChannels(reread);
        assertEquals(300, m.channel(MixerChannels.MIC).syncMs);
    }
}
