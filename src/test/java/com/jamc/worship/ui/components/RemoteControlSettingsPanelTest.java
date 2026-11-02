package com.jamc.worship.ui.components;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.remote.RemoteManager;
import com.jamc.worship.remote.RemotePermission;
import com.jamc.worship.remote.RemoteRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings → Remote Control controls change the manager, and devices show up as they connect. */
class RemoteControlSettingsPanelTest {

    @TempDir
    Path tempDir;

    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                found.add(type.cast(c));
            }
            if (c instanceof Container k) {
                found.addAll(all(k, type));
            }
        }
        return found;
    }

    private static AbstractButton button(Container root, String text) {
        return all(root, AbstractButton.class).stream().filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }

    private static boolean shows(Container root, String text) {
        return all(root, JLabel.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains(text));
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void layOut(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) {
            if (k instanceof Container child) {
                layOut(child);
            }
        }
    }

    private RemoteManager manager() {
        return new RemoteManager(new AppConfig(new AppPaths(tempDir)), tempDir.resolve("config/remote-devices.json"));
    }

    /** The Settings page can be wider than the window, so the QR code must not sit at the far edge. */
    @Test
    void theQrCodeStaysBesideTheControlsOnAVeryWidePage() throws Exception {
        RemoteManager remote = manager();
        SwingUtilities.invokeAndWait(() -> {
            RemoteControlSettingsPanel panel = new RemoteControlSettingsPanel(remote);
            panel.setSize(3000, 800);
            layOut(panel);
            Component qr = all(panel, Component.class).stream().filter(c -> c.getClass().getSimpleName().equals("QrView")).findFirst().orElseThrow();
            assertTrue(SwingUtilities.convertPoint(qr, qr.getWidth(), 0, panel).x < 1100, "the QR code is out at x=" + qr.getX());
            assertTrue(qr.getWidth() > 100, "the QR code has its size");
        });
    }

    @Test
    void theEditorsRoleFollowsItsCheckboxesAndOnlyLyricsStartsTicked() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RemotePermissionEditor editor = new RemotePermissionEditor(RemotePermission.defaults());
            assertEquals(Set.of(RemotePermission.LYRICS), editor.permissions());
            assertEquals("Lyrics Only", RemotePermissionEditor.summary(editor.permissions()));
            editor.box(RemotePermission.TAKE).doClick();
            assertEquals(EnumSet.of(RemotePermission.LYRICS, RemotePermission.TAKE), editor.permissions());
            assertEquals(RemoteRole.CUSTOM, RemoteRole.of(editor.permissions()));
        });
    }

    @Test
    void controlsDriveTheManagerAndDevicesAppear() throws Exception {
        RemoteManager remote = manager();
        try (var free = new java.net.ServerSocket(0)) {
            remote.setPort(free.getLocalPort());
        }
        int port = remote.port();
        remote.setEnabled(true);
        // only the manager holds the token: the link in the QR code is how a device learns it
        String token = URI.create(remote.pairingLink()).getQuery().replaceAll(".*token=", "");
        try (HttpClient client = HttpClient.newHttpClient()) {
            RemoteControlSettingsPanel[] holder = new RemoteControlSettingsPanel[1];
            SwingUtilities.invokeAndWait(() -> holder[0] = new RemoteControlSettingsPanel(remote));
            RemoteControlSettingsPanel panel = holder[0];
            flush();
            assertTrue(shows(panel, "RUNNING"));
            assertTrue(shows(panel, "No devices yet"));

            CountDownLatch answered = new CountDownLatch(2); // pending, then welcome
            WebSocket socket = client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    answered.countDown();
                    ws.request(1);
                    return null;
                }
            }).get(5, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"hello\",\"protocol\":2,\"name\":\"Tablet\",\"pairingToken\":\"" + token + "\"}", true).join();
            for (int i = 0; i < 100 && remote.pairingRequests().isEmpty(); i++) {
                Thread.sleep(50);
            }
            flush();
            assertTrue(shows(panel, "waiting for approval"), "the waiting device shows");
            assertTrue(remote.devices().isEmpty(), "and is not trusted yet");

            SwingUtilities.invokeAndWait(() -> button(panel, "Allow").doClick());
            assertTrue(answered.await(5, TimeUnit.SECONDS));
            flush();
            assertTrue(shows(panel, "Tablet"));
            assertTrue(shows(panel, "Lyrics Only"));
            assertFalse(shows(panel, "No devices yet"));
            assertFalse(shows(panel, "waiting for approval"));
            assertEquals(Set.of(RemotePermission.LYRICS), remote.devices().getFirst().device().permissions());
            assertTrue(button(panel, "Disconnect").isEnabled());

            SwingUtilities.invokeAndWait(() -> button(panel, "Disconnect").doClick());
            flush();
            assertFalse(remote.devices().getFirst().session());
            assertFalse(button(panel, "Disconnect").isEnabled());

            SwingUtilities.invokeAndWait(() -> button(panel, "Lock Remote Commands").doClick());
            flush();
            assertTrue(remote.locked());
            assertTrue(shows(panel, "Locked"));
            SwingUtilities.invokeAndWait(() -> button(panel, "Unlock Remote Commands").doClick());
            flush();
            assertFalse(remote.locked());

            SwingUtilities.invokeAndWait(() -> button(panel, "Disable All Remotes").doClick());
            flush();
            assertTrue(remote.disabled());
            SwingUtilities.invokeAndWait(() -> button(panel, "Enable Remotes Again").doClick());
            flush();
            assertFalse(remote.disabled());

            remote.forget(remote.devices().getFirst().device().id());
            flush();
            assertTrue(shows(panel, "No devices yet"));
        } finally {
            remote.shutdown();
        }
    }
}
