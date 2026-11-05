package com.jamc.worship.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real server on a real local port, the way a phone uses it, with a stand-in for the desktop. */
class RemoteServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Device> opened = new ArrayList<>();
    private final FakeDesk desk = new FakeDesk();
    private RemoteManager manager;
    private int port;

    /** Records commands, and always has the same state. */
    private static final class FakeDesk implements RemoteDesk {
        final BlockingQueue<RemoteCommand> ran = new LinkedBlockingQueue<>();
        final Map<String, Object> state = new ConcurrentHashMap<>(Map.of(
                "monitors", Map.of("preview", Map.of("title", "P"), "program", Map.of("title", "L"), "canTake", true),
                "lyrics", Map.of("title", "Amazing Grace"),
                "cameras", List.of(Map.of("id", "cam", "name", "CAM 1")),
                "stream", Map.of("state", "IDLE")));
        volatile String result;
        volatile Set<RemotePermission> lastPermissions;
        volatile boolean onUiThread;

        @Override
        public Map<String, Object> snapshot() {
            return new HashMap<>(state);
        }

        @Override
        public String execute(RemoteCommand command, JsonNode payload, Set<RemotePermission> permissions) {
            onUiThread = java.awt.EventQueue.isDispatchThread();
            lastPermissions = permissions;
            ran.add(command);
            return result;
        }

        @Override
        public Object query(RemoteQuery query, JsonNode params) {
            return List.of(Map.of("title", query.name() + ":" + params.path("search").asText("")));
        }

        @Override
        public BufferedImage[] monitorPictures() {
            return new BufferedImage[]{new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)};
        }
    }

    /** A device on the WebSocket: what it sends, and the messages it receives in order. */
    private final class Device implements WebSocket.Listener {
        final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final StringBuilder partial = new StringBuilder();
        WebSocket socket;

        Device open(String origin) throws Exception {
            WebSocket.Builder b = client.newWebSocketBuilder();
            if (origin != null) {
                b.header("Origin", origin);
            }
            socket = b.buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), this).get(5, TimeUnit.SECONDS);
            opened.add(this);
            return this;
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        JsonNode next() throws InterruptedException {
            JsonNode message = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "no message from the server");
            return message;
        }

        /** The next message of this type; others in between are skipped. */
        JsonNode next(String type) throws InterruptedException {
            JsonNode message = next();
            while (!type.equals(message.path("type").asText())) {
                message = next();
            }
            return message;
        }

        /** The next value of this slice. */
        JsonNode slice(String name) throws InterruptedException {
            JsonNode message = next("state");
            while (!name.equals(message.path("slice").asText())) {
                message = next("state");
            }
            return message.path("value");
        }

        void command(String id, String command, String payload) {
            send("{\"type\":\"command\",\"requestId\":\"" + id + "\",\"command\":\"" + command + "\",\"payload\":" + payload + "}");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    received.add(JSON.readTree(partial.toString()));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.countDown();
            return null;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        AppConfig config = new AppConfig(new AppPaths(tempDir));
        manager = new RemoteManager(config, tempDir.resolve("config/remote-devices.json"));
        manager.setDesk(desk);
        port = freePort();
        manager.setPort(port);
        manager.setEnabled(true);
        assertTrue(manager.running(), manager.problem());
    }

    @AfterEach
    void tearDown() {
        opened.forEach(d -> d.socket.abort());
        manager.shutdown();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private Device hello(String name) throws Exception {
        Device d = new Device().open(null);
        d.send("{\"type\":\"hello\",\"protocol\":2,\"pairingToken\":\"" + manager.pairingToken() + "\",\"name\":\"" + name + "\"}");
        return d;
    }

    /** Scans the QR code, the operator allows it with these permissions, and it is welcomed. */
    private Device pair(String name, Set<RemotePermission> permissions) throws Exception {
        Device d = hello(name);
        assertEquals("pending", d.next().path("type").asText());
        String request = manager.pairingRequests().getFirst().id();
        manager.approve(request, permissions, null);
        return d;
    }

    /** Sends a raw request, because the JDK client does not allow setting Host. Returns the status line. */
    private String rawStatus(String host) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/status HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = new String(s.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            return response.lines().findFirst().orElse("");
        }
    }

    @Test
    void servesAPageAndStatus() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));

        JsonNode status = JSON.readTree(get("/api/status").body());
        assertEquals("LiveCat Multimedia", status.path("app").asText());
        assertFalse(status.path("locked").asBoolean());
        assertEquals(404, get("/api/nothing").statusCode());
        assertEquals(page.body(), get("/pair").body(), "the app routes its own pages");
        if (RemoteServer.hasBundledApp()) {
            assertEquals(404, get("/missing.png").statusCode());
            assertFalse(get("/../secret.txt").statusCode() == 200, "nothing outside the app is served");
        }
    }

    @Test
    void aNewDeviceWaitsThenGetsLyricsOnlyAndATokenWhenAllowed() throws Exception {
        Device d = hello("Worship leader");
        assertEquals("pending", d.next().path("type").asText());
        assertTrue(manager.devices().isEmpty());

        manager.approve(manager.pairingRequests().getFirst().id(), manager.defaultPermissions(), null);
        JsonNode welcome = d.next("welcome");
        assertFalse(welcome.path("deviceToken").asText().isEmpty());
        assertEquals("[\"LYRICS\"]", welcome.path("permissions").toString());
        assertEquals("Lyrics Only", welcome.path("role").asText());
        assertEquals("Worship leader", manager.devices().getFirst().device().name());
        assertEquals("127.0.0.1", manager.devices().getFirst().device().address());
        assertTrue(manager.devices().getFirst().online());

        d.send("{\"type\":\"ping\"}");
        assertEquals("pong", d.next("pong").path("type").asText());
    }

    @Test
    void aDeniedDeviceIsToldAndClosed() throws Exception {
        Device d = hello("A");
        d.next("pending");
        manager.deny(manager.pairingRequests().getFirst().id());
        assertEquals("denied", d.next("rejected").path("code").asText());
        assertTrue(d.closed.await(5, TimeUnit.SECONDS));
        assertTrue(manager.devices().isEmpty());
    }

    @Test
    void aDeviceClosingWhileWaitingRemovesItsRequest() throws Exception {
        Device d = hello("A");
        d.next("pending");
        assertEquals(1, manager.pairingRequests().size());
        d.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        for (int i = 0; i < 100 && !manager.pairingRequests().isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertTrue(manager.pairingRequests().isEmpty());
    }

    @Test
    void withoutThePairingTokenNobodyJoins() throws Exception {
        Device d = new Device().open(null);
        d.send("{\"type\":\"hello\",\"protocol\":2,\"pairingToken\":\"guess\"}");
        JsonNode rejected = d.next();
        assertEquals("rejected", rejected.path("type").asText());
        assertEquals("pairing_required", rejected.path("code").asText());
        assertTrue(d.closed.await(5, TimeUnit.SECONDS));
        assertTrue(manager.devices().isEmpty());
        assertTrue(manager.pairingRequests().isEmpty());

        Device noHello = new Device().open(null);
        noHello.command("1", "TAKE", "{}");
        assertEquals("bad_request", noHello.next().path("code").asText());
        assertTrue(desk.ran.isEmpty());

        Device waiting = hello("A");
        waiting.next("pending");
        waiting.command("1", "TAKE", "{}");
        assertEquals("bad_request", waiting.next("rejected").path("code").asText(), "a waiting device may not run anything");
        assertTrue(desk.ran.isEmpty());
    }

    @Test
    void aTrustedDeviceComesBackWithItsTokenAfterTheQrCodeChanged() throws Exception {
        String token = pair("A", RemotePermission.defaults()).next("welcome").path("deviceToken").asText();
        manager.newPairingToken();
        Device again = new Device().open(null);
        again.send("{\"type\":\"hello\",\"protocol\":2,\"deviceToken\":\"" + token + "\"}");
        JsonNode welcome = again.next("welcome");
        assertNull(welcome.get("deviceToken"), "the token is only sent when the device is created");
        assertEquals("A", welcome.path("name").asText());
        assertEquals(1, manager.devices().size());
    }

    @Test
    void aCommandTheDeviceMayNotRunIsRefusedAtTheServer() throws Exception {
        Device d = pair("A", RemotePermission.defaults());
        d.next("welcome");
        for (String refused : List.of("TAKE", "BLACK_PROJECTOR", "SET_CAMERA_PREVIEW", "SET_SCENE_PREVIEW", "PRESENTATION_NEXT", "MEDIA_PLAY")) {
            d.command("c", refused, "{}");
            JsonNode result = d.next("result");
            assertFalse(result.path("ok").asBoolean(), refused);
            assertEquals("no_permission", result.path("code").asText(), refused);
        }
        assertTrue(desk.ran.isEmpty(), "none of them reached the desktop");

        d.command("c", "NOT_A_COMMAND", "{}");
        assertEquals("not_supported", d.next("result").path("code").asText());
    }

    @Test
    void cameraPermissionDoesNotGrantTakeAndPreviewIsAlsoNeeded() throws Exception {
        Device d = pair("A", EnumSet.of(RemotePermission.CAMERAS));
        d.next("welcome");
        d.command("c", "SET_CAMERA_PREVIEW", "{\"id\":\"cam\"}");
        assertEquals("no_permission", d.next("result").path("code").asText(), "Cameras without Preview only watches");

        manager.setPermission(manager.devices().getFirst().device().id(), RemotePermission.PREVIEW, true);
        d.command("c2", "SET_CAMERA_PREVIEW", "{\"id\":\"cam\"}");
        assertTrue(d.next("result").path("ok").asBoolean());
        assertEquals(RemoteCommand.SET_CAMERA_PREVIEW, desk.ran.poll(5, TimeUnit.SECONDS));

        d.command("c3", "TAKE", "{}");
        assertEquals("no_permission", d.next("result").path("code").asText());
        assertTrue(desk.ran.isEmpty());
    }

    @Test
    void anAllowedCommandRunsOnTheUiThreadWithTheDevicesPermissions() throws Exception {
        Device d = pair("A", EnumSet.of(RemotePermission.TAKE));
        d.next("welcome");
        d.command("req-1", "TAKE", "{}");
        JsonNode result = d.next("result");
        assertEquals("req-1", result.path("requestId").asText());
        assertTrue(result.path("ok").asBoolean());
        assertEquals(RemoteCommand.TAKE_PREVIEW, desk.ran.poll(5, TimeUnit.SECONDS));
        assertTrue(desk.onUiThread);
        assertEquals(Set.of(RemotePermission.TAKE), desk.lastPermissions);

        desk.result = "nothing_in_preview";
        d.command("req-2", "TAKE", "{}");
        JsonNode failed = d.next("result");
        assertFalse(failed.path("ok").asBoolean());
        assertEquals("nothing_in_preview", failed.path("code").asText());
    }

    private void query(Device d, String id, String query) {
        d.send("{\"type\":\"query\",\"requestId\":\"" + id + "\",\"query\":\"" + query + "\",\"params\":{\"search\":\"grace\"}}");
    }

    @Test
    void aDeviceMayOnlyBrowseTheLibraryOfItsRole() throws Exception {
        Device d = pair("A", RemotePermission.defaults());
        d.next("welcome");
        query(d, "q1", "SONGS");
        JsonNode songs = d.next("result");
        assertTrue(songs.path("ok").asBoolean());
        assertEquals("SONGS:grace", songs.path("data").get(0).path("title").asText());

        for (String refused : List.of("BIBLE_VERSIONS", "BIBLE_THUMBNAIL", "PRESENTATIONS", "PRESENTATION_THUMBNAIL", "CAMERA_PICTURE", "MEDIA_ITEMS", "SERVICES")) {
            query(d, "q2", refused);
            assertEquals("no_permission", d.next("result").path("code").asText(), refused);
        }
        for (String refused : List.of("OPEN_BIBLE", "OPEN_PRESENTATION", "PRESENTATION_SELECT", "OPEN_MEDIA", "OPEN_SERVICE", "BIBLE_CLEAR", "PRESENTATION_CLEAR", "MEDIA_CLEAR")) {
            d.command("c", refused, "{}");
            assertEquals("no_permission", d.next("result").path("code").asText(), refused);
        }
        assertTrue(desk.ran.isEmpty());

        query(d, "q3", "NOT_A_QUERY");
        assertEquals("not_supported", d.next("result").path("code").asText());
    }

    @Test
    void lockAllowsBrowsingButNotOpening() throws Exception {
        Device d = pair("A", RemotePermission.defaults());
        d.next("welcome");
        manager.setLocked(true);
        query(d, "q", "SONGS");
        assertTrue(d.next("result").path("ok").asBoolean());
        d.command("c", "OPEN_SONG", "{\"id\":1}");
        assertEquals("locked", d.next("result").path("code").asText());
        assertTrue(desk.ran.isEmpty());
    }

    @Test
    void onlyADeviceThatAsksIsSentTheMonitorPictures() throws Exception {
        Device watching = pair("A", RemotePermission.defaults());
        watching.next("welcome");
        Device other = pair("B", RemotePermission.defaults());
        other.next("welcome");
        watching.send("{\"type\":\"watch\",\"pictures\":true}");
        assertEquals("pictures", waitForSlice(watching, RemoteServer.PICTURES).path("slice").asText());

        other.send("{\"type\":\"ping\"}");
        other.next("pong");
        for (JsonNode m : other.received) {
            assertFalse(RemoteServer.PICTURES.equals(m.path("slice").asText()), "pictures went to a device that did not ask");
        }
    }

    private JsonNode waitForSlice(Device d, String name) throws InterruptedException {
        JsonNode m = d.next("state");
        while (!name.equals(m.path("slice").asText())) {
            m = d.next("state");
        }
        return m;
    }

    @Test
    void aWaitingDeviceCannotBrowse() throws Exception {
        Device d = hello("A");
        d.next("pending");
        query(d, "q", "SONGS");
        assertEquals("bad_request", d.next("rejected").path("code").asText());
    }

    @Test
    void aDeviceOnlyReceivesTheStateItMaySee() throws Exception {
        Device d = pair("A", RemotePermission.defaults());
        d.next("welcome");
        manager.publishState();
        Map<String, JsonNode> seen = new HashMap<>();
        for (int i = 0; i < 40 && !(seen.containsKey("cameras") && seen.get("lyrics") != null && !seen.get("lyrics").isNull()); i++) {
            JsonNode m = d.next("state");
            seen.put(m.path("slice").asText(), m.path("value"));
        }
        assertEquals("Amazing Grace", seen.get("lyrics").path("title").asText());
        assertTrue(seen.get("cameras").isNull(), "Lyrics Only is never sent the cameras");
        assertEquals("P", manager.slice("monitors") == null ? null : ((Map<?, ?>) ((Map<?, ?>) manager.slice("monitors")).get("preview")).get("title"));
    }

    @Test
    void everyConnectedDeviceSeesPreviewAndProgramAndHearsWhatChanges() throws Exception {
        Device a = pair("A", RemotePermission.defaults());
        a.next("welcome");
        Device b = pair("B", EnumSet.of(RemotePermission.TAKE));
        b.next("welcome");

        desk.state.put("monitors", Map.of("preview", Map.of("title", "P"), "program", Map.of("title", "Taken by B"), "canTake", false));
        b.command("c", "TAKE", "{}");
        for (Device d : List.of(a, b)) {
            JsonNode monitors = d.slice("monitors");
            while (monitors.isNull()) { // sent on connecting, before the desktop's first state
                monitors = d.slice("monitors");
            }
            assertEquals("Taken by B", monitors.path("program").path("title").asText(), "every remote sees what one did");
        }
    }

    @Test
    void losingAPermissionTakesTheStateAwayAtOnce() throws Exception {
        manager.publishState();
        Device d = pair("A", RemotePermission.defaults());
        JsonNode welcome = d.next("welcome");
        assertEquals("Amazing Grace", d.slice("lyrics").path("title").asText());

        manager.setPermission(welcome.path("deviceId").asText(), RemotePermission.LYRICS, false);
        assertEquals("[]", d.next("access").path("permissions").toString());
        assertTrue(d.slice("lyrics").isNull(), "lyrics are no longer sent");
        d.command("c", "LYRICS_NEXT", "{}");
        assertEquals("no_permission", d.next("result").path("code").asText());
        assertTrue(desk.ran.isEmpty());

        manager.setPermission(welcome.path("deviceId").asText(), RemotePermission.LYRICS, true);
        assertEquals("[\"LYRICS\"]", d.next("access").path("permissions").toString());
        assertEquals("Amazing Grace", d.slice("lyrics").path("title").asText());
    }

    @Test
    void lockKeepsDevicesWatchingButRefusesCommands() throws Exception {
        manager.publishState();
        Device d = pair("A", RemotePermission.defaults());
        d.next("welcome");
        manager.setLocked(true);
        assertTrue(d.next("access").path("locked").asBoolean());
        assertEquals("Amazing Grace", d.slice("lyrics").path("title").asText(), "they can continue viewing");

        d.command("c", "LYRICS_NEXT", "{}");
        assertEquals("locked", d.next("result").path("code").asText());
        assertTrue(desk.ran.isEmpty());

        manager.setLocked(false);
        assertFalse(d.next("access").path("locked").asBoolean());
    }

    @Test
    void disableClosesEveryConnectionAndRefusesNewOnes() throws Exception {
        Device d = pair("A", RemotePermission.defaults());
        d.next("welcome");
        manager.setDisabled(true);
        assertEquals("disabled", d.next("closed").path("reason").asText());
        assertTrue(d.closed.await(5, TimeUnit.SECONDS));
        assertFalse(manager.devices().getFirst().session());

        Device late = hello("B");
        assertEquals("disabled", late.next("rejected").path("code").asText());
        assertTrue(manager.running(), "the server itself keeps running");
    }

    @Test
    void disconnectClosesTheConnectionAndTheDeviceMayReturn() throws Exception {
        Device d = pair("A", RemotePermission.defaults());
        JsonNode welcome = d.next("welcome");
        manager.disconnect(welcome.path("deviceId").asText());
        assertEquals("disconnected", d.next("closed").path("reason").asText());
        assertTrue(d.closed.await(5, TimeUnit.SECONDS));
        assertFalse(manager.devices().getFirst().session());

        Device again = new Device().open(null);
        again.send("{\"type\":\"hello\",\"protocol\":2,\"deviceToken\":\"" + welcome.path("deviceToken").asText() + "\"}");
        again.next("welcome");
        assertTrue(manager.devices().getFirst().session());
    }

    @Test
    void forgetClosesTheConnectionAndTheTokenStopsWorking() throws Exception {
        Device d = pair("A", RemotePermission.defaults());
        JsonNode welcome = d.next("welcome");
        manager.forget(welcome.path("deviceId").asText());
        assertEquals("forgotten", d.next("closed").path("reason").asText());

        Device again = new Device().open(null);
        again.send("{\"type\":\"hello\",\"protocol\":2,\"deviceToken\":\"" + welcome.path("deviceToken").asText() + "\"}");
        assertEquals("pairing_required", again.next().path("code").asText());
    }

    @Test
    void aClosedConnectionEndsTheSession() throws Exception {
        Device d = pair("A", RemotePermission.defaults());
        d.next("welcome");
        assertTrue(manager.devices().getFirst().session());
        d.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        assertTrue(d.closed.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 100 && manager.devices().getFirst().session(); i++) {
            Thread.sleep(50);
        }
        assertFalse(manager.devices().getFirst().session());
    }

    @Test
    void otherWebsitesAreRefused() throws Exception {
        assertTrue(rawStatus("192.168.1.15:" + port).contains("200"));
        assertTrue(rawStatus("localhost:" + port).contains("200"));
        assertTrue(rawStatus("evil.example").contains("403"), "DNS rebinding: a site's own domain must not reach the server");

        assertThrows(ExecutionException.class, () -> new Device().open("http://evil.example"), "a page on another website");
        assertThrows(ExecutionException.class, () -> new Device().open("http://127.0.0.1:9999"), "another port on this PC");
        assertThrows(ExecutionException.class, () -> new Device().open("http://192.168.1.15:5173"), "the same port on another PC");

        assertNotNull(new Device().open("http://127.0.0.1:" + port).socket, "this server's own page");
        assertNotNull(new Device().open("http://127.0.0.1:" + RemoteManager.DEFAULT_APP_PORT).socket, "the remote app on this PC");
    }

    @Test
    void turningOffStopsListeningAndEndsSessions() throws Exception {
        Device d = pair("A", RemotePermission.defaults());
        d.next("welcome");
        manager.setEnabled(false);
        assertFalse(manager.running());
        assertThrows(IOException.class, () -> get("/api/status"));
        assertTrue(d.closed.await(5, TimeUnit.SECONDS), "connected devices are told");
        assertEquals(1, manager.devices().size());
        assertFalse(manager.devices().getFirst().session());
    }

    @Test
    void everyStartMakesANewQrCode() {
        String first = manager.pairingToken();
        manager.setEnabled(false);
        manager.setEnabled(true);
        assertFalse(first.equals(manager.pairingToken()));
    }

    @Test
    void aBusyPortIsReportedAndAnotherPortWorks() throws Exception {
        manager.setEnabled(false);
        try (ServerSocket taken = new ServerSocket(port)) {
            manager.setEnabled(true);
            assertFalse(manager.running());
            assertTrue(manager.enabled(), "the operator's choice stays ON");
            assertNotNull(manager.problem());
            assertTrue(manager.problem().contains(String.valueOf(taken.getLocalPort())), manager.problem());

            port = freePort();
            manager.setPort(port);
            assertTrue(manager.running());
            assertNull(manager.problem());
            assertEquals(200, get("/api/status").statusCode());
        }
    }

    @Test
    void changingThePortMovesARunningServer() throws Exception {
        int old = port;
        port = freePort();
        manager.setPort(port);
        assertTrue(manager.running());
        assertEquals(200, get("/api/status").statusCode());
        port = old;
        assertThrows(IOException.class, () -> get("/api/status"));
    }
}
