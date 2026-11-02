package com.jamc.worship.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.jamc.worship.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Settings → Remote Control: the local remote server, the devices it knows and what each may do. Requests
 * arrive on the server's threads and the operator's changes on the UI thread, so all state is guarded by this
 * object, and it never calls the server while holding that lock. Listeners may be called on any thread.
 *
 * <p>A new device presents the pairing token from the QR code, then waits until the operator allows it; it then
 * receives its own secret token and is trusted from then on, with the permissions it was given. Disconnect ends its
 * connection; Forget removes it, so it has to pair again.
 */
public final class RemoteManager {

    private static final Logger log = LoggerFactory.getLogger(RemoteManager.class);

    public static final String KEY_ENABLED = "remote.enabled";
    public static final String KEY_PORT = "remote.port";
    public static final String KEY_LOCKED = "remote.locked";
    /** Disable All Remotes: every connection is closed and none is accepted until it is turned off. */
    public static final String KEY_DISABLED = "remote.disabled";
    public static final String KEY_ALLOW_NEW = "remote.allowNewDevices";
    public static final String KEY_DEFAULT_PERMISSIONS = "remote.defaultPermissions";
    /** The network address chosen by the operator; none = auto-detect. */
    public static final String KEY_ADDRESS = "remote.address";
    /** Where the remote app runs in development, when this app does not serve a built one. */
    public static final String KEY_APP_PORT = "remote.appPort";

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_APP_PORT = 5173;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    /** A device silent for this long no longer shows as connected. */
    static final Duration ONLINE_WINDOW = Duration.ofSeconds(30);
    /** So something flooding the port cannot fill the list and the file without limit. */
    static final int MAX_DEVICES = 50;
    static final int MAX_PENDING = 8;
    private static final long TICK_SECONDS = 5;
    /** How often the desktop's state is compared with what remotes last heard. */
    private static final long STATE_TICK_MS = 300;
    private static final int PICTURE_EVERY_TICKS = 2;

    /** The answer to "may this device do this now?". */
    public enum Access { ALLOWED, UNKNOWN_DEVICE, NOT_CONNECTED, LOCKED, NO_PERMISSION }

    /** A device as the Settings page shows it. */
    public record DeviceView(RemoteDevice device, boolean session, boolean online) {
    }

    /** A device waiting for the operator's answer. */
    public record PairingRequest(String id, String name, String address) {
    }

    /** What a device connecting is told. */
    enum ConnectStatus { OK, PENDING, PAIRING_REQUIRED, NEW_DEVICES_OFF, DISABLED, FULL, BUSY, DENIED }

    /**
     * @param newToken only when the device was just created: the one time its secret leaves this class
     */
    record ConnectResult(ConnectStatus status, RemoteDevice device, String newToken, String requestId) {
        static ConnectResult of(ConnectStatus status) {
            return new ConnectResult(status, null, null, null);
        }
    }

    private record Pending(PairingRequest request, String userAgent, Consumer<ConnectResult> answer) {
    }

    private final AppConfig config;
    private final RemoteDeviceStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, RemoteDevice> devices = new LinkedHashMap<>();
    /** Open connections per device (a phone may have the remote open in two tabs). */
    private final Map<String, Integer> sessions = new HashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    /** Held while the server starts or stops. Server threads never take it, so stopping cannot wait on them. */
    private final Object lifecycle = new Object();
    private final AtomicBoolean stateTickQueued = new AtomicBoolean();

    private RemoteServer server;
    private ScheduledExecutorService ticker;
    private String problem;
    private List<LocalNetwork.Address> addresses = List.of();
    private Set<String> onlineLastTick = Set.of();
    private String pairingToken = randomToken(16);
    private RemoteDesk desk;
    private Map<String, Object> slices = new HashMap<>();
    private int stateTicks;

    public RemoteManager(AppConfig config, Path devicesFile) {
        this(config, devicesFile, Clock.systemUTC());
    }

    RemoteManager(AppConfig config, Path devicesFile, Clock clock) {
        this.config = config;
        this.store = new RemoteDeviceStore(devicesFile);
        this.clock = clock;
        store.load().forEach(d -> devices.put(d.id(), d));
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }

