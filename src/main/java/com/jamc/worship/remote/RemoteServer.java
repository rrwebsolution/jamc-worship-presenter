package com.jamc.worship.remote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server phones and tablets connect to. It only runs on its own threads, so a slow or misbehaving device can
 * never hold up the presenter.
 *
 * <pre>
 *   GET /…            the bundled remote app (or a short page when none is bundled)
 *   GET /api/status   is the server there, is it locked
 *   WS  /ws           JSON messages:
 *
 *   device → {"type":"hello","protocol":2,"deviceToken":…,"pairingToken":…,"name":…}   must come first
 *   server ← {"type":"pending"}                 a new device waits for the operator
 *   server ← {"type":"welcome","deviceId":…,"deviceToken":… (new devices only),"name":…,"role":…,"permissions":[…],"locked":…}
 *          | {"type":"rejected","code":"pairing_required"|"new_devices_off"|"denied"|"disabled"|"too_many_devices"|"busy"|"bad_request"}, then closes
 *   device → {"type":"ping"}                    every ~10 s; server ← {"type":"pong"}
 *   device → {"type":"command","requestId":…,"command":"TAKE","payload":{…}}
 *   device → {"type":"watch","pictures":true|false}   a screen showing the monitors asks for their pictures
 *   device → {"type":"query","requestId":…,"query":"SONGS","params":{…}}   a list from the library
 *   server ← {"type":"result","requestId":…,"ok":…,"code":…,"data":…}
 *   server ← {"type":"access",…}                when permissions or the lock change
 *   server ← {"type":"state","slice":"lyrics","value":{…}|null}   null = nothing to show, or not allowed
 *   server ← {"type":"closed","reason":"disconnected"|"forgotten"|"disabled"}, then closes
 * </pre>
 * Permissions are enforced here, whatever the remote's screen shows: a slice a device may not see is never sent to
 * it, and a command it may not run is refused.
 */
public final class RemoteServer {

