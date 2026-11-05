package com.jamc.worship.ui.components;

import com.jamc.worship.stream.StreamEncoder;
import com.jamc.worship.stream.StreamManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusBarTest {

    private static StreamManager.Session session(StreamManager.State state, String detail, StreamEncoder.Stats stats) {
        return new StreamManager.Session(state, detail, stats, System.currentTimeMillis(), null);
    }

    @Test
    void theFooterSaysWhatTheLivestreamIsDoing() {
        assertEquals("Not live", StatusBar.liveText(session(StreamManager.State.IDLE, null, null)));
        assertEquals("Reconnecting (2/10)…", StatusBar.liveText(session(StreamManager.State.STARTING, "Reconnecting (2/10)…", null)));
        assertEquals("Connecting the livestream…", StatusBar.liveText(session(StreamManager.State.STARTING, null, null)));
        assertTrue(StatusBar.liveText(session(StreamManager.State.ERROR, "the key", null)).startsWith("Livestream stopped"));
        String onAir = StatusBar.liveText(session(StreamManager.State.ACTIVE, null, new StreamEncoder.Stats(900, 29.9, 0, 12, 30_000)));
        assertTrue(onAir.startsWith("LIVE 00:00:00") && onAir.endsWith("30 fps  ·  dropped 12"), onAir);
    }
}