    /** The desktop behind the remotes. */
    public synchronized void setDesk(RemoteDesk desk) {
        this.desk = desk;
    }

    // ------------------------------------------------------------------ server

    public boolean enabled() {
        return config.getBoolean(KEY_ENABLED, false);
    }

    public synchronized boolean running() {
        return server != null;
    }

    /** Why the server is on but not running, or null. */
    public synchronized String problem() {
        return problem;
    }

    public int port() {
        return validPort(config.get(KEY_PORT, null), DEFAULT_PORT);
    }

    int appPort() {
        return validPort(config.get(KEY_APP_PORT, null), DEFAULT_APP_PORT);
    }

    private static int validPort(String value, int fallback) {
        try {
            int p = value == null ? fallback : Integer.parseInt(value.strip());
            return p >= MIN_PORT && p <= MAX_PORT ? p : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** At startup: brings the server back when the operator left it on. Blocks briefly; call off the UI thread. */
    public void startIfEnabled() {
        if (enabled()) {
            refreshNetwork();
            startServer();
            fire();
        }
    }

    /** Remote Server ON / OFF, remembered. Blocks briefly; call off the UI thread. */
    public void setEnabled(boolean on) {
        config.set(KEY_ENABLED, String.valueOf(on));
        config.save();
        refreshNetwork();
        synchronized (lifecycle) {
            setProblem(null);
            if (on) {
                startServer();
            } else {
                stopServer();
            }
        }
        fire();
    }

    /** Changes the port, restarting a running server. Blocks briefly; call off the UI thread. */
    public void setPort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        config.set(KEY_PORT, String.valueOf(port));
        config.save();
        synchronized (lifecycle) {
            stopServer();
            setProblem(null);
            if (enabled()) {
                startServer();
            }
        }
        fire();
    }

    /** At exit: stops listening but leaves the ON / OFF choice as it is. */
    public void shutdown() {
        stopServer();
    }

    private synchronized void setProblem(String text) {
        problem = text;
    }

    private void startServer() {
        synchronized (lifecycle) {
            if (running()) {
                return;
            }
            int port = port();
            RemoteServer s = new RemoteServer(this);
            synchronized (this) {
                pairingToken = randomToken(16); // a QR code from an earlier run no longer pairs new devices
            }
            try {
                s.start(port);
            } catch (IOException e) {
                if (e.getCause() instanceof BindException || e instanceof BindException) {
                    setProblem("Port " + port + " is already in use by another program. Choose another port.");
                    log.warn("Remote server: port {} is in use", port);
                } else {
                    setProblem("Could not start: " + e.getMessage());
                    log.error("Remote server could not start", e);
                }
                return;
            }
            synchronized (this) {
                server = s;
                problem = null;
            }
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Remote-Tick");
                t.setDaemon(true);
                return t;
            });
            ticker.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
            ticker.scheduleWithFixedDelay(this::queueStateTick, STATE_TICK_MS, STATE_TICK_MS, TimeUnit.MILLISECONDS);
            log.info("Remote server listening on port {}", port);
        }
    }

    private void stopServer() {
        synchronized (lifecycle) {
            RemoteServer s;
            List<Pending> waiting;
            synchronized (this) {
                s = server;
                server = null;
                sessions.clear();
                waiting = new ArrayList<>(pending.values());
                pending.clear();
                saveDevices(); // heartbeats only update lastSeen in memory
            }
            waiting.forEach(p -> p.answer().accept(ConnectResult.of(ConnectStatus.DENIED)));
            if (s == null) {
                return;
            }
            ticker.shutdownNow();
            ticker = null;
            s.stop();
            log.info("Remote server stopped");
        }
    }

    /** While running: follows a changed IP address and devices going silent. */
    private void tick() {
        try {
            refreshNetwork();
            Set<String> online = new HashSet<>();
            boolean changed;
            synchronized (this) {
                devices.values().stream().filter(this::isOnline).forEach(d -> online.add(d.id()));
                changed = !online.equals(onlineLastTick);
                onlineLastTick = online;
            }
            if (changed) {
                fire();
            }
        } catch (RuntimeException e) {
            log.warn("Remote server tick failed", e);
        }
    }

    private synchronized RemoteServer server() {
        return server;
    }

    // ------------------------------------------------------------------ the desktop's state → remotes

    /** Only while a device is connected, and never more than one waiting: remotes must not cost the service anything. */
    private void queueStateTick() {
        boolean anyone;
        synchronized (this) {
            anyone = desk != null && !sessions.isEmpty();
        }
        if (anyone && stateTickQueued.compareAndSet(false, true)) {
            EventQueue.invokeLater(() -> {
                stateTickQueued.set(false);
                publishState();
            });
        }
    }

    /** UI thread: compares the desktop with what remotes last heard, and sends only what changed. */
    void publishState() {
        RemoteDesk d;
        boolean pictures;
        synchronized (this) {
            d = desk;
            pictures = ++stateTicks % PICTURE_EVERY_TICKS == 0 && server != null && server.anyoneWatchingPictures();
        }
        if (d == null) {
            return;
        }
        try {
            store(d.snapshot());
            if (pictures) {
                BufferedImage[] images = d.monitorPictures();
                ScheduledExecutorService t = ticker;
                if (images != null && t != null) {
                    t.execute(() -> encodePictures(images));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Remote state failed", e);
        }
    }

    private void encodePictures(BufferedImage[] images) {
        Map<String, Object> pictures = new LinkedHashMap<>();
        pictures.put("preview", jpeg(images[0]));
        pictures.put("program", jpeg(images[1]));
        store(Map.of(RemoteServer.PICTURES, pictures));
    }

    private static String jpeg(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16_000);
            ImageIO.write(image, "jpg", out);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    private void store(Map<String, Object> fresh) {
        List<String> changed = new ArrayList<>();
        synchronized (this) {
            Map<String, Object> next = new HashMap<>(slices);
            fresh.forEach((name, value) -> {
                if (!Objects.equals(value, slices.get(name))) {
                    changed.add(name);
                    next.put(name, value);
                }
            });
            slices = next;
            if (changed.contains("monitors")) {
                stateTicks = PICTURE_EVERY_TICKS - 1; // Preview or Program changed: the pictures follow at the next tick
            }
        }
        RemoteServer s = server();
        if (s != null) {
            changed.forEach(s::pushSlice);
        }
    }

    /** The latest of one slice. */
    synchronized Object slice(String name) {
        return slices.get(name);
    }

    /** Runs a device's command on the UI thread; {@code done} gets null, or why it did not happen. */
    void execute(String deviceId, RemoteCommand command, JsonNode payload, Consumer<String> done) {
        Set<RemotePermission> permissions;
        RemoteDesk d;
        synchronized (this) {
            for (RemotePermission p : command.required()) {
                Access access = authorize(deviceId, p);
                if (access != Access.ALLOWED) {
                    done.accept(access.name().toLowerCase());
                    return;
                }
            }
            RemoteDevice device = devices.get(deviceId);
            permissions = device.permissions();
            d = desk;
        }
        if (d == null) {
            done.accept("not_available");
            return;
        }
        EventQueue.invokeLater(() -> {
            String result;
            try {
                result = d.execute(command, payload, permissions);
            } catch (RuntimeException e) {
                log.warn("Remote command {} failed", command, e);
                result = "failed";
            }
            publishState(); // remotes see the result at once, not at the next tick
            done.accept(result);
        });
    }

    /**
     * A list from the library, for a device allowed that kind of content. It only reads, so it also works while
     * commands are locked. Call on a server thread.
     *
     * @throws SecurityException with the reason when the device may not ask
     */
    Object query(String deviceId, RemoteQuery query, JsonNode params) throws Exception {
        RemoteDesk d;
        synchronized (this) {
            RemoteDevice device = devices.get(deviceId);
            if (device == null || !sessions.containsKey(deviceId)) {
                throw new SecurityException("not_connected");
            }
            if (!device.permissions().contains(query.required())) {
                throw new SecurityException("no_permission");
            }
            d = desk;
        }
        if (d == null) {
            throw new SecurityException("not_available");
        }
        return d.query(query, params);
    }

    // ------------------------------------------------------------------ network address

    /** Looks at the network adapters again. Can take a moment: call off the UI thread. */
    public void refreshNetwork() {
        List<LocalNetwork.Address> found = LocalNetwork.detect();
        boolean changed;
        synchronized (this) {
            changed = !found.equals(addresses);
            addresses = found;
        }
        if (changed) {
            fire();
        }
    }

    public synchronized List<LocalNetwork.Address> addresses() {
        return addresses;
    }

    /** The operator's chosen address while this PC still has it, otherwise the best one detected, or null. */
    public synchronized LocalNetwork.Address selectedAddress() {
        String chosen = config.get(KEY_ADDRESS, null);
        return addresses.stream().filter(a -> a.ip().equals(chosen)).findFirst()
                .orElse(addresses.isEmpty() ? null : addresses.get(0));
    }

    public void selectAddress(LocalNetwork.Address address) {
        config.set(KEY_ADDRESS, address == null ? null : address.ip());
        config.save();
        fire();
    }

    /** For example {@code http://192.168.1.15:8080}, or null when this PC is on no network. */
    public String remoteAddress() {
        LocalNetwork.Address a = selectedAddress();
        return a == null ? null : "http://" + a.ip() + ":" + port();
    }

    /**
     * What the QR code opens. This server serves the remote app when one is bundled, so nothing else has to be
     * installed on the church computer; otherwise the development server. Null without a network.
     */
    public synchronized String pairingLink() {
        LocalNetwork.Address a = selectedAddress();
        if (a == null) {
            return null;
        }
        return RemoteServer.hasBundledApp()
                ? "http://" + a.ip() + ":" + port() + "/pair?token=" + pairingToken
                : "http://" + a.ip() + ":" + appPort() + "/pair?server=" + a.ip() + ":" + port() + "&token=" + pairingToken;
    }

    /** Regenerate Pairing QR: the shown code stops pairing new devices. Devices already paired are not affected. */
    public void newPairingToken() {
        synchronized (this) {
            pairingToken = randomToken(16);
        }
        fire();
    }

    synchronized String pairingToken() {
        return pairingToken;
    }

    // ------------------------------------------------------------------ operator actions

    public boolean locked() {
        return config.getBoolean(KEY_LOCKED, false);
    }

    /** Lock: devices stay connected and can watch, but every command is refused. Remembered across restarts. */
    public void setLocked(boolean on) {
        config.set(KEY_LOCKED, String.valueOf(on));
        config.save();
        log.info("Remotes {}", on ? "locked" : "unlocked");
        pushAccess();
        fire();
    }

    public boolean disabled() {
        return config.getBoolean(KEY_DISABLED, false);
    }

    /** Disable All Remotes: closes every connection and accepts none. The presentation carries on. Remembered. */
    public void setDisabled(boolean on) {
        List<Pending> waiting = List.of();
        config.set(KEY_DISABLED, String.valueOf(on));
        config.save();
        log.warn("All remotes {}", on ? "disabled" : "enabled again");
        if (on) {
            synchronized (this) {
                sessions.clear();
                waiting = new ArrayList<>(pending.values());
                pending.clear();
            }
            RemoteServer s = server();
            if (s != null) {
                s.closeAll("disabled");
            }
        }
        waiting.forEach(p -> p.answer().accept(ConnectResult.of(ConnectStatus.DISABLED)));
        fire();
    }

    public boolean allowNewDevices() {
        return config.getBoolean(KEY_ALLOW_NEW, true);
    }

    public void setAllowNewDevices(boolean on) {
        config.set(KEY_ALLOW_NEW, String.valueOf(on));
        config.save();
        fire();
    }

    /** What a new device starts with: Lyrics only until the operator changes it. */
    public Set<RemotePermission> defaultPermissions() {
        String saved = config.get(KEY_DEFAULT_PERMISSIONS, null);
        if (saved == null) {
            return RemotePermission.defaults();
        }
        EnumSet<RemotePermission> p = EnumSet.noneOf(RemotePermission.class);
        for (String name : saved.split(",")) {
            RemotePermission parsed = RemotePermission.parse(name.strip());
            if (parsed != null) {
                p.add(parsed);
            }
        }
        return p;
    }

    public void setDefaultPermissions(Set<RemotePermission> permissions) {
        config.set(KEY_DEFAULT_PERMISSIONS, permissions.stream().map(Enum::name).collect(Collectors.joining(",")));
        config.save();
        fire();
    }

    /** Connected devices first, then by name. */
    public synchronized List<DeviceView> devices() {
        return devices.values().stream()
                .map(d -> new DeviceView(d, sessions.containsKey(d.id()), isOnline(d)))
                .sorted(Comparator.comparing((DeviceView v) -> !v.session())
                        .thenComparing(v -> v.device().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Applies at once: the device's screen follows, and commands it may no longer run are refused. */
    public void setPermissions(String deviceId, Set<RemotePermission> permissions) {
        synchronized (this) {
            RemoteDevice d = devices.get(deviceId);
            if (d == null) {
                return;
            }
            devices.put(deviceId, d.withPermissions(permissions));
            saveDevices();
        }
        pushAccess();
        fire();
    }

    public void setPermission(String deviceId, RemotePermission permission, boolean allowed) {
        RemoteDevice d;
        synchronized (this) {
            d = devices.get(deviceId);
        }
        if (d != null) {
            EnumSet<RemotePermission> p = EnumSet.noneOf(RemotePermission.class);
            p.addAll(d.permissions());
            if (allowed) {
                p.add(permission);
            } else {
                p.remove(permission);
            }
            setPermissions(deviceId, p);
        }
    }

    public void rename(String deviceId, String name) {
        String clean = RemoteDevice.cleanName(name);
        synchronized (this) {
            RemoteDevice d = devices.get(deviceId);
            if (d == null || clean == null) {
                return;
            }
            devices.put(deviceId, d.seen(clean, d.address(), d.lastSeen()));
            saveDevices();
        }
        pushAccess();
        fire();
    }

    /** Ends the device's connection. It stays in the list with its permissions and may connect again. */
    public void disconnect(String deviceId) {
        synchronized (this) {
            sessions.remove(deviceId);
        }
        closeConnections(deviceId, "disconnected");
        fire();
    }

    /** Removes the device and its permissions. Its token stops working at once, and it has to pair again. */
    public void forget(String deviceId) {
        synchronized (this) {
            sessions.remove(deviceId);
            if (devices.remove(deviceId) != null) {
                saveDevices();
            }
        }
        closeConnections(deviceId, "forgotten");
        fire();
    }

    private void closeConnections(String deviceId, String reason) {
        RemoteServer s = server();
        if (s != null) {
            s.closeDevice(deviceId, reason);
        }
    }

    private void pushAccess() {
        RemoteServer s = server();
        if (s != null) {
            s.pushAccess();
        }
    }

    // ------------------------------------------------------------------ pairing approval

    /** Devices waiting for the operator's answer, oldest first. */
    public synchronized List<PairingRequest> pairingRequests() {
        return pending.values().stream().map(Pending::request).toList();
    }

    /** Allow: the device is trusted from now on, with these permissions. */
    public void approve(String requestId, Set<RemotePermission> permissions, String name) {
        Pending p;
        ConnectResult result;
        synchronized (this) {
            p = pending.remove(requestId);
            if (p == null) {
                return;
            }
            if (devices.size() >= MAX_DEVICES) {
                log.warn("Remote device refused: {} devices are already saved", MAX_DEVICES);
                result = ConnectResult.of(ConnectStatus.FULL);
            } else {
                String newToken = randomToken(32);
                String clean = RemoteDevice.cleanName(name);
                Instant now = clock.instant();
                RemoteDevice d = new RemoteDevice(UUID.randomUUID().toString(), clean == null ? p.request().name() : clean,
                        hash(newToken), p.request().address(), permissions, now, now);
                devices.put(d.id(), d);
                sessions.merge(d.id(), 1, Integer::sum);
                saveDevices();
                log.info("Remote device \"{}\" allowed", d.name());
                result = new ConnectResult(ConnectStatus.OK, d, newToken, null);
            }
        }
        p.answer().accept(result);
        fire();
    }

    /** Deny: the device is told, and nothing is saved about it. */
    public void deny(String requestId) {
        Pending p;
        synchronized (this) {
            p = pending.remove(requestId);
        }
        if (p != null) {
            log.info("Remote device \"{}\" denied", p.request().name());
            p.answer().accept(ConnectResult.of(ConnectStatus.DENIED));
            fire();
        }
    }

    /** The device gave up (closed the page) while waiting. */
    void cancelPairing(String requestId) {
        boolean removed;
        synchronized (this) {
            removed = pending.remove(requestId) != null;
        }
        if (removed) {
            fire();
        }
    }

    // ------------------------------------------------------------------ requests from devices (server threads)

    /**
     * A device joins or comes back. A known device only needs its own token. A new one needs the pairing token from
     * the QR code, and then waits: {@code answer} is called when the operator decides.
     */
    ConnectResult connect(String deviceToken, String pairing, String requestedName, String address, String userAgent,
                          Consumer<ConnectResult> answer) {
        ConnectResult result;
        synchronized (this) {
            if (disabled()) {
                return ConnectResult.of(ConnectStatus.DISABLED);
            }
            String name = RemoteDevice.cleanName(requestedName);
            RemoteDevice known = byToken(deviceToken);
            if (known != null) {
                RemoteDevice d = known.seen(name == null ? known.name() : name, address, clock.instant());
                devices.put(d.id(), d);
                sessions.merge(d.id(), 1, Integer::sum);
                saveDevices();
                result = new ConnectResult(ConnectStatus.OK, d, null, null);
            } else if (pairing == null || !MessageDigest.isEqual(pairing.getBytes(StandardCharsets.UTF_8),
                    pairingToken.getBytes(StandardCharsets.UTF_8))) {
                return ConnectResult.of(ConnectStatus.PAIRING_REQUIRED);
            } else if (!allowNewDevices()) {
                return ConnectResult.of(ConnectStatus.NEW_DEVICES_OFF);
            } else if (pending.size() >= MAX_PENDING) {
                return ConnectResult.of(ConnectStatus.BUSY);
            } else {
                String id = UUID.randomUUID().toString();
                String shown = name == null ? RemoteDevice.guessName(userAgent) : name;
                pending.put(id, new Pending(new PairingRequest(id, shown, address), userAgent, answer));
                log.info("Remote device \"{}\" from {} is waiting for approval", shown, address);
                result = new ConnectResult(ConnectStatus.PENDING, null, null, id);
            }
        }
        fire();
        return result;
    }

    /** A device's connection closed by itself (phone asleep, Wi-Fi gone, tab closed). */
    void connectionClosed(String deviceId) {
        synchronized (this) {
            sessions.computeIfPresent(deviceId, (id, n) -> n > 1 ? n - 1 : null);
        }
        fire();
    }

    /** A sign of life. */
    synchronized void touch(String deviceId, String address) {
        RemoteDevice d = devices.get(deviceId);
        if (d != null) {
            devices.put(deviceId, d.seen(d.name(), address, clock.instant()));
        }
    }

    /** The device, or null when it was forgotten. */
    synchronized RemoteDevice device(String deviceId) {
        return devices.get(deviceId);
    }

    /** Every remote action passes through this before it may run. */
    public synchronized Access authorize(String deviceId, RemotePermission permission) {
        RemoteDevice d = devices.get(deviceId);
        if (d == null) {
            return Access.UNKNOWN_DEVICE;
        }
        if (!sessions.containsKey(deviceId)) {
            return Access.NOT_CONNECTED;
        }
        if (locked()) {
            return Access.LOCKED;
        }
        return d.permissions().contains(permission) ? Access.ALLOWED : Access.NO_PERMISSION;
    }

    private boolean isOnline(RemoteDevice d) {
        return sessions.containsKey(d.id()) && Duration.between(d.lastSeen(), clock.instant()).compareTo(ONLINE_WINDOW) < 0;
    }

    private RemoteDevice byToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String h = hash(token);
        return devices.values().stream().filter(d -> d.tokenHash().equals(h)).findFirst().orElse(null);
    }

    private void saveDevices() {
        store.save(new ArrayList<>(devices.values()));
    }

    private String randomToken(int bytes) {
        byte[] secret = new byte[bytes];
        random.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