    private static final Logger log = LoggerFactory.getLogger(RemoteServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PROTOCOL = 2;
    private static final int MAX_MESSAGE_CHARS = 4096;
    /** Longer than the devices' ping interval, so only a dead connection reaches it. */
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);
    /** An IP address or localhost. A site's own domain name here means DNS rebinding, so it is refused. */
    private static final Pattern ALLOWED_HOST = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|localhost|\\[[0-9a-fA-F:.]+])(:\\d+)?$");
    private static final Pattern HOST_AND_PORT = Pattern.compile("^(.*?)(?::(\\d+))?$");
    private static final String APP_RESOURCE = "/remote-app";
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"),
            Map.entry("json", "application/json"), Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("woff2", "font/woff2"), Map.entry("ico", "image/x-icon"));
    private static final String PLACEHOLDER_PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>LiveCat Remote</title></head>
            <body style="font-family:system-ui,sans-serif;background:#0B1220;color:#F8FAFC;text-align:center;padding:48px 24px">
            <h1>LiveCat Multimedia</h1>
            <p>This device reached the Remote Server.</p>
            <p style="color:#94A3B8">To use the remote, scan the QR code in Settings → Remote Control.</p>
            </body></html>
            """;

    /** The pictures of Preview and Program. Everyone connected may see what is on the monitors. */
    static final String PICTURES = "pictures";
    /** Slices of state, and the permission each needs (none = every connected device). */
    static final Map<String, RemotePermission> SLICES = sliceTable();

    private static Map<String, RemotePermission> sliceTable() {
        Map<String, RemotePermission> t = new LinkedHashMap<>();
        t.put("monitors", null);
        t.put(PICTURES, null);
        t.put("lyrics", RemotePermission.LYRICS);
        t.put("bible", RemotePermission.BIBLE);
        t.put("presentation", RemotePermission.PRESENTATION);
        t.put("service", RemotePermission.SERVICE);
        t.put("scenes", RemotePermission.SCENES);
        t.put("cameras", RemotePermission.CAMERAS);
        t.put("media", RemotePermission.MEDIA);
        t.put("projector", RemotePermission.BLACK_PROJECTOR);
        t.put("stream", RemotePermission.STREAM_MONITOR);
        t.put("recording", RemotePermission.RECORDING_MONITOR);
        return t;
    }

    private final RemoteManager manager;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private Server jetty;

    RemoteServer(RemoteManager manager) {
        this.manager = manager;
    }

    /** True when the remote app was built into this application. */
    static boolean hasBundledApp() {
        return RemoteServer.class.getResource(APP_RESOURCE + "/index.html") != null;
    }

    void start(int port) throws IOException {
        QueuedThreadPool threads = new QueuedThreadPool(32, 4);
        threads.setName("Remote-Server");
        threads.setDaemon(true);
        jetty = new Server(threads);
        HttpConfiguration http = new HttpConfiguration();
        http.setSendServerVersion(false);
        ServerConnector connector = new ServerConnector(jetty, new HttpConnectionFactory(http));
        connector.setPort(port);
        jetty.addConnector(connector);

        WebSocketUpgradeHandler sockets = WebSocketUpgradeHandler.from(jetty, container -> {
            container.setIdleTimeout(IDLE_TIMEOUT);
            container.setMaxTextMessageSize(MAX_MESSAGE_CHARS);
            container.addMapping("/ws", (request, response, callback) ->
                    new Connection(Request.getRemoteAddr(request), request.getHeaders().get(HttpHeader.USER_AGENT)));
        });
        sockets.setHandler(new Pages());
        jetty.setHandler(new SameSiteOnly(sockets));
        try {
            jetty.start();
        } catch (IOException e) {
            stop();
            throw e;
        } catch (Exception e) {
            stop();
            throw new IOException(e);
        }
    }

    void stop() {
        try {
            jetty.stop();
        } catch (Exception e) {
            log.warn("Remote server did not stop cleanly", e);
        }
        connections.clear();
    }

    // ------------------------------------------------------------------ pushes from the manager

    /** Permissions or the lock changed: every device hears what it may do now, and what it may no longer see. */
    void pushAccess() {
        connections.forEach(Connection::sendAccessAndState);
    }

    /** Whether any device is showing the monitors, so they need not be drawn when none is. */
    boolean anyoneWatchingPictures() {
        return connections.stream().anyMatch(c -> c.watchingPictures);
    }

    void pushSlice(String name) {
        connections.forEach(c -> c.sendSlice(name));
    }

    void closeDevice(String deviceId, String reason) {
        for (Connection c : connections) {
            c.closeIfDevice(deviceId, reason);
        }
    }

    void closeAll(String reason) {
        connections.forEach(c -> c.closeAny(reason));
    }

    // ------------------------------------------------------------------ who may connect

    /** Refuses other websites: DNS rebinding through Host, and a page elsewhere opening a connection through Origin. */
    private final class SameSiteOnly extends Handler.Wrapper {
        SameSiteOnly(Handler next) {
            super(next);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String host = request.getHeaders().get(HttpHeader.HOST);
            if (host == null || !ALLOWED_HOST.matcher(host).matches() || !allowedOrigin(request.getHeaders().get(HttpHeader.ORIGIN), host)) {
                sendJson(response, 403, Map.of("error", "forbidden"), callback);
                return true;
            }
            return super.handle(request, response, callback);
        }
    }

    /**
     * Pages served by this server, or by the remote app's own server on the same computer (browsers always send
     * Origin with a WebSocket, and in development the app comes from another port).
     */
    private boolean allowedOrigin(String origin, String host) {
        if (origin == null) {
            return true;
        }
        try {
            String authority = URI.create(origin).getAuthority();
            if (authority == null) {
                return false;
            }
            Matcher o = HOST_AND_PORT.matcher(authority);
            Matcher h = HOST_AND_PORT.matcher(host);
            if (!o.matches() || !h.matches() || !o.group(1).equalsIgnoreCase(h.group(1))) {
                return false;
            }
            return authority.equalsIgnoreCase(host) || String.valueOf(manager.appPort()).equals(o.group(2));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ HTTP pages

    private final class Pages extends Handler.Abstract.NonBlocking {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getPath();
            if (!"GET".equals(request.getMethod())) {
                sendJson(response, 405, Map.of("error", "method_not_allowed"), callback);
            } else if (path.equals("/api/status")) {
                sendJson(response, 200, Map.of("app", "LiveCat Multimedia", "protocol", PROTOCOL, "locked", manager.locked()), callback);
            } else if (path.startsWith("/api/") || path.equals("/ws")) {
                sendJson(response, 404, Map.of("error", "not_found"), callback);
            } else {
                serveApp(path, response, callback);
            }
            return true;
        }
    }

    /** A file of the bundled app. Any other path is one of the app's own pages, so it gets the app (it routes itself). */
    private static void serveApp(String path, Response response, Callback callback) throws IOException {
        if (!hasBundledApp()) {
            send(response, 200, "text/html; charset=utf-8", PLACEHOLDER_PAGE.getBytes(StandardCharsets.UTF_8), "no-store", callback);
            return;
        }
        String extension = path.substring(path.lastIndexOf('/') + 1).contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
        byte[] file = path.contains("..") || path.contains("\\") || !CONTENT_TYPES.containsKey(extension) ? null : resource(path);
        if (file != null) {
            // built files carry a hash in their name, so they can be kept; everything else must stay current
            String cache = path.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache";
            send(response, 200, CONTENT_TYPES.get(extension), file, cache, callback);
        } else if (!extension.isEmpty()) {
            sendJson(response, 404, Map.of("error", "not_found"), callback);
        } else {
            send(response, 200, CONTENT_TYPES.get("html"), resource("/index.html"), "no-cache", callback);
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = RemoteServer.class.getResourceAsStream(APP_RESOURCE + path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void sendJson(Response response, int status, Map<String, ?> body, Callback callback) throws IOException {
        send(response, status, "application/json", JSON.writeValueAsBytes(body), "no-store", callback);
    }

    private static void send(Response response, int status, String contentType, byte[] body, String cache, Callback callback) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, cache);
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        response.write(true, ByteBuffer.wrap(body), callback);
    }

    // ------------------------------------------------------------------ one device connection

    /**
     * One open WebSocket. Sending happens under this object's lock, so a device always hears its welcome before
     * any push. The order is always this lock, then the manager's; the manager never calls here holding its own.
     * Public only because Jetty calls these listener methods through a public lookup.
     */
    public final class Connection implements Session.Listener.AutoDemanding {
        private final String address;
        private final String userAgent;
        private Session session;
        /** Null until the device is accepted, and again once the operator closed it. */
        private String deviceId;
        private String pendingRequest;
        private volatile boolean watchingPictures;

        Connection(String address, String userAgent) {
            this.address = address;
            this.userAgent = userAgent;
        }

        @Override
        public synchronized void onWebSocketOpen(Session session) {
            this.session = session;
            connections.add(this);
        }

        @Override
        public void onWebSocketText(String text) {
            try {
                JsonNode message = JSON.readTree(text);
                switch (message.path("type").asText("")) {
                    case "hello" -> hello(message);
                    case "ping" -> ping();
                    case "command" -> command(message);
                    case "query" -> query(message);
                    case "watch" -> watch(message.path("pictures").asBoolean(false));
                    default -> reject("bad_request");
                }
            } catch (JsonProcessingException e) {
                reject("bad_request");
            } catch (RuntimeException e) {
                log.warn("Remote message failed", e);
                reject("bad_request");
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            String id;
            String request;
            synchronized (this) {
                connections.remove(this);
                id = deviceId;
                deviceId = null;
                request = pendingRequest;
                pendingRequest = null;
            }
            if (id != null) {
                manager.connectionClosed(id);
            }
            if (request != null) {
                manager.cancelPairing(request);
            }
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            log.debug("Remote connection error", cause);
        }

        private synchronized void hello(JsonNode message) {
            if (deviceId != null || pendingRequest != null) {
                return;
            }
            if (message.path("protocol").asInt(-1) != PROTOCOL) {
                reject("bad_request");
                return;
            }
            answer(manager.connect(message.path("deviceToken").asText(null), message.path("pairingToken").asText(null),
                    message.path("name").asText(null), address, userAgent, this::answer));
        }

        /** The manager's answer: at once, or later when the operator decided about a new device. */
        private synchronized void answer(RemoteManager.ConnectResult result) {
            switch (result.status()) {
                case PENDING -> {
                    pendingRequest = result.requestId();
                    send(Map.of("type", "pending"));
                }
                case PAIRING_REQUIRED -> reject("pairing_required");
                case NEW_DEVICES_OFF -> reject("new_devices_off");
                case DISABLED -> reject("disabled");
                case FULL -> reject("too_many_devices");
                case BUSY -> reject("busy");
                case DENIED -> {
                    pendingRequest = null;
                    reject("denied");
                }
                case OK -> {
                    pendingRequest = null;
                    deviceId = result.device().id();
                    Map<String, Object> welcome = access(result.device(), "welcome");
                    welcome.put("deviceId", deviceId);
                    welcome.put("protocol", PROTOCOL);
                    if (result.newToken() != null) {
                        welcome.put("deviceToken", result.newToken());
                    }
                    send(welcome);
                    SLICES.keySet().forEach(this::sendSlice);
                }
            }
        }

        private synchronized void ping() {
            if (pendingRequest != null) {
                send(Map.of("type", "pong"));
                return;
            }
            if (deviceId == null) {
                reject("bad_request");
                return;
            }
            manager.touch(deviceId, address);
            send(Map.of("type", "pong"));
        }

        private synchronized void command(JsonNode message) {
            if (deviceId == null) {
                reject("bad_request");
                return;
            }
            String requestId = message.path("requestId").asText("");
            RemoteCommand command = RemoteCommand.parse(message.path("command").asText());
            if (command == null) {
                sendResult(requestId, "not_supported");
                return;
            }
            manager.execute(deviceId, command, message.path("payload"), code -> sendResult(requestId, code));
        }

        /** Only a screen showing the monitors asks for their pictures: they are heavy, and a phone on Wi-Fi feels it. */
        private synchronized void watch(boolean on) {
            if (deviceId == null) {
                reject("bad_request");
                return;
            }
            watchingPictures = on;
            if (on) {
                sendSlice(PICTURES);
            }
        }

        /** Not under this object's lock: a library read can take a while, and pushes to this device must not wait for it. */
        private void query(JsonNode message) {
            String id;
            synchronized (this) {
                id = deviceId;
            }
            if (id == null) {
                reject("bad_request");
                return;
            }
            String requestId = message.path("requestId").asText("");
            RemoteQuery query = RemoteQuery.parse(message.path("query").asText());
            if (query == null) {
                sendResult(requestId, "not_supported");
                return;
            }
            try {
                sendResult(requestId, null, manager.query(id, query, message.path("params")));
            } catch (SecurityException e) {
                sendResult(requestId, e.getMessage());
            } catch (Exception e) {
                log.warn("Remote query {} failed", query, e);
                sendResult(requestId, "failed");
            }
        }

        private void sendResult(String requestId, String code) {
            sendResult(requestId, code, null);
        }

        private synchronized void sendResult(String requestId, String code, Object data) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "result");
            result.put("requestId", requestId);
            result.put("ok", code == null);
            result.put("code", code);
            if (data != null) {
                result.put("data", data);
            }
            send(result);
        }

        synchronized void sendAccessAndState() {
            RemoteDevice device = deviceId == null ? null : manager.device(deviceId);
            if (device != null) {
                send(access(device, "access"));
                SLICES.keySet().forEach(this::sendSlice);
            }
        }

        /** Sends a slice, or null in its place when this device may not see it. */
        synchronized void sendSlice(String name) {
            RemoteDevice device = deviceId == null ? null : manager.device(deviceId);
            if (device == null || (PICTURES.equals(name) && !watchingPictures)) {
                return;
            }
            RemotePermission needed = SLICES.get(name);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "state");
            message.put("slice", name);
            message.put("value", needed == null || device.permissions().contains(needed) ? manager.slice(name) : null);
            send(message);
        }

        synchronized void closeIfDevice(String id, String reason) {
            if (id.equals(deviceId)) {
                closeAny(reason);
            }
        }

        synchronized void closeAny(String reason) {
            deviceId = null; // the manager already ended this session
            pendingRequest = null;
            if (session != null && session.isOpen()) {
                send(Map.of("type", "closed", "reason", reason));
                session.close(StatusCode.NORMAL, reason, org.eclipse.jetty.websocket.api.Callback.NOOP);
            }
        }

        private synchronized void reject(String code) {
            if (session != null && session.isOpen()) {
                send(Map.of("type", "rejected", "code", code));
                session.close(StatusCode.POLICY_VIOLATION, code, org.eclipse.jetty.websocket.api.Callback.NOOP);
            }
        }

        private Map<String, Object> access(RemoteDevice d, String type) {
            List<String> permissions = new ArrayList<>();
            d.permissions().forEach(p -> permissions.add(p.name()));
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", type);
            message.put("name", d.name());
            message.put("role", RemoteRole.of(d.permissions()).label());
            message.put("permissions", permissions);
            message.put("locked", manager.locked());
            return message;
        }

        private void send(Map<String, ?> message) {
            try {
                session.sendText(JSON.writeValueAsString(message), org.eclipse.jetty.websocket.api.Callback.NOOP);
            } catch (JsonProcessingException | RuntimeException e) {
                log.debug("Remote message not sent", e);
            }
        }
    }
}
